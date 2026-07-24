#!/system/bin/sh
# Module package generations are activated only by the root manager at boot.
##########################################################################################
# Zapret2 root module - Service Script (runs at boot)
##########################################################################################

MODDIR="${0%/*}"
ZAPRET_DIR="$MODDIR/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
START_SCRIPT="$SCRIPT_DIR/zapret-start.sh"
LOG_READY=0
MODULE_DISABLED=0

# This is the root-manager boot entry point. The lifecycle lock in zapret-start.sh
# serializes this invocation with other lifecycle callers.

log() {
    if [ "$LOG_READY" = "1" ]; then
        append_lifecycle_log "$(date '+%Y-%m-%d %H:%M:%S') [SERVICE] $1" || LOG_READY=0
    fi
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

# Root-manager disable markers are authoritative at boot. A disabled module
# still retires authenticated previous-boot runtime metadata when such state is
# already present; with no state at all it remains a mutation-free no-op.
# Unsafe marker types fail closed before state creation or lifecycle mutation.
DISABLE_MARKER="$MODDIR/disable"
if [ -e "$DISABLE_MARKER" ] || [ -L "$DISABLE_MARKER" ]; then
    if [ -f "$DISABLE_MARKER" ] && [ ! -L "$DISABLE_MARKER" ]; then
        MODULE_DISABLED=1
        if [ ! -e "${STATE_DIR:-/data/adb/zapret2-state}" ] &&
           [ ! -L "${STATE_DIR:-/data/adb/zapret2-state}" ]; then
            log "Module disable marker is present; no runtime state requires boot recovery"
            exit 0
        fi
    else
        /system/bin/log -p e -t "Zapret2" "Unsafe module disable marker; boot startup was refused" 2>/dev/null
        exit 1
    fi
fi

if [ ! -f "$COMMON_SCRIPT" ] || [ -L "$COMMON_SCRIPT" ] ||
   [ ! -f "$START_SCRIPT" ] || [ -L "$START_SCRIPT" ]; then
    /system/bin/log -p e -t "Zapret2" "Secure lifecycle helpers are unavailable; boot startup was refused" 2>/dev/null
    exit 1
fi

. "$COMMON_SCRIPT"

# The boot service owns only the dedicated root state directory.  Refuse an
# unsafe existing path instead of repairing it implicitly.  Create it only for
# an enabled module; disabled/no-state boot was handled above as a clean no-op.
if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then
    state_dir_is_secure || {
        /system/bin/log -p e -t "Zapret2" "Secure state directory is unavailable; boot startup was refused" 2>/dev/null
        exit 1
    }
elif [ "$MODULE_DISABLED" = 1 ]; then
    log "Module disable marker is present; boot startup skipped"
    exit 0
elif ! ensure_state_dir; then
    /system/bin/log -p e -t "Zapret2" "Secure state directory is unavailable; boot startup was refused" 2>/dev/null
    exit 1
fi

# Wait for boot to complete
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done

# This audit is intentionally before both the disable/autostart gates.  It can
# only retire proven cross-boot metadata and cannot launch nfqws2 or add rules.
if ! command -v recover_boot_stale_runtime_state >/dev/null 2>&1 ||
   ! recover_boot_stale_runtime_state; then
    log "ERROR: Previous-boot runtime recovery failed: ${BOOT_RECOVERY_DIAGNOSTIC:-unsafe recovery state}"
    exit 1
fi

if [ "$MODULE_DISABLED" = 1 ]; then
    log "Module disable marker is present; previous-boot recovery completed and startup was skipped"
    exit 0
fi

if ! prepare_lifecycle_log; then
    LOG_READY=0
    /system/bin/log -p w -t "Zapret2" "Lifecycle file logging is unavailable; continuing in logcat only" 2>/dev/null
fi

if [ "$BOOT_INCOMPATIBLE_STATE_RETIRED" = 1 ]; then
    log "Incompatible boot-local state was discarded"
fi
log "=== Zapret2 service starting ==="

log "Boot completed, waiting for network..."

# Wait until network appears, up to 15 seconds
waited=0
while [ "$waited" -lt 15 ]; do
    if command -v ip >/dev/null 2>&1; then
        if [ -n "$(ip route show default 2>/dev/null)" ]; then
            log "Network is ready after ${waited}s"
            break
        fi
    elif [ -n "$(getprop net.dns1)" ]; then
        log "DNS property detected after ${waited}s"
        break
    fi

    sleep 1
    waited=$((waited + 1))
done

if [ "$waited" -ge 15 ]; then
    log "Network wait timeout reached, continuing startup"
fi

# Check if autostart is enabled.  This preflight is read-only; any migration is
# performed by zapret-start.sh only after update/lifecycle serialization.
load_effective_core_config_readonly
CONFIG_RC=$?

if [ "$CONFIG_RC" -ne 0 ]; then
    log "runtime.ini requires serialized repair: ${RUNTIME_CONFIG_ERROR:-unknown error}"
    REPAIR_OUTPUT="$(sh "$START_SCRIPT" --repair-runtime-only 2>&1)"
    REPAIR_RC=$?
    if [ "$REPAIR_RC" -ne 0 ]; then
        log "ERROR: Serialized runtime.ini repair failed (exit $REPAIR_RC): $REPAIR_OUTPUT"
        log "=== Zapret2 service script failed ==="
        exit 1
    fi
    log "$REPAIR_OUTPUT"
    if ! load_effective_core_config_readonly; then
        log "ERROR: Repaired runtime.ini still failed strict read-only validation"
        log "=== Zapret2 service script failed ==="
        exit 1
    fi
fi

case "$RUNTIME_CONFIG_STATUS" in
    loaded|regenerated)
        log "$(runtime_config_status_message)"
        ;;
    unavailable)
        log "$(runtime_config_status_message)"
        ;;
esac

log "$(core_config_source_message)"
log "Category state source: $CATEGORIES_FILE"

if [ "$AUTOSTART" = "1" ]; then
    log "Autostart enabled, launching zapret2..."
    # Package updates are activated by the root manager only at boot.
    # zapret-start.sh gates runtime state, module removal, and uninstall tombstones.
    /system/bin/sh "$START_SCRIPT"
    START_RC=$?
    if [ "$START_RC" -eq 0 ]; then
        log "Autostart command completed successfully (exit $START_RC)"
    else
        log "ERROR: Autostart command failed (exit $START_RC)"
    fi
else
    START_RC=0
    log "Autostart disabled in effective core config ($CORE_CONFIG_SOURCE)"
fi

if [ "$START_RC" -eq 0 ]; then
    log "=== Zapret2 service script finished successfully (exit $START_RC) ==="
else
    log "=== Zapret2 service script finished with errors (exit $START_RC) ==="
fi
exit "$START_RC"
