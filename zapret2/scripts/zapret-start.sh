#!/system/bin/sh
##########################################################################################
#
# Zapret2 Start Script
#
# This script starts the nfqws2 DPI bypass daemon with configured strategies.
# It handles:
#   - Configuration loading from runtime.ini, with bootstrap fallback only on regeneration failure
#   - Permission fixing for dropped privileges
#   - iptables rule application
#   - Strategy building (preset-based or category-based)
#   - Hostlist/ipset filtering support
#
##########################################################################################

##########################################################################################
# PATH CONFIGURATION
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"

# Load common constants (paths, runtime files, defaults)
. "$SCRIPT_DIR/common.sh"

# Fast mode is used by zapret-restart.sh to minimize restart latency.
FAST_RESTART="${FAST_RESTART:-0}"

##########################################################################################
# LOGGING FUNCTIONS
##########################################################################################

# Log informational message
log_msg() {
    local msg="$1"
    echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') $msg" >> "$LOGFILE"
}

# Log error message to Android system log (logcat)
log_system_error() {
    local msg="$1"
    if command -v log >/dev/null 2>&1; then
        log -p e -t Zapret2 "$msg" 2>/dev/null
    fi
}

# Log error message
log_error() {
    local msg="$1"
    echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $msg" >> "$LOGFILE"
    log_system_error "$msg"
}

# Log debug message (only if debug mode enabled)
log_debug() {
    local msg="$1"
    if [ "$DEBUG" = "1" ]; then
        echo "[DEBUG] $(date '+%Y-%m-%d %H:%M:%S') $msg" >> "$LOGFILE"
    fi
}

# Log section header
log_section() {
    local title="$1"
    log_msg "========================================"
    log_msg "$title"
    log_msg "========================================"
}

log_legacy_conflict() {
    local msg="$1"
    log_error "LEGACY CONFLICT: $msg"
}

##########################################################################################
# LOAD COMMAND BUILDER
##########################################################################################

# Load command builder functions (INI parsers, strategy builders, etc.)
. "$SCRIPT_DIR/command-builder.sh"

##########################################################################################
# DEFAULT CONFIGURATION VALUES
##########################################################################################

set_default_config() {
    set_core_config_defaults

    # Hostlist/Ipset mode: none, hostlist, ipset
    HOSTLIST_MODE="none"
    HOSTLIST_FILES="youtube.txt"
}

##########################################################################################
# CONFIGURATION LOADING
##########################################################################################

load_config() {
    log_msg "Loading configuration..."

    # Set defaults first
    set_default_config

    load_effective_core_config

    log_msg "$(runtime_config_status_message)"
    log_msg "$(core_config_source_message)"
    log_msg "$(bootstrap_fallback_message)"
    log_msg "Category state source: $CATEGORIES_FILE"

    if [ "$BOOTSTRAP_FALLBACK_USED" = "1" ]; then
        if [ -n "$RUNTIME_CONFIG_ERROR" ]; then
            log_error "runtime.ini regeneration failed: $RUNTIME_CONFIG_ERROR"
        fi

        if shell_config_sets_key "$CONFIG" "PRESET_MODE" && shell_config_sets_key "$USER_CONFIG" "PRESET_MODE"; then
            log_legacy_conflict "PRESET_MODE is defined in both $CONFIG and $USER_CONFIG; effective mode depends on shell source order"
        fi

        if shell_config_sets_key "$CONFIG" "CUSTOM_CMDLINE_FILE" && shell_config_sets_key "$USER_CONFIG" "CUSTOM_CMDLINE_FILE"; then
            log_legacy_conflict "CUSTOM_CMDLINE_FILE is defined in both $CONFIG and $USER_CONFIG; effective cmdline source depends on shell source order"
        fi

        case "$PRESET_MODE" in
            file|preset|txt)
                log_legacy_conflict "Legacy preset-file mode is active (PRESET_MODE=$PRESET_MODE, PRESET_FILE=${PRESET_FILE:-Default.txt}); runtime.ini bootstrap regeneration is unavailable"
                ;;
            cmdline|manual|raw)
                log_legacy_conflict "Legacy raw-cmdline mode is active (PRESET_MODE=$PRESET_MODE, CUSTOM_CMDLINE_FILE=${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}); runtime.ini bootstrap regeneration is unavailable"
                ;;
        esac
    else
        log_msg "Bootstrap shell inputs are migration-only while runtime.ini is available: $CONFIG, $USER_CONFIG"
    fi

    # Verify strategy INI files exist
    for ini_file in "$TCP_STRATEGIES_INI" "$UDP_STRATEGIES_INI" "$STUN_STRATEGIES_INI"; do
        if [ -f "$ini_file" ]; then
            log_msg "Found strategy file: $ini_file"
        else
            log_msg "WARNING: Strategy file not found: $ini_file"
        fi
    done

    if [ "$PRESET_MODE" = "file" ] || [ "$PRESET_MODE" = "preset" ] || [ "$PRESET_MODE" = "txt" ]; then
        local preset_path
        preset_path="$(resolve_preset_file_path "${PRESET_FILE:-Default.txt}")"
        if [ -f "$preset_path" ]; then
            log_msg "Found preset file: $preset_path"
        else
            log_msg "WARNING: Preset file not found: $preset_path"
        fi
    elif [ "$PRESET_MODE" = "cmdline" ] || [ "$PRESET_MODE" = "manual" ] || [ "$PRESET_MODE" = "raw" ]; then
        local cmdline_path
        cmdline_path="$(resolve_custom_cmdline_file_path "${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}")"
        if [ -f "$cmdline_path" ]; then
            log_msg "Found custom cmdline file: $cmdline_path"
        else
            log_msg "WARNING: Custom cmdline file not found: $cmdline_path"
        fi
    fi

    # Log current configuration
    log_debug "QNUM=$QNUM"
    log_debug "PORTS_TCP=$PORTS_TCP"
    log_debug "PORTS_UDP=$PORTS_UDP"
    log_debug "STRATEGY_PRESET=$STRATEGY_PRESET"
    log_debug "PRESET_MODE=$PRESET_MODE"
    log_debug "PRESET_FILE=$PRESET_FILE"
    log_debug "CUSTOM_CMDLINE_FILE=${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}"
    log_debug "NFQWS_UID=${NFQWS_UID:-<root>}"
    log_debug "HOSTLIST_MODE=$HOSTLIST_MODE"
    log_debug "PKT_OUT=$PKT_OUT"
    log_debug "PKT_IN=$PKT_IN"
}

##########################################################################################
# PROCESS MANAGEMENT
##########################################################################################

# Check if nfqws2 is already running
check_already_running() {
    if [ -f "$PIDFILE" ]; then
        local pid=$(cat "$PIDFILE")
        if [ -d "/proc/$pid" ]; then
            log_msg "Already running with PID $pid"

            # Check if iptables rules are still in place
            local ipt_count=0
            ipt_count=$(iptables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true)
            ipt_count=$((ipt_count + $(ip6tables -t mangle -L OUTPUT -n 2>/dev/null | grep -c NFQUEUE || true)))

            if [ "$ipt_count" -eq 0 ]; then
                log_msg "WARNING: nfqws2 running but iptables rules missing! Re-applying..."
                echo "Zapret2 is already running (PID: $pid) - restoring iptables rules"
                load_config
                apply_iptables
            else
                echo "Zapret2 is already running (PID: $pid)"
            fi

            return 0
        else
            # Stale PID file, remove it
            rm -f "$PIDFILE"
            log_msg "Removed stale PID file"
        fi
    fi
    return 1
}

# Kill any orphan/stale nfqws/nfqws2 processes from previous runs
kill_stale_nfqws() {
    local pids
    pids=$(pgrep -f 'nfqws' 2>/dev/null)
    if [ -n "$pids" ]; then
        log_msg "Killing stale nfqws processes: $pids"
        kill $pids 2>/dev/null
        sleep 0.1
        # Force kill survivors
        for p in $pids; do
            if [ -d "/proc/$p" ]; then
                kill -9 $p 2>/dev/null
                log_msg "Force-killed stale process: $p"
            fi
        done
    fi
    rm -f "$PIDFILE"
}

# Verify nfqws2 binary exists and is executable
check_binary() {
    log_msg "Checking nfqws2 binary..."

    if [ ! -f "$NFQWS2" ]; then
        log_error "nfqws2 binary not found at $NFQWS2"
        echo "ERROR: nfqws2 binary not found!"
        echo "Please download from GitHub releases:"
        echo "https://github.com/bol-van/zapret/releases"
        return 1
    fi

    if [ ! -x "$NFQWS2" ]; then
        chmod 755 "$NFQWS2"
        log_msg "Made nfqws2 executable"
    fi

    # In fast restart mode we skip expensive self-check probing.
    if [ "$FAST_RESTART" = "1" ]; then
        log_msg "Fast restart mode: skipping binary --help probe"
        return 0
    fi

    # Verify binary responds
    local help_out=$($NFQWS2 --help 2>&1 | head -1)
    if [ -z "$help_out" ]; then
        log_error "nfqws2 binary does not respond to --help"
        return 1
    else
        log_msg "nfqws2 binary OK: $help_out"
    fi

    return 0
}

##########################################################################################
# PERMISSION FIXING
##########################################################################################

# Refresh permissions for files that can change between updates/restarts.
refresh_dynamic_file_permissions() {
    # Parent directories must be traversable by dropped uid.
    chmod 755 /data 2>/dev/null
    chmod 755 /data/adb 2>/dev/null
    chmod 755 /data/adb/modules 2>/dev/null
    chmod 755 "$MODDIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR" 2>/dev/null

    # Subdirectories that must stay traversable/readable
    chmod 755 "$ZAPRET_DIR/lua" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/bin" 2>/dev/null
    chmod 755 "$LISTS_DIR" 2>/dev/null
    chmod 755 "$PRESETS_DIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/scripts" 2>/dev/null

    # Binary needs execute
    chmod 755 "$NFQWS2" 2>/dev/null

    # Lua files - READ for all
    chmod 644 "$ZAPRET_DIR/lua/"*.lua 2>/dev/null
    chmod 644 "$ZAPRET_DIR/lua/"*.lua.gz 2>/dev/null

    # Blob files - READ for all
    chmod 644 "$ZAPRET_DIR/bin/"*.bin 2>/dev/null

    # Hostlist/ipset files - READ for all
    chmod 644 "$LISTS_DIR/"*.txt 2>/dev/null

    # Preset files - READ for all
    chmod 644 "$PRESETS_DIR/"*.txt 2>/dev/null

    # Keep SELinux labels aligned for module files
    chcon -R u:object_r:system_file:s0 "$MODDIR" 2>/dev/null
}

# Fix permissions for non-root access after privilege drop
# nfqws2 drops to uid 1:3003 after start, needs +x on all directories
fix_permissions() {
    # Fast path: permissions were already initialized earlier.
    if [ "$FAST_RESTART" = "1" ] && [ -f "$PERM_STAMP_FILE" ]; then
        chmod 755 "$NFQWS2" 2>/dev/null
        refresh_dynamic_file_permissions
        log_msg "Fast restart mode: skipped heavy pass, refreshed dynamic file permissions"
        return
    fi

    if [ -f "$PERM_STAMP_FILE" ] && [ "${FORCE_PERM_FIX:-0}" != "1" ]; then
        chmod 755 "$NFQWS2" 2>/dev/null
        refresh_dynamic_file_permissions
        log_msg "Permissions already initialized, refreshed dynamic file permissions"
        return
    fi

    log_msg "Fixing permissions for non-root access..."

    # Parent directories need +x for traversal by uid 1
    chmod 755 /data 2>/dev/null
    chmod 755 /data/adb 2>/dev/null
    chmod 755 /data/adb/modules 2>/dev/null
    chmod 755 "$MODDIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR" 2>/dev/null

    # Subdirectories
    chmod 755 "$ZAPRET_DIR/lua" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/bin" 2>/dev/null
    chmod 755 "$LISTS_DIR" 2>/dev/null
    chmod 755 "$PRESETS_DIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/scripts" 2>/dev/null

    # Lua files - READ for all (fopen "rb")
    chmod 644 "$ZAPRET_DIR/lua/"*.lua 2>/dev/null
    chmod 644 "$ZAPRET_DIR/lua/"*.lua.gz 2>/dev/null

    # Blob files - READ for all
    chmod 644 "$ZAPRET_DIR/bin/"*.bin 2>/dev/null

    # Hostlist/ipset files - READ for all
    chmod 644 "$LISTS_DIR/"*.txt 2>/dev/null

    # Preset files - READ for all
    chmod 644 "$PRESETS_DIR/"*.txt 2>/dev/null

    # Auto-hostlist - WRITE permission (if used)
    touch "$LISTS_DIR/autohostlist.txt" 2>/dev/null
    chmod 666 "$LISTS_DIR/autohostlist.txt" 2>/dev/null

    # Binary needs execute
    chmod 755 "$NFQWS2" 2>/dev/null

    # Fix SELinux context (critical for Android!)
    chcon -R u:object_r:system_file:s0 "$MODDIR" 2>/dev/null

    echo "$(date '+%Y-%m-%d %H:%M:%S')" > "$PERM_STAMP_FILE" 2>/dev/null

    log_msg "Permissions and SELinux context fixed"
}

##########################################################################################
# IPTABLES RULES
##########################################################################################

# Apply iptables rules for traffic interception
apply_iptables() {
    log_section "Applying iptables rules"

    # Clean up ALL NFQUEUE rules before adding fresh rules
    local stale_removed
    stale_removed=$(remove_all_nfqueue_rules)
    if [ -n "$stale_removed" ] && [ "$stale_removed" -gt 0 ] 2>/dev/null; then
        log_msg "Removed stale NFQUEUE rules: $stale_removed"
    fi

    sysctl -w net.netfilter.nf_conntrack_tcp_be_liberal=1 2>/dev/null

    # ===== PROBE KERNEL CAPABILITIES =====
    local cap_nfqueue=1 cap_queue_bypass=1 cap_connbytes=1 cap_multiport=1 cap_mark=1
    local diag_parts=""

    # Probe 1: NFQUEUE with --queue-bypass
    if iptables -t mangle -A OUTPUT -p tcp --dport 1 -j NFQUEUE --queue-num 65534 --queue-bypass 2>/dev/null; then
        iptables -t mangle -D OUTPUT -p tcp --dport 1 -j NFQUEUE --queue-num 65534 --queue-bypass 2>/dev/null
    else
        # Try NFQUEUE without --queue-bypass
        if iptables -t mangle -A OUTPUT -p tcp --dport 1 -j NFQUEUE --queue-num 65534 2>/dev/null; then
            iptables -t mangle -D OUTPUT -p tcp --dport 1 -j NFQUEUE --queue-num 65534 2>/dev/null
            cap_queue_bypass=0
            diag_parts="${diag_parts}queue-bypass: no; "
            log_msg "queue-bypass not supported, using NFQUEUE without it"
        else
            cap_nfqueue=0
            diag_parts="${diag_parts}NFQUEUE: no; "
            log_error "Kernel does not support NFQUEUE target"
        fi
    fi

    # Probe 2: connbytes
    if [ "$cap_nfqueue" = "1" ]; then
        if iptables -t mangle -A OUTPUT -p tcp --dport 1 -m connbytes --connbytes 1:1 --connbytes-dir=original --connbytes-mode=packets -j ACCEPT 2>/dev/null; then
            iptables -t mangle -D OUTPUT -p tcp --dport 1 -m connbytes --connbytes 1:1 --connbytes-dir=original --connbytes-mode=packets -j ACCEPT 2>/dev/null
        else
            cap_connbytes=0
            diag_parts="${diag_parts}connbytes: no; "
            log_msg "connbytes not supported, applying rules without packet limit"
        fi
    fi

    # Probe 3: multiport
    if [ "$cap_nfqueue" = "1" ]; then
        if iptables -t mangle -A OUTPUT -p tcp -m multiport --dports 80,443 -j ACCEPT 2>/dev/null; then
            iptables -t mangle -D OUTPUT -p tcp -m multiport --dports 80,443 -j ACCEPT 2>/dev/null
        else
            cap_multiport=0
            diag_parts="${diag_parts}multiport: no; "
            log_msg "multiport not supported, using individual port rules"
        fi
    fi

    # Probe 4: mark match
    if [ "$cap_nfqueue" = "1" ]; then
        if iptables -t mangle -A OUTPUT -p tcp --dport 1 -m mark --mark 0x40000000/0x40000000 -j ACCEPT 2>/dev/null; then
            iptables -t mangle -D OUTPUT -p tcp --dport 1 -m mark --mark 0x40000000/0x40000000 -j ACCEPT 2>/dev/null
        else
            cap_mark=0
            diag_parts="${diag_parts}mark: no; "
            log_msg "mark match not supported, applying rules without desync mark filter"
        fi
    fi

    # ===== BUILD AND APPLY RULES =====
    local rules_ok=0 rules_fail=0 fail_details="" fallback_mode=0

    if [ "$cap_nfqueue" = "0" ]; then
        rules_fail=8
        fail_details="NFQUEUE not supported by kernel"
    else
        # Set fallback flag if any capability is missing
        [ "$cap_connbytes" = "0" ] || [ "$cap_multiport" = "0" ] || [ "$cap_mark" = "0" ] || [ "$cap_queue_bypass" = "0" ] && fallback_mode=1

        # Build NFQUEUE target string
        local nfq_target="-j NFQUEUE --queue-num $QNUM"
        [ "$cap_queue_bypass" = "1" ] && nfq_target="$nfq_target --queue-bypass"

        local mode_tag=""
        [ "$fallback_mode" = "1" ] && mode_tag=" [fallback]"

        for ipt in iptables ip6tables; do
            local label="IPv4"
            [ "$ipt" = "ip6tables" ] && label="IPv6"

            # Check binary exists
            if ! command -v $ipt >/dev/null 2>&1; then
                log_msg "$label: binary not found, skipping"
                continue
            fi

            # Check mangle table works
            if ! $ipt -t mangle -L OUTPUT -n >/dev/null 2>&1; then
                log_msg "$label mangle table not supported, skipping"
                continue
            fi

            # Build port lists: with multiport = single entry "80,443", without = separate "80" "443"
            local tcp_out_ports udp_out_ports tcp_in_ports udp_in_ports
            if [ "$cap_multiport" = "1" ]; then
                tcp_out_ports="$PORTS_TCP"
                udp_out_ports="$PORTS_UDP"
                tcp_in_ports="$PORTS_TCP"
                udp_in_ports="$PORTS_UDP"
            else
                tcp_out_ports=$(echo "$PORTS_TCP" | tr ',' ' ')
                udp_out_ports=$(echo "$PORTS_UDP" | tr ',' ' ')
                tcp_in_ports=$(echo "$PORTS_TCP" | tr ',' ' ')
                udp_in_ports=$(echo "$PORTS_UDP" | tr ',' ' ')
            fi

            # --- OUTPUT TCP ---
            for ports in $tcp_out_ports; do
                local pmatch
                [ "$cap_multiport" = "1" ] && pmatch="-m multiport --dports $ports" || pmatch="--dport $ports"
                local extra=""
                [ "$cap_connbytes" = "1" ] && extra="$extra -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets"
                [ "$cap_mark" = "1" ] && extra="$extra -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK"

                if $ipt -t mangle -A OUTPUT -p tcp $pmatch $extra $nfq_target 2>/dev/null; then
                    log_msg "Added $label TCP OUTPUT (ports: $ports)$mode_tag"
                    rules_ok=$((rules_ok + 1))
                else
                    log_error "Failed to add $label TCP OUTPUT (ports: $ports)"
                    fail_details="${fail_details}${label} TCP OUT($ports): failed\n"
                    rules_fail=$((rules_fail + 1))
                fi
            done

            # --- OUTPUT UDP ---
            for ports in $udp_out_ports; do
                local pmatch
                [ "$cap_multiport" = "1" ] && pmatch="-m multiport --dports $ports" || pmatch="--dport $ports"
                local extra=""
                [ "$cap_connbytes" = "1" ] && extra="$extra -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets"
                [ "$cap_mark" = "1" ] && extra="$extra -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK"

                if $ipt -t mangle -A OUTPUT -p udp $pmatch $extra $nfq_target 2>/dev/null; then
                    log_msg "Added $label UDP OUTPUT (ports: $ports)$mode_tag"
                    rules_ok=$((rules_ok + 1))
                else
                    log_error "Failed to add $label UDP OUTPUT (ports: $ports)"
                    fail_details="${fail_details}${label} UDP OUT($ports): failed\n"
                    rules_fail=$((rules_fail + 1))
                fi
            done

            # --- INPUT TCP ---
            for ports in $tcp_in_ports; do
                local pmatch
                [ "$cap_multiport" = "1" ] && pmatch="-m multiport --sports $ports" || pmatch="--sport $ports"
                local extra=""
                [ "$cap_connbytes" = "1" ] && extra="$extra -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets"

                if $ipt -t mangle -A INPUT -p tcp $pmatch $extra $nfq_target 2>/dev/null; then
                    log_msg "Added $label TCP INPUT (ports: $ports)$mode_tag"
                    rules_ok=$((rules_ok + 1))
                else
                    log_error "Failed to add $label TCP INPUT (ports: $ports)"
                    fail_details="${fail_details}${label} TCP IN($ports): failed\n"
                    rules_fail=$((rules_fail + 1))
                fi
            done

            # --- INPUT UDP ---
            for ports in $udp_in_ports; do
                local pmatch
                [ "$cap_multiport" = "1" ] && pmatch="-m multiport --sports $ports" || pmatch="--sport $ports"
                local extra=""
                [ "$cap_connbytes" = "1" ] && extra="$extra -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets"

                if $ipt -t mangle -A INPUT -p udp $pmatch $extra $nfq_target 2>/dev/null; then
                    log_msg "Added $label UDP INPUT (ports: $ports)$mode_tag"
                    rules_ok=$((rules_ok + 1))
                else
                    log_error "Failed to add $label UDP INPUT (ports: $ports)"
                    fail_details="${fail_details}${label} UDP IN($ports): failed\n"
                    rules_fail=$((rules_fail + 1))
                fi
            done
        done
    fi

    # ===== WRITE STATUS FILE =====
    local status_file="$ZAPRET_DIR/iptables-status"
    {
        echo "timestamp=$(date '+%Y-%m-%d %H:%M:%S')"
        echo "rules_ok=$rules_ok"
        echo "rules_fail=$rules_fail"
        echo "rules_total=$((rules_ok + rules_fail))"
        echo "nfqueue_supported=$cap_nfqueue"
        echo "queue_bypass_supported=$cap_queue_bypass"
        echo "connbytes_supported=$cap_connbytes"
        echo "multiport_supported=$cap_multiport"
        echo "mark_supported=$cap_mark"
        echo "fallback_mode=$fallback_mode"
        echo "diagnostics=${diag_parts%%; }"
        if [ "$rules_fail" -gt 0 ]; then
            echo "status=partial"
            echo "errors=$(printf '%s' "$fail_details" | tr '\n' '|' | sed 's/|$//')"
        else
            echo "status=ok"
            echo "errors="
        fi
    } > "$status_file" 2>/dev/null
    chmod 644 "$status_file" 2>/dev/null

    # ===== LOG SUMMARY =====
    if [ "$cap_nfqueue" = "0" ]; then
        log_error "NFQUEUE not supported. DPI bypass cannot work on this device."
    elif [ "$rules_fail" -gt 0 ] && [ "$rules_ok" -eq 0 ]; then
        log_error "ALL iptables rules failed ($rules_fail failures). nfqws2 will not receive traffic!"
    elif [ "$rules_fail" -gt 0 ]; then
        log_msg "iptables rules PARTIALLY applied ($rules_ok ok, $rules_fail failed)"
    elif [ "$fallback_mode" = "1" ]; then
        log_msg "iptables rules applied in FALLBACK mode ($rules_ok rules)"
    else
        log_msg "iptables rules applied successfully ($rules_ok rules)"
    fi
}

##########################################################################################
# PRE-FLIGHT DIAGNOSTICS
##########################################################################################

# Run advisory diagnostics before starting nfqws2.
# All checks are non-blocking: failures are reported but startup continues.
preflight_check() {
    log_msg "Running pre-flight diagnostics..."

    # a) Check core Lua files exist and are readable
    for lua_name in zapret-lib.lua zapret-antidpi.lua zapret-auto.lua; do
        local lua_path="$ZAPRET_DIR/lua/$lua_name"
        if [ ! -f "$lua_path" ]; then
            local msg="DIAGNOSTIC: Missing Lua file: $lua_name. Reinstall the module."
            echo "$msg"
            log_error "$msg"
        elif [ ! -r "$lua_path" ]; then
            local msg="DIAGNOSTIC: Lua file not readable: $lua_name. Check permissions."
            echo "$msg"
            log_error "$msg"
        fi
    done

    # b) nfqws2 Lua compatibility version check
    local lib_lua="$ZAPRET_DIR/lua/zapret-lib.lua"
    if [ -f "$lib_lua" ] && [ -f "$NFQWS2" ] && [ -x "$NFQWS2" ]; then
        local required_ver
        required_ver=$(grep 'NFQWS2_COMPAT_VER_REQUIRED' "$lib_lua" 2>/dev/null | head -1 | grep -o '[0-9][0-9]*')
        if [ -n "$required_ver" ]; then
            local binary_ver
            binary_ver=$($NFQWS2 --lua-exec="print(NFQWS2_COMPAT_VER or 'nil')" 2>/dev/null | tr -d '[:space:]')
            if [ -n "$binary_ver" ] && [ "$binary_ver" != "nil" ]; then
                if [ "$binary_ver" != "$required_ver" ]; then
                    local msg="DIAGNOSTIC: Version mismatch. nfqws2 binary has Lua compat version $binary_ver, but scripts require version $required_ver. Download nfqws2 and Lua scripts from the same zapret release."
                    echo "$msg"
                    log_error "$msg"
                else
                    log_msg "Lua compat version OK: $binary_ver"
                fi
            else
                log_msg "Could not determine nfqws2 Lua compat version, skipping check"
            fi
        fi
    fi

    # c) Check strategy INI files
    if [ ! -f "$TCP_STRATEGIES_INI" ]; then
        local msg="DIAGNOSTIC: Missing strategies-tcp.ini"
        echo "$msg"
        log_error "$msg"
    fi

    log_msg "Pre-flight diagnostics complete"
    return 0
}

##########################################################################################
# NFQWS2 STARTUP
##########################################################################################

should_retry_without_uid_drop() {
    [ -n "${NFQWS_UID:-}" ] || return 1
    [ "${NFQWS_UID}" != "0:0" ] || return 1
    [ -s "$ERROR_LOG" ] || return 1

    if grep -q "file_open_test: Permission denied" "$ERROR_LOG" 2>/dev/null &&
       grep -q "cannot access \(hostlist\|ipset\) file" "$ERROR_LOG" 2>/dev/null; then
        return 0
    fi

    return 1
}

# Start nfqws2 daemon
start_nfqws2() {
    log_section "Starting nfqws2"

    local retried_without_uid=0

    while true; do
        # Build options
        OPTS=$(build_options)
        if [ $? -ne 0 ]; then
            log_error "Failed to build options"
            return 1
        fi

        # Save full command line to file for WebUI access
        echo "$NFQWS2 $OPTS" > "$CMDLINE_FILE"
        log_msg "Command saved to: $CMDLINE_FILE"

        # Log full command
        log_section "FULL COMMAND LINE"
        log_msg "$NFQWS2 $OPTS"
        log_section "END COMMAND LINE"

        # Clear previous logs
        > "$STARTUP_LOG"
        > "$ERROR_LOG"

        # Start nfqws2 in background, capturing output
        $NFQWS2 $OPTS >"$STARTUP_LOG" 2>"$ERROR_LOG" &
        PID=$!

        # Quick check - nfqws2 starts in milliseconds
        sleep 0.2

        # Parse and log startup output
        if [ "$FAST_RESTART" != "1" ]; then
            parse_startup_output
        else
            log_startup_stderr_errors
        fi

        # Check if process is running
        if [ -d "/proc/$PID" ]; then
            echo $PID > "$PIDFILE"
            log_msg "nfqws2 started successfully (PID: $PID)"

            # Count and display active strategies
            local new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
            local strategy_count=$((new_count + 1))
            log_msg "Active strategies: $strategy_count"

            if [ "$retried_without_uid" -eq 1 ]; then
                log_msg "nfqws2 started after switching to --uid=0:0 for this run"
                log_msg "Tip: set NFQWS_UID=\"0:0\" in $CONFIG to avoid repeated fallback"
            fi

            echo "Zapret2 started (PID: $PID)"
            echo "Strategies: $strategy_count"
            echo "Config file: $CMDLINE_FILE"
            return 0
        fi

        log_error "nfqws2 failed to start (PID $PID exited)"

        if [ "$retried_without_uid" -eq 0 ] && should_retry_without_uid_drop; then
            log_msg "Detected file access error after --uid drop. Retrying with --uid=0:0..."
            NFQWS_UID="0:0"
            retried_without_uid=1
            continue
        fi

        show_error_details
        echo "ERROR: Failed to start nfqws2"
        echo "Check logs: $LOGFILE"
        echo "Error log: $ERROR_LOG"
        return 1
    done
}

# Parse startup output from nfqws2
parse_startup_output() {
    if [ -f "$STARTUP_LOG" ] && [ -s "$STARTUP_LOG" ]; then
        log_msg "=== nfqws2 startup output ==="
        while IFS= read -r line; do
            log_msg "  $line"
        done < "$STARTUP_LOG"
        log_msg "=== end startup output ==="
    fi

    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        log_msg "=== nfqws2 stderr ==="
        while IFS= read -r line; do
            [ -n "$line" ] && log_error "nfqws2 stderr: $line"
        done < "$ERROR_LOG"
        log_msg "=== end stderr ==="
    fi
}

# Log nfqws2 startup stderr in fast restart mode.
log_startup_stderr_errors() {
    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        while IFS= read -r line; do
            [ -n "$line" ] && log_error "nfqws2 stderr: $line"
        done < "$ERROR_LOG"
    fi
}

prepare_cmdline_core_runtime() {
    is_custom_cmdline_mode || return 0

    if sync_cmdline_core_overrides; then
        log_msg "Raw cmdline mode validated with runtime [core] values: QNUM=$QNUM DESYNC_MARK=$DESYNC_MARK NFQWS_UID=${NFQWS_UID:-0:0} LOG_MODE=${LOG_MODE:-none}"
        return 0
    fi

    log_error "Could not validate raw cmdline source before applying iptables"
    return 1
}

# Show detailed error information
show_error_details() {
    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        log_error "Error details:"
        tail -10 "$ERROR_LOG" | while IFS= read -r line; do
            log_error "  $line"
        done
    fi

    # Parse common errors and provide user-friendly diagnostics
    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        # Pattern 1: desync function not found
        local missing_func=$(grep -o "desync function '[^']*' does not exist" "$ERROR_LOG" | head -1)
        if [ -n "$missing_func" ]; then
            local func_name=$(echo "$missing_func" | grep -o "'[^']*'" | tr -d "'")
            local msg="DIAGNOSTIC: $missing_func"
            echo "$msg"
            log_error "$msg"
            # Check which Lua file should contain this function
            if grep -rql "function $func_name" "$ZAPRET_DIR/lua/" 2>/dev/null; then
                local lua_file=$(grep -rl "function $func_name" "$ZAPRET_DIR/lua/" 2>/dev/null | head -1)
                local lua_basename=$(basename "$lua_file")
                msg="DIAGNOSTIC: Function '$func_name' is defined in $lua_basename"
                echo "$msg"
                log_error "$msg"
                msg="DIAGNOSTIC: This file may not be loading. Check that all Lua files are present and compatible with your nfqws2 version."
                echo "$msg"
                log_error "$msg"
            else
                msg="DIAGNOSTIC: Function '$func_name' not found in any Lua file. Check your strategy configuration."
                echo "$msg"
                log_error "$msg"
            fi
            msg="DIAGNOSTIC: Try updating nfqws2 binary and Lua scripts from the same zapret release."
            echo "$msg"
            log_error "$msg"
        fi

        # Pattern 2: Lua compatibility error
        if grep -q "Incompatible NFQWS2_COMPAT_VER" "$ERROR_LOG" 2>/dev/null; then
            local msg="DIAGNOSTIC: Lua scripts are incompatible with nfqws2 binary version."
            echo "$msg"
            log_error "$msg"
            msg="DIAGNOSTIC: Download nfqws2 binary and Lua scripts from the same zapret release."
            echo "$msg"
            log_error "$msg"
        fi

        # Pattern 3: Permission denied
        if grep -q "Permission denied" "$ERROR_LOG" 2>/dev/null; then
            local msg="DIAGNOSTIC: Permission denied error. Try reinstalling the module or check file permissions."
            echo "$msg"
            log_error "$msg"
        fi

        # Pattern 4: file not found / cannot open
        local missing_file=$(grep -o "cannot open [^ ]*\|No such file[^)]*" "$ERROR_LOG" | head -1)
        if [ -n "$missing_file" ]; then
            local msg="DIAGNOSTIC: $missing_file"
            echo "$msg"
            log_error "$msg"
        fi

        # Pattern 5: Lua syntax error
        if grep -q "lua.*syntax error\|lua.*error loading" "$ERROR_LOG" 2>/dev/null; then
            local msg="DIAGNOSTIC: Lua syntax error. Scripts may be corrupted. Reinstall the module."
            echo "$msg"
            log_error "$msg"
        fi

        # Always show raw stderr for advanced users
        echo "DIAGNOSTIC: Raw error output:"
        log_error "DIAGNOSTIC: Raw error output:"
        tail -5 "$ERROR_LOG" | while IFS= read -r line; do
            if [ -n "$line" ]; then
                echo "DIAGNOSTIC: > $line"
                log_error "DIAGNOSTIC: > $line"
            fi
        done
    fi
}

##########################################################################################
# MAIN ENTRY POINT
##########################################################################################

main() {
    log_section "Starting Zapret2 DPI bypass"
    log_msg "Script version: 2.0"
    log_msg "Date: $(date)"

    # Load configuration
    load_config

    # Check if already running
    if check_already_running; then
        exit 0
    fi

    # Kill any stale nfqws/nfqws2 processes from old runs
    kill_stale_nfqws

    # Check binary
    if ! check_binary; then
        exit 1
    fi

    # Fix permissions
    fix_permissions

    # Run pre-flight diagnostics (advisory only, does not block startup)
    preflight_check

    prepare_cmdline_core_runtime

    # Apply iptables rules
    apply_iptables

    # Start nfqws2
    if ! start_nfqws2; then
        exit 1
    fi

    log_section "Zapret2 started successfully"
    log_msg "HOSTLIST_MODE: $HOSTLIST_MODE"
    log_msg "HOSTLIST_FILES: $HOSTLIST_FILES"

    exit 0
}

# Run main function
main "$@"
