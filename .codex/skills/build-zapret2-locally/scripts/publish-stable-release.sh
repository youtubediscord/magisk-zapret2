#!/usr/bin/env bash
# Publish a locally qualified build as the canonical stable GitHub release.
set -euo pipefail
umask 077

readonly DEFAULT_SIGNING_DIR="/home/codex-pve/.config/zapret2-signing"
readonly DEFAULT_SDK_DIR="/home/codex-pve/Android/Sdk"
readonly RELEASE_REPO="youtubediscord/magisk-zapret2"

usage() {
    cat >&2 <<'USAGE'
Usage: publish-stable-release.sh [options]

Options:
  --repo PATH          Repository checkout (default: current directory)
  --signing-dir PATH   Durable production signing directory
  --artifacts PATH     Qualified build directory
                       (default: REPO/.artifacts/local-releases/vVERSION)
  -h, --help           Show this help

The source commit must be the clean, pushed origin/main tip. Run
build-local-release.sh --channel stable first. This command creates the immutable
vVERSION stable release, marks it Latest, and uploads the APK, module ZIP, checksums,
and update.json.
USAGE
}

fail() {
    printf 'stable release publish: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 ||
        fail "required command is unavailable: $1"
}

normalize_digest() {
    tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]'
}

repo_arg="$PWD"
signing_arg="$DEFAULT_SIGNING_DIR"
artifacts_arg=""

while (($#)); do
    case "$1" in
        --repo|--signing-dir|--artifacts)
            (($# >= 2)) || fail "missing value for $1"
            case "$1" in
                --repo) repo_arg="$2" ;;
                --signing-dir) signing_arg="$2" ;;
                --artifacts) artifacts_arg="$2" ;;
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

for command_name in git gh jq stat sha256sum awk sed sort tr find mktemp unzip grep cmp; do
    require_command "$command_name"
done

[[ -d "$repo_arg" && ! -L "$repo_arg" ]] ||
    fail "repository path is not a regular directory"
REPO="$(cd -- "$repo_arg" && pwd -P)"
readonly REPO
[[ "$(git -C "$REPO" rev-parse --show-toplevel)" == "$REPO" ]] ||
    fail "--repo must identify the repository root"
[[ -z "$(git -C "$REPO" status --short)" ]] ||
    fail "repository worktree is not clean"
[[ "$(git -C "$REPO" branch --show-current)" == "main" ]] ||
    fail "current branch must be main"

SOURCE_SHA="$(git -C "$REPO" rev-parse HEAD)"
readonly SOURCE_SHA
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "invalid source commit"
REMOTE_SHA="$(
    git -C "$REPO" ls-remote --heads origin refs/heads/main |
        awk 'NR == 1 { print $1 }'
)"
readonly REMOTE_SHA
[[ "$REMOTE_SHA" == "$SOURCE_SHA" ]] ||
    fail "HEAD is not the exact pushed origin/main commit"

VERSION_METADATA="$(sh "$REPO/tools/release-version.sh")"
readonly VERSION_METADATA
VERSION="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version=//p')"
VERSION_CODE="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version_code=//p')"
VERSION_TAG="$(printf '%s\n' "$VERSION_METADATA" | sed -n 's/^version_tag=//p')"
readonly VERSION VERSION_CODE VERSION_TAG
[[ -n "$VERSION" && -n "$VERSION_CODE" && "$VERSION_TAG" == "v$VERSION" ]] ||
    fail "canonical release metadata is incomplete"

if [[ -n "$artifacts_arg" ]]; then
    if [[ "$artifacts_arg" == /* ]]; then
        ARTIFACT_DIR="$artifacts_arg"
    else
        ARTIFACT_DIR="$REPO/$artifacts_arg"
    fi
else
    ARTIFACT_DIR="$REPO/.artifacts/local-releases/$VERSION_TAG"
fi
readonly ARTIFACT_DIR
[[ -d "$ARTIFACT_DIR" && ! -L "$ARTIFACT_DIR" ]] ||
    fail "qualified artifact directory is missing or unsafe: $ARTIFACT_DIR"

ZIP_NAME="zapret2-magisk-$VERSION_TAG.zip"
APK_NAME="zapret2-control-$VERSION_TAG.apk"
UPDATE_NAME="update.json"
readonly ZIP_NAME APK_NAME UPDATE_NAME
readonly ZIP_PATH="$ARTIFACT_DIR/$ZIP_NAME"
readonly APK_PATH="$ARTIFACT_DIR/$APK_NAME"
readonly UPDATE_PATH="$ARTIFACT_DIR/$UPDATE_NAME"
readonly ZIP_SUM_PATH="$ZIP_PATH.sha256"
readonly APK_SUM_PATH="$APK_PATH.sha256"

expected_names=(
    "$ZIP_NAME"
    "$ZIP_NAME.sha256"
    "$APK_NAME"
    "$APK_NAME.sha256"
    "$UPDATE_NAME"
)
for expected_name in "${expected_names[@]}"; do
    [[ -f "$ARTIFACT_DIR/$expected_name" &&
        ! -L "$ARTIFACT_DIR/$expected_name" &&
        -s "$ARTIFACT_DIR/$expected_name" ]] ||
        fail "required release asset is missing or unsafe: $expected_name"
done

mapfile -d '' artifact_entries < <(
    find "$ARTIFACT_DIR" -mindepth 1 -maxdepth 1 -print0
)
((${#artifact_entries[@]} == ${#expected_names[@]})) ||
    fail "artifact directory must contain exactly the five release assets"

(
    cd "$ARTIFACT_DIR"
    sha256sum -c "$ZIP_NAME.sha256"
    sha256sum -c "$APK_NAME.sha256"
) >/dev/null || fail "artifact checksum verification failed"
unzip -tq "$ZIP_PATH" >/dev/null || fail "module ZIP integrity check failed"
[[ "$(unzip -p "$ZIP_PATH" module.prop | sed -n 's/^version=//p')" == "$VERSION_TAG" ]] ||
    fail "module ZIP version does not match $VERSION_TAG"
[[ "$(unzip -p "$ZIP_PATH" module.prop | sed -n 's/^versionCode=//p')" == "$VERSION_CODE" ]] ||
    fail "module ZIP versionCode does not match $VERSION_CODE"

EXPECTED_ZIP_URL="https://github.com/$RELEASE_REPO/releases/download/$VERSION_TAG/$ZIP_NAME"
EXPECTED_CHANGELOG="https://github.com/$RELEASE_REPO/releases/tag/$VERSION_TAG"
readonly EXPECTED_ZIP_URL EXPECTED_CHANGELOG
jq -e \
    --arg version "$VERSION_TAG" \
    --argjson version_code "$VERSION_CODE" \
    --arg zip_url "$EXPECTED_ZIP_URL" \
    --arg changelog "$EXPECTED_CHANGELOG" \
    'keys == ["changelog", "version", "versionCode", "zipUrl"] and
     .version == $version and
     .versionCode == $version_code and
     .zipUrl == $zip_url and
     .changelog == $changelog' \
    "$UPDATE_PATH" >/dev/null ||
    fail "update.json does not describe this stable release exactly"

[[ -d "$signing_arg" && ! -L "$signing_arg" ]] ||
    fail "signing path is not a regular directory"
SIGNING_DIR="$(cd -- "$signing_arg" && pwd -P)"
readonly SIGNING_DIR
[[ "$(stat -c '%a' "$SIGNING_DIR")" == "700" ]] ||
    fail "signing directory mode must be 700"
readonly CERT_DIGEST_FILE="$SIGNING_DIR/apk-signing-cert-sha256.txt"
[[ -f "$CERT_DIGEST_FILE" && ! -L "$CERT_DIGEST_FILE" &&
    -s "$CERT_DIGEST_FILE" ]] ||
    fail "production signer digest file is missing or unsafe"
EXPECTED_CERT="$(normalize_digest < "$CERT_DIGEST_FILE")"
readonly EXPECTED_CERT
[[ "$EXPECTED_CERT" =~ ^[0-9A-F]{64}$ ]] ||
    fail "invalid production signer digest"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$DEFAULT_SDK_DIR}}"
readonly SDK_ROOT
APKSIGNER="$(
    find "$SDK_ROOT/build-tools" -mindepth 2 -maxdepth 2 -type f \
        -name apksigner -print | sort -V | tail -n 1
)"
readonly APKSIGNER
[[ -x "$APKSIGNER" ]] || fail "apksigner was not found"
VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs "$APK_PATH")"
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
[[ "${#ACTUAL_CERT}" -eq 64 && "$ACTUAL_CERT" == "$EXPECTED_CERT" ]] ||
    fail "APK signer does not match the production identity"

REMOTE_WORK="$(mktemp -d /tmp/zapret2-stable-release.XXXXXXXX)"
readonly REMOTE_WORK
cleanup() {
    case "$REMOTE_WORK" in
        /tmp/zapret2-stable-release.*)
            rm -rf -- "$REMOTE_WORK"
            ;;
    esac
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

REMOTE_TAG="$(
    git -C "$REPO" ls-remote --tags origin "refs/tags/$VERSION_TAG"
)" || fail "unable to inspect the remote release tag"
[[ -z "$REMOTE_TAG" ]] ||
    fail "immutable release tag already exists: $VERSION_TAG"

RELEASE_LOOKUP_ERROR="$REMOTE_WORK/release-lookup.error"
if gh api "repos/$RELEASE_REPO/releases/tags/$VERSION_TAG" \
    >"$REMOTE_WORK/release-lookup.json" 2>"$RELEASE_LOOKUP_ERROR"; then
    fail "immutable GitHub Release already exists: $VERSION_TAG"
elif ! grep -Fq 'HTTP 404' "$RELEASE_LOOKUP_ERROR"; then
    fail "unable to determine whether the GitHub Release already exists"
fi

LATEST_ERROR="$REMOTE_WORK/latest.error"
if LATEST_RELEASE="$(
    gh api "repos/$RELEASE_REPO/releases/latest" 2>"$LATEST_ERROR"
)"; then
    LATEST_TAG="$(jq -er '.tag_name | strings' <<<"$LATEST_RELEASE")"
    LATEST_ASSET_ID="$(
        jq -er '
            [.assets[] | select(.name == "update.json")] |
            if length == 1 then .[0].id else error("latest release update.json is not unique") end
        ' <<<"$LATEST_RELEASE"
    )"
    gh api "repos/$RELEASE_REPO/releases/assets/$LATEST_ASSET_ID" \
        -H 'Accept: application/octet-stream' > "$REMOTE_WORK/latest-update.json"
    LATEST_VERSION="$(jq -er '.version | strings' "$REMOTE_WORK/latest-update.json")"
    LATEST_VERSION_CODE="$(
        jq -er '.versionCode | select(type == "number" and floor == .)' \
            "$REMOTE_WORK/latest-update.json"
    )"
    [[ "$LATEST_VERSION" == "$LATEST_TAG" ]] ||
        fail "Latest release metadata does not match its tag"
    ((VERSION_CODE > LATEST_VERSION_CODE)) ||
        fail "versionCode $VERSION_CODE must exceed Latest versionCode $LATEST_VERSION_CODE"
elif ! grep -Fq 'HTTP 404' "$LATEST_ERROR"; then
    fail "unable to inspect the current Latest release"
fi

# Recheck immediately before the one-way publication boundary.
REMOTE_TAG="$(
    git -C "$REPO" ls-remote --tags origin "refs/tags/$VERSION_TAG"
)" || fail "unable to recheck the remote release tag"
[[ -z "$REMOTE_TAG" ]] ||
    fail "release tag appeared during validation: $VERSION_TAG"
if gh api "repos/$RELEASE_REPO/releases/tags/$VERSION_TAG" \
    >"$REMOTE_WORK/release-recheck.json" 2>"$RELEASE_LOOKUP_ERROR"; then
    fail "GitHub Release appeared during validation: $VERSION_TAG"
elif ! grep -Fq 'HTTP 404' "$RELEASE_LOOKUP_ERROR"; then
    fail "unable to recheck whether the GitHub Release exists"
fi

RELEASE_NOTES="$(
    printf "Production build created locally from exact commit \`%s\`.\n\n" "$SOURCE_SHA"
    printf "APK signing certificate SHA-256: \`%s\`.\n\n" "$ACTUAL_CERT"
    printf 'GitHub Actions is an independent background validation and is not a publication dependency.'
)"

RELEASE_URL="$(
    gh release create "$VERSION_TAG" \
        --repo "$RELEASE_REPO" \
        --target "$SOURCE_SHA" \
        --title "Zapret2 $VERSION" \
        --generate-notes \
        --notes "$RELEASE_NOTES" \
        --latest \
        "$ZIP_PATH" \
        "$ZIP_SUM_PATH" \
        "$APK_PATH" \
        "$APK_SUM_PATH" \
        "$UPDATE_PATH"
)"
readonly RELEASE_URL

PUBLISHED_RELEASE="$(
    gh api "repos/$RELEASE_REPO/releases/tags/$VERSION_TAG"
)"
jq -e \
    --arg tag "$VERSION_TAG" \
    '(.draft | not) and
     (.prerelease | not) and
     .tag_name == $tag and
     ([.assets[].name] | sort) ==
       ["update.json",
        ("zapret2-control-" + $tag + ".apk"),
        ("zapret2-control-" + $tag + ".apk.sha256"),
        ("zapret2-magisk-" + $tag + ".zip"),
        ("zapret2-magisk-" + $tag + ".zip.sha256")]' \
    <<<"$PUBLISHED_RELEASE" >/dev/null ||
    fail "published release does not satisfy the stable asset contract"

PUBLISHED_TAG_SHA="$(
    git -C "$REPO" ls-remote --tags origin "refs/tags/$VERSION_TAG" |
        awk 'NR == 1 { print $1 }'
)"
[[ "$PUBLISHED_TAG_SHA" == "$SOURCE_SHA" ]] ||
    fail "published tag does not target the exact source commit"
[[ "$(gh api "repos/$RELEASE_REPO/releases/latest" --jq .tag_name)" == "$VERSION_TAG" ]] ||
    fail "published stable release was not selected as Latest"

mkdir "$REMOTE_WORK/assets"
for expected_name in "${expected_names[@]}"; do
    gh release download "$VERSION_TAG" \
        --repo "$RELEASE_REPO" \
        --dir "$REMOTE_WORK/assets" \
        --pattern "$expected_name" >/dev/null
    cmp -s "$ARTIFACT_DIR/$expected_name" "$REMOTE_WORK/assets/$expected_name" ||
        fail "remote asset bytes differ from the qualified local asset: $expected_name"
done

[[ -z "$(git -C "$REPO" status --short)" ]] ||
    fail "repository worktree changed during publication"
[[ "$(stat -c '%a' "$SIGNING_DIR")" == "700" ]] ||
    fail "signing directory permissions changed during publication"

printf 'release_url=%s\n' "$RELEASE_URL"
printf 'source_commit=%s\n' "$SOURCE_SHA"
printf 'version=%s\n' "$VERSION"
printf 'version_code=%s\n' "$VERSION_CODE"
printf 'version_tag=%s\n' "$VERSION_TAG"
printf 'signer_sha256=%s\n' "$ACTUAL_CERT"
printf 'latest=verified\n'
printf 'remote_assets=verified\n'
