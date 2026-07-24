#!/usr/bin/env bash
set -euo pipefail

DEFAULT_ROOT="$(cd -- "$(dirname "$0")/../.." && pwd)"
ROOT_ARG="${1:-$DEFAULT_ROOT}"
[[ -d "$ROOT_ARG" && ! -L "$ROOT_ARG" ]] || {
    printf 'release-source policy: invalid repository root\n' >&2
    exit 1
}
ROOT="$(cd -- "$ROOT_ARG" && pwd -P)"

fail() {
    printf 'release-source policy: %s\n' "$*" >&2
    exit 1
}

if [[ "$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null || true)" == "$ROOT" ]]; then
    FORBIDDEN_FILES="$(
        git -C "$ROOT" ls-files |
            grep -E '(^|/)(local[.]properties|keystore[.]properties|signing[.]properties|[.]env([.][^/]*)?|[^/]+[.](jks|keystore|p12|pfx))$' ||
            true
    )"
else
    FORBIDDEN_FILES="$(
        find "$ROOT" \
            -type f \
            \( -name local.properties \
               -o -name keystore.properties \
               -o -name signing.properties \
               -o -name '.env' \
               -o -name '.env.*' \
               -o -name '*.jks' \
               -o -name '*.keystore' \
               -o -name '*.p12' \
               -o -name '*.pfx' \) \
            -print
    )"
fi
[[ -z "$FORBIDDEN_FILES" ]] ||
    fail "secret-bearing files are present in the release source"

if [[ "$(git -C "$ROOT" rev-parse --show-toplevel 2>/dev/null || true)" == "$ROOT" ]]; then
    if git -C "$ROOT" grep -n -I -E -- \
        '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----'; then
        fail "a private-key PEM block is present in the release source"
    fi
else
    if grep -R -I -n -E -- \
        '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----' "$ROOT"; then
        fail "a private-key PEM block is present in the release source"
    fi
fi

MAIN_MANIFEST="$ROOT/android-app/app/src/main/AndroidManifest.xml"
APP_BUILD="$ROOT/android-app/app/build.gradle.kts"
[[ -f "$MAIN_MANIFEST" && ! -L "$MAIN_MANIFEST" ]] ||
    fail "Android release manifest is missing or unsafe"
[[ -f "$APP_BUILD" && ! -L "$APP_BUILD" ]] ||
    fail "Android build policy is missing or unsafe"

RELEASE_BLOCK="$(sed -n '/^        release {$/,/^        }$/p' "$APP_BUILD")"
[[ -n "$RELEASE_BLOCK" ]] ||
    fail "Android release build type could not be located"
if grep -E 'android:(debuggable|testOnly)="true"' "$MAIN_MANIFEST"; then
    fail "Android release manifest contains a debug-only application flag"
fi
grep -Fq 'isDebuggable = false' <<<"$RELEASE_BLOCK" ||
    fail "release debuggability is not explicitly disabled"
grep -Fq 'signingConfig = signingConfigs.getByName("release")' <<<"$RELEASE_BLOCK" ||
    fail "release signing configuration is not fail-closed"
grep -Fq 'isMinifyEnabled = true' <<<"$RELEASE_BLOCK" ||
    fail "release code optimization is not enabled"
grep -Fq 'isShrinkResources = true' <<<"$RELEASE_BLOCK" ||
    fail "release resource optimization is not enabled"
if grep -Fq 'signingConfigs.getByName("debug")' <<<"$RELEASE_BLOCK"; then
    fail "release build type references the debug signing configuration"
fi

printf 'release-source policy: passed\n'
