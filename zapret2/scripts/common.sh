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
RUNTIME_CONFIG="$ZAPRET_DIR/runtime.ini"
RUNTIME_MIGRATE_SCRIPT="$SCRIPT_DIR/runtime-migrate.sh"

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
CORE_CONFIG_SOURCE="defaults"

runtime_config_exists() {
    [ -f "$RUNTIME_CONFIG" ] && [ -r "$RUNTIME_CONFIG" ]
}

trim_config_value() {
    printf '%s' "$1" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

set_core_config_defaults() {
    AUTOSTART=1
    WIFI_ONLY=0
    DEBUG=0
    QNUM=200
    DESYNC_MARK=0x40000000
    PORTS_TCP="80,443"
    PORTS_UDP="443"
    PKT_OUT=20
    PKT_IN=10
    STRATEGY_PRESET="youtube"
    PRESET_MODE="categories"
    PRESET_FILE="Default.txt"
    CUSTOM_CMDLINE_FILE="$ZAPRET_DIR/cmdline.txt"
    NFQWS_UID="0:0"
    LOG_MODE="none"
}

load_legacy_core_config_overrides() {
    [ -f "$CONFIG" ] && . "$CONFIG"
    [ -f "$USER_CONFIG" ] && . "$USER_CONFIG"
}

apply_runtime_core_overrides() {
    runtime_config_exists || return 1

    local current_section=""
    local line=""
    local cr
    local key
    local value

    cr=$(printf '\r')

    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"

        case "$line" in
            ""|"#"*|";"*)
                continue
                ;;
            "["*"]")
                current_section="${line#[}"
                current_section="${current_section%]}"
                current_section="$(trim_config_value "$current_section")"
                continue
                ;;
        esac

        [ "$current_section" = "core" ] || continue

        case "$line" in
            *=*)
                key="${line%%=*}"
                value="${line#*=}"
                key="$(trim_config_value "$key")"
                value="$(trim_config_value "$value")"
                ;;
            *)
                continue
                ;;
        esac

        case "$key" in
            autostart)
                AUTOSTART="$value"
                ;;
            wifi_only)
                WIFI_ONLY="$value"
                ;;
            debug)
                DEBUG="$value"
                ;;
            qnum)
                QNUM="$value"
                ;;
            desync_mark)
                DESYNC_MARK="$value"
                ;;
            ports_tcp)
                PORTS_TCP="$value"
                ;;
            ports_udp)
                PORTS_UDP="$value"
                ;;
            pkt_out)
                PKT_OUT="$value"
                ;;
            pkt_in)
                PKT_IN="$value"
                ;;
            strategy_preset)
                STRATEGY_PRESET="$value"
                ;;
            preset_mode)
                PRESET_MODE="$value"
                ;;
            preset_file)
                PRESET_FILE="$value"
                ;;
            custom_cmdline_file)
                CUSTOM_CMDLINE_FILE="$value"
                ;;
            nfqws_uid)
                NFQWS_UID="$value"
                ;;
            log_mode)
                LOG_MODE="$value"
                ;;
        esac
    done < "$RUNTIME_CONFIG"

    return 0
}

load_effective_core_config() {
    set_core_config_defaults

    if runtime_config_exists; then
        apply_runtime_core_overrides >/dev/null 2>&1 || true
        CORE_CONFIG_SOURCE="runtime.ini"
        return 0
    fi

    load_legacy_core_config_overrides

    if [ -f "$USER_CONFIG" ]; then
        CORE_CONFIG_SOURCE="legacy-user"
    elif [ -f "$CONFIG" ]; then
        CORE_CONFIG_SOURCE="legacy-config"
    else
        CORE_CONFIG_SOURCE="defaults"
    fi
}

shell_config_sets_key() {
    local file_path="$1"
    local key="$2"

    [ -f "$file_path" ] || return 1
    grep -Eq "^[[:space:]]*${key}=" "$file_path" 2>/dev/null
}

# Remove all NFQUEUE rules for the configured queue from mangle OUTPUT/INPUT.
# Handles both IPv4 (iptables) and IPv6 (ip6tables).
remove_nfqueue_rules_by_qnum() {
    local chain line rule ipt
    local removed_total=0
    local rules
    local queue_num="${1:-$QNUM}"

    [ -n "$queue_num" ] || return 0

    for ipt in iptables ip6tables; do
        for chain in OUTPUT INPUT; do
            rules="$($ipt -t mangle -S "$chain" 2>/dev/null)"
            [ -z "$rules" ] && continue

            while IFS= read -r line; do
                case "$line" in
                    "-A $chain "*)
                        case "$line" in
                            *"-j NFQUEUE"*"--queue-num $queue_num"*)
                                rule="${line#-A $chain }"
                                if [ -n "$rule" ]; then
                                    # shellcheck disable=SC2086
                                    if $ipt -t mangle -D "$chain" $rule 2>/dev/null; then
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
    done

    echo "$removed_total"
}

# Remove ALL NFQUEUE rules from mangle OUTPUT/INPUT regardless of queue number.
# Handles both IPv4 and IPv6. Used during startup to clean stale rules.
remove_all_nfqueue_rules() {
    local chain line rule ipt
    local removed_total=0
    local rules

    for ipt in iptables ip6tables; do
        for chain in OUTPUT INPUT; do
            rules="$($ipt -t mangle -S "$chain" 2>/dev/null)"
            [ -z "$rules" ] && continue

            while IFS= read -r line; do
                case "$line" in
                    "-A $chain "*)
                        case "$line" in
                            *"-j NFQUEUE"*"--queue-bypass"*)
                                rule="${line#-A $chain }"
                                if [ -n "$rule" ]; then
                                    # shellcheck disable=SC2086
                                    if $ipt -t mangle -D "$chain" $rule 2>/dev/null; then
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
    done

    echo "$removed_total"
}
