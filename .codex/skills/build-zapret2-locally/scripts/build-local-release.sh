#!/usr/bin/env bash
# Build stable and development Zapret2 artifacts entirely on the local machine.
set -euo pipefail
umask 077

readonly DEFAULT_SIGNING_DIR="/home/codex-pve/.config/zapret2-signing"
readonly DEFAULT_SDK_DIR="/home/codex-pve/Android/Sdk"
readonly RELEASE_REPO="youtubediscord/magisk-zapret2"

usage() {
    cat >&2 <<'USAGE'
Usage: build-local-release.sh --channel stable|dev [options]

Options:
  --channel CHANNEL   stable: exact pushed main, publishable artifacts
                      dev: current worktree snapshot, local-only artifacts
  --repo PATH          Repository checkout (default: current directory)
  --signing-dir PATH   Durable production signing directory
  --payload-dir PATH   Reuse an already verified upstream payload
  --output PATH        New output directory
                       stable default: REPO/.artifacts/local-releases/vVERSION
                       dev default: REPO/.artifacts/dev-builds/vVERSION-dev.ID
                       Each default keeps only its latest successful channel build
  -h, --help           Show this help
USAGE
}

fail() {
    printf 'local Zapret2 build: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

show_failure_log() {
    local log_file="$1"
    grep -E '(^|[[:space:]])(FAILED|FAILURE:|[Ee]rror:|[0-9]+ tests completed)' \
        "$log_file" 2>/dev/null |
        tail -n 40 >&2 || true
    tail -n 40 "$log_file" >&2 || true
}

normalize_digest() {
    tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]'
}

repo_arg="$PWD"
signing_arg="$DEFAULT_SIGNING_DIR"
payload_arg=""
output_arg=""
channel_arg=""

while (($#)); do
    case "$1" in
        --channel|--repo|--signing-dir|--payload-dir|--output)
            (($# >= 2)) || fail "missing value for $1"
            case "$1" in
                --channel) channel_arg="$2" ;;
                --repo) repo_arg="$2" ;;
                --signing-dir) signing_arg="$2" ;;
                --payload-dir) payload_arg="$2" ;;
                --output) output_arg="$2" ;;
            esac
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

case "$channel_arg" in
    stable|dev) ;;
    "") fail "--channel stable|dev is required" ;;
    *) fail "unsupported build channel: $channel_arg" ;;
esac
readonly channel_arg

for command_name in git jq tar install sudo stat keytool sha256sum awk sed sort tr find mktemp zip unzip zipinfo cp mkdir date; do
    require_command "$command_name"
done

[[ -d "$repo_arg" && ! -L "$repo_arg" ]] || fail "repository path is not a regular directory"
REPO="$(cd -- "$repo_arg" && pwd -P)"
readonly REPO
[[ "$(git -C "$REPO" rev-parse --show-toplevel)" == "$REPO" ]] \
    || fail "--repo must identify the repository root"
[[ -f "$REPO/AGENTS.md" ]] || fail "repository AGENTS.md is missing"

SOURCE_SHA="$(git -C "$REPO" rev-parse HEAD)"
readonly SOURCE_SHA
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "invalid source commit"
if [[ "$channel_arg" == stable ]]; then
    [[ -z "$(git -C "$REPO" status --short)" ]] ||
        fail "stable build requires a clean worktree"
    [[ "$(git -C "$REPO" branch --show-current)" == "main" ]] ||
        fail "stable build requires branch main"
    REMOTE_SHA="$(
        git -C "$REPO" ls-remote --heads origin refs/heads/main |
            awk 'NR == 1 { print $1 }'
    )"
    readonly REMOTE_SHA
    [[ "$REMOTE_SHA" == "$SOURCE_SHA" ]] ||
        fail "stable source is not the exact pushed origin/main commit"
    EMPTY_TREE="4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    readonly EMPTY_TREE
    git -C "$REPO" diff --check "$EMPTY_TREE" "$SOURCE_SHA" -- . ||
        fail "stable source contains whitespace errors"
    PRERELEASE_ID=""
else
    PRERELEASE_ID="dev.$(date -u +%Y%m%d%H%M%S).${SOURCE_SHA:0:8}"
fi
readonly PRERELEASE_ID

VERSION_METADATA="$(sh "$REPO/tools/release-version.sh")"
readonly VERSION_METADATA
VERSION="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version=//p')"
VERSION_CODE="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version_code=//p')"
VERSION_TAG="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version_tag=//p')"
readonly VERSION VERSION_CODE VERSION_TAG
[[ -n "$VERSION" && -n "$VERSION_CODE" && "$VERSION_TAG" == "v$VERSION" ]] \
    || fail "canonical release metadata is incomplete"
if [[ "$channel_arg" == stable ]]; then
    BUILD_VERSION="$VERSION"
    BUILD_VERSION_TAG="$VERSION_TAG"
else
    BUILD_VERSION="$VERSION-$PRERELEASE_ID"
    BUILD_VERSION_TAG="$VERSION_TAG-$PRERELEASE_ID"
fi
readonly BUILD_VERSION BUILD_VERSION_TAG

[[ -d "$signing_arg" && ! -L "$signing_arg" ]] || fail "signing path is not a regular directory"
SIGNING_DIR="$(cd -- "$signing_arg" && pwd -P)"
readonly SIGNING_DIR
[[ "$(stat -c '%a' "$SIGNING_DIR")" == "700" ]] || fail "signing directory mode must be 700"
readonly SECRETS_JSON="$SIGNING_DIR/github-secrets.json"
readonly KEYSTORE_FILE="$SIGNING_DIR/keystore.jks"
readonly CERT_DIGEST_FILE="$SIGNING_DIR/apk-signing-cert-sha256.txt"
for private_file in "$SECRETS_JSON" "$KEYSTORE_FILE" \
    "$SIGNING_DIR/keystore.base64" "$SIGNING_DIR/store-password.txt"; do
    [[ -f "$private_file" && ! -L "$private_file" && -s "$private_file" ]] \
        || fail "required signing file is missing or unsafe: $private_file"
    [[ "$(stat -c '%a' "$private_file")" == "600" ]] \
        || fail "confidential signing file mode must be 600: $private_file"
done
[[ -f "$CERT_DIGEST_FILE" && ! -L "$CERT_DIGEST_FILE" && -s "$CERT_DIGEST_FILE" ]] \
    || fail "signer digest file is missing or unsafe"

for secret_name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD APK_SIGNING_CERT_SHA256; do
    jq -er --arg name "$secret_name" '.[$name] | strings | select(length > 0)' \
        "$SECRETS_JSON" >/dev/null || fail "missing signing value: $secret_name"
done

KEYSTORE_PASSWORD="$(jq -er '.KEYSTORE_PASSWORD' "$SECRETS_JSON")"
KEY_ALIAS="$(jq -er '.KEY_ALIAS' "$SECRETS_JSON")"
KEY_PASSWORD="$(jq -er '.KEY_PASSWORD' "$SECRETS_JSON")"
export KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD
EXPECTED_CERT="$(jq -er '.APK_SIGNING_CERT_SHA256' "$SECRETS_JSON" | normalize_digest)"
readonly EXPECTED_CERT
[[ "$EXPECTED_CERT" =~ ^[0-9A-F]{64}$ ]] || fail "invalid expected signer digest"
FILE_CERT="$(normalize_digest < "$CERT_DIGEST_FILE")"
readonly FILE_CERT
[[ "$FILE_CERT" == "$EXPECTED_CERT" ]] || fail "signer digest file disagrees with github-secrets.json"
KEYSTORE_CERT="$(
    keytool -exportcert -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" \
        -alias "$KEY_ALIAS" 2>/dev/null | sha256sum | awk '{ print toupper($1) }'
)"
readonly KEYSTORE_CERT
[[ "$KEYSTORE_CERT" == "$EXPECTED_CERT" ]] || fail "keystore certificate does not match the production identity"

DEFAULT_OUTPUT_MODE=0
DEFAULT_OUTPUT_PARENT=""
if [[ -n "$output_arg" ]]; then
    if [[ "$output_arg" == /* ]]; then
        OUTPUT_DIR="$output_arg"
    else
        OUTPUT_DIR="$REPO/$output_arg"
    fi
else
    DEFAULT_OUTPUT_MODE=1
    if [[ "$channel_arg" == stable ]]; then
        DEFAULT_OUTPUT_PARENT="$REPO/.artifacts/local-releases"
    else
        DEFAULT_OUTPUT_PARENT="$REPO/.artifacts/dev-builds"
    fi
    if [[ -e "$REPO/.artifacts" ]]; then
        [[ -d "$REPO/.artifacts" && ! -L "$REPO/.artifacts" ]] \
            || fail "default artifact root is unsafe"
    fi
    mkdir -p -- "$DEFAULT_OUTPUT_PARENT"
    [[ -d "$DEFAULT_OUTPUT_PARENT" && ! -L "$DEFAULT_OUTPUT_PARENT" ]] \
        || fail "default release output parent is unsafe"
    OUTPUT_DIR="$DEFAULT_OUTPUT_PARENT/$BUILD_VERSION_TAG"
fi
readonly DEFAULT_OUTPUT_MODE DEFAULT_OUTPUT_PARENT OUTPUT_DIR
[[ "$OUTPUT_DIR" != "/" && "$OUTPUT_DIR" != "$REPO" && "$OUTPUT_DIR" != "$SIGNING_DIR" ]] \
    || fail "unsafe output directory"
[[ -d "$(dirname -- "$OUTPUT_DIR")" && ! -L "$(dirname -- "$OUTPUT_DIR")" ]] \
    || fail "output parent directory is missing or unsafe"
if ((DEFAULT_OUTPUT_MODE)); then
    if [[ -e "$OUTPUT_DIR" ]]; then
        [[ -d "$OUTPUT_DIR" && ! -L "$OUTPUT_DIR" ]] \
            || fail "existing default output is unsafe: $OUTPUT_DIR"
    fi
else
    [[ ! -e "$OUTPUT_DIR" ]] \
        || fail "output directory already exists; refusing to overwrite it: $OUTPUT_DIR"
fi

WORK_ROOT="$(mktemp -d /tmp/zapret2-local-release.XXXXXXXX)"
readonly WORK_ROOT
PENDING_OUTPUT_DIR=""
cleanup() {
    if [[ -n "$PENDING_OUTPUT_DIR" && -d "$PENDING_OUTPUT_DIR" \
        && ! -L "$PENDING_OUTPUT_DIR" ]]; then
        case "$PENDING_OUTPUT_DIR" in
            "$DEFAULT_OUTPUT_PARENT"/.pending-*)
                rm -rf -- "$PENDING_OUTPUT_DIR"
                ;;
        esac
    fi
    case "$WORK_ROOT" in
        /tmp/zapret2-local-release.*)
            sudo chown -R "$(id -u):$(id -g)" "$WORK_ROOT" 2>/dev/null || true
            rm -rf -- "$WORK_ROOT"
            ;;
    esac
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

MODULE_SOURCE="$WORK_ROOT/module-source"
APP_SOURCE="$WORK_ROOT/app-source"
mkdir -p -- "$MODULE_SOURCE" "$APP_SOURCE"
if [[ "$channel_arg" == stable ]]; then
    git -C "$REPO" archive "$SOURCE_SHA" | tar -x -C "$MODULE_SOURCE"
else
    while IFS= read -r -d '' source_path; do
        case "$source_path" in
            ""|/*|../*|*/../*|*/..) fail "unsafe worktree source path" ;;
        esac
        [[ -e "$REPO/$source_path" || -L "$REPO/$source_path" ]] || continue
        mkdir -p -- "$MODULE_SOURCE/$(dirname -- "$source_path")"
        cp -a -- "$REPO/$source_path" "$MODULE_SOURCE/$source_path"
    done < <(git -C "$REPO" ls-files -co --exclude-standard -z)
fi
cp -a -- "$MODULE_SOURCE/." "$APP_SOURCE/"
SNAPSHOT_SHA256="$(
    tar -C "$MODULE_SOURCE" \
        --sort=name \
        --mtime='UTC 1970-01-01' \
        --owner=0 \
        --group=0 \
        --numeric-owner \
        -cf - . |
        sha256sum |
        awk '{ print $1 }'
)"
readonly SNAPSHOT_SHA256
[[ "$SNAPSHOT_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
    fail "unable to identify the source snapshot"

SOURCE_POLICY_LOG="$WORK_ROOT/source-policy.log"
if ! bash "$MODULE_SOURCE/tests/release/source-policy.sh" \
    "$MODULE_SOURCE" >"$SOURCE_POLICY_LOG" 2>&1; then
    tail -n 80 "$SOURCE_POLICY_LOG" >&2 || true
    fail "release-source policy failed"
fi

RELEASE_POLICY_LOG="$WORK_ROOT/release-policy.log"
if ! bash "$MODULE_SOURCE/tests/release/release-channel.sh" \
    "$MODULE_SOURCE" >"$RELEASE_POLICY_LOG" 2>&1; then
    tail -n 80 "$RELEASE_POLICY_LOG" >&2 || true
    fail "release-channel policy failed"
fi

if [[ -n "$payload_arg" ]]; then
    [[ -d "$payload_arg" && ! -L "$payload_arg" ]] || fail "payload path is not a regular directory"
    PAYLOAD_DIR="$(cd -- "$payload_arg" && pwd -P)"
else
    PAYLOAD_DIR="$WORK_ROOT/upstream-payload"
    chmod +x "$MODULE_SOURCE/upstream/fetch-release.sh"
    "$MODULE_SOURCE/upstream/fetch-release.sh" "$PAYLOAD_DIR"
fi
readonly PAYLOAD_DIR
for provenance_file in \
    upstream-zapret2.commit \
    upstream-zapret2.release \
    upstream-zapret2.archive.sha256; do
    [[ -f "$PAYLOAD_DIR/$provenance_file" \
        && ! -L "$PAYLOAD_DIR/$provenance_file" \
        && -s "$PAYLOAD_DIR/$provenance_file" ]] \
        || fail "verified upstream provenance is missing: $provenance_file"
done
for abi in arm64-v8a armeabi-v7a; do
    [[ -f "$PAYLOAD_DIR/bin/$abi/nfqws2" && ! -L "$PAYLOAD_DIR/bin/$abi/nfqws2" \
        && -s "$PAYLOAD_DIR/bin/$abi/nfqws2" ]] || fail "verified upstream binary is missing: $abi"
    install -D -m 0755 "$PAYLOAD_DIR/bin/$abi/nfqws2" \
        "$MODULE_SOURCE/zapret2/bin/$abi/nfqws2"
done
while IFS= read -r lua_file || [[ -n "$lua_file" ]]; do
    [[ -z "$lua_file" || "$lua_file" == \#* ]] && continue
    [[ "$lua_file" =~ ^[A-Za-z0-9._-]+[.]lua$ ]] || fail "unsafe Lua allowlist entry"
    [[ -f "$PAYLOAD_DIR/lua/$lua_file" && ! -L "$PAYLOAD_DIR/lua/$lua_file" \
        && -s "$PAYLOAD_DIR/lua/$lua_file" ]] || fail "verified upstream Lua is missing: $lua_file"
    install -m 0644 "$PAYLOAD_DIR/lua/$lua_file" "$MODULE_SOURCE/zapret2/lua/$lua_file"
done < "$MODULE_SOURCE/upstream/lua-files.txt"
for provenance_file in \
    upstream-zapret2.commit \
    upstream-zapret2.release \
    upstream-zapret2.archive.sha256; do
    install -m 0644 "$PAYLOAD_DIR/$provenance_file" \
        "$MODULE_SOURCE/zapret2/$provenance_file"
done

SHELL_TEST_LOG="$WORK_ROOT/shell-tests.log"
chmod +x "$MODULE_SOURCE/tests/shell/run.sh"
if ! (
    cd "$MODULE_SOURCE"
    sudo sh tests/shell/run.sh
) >"$SHELL_TEST_LOG" 2>&1; then
    tail -n 100 "$SHELL_TEST_LOG" >&2 || true
    fail "shell integration tests failed"
fi

MODULE_LOG="$WORK_ROOT/module-build.log"
sudo chown -R root:root "$MODULE_SOURCE"
run_module_build() {
    sudo bash -c 'cd "$1" && bash ./build.sh "$2" artifacts "$3"' \
        _ "$MODULE_SOURCE" "$VERSION" "$PRERELEASE_ID"
}
if ! run_module_build >"$MODULE_LOG" 2>&1; then
    show_failure_log "$MODULE_LOG"
    fail "Magisk module build failed"
fi
sudo chown -R "$(id -u):$(id -g)" "$MODULE_SOURCE"
MODULE_ZIP="$MODULE_SOURCE/artifacts/zapret2-magisk-$BUILD_VERSION_TAG.zip"
[[ -f "$MODULE_ZIP" && -s "$MODULE_ZIP" ]] || fail "Magisk ZIP was not produced"

install -m 0600 "$KEYSTORE_FILE" "$APP_SOURCE/android-app/keystore.jks"
POLICY_LOG="$WORK_ROOT/android-policy.log"
if ! (
    cd "$APP_SOURCE/android-app"
    ./gradlew testDebugUnitTest lintRelease \
        --dependency-verification=strict --no-daemon --stacktrace --console=plain
) >"$POLICY_LOG" 2>&1; then
    show_failure_log "$POLICY_LOG"
    fail "Android tests or release lint failed"
fi

APP_GRADLE="$APP_SOURCE/android-app/app/build.gradle.kts"
sed -i "s/versionCode = [0-9]*/versionCode = ${VERSION_CODE}/" "$APP_GRADLE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"${BUILD_VERSION}\"/" "$APP_GRADLE"
grep -Fq "versionCode = $VERSION_CODE" "$APP_GRADLE" || fail "Android versionCode stamp failed"
grep -Fq "versionName = \"$BUILD_VERSION\"" "$APP_GRADLE" ||
    fail "Android versionName stamp failed"

APK_LOG="$WORK_ROOT/android-build.log"
if ! (
    cd "$APP_SOURCE/android-app"
    ./gradlew assembleRelease \
        --dependency-verification=strict --no-daemon --stacktrace --console=plain
) >"$APK_LOG" 2>&1; then
    show_failure_log "$APK_LOG"
    fail "release APK build failed"
fi
SOURCE_APK="$APP_SOURCE/android-app/app/build/outputs/apk/release/app-release.apk"
[[ -f "$SOURCE_APK" && -s "$SOURCE_APK" ]] || fail "release APK was not produced"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$DEFAULT_SDK_DIR}}"
readonly SDK_ROOT
APKSIGNER="$(find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f \
    -name apksigner -print | sort -V | tail -n 1)"
readonly APKSIGNER
[[ -x "$APKSIGNER" ]] || fail "apksigner was not found"
VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs "$SOURCE_APK")"
ACTUAL_CERT="$(
    printf '%s\n' "$VERIFY_OUTPUT" |
        awk '
            /certificate SHA-256 digest:/ {
                digest = $0
                sub(/^.*certificate SHA-256 digest:[[:space:]]*/, "", digest)
                gsub(/:|[[:space:]]/, "", digest)
                print toupper(digest)
            }
        ' |
        sort -u
)"
readonly ACTUAL_CERT
[[ "${#ACTUAL_CERT}" -eq 64 ]] || fail "unable to read one APK signer certificate digest"
[[ "$ACTUAL_CERT" == "$EXPECTED_CERT" ]] || fail "APK signer does not match the production identity"

if ((DEFAULT_OUTPUT_MODE)); then
    PENDING_OUTPUT_DIR="$(
        mktemp -d "$DEFAULT_OUTPUT_PARENT/.pending-${BUILD_VERSION_TAG}.XXXXXXXX"
    )"
    ARTIFACT_DIR="$PENDING_OUTPUT_DIR"
else
    mkdir -- "$OUTPUT_DIR"
    chmod 0700 "$OUTPUT_DIR"
    ARTIFACT_DIR="$OUTPUT_DIR"
fi
readonly ARTIFACT_DIR
ZIP_NAME="zapret2-magisk-$BUILD_VERSION_TAG.zip"
APK_NAME="zapret2-control-$BUILD_VERSION_TAG.apk"
UPDATE_NAME="update.json"
BUILD_INFO_NAME="build-info.json"
install -m 0644 "$MODULE_ZIP" "$ARTIFACT_DIR/$ZIP_NAME"
install -m 0644 "$SOURCE_APK" "$ARTIFACT_DIR/$APK_NAME"
if [[ "$channel_arg" == stable ]]; then
    jq -n \
        --arg version "$VERSION_TAG" \
        --argjson version_code "$VERSION_CODE" \
        --arg zip_url "https://github.com/$RELEASE_REPO/releases/download/$VERSION_TAG/$ZIP_NAME" \
        --arg changelog "https://github.com/$RELEASE_REPO/releases/tag/$VERSION_TAG" \
        '{
            version: $version,
            versionCode: $version_code,
            zipUrl: $zip_url,
            changelog: $changelog
        }' > "$ARTIFACT_DIR/$UPDATE_NAME"
    chmod 0644 "$ARTIFACT_DIR/$UPDATE_NAME"
    jq -e \
        --arg version "$VERSION_TAG" \
        --argjson version_code "$VERSION_CODE" \
        --arg zip_url "https://github.com/$RELEASE_REPO/releases/download/$VERSION_TAG/$ZIP_NAME" \
        --arg changelog "https://github.com/$RELEASE_REPO/releases/tag/$VERSION_TAG" \
        'keys == ["changelog", "version", "versionCode", "zipUrl"] and
         .version == $version and
         .versionCode == $version_code and
         .zipUrl == $zip_url and
         .changelog == $changelog' \
        "$ARTIFACT_DIR/$UPDATE_NAME" >/dev/null
else
    jq -n \
        --arg channel "$channel_arg" \
        --arg version "$BUILD_VERSION_TAG" \
        --argjson version_code "$VERSION_CODE" \
        --arg source_commit "$SOURCE_SHA" \
        --arg snapshot_sha256 "$SNAPSHOT_SHA256" \
        --arg signer_sha256 "$ACTUAL_CERT" \
        '{
            channel: $channel,
            version: $version,
            versionCode: $version_code,
            sourceCommit: $source_commit,
            snapshotSha256: $snapshot_sha256,
            signerSha256: $signer_sha256,
            publishable: false
        }' > "$ARTIFACT_DIR/$BUILD_INFO_NAME"
    chmod 0644 "$ARTIFACT_DIR/$BUILD_INFO_NAME"
fi
(
    cd "$ARTIFACT_DIR"
    sha256sum "$ZIP_NAME" > "$ZIP_NAME.sha256"
    sha256sum "$APK_NAME" > "$APK_NAME.sha256"
    sha256sum -c "$ZIP_NAME.sha256"
    sha256sum -c "$APK_NAME.sha256"
)

if ((DEFAULT_OUTPUT_MODE)); then
    if [[ -e "$OUTPUT_DIR" ]]; then
        [[ -d "$OUTPUT_DIR" && ! -L "$OUTPUT_DIR" ]] \
            || fail "existing default output became unsafe: $OUTPUT_DIR"
        rm -rf -- "$OUTPUT_DIR"
    fi
    mv -- "$PENDING_OUTPUT_DIR" "$OUTPUT_DIR"
    PENDING_OUTPUT_DIR=""

    while IFS= read -r -d '' old_output; do
        [[ "$old_output" == "$OUTPUT_DIR" ]] && continue
        [[ -d "$old_output" && ! -L "$old_output" ]] \
            || fail "old local release output is unsafe: $old_output"
        case "$old_output" in
            "$DEFAULT_OUTPUT_PARENT"/v*|"$DEFAULT_OUTPUT_PARENT"/.pending-*)
                rm -rf -- "$old_output"
                ;;
            *)
                fail "refusing to remove unexpected output path: $old_output"
                ;;
        esac
    done < <(
        find "$DEFAULT_OUTPUT_PARENT" -mindepth 1 -maxdepth 1 -type d \
            \( -name 'v*' -o -name '.pending-*' \) -print0
    )
fi

printf 'source_commit=%s\n' "$SOURCE_SHA"
printf 'source_snapshot_sha256=%s\n' "$SNAPSHOT_SHA256"
printf 'channel=%s\n' "$channel_arg"
printf 'version=%s\n' "$BUILD_VERSION"
printf 'version_code=%s\n' "$VERSION_CODE"
printf 'version_tag=%s\n' "$BUILD_VERSION_TAG"
printf 'output_directory=%s\n' "$OUTPUT_DIR"
printf 'magisk_zip=%s\n' "$OUTPUT_DIR/$ZIP_NAME"
printf 'android_apk=%s\n' "$OUTPUT_DIR/$APK_NAME"
if [[ "$channel_arg" == stable ]]; then
    printf 'update_json=%s\n' "$OUTPUT_DIR/$UPDATE_NAME"
else
    printf 'build_info=%s\n' "$OUTPUT_DIR/$BUILD_INFO_NAME"
fi
printf 'signer_sha256=%s\n' "$ACTUAL_CERT"
printf 'source_policy=passed\n'
printf 'release_policy=passed\n'
printf 'shell_tests=passed\n'
printf 'android_policy=passed\n'
printf 'module_contract=passed\n'
