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

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1" >> "$LOGFILE"
    log -t "Zapret2" "$1" 2>/dev/null
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
    if [ -f "$PIDFILE" ]; then
        PID=$(cat "$PIDFILE")
        if [ -d "/proc/$PID" ]; then
            log "Stopping nfqws2 (PID: $PID)..."
            kill $PID 2>/dev/null
            sleep 1

            # Force kill if still running
            if [ -d "/proc/$PID" ]; then
                kill -9 $PID 2>/dev/null
            fi

            log "nfqws2 stopped"
            echo "Stopped nfqws2 (PID: $PID)"
        else
            log "PID file exists but process not running"
        fi
        rm -f "$PIDFILE"
    else
        # Try to find and kill any running nfqws2
        PIDS=$(pgrep -f nfqws2)
        if [ -n "$PIDS" ]; then
            for PID in $PIDS; do
                kill $PID 2>/dev/null
                log "Killed orphan nfqws2 process: $PID"
            done
        else
            echo "Zapret2 is not running"
        fi
    fi
}

##########################################################################################
# Remove iptables rules
##########################################################################################

remove_iptables() {
    log "Removing iptables rules..."

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

    log "iptables rules removed"
}

##########################################################################################
# Main
##########################################################################################

log "=========================================="
log "Stopping Zapret2 DPI bypass"
log "=========================================="

stop_daemon
remove_iptables

log "Zapret2 stopped successfully"
echo "Zapret2 stopped"
