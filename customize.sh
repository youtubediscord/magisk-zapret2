#!/system/bin/sh
SKIPUNZIP=1

##########################################################################################
# Zapret2 Magisk Module - boot-mode customization
#
# Magisk owns staging under modules_update and final publication. This script
# validates and extracts the archive itself, then performs device preparation
# and user-state carryover.
##########################################################################################

umask 077

LIVE_MODPATH="/data/adb/modules/zapret2"
EXPECTED_MODPATH="/data/adb/modules_update/zapret2"
STATE_DIR="/data/adb/zapret2-state"
LOCK_ACQUIRED=0
INSTALL_COMMITTED=0
STATE_RECOVERY_ROOT=""
TOMBSTONE_RECOVERY=""
ROLLBACK_META_RECOVERY=""
ROLLBACK_HOSTS_RECOVERY=""
RETIRE_TOMBSTONE=0
RETIRE_ROLLBACK=0

remove_state_recovery_root() {
    case "$STATE_RECOVERY_ROOT" in /data/adb/zapret2-install-state.*) ;; *) return 1 ;; esac
    [ -e "$STATE_RECOVERY_ROOT" ] || [ -L "$STATE_RECOVERY_ROOT" ] || return 0
    [ -d "$STATE_RECOVERY_ROOT" ] && [ ! -L "$STATE_RECOVERY_ROOT" ] || return 1
    rm -rf "$STATE_RECOVERY_ROOT" 2>/dev/null
}

restore_retired_state() {
    local recovery destination
    [ -n "$STATE_RECOVERY_ROOT" ] || return 0
    for recovery in "$ROLLBACK_META_RECOVERY" "$ROLLBACK_HOSTS_RECOVERY" "$TOMBSTONE_RECOVERY"; do
        [ -n "$recovery" ] || continue
        [ -e "$recovery" ] || [ -L "$recovery" ] || continue
        case "$recovery" in
            "$STATE_RECOVERY_ROOT/uninstall.tombstone") destination="$UNINSTALL_TOMBSTONE" ;;
            "$STATE_RECOVERY_ROOT/full-rollback.meta") destination="$FULL_ROLLBACK_META" ;;
            "$STATE_RECOVERY_ROOT/hosts.rollback.backup") destination="$FULL_ROLLBACK_HOSTS_BACKUP" ;;
            *) return 1 ;;
        esac
        [ -f "$recovery" ] && [ ! -L "$recovery" ] || return 1
        [ ! -e "$destination" ] && [ ! -L "$destination" ] || return 1
        mv "$recovery" "$destination" 2>/dev/null || return 1
    done
    remove_state_recovery_root
}

finish_install() {
    local rc=$?
    trap - EXIT HUP INT TERM
    if [ "$INSTALL_COMMITTED" -ne 1 ]; then
        restore_retired_state >/dev/null 2>&1 || rc=1
    fi
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || rc=1
        LOCK_ACQUIRED=0
    fi
    if [ "$INSTALL_COMMITTED" -eq 1 ] && [ -n "$STATE_RECOVERY_ROOT" ]; then
        remove_state_recovery_root >/dev/null 2>&1 || rc=1
    fi
    exit "$rc"
}

trap finish_install EXIT
trap 'ui_print "! Installation interrupted"; exit 1' HUP INT TERM

# Recovery installation cannot provide the same state-preserving transaction: Magisk removes
# the live module directory before sourcing customize.sh.  The distributable intentionally has
# no recovery update-binary, and this guard fails closed for third-party repackaging/managers.
[ "${BOOTMODE:-false}" = true ] || abort "! Zapret2 supports installation from Magisk or its APK only; recovery flashing is unsupported"
[ "$MODPATH" = "$EXPECTED_MODPATH" ] || abort "! Unexpected Magisk staging path: $MODPATH"
[ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || abort "! Magisk staging directory is missing or unsafe"

# Magisk's default set_perm_recursive expands paths without quoting. Preset
# names intentionally contain spaces, so letting Magisk own extraction turns
# path fragments into chown owner/group arguments. Bootstrap only the immutable
# package contract, validate the complete ZIP namespace before extraction, then
# apply every packaged mode through the manifest's quoted per-path callback.
INSTALL_ZIP_LISTING="$MODPATH/.zapret2-install-listing.$$"
INSTALL_ZIP_NAMES="$MODPATH/.zapret2-install-names.$$"
unzip -o "$ZIPFILE" module.prop zapret2/runtime-manifest.tsv \
    zapret2/scripts/package-contract.sh -d "$MODPATH" >&2 ||
    abort "! Cannot extract the installation contract"
[ -f "$MODPATH/module.prop" ] && [ ! -L "$MODPATH/module.prop" ] ||
    abort "! module.prop is missing or unsafe"
[ -f "$MODPATH/zapret2/runtime-manifest.tsv" ] &&
    [ ! -L "$MODPATH/zapret2/runtime-manifest.tsv" ] &&
    [ -f "$MODPATH/zapret2/scripts/package-contract.sh" ] &&
    [ ! -L "$MODPATH/zapret2/scripts/package-contract.sh" ] ||
    abort "! Package bootstrap contract is missing or unsafe"
grep -qx 'id=zapret2' "$MODPATH/module.prop" || abort "! Refusing package with unexpected module id"
. "$MODPATH/zapret2/scripts/package-contract.sh" || abort "! Cannot load the package bootstrap contract"
unzip -l "$ZIPFILE" > "$INSTALL_ZIP_LISTING" 2>/dev/null &&
    package_contract_extract_zip_names "$INSTALL_ZIP_LISTING" "$INSTALL_ZIP_NAMES" &&
    package_contract_validate_zip_names "$MODPATH" "$INSTALL_ZIP_NAMES" || {
        rm -f "$INSTALL_ZIP_LISTING" "$INSTALL_ZIP_NAMES"
        abort "! Module ZIP namespace is invalid: ${PACKAGE_CONTRACT_CODE:-unknown} ${PACKAGE_CONTRACT_DETAIL:-}"
    }
rm -f "$INSTALL_ZIP_LISTING" "$INSTALL_ZIP_NAMES" ||
    abort "! Cannot retire installation namespace evidence"
ui_print "- Extracting Zapret2 files with the package contract"
unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >&2 ||
    abort "! Cannot extract module files"
package_contract_validate_exact_tree "$MODPATH" package ||
    abort "! Extracted package tree is invalid: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_apply_modes "$MODPATH" package &&
    package_contract_validate_modes "$MODPATH" package ||
    abort "! Extracted package modes are invalid: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"

case "${ARCH:-}" in
    arm64) ARCH_DIR="arm64-v8a" ;;
    arm) ARCH_DIR="armeabi-v7a" ;;
    *) abort "! Unsupported architecture: ${ARCH:-unknown}" ;;
esac

ZAPRET_DIR="$MODPATH/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
PACKAGE_CONTRACT="$SCRIPT_DIR/package-contract.sh"
LIVE_ZAPRET_DIR="$LIVE_MODPATH/zapret2"
LIVE_NFQWS2="$LIVE_ZAPRET_DIR/nfqws2"
LIFECYCLE_LOCK_WAIT_SECONDS=5
MODDIR="$LIVE_MODPATH"
export STATE_DIR MODDIR ZAPRET_DIR SCRIPT_DIR LIFECYCLE_LOCK_WAIT_SECONDS

for required in "$COMMON_SCRIPT" "$PACKAGE_CONTRACT" "$SCRIPT_DIR/zapret-start.sh" \
    "$SCRIPT_DIR/zapret-stop.sh" "$SCRIPT_DIR/zapret-status.sh" \
    "$SCRIPT_DIR/zapret-update-guard.sh" "$ZAPRET_DIR/bin/$ARCH_DIR/nfqws2"; do
    [ -f "$required" ] && [ ! -L "$required" ] && [ -s "$required" ] ||
        abort "! Extracted module is incomplete: ${required#"$MODPATH"/}"
done

. "$COMMON_SCRIPT" || abort "! Cannot load lifecycle helpers"
. "$PACKAGE_CONTRACT" || abort "! Cannot load preservation helpers"

audit_live_install_recovery_artifacts() {
    audit_recovery_artifacts install "$LIVE_NFQWS2"
}

read_completed_rollback_meta() {
    local key value version="" module="" token="" generation="" archive=""
    local completed="" complete="" diagnostic="" seen="" size
    state_file_is_secure "$FULL_ROLLBACK_META" && [ -r "$FULL_ROLLBACK_META" ] || return 1
    size="$(wc -c < "$FULL_ROLLBACK_META" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le 4096 ] 2>/dev/null || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1 ;; esac; version="$value"; seen="${seen}v" ;;
            module_dir) case "$seen" in *m*) return 1 ;; esac; module="$value"; seen="${seen}m" ;;
            token) case "$seen" in *t*) return 1 ;; esac; token="$value"; seen="${seen}t" ;;
            generation) case "$seen" in *g*) return 1 ;; esac; generation="$value"; seen="${seen}g" ;;
            archive_sha256) case "$seen" in *a*) return 1 ;; esac; archive="$value"; seen="${seen}a" ;;
            completed_epoch) case "$seen" in *e*) return 1 ;; esac; completed="$value"; seen="${seen}e" ;;
            complete) case "$seen" in *c*) return 1 ;; esac; complete="$value"; seen="${seen}c" ;;
            diagnostic) case "$seen" in *d*) return 1 ;; esac; diagnostic="$value"; seen="${seen}d" ;;
            *) return 1 ;;
        esac
    done < "$FULL_ROLLBACK_META"
    [ "${#seen}" -eq 8 ] 2>/dev/null &&
        [ "$version" = "$FULL_ROLLBACK_VERSION" ] && [ "$module" = "$LIVE_MODPATH" ] &&
        is_safe_token "$token" && is_safe_token "$generation" &&
        [ "${#generation}" -le 128 ] 2>/dev/null && is_lower_sha256 "$archive" &&
        is_decimal "$completed" && [ "$complete" = 1 ] && [ -n "$diagnostic" ] || return 1
    ROLLBACK_META_GENERATION="$generation"
    ROLLBACK_META_ARCHIVE_SHA256="$archive"
}

safe_preserved_regular() {
    local path="$1" max_bytes="${2:-}" mode size
    [ -f "$path" ] && [ ! -L "$path" ] && path_uid_is_root "$path" &&
        path_nlink_is_one "$path" || return 1
    mode="$(stat -c %a "$path" 2>/dev/null)" || return 1
    case "$mode" in 600|644) ;; *) return 1 ;; esac
    if [ -n "$max_bytes" ]; then
        size="$(wc -c < "$path" 2>/dev/null)" || return 1
        is_decimal "$size" && [ "$size" -le "$max_bytes" ] 2>/dev/null || return 1
    fi
}

preserve_release_seed() {
    local relative="$1" source="$LIVE_MODPATH/$1" target="$MODPATH/$1"
    [ ! -e "$source" ] && [ ! -L "$source" ] && return 0
    safe_preserved_regular "$source" || return 1
    [ -f "$target" ] && [ ! -L "$target" ] || return 1
    cp "$source" "$target" && chmod 0644 "$target" && cmp -s "$source" "$target"
}

preserve_bounded_file() {
    local relative="$1" max_bytes="$2" source="$LIVE_MODPATH/$1" target="$MODPATH/$1"
    [ ! -e "$source" ] && [ ! -L "$source" ] && return 0
    safe_preserved_regular "$source" "$max_bytes" || return 1
    [ -f "$target" ] && [ ! -L "$target" ] || return 1
    cp "$source" "$target" && chmod 0644 "$target" && cmp -s "$source" "$target"
}

write_install_generation() {
    local archive generation target temporary
    archive="$(sha256sum "$ZIPFILE" 2>/dev/null | awk 'NR == 1 { print $1 }')" || return 1
    is_lower_sha256 "$archive" || return 1
    generation="$(new_lifecycle_token)" || return 1
    is_safe_token "$generation" && [ "${#generation}" -le 128 ] 2>/dev/null || return 1
    target="$ZAPRET_DIR/install-generation.meta"
    temporary="$ZAPRET_DIR/.install-generation.meta.$$.$generation"
    [ ! -e "$target" ] && [ ! -L "$target" ] && [ ! -e "$temporary" ] && [ ! -L "$temporary" ] || return 1
    {
        printf 'version=1\n'
        printf 'module_dir=%s\n' "$LIVE_MODPATH"
        printf 'generation=%s\n' "$generation"
        printf 'archive_sha256=%s\n' "$archive"
    } > "$temporary" || return 1
    chmod 0600 "$temporary" && mv "$temporary" "$target" || { rm -f "$temporary"; return 1; }
    read_install_generation_meta "$target" &&
        [ "$INSTALL_META_GENERATION" = "$generation" ] &&
        [ "$INSTALL_META_ARCHIVE_SHA256" = "$archive" ]
}

if ! acquire_lifecycle_lock; then
    abort "! Zapret2 lifecycle is busy; retry the installation in a few seconds"
fi
LOCK_ACQUIRED=1

if [ -e "$UPDATE_LOCK" ] || [ -L "$UPDATE_LOCK" ] ||
   [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ]; then
    abort "! An APK hot update is active or requires recovery"
fi

if [ -e "$LIVE_MODPATH" ] || [ -L "$LIVE_MODPATH" ]; then
    [ -d "$LIVE_MODPATH" ] && [ ! -L "$LIVE_MODPATH" ] && path_uid_is_root "$LIVE_MODPATH" ||
        abort "! Existing module directory is unsafe"
else
    retire_unverifiable_tracks_for_absent_module "$LIVE_NFQWS2" ||
        abort "! Removed-module recovery cannot prove a clean process/firewall state"
    if [ "$REMOVED_MODULE_TRACKS_RETIRED" = 1 ]; then
        ui_print "- Retired orphaned interrupted-build state from the removed module"
    fi
fi

if ! audit_live_install_recovery_artifacts; then
    abort "! Recovery state blocks installation: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unknown state}"
fi
case "$RECOVERY_ARTIFACT_CLASS" in
    clean) ;;
    rollback-complete)
        read_completed_rollback_meta &&
            read_install_generation_meta "$LIVE_ZAPRET_DIR/install-generation.meta" &&
            [ "$ROLLBACK_META_GENERATION" = "$INSTALL_META_GENERATION" ] &&
            [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] ||
            abort "! Completed rollback does not match the installed generation"
        RETIRE_ROLLBACK=1
        ;;
    *) abort "! Unsupported recovery state: $RECOVERY_ARTIFACT_CLASS" ;;
esac

if [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; then
    state_file_is_secure "$UNINSTALL_TOMBSTONE" && read_uninstall_tombstone &&
        [ "$UNINSTALL_FILE_MODULE" = "$LIVE_MODPATH" ] && ! uninstall_tombstone_owner_alive ||
        abort "! Prior uninstall is still active or its state is unsafe"
    RETIRE_TOMBSTONE=1
fi

ui_print "- Preparing Zapret2 for $ARCH_DIR"

if [ -d "$LIVE_MODPATH" ]; then
    ui_print "- Preserving user configuration"
    ACTIVE_PRESET_NAME="$(package_contract_runtime_core_value "$LIVE_MODPATH" active_preset 2>/dev/null)" || ACTIVE_PRESET_NAME=""
    if [ -n "$ACTIVE_PRESET_NAME" ]; then
        package_contract_safe_preset_name "$ACTIVE_PRESET_NAME" ||
            abort "! Existing runtime.ini selects an unsafe preset"
        preserve_release_seed zapret2/runtime.ini || abort "! Cannot preserve runtime.ini safely"
    fi

    LIVE_PRESETS="$LIVE_ZAPRET_DIR/presets"
    if [ -e "$LIVE_PRESETS" ] || [ -L "$LIVE_PRESETS" ]; then
        [ -d "$LIVE_PRESETS" ] && [ ! -L "$LIVE_PRESETS" ] || abort "! Existing preset directory is unsafe"
        for USER_PRESET_SOURCE in "$LIVE_PRESETS"/*.txt; do
            [ -e "$USER_PRESET_SOURCE" ] || [ -L "$USER_PRESET_SOURCE" ] || continue
            USER_PRESET_NAME="${USER_PRESET_SOURCE##*/}"
            package_contract_safe_preset_name "$USER_PRESET_NAME" || abort "! Existing preset has an unsafe name"
            USER_PRESET_TARGET="$ZAPRET_DIR/presets/$USER_PRESET_NAME"
            # A packaged name is built-in and is intentionally overwritten.
            [ -e "$USER_PRESET_TARGET" ] || [ -L "$USER_PRESET_TARGET" ] || {
                safe_preserved_regular "$USER_PRESET_SOURCE" 1048576 || abort "! Custom preset is unsafe or too large"
                cp "$USER_PRESET_SOURCE" "$USER_PRESET_TARGET" && chmod 0644 "$USER_PRESET_TARGET" &&
                    cmp -s "$USER_PRESET_SOURCE" "$USER_PRESET_TARGET" || abort "! Cannot preserve custom preset"
            }
        done
    fi

    LIVE_LISTS="$LIVE_ZAPRET_DIR/lists"
    if [ -e "$LIVE_LISTS" ] || [ -L "$LIVE_LISTS" ]; then
        [ -d "$LIVE_LISTS" ] && [ ! -L "$LIVE_LISTS" ] || abort "! Existing hostlist directory is unsafe"
        if find "$LIVE_LISTS" ! -type d ! -type f -print -quit | grep -q .; then
            abort "! Existing hostlists contain a symlink or special file"
        fi
        if find "$LIVE_LISTS" -type f ! -links 1 -print -quit | grep -q .; then
            abort "! Existing hostlists contain a hard-linked file"
        fi
        cp -R "$LIVE_LISTS/." "$ZAPRET_DIR/lists/" || abort "! Cannot preserve user hostlists"
    fi

    PRESET_SCAN_OUTPUT="$STATE_DIR/install-preset-scan.$$"
    sh "$SCRIPT_DIR/command-builder.sh" --scan-presets-machine "$ZAPRET_DIR" \
        > "$PRESET_SCAN_OUTPUT" 2>/dev/null || {
            rm -f "$PRESET_SCAN_OUTPUT"
            abort "! Cannot validate preserved presets"
        }
    if grep -q "$(printf '^Z2_PRESET\tQUARANTINED\t')" "$PRESET_SCAN_OUTPUT"; then
        rm -f "$PRESET_SCAN_OUTPUT"
        abort "! A preserved custom preset is incompatible"
    fi
    grep -q "$(printf '^Z2_PRESET_SUMMARY\t1\t')" "$PRESET_SCAN_OUTPUT" || {
        rm -f "$PRESET_SCAN_OUTPUT"
        abort "! Preset validation returned an invalid protocol"
    }
    rm -f "$PRESET_SCAN_OUTPUT"

    LIVE_DISABLE="$LIVE_MODPATH/disable"
    if [ -e "$LIVE_DISABLE" ] || [ -L "$LIVE_DISABLE" ]; then
        safe_preserved_regular "$LIVE_DISABLE" 0 && [ ! -s "$LIVE_DISABLE" ] ||
            abort "! Existing Magisk disable marker is unsafe"
        [ ! -e "$MODPATH/disable" ] && [ ! -L "$MODPATH/disable" ] ||
            abort "! Release unexpectedly contains a disable marker"
        cp "$LIVE_DISABLE" "$MODPATH/disable" && chmod 0600 "$MODPATH/disable" ||
            abort "! Cannot preserve the Magisk disable marker"
    fi
fi

NFQWS_SOURCE="$ZAPRET_DIR/bin/$ARCH_DIR/nfqws2"
NFQWS_TEMP="$ZAPRET_DIR/.nfqws2.install.$$"
cp "$NFQWS_SOURCE" "$NFQWS_TEMP" && chmod 0755 "$NFQWS_TEMP" && mv "$NFQWS_TEMP" "$ZAPRET_DIR/nfqws2" || {
    rm -f "$NFQWS_TEMP"
    abort "! Cannot select the $ARCH_DIR nfqws2 binary"
}

# Reapply the mutable installed subset after preserving user files and creating
# the selected runtime binary. Packaged paths were already validated and chmod'd
# through the manifest immediately after private extraction above.
chmod 0755 "$MODPATH/service.sh" "$MODPATH/uninstall.sh" "$MODPATH/action.sh" \
    "$ZAPRET_DIR/bin/arm64-v8a/nfqws2" "$ZAPRET_DIR/bin/armeabi-v7a/nfqws2" \
    "$ZAPRET_DIR/nfqws2" "$ZAPRET_DIR/scripts/"*.sh \
    "$ZAPRET_DIR/scripts/lifecycle/"*.sh "$MODPATH/system/bin/zapret2-"* ||
    abort "! Cannot apply executable permissions"
chmod 0644 "$ZAPRET_DIR/runtime.ini" "$ZAPRET_DIR/lua/zapret-custom.lua" \
    "$ZAPRET_DIR/lua/init_vars.lua" ||
    abort "! Cannot apply user configuration permissions"
find "$ZAPRET_DIR/lists" -type d -exec chmod 0755 {} + &&
    find "$ZAPRET_DIR/lists" -type f -exec chmod 0644 {} + ||
    abort "! Cannot apply hostlist permissions"

ACTIVE_PRESET_NAME="$(package_contract_runtime_core_value "$MODPATH" active_preset)" ||
    abort "! runtime.ini does not select a valid preset"
package_contract_safe_preset_name "$ACTIVE_PRESET_NAME" || abort "! runtime.ini selects an unsafe preset"
sh "$SCRIPT_DIR/command-builder.sh" --validate-strategies-machine "$ZAPRET_DIR" >/dev/null 2>&1 ||
    abort "! Strategy catalogs are incompatible"
sh "$SCRIPT_DIR/command-builder.sh" --preflight-preset-machine "$ZAPRET_DIR" \
    "$ZAPRET_DIR/presets/$ACTIVE_PRESET_NAME" "$ACTIVE_PRESET_NAME" >/dev/null 2>&1 ||
    abort "! Active preset failed validation or nfqws2 dry-run"

write_install_generation || abort "! Cannot publish the installation generation"

for required_exec in "$MODPATH/service.sh" "$MODPATH/uninstall.sh" "$MODPATH/action.sh" \
    "$SCRIPT_DIR/common.sh" "$SCRIPT_DIR/command-builder.sh" "$SCRIPT_DIR/zapret-start.sh" \
    "$SCRIPT_DIR/zapret-stop.sh" "$SCRIPT_DIR/zapret-status.sh" \
    "$SCRIPT_DIR/zapret-update-guard.sh" "$ZAPRET_DIR/nfqws2"; do
    [ -f "$required_exec" ] && [ ! -L "$required_exec" ] && [ -s "$required_exec" ] &&
        [ -x "$required_exec" ] || abort "! Prepared module is incomplete: ${required_exec#"$MODPATH"/}"
done

# Retire only authenticated terminal recovery records, at the final serialized handoff. The
# private directory exists only for this uncommon recovery transition and lets the EXIT trap put
# every record back if publication fails before the lifecycle lock is released.
if [ "$RETIRE_TOMBSTONE" -eq 1 ] || [ "$RETIRE_ROLLBACK" -eq 1 ]; then
    STATE_RECOVERY_ROOT="$(mktemp -d /data/adb/zapret2-install-state.XXXXXX 2>/dev/null)" ||
        abort "! Cannot create state retirement workspace"
    chmod 0700 "$STATE_RECOVERY_ROOT" || abort "! Cannot secure state retirement workspace"
    TOMBSTONE_RECOVERY="$STATE_RECOVERY_ROOT/uninstall.tombstone"
    ROLLBACK_META_RECOVERY="$STATE_RECOVERY_ROOT/full-rollback.meta"
    ROLLBACK_HOSTS_RECOVERY="$STATE_RECOVERY_ROOT/hosts.rollback.backup"
fi

# Ignore interactive cancellation only across the final bounded rename/unlock handoff. Every
# expensive/copying operation above remains signal-interruptible.
trap '' HUP INT TERM
if [ "$RETIRE_TOMBSTONE" -eq 1 ]; then
    state_file_is_secure "$UNINSTALL_TOMBSTONE" && read_uninstall_tombstone &&
        [ "$UNINSTALL_FILE_MODULE" = "$LIVE_MODPATH" ] && ! uninstall_tombstone_owner_alive &&
        mv "$UNINSTALL_TOMBSTONE" "$TOMBSTONE_RECOVERY" ||
        abort "! Prior uninstall state changed before commit"
fi
if [ "$RETIRE_ROLLBACK" -eq 1 ]; then
    # The candidate generation above is intentionally different. Re-authenticate the terminal
    # record against the still-live generation immediately before retiring it.
    read_completed_rollback_meta &&
        read_install_generation_meta "$LIVE_ZAPRET_DIR/install-generation.meta" &&
        [ "$ROLLBACK_META_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] ||
        abort "! Completed rollback state changed before commit"
    if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ] || [ -L "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
        state_file_is_secure "$FULL_ROLLBACK_HOSTS_BACKUP" &&
            mv "$FULL_ROLLBACK_HOSTS_BACKUP" "$ROLLBACK_HOSTS_RECOVERY" ||
            abort "! Cannot retire rollback host backup"
    fi
    mv "$FULL_ROLLBACK_META" "$ROLLBACK_META_RECOVERY" || abort "! Cannot retire completed rollback metadata"
fi

if ! audit_live_install_recovery_artifacts || [ "$RECOVERY_ARTIFACT_CLASS" != clean ]; then
    abort "! Recovery state changed during installation commit"
fi
if ! release_lifecycle_lock; then
    abort "! Cannot release the Zapret2 lifecycle lock"
fi
LOCK_ACQUIRED=0
INSTALL_COMMITTED=1

if [ -n "$STATE_RECOVERY_ROOT" ]; then
    if remove_state_recovery_root; then
        STATE_RECOVERY_ROOT=""
    else
        ui_print "! Module is ready, but obsolete root-only recovery data remains at $STATE_RECOVERY_ROOT"
    fi
fi
trap - EXIT HUP INT TERM

ui_print "- Zapret2 is ready; reboot to activate the Magisk update"
