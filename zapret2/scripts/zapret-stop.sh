#!/system/bin/sh
##########################################################################################
# Zapret2 Stop Script
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

##########################################################################################
# Logging
##########################################################################################

log_msg() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1" >> "$LOGFILE"
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

##########################################################################################
# Stop nfqws2 daemon
##########################################################################################

stop_daemon() {
    local DAEMON_STOPPED=0

    if [ -f "$PIDFILE" ]; then
        PID=$(cat "$PIDFILE")
        if [ -d "/proc/$PID" ]; then
            log_msg "Stopping nfqws2 (PID: $PID)..."
            kill $PID 2>/dev/null

            # Force kill if still running
            if [ -d "/proc/$PID" ]; then
                log_msg "Process still running, sending SIGKILL..."
                kill -9 $PID 2>/dev/null
            fi

            # Verify process stopped
            if [ -d "/proc/$PID" ]; then
                log_msg "ERROR: Failed to stop nfqws2 (PID: $PID)"
                echo "ERROR: Failed to stop nfqws2 (PID: $PID)"
                return 1
            else
                log_msg "nfqws2 stopped successfully"
                echo "Stopped nfqws2 (PID: $PID)"
                DAEMON_STOPPED=1
            fi
        else
            log_msg "PID file exists but process not running"
        fi
        rm -f "$PIDFILE"
    fi

    # Always try to find and kill any orphan nfqws2 processes
    PIDS=$(pgrep -f nfqws2 2>/dev/null)
    if [ -n "$PIDS" ]; then
        kill $PIDS 2>/dev/null
        for PID in $PIDS; do
            if [ -d "/proc/$PID" ]; then
                kill -9 $PID 2>/dev/null
            fi
            log_msg "Killed orphan nfqws2 process: $PID"
        done
        DAEMON_STOPPED=1
    fi

    if [ "$DAEMON_STOPPED" -eq 0 ] && [ ! -f "$PIDFILE" ]; then
        echo "Zapret2 is not running"
    fi

    # Clean up any stale PID file
    rm -f "$PIDFILE" 2>/dev/null

    return 0
}

##########################################################################################
# Remove iptables rules
##########################################################################################

remove_iptables() {
    log_msg "Removing iptables rules..."

    local removed
    removed=$(remove_nfqueue_rules_by_qnum)
    if [ -n "$removed" ] && [ "$removed" -gt 0 ] 2>/dev/null; then
        log_msg "Removed NFQUEUE rules: $removed"
    else
        log_msg "No NFQUEUE rules found for queue $QNUM"
    fi

    log_msg "iptables rules removed"
}

##########################################################################################
# Main
##########################################################################################

log_msg "=========================================="
log_msg "Stopping Zapret2 DPI bypass"
log_msg "=========================================="

EXIT_CODE=0

if ! stop_daemon; then
    log_msg "WARNING: Daemon stop encountered issues"
    EXIT_CODE=1
fi

remove_iptables

# Final verification
REMAINING=$(pgrep -f nfqws2 2>/dev/null)
if [ -n "$REMAINING" ]; then
    log_msg "WARNING: Some nfqws2 processes still running: $REMAINING"
    EXIT_CODE=1
fi

if [ "$EXIT_CODE" -eq 0 ]; then
    log_msg "Zapret2 stopped successfully"
    echo "Zapret2 stopped"
else
    log_msg "Zapret2 stopped with warnings"
    echo "Zapret2 stopped with warnings (check logs)"
fi

exit $EXIT_CODE
