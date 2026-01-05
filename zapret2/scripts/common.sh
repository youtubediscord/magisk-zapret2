#!/system/bin/sh
# Common constants for zapret2 scripts
# Source this file: . "$SCRIPT_DIR/common.sh"

# Paths (derived from SCRIPT_DIR)
ZAPRET_DIR="${ZAPRET_DIR:-$(dirname "$SCRIPT_DIR")}"
MODDIR="${MODDIR:-$(dirname "$ZAPRET_DIR")}"

# Runtime files
PIDFILE="/data/local/tmp/nfqws2.pid"
LOGFILE="/data/local/tmp/zapret2.log"
CMDLINE_FILE="/data/local/tmp/nfqws2-cmdline.txt"
STARTUP_LOG="/data/local/tmp/nfqws2-startup.log"
ERROR_LOG="/data/local/tmp/nfqws2-error.log"
DEBUG_LOG="/data/local/tmp/nfqws2-debug.log"

# NFQUEUE settings (defaults, can be overridden by config.sh)
QNUM="${QNUM:-200}"
DESYNC_MARK="${DESYNC_MARK:-0x40000000}"

# Port configuration
PORTS_TCP="${PORTS_TCP:-80,443}"
PORTS_UDP="${PORTS_UDP:-443}"

# Packet limits
PKT_OUT="${PKT_OUT:-20}"
PKT_IN="${PKT_IN:-10}"

# Core paths
NFQWS2="$ZAPRET_DIR/nfqws2"
CONFIG="$ZAPRET_DIR/config.sh"
LISTS_DIR="$ZAPRET_DIR/lists"
BLOBS_FILE="$ZAPRET_DIR/blobs.txt"

# Strategy INI files
TCP_STRATEGIES_INI="$ZAPRET_DIR/strategies-tcp.ini"
UDP_STRATEGIES_INI="$ZAPRET_DIR/strategies-udp.ini"
STUN_STRATEGIES_INI="$ZAPRET_DIR/strategies-stun.ini"
CATEGORIES_FILE="$ZAPRET_DIR/categories.ini"

# Load user config (overrides defaults above)
[ -f "$CONFIG" ] && . "$CONFIG"
