#!/system/bin/sh
SKIPUNZIP=1

# The active root manager owns publication in modules_update. This installer
# creates one fresh release generation and never reads, merges, stops, or
# replaces the live tree.

umask 077

[ "${BOOTMODE:-false}" = true ] ||
    abort "! Zapret2 must be installed from a running root manager"
case "${MODPATH:-}" in
    /*/modules_update/zapret2) ;;
    *) abort "! Unexpected module staging path: ${MODPATH:-missing}" ;;
esac
case "$MODPATH" in
    *//*|*/./*|*/../*) abort "! Unsafe module staging path: $MODPATH" ;;
esac
[ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] ||
    abort "! Module staging directory is missing or unsafe"
[ -f "$ZIPFILE" ] && [ ! -L "$ZIPFILE" ] ||
    abort "! Module archive is missing or unsafe"

MODULE_STORAGE="${MODPATH%/modules_update/zapret2}"
[ "$MODULE_STORAGE" = /data/adb ] ||
    abort "! Unsupported root-manager module storage: $MODULE_STORAGE"
LIVE_MODPATH="$MODULE_STORAGE/modules/zapret2"
case "${APATCH:-false}:${KSU:-false}" in
    true:*) ROOT_MANAGER="APatch" ;;
    *:true) ROOT_MANAGER="KernelSU" ;;
    *) ROOT_MANAGER="Magisk" ;;
esac

# SKIPUNZIP leaves extraction to this script. Start with an empty, exact pending
# directory so a successful generation cannot inherit files from an earlier one.
find "$MODPATH" -mindepth 1 -maxdepth 1 -exec rm -rf {} + ||
    abort "! Cannot clear the module staging directory"
ui_print "- Extracting a fresh Zapret2 generation for $ROOT_MANAGER"
unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2 ||
    abort "! Cannot extract module files"

if find "$MODPATH" ! -type d ! -type f -print -quit | grep -q .; then
    abort "! Extracted module contains a link or special file"
fi
[ "$(grep -c '^id=zapret2$' "$MODPATH/module.prop" 2>/dev/null)" = 1 ] ||
    abort "! Refusing package with unexpected module identity"

case "${ARCH:-}" in
    arm64) ARCH_DIR="arm64-v8a" ;;
    arm) ARCH_DIR="armeabi-v7a" ;;
    *) abort "! Unsupported architecture: ${ARCH:-unknown}" ;;
esac

ZAPRET_DIR="$MODPATH/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
NFQWS_SOURCE="$ZAPRET_DIR/bin/$ARCH_DIR/nfqws2"
NFQWS_TARGET="$ZAPRET_DIR/nfqws2"
NFQWS_TEMP="$ZAPRET_DIR/.nfqws2.install.$$"

for required in \
    "$MODPATH/module.prop" \
    "$MODPATH/service.sh" \
    "$MODPATH/uninstall.sh" \
    "$MODPATH/action.sh" \
    "$ZAPRET_DIR/runtime-manifest.tsv" \
    "$ZAPRET_DIR/lifecycle-contract.version" \
    "$ZAPRET_DIR/upstream-zapret2.commit" \
    "$ZAPRET_DIR/upstream-zapret2.release" \
    "$ZAPRET_DIR/upstream-zapret2.archive.sha256" \
    "$ZAPRET_DIR/runtime.ini" \
    "$ZAPRET_DIR/hosts.ini" \
    "$SCRIPT_DIR/common.sh" \
    "$SCRIPT_DIR/command-builder.sh" \
    "$SCRIPT_DIR/package-contract.sh" \
    "$SCRIPT_DIR/zapret-start.sh" \
    "$SCRIPT_DIR/zapret-stop.sh" \
    "$SCRIPT_DIR/zapret-status.sh" \
    "$NFQWS_SOURCE"; do
    [ -f "$required" ] && [ ! -L "$required" ] && [ -s "$required" ] ||
        abort "! Extracted module is incomplete: ${required#"$MODPATH"/}"
done

# Release CI owns exhaustive package validation. Device installation performs a
# bounded critical-file check and one filesystem pass for canonical modes.
find "$MODPATH" -type d -exec chmod 0755 {} + &&
    find "$MODPATH" -type f -exec chmod 0644 {} + ||
    abort "! Cannot apply package permissions"
chmod 0755 \
    "$MODPATH/customize.sh" \
    "$MODPATH/service.sh" \
    "$MODPATH/uninstall.sh" \
    "$MODPATH/action.sh" \
    "$ZAPRET_DIR/bin/arm64-v8a/nfqws2" \
    "$ZAPRET_DIR/bin/armeabi-v7a/nfqws2" \
    "$SCRIPT_DIR/"*.sh \
    "$SCRIPT_DIR/lifecycle/"*.sh \
    "$MODPATH/system/bin/zapret2-"* ||
    abort "! Cannot apply executable permissions"

cp "$NFQWS_SOURCE" "$NFQWS_TEMP" &&
    chmod 0755 "$NFQWS_TEMP" &&
    mv "$NFQWS_TEMP" "$NFQWS_TARGET" || {
        rm -f "$NFQWS_TEMP"
        abort "! Cannot select the $ARCH_DIR nfqws2 binary"
    }

ARCHIVE_SHA256="$(sha256sum "$ZIPFILE" 2>/dev/null | awk 'NR == 1 { print $1 }')" ||
    abort "! Cannot identify the module archive"
case "$ARCHIVE_SHA256" in
    *[!0-9a-f]*|"") abort "! Module archive identity is invalid" ;;
esac
[ "${#ARCHIVE_SHA256}" -eq 64 ] 2>/dev/null ||
    abort "! Module archive identity is invalid"

GENERATION_FILE="$ZAPRET_DIR/install-generation.meta"
GENERATION_TEMP="$ZAPRET_DIR/.install-generation.meta.$$"
[ ! -e "$GENERATION_FILE" ] && [ ! -L "$GENERATION_FILE" ] ||
    abort "! Release unexpectedly contains installation state"
{
    printf 'version=1\n'
    printf 'module_dir=%s\n' "$LIVE_MODPATH"
    printf 'generation=%s\n' "$ARCHIVE_SHA256"
    printf 'archive_sha256=%s\n' "$ARCHIVE_SHA256"
} > "$GENERATION_TEMP" &&
    chmod 0600 "$GENERATION_TEMP" &&
    mv "$GENERATION_TEMP" "$GENERATION_FILE" ||
    abort "! Cannot publish installation generation"

for required_exec in \
    "$MODPATH/service.sh" \
    "$MODPATH/uninstall.sh" \
    "$MODPATH/action.sh" \
    "$SCRIPT_DIR/common.sh" \
    "$SCRIPT_DIR/command-builder.sh" \
    "$SCRIPT_DIR/zapret-start.sh" \
    "$SCRIPT_DIR/zapret-stop.sh" \
    "$SCRIPT_DIR/zapret-status.sh" \
    "$NFQWS_TARGET"; do
    [ -f "$required_exec" ] && [ ! -L "$required_exec" ] &&
        [ -s "$required_exec" ] && [ -x "$required_exec" ] ||
        abort "! Prepared module is incomplete: ${required_exec#"$MODPATH"/}"
done

ui_print "- Fresh Zapret2 generation staged by $ROOT_MANAGER; reboot to activate it"
