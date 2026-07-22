#!/system/bin/sh
# Serialize Android hot-update ownership with install/uninstall/start/stop.

umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" 2>/dev/null && pwd -P)" || exit 1
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"

[ "$MODDIR" = "/data/adb/modules/zapret2" ] || {
    echo "ERROR: update guard must run from the active Zapret2 module" >&2
    exit 1
}
[ -f "$COMMON_SCRIPT" ] && [ ! -L "$COMMON_SCRIPT" ] || {
    echo "ERROR: secure lifecycle helpers are unavailable" >&2
    exit 1
}
. "$COMMON_SCRIPT" || exit 1

GUARD_LIFECYCLE_HELD=0
GUARD_COMMITTED=0
GUARD_PID=""
GUARD_START=""
GUARD_CREATED=""
GUARD_BOOT=""
GUARD_TOKEN=""
GUARD_MODULE=""

guard_cleanup() {
    local rc=$? tmp
    trap - EXIT HUP INT TERM
    if [ "$rc" -ne 0 ] && [ "$GUARD_COMMITTED" -ne 1 ]; then
        if [ "$UPDATE_LOCK_PUBLICATION_ACTIVE" = 1 ]; then
            if lifecycle_lock_owned_by_current_process; then
                remove_exact_update_lock_while_locked \
                    "$GUARD_PID" "$GUARD_START" "$GUARD_CREATED" "$GUARD_BOOT" "$GUARD_TOKEN" "$GUARD_MODULE" || {
                    echo "ERROR: interrupted acquisition lock was preserved because exact rollback failed" >&2
                    rc=1
                }
            else
                echo "ERROR: published update lock was preserved because lifecycle ownership was lost" >&2
                rc=1
            fi
        fi
        tmp="$UPDATE_LOCK_PUBLICATION_TEMP"
        if [ -n "$tmp" ] && state_path_is_managed_file "$tmp" &&
           [ -f "$tmp" ] && [ ! -L "$tmp" ]; then
            rm -f "$tmp" 2>/dev/null || rc=1
        fi
    fi
    if [ "$GUARD_LIFECYCLE_HELD" = 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || rc=1
        GUARD_LIFECYCLE_HELD=0
    fi
    exit "$rc"
}

trap guard_cleanup EXIT
trap 'echo "ERROR: update acquisition interrupted by HUP" >&2; exit 1' HUP
trap 'echo "ERROR: update acquisition interrupted by INT" >&2; exit 1' INT
trap 'echo "ERROR: update acquisition interrupted by TERM" >&2; exit 1' TERM

usage() {
    echo "usage: $0 acquire --pid PID --token TOKEN --module-dir /data/adb/modules/zapret2" >&2
    exit 2
}

[ "${1:-}" = acquire ] || usage
shift
pid=""; token=""; module=""
seen_pid=0; seen_token=0; seen_module=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --pid)
            [ "$seen_pid" = 0 ] && [ "$#" -ge 2 ] || usage
            pid="$2"; seen_pid=1; shift 2
            ;;
        --token)
            [ "$seen_token" = 0 ] && [ "$#" -ge 2 ] || usage
            token="$2"; seen_token=1; shift 2
            ;;
        --module-dir)
            [ "$seen_module" = 0 ] && [ "$#" -ge 2 ] || usage
            module="$2"; seen_module=1; shift 2
            ;;
        *) usage ;;
    esac
done
[ "$seen_pid:$seen_token:$seen_module" = 1:1:1 ] || usage
is_decimal "$pid" && [ "$pid" -gt 0 ] 2>/dev/null || usage
is_safe_token "$token" && [ "${#token}" -le 128 ] 2>/dev/null || usage
[ "$module" = "$MODDIR" ] || usage

if ! acquire_lifecycle_lock; then
    echo "ERROR: Zapret2 lifecycle is busy; update lock was not created" >&2
    exit 1
fi
GUARD_LIFECYCLE_HELD=1

if ! audit_recovery_artifacts update; then
    echo "ERROR: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-recovery artifacts block update acquisition}" >&2
    exit 1
fi

if ! update_acquire_preflight_clear; then
    echo "ERROR: ${UPDATE_ACQUIRE_ERROR:-update acquisition preflight failed}" >&2
    exit 1
fi

start="$(proc_starttime "$pid")" || {
    echo "ERROR: update owner PID is not alive" >&2
    exit 1
}
update_owner_identity_is_live "$pid" "$start" "$token" "$module" || {
    echo "ERROR: update owner identity is not stable" >&2
    exit 1
}
created="$(date +%s 2>/dev/null)" || exit 1
is_decimal "$created" || exit 1
read_current_boot_id || {
    echo "ERROR: current boot identity is unavailable" >&2
    exit 1
}
boot="$CURRENT_BOOT_ID"

GUARD_PID="$pid"
GUARD_START="$start"
GUARD_CREATED="$created"
GUARD_BOOT="$boot"
GUARD_TOKEN="$token"
GUARD_MODULE="$module"

if ! publish_update_lock_exact "$pid" "$start" "$created" "$boot" "$token" "$module"; then
    echo "ERROR: ${UPDATE_ACQUIRE_ERROR:-atomic update-lock publication failed}" >&2
    exit 1
fi
sync >/dev/null 2>&1 || {
    echo "ERROR: update-lock durability sync failed" >&2
    exit 1
}

# Magisk's remove marker is not lifecycle-lock aware, so repeat every external
# gate and owner proof immediately before the signal-safe handoff.
if module_removal_pending ||
   [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ] ||
   [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ] ||
   [ -e "$UPDATE_CLEANUP" ] || [ -L "$UPDATE_CLEANUP" ] ||
   ! update_lock_file_matches_exact "$UPDATE_LOCK" "$pid" "$start" "$created" "$boot" "$token" "$module" ||
   ! update_owner_identity_is_live "$pid" "$start" "$token" "$module"; then
    echo "ERROR: update acquisition changed before commit" >&2
    exit 1
fi

# No signal handler may roll MODDIR/update ownership backward after unlock.
trap '' HUP INT TERM
if ! release_lifecycle_lock; then
    echo "ERROR: lifecycle unlock failed after update-lock publication" >&2
    exit 1
fi
GUARD_LIFECYCLE_HELD=0 GUARD_COMMITTED=1

printf 'Z2_LOCK_START=%s\n' "$start"
printf 'Z2_LOCK_CREATED=%s\n' "$created"
printf 'Z2_LOCK_BOOT=%s\n' "$boot"
printf 'Z2_LOCK_COMPLETE=1\n'
trap - EXIT
exit 0
