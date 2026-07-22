#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/magisk-boot-installer"
LIVE=/data/adb/modules/zapret2
UPDATE=/data/adb/modules_update/zapret2
STATE=/data/adb/zapret2-state
ARCHIVE="$CASE/module.zip"
OWNED=0

fail() { echo "FAIL: Magisk boot installer: $*" >&2; exit 1; }

cleanup() {
    [ "$OWNED" = 1 ] || return 0
    rm -rf "$LIVE" "$UPDATE" "$STATE" "$CASE"
}

[ "$(id -u)" = 0 ] || fail "run as root"
for path in "$LIVE" "$UPDATE" "$STATE" "$CASE"; do
    [ ! -e "$path" ] && [ ! -L "$path" ] || fail "test path already exists: $path"
done
OWNED=1
trap cleanup EXIT HUP INT TERM
mkdir -p /data/adb/modules /data/adb/modules_update "$CASE"
(cd "$ROOT" && zip -q "$ARCHIVE" module.prop)

prepare_magisk_stage() {
    rm -rf "$UPDATE"
    mkdir -p "$UPDATE"
    cp "$ROOT/module.prop" "$ROOT/customize.sh" "$ROOT/service.sh" \
        "$ROOT/uninstall.sh" "$ROOT/action.sh" "$UPDATE/"
    cp -R "$ROOT/system" "$ROOT/zapret2" "$UPDATE/"
    mkdir -p "$UPDATE/zapret2/bin/arm64-v8a" "$UPDATE/zapret2/bin/armeabi-v7a"
    cp /bin/true "$UPDATE/zapret2/bin/arm64-v8a/nfqws2"
    cp /bin/false "$UPDATE/zapret2/bin/armeabi-v7a/nfqws2"
    find "$UPDATE" -type d -exec chmod 0755 {} +
    find "$UPDATE" -type f -exec chmod 0644 {} +
    find "$UPDATE/system/bin" -type f -exec chmod 0755 {} +
}

run_installer() {
    log="$1"
    (
        MODPATH="$UPDATE"
        ZIPFILE="$ARCHIVE"
        BOOTMODE=true
        ARCH=arm64
        export MODPATH ZIPFILE BOOTMODE ARCH
        abort() { echo "$*" >&2; rm -rf "$MODPATH"; exit 1; }
        ui_print() { printf '%s\n' "$*"; }
        . "$ROOT/customize.sh"
    ) > "$log" 2>&1
    # This is the canonical cleanup Magisk performs after customize.sh returns.
    rm -f "$UPDATE/customize.sh"
}

prepare_magisk_stage
started=$(date +%s)
run_installer "$CASE/fresh.log" || fail "fresh boot-mode installation failed"
elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -le 10 ] || fail "device-dependent customization exceeded 10 seconds: ${elapsed}s"
grep -Fq 'Zapret2 is ready' "$CASE/fresh.log" || fail "fresh install did not reach the bounded terminal message"
if grep -Fq 'Extracting staged module files' "$CASE/fresh.log"; then
    fail "customization reintroduced private archive extraction"
fi
[ ! -e "$UPDATE/customize.sh" ] || fail "installer-only customize.sh remained installed"
[ -x "$UPDATE/zapret2/nfqws2" ] || fail "selected runtime binary is not executable"
cmp -s /bin/true "$UPDATE/zapret2/nfqws2" || fail "Magisk ARCH was not used to select arm64"
[ -f "$UPDATE/zapret2/install-generation.meta" ] || fail "install generation is missing"
expected_sha=$(sha256sum "$ARCHIVE" | awk 'NR == 1 { print $1 }')
grep -Fxq "archive_sha256=$expected_sha" "$UPDATE/zapret2/install-generation.meta" ||
    fail "install generation is not bound to the archive"
[ ! -e "$STATE/lifecycle.lock" ] && [ ! -L "$STATE/lifecycle.lock" ] ||
    fail "successful installation leaked the lifecycle lock"

mv "$UPDATE" "$LIVE"
sed -i 's/^custom_cmdline_file=.*/custom_cmdline_file=User Options/' "$LIVE/zapret2/runtime.ini"
printf '%s\n' '--custom-user-option' > "$LIVE/zapret2/User Options"
printf '%s\n' 'user-category-state' > "$LIVE/zapret2/categories.ini"
printf '%s\n' 'user-lua-state' > "$LIVE/zapret2/lua/zapret-custom.lua"
printf '%s\n' 'custom.example' > "$LIVE/zapret2/lists/custom-user-list.txt"
printf '%s\n' 'old-core-must-not-cross' > "$LIVE/zapret2/lua/custom_funcs.lua"
chmod 0644 "$LIVE/zapret2/User Options" "$LIVE/zapret2/categories.ini" \
    "$LIVE/zapret2/lua/zapret-custom.lua" "$LIVE/zapret2/lists/custom-user-list.txt" \
    "$LIVE/zapret2/lua/custom_funcs.lua"
: > "$LIVE/disable"
chmod 0600 "$LIVE/disable"

prepare_magisk_stage
packaged_core_sha=$(sha256sum "$UPDATE/zapret2/lua/custom_funcs.lua" | awk 'NR == 1 { print $1 }')
run_installer "$CASE/update.log" || fail "state-preserving boot-mode update failed"
grep -Fxq 'user-category-state' "$UPDATE/zapret2/categories.ini" || fail "categories were not preserved"
grep -Fxq 'user-lua-state' "$UPDATE/zapret2/lua/zapret-custom.lua" || fail "approved user Lua was not preserved"
grep -Fxq 'custom.example' "$UPDATE/zapret2/lists/custom-user-list.txt" || fail "custom hostlist was not preserved"
grep -Fxq -- '--custom-user-option' "$UPDATE/zapret2/User Options" || fail "configured command line was not preserved"
[ -f "$UPDATE/disable" ] && [ ! -s "$UPDATE/disable" ] &&
    [ "$(stat -c %a "$UPDATE/disable")" = 600 ] || fail "disable marker was not preserved"
[ "$(sha256sum "$UPDATE/zapret2/lua/custom_funcs.lua" | awk 'NR == 1 { print $1 }')" = "$packaged_core_sha" ] ||
    fail "old core Lua crossed the release boundary"

echo "Magisk boot installer tests passed"
