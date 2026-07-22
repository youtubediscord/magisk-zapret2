#!/system/bin/sh
# One-shot irreversible module cleanup shared by the APK, CLI and Magisk Action.

umask 077
LIFECYCLE_DIR="$(CDPATH=; cd "$(dirname "$0")" 2>/dev/null && pwd -P)" || exit 1
SCRIPT_DIR="$(dirname "$LIFECYCLE_DIR")"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
PURGE_CONTRACT="$LIFECYCLE_DIR/purge-contract.sh"
UNINSTALL_SCRIPT="$MODDIR/uninstall.sh"

case "$MODDIR" in /data/adb/modules/zapret2) ;; *) echo "ERROR: non-canonical purge module path" >&2; exit 1 ;; esac
for required in "$COMMON_SCRIPT" "$PURGE_CONTRACT" "$UNINSTALL_SCRIPT"; do
    [ -f "$required" ] && [ ! -L "$required" ] || { echo "ERROR: purge dependency is unavailable" >&2; exit 1; }
done

STATE_DIR="/data/adb/zapret2-state"
. "$COMMON_SCRIPT" || exit 1
. "$PURGE_CONTRACT" || exit 1
PURGE_REQUEST="$Z2_PURGE_REQUEST"
export PURGE_REQUEST

Z2_PURGE_MACHINE=0
Z2_PURGE_DIAGNOSTIC=""

purge_report() {
    local status="$1" process_clean="$2" firewall_clean="$3" module_removed="$4"
    local state_removed="$5" external_removed="$6" reboot_required="$7" diagnostic="$8"
    diagnostic="$(printf '%s' "$diagnostic" | tr '\r\n' '  ' | cut -c 1-1024)"
    if [ "$Z2_PURGE_MACHINE" = 1 ]; then
        printf 'Z2_PURGE_VERSION=%s\n' "$Z2_PURGE_PROTOCOL_VERSION"
        printf 'Z2_PURGE_STATUS=%s\n' "$status"
        printf 'Z2_PURGE_PROCESS_CLEAN=%s\n' "$process_clean"
        printf 'Z2_PURGE_FIREWALL_CLEAN=%s\n' "$firewall_clean"
        printf 'Z2_PURGE_MODULE_REMOVED=%s\n' "$module_removed"
        printf 'Z2_PURGE_STATE_REMOVED=%s\n' "$state_removed"
        printf 'Z2_PURGE_EXTERNAL_REMOVED=%s\n' "$external_removed"
        printf 'Z2_PURGE_APK_TOUCHED=0\n'
        printf 'Z2_PURGE_REBOOT_REQUIRED=%s\n' "$reboot_required"
        printf 'Z2_PURGE_DIAGNOSTIC=%s\n' "$diagnostic"
        printf 'Z2_PURGE_COMPLETE=1\n'
    else
        printf '%s\n' "$diagnostic"
    fi
}

purge_prepare_report() {
    local status="$1" token="$2" diagnostic="$3"
    diagnostic="$(printf '%s' "$diagnostic" | tr '\r\n' '  ' | cut -c 1-512)"
    if [ "$Z2_PURGE_MACHINE" = 1 ]; then
        printf 'Z2_PURGE_PREPARE_VERSION=%s\n' "$Z2_PURGE_PROTOCOL_VERSION"
        printf 'Z2_PURGE_PREPARE_STATUS=%s\n' "$status"
        printf 'Z2_PURGE_PREPARE_TOKEN=%s\n' "$token"
        printf 'Z2_PURGE_PREPARE_DIAGNOSTIC=%s\n' "$diagnostic"
        printf 'Z2_PURGE_PREPARE_COMPLETE=1\n'
    else
        printf '%s\n' "$diagnostic"
    fi
}

remove_request_if_exact() {
    [ -e "$Z2_PURGE_REQUEST" ] || [ -L "$Z2_PURGE_REQUEST" ] || return 0
    z2_purge_request_is_secure || return 1
    rm -f "$Z2_PURGE_REQUEST" 2>/dev/null || return 1
    [ ! -e "$Z2_PURGE_REQUEST" ] && [ ! -L "$Z2_PURGE_REQUEST" ]
}

prepare_purge() {
    local source="$1" token created tmp
    case "$source" in app|magisk|cli) ;; *) purge_prepare_report error "" "invalid purge source"; return 1 ;; esac
    [ "$(id -u 2>/dev/null)" = 0 ] || { purge_prepare_report blocked "" "root access is required"; return 1; }
    z2_purge_module_identity_is_exact "$MODDIR" || { purge_prepare_report blocked "" "installed module identity is unsafe"; return 1; }
    ensure_state_dir && z2_purge_state_dir_is_secure || { purge_prepare_report error "" "secure purge state is unavailable"; return 1; }
    if [ -e "$UPDATE_LOCK" ] || [ -L "$UPDATE_LOCK" ] ||
       [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ] ||
       [ -e "$UPDATE_CLEANUP" ] || [ -L "$UPDATE_CLEANUP" ] ||
       [ -e "$FULL_ROLLBACK_TRANSACTION" ] || [ -L "$FULL_ROLLBACK_TRANSACTION" ]; then
        purge_prepare_report blocked "" "another update or rollback transaction is active"
        return 1
    fi
    if [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; then
        state_file_is_secure "$UNINSTALL_TOMBSTONE" && read_uninstall_tombstone &&
            [ "$UNINSTALL_FILE_MODULE" = "$MODDIR" ] && ! uninstall_tombstone_owner_alive || {
                purge_prepare_report blocked "" "uninstall evidence is active, malformed, or unsafe"
                return 1
            }
    fi
    if [ -e "$MODDIR/remove" ] || [ -L "$MODDIR/remove" ]; then
        [ -f "$MODDIR/remove" ] && [ ! -L "$MODDIR/remove" ] &&
            z2_purge_path_uid_is_root "$MODDIR/remove" && path_mode_is_0600 "$MODDIR/remove" &&
            [ "$(wc -c < "$MODDIR/remove" 2>/dev/null)" = 0 ] || {
                purge_prepare_report blocked "" "module removal marker is unsafe"
                return 1
            }
    fi
    if [ -e "$Z2_PURGE_REQUEST" ] || [ -L "$Z2_PURGE_REQUEST" ]; then
        if z2_purge_request_is_live; then
            purge_prepare_report blocked "" "another irreversible purge confirmation is already armed"
            return 1
        fi
        remove_request_if_exact || { purge_prepare_report blocked "" "stale purge request is unsafe"; return 1; }
    fi
    token="$(new_lifecycle_token)" || { purge_prepare_report error "" "cannot create one-time purge token"; return 1; }
    z2_purge_is_safe_token "$token" || { purge_prepare_report error "" "generated purge token is invalid"; return 1; }
    created="$(date +%s 2>/dev/null)" || { purge_prepare_report error "" "cannot read confirmation time"; return 1; }
    z2_purge_is_decimal "$created" && read_current_boot_id || {
        purge_prepare_report error "" "cannot bind confirmation to the current boot"
        return 1
    }
    tmp="$Z2_PURGE_REQUEST.tmp.$$.$token"
    state_path_is_managed_file "$tmp" && [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || {
        purge_prepare_report error "" "one-time confirmation target is unsafe"
        return 1
    }
    {
        printf 'version=%s\n' "$Z2_PURGE_PROTOCOL_VERSION"
        printf 'source=%s\n' "$source"
        printf 'token=%s\n' "$token"
        printf 'created_epoch=%s\n' "$created"
        printf 'boot_id=%s\n' "$CURRENT_BOOT_ID"
        printf 'module_dir=%s\n' "$Z2_PURGE_CANONICAL_MODULE_DIR"
    } > "$tmp" || { rm -f "$tmp"; purge_prepare_report error "" "cannot write one-time confirmation"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || {
        rm -f "$tmp"
        purge_prepare_report error "" "cannot secure one-time confirmation"
        return 1
    }
    mv -f "$tmp" "$Z2_PURGE_REQUEST" 2>/dev/null || {
        rm -f "$tmp"
        purge_prepare_report error "" "cannot publish one-time confirmation"
        return 1
    }
    if ! sync >/dev/null 2>&1; then
        remove_request_if_exact >/dev/null 2>&1 || true
        purge_prepare_report error "" "one-time confirmation was not durable"
        return 1
    fi
    if ! z2_purge_request_is_live || [ "$Z2_PURGE_REQUEST_SOURCE" != "$source" ] ||
       [ "$Z2_PURGE_REQUEST_TOKEN" != "$token" ]; then
        remove_request_if_exact >/dev/null 2>&1 || true
        purge_prepare_report error "" "one-time confirmation verification failed"
        return 1
    fi
    purge_prepare_report armed "$token" "irreversible purge armed for 120 seconds"
}

publish_remove_marker() {
    local marker="$MODDIR/remove" tmp="$MODDIR/.remove.purge.$$.$Z2_PURGE_REQUEST_TOKEN"
    if [ -e "$marker" ] || [ -L "$marker" ]; then
        [ -f "$marker" ] && [ ! -L "$marker" ] && z2_purge_path_uid_is_root "$marker" &&
            [ "$(wc -c < "$marker" 2>/dev/null)" = 0 ] || return 1
        return 0
    fi
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    : > "$tmp" || return 1
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv "$tmp" "$marker" 2>/dev/null || { rm -f "$tmp"; return 1; }
    [ -f "$marker" ] && [ ! -L "$marker" ] && [ "$(wc -c < "$marker" 2>/dev/null)" = 0 ]
}

commit_purge() {
    local source="$1" token="$2" uninstall_output uninstall_rc=0 cleanup_rc=0
    case "$source" in app|magisk|cli) ;; *) purge_report error 0 0 0 0 0 0 "invalid purge source"; return 1 ;; esac
    z2_purge_is_safe_token "$token" || { purge_report error 0 0 0 0 0 0 "invalid purge token"; return 1; }
    [ "$(id -u 2>/dev/null)" = 0 ] || { purge_report blocked 0 0 0 0 0 0 "root access is required"; return 1; }
    z2_purge_request_is_live && [ "$Z2_PURGE_REQUEST_SOURCE" = "$source" ] &&
        [ "$Z2_PURGE_REQUEST_TOKEN" = "$token" ] || {
            purge_report blocked 0 0 0 0 0 0 "purge confirmation is missing, expired, or belongs to another caller"
            return 1
        }
    remove_request_if_exact || { purge_report error 0 0 0 0 0 0 "one-time purge request could not be consumed"; return 1; }
    sync >/dev/null 2>&1 || { purge_report error 0 0 0 0 0 0 "purge request retirement was not durable"; return 1; }
    z2_purge_module_identity_is_exact "$MODDIR" || { purge_report blocked 0 0 0 0 0 0 "installed module identity changed"; return 1; }

    uninstall_output="$(MODPATH="$MODDIR" /system/bin/sh "$UNINSTALL_SCRIPT" 2>&1)" || uninstall_rc=$?
    if [ "$uninstall_rc" -ne 0 ]; then
        purge_report blocked 0 0 0 0 0 1 "verified service/firewall uninstall failed: $uninstall_output"
        return 1
    fi
    publish_remove_marker || { purge_report partial 1 1 0 0 0 1 "cannot publish the permanent module-removal gate"; return 1; }

    z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_PENDING_DIR" || cleanup_rc=1
    z2_purge_remove_external_workspaces || cleanup_rc=1
    z2_purge_remove_legacy_files || cleanup_rc=1
    z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_MODULE_DIR" || cleanup_rc=1
    z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_STATE_DIR" || cleanup_rc=1
    sync >/dev/null 2>&1 || cleanup_rc=1

    if [ "$cleanup_rc" -ne 0 ] || [ -e "$Z2_PURGE_CANONICAL_MODULE_DIR" ] ||
       [ -L "$Z2_PURGE_CANONICAL_MODULE_DIR" ] || [ -e "$Z2_PURGE_CANONICAL_STATE_DIR" ] ||
       [ -L "$Z2_PURGE_CANONICAL_STATE_DIR" ]; then
        purge_report partial 1 1 0 0 0 1 "service and firewall are clean, but one or more module artifacts remain"
        return 1
    fi
    purge_report complete 1 1 1 1 1 1 "Zapret2 module data was permanently removed; APK preserved; reboot required"
}

clear_installed_apk_private_data() {
    local package="com.zapret2.app" user
    command -v am >/dev/null 2>&1 && command -v pm >/dev/null 2>&1 || return 1
    user="$(am get-current-user 2>/dev/null)" || return 1
    z2_purge_is_decimal "$user" || return 1
    if ! pm path --user "$user" "$package" 2>/dev/null | grep -q '^package:'; then
        # No installed APK means there is no APK-private state to retain or clear.
        return 0
    fi
    pm clear --user "$user" "$package" >/dev/null 2>&1
}

magisk_action() {
    local token
    Z2_PURGE_MACHINE=0
    if z2_purge_request_is_live && [ "$Z2_PURGE_REQUEST_SOURCE" = magisk ]; then
        token="$Z2_PURGE_REQUEST_TOKEN"
        echo "Second confirmation received. Permanently removing Zapret2 module data..."
        commit_purge magisk "$token" || return 1
        if clear_installed_apk_private_data; then
            echo "Zapret2 app data cleared; installed APK preserved. Reboot required."
        else
            echo "WARNING: module cleanup completed, but APK-private data could not be cleared." >&2
            return 1
        fi
        return
    fi
    remove_request_if_exact >/dev/null 2>&1 || true
    prepare_purge magisk || return 1
    echo "WARNING: this permanently deletes the module, settings, lists, logs and recovery data."
    echo "The Zapret2 APK is preserved. Press the Magisk Action button again within 120 seconds to confirm."
}

case "${1:-}" in
    --prepare)
        [ "${3:-}" = --machine ] && Z2_PURGE_MACHINE=1
        prepare_purge "${2:-}"
        ;;
    --commit)
        [ "${4:-}" = --machine ] && Z2_PURGE_MACHINE=1
        commit_purge "${2:-}" "${3:-}"
        ;;
    --magisk-action) magisk_action ;;
    *)
        echo "usage: $0 --prepare {app|magisk|cli} --machine | --commit {app|magisk|cli} TOKEN --machine | --magisk-action" >&2
        exit 2
        ;;
esac
