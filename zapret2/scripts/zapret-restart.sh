#!/system/bin/sh
##########################################################################################
# Zapret2 Quick Restart Script
# Fast restart without delays - kills process, rebuilds options, restarts
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

# 1. Quick kill nfqws2
if [ -f "$PIDFILE" ]; then
    kill -9 $(cat "$PIDFILE") 2>/dev/null
    rm -f "$PIDFILE"
fi
pkill -9 -f nfqws2 2>/dev/null

# 2. Remove iptables (quick, no logging)
iptables -t mangle -D OUTPUT -p tcp -m multiport --dports $PORTS_TCP -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null
iptables -t mangle -D OUTPUT -p udp -m multiport --dports $PORTS_UDP -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null
iptables -t mangle -D INPUT -p tcp -m multiport --sports $PORTS_TCP -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null
iptables -t mangle -D INPUT -p udp -m multiport --sports $PORTS_UDP -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

# 3. Start fresh (calls full start script for options building)
exec "$SCRIPT_DIR/zapret-start.sh"
