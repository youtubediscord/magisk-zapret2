#!/system/bin/sh
##########################################################################################
# Zapret2 Stop Script
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
CONFIG="$ZAPRET_DIR/config.sh"

PIDFILE="/data/local/tmp/nfqws2.pid"
LOGFILE="/data/local/tmp/zapret2.log"

##########################################################################################
# Logging
##########################################################################################

log_msg() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1" >> "$LOGFILE"
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

##########################################################################################
# Load configuration
##########################################################################################

QNUM=200
DESYNC_MARK=0x40000000
PORTS_TCP="80,443"
PORTS_UDP="443"
PKT_OUT=20
PKT_IN=10

if [ -f "$CONFIG" ]; then
    . "$CONFIG"
fi

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
            sleep 1

            # Force kill if still running
            if [ -d "/proc/$PID" ]; then
                log_msg "Process still running, sending SIGKILL..."
                kill -9 $PID 2>/dev/null
                sleep 0.5
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
        for PID in $PIDS; do
            kill $PID 2>/dev/null
            sleep 0.5
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

    # Remove OUTPUT TCP rule
    iptables -t mangle -D OUTPUT \
        -p tcp -m multiport --dports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    # Remove OUTPUT UDP rule
    iptables -t mangle -D OUTPUT \
        -p udp -m multiport --dports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    # Remove INPUT TCP rule
    iptables -t mangle -D INPUT \
        -p tcp -m multiport --sports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    # Remove INPUT UDP rule
    iptables -t mangle -D INPUT \
        -p udp -m multiport --sports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

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
