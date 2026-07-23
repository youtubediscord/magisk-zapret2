#!/system/bin/sh
##########################################################################################
# Zapret2 Magisk Module - Uninstall Script
##########################################################################################

MODPATH="${MODPATH:-/data/adb/modules/zapret2}"
case "$MODPATH" in
    /data/adb/modules/zapret2) ;;
    *) printf '%s\n' "ERROR: refusing unexpected uninstall path: $MODPATH" >&2; exit 1 ;;
esac
ZAPRET_DIR="$MODPATH/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
STOP_SCRIPT="$SCRIPT_DIR/zapret-stop.sh"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
PURGE_CONTRACT="$SCRIPT_DIR/lifecycle/purge-contract.sh"
EXPECTED_STATE_DIR="/data/adb/zapret2-state"
# Uninstall owns the same fixed privileged namespace as the installer. The
# shared helpers permit STATE_DIR overrides for isolated callers, but an
# environment inherited from a root manager must not redirect live cleanup.
STATE_DIR="$EXPECTED_STATE_DIR"
STOP_STATUS=127
STOP_MAX_ATTEMPTS=3
STOP_RETRY_DELAY_SECONDS=1
LOCK_ACQUIRED=0
LEGACY_RUNTIME_OWNED=0
COMPLETED_ROLLBACK_PENDING=0
ROLLBACK_GENERATION=""
ROLLBACK_ARCHIVE_SHA256=""

# Keep the outer uninstall retry bounded even when another lifecycle caller is
# present.  A busy lock is never deleted by this script.
LIFECYCLE_LOCK_WAIT_SECONDS=3
export LIFECYCLE_LOCK_WAIT_SECONDS

LEGACY_PIDFILE="/data/local/tmp/nfqws2.pid"
LEGACY_OWNER_STATE="/data/local/tmp/zapret2-owner.state"
LEGACY_CMDLINE_FILE="/data/local/tmp/nfqws2-cmdline.txt"
LEGACY_STARTUP_LOG="/data/local/tmp/nfqws2-startup.log"
LEGACY_ERROR_LOG="/data/local/tmp/nfqws2-error.log"
LEGACY_DEBUG_LOG="/data/local/tmp/nfqws2-debug.log"
LEGACY_LOGFILE="/data/local/tmp/zapret2.log"
LEGACY_LOGFILE_PREVIOUS="/data/local/tmp/zapret2.log.1"
LEGACY_OWNER_MARKER="/data/local/tmp/zapret2-runtime.owner"

report_warning() {
    printf '%s\n' "WARNING: $1" >&2
    /system/bin/log -p w -t "Zapret2" "$1" 2>/dev/null
}

report_error() {
    printf '%s\n' "ERROR: $1" >&2
    /system/bin/log -p e -t "Zapret2" "$1" 2>/dev/null
}

report_notice() {
    printf '%s\n' "$1"
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

if [ ! -f "$COMMON_SCRIPT" ] || [ -L "$COMMON_SCRIPT" ]; then
    report_error "zapret2 common lifecycle helpers are unavailable; state was preserved"
    exit 1
fi

MODDIR="$MODPATH"
. "$COMMON_SCRIPT"
command -v audit_recovery_artifacts >/dev/null 2>&1 || {
    report_error "common lifecycle helpers lack the recovery-artifact audit"
    exit 1
}

INSTALL_GENERATION_META="$MODPATH/zapret2/install-generation.meta"

mode_is_0600() {
    local path="$1" mode listing
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
        [ "$mode" = 600 ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    set -- $listing
    case "${1:-}" in -rw-------*) return 0 ;; *) return 1 ;; esac
}

valid_archive_sha256() {
    [ "${#1}" -eq 64 ] 2>/dev/null || return 1
    case "$1" in *[!0-9a-f]*) return 1 ;; *) return 0 ;; esac
}

read_install_generation_meta() {
    local key value version="" module="" generation="" archive=""
    local seen_version=0 seen_module=0 seen_generation=0 seen_archive=0 size
    [ -f "$INSTALL_GENERATION_META" ] && [ ! -L "$INSTALL_GENERATION_META" ] &&
        path_uid_is_root "$INSTALL_GENERATION_META" && mode_is_0600 "$INSTALL_GENERATION_META" || return 1
    size="$(wc -c < "$INSTALL_GENERATION_META" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le 1024 ] 2>/dev/null || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) [ "$seen_version" -eq 0 ] || return 1; version="$value"; seen_version=1 ;;
            module_dir) [ "$seen_module" -eq 0 ] || return 1; module="$value"; seen_module=1 ;;
            generation) [ "$seen_generation" -eq 0 ] || return 1; generation="$value"; seen_generation=1 ;;
            archive_sha256) [ "$seen_archive" -eq 0 ] || return 1; archive="$value"; seen_archive=1 ;;
            *) return 1 ;;
        esac
    done < "$INSTALL_GENERATION_META"
    [ "$seen_version" -eq 1 ] && [ "$version" = 1 ] &&
        [ "$seen_module" -eq 1 ] && [ "$module" = "$MODPATH" ] &&
        [ "$seen_generation" -eq 1 ] && is_safe_token "$generation" &&
        [ "${#generation}" -le 128 ] 2>/dev/null &&
        [ "$seen_archive" -eq 1 ] && valid_archive_sha256 "$archive" || return 1
    INSTALL_META_GENERATION="$generation"
    INSTALL_META_ARCHIVE_SHA256="$archive"
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
        [ "$version" = "$FULL_ROLLBACK_VERSION" ] && [ "$module" = "$MODPATH" ] &&
        is_safe_token "$token" && is_safe_token "$generation" && [ "${#generation}" -le 128 ] 2>/dev/null &&
        valid_archive_sha256 "$archive" && is_decimal "$completed" && [ "$complete" = 1 ] &&
        [ -n "$diagnostic" ] || return 1
    ROLLBACK_META_GENERATION="$generation"
    ROLLBACK_META_ARCHIVE_SHA256="$archive"
}

authenticate_completed_rollback_generation() {
    read_completed_rollback_meta && read_install_generation_meta || return 1
    [ "$ROLLBACK_META_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] || return 1
    ROLLBACK_GENERATION="$ROLLBACK_META_GENERATION"
    ROLLBACK_ARCHIVE_SHA256="$ROLLBACK_META_ARCHIVE_SHA256"
    COMPLETED_ROLLBACK_PENDING=1
}

state_dir_resolves_exactly() {
    local resolved
    [ "$STATE_DIR" = "$EXPECTED_STATE_DIR" ] || return 1
    [ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || return 1
    resolved="$(CDPATH=; cd "$STATE_DIR" 2>/dev/null && pwd -P)" || return 1
    [ "$resolved" = "$EXPECTED_STATE_DIR" ]
}

legacy_lock_present() {
    local path
    for path in \
        /data/local/tmp/zapret2-lifecycle.lock \
        /data/local/tmp/zapret2-lifecycle.lock.*; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            LEGACY_LOCK_PATH="$path"
            return 0
        fi
    done
    LEGACY_LOCK_PATH=""
    return 1
}

legacy_runtime_file_present() {
    local path
    for path in \
        "$LEGACY_PIDFILE" \
        "$LEGACY_OWNER_STATE" \
        "$LEGACY_CMDLINE_FILE" \
        "$LEGACY_STARTUP_LOG" \
        "$LEGACY_ERROR_LOG" \
        "$LEGACY_DEBUG_LOG" \
        "$LEGACY_LOGFILE" \
        "$LEGACY_LOGFILE_PREVIOUS"; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            return 0
        fi
    done
    return 1
}

read_legacy_owner_marker() {
    local key value version="" module="" nfqws=""
    local seen_version=0 seen_module=0 seen_nfqws=0
    [ -f "$LEGACY_OWNER_MARKER" ] && [ ! -L "$LEGACY_OWNER_MARKER" ] || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version)
                [ "$seen_version" -eq 0 ] || return 1
                version="$value"; seen_version=1
                ;;
            module_dir)
                [ "$seen_module" -eq 0 ] || return 1
                module="$value"; seen_module=1
                ;;
            nfqws)
                [ "$seen_nfqws" -eq 0 ] || return 1
                nfqws="$value"; seen_nfqws=1
                ;;
            *)
                return 1
                ;;
        esac
    done < "$LEGACY_OWNER_MARKER"
    [ "$version" = 1 ] &&
        [ "$module" = "$MODPATH" ] &&
        [ "$nfqws" = "$ZAPRET_DIR/nfqws2" ]
}

validate_legacy_cleanup_set() {
    local path
    if [ -e "$LEGACY_OWNER_MARKER" ] || [ -L "$LEGACY_OWNER_MARKER" ]; then
        if ! read_legacy_owner_marker; then
            LEGACY_AUDIT_ERROR="legacy ownership marker is malformed, unsafe, or belongs to another installation"
            return 1
        fi
        LEGACY_RUNTIME_OWNED=1
    elif legacy_runtime_file_present; then
        LEGACY_AUDIT_ERROR="legacy runtime files exist without a strong Zapret2 ownership marker"
        return 1
    else
        LEGACY_RUNTIME_OWNED=0
        return 0
    fi

    for path in \
        "$LEGACY_PIDFILE" \
        "$LEGACY_OWNER_STATE" \
        "$LEGACY_CMDLINE_FILE" \
        "$LEGACY_STARTUP_LOG" \
        "$LEGACY_ERROR_LOG" \
        "$LEGACY_DEBUG_LOG" \
        "$LEGACY_LOGFILE" \
        "$LEGACY_LOGFILE_PREVIOUS"; do
        if [ -e "$path" ] || [ -L "$path" ]; then
            if [ -L "$path" ] || [ ! -f "$path" ]; then
                LEGACY_AUDIT_ERROR="legacy runtime path is a symlink or special file: $path"
                return 1
            fi
        fi
    done
    return 0
}

firewall_is_clean() {
    local family_state
    if ! command -v iptables >/dev/null 2>&1 ||
       ! iptables -t mangle -S OUTPUT >/dev/null 2>&1; then
        AUDIT_ERROR="unable to verify IPv4 firewall state"
        return 1
    fi
    owned_family_present iptables; family_state=$?
    case "$family_state" in
        1) ;;
        0) AUDIT_ERROR="owned IPv4 firewall artifacts remain"; return 1 ;;
        *) AUDIT_ERROR="unable to query complete IPv4 firewall state"; return 1 ;;
    esac
    if command -v ip6tables >/dev/null 2>&1; then
        if ! ip6tables -t mangle -S OUTPUT >/dev/null 2>&1; then
            AUDIT_ERROR="unable to verify IPv6 firewall state"
            return 1
        fi
        owned_family_present ip6tables; family_state=$?
        case "$family_state" in
            1) ;;
            0) AUDIT_ERROR="owned IPv6 firewall artifacts remain"; return 1 ;;
            *) AUDIT_ERROR="unable to query complete IPv6 firewall state"; return 1 ;;
        esac
    fi
    return 0
}

process_is_stopped() {
    local candidate
    if [ -e "$OWNER_STATE" ] || [ -L "$OWNER_STATE" ]; then
        if [ -L "$OWNER_STATE" ] || [ ! -f "$OWNER_STATE" ]; then
            AUDIT_ERROR="ownership metadata is a symlink or special file"
            return 1
        fi
        if ! read_owner_state; then
            AUDIT_ERROR="ownership metadata is malformed or unsafe"
            return 1
        fi
        if kill -0 "$OWNER_STATE_PID" 2>/dev/null; then
            AUDIT_ERROR="owner-state PID $OWNER_STATE_PID is still live"
            return 1
        fi
    fi

    if [ -e "$PIDFILE" ] || [ -L "$PIDFILE" ]; then
        if [ -L "$PIDFILE" ] || [ ! -f "$PIDFILE" ]; then
            AUDIT_ERROR="PID metadata is a symlink or special file"
            return 1
        fi
        candidate="$(cat "$PIDFILE" 2>/dev/null)"
        if ! is_decimal "$candidate" || [ "$candidate" -le 0 ] 2>/dev/null; then
            AUDIT_ERROR="PID metadata is malformed"
            return 1
        fi
        if kill -0 "$candidate" 2>/dev/null; then
            if read_verified_pidfile; then
                AUDIT_ERROR="verified nfqws2 PID $VERIFIED_PID is still live"
            else
                AUDIT_ERROR="unverified live PID $candidate remains in process metadata"
            fi
            return 1
        fi
    fi

    # Catch an exact module-binary process even if all metadata was lost.
    scan_exact_owned_nfqws >/dev/null 2>&1 || {
        AUDIT_ERROR="unable to complete exact module-process scan"
        return 1
    }
    if [ -n "$OWNED_SCAN_PIDS" ]; then
        AUDIT_ERROR="module nfqws2 process(es) remain without usable metadata: $OWNED_SCAN_PIDS"
        return 1
    fi
    return 0
}

remove_state_regular_file() {
    local path="$1"
    [ -n "$path" ] || return 1
    case "$path" in
        "$STATE_DIR"/*) ;;
        *) return 1 ;;
    esac
    if [ -e "$path" ] || [ -L "$path" ]; then
        [ ! -L "$path" ] && [ -f "$path" ] || return 1
        rm -f "$path" 2>/dev/null || return 1
        [ ! -e "$path" ] && [ ! -L "$path" ] || return 1
    fi
    return 0
}

state_directory_has_preserved_children() {
    local entry restore_noglob=0
    case "$-" in *f*) restore_noglob=1; set +f ;; esac
    set -- "$STATE_DIR"/* "$STATE_DIR"/.[!.]* "$STATE_DIR"/..?*
    [ "$restore_noglob" = 1 ] && set -f
    for entry in "$@"; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        [ "$entry" = "$UNINSTALL_TOMBSTONE" ] && continue
        return 0
    done
    return 1
}

retire_authenticated_completed_rollback() {
    [ "$COMPLETED_ROLLBACK_PENDING" -eq 1 ] || return 0
    audit_recovery_artifacts uninstall || return 1
    [ "$RECOVERY_ARTIFACT_CLASS" = rollback-complete ] || return 1
    read_completed_rollback_meta || return 1
    [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] || return 1

    # The authenticated completion record is the commit marker and is removed
    # last. A retry can therefore finish retirement if backup removal succeeds
    # but a later durability/removal step fails.
    if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ] || [ -L "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
        state_file_is_secure "$FULL_ROLLBACK_HOSTS_BACKUP" || return 1
        remove_state_regular_file "$FULL_ROLLBACK_HOSTS_BACKUP" || return 1
        command -v sync >/dev/null 2>&1 && sync >/dev/null 2>&1 || return 1
    fi
    read_completed_rollback_meta || return 1
    [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] || return 1
    remove_state_regular_file "$FULL_ROLLBACK_META" || return 1
    command -v sync >/dev/null 2>&1 && sync >/dev/null 2>&1 || return 1
    if ! audit_recovery_artifacts uninstall || [ "$RECOVERY_ARTIFACT_CLASS" != clean ]; then
        return 1
    fi
    COMPLETED_ROLLBACK_PENDING=0
}

remove_legacy_regular_file() {
    local path="$1"
    case "$path" in
        "$LEGACY_PIDFILE"|"$LEGACY_OWNER_STATE"|"$LEGACY_CMDLINE_FILE"|\
        "$LEGACY_STARTUP_LOG"|"$LEGACY_ERROR_LOG"|"$LEGACY_DEBUG_LOG"|\
        "$LEGACY_LOGFILE"|"$LEGACY_LOGFILE_PREVIOUS"|"$LEGACY_OWNER_MARKER") ;;
        *) return 1 ;;
    esac
    if [ -e "$path" ] || [ -L "$path" ]; then
        [ ! -L "$path" ] && [ -f "$path" ] || return 1
        rm -f "$path" 2>/dev/null || return 1
        [ ! -e "$path" ] && [ ! -L "$path" ] || return 1
    fi
    return 0
}

unlock_after_signal() {
    trap - HUP INT TERM
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || true
        LOCK_ACQUIRED=0
    fi
    report_error "Zapret2 uninstall was interrupted; remaining state was preserved"
    exit 1
}

fail_while_locked() {
    local message="$1"
    local unlock_error=""
    trap - HUP INT TERM
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        if ! release_lifecycle_lock; then
            unlock_error="; lifecycle lock release could not be verified"
        fi
        LOCK_ACQUIRED=0
    fi
    report_error "$message$unlock_error"
    exit 1
}

publish_uninstall_tombstone() {
    local self_start token tmp
    self_start="$(proc_starttime "$$")" || return 1
    token="$(new_lifecycle_token)" || return 1
    is_safe_token "$token" || return 1

    if [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; then
        [ ! -L "$UNINSTALL_TOMBSTONE" ] && read_uninstall_tombstone || return 1
        uninstall_tombstone_owner_alive && return 1
    fi

    read_lock_owner && lock_owner_alive || return 1
    [ "$LOCK_FILE_PID" = "$$" ] && [ "$LOCK_FILE_START" = "$self_start" ] || return 1
    state_file_target_is_safe "$UNINSTALL_TOMBSTONE" || return 1
    tmp="$UNINSTALL_TOMBSTONE.tmp.$$.$token"
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    {
        printf 'version=%s\n' "$UNINSTALL_TOMBSTONE_VERSION"
        printf 'pid=%s\nstarttime=%s\ntoken=%s\n' "$$" "$self_start" "$token"
        printf 'module_dir=%s\n' "/data/adb/modules/zapret2"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$UNINSTALL_TOMBSTONE" 2>/dev/null || { rm -f "$tmp"; return 1; }
    state_file_is_secure "$UNINSTALL_TOMBSTONE" && read_uninstall_tombstone || return 1
    [ "$UNINSTALL_FILE_PID" = "$$" ] && [ "$UNINSTALL_FILE_START" = "$self_start" ] &&
        [ "$UNINSTALL_FILE_TOKEN" = "$token" ] &&
        [ "$UNINSTALL_FILE_MODULE" = "/data/adb/modules/zapret2" ] || return 1

    ZAPRET2_UNINSTALL_TOKEN="$token"
    ZAPRET2_UNINSTALL_OWNER_PID="$$"
    ZAPRET2_UNINSTALL_OWNER_START="$self_start"
    export ZAPRET2_UNINSTALL_TOKEN ZAPRET2_UNINSTALL_OWNER_PID ZAPRET2_UNINSTALL_OWNER_START
    uninstall_tombstone_allows_stop
}

magisk_removal_marker_is_exact() {
    local marker="$MODPATH/remove" size
    [ -f "$marker" ] && [ ! -L "$marker" ] && path_uid_is_root "$marker" &&
        path_nlink_is_one "$marker" || return 1
    size="$(wc -c < "$marker" 2>/dev/null)" || return 1
    [ "$size" = 0 ]
}

magisk_remove_all_owned_state() {
    local tool pending_nfqws="/data/adb/modules_update/zapret2/zapret2/nfqws2"
    [ -f "$PURGE_CONTRACT" ] && [ ! -L "$PURGE_CONTRACT" ] || {
        report_error "Magisk removal cleanup contract is unavailable"
        return 1
    }
    . "$PURGE_CONTRACT" || return 1

    # The root-owned empty Magisk remove marker is the durable global fence.
    # zapret-start and every mutation entry refuse work while it exists, so no
    # lifecycle tombstone is needed after the whole private state tree is gone.
    stop_all_exact_owned_nfqws_for_path "$NFQWS2" || {
        report_error "Unable to stop every exact live-module nfqws2 process"
        return 1
    }
    if [ -f "$pending_nfqws" ] && [ ! -L "$pending_nfqws" ]; then
        stop_all_exact_owned_nfqws_for_path "$pending_nfqws" || {
            report_error "Unable to stop every exact staged-module nfqws2 process"
            return 1
        }
    fi
    for tool in iptables ip6tables; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            [ "$tool" = ip6tables ] && continue
            report_error "Unable to access $tool during Magisk removal"
            return 1
        fi
        purge_zapret2_namespace "$tool" || {
            report_error "Unable to remove the strict Zapret2 namespace from $tool"
            return 1
        }
    done

    if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then
        state_dir_is_secure && state_dir_resolves_exactly || {
            report_error "Zapret2 state path is not the exact secure owned directory"
            return 1
        }
    fi
    z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_PENDING_DIR" || return 1
    z2_purge_remove_external_workspaces || return 1
    z2_purge_remove_legacy_files || return 1
    z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_STATE_DIR" || return 1
    sync >/dev/null 2>&1 || return 1
    [ ! -e "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || return 1
    report_notice "Magisk removal marker verified; all Zapret2 service, firewall, and private state was removed"
    return 0
}

if module_removal_pending; then
    if ! magisk_removal_marker_is_exact; then
        report_error "Magisk removal marker is unsafe; refusing uninstall"
        exit 1
    fi
    magisk_remove_all_owned_state
    exit $?
else
    report_warning "Magisk removal marker is absent (non-Magisk manager or direct invocation); relying on the persistent uninstall tombstone"
fi

if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then
    if ! state_dir_is_secure || ! state_dir_resolves_exactly; then
        report_error "Zapret2 state directory is unsafe or does not resolve to the exact owned path"
        exit 1
    fi
elif ! ensure_state_dir || ! state_dir_resolves_exactly; then
    report_error "Unable to create the secure state directory required for serialized uninstall"
    exit 1
fi

if ! audit_recovery_artifacts uninstall; then
    report_error "Recovery artifacts block uninstall before mutation: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
    exit 1
fi

if legacy_lock_present; then
    report_error "Legacy lifecycle lock is present and was preserved: $LEGACY_LOCK_PATH"
    exit 1
fi

LEGACY_AUDIT_ERROR=""
if ! validate_legacy_cleanup_set; then
    report_error "$LEGACY_AUDIT_ERROR; ambiguous legacy files were preserved"
    exit 1
fi

# Hold one lifecycle lock continuously across stop, final audit, and metadata
# cleanup.  zapret-stop.sh inherits the lock and therefore cannot race another
# start/stop caller; only this parent may release it.
if ! acquire_lifecycle_lock; then
    report_error "Zapret2 lifecycle is busy; state and locks were preserved"
    exit 1
fi
LOCK_ACQUIRED=1
trap unlock_after_signal HUP INT TERM

if ! audit_recovery_artifacts uninstall; then
    fail_while_locked "Recovery artifacts changed while uninstall was serializing: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
fi
case "$RECOVERY_ARTIFACT_CLASS" in
    clean) ;;
    rollback-complete)
        authenticate_completed_rollback_generation ||
            fail_while_locked "Completed rollback does not authenticate to this installed generation/archive"
        ;;
    *) fail_while_locked "Unexpected recovery-artifact class blocks uninstall: $RECOVERY_ARTIFACT_CLASS" ;;
esac

# Publish the persistent start gate while the uninstall parent still owns the
# lifecycle lock.  zapret-stop.sh receives the exact live owner credentials;
# every other start/stop/restart caller is refused.  The tombstone deliberately
# survives successful uninstall until a fully staged reinstall clears it.
if ! publish_uninstall_tombstone; then
    fail_while_locked "Unable to publish and verify the uninstall tombstone; state was preserved"
fi

attempt=1
while [ "$attempt" -le "$STOP_MAX_ATTEMPTS" ]; do
    if [ ! -f "$STOP_SCRIPT" ] || [ -L "$STOP_SCRIPT" ]; then
        STOP_STATUS=127
        report_warning "zapret-stop.sh is unavailable during uninstall"
        break
    fi

    /system/bin/sh "$STOP_SCRIPT"
    STOP_STATUS=$?
    [ "$STOP_STATUS" -eq 0 ] && break

    report_warning "zapret-stop.sh attempt $attempt/$STOP_MAX_ATTEMPTS failed during uninstall (exit $STOP_STATUS)"
    if [ "$attempt" -lt "$STOP_MAX_ATTEMPTS" ]; then
        sleep "$STOP_RETRY_DELAY_SECONDS"
    fi
    attempt=$((attempt + 1))
done

if [ "$STOP_STATUS" -ne 0 ]; then
    fail_while_locked "Zapret2 stop did not complete after bounded retries; process/firewall state was preserved"
fi

AUDIT_ERROR=""
if ! process_is_stopped || ! firewall_is_clean; then
    fail_while_locked "Zapret2 final shutdown verification failed: ${AUDIT_ERROR:-unknown state}; metadata was preserved"
fi

if ! audit_recovery_artifacts uninstall; then
    fail_while_locked "Recovery-artifact commit audit failed: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
fi
case "$RECOVERY_ARTIFACT_CLASS:$COMPLETED_ROLLBACK_PENDING" in
    clean:0) ;;
    rollback-complete:1)
        read_completed_rollback_meta && read_install_generation_meta &&
            [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
            [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] &&
            [ "$INSTALL_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
            [ "$INSTALL_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] ||
            fail_while_locked "Completed rollback identity changed before uninstall commit"
        ;;
    *) fail_while_locked "Recovery-artifact class changed before uninstall commit" ;;
esac
if ! retire_authenticated_completed_rollback; then
    fail_while_locked "Authenticated completed rollback generation/hosts backup could not be retired safely"
fi

# Remove only named regular metadata while the lifecycle is serialized.  The
# active lifecycle lock and any unknown child are deliberately not removed.
STATE_CLEANUP_ERROR=""
for state_file in \
    "$PIDFILE" \
    "$OWNER_STATE" \
    "$CMDLINE_FILE" \
    "$STARTUP_LOG" \
    "$ERROR_LOG" \
    "$DEBUG_LOG" \
    "$LOGFILE" \
    "$LOGFILE_PREVIOUS" \
    "$STATUS_SNAPSHOT" \
    "$IPTABLES_STATUS" \
    "$RUNTIME_OWNER_MARKER" \
    "$LEGACY_MIGRATION_MARKER" \
    "$PURGE_REQUEST"; do
    if ! remove_state_regular_file "$state_file"; then
        STATE_CLEANUP_ERROR="unsafe or unremovable state entry: $state_file"
        break
    fi
done

if [ -n "$STATE_CLEANUP_ERROR" ]; then
    fail_while_locked "$STATE_CLEANUP_ERROR; state directory was preserved"
fi

trap - HUP INT TERM
if ! release_lifecycle_lock; then
    LOCK_ACQUIRED=0
    report_error "Lifecycle lock release could not be verified; state directory was preserved"
    exit 1
fi
LOCK_ACQUIRED=0

# The state directory intentionally remains as a tiny root-only tombstone
# carrier.  Removing it after unlocking would reopen the final-start race.
if ! state_dir_is_secure || ! state_dir_resolves_exactly; then
    report_error "State directory changed during uninstall; it was preserved"
    exit 1
fi
for lock_artifact in "$STATE_DIR"/lifecycle.lock*; do
    if [ -e "$lock_artifact" ] || [ -L "$lock_artifact" ]; then
        report_error "Lifecycle lock artifact appeared during uninstall; the state directory was preserved"
        exit 1
    fi
done
if ! state_file_is_secure "$UNINSTALL_TOMBSTONE" || ! read_uninstall_tombstone ||
   [ "$UNINSTALL_FILE_PID" != "$$" ] ||
   [ "$UNINSTALL_FILE_TOKEN" != "$ZAPRET2_UNINSTALL_TOKEN" ] ||
   [ "$UNINSTALL_FILE_MODULE" != "/data/adb/modules/zapret2" ]; then
    report_error "Persistent uninstall tombstone changed after lifecycle unlock; manual repair is required"
    exit 1
fi

if legacy_lock_present; then
    report_error "Legacy lock appeared during uninstall and was preserved: $LEGACY_LOCK_PATH"
    exit 1
fi

if [ "$LEGACY_RUNTIME_OWNED" -eq 1 ]; then
    # Revalidate the marker and every target immediately before touching the
    # public legacy parent.  The exact ownership marker is removed last.
    LEGACY_AUDIT_ERROR=""
    if ! validate_legacy_cleanup_set; then
        report_error "$LEGACY_AUDIT_ERROR; legacy state was preserved"
        exit 1
    fi
    for legacy_file in \
        "$LEGACY_PIDFILE" \
        "$LEGACY_OWNER_STATE" \
        "$LEGACY_CMDLINE_FILE" \
        "$LEGACY_STARTUP_LOG" \
        "$LEGACY_ERROR_LOG" \
        "$LEGACY_DEBUG_LOG" \
        "$LEGACY_LOGFILE" \
        "$LEGACY_LOGFILE_PREVIOUS"; do
        if ! remove_legacy_regular_file "$legacy_file"; then
            report_error "Verified legacy runtime file could not be removed safely: $legacy_file"
            exit 1
        fi
    done
    if ! read_legacy_owner_marker || ! remove_legacy_regular_file "$LEGACY_OWNER_MARKER"; then
        report_error "Verified legacy ownership marker changed or could not be removed safely"
        exit 1
    fi
    if legacy_runtime_file_present || [ -e "$LEGACY_OWNER_MARKER" ] || [ -L "$LEGACY_OWNER_MARKER" ]; then
        report_error "Legacy runtime cleanup could not be verified"
        exit 1
    fi
fi

# Unknown root-state children are deliberately never deleted.  Their presence
# means the owned service/firewall cleanup completed only partially from the
# installer's perspective, so keep the tombstone gate and return a non-success
# result instead of claiming the whole state directory is clean.
if state_directory_has_preserved_children; then
    report_error "Zapret2 service/firewall cleanup completed, but unknown state entries were preserved; uninstall cleanup is partial"
    exit 1
fi

echo "Zapret2 stopped, verified clean, and uninstalled"
exit 0
