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

# 2. Remove iptables rules (resilient to stale config and duplicate rules)
remove_nfqueue_rules_by_qnum >/dev/null 2>&1

# 3. Start fresh (calls full start script for options building)
FAST_RESTART=1 exec "$SCRIPT_DIR/zapret-start.sh"
