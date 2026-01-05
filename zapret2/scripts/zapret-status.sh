#!/system/bin/sh
##########################################################################################
# Zapret2 Status Script
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

echo "=========================================="
echo " Zapret2 Status"
echo "=========================================="
echo ""

# Check if running
if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE")
    if [ -d "/proc/$PID" ]; then
        echo "Status: RUNNING"
        echo "PID: $PID"

        # Get memory usage
        if [ -f "/proc/$PID/status" ]; then
            MEM=$(grep VmRSS /proc/$PID/status | awk '{print $2 " " $3}')
            echo "Memory: $MEM"
        fi

        # Get uptime
        if [ -f "/proc/$PID/stat" ]; then
            START_TIME=$(cat /proc/$PID/stat | cut -d' ' -f22)
            UPTIME_SEC=$(($(cut -d. -f1 /proc/uptime) - START_TIME / $(getconf CLK_TCK)))
            if [ $UPTIME_SEC -ge 0 ]; then
                HOURS=$((UPTIME_SEC / 3600))
                MINS=$(((UPTIME_SEC % 3600) / 60))
                SECS=$((UPTIME_SEC % 60))
                echo "Uptime: ${HOURS}h ${MINS}m ${SECS}s"
            fi
        fi
    else
        echo "Status: STOPPED (stale PID file)"
        rm -f "$PIDFILE"
    fi
else
    # Check for orphan processes
    PIDS=$(pgrep -f nfqws2)
    if [ -n "$PIDS" ]; then
        echo "Status: RUNNING (orphan)"
        echo "PIDs: $PIDS"
    else
        echo "Status: STOPPED"
    fi
fi

echo ""

# Check iptables rules
echo "iptables mangle OUTPUT rules:"
iptables -t mangle -L OUTPUT -n --line-numbers 2>/dev/null | grep -E "NFQUEUE|dpt:" | head -5
if [ $? -ne 0 ]; then
    echo "  (none or iptables error)"
fi

echo ""

echo "iptables mangle INPUT rules:"
iptables -t mangle -L INPUT -n --line-numbers 2>/dev/null | grep -E "NFQUEUE|spt:" | head -5
if [ $? -ne 0 ]; then
    echo "  (none or iptables error)"
fi

echo ""

# Check NFQUEUE
echo "NFQUEUE status:"
if [ -f /proc/net/netfilter/nf_queue ]; then
    cat /proc/net/netfilter/nf_queue 2>/dev/null | head -5
else
    echo "  NFQUEUE not available in kernel"
fi

echo ""

# Last log entries
echo "Last log entries:"
if [ -f "$LOGFILE" ]; then
    tail -10 "$LOGFILE"
else
    echo "  No log file found"
fi

echo ""
echo "=========================================="

# Exit with appropriate code for programmatic use
# 0 = running, 1 = stopped
if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$PID" ] && [ -d "/proc/$PID" ]; then
        exit 0
    fi
fi

# Check for orphan process
if pgrep -f nfqws2 > /dev/null 2>&1; then
    exit 0
fi

exit 1
