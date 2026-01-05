#!/system/bin/sh
##########################################################################################
#
# Zapret2 Start Script
#
# This script starts the nfqws2 DPI bypass daemon with configured strategies.
# It handles:
#   - Configuration loading from config.sh
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

##########################################################################################
# LOGGING FUNCTIONS
##########################################################################################

# Log informational message
log_msg() {
    local msg="$1"
    echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') $msg" >> "$LOGFILE"
}

# Log error message
log_error() {
    local msg="$1"
    echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $msg" >> "$LOGFILE"
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

##########################################################################################
# LOAD COMMAND BUILDER
##########################################################################################

# Load command builder functions (INI parsers, strategy builders, etc.)
. "$SCRIPT_DIR/command-builder.sh"

##########################################################################################
# DEFAULT CONFIGURATION VALUES
##########################################################################################

set_default_config() {
    # NFQUEUE settings
    QNUM=200
    DESYNC_MARK=0x40000000

    # Port configuration
    PORTS_TCP="80,443"
    PORTS_UDP="443"

    # Packet interception limits
    PKT_OUT=20
    PKT_IN=10

    # Strategy configuration
    STRATEGY_PRESET="youtube"

    # Logging
    LOG_MODE="android"
    DEBUG=0

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

    # Load module config if exists (default values)
    if [ -f "$CONFIG" ]; then
        . "$CONFIG"
        log_msg "Loaded module config from $CONFIG"
    else
        log_msg "Using default configuration (no config.sh found)"
    fi

    # Load user config if exists (overrides module config)
    # This file persists across module updates
    USER_CONFIG="/data/local/tmp/zapret2-user.conf"
    if [ -f "$USER_CONFIG" ]; then
        . "$USER_CONFIG"
        log_msg "Loaded user config from $USER_CONFIG"
    fi

    # Verify strategy INI files exist
    for ini_file in "$TCP_STRATEGIES_INI" "$UDP_STRATEGIES_INI" "$STUN_STRATEGIES_INI"; do
        if [ -f "$ini_file" ]; then
            log_msg "Found strategy file: $ini_file"
        else
            log_msg "WARNING: Strategy file not found: $ini_file"
        fi
    done

    # Log current configuration
    log_debug "QNUM=$QNUM"
    log_debug "PORTS_TCP=$PORTS_TCP"
    log_debug "PORTS_UDP=$PORTS_UDP"
    log_debug "STRATEGY_PRESET=$STRATEGY_PRESET"
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
            echo "Zapret2 is already running (PID: $pid)"
            return 0
        else
            # Stale PID file, remove it
            rm -f "$PIDFILE"
            log_msg "Removed stale PID file"
        fi
    fi
    return 1
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

# Fix permissions for non-root access after privilege drop
# nfqws2 drops to uid 1:3003 after start, needs +x on all directories
fix_permissions() {
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
    chmod 755 "$ZAPRET_DIR/scripts" 2>/dev/null

    # Lua files - READ for all (fopen "rb")
    chmod 644 "$ZAPRET_DIR/lua/"*.lua 2>/dev/null
    chmod 644 "$ZAPRET_DIR/lua/"*.lua.gz 2>/dev/null

    # Blob files - READ for all
    chmod 644 "$ZAPRET_DIR/bin/"*.bin 2>/dev/null

    # Hostlist/ipset files - READ for all
    chmod 644 "$LISTS_DIR/"*.txt 2>/dev/null

    # Auto-hostlist - WRITE permission (if used)
    touch "$LISTS_DIR/autohostlist.txt" 2>/dev/null
    chmod 666 "$LISTS_DIR/autohostlist.txt" 2>/dev/null

    # Binary needs execute
    chmod 755 "$NFQWS2" 2>/dev/null

    # Fix SELinux context (critical for Android!)
    chcon -R u:object_r:system_file:s0 "$MODDIR" 2>/dev/null

    log_msg "Permissions and SELinux context fixed"
}

##########################################################################################
# IPTABLES RULES
##########################################################################################

# Apply iptables rules for traffic interception
apply_iptables() {
    log_section "Applying iptables rules"

    # Enable liberal TCP tracking (important for RST with wrong ACK)
    sysctl -w net.netfilter.nf_conntrack_tcp_be_liberal=1 2>/dev/null

    # OUTPUT chain - outgoing TCP
    if iptables -t mangle -A OUTPUT \
        -p tcp -m multiport --dports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null; then
        log_msg "Added TCP OUTPUT rule (ports: $PORTS_TCP)"
    else
        log_error "Failed to add TCP OUTPUT rule"
    fi

    # OUTPUT chain - outgoing UDP (QUIC)
    if iptables -t mangle -A OUTPUT \
        -p udp -m multiport --dports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null; then
        log_msg "Added UDP OUTPUT rule (ports: $PORTS_UDP)"
    else
        log_error "Failed to add UDP OUTPUT rule (connbytes may not be supported)"
    fi

    # INPUT chain - incoming TCP (for autohostlist detection)
    if iptables -t mangle -A INPUT \
        -p tcp -m multiport --sports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null; then
        log_msg "Added TCP INPUT rule"
    fi

    # INPUT chain - incoming UDP
    if iptables -t mangle -A INPUT \
        -p udp -m multiport --sports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null; then
        log_msg "Added UDP INPUT rule"
    fi

    log_msg "iptables rules applied"
}

##########################################################################################
# NFQWS2 STARTUP
##########################################################################################

# Start nfqws2 daemon
start_nfqws2() {
    log_section "Starting nfqws2"

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
    parse_startup_output

    # Check if process is running
    if [ -d "/proc/$PID" ]; then
        echo $PID > "$PIDFILE"
        log_msg "nfqws2 started successfully (PID: $PID)"

        # Count and display active strategies
        local new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
        local strategy_count=$((new_count + 1))
        log_msg "Active strategies: $strategy_count"

        echo "Zapret2 started (PID: $PID)"
        echo "Strategies: $strategy_count"
        echo "Config file: $CMDLINE_FILE"
        return 0
    else
        log_error "nfqws2 failed to start (PID $PID exited)"
        show_error_details
        echo "ERROR: Failed to start nfqws2"
        echo "Check logs: $LOGFILE"
        echo "Error log: $ERROR_LOG"
        return 1
    fi
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
            case "$line" in
                *error*|*Error*|*ERROR*|*fail*|*Fail*|*FAIL*|*cannot*|*Cannot*)
                    log_error "  $line"
                    ;;
                *)
                    log_msg "  $line"
                    ;;
            esac
        done < "$ERROR_LOG"
        log_msg "=== end stderr ==="
    fi
}

# Show detailed error information
show_error_details() {
    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        log_error "Error details:"
        tail -10 "$ERROR_LOG" | while IFS= read -r line; do
            log_error "  $line"
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

    # Check binary
    if ! check_binary; then
        exit 1
    fi

    # Fix permissions
    fix_permissions

    # Apply iptables rules
    apply_iptables

    # Start nfqws2
    if ! start_nfqws2; then
        exit 1
    fi

    log_section "Zapret2 started successfully"
    log_msg "HOSTLIST_MODE: $HOSTLIST_MODE"
    log_msg "HOSTLIST_FILES: $HOSTLIST_FILES"
}

# Run main function
main "$@"
