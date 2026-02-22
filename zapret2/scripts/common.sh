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
PERM_STAMP_FILE="/data/local/tmp/zapret2-perms.stamp"

# User overrides (persistent across module updates)
USER_CONFIG="/data/local/tmp/zapret2-user.conf"

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
PRESETS_DIR="$ZAPRET_DIR/presets"
CUSTOM_CMDLINE_FILE="${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}"

# Strategy INI files
TCP_STRATEGIES_INI="$ZAPRET_DIR/strategies-tcp.ini"
UDP_STRATEGIES_INI="$ZAPRET_DIR/strategies-udp.ini"
STUN_STRATEGIES_INI="$ZAPRET_DIR/strategies-stun.ini"
CATEGORIES_FILE="$ZAPRET_DIR/categories.ini"

# Load user config (overrides defaults above)
[ -f "$CONFIG" ] && . "$CONFIG"
[ -f "$USER_CONFIG" ] && . "$USER_CONFIG"

# Remove all NFQUEUE rules for the configured queue from mangle OUTPUT/INPUT.
# This is resilient to config changes and cleans up stale duplicate rules.
remove_nfqueue_rules_by_qnum() {
    local chain line rule
    local removed_total=0
    local rules
    local queue_num="${1:-$QNUM}"

    [ -n "$queue_num" ] || return 0

    for chain in OUTPUT INPUT; do
        rules="$(iptables -t mangle -S "$chain" 2>/dev/null)"
        [ -z "$rules" ] && continue

        while IFS= read -r line; do
            case "$line" in
                "-A $chain "*)
                    case "$line" in
                        *"-j NFQUEUE"*"--queue-num $queue_num"*)
                            rule="${line#-A $chain }"
                            if [ -n "$rule" ]; then
                                # shellcheck disable=SC2086
                                if iptables -t mangle -D "$chain" $rule 2>/dev/null; then
                                    removed_total=$((removed_total + 1))
                                fi
                            fi
                            ;;
                    esac
                    ;;
            esac
        done <<EOF
$rules
EOF
    done

    echo "$removed_total"
}

# Remove ALL NFQUEUE rules from mangle OUTPUT/INPUT regardless of queue number.
# Used during startup to ensure no stale rules remain from previous mode/qnum.
remove_all_nfqueue_rules() {
    local chain line rule
    local removed_total=0
    local rules

    for chain in OUTPUT INPUT; do
        rules="$(iptables -t mangle -S "$chain" 2>/dev/null)"
        [ -z "$rules" ] && continue

        while IFS= read -r line; do
            case "$line" in
                "-A $chain "*)
                    case "$line" in
                        *"-j NFQUEUE"*"--queue-bypass"*)
                            rule="${line#-A $chain }"
                            if [ -n "$rule" ]; then
                                # shellcheck disable=SC2086
                                if iptables -t mangle -D "$chain" $rule 2>/dev/null; then
                                    removed_total=$((removed_total + 1))
                                fi
                            fi
                            ;;
                    esac
                    ;;
            esac
        done <<EOF
$rules
EOF
    done

    echo "$removed_total"
}
