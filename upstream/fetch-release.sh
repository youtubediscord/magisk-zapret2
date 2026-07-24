#!/usr/bin/env bash
set -euo pipefail

readonly UPSTREAM_REPOSITORY="bol-van/zapret2"
readonly API_ROOT="https://api.github.com/repos/${UPSTREAM_REPOSITORY}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT_DIR
readonly UPSTREAM_LUA_LIST="${SCRIPT_DIR}/lua-files.txt"
readonly BINARY_MAP="${SCRIPT_DIR}/android-binaries.tsv"

usage() {
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
}

fail() {
    printf 'upstream fetch: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command is unavailable: $1"
}

api_get() {
    curl --fail --silent --show-error --location --retry 3 \
        --header 'Accept: application/vnd.github+json' \
        --header 'X-GitHub-Api-Version: 2022-11-28' \
        --header 'User-Agent: magisk-zapret2-upstream-fetch' \
        "$1"
}

download() {
    curl --fail --silent --show-error --location --retry 3 \
        --header 'User-Agent: magisk-zapret2-upstream-fetch' \
        --output "$2" "$1"
}

verify_asset_digest() {
    local digest="$1" file="$2" hex=""
    case "$digest" in
        sha256:[0-9a-fA-F]*) hex="${digest#sha256:}" ;;
        *) fail "GitHub did not publish a SHA-256 digest for $(basename -- "$file")" ;;
    esac
    [[ "$hex" =~ ^[0-9a-fA-F]{64}$ ]] || fail "invalid GitHub asset digest for $(basename -- "$file")"
    printf '%s  %s\n' "${hex,,}" "$file" | sha256sum --check --status - \
        || fail "GitHub asset digest mismatch for $(basename -- "$file")"
}

verify_release_binary_hash() {
    local checksums="$1" archive_path="$2" extracted_file="$3"
    local expected="" actual=""
    expected="$(awk -v wanted="$archive_path" '$2 == wanted { print $1 }' "$checksums")"
    [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] \
        || fail "missing or invalid upstream checksum for $archive_path"
    actual="$(sha256sum "$extracted_file" | awk '{ print $1 }')"
    [[ "${actual,,}" == "${expected,,}" ]] \
        || fail "upstream checksum mismatch for $archive_path"
}

verify_elf() {
    local file="$1" expected_class="$2" expected_machine="$3"
    local actual_class="" actual_machine=""
    actual_class="$(readelf --file-header "$file" | awk -F: '/^[[:space:]]*Class:/{gsub(/[[:space:]]/, "", $2); print $2}')"
    actual_machine="$(readelf --file-header "$file" | awk -F: '/^[[:space:]]*Machine:/{sub(/^[[:space:]]+/, "", $2); print $2}')"
    [[ "$actual_class" == "$expected_class" ]] \
        || fail "unexpected ELF class for $file: $actual_class"
    case "$expected_machine:$actual_machine" in
        AArch64:AArch64|ARM:ARM) ;;
        *) fail "unexpected ELF machine for $file: $actual_machine" ;;
    esac
}

[[ $# -eq 1 ]] || { usage; exit 2; }
readonly OUTPUT_DIR="$1"
[[ -n "$OUTPUT_DIR" && "$OUTPUT_DIR" != / ]] || fail "unsafe output directory"

for command_name in curl jq tar sha256sum awk readelf install mktemp find; do
    require_command "$command_name"
done
[[ -f "$UPSTREAM_LUA_LIST" && ! -L "$UPSTREAM_LUA_LIST" ]] || fail "invalid upstream Lua allowlist"
[[ -f "$BINARY_MAP" && ! -L "$BINARY_MAP" ]] || fail "invalid Android binary map"

if [[ -e "$OUTPUT_DIR" ]]; then
    [[ -d "$OUTPUT_DIR" && ! -L "$OUTPUT_DIR" ]] || fail "output path is not a regular directory"
    [[ -z "$(find "$OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]] \
        || fail "output directory is not empty: $OUTPUT_DIR"
else
    mkdir -p -- "$OUTPUT_DIR"
fi

TEMP_DIR="$(mktemp -d)"
readonly TEMP_DIR
trap 'rm -rf -- "$TEMP_DIR"' EXIT HUP INT TERM

readonly RELEASE_JSON="$TEMP_DIR/release.json"
api_get "$API_ROOT/releases/latest" > "$RELEASE_JSON"
jq -e '.draft == false and .prerelease == false' "$RELEASE_JSON" >/dev/null \
    || fail "GitHub latest endpoint returned a non-stable release"

RELEASE_TAG="$(jq -er '.tag_name' "$RELEASE_JSON")"
readonly RELEASE_TAG
[[ "$RELEASE_TAG" =~ ^v[0-9]+([.][0-9]+){1,3}([._-][0-9A-Za-z]+)*$ ]] \
    || fail "unsafe latest release tag: $RELEASE_TAG"

RELEASE_SHA="$(api_get "$API_ROOT/commits/$RELEASE_TAG" | jq -er '.sha')"
readonly RELEASE_SHA
[[ "$RELEASE_SHA" =~ ^[0-9a-f]{40}$ ]] || fail "invalid release commit SHA"

readonly ARCHIVE_NAME="zapret2-${RELEASE_TAG}.tar.gz"
ARCHIVE_URL="$(jq -er --arg name "$ARCHIVE_NAME" '.assets[] | select(.name == $name) | .browser_download_url' "$RELEASE_JSON")"
readonly ARCHIVE_URL
ARCHIVE_DIGEST="$(jq -er --arg name "$ARCHIVE_NAME" '.assets[] | select(.name == $name) | .digest' "$RELEASE_JSON")"
readonly ARCHIVE_DIGEST
CHECKSUM_URL="$(jq -er '.assets[] | select(.name == "sha256sum.txt") | .browser_download_url' "$RELEASE_JSON")"
readonly CHECKSUM_URL
CHECKSUM_DIGEST="$(jq -er '.assets[] | select(.name == "sha256sum.txt") | .digest' "$RELEASE_JSON")"
readonly CHECKSUM_DIGEST
readonly ARCHIVE_FILE="$TEMP_DIR/$ARCHIVE_NAME"
readonly CHECKSUM_FILE="$TEMP_DIR/sha256sum.txt"

download "$ARCHIVE_URL" "$ARCHIVE_FILE"
download "$CHECKSUM_URL" "$CHECKSUM_FILE"
verify_asset_digest "$ARCHIVE_DIGEST" "$ARCHIVE_FILE"
verify_asset_digest "$CHECKSUM_DIGEST" "$CHECKSUM_FILE"

readonly ARCHIVE_ROOT="zapret2-${RELEASE_TAG}"
readonly EXTRACT_ROOT="$TEMP_DIR/extracted"
mkdir -p -- "$EXTRACT_ROOT"

declare -a MEMBERS=()
while IFS= read -r lua_name || [[ -n "$lua_name" ]]; do
    [[ -z "$lua_name" || "$lua_name" == \#* ]] && continue
    [[ "$lua_name" =~ ^[a-zA-Z0-9._-]+[.]lua$ ]] || fail "unsafe Lua allowlist entry: $lua_name"
    MEMBERS+=("$ARCHIVE_ROOT/lua/$lua_name")
done < "$UPSTREAM_LUA_LIST"

while IFS=$'\t' read -r module_abi archive_path elf_class elf_machine extra || [[ -n "$module_abi$archive_path$elf_class$elf_machine$extra" ]]; do
    [[ -z "$module_abi" || "$module_abi" == \#* ]] && continue
    [[ -z "$extra" ]] || fail "invalid Android binary map row"
    [[ "$module_abi" =~ ^[a-z0-9_-]+$ ]] || fail "unsafe ABI mapping: $module_abi"
    [[ "$archive_path" =~ ^binaries/android-[a-z0-9_/-]+/nfqws2$ ]] || fail "unsafe binary archive path: $archive_path"
    MEMBERS+=("$ARCHIVE_ROOT/$archive_path")
done < "$BINARY_MAP"

[[ ${#MEMBERS[@]} -ge 5 ]] || fail "upstream payload allowlists are incomplete"
readonly ARCHIVE_LIST="$TEMP_DIR/archive.list"
tar -tzf "$ARCHIVE_FILE" > "$ARCHIVE_LIST"
for member in "${MEMBERS[@]}"; do
    [[ "$(grep -Fxc -- "$member" "$ARCHIVE_LIST")" -eq 1 ]] \
        || fail "release archive is missing or duplicates $member"
done
tar --extract --gzip --file "$ARCHIVE_FILE" --directory "$EXTRACT_ROOT" \
    --no-same-owner --no-same-permissions -- "${MEMBERS[@]}"

while IFS= read -r lua_name || [[ -n "$lua_name" ]]; do
    [[ -z "$lua_name" || "$lua_name" == \#* ]] && continue
    source_file="$EXTRACT_ROOT/$ARCHIVE_ROOT/lua/$lua_name"
    [[ -f "$source_file" && ! -L "$source_file" && -s "$source_file" ]] \
        || fail "unsafe or empty upstream Lua file: $lua_name"
    install -D -m 0644 -- "$source_file" "$OUTPUT_DIR/lua/$lua_name"
done < "$UPSTREAM_LUA_LIST"

while IFS=$'\t' read -r module_abi archive_path elf_class elf_machine extra || [[ -n "$module_abi$archive_path$elf_class$elf_machine$extra" ]]; do
    [[ -z "$module_abi" || "$module_abi" == \#* ]] && continue
    source_file="$EXTRACT_ROOT/$ARCHIVE_ROOT/$archive_path"
    [[ -f "$source_file" && ! -L "$source_file" && -s "$source_file" ]] \
        || fail "unsafe or empty upstream binary: $archive_path"
    verify_release_binary_hash "$CHECKSUM_FILE" "$ARCHIVE_ROOT/$archive_path" "$source_file"
    verify_elf "$source_file" "$elf_class" "$elf_machine"
    install -D -m 0755 -- "$source_file" "$OUTPUT_DIR/bin/$module_abi/nfqws2"
done < "$BINARY_MAP"

printf '%s\n' "$RELEASE_SHA" > "$OUTPUT_DIR/upstream-zapret2.commit"
printf '%s\n' "$RELEASE_TAG" > "$OUTPUT_DIR/upstream-zapret2.release"
printf '%s\n' "${ARCHIVE_DIGEST#sha256:}" > "$OUTPUT_DIR/upstream-zapret2.archive.sha256"
chmod 0644 "$OUTPUT_DIR"/upstream-zapret2.*

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        printf 'sha=%s\n' "$RELEASE_SHA"
        printf 'tag=%s\n' "$RELEASE_TAG"
        printf 'archive_sha256=%s\n' "${ARCHIVE_DIGEST#sha256:}"
    } >> "$GITHUB_OUTPUT"
fi

printf 'Fetched %s@%s (%s)\n' "$UPSTREAM_REPOSITORY" "$RELEASE_SHA" "$RELEASE_TAG"
