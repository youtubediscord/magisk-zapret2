#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/magisk-boot-installer"
LIVE=/data/adb/modules/zapret2
UPDATE=/data/adb/modules_update/zapret2
STATE=/data/adb/zapret2-state
ARCHIVE="$CASE/module.zip"
PACKAGE="$CASE/package"
SOURCE="$CASE/source"
OWNED=0

fail() { echo "FAIL: Magisk boot installer: $*" >&2; exit 1; }

cleanup() {
    [ "$OWNED" = 1 ] || return 0
    [ "${Z2_KEEP_TEST_ARTIFACTS:-0}" != 1 ] || return 0
    rm -rf "$LIVE" "$UPDATE" "$STATE" "$CASE"
}

[ "$(id -u)" = 0 ] || fail "run as root"
for path in "$LIVE" "$UPDATE" "$STATE" "$CASE"; do
    [ ! -e "$path" ] && [ ! -L "$path" ] || fail "test path already exists: $path"
done
OWNED=1
trap cleanup EXIT HUP INT TERM
mkdir -p /data/adb/modules /data/adb/modules_update "$CASE"
mkdir -p "$PACKAGE" "$SOURCE"
cp "$ROOT/module.prop" "$ROOT/customize.sh" "$ROOT/service.sh" \
    "$ROOT/uninstall.sh" "$ROOT/action.sh" "$SOURCE/"
cp -R "$ROOT/system" "$ROOT/zapret2" "$SOURCE/"
mkdir -p "$SOURCE/zapret2/bin/arm64-v8a" "$SOURCE/zapret2/bin/armeabi-v7a"
cp /bin/true "$SOURCE/zapret2/bin/arm64-v8a/nfqws2"
cp /bin/false "$SOURCE/zapret2/bin/armeabi-v7a/nfqws2"
printf '%s\n' b78b52c4cd7f843da3ff0848a3430afbd401bdf2 > "$SOURCE/zapret2/upstream-zapret2.commit"
. "$SOURCE/zapret2/scripts/package-contract.sh"
package_contract_assemble_package "$SOURCE" "$PACKAGE" ||
    fail "cannot assemble installer fixture: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
(cd "$PACKAGE" && zip -qr "$ARCHIVE" module.prop customize.sh service.sh uninstall.sh action.sh system zapret2)

prepare_magisk_stage() {
    rm -rf "$UPDATE"
    mkdir -p "$UPDATE"
    unzip -oq "$ARCHIVE" customize.sh -d "$UPDATE"
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
        . "$UPDATE/customize.sh"
    ) > "$log" 2>&1
    # This is the canonical cleanup Magisk performs after customize.sh returns.
    rm -f "$UPDATE/customize.sh"
}

prepare_magisk_stage
KSU=true run_installer "$CASE/kernelsu.log" ||
    fail "KernelSU boot-mode installation failed"
grep -Fq 'for KernelSU' "$CASE/kernelsu.log" ||
    fail "KernelSU environment was not detected"

prepare_magisk_stage
APATCH=true run_installer "$CASE/apatch.log" ||
    fail "APatch boot-mode installation failed"
grep -Fq 'for APatch' "$CASE/apatch.log" ||
    fail "APatch environment was not detected"

prepare_magisk_stage
started=$(date +%s)
run_installer "$CASE/fresh.log" || fail "fresh boot-mode installation failed"
elapsed=$(( $(date +%s) - started ))
[ "$elapsed" -le 10 ] || fail "device-dependent customization exceeded 10 seconds: ${elapsed}s"
grep -Fq 'Fresh Zapret2 generation staged' "$CASE/fresh.log" ||
    fail "fresh install did not reach the bounded terminal message"
grep -Fq 'Extracting a fresh Zapret2 generation' "$CASE/fresh.log" ||
    fail "customization did not create a fresh generation"
if grep -Fq 'chown: unknown user/group' "$CASE/fresh.log"; then fail "space-bearing paths reached Magisk chown"; fi
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
sed -i 's/^active_preset=.*/active_preset=My custom.txt/' "$LIVE/zapret2/runtime.ini"
printf '%s\n' '--custom-user-option' > "$LIVE/zapret2/User Options"
printf '%s\n' 'user-category-state' > "$LIVE/zapret2/categories.ini"
printf '%s\n' 'user-lua-state' > "$LIVE/zapret2/lua/zapret-custom.lua"
printf '%s\n' 'custom.example' > "$LIVE/zapret2/lists/custom-user-list.txt"
cp "$LIVE/zapret2/presets/Default v1 (game filter).txt" "$LIVE/zapret2/presets/My custom.txt"
printf '%s\n' '# user-owned custom preset' >> "$LIVE/zapret2/presets/My custom.txt"
printf '%s\n' 'old-built-in-must-not-cross' > "$LIVE/zapret2/presets/Default v1 (game filter).txt"
printf '%s\n' 'old-core-must-not-cross' > "$LIVE/zapret2/lua/custom_funcs.lua"
chmod 0644 "$LIVE/zapret2/User Options" "$LIVE/zapret2/categories.ini" \
    "$LIVE/zapret2/lua/zapret-custom.lua" "$LIVE/zapret2/lists/custom-user-list.txt" \
    "$LIVE/zapret2/lua/custom_funcs.lua" "$LIVE/zapret2/presets/My custom.txt" \
    "$LIVE/zapret2/presets/Default v1 (game filter).txt"
: > "$LIVE/disable"
chmod 0600 "$LIVE/disable"

prepare_magisk_stage
packaged_core_sha=$(sha256sum "$PACKAGE/zapret2/lua/custom_funcs.lua" | awk 'NR == 1 { print $1 }')
packaged_custom_lua_sha=$(sha256sum "$PACKAGE/zapret2/lua/zapret-custom.lua" | awk 'NR == 1 { print $1 }')
packaged_default_sha=$(sha256sum "$PACKAGE/zapret2/presets/Default v1 (game filter).txt" | awk 'NR == 1 { print $1 }')
packaged_runtime_sha=$(sha256sum "$PACKAGE/zapret2/runtime.ini" | awk 'NR == 1 { print $1 }')
run_installer "$CASE/update.log" || fail "clean boot-mode update failed"
[ "$(sha256sum "$UPDATE/zapret2/runtime.ini" | awk 'NR == 1 { print $1 }')" = "$packaged_runtime_sha" ] ||
    fail "old runtime configuration crossed the release boundary"
[ ! -e "$UPDATE/zapret2/presets/My custom.txt" ] || fail "old custom preset crossed the release boundary"
[ ! -e "$UPDATE/zapret2/lists/custom-user-list.txt" ] || fail "old custom hostlist crossed the release boundary"
[ ! -e "$UPDATE/zapret2/categories.ini" ] || fail "retired categories were preserved"
[ ! -e "$UPDATE/zapret2/User Options" ] || fail "retired command line was preserved"
[ ! -e "$UPDATE/disable" ] && [ ! -L "$UPDATE/disable" ] ||
    fail "old disable marker crossed the release boundary"
[ "$(sha256sum "$UPDATE/zapret2/lua/custom_funcs.lua" | awk 'NR == 1 { print $1 }')" = "$packaged_core_sha" ] ||
    fail "old core Lua crossed the release boundary"
[ "$(sha256sum "$UPDATE/zapret2/lua/zapret-custom.lua" | awk 'NR == 1 { print $1 }')" = "$packaged_custom_lua_sha" ] ||
    fail "old custom Lua crossed the release boundary"
[ "$(sha256sum "$UPDATE/zapret2/presets/Default v1 (game filter).txt" | awk 'NR == 1 { print $1 }')" = "$packaged_default_sha" ] ||
    fail "old built-in preset crossed the release boundary"

echo "Magisk boot installer tests passed"
