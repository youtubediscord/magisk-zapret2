#!/bin/bash
##########################################################################################
# Zapret2 Magisk Module - Local Build Script
##########################################################################################

set -e

VERSION="${1:-}"
OUTPUT_DIR="${2:-dist}"

if [ ! -f "version.series" ]; then
    echo "ERROR: version.series file is missing"
    exit 1
fi

VERSION_SERIES=$(tr -d '[:space:]' < version.series)
if [[ ! "$VERSION_SERIES" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "ERROR: invalid version.series value '$VERSION_SERIES' (expected MAJOR.MINOR)"
    exit 1
fi

if [ -n "$VERSION" ]; then
    VERSION="${VERSION#v}"
    VERSION_MAJOR="${VERSION_SERIES%%.*}"
    VERSION_MINOR="${VERSION_SERIES#*.}"
    if [[ ! "$VERSION" =~ ^${VERSION_MAJOR}\.${VERSION_MINOR}\.([1-9][0-9]{0,9})$ ]]; then
        echo "ERROR: invalid version '$VERSION' (expected ${VERSION_SERIES}.POSITIVE_VERSION_CODE)"
        exit 1
    fi
    VERSION_CODE="${BASH_REMATCH[1]}"
else
    COMMIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || true)
    if [ -z "$COMMIT_COUNT" ]; then
        COMMIT_COUNT=1
    fi
    VERSION_CODE=$((100000 + COMMIT_COUNT))
    VERSION="${VERSION_SERIES}.${VERSION_CODE}"
fi

if (( 10#$VERSION_CODE > 2100000000 )); then
    echo "ERROR: versionCode '$VERSION_CODE' exceeds the Android limit"
    exit 1
fi

echo "Building Zapret2 Magisk Module v$VERSION"
echo "=========================================="

# Create output directory
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR_ABS="$(cd "$OUTPUT_DIR" && pwd -P)"

# Check for binaries
MISSING_BIN=0
for arch in arm64-v8a armeabi-v7a; do
    if [ ! -s "zapret2/bin/$arch/nfqws2" ] || [ -L "zapret2/bin/$arch/nfqws2" ]; then
        echo "ERROR: Missing, empty, or unsafe binary for $arch"
        MISSING_BIN=1
    fi
done

if [ $MISSING_BIN -eq 1 ]; then
    echo "Refusing to create a module ZIP that customize.sh cannot install."
    exit 1
fi

# Update version in module.prop
sed -i "s/^version=.*/version=v$VERSION/" module.prop
sed -i "s/^versionCode=.*/versionCode=$VERSION_CODE/" module.prop

# Make scripts executable
chmod +x customize.sh service.sh uninstall.sh action.sh 2>/dev/null || true
chmod +x zapret2/scripts/*.sh 2>/dev/null || true
chmod +x zapret2/scripts/lifecycle/*.sh 2>/dev/null || true
chmod 0755 system/bin/zapret2-start system/bin/zapret2-stop system/bin/zapret2-status system/bin/zapret2-restart system/bin/zapret2-full-rollback

EXPECTED_OWNER_PROTOCOL_LINE='owner_protocol|6|zapret2-firewall'
ACTUAL_OWNER_PROTOCOL_LINE="$(sed -n '2p' zapret2/runtime-manifest.tsv)"
ACTUAL_OWNER_PROTOCOL_LINE="${ACTUAL_OWNER_PROTOCOL_LINE%"$(printf '\r')"}"
[ "$ACTUAL_OWNER_PROTOCOL_LINE" = "$EXPECTED_OWNER_PROTOCOL_LINE" ] || {
    echo "ERROR: incompatible owner protocol in zapret2/runtime-manifest.tsv"
    exit 1
}
. ./zapret2/scripts/package-contract.sh
package_contract_apply_modes "$PWD" package || {
    echo "ERROR: source tree violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
package_contract_validate_all "$PWD" package || {
    echo "ERROR: source catalog violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}

WRAPPER_EXPECTED="$(mktemp)"
WRAPPER_ACTUAL="$(mktemp)"
ZIP_LIST="$(mktemp)"
UPDATE_BINARY_ACTUAL="$(mktemp)"
PACKAGE_META_ROOT="$(mktemp -d)"
PACKAGE_ASSEMBLY_ROOT="$(mktemp -d)"
PACKAGE_VALIDATE_ROOT="$(mktemp -d)"
cleanup_build_temps() {
    rm -f "$WRAPPER_EXPECTED" "$WRAPPER_ACTUAL" "$ZIP_LIST" "$UPDATE_BINARY_ACTUAL"
    [ -z "${PACKAGE_META_ROOT:-}" ] || rm -rf "$PACKAGE_META_ROOT"
    [ -z "${PACKAGE_ASSEMBLY_ROOT:-}" ] || rm -rf "$PACKAGE_ASSEMBLY_ROOT"
    [ -z "${PACKAGE_VALIDATE_ROOT:-}" ] || rm -rf "$PACKAGE_VALIDATE_ROOT"
}
trap cleanup_build_temps EXIT
trap 'exit 1' HUP INT TERM

# Match the immutable command-entry contract enforced by customize.sh, the
# Android hot updater, and the release workflow.
test -d system/bin
test ! -L system
test ! -L system/bin
test -f zapret2/scripts/zapret-update-guard.sh
test ! -L zapret2/scripts/zapret-update-guard.sh
test -s zapret2/scripts/zapret-update-guard.sh
test -x zapret2/scripts/zapret-update-guard.sh
test "$(sed -n '1p' zapret2/scripts/zapret-update-guard.sh)" = '#!/system/bin/sh'
if LC_ALL=C grep -q "$(printf '\r')" zapret2/scripts/zapret-update-guard.sh; then exit 1; fi
test -f zapret2/scripts/zapret-full-rollback.sh
test ! -L zapret2/scripts/zapret-full-rollback.sh
test -x zapret2/scripts/zapret-full-rollback.sh
test "$(sed -n '1p' zapret2/scripts/zapret-full-rollback.sh)" = '#!/system/bin/sh'
if LC_ALL=C grep -q "$(printf '\r')" zapret2/scripts/zapret-full-rollback.sh; then exit 1; fi
for purge_script in zapret2/scripts/lifecycle/purge-contract.sh zapret2/scripts/lifecycle/zapret-purge.sh; do
    test -f "$purge_script"
    test ! -L "$purge_script"
    test -s "$purge_script"
    test -x "$purge_script"
    test "$(sed -n '1p' "$purge_script")" = '#!/system/bin/sh'
    if LC_ALL=C grep -q "$(printf '\r')" "$purge_script"; then exit 1; fi
done
for command_name in start stop status restart full-rollback; do
    wrapper="system/bin/zapret2-$command_name"
    target="/data/adb/modules/zapret2/zapret2/scripts/zapret-$command_name.sh"
    test -f "$wrapper"
    test ! -L "$wrapper"
    test -x "$wrapper"
    {
        printf '%s\n' '#!/system/bin/sh'
        printf 'exec %s "$@"\n' "$target"
    } > "$WRAPPER_EXPECTED"
    cmp -s "$WRAPPER_EXPECTED" "$wrapper"
done

# Create ZIP
ZIP_NAME="zapret2-magisk-v$VERSION.zip"
ZIP_PATH="$OUTPUT_DIR_ABS/$ZIP_NAME"

echo "Creating $ZIP_NAME..."

RECOVERY_UPDATE_BINARY="META-INF/com/google/android/update-binary"
NORMALIZED_UPDATE_BINARY="$PACKAGE_META_ROOT/$RECOVERY_UPDATE_BINARY"
test -f "$RECOVERY_UPDATE_BINARY"
test ! -L "$RECOVERY_UPDATE_BINARY"
mkdir -p "$(dirname "$NORMALIZED_UPDATE_BINARY")"
tr -d '\r' < "$RECOVERY_UPDATE_BINARY" > "$NORMALIZED_UPDATE_BINARY"
chmod 0755 "$NORMALIZED_UPDATE_BINARY"
test "$(sed -n '1p' "$NORMALIZED_UPDATE_BINARY")" = '#!/sbin/sh'
if LC_ALL=C grep -q "$(printf '\r')" "$NORMALIZED_UPDATE_BINARY"; then exit 1; fi

package_contract_assemble_package "$PWD" "$PACKAGE_ASSEMBLY_ROOT" || {
    echo "ERROR: cannot assemble exact runtime-manifest package: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
mkdir -p "$PACKAGE_ASSEMBLY_ROOT/META-INF/com/google/android"
cp META-INF/com/google/android/updater-script "$PACKAGE_ASSEMBLY_ROOT/META-INF/com/google/android/updater-script"
cp "$NORMALIZED_UPDATE_BINARY" "$PACKAGE_ASSEMBLY_ROOT/$RECOVERY_UPDATE_BINARY"
chmod 0644 "$PACKAGE_ASSEMBLY_ROOT/META-INF/com/google/android/updater-script"
chmod 0755 "$PACKAGE_ASSEMBLY_ROOT/$RECOVERY_UPDATE_BINARY"

# Never update an existing archive in place: Info-ZIP otherwise retains files
# deleted from the exact manifest-derived assembly tree.
rm -f "$ZIP_PATH"
(
    cd "$PACKAGE_ASSEMBLY_ROOT"
    zip -r "$ZIP_PATH" META-INF module.prop customize.sh service.sh uninstall.sh action.sh system zapret2
)

# Fail locally on the same missing/duplicate/type/mode/content regressions that
# are release blockers in CI.
zipinfo -1 "$ZIP_PATH" | tee "$ZIP_LIST"
package_contract_validate_zip_names "$PWD" "$ZIP_LIST" || {
    echo "ERROR: ZIP entries violate runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
unzip -q "$ZIP_PATH" -d "$PACKAGE_VALIDATE_ROOT"
package_contract_validate_all "$PACKAGE_VALIDATE_ROOT" package || {
    echo "ERROR: extracted ZIP violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
package_contract_validate_exact_tree "$PACKAGE_VALIDATE_ROOT" package allow-meta || {
    echo "ERROR: extracted ZIP contains undeclared entries: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
package_contract_validate_modes "$PACKAGE_VALIDATE_ROOT" package || {
    echo "ERROR: ZIP modes violate runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    exit 1
}
grep -Fxq 'system/bin/' "$ZIP_LIST"
for command_name in start stop status restart full-rollback; do
    entry="system/bin/zapret2-$command_name"
    target="/data/adb/modules/zapret2/zapret2/scripts/zapret-$command_name.sh"
    test "$(grep -Fxc "$entry" "$ZIP_LIST")" -eq 1
    zipinfo -l "$ZIP_PATH" "$entry" | grep -Eq '^-rwxr-xr-x[[:space:]]'
    {
        printf '%s\n' '#!/system/bin/sh'
        printf 'exec %s "$@"\n' "$target"
    } > "$WRAPPER_EXPECTED"
    unzip -p "$ZIP_PATH" "$entry" > "$WRAPPER_ACTUAL"
    cmp -s "$WRAPPER_EXPECTED" "$WRAPPER_ACTUAL"
done
UPDATE_GUARD_ENTRY='zapret2/scripts/zapret-update-guard.sh'
test "$(grep -Fxc "$UPDATE_GUARD_ENTRY" "$ZIP_LIST")" -eq 1
zipinfo -l "$ZIP_PATH" "$UPDATE_GUARD_ENTRY" | grep -Eq '^-rwxr-xr-x[[:space:]]'
unzip -p "$ZIP_PATH" "$UPDATE_GUARD_ENTRY" > "$WRAPPER_ACTUAL"
test -s "$WRAPPER_ACTUAL"
cmp -s "$UPDATE_GUARD_ENTRY" "$WRAPPER_ACTUAL"
test "$(sed -n '1p' "$WRAPPER_ACTUAL")" = '#!/system/bin/sh'
if LC_ALL=C grep -q "$(printf '\r')" "$WRAPPER_ACTUAL"; then exit 1; fi
ROLLBACK_SCRIPT_ENTRY='zapret2/scripts/zapret-full-rollback.sh'
test "$(grep -Fxc "$ROLLBACK_SCRIPT_ENTRY" "$ZIP_LIST")" -eq 1
zipinfo -l "$ZIP_PATH" "$ROLLBACK_SCRIPT_ENTRY" | grep -Eq '^-rwxr-xr-x[[:space:]]'
unzip -p "$ZIP_PATH" "$ROLLBACK_SCRIPT_ENTRY" > "$WRAPPER_ACTUAL"
cmp -s "$ROLLBACK_SCRIPT_ENTRY" "$WRAPPER_ACTUAL"
if LC_ALL=C grep -q "$(printf '\r')" "$WRAPPER_ACTUAL"; then exit 1; fi
for PURGE_SCRIPT_ENTRY in \
    zapret2/scripts/lifecycle/purge-contract.sh \
    zapret2/scripts/lifecycle/zapret-purge.sh; do
    test "$(grep -Fxc "$PURGE_SCRIPT_ENTRY" "$ZIP_LIST")" -eq 1
    zipinfo -l "$ZIP_PATH" "$PURGE_SCRIPT_ENTRY" | grep -Eq '^-rwxr-xr-x[[:space:]]'
    unzip -p "$ZIP_PATH" "$PURGE_SCRIPT_ENTRY" > "$WRAPPER_ACTUAL"
    test -s "$WRAPPER_ACTUAL"
    cmp -s "$PURGE_SCRIPT_ENTRY" "$WRAPPER_ACTUAL"
    test "$(sed -n '1p' "$WRAPPER_ACTUAL")" = '#!/system/bin/sh'
    if LC_ALL=C grep -q "$(printf '\r')" "$WRAPPER_ACTUAL"; then exit 1; fi
done
test "$(grep -Fxc 'action.sh' "$ZIP_LIST")" -eq 1
zipinfo -l "$ZIP_PATH" action.sh | grep -Eq '^-rwxr-xr-x[[:space:]]'
test "$(grep -Fxc "$RECOVERY_UPDATE_BINARY" "$ZIP_LIST")" -eq 1
zipinfo -l "$ZIP_PATH" "$RECOVERY_UPDATE_BINARY" | grep -Eq '^-rwxr-xr-x[[:space:]]'
unzip -p "$ZIP_PATH" "$RECOVERY_UPDATE_BINARY" > "$UPDATE_BINARY_ACTUAL"
cmp -s "$NORMALIZED_UPDATE_BINARY" "$UPDATE_BINARY_ACTUAL"
test "$(sed -n '1p' "$UPDATE_BINARY_ACTUAL")" = '#!/sbin/sh'
if LC_ALL=C grep -q "$(printf '\r')" "$UPDATE_BINARY_ACTUAL"; then exit 1; fi

echo ""
echo "Build complete: $ZIP_PATH"
echo ""

# Show file size
ls -lh "$ZIP_PATH"

# Verify ZIP structure
echo ""
echo "ZIP contents:"
unzip -l "$ZIP_PATH" | head -30

cleanup_build_temps
trap - EXIT HUP INT TERM
