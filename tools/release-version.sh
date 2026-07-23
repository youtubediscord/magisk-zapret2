#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
METADATA="$ROOT/version.properties"

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

[ -f "$METADATA" ] && [ ! -L "$METADATA" ] ||
    fail "version.properties is missing or unsafe"

VERSION_NAME=""
VERSION_CODE=""
SEEN_NAME=0
SEEN_CODE=0

while IFS='=' read -r key value || [ -n "$key$value" ]; do
    case "$key" in
        VERSION_NAME)
            [ "$SEEN_NAME" -eq 0 ] || fail "duplicate VERSION_NAME"
            VERSION_NAME="$value"
            SEEN_NAME=1
            ;;
        VERSION_CODE)
            [ "$SEEN_CODE" -eq 0 ] || fail "duplicate VERSION_CODE"
            VERSION_CODE="$value"
            SEEN_CODE=1
            ;;
        "") fail "blank version metadata line" ;;
        *) fail "unknown version metadata key: $key" ;;
    esac
done < "$METADATA"

[ "$SEEN_NAME:$SEEN_CODE" = 1:1 ] ||
    fail "version metadata is incomplete"

case "$VERSION_NAME" in
    *[!0-9.]*|.*|*.|*..*) fail "VERSION_NAME must be canonical MAJOR.MINOR.PATCH" ;;
esac

old_ifs=$IFS
IFS=.
set -- $VERSION_NAME
IFS=$old_ifs
[ "$#" -eq 3 ] || fail "VERSION_NAME must be canonical MAJOR.MINOR.PATCH"

major=$1
minor=$2
patch=$3
for component in "$major" "$minor" "$patch"; do
    case "$component" in
        0) ;;
        ""|*[!0-9]*|0*) fail "VERSION_NAME contains a noncanonical component" ;;
        *) ;;
    esac
done

[ "$major" -le 2100 ] 2>/dev/null ||
    fail "VERSION_NAME major exceeds the Android versionCode range"
[ "$minor" -le 99 ] 2>/dev/null ||
    fail "VERSION_NAME minor must be between 0 and 99"
[ "$patch" -le 9999 ] 2>/dev/null ||
    fail "VERSION_NAME patch must be between 0 and 9999"

expected_code=$((major * 1000000 + minor * 10000 + patch))
[ "$expected_code" -gt 0 ] && [ "$expected_code" -le 2100000000 ] ||
    fail "derived Android versionCode is out of range"

case "$VERSION_CODE" in
    ""|*[!0-9]*|0*) fail "VERSION_CODE must be a canonical positive integer" ;;
    *) ;;
esac
[ "$VERSION_CODE" -eq "$expected_code" ] 2>/dev/null ||
    fail "VERSION_CODE must equal MAJOR*1000000 + MINOR*10000 + PATCH"

printf 'version=%s\n' "$VERSION_NAME"
printf 'version_code=%s\n' "$VERSION_CODE"
printf 'version_tag=v%s\n' "$VERSION_NAME"
