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

# Core paths
NFQWS2="$ZAPRET_DIR/nfqws2"
CONFIG="$ZAPRET_DIR/config.sh"
STRATEGIES_FILE="$ZAPRET_DIR/strategies.sh"
BLOBS_FILE="$ZAPRET_DIR/blobs.txt"
LISTS_DIR="$ZAPRET_DIR/lists"

# Runtime files (in writable location)
PIDFILE="/data/local/tmp/nfqws2.pid"
LOGFILE="/data/local/tmp/zapret2.log"
CMDLINE_FILE="/data/local/tmp/nfqws2-cmdline.txt"
STARTUP_LOG="/data/local/tmp/nfqws2-startup.log"
ERROR_LOG="/data/local/tmp/nfqws2-error.log"
DEBUG_LOG="/data/local/tmp/nfqws2-debug.log"

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
    USE_CATEGORIES=1

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

    # Load strategies helper if exists
    if [ -f "$STRATEGIES_FILE" ]; then
        . "$STRATEGIES_FILE"
        log_msg "Loaded strategies from $STRATEGIES_FILE"
    fi

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
# HOSTLIST / IPSET FILTERING
##########################################################################################

# Build hostlist/ipset options based on HOSTLIST_MODE
# Arguments: $1 = existing filter options
# Returns: filter options with hostlist/ipset added
build_filter_options() {
    local filter="$1"

    case "$HOSTLIST_MODE" in
        hostlist)
            # Use domain-based filtering (SNI in TLS, Host in HTTP)
            local hostlist_opts=""

            # Parse comma-separated list of hostlist files
            local IFS_OLD="$IFS"
            IFS=","
            for file in $HOSTLIST_FILES; do
                # Trim whitespace
                file=$(echo "$file" | tr -d ' ')
                local full_path="$LISTS_DIR/$file"

                if [ -f "$full_path" ]; then
                    hostlist_opts="$hostlist_opts --hostlist=$full_path"
                    log_debug "Added hostlist: $file"
                else
                    log_error "Hostlist file not found: $full_path"
                fi
            done
            IFS="$IFS_OLD"

            if [ -n "$hostlist_opts" ]; then
                echo "$filter$hostlist_opts"
                return
            fi
            ;;

        ipset)
            # Use IP-based filtering (faster but less precise)
            local ipset_opts=""

            # Parse comma-separated list of ipset files
            local IFS_OLD="$IFS"
            IFS=","
            for file in $HOSTLIST_FILES; do
                # Trim whitespace
                file=$(echo "$file" | tr -d ' ')

                # Convert hostlist filename to ipset filename
                # e.g., youtube.txt -> ipset-youtube.txt
                local ipset_file="ipset-${file}"
                local full_path="$LISTS_DIR/$ipset_file"

                if [ -f "$full_path" ]; then
                    ipset_opts="$ipset_opts --ipset=$full_path"
                    log_debug "Added ipset: $ipset_file"
                else
                    # Try original filename (maybe it's already ipset-*)
                    full_path="$LISTS_DIR/$file"
                    if [ -f "$full_path" ]; then
                        ipset_opts="$ipset_opts --ipset=$full_path"
                        log_debug "Added ipset: $file"
                    else
                        log_error "Ipset file not found: $LISTS_DIR/$ipset_file or $LISTS_DIR/$file"
                    fi
                fi
            done
            IFS="$IFS_OLD"

            if [ -n "$ipset_opts" ]; then
                echo "$filter$ipset_opts"
                return
            fi
            ;;

        none|*)
            # No filtering - apply to all traffic on specified ports
            log_debug "HOSTLIST_MODE=none, no domain/IP filtering"
            ;;
    esac

    echo "$filter"
}

# Get hostlist option for a specific category
# Arguments: $1 = category hostlist filename
# Returns: --hostlist=path or empty string
get_category_hostlist() {
    local hostlist_file="$1"

    # Skip if hostlist mode is not enabled for categories
    if [ "$HOSTLIST_MODE" = "none" ]; then
        echo ""
        return
    fi

    local full_path="$LISTS_DIR/$hostlist_file"

    if [ "$HOSTLIST_MODE" = "ipset" ]; then
        # Convert to ipset filename
        local ipset_file="ipset-${hostlist_file}"
        full_path="$LISTS_DIR/$ipset_file"

        if [ -f "$full_path" ]; then
            echo "--ipset=$full_path"
        else
            echo ""
        fi
    else
        # Use hostlist
        if [ -f "$full_path" ]; then
            echo "--hostlist=$full_path"
        else
            echo ""
        fi
    fi
}

##########################################################################################
# LUA INITIALIZATION
##########################################################################################

# Build Lua init options
build_lua_opts() {
    local lua_opts=""

    # Load Lua files in correct order: lib -> antidpi -> auto
    if [ -f "$ZAPRET_DIR/lua/zapret-lib.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-lib.lua"
        log_debug "Added Lua: zapret-lib.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-antidpi.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-antidpi.lua"
        log_debug "Added Lua: zapret-antidpi.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-auto.lua"
        log_debug "Added Lua: zapret-auto.lua"
    fi

    echo "$lua_opts"
}

##########################################################################################
# BLOB LOADING
##########################################################################################

# Build blob options from blobs.txt
build_blob_opts() {
    local blob_opts=""

    if [ ! -f "$BLOBS_FILE" ]; then
        log_msg "No blobs.txt found, using built-in blobs only"
        echo ""
        return
    fi

    log_msg "Loading blobs from $BLOBS_FILE"

    while IFS= read -r line || [ -n "$line" ]; do
        # Skip empty lines and comments
        case "$line" in
            ""|\#*) continue ;;
        esac

        # Replace @bin/ with full path
        local blob_opt=$(echo "$line" | sed "s|@bin/|@$ZAPRET_DIR/bin/|g")
        blob_opts="$blob_opts $blob_opt"
    done < "$BLOBS_FILE"

    echo "$blob_opts"
}

##########################################################################################
# DEBUG OPTIONS
##########################################################################################

# Build debug/logging options
build_debug_opts() {
    local debug_opts=""

    case "$LOG_MODE" in
        android)
            debug_opts="--debug=android"
            log_msg "Debug output: android logcat"
            ;;
        file)
            debug_opts="--debug=@$DEBUG_LOG"
            log_msg "Debug output: file ($DEBUG_LOG)"
            ;;
        syslog)
            debug_opts="--debug=syslog"
            log_msg "Debug output: syslog"
            ;;
        none|*)
            # No debug output (default for production)
            log_msg "Debug output: disabled"
            ;;
    esac

    echo "$debug_opts"
}

##########################################################################################
# STRATEGY BUILDING - PRESET MODE
##########################################################################################

# Build options for preset-based strategies
build_preset_options() {
    local strategy_opts=""

    log_msg "Using preset: $STRATEGY_PRESET"

    case "$STRATEGY_PRESET" in
        youtube)
            # YouTube strategy with syndata + multisplit
            strategy_opts="--filter-tcp=443 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
            ;;

        discord)
            # Discord TLS + Voice (STUN/Discord protocol detection)
            strategy_opts="--filter-tcp=443 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld \
--new \
--filter-l7=stun,discord \
--payload=stun,discord_ip_discovery \
--lua-desync=fake:blob=fake_stun:repeats=6"
            ;;

        all)
            # Aggressive multi-protocol bypass
            strategy_opts="--filter-tcp=80 \
--lua-desync=fake:blob=http_fake:badsum \
--lua-desync=multisplit:pos=host+1 \
--new \
--filter-tcp=443 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:repeats=6 \
--lua-desync=multidisorder:pos=1,midsld \
--new \
--filter-udp=443 \
--lua-desync=fake:blob=quic_google:repeats=6"
            ;;

        custom)
            strategy_opts="$CUSTOM_STRATEGY"
            ;;

        *)
            log_error "Unknown strategy preset: $STRATEGY_PRESET"
            return 1
            ;;
    esac

    # Apply hostlist/ipset filtering if enabled
    strategy_opts=$(build_filter_options "$strategy_opts")

    echo "$strategy_opts"
}

##########################################################################################
# STRATEGY BUILDING - CATEGORY MODE (LEGACY)
##########################################################################################

# Add a category strategy to options (legacy - used when categories.txt not present)
# Arguments: $1=name, $2=strategy_id, $3=filter, $4=hostlist_file
# Uses global: OPTS, first
add_category() {
    local name="$1"
    local strategy="$2"
    local filter="$3"
    local hostlist="$4"

    # Skip if strategy is empty or "none"
    if [ -z "$strategy" ] || [ "$strategy" = "none" ]; then
        return
    fi

    # Build full filter with --out-range (uses PKT_OUT from config.sh)
    local full_filter="--out-range=-d$PKT_OUT $filter"

    # Add hostlist if available
    local hostlist_opt=$(get_category_hostlist "$hostlist")
    if [ -n "$hostlist_opt" ]; then
        full_filter="$full_filter $hostlist_opt"
    fi

    # Get strategy options
    local strat_opts=$(get_strategy_options "$strategy" "$full_filter")

    if [ -z "$strat_opts" ]; then
        log_msg "WARNING: No options for $name: $strategy"
        return
    fi

    # Add --new separator if not first strategy
    if [ $first -eq 0 ]; then
        OPTS="$OPTS --new"
    fi

    OPTS="$OPTS $strat_opts"
    first=0
    log_msg "Added $name: $strategy (pkt=$PKT_OUT)"
}

##########################################################################################
# STRATEGY BUILDING - CATEGORIES.TXT PARSING
##########################################################################################

# Path to categories configuration file
CATEGORIES_FILE="$ZAPRET_DIR/categories.txt"

# Build filter option for a category based on filter mode
# Arguments: $1=filter_mode, $2=hostlist_file
# Returns: filter options string
build_category_filter() {
    local filter_mode="$1"
    local hostlist_file="$2"
    local filter_opts=""

    case "$filter_mode" in
        hostlist)
            if [ -n "$hostlist_file" ] && [ -f "$LISTS_DIR/$hostlist_file" ]; then
                filter_opts="--hostlist=$LISTS_DIR/$hostlist_file"
                log_debug "Using hostlist: $hostlist_file"
            fi
            ;;
        ipset)
            local ipset_file="ipset-${hostlist_file}"
            if [ -n "$hostlist_file" ] && [ -f "$LISTS_DIR/$ipset_file" ]; then
                filter_opts="--ipset=$LISTS_DIR/$ipset_file"
                log_debug "Using ipset: $ipset_file"
            fi
            ;;
        none|*)
            # No filtering
            log_debug "No domain/IP filtering for this category"
            ;;
    esac

    echo "$filter_opts"
}

# Build options for a single category from categories.txt
# Arguments: $1=category, $2=protocol, $3=filter_mode, $4=hostlist, $5=strategy_name
# Uses global: OPTS, first
build_category_options_single() {
    local category="$1"
    local protocol="$2"
    local filter_mode="$3"
    local hostlist="$4"
    local strategy_name="$5"

    # Skip if strategy_name is empty
    if [ -z "$strategy_name" ]; then
        log_msg "WARNING: Empty strategy_name for category: $category"
        return
    fi

    # Determine protocol filter based on PROTOCOL field (not category name!)
    local proto_filter=""
    local strat_opts=""
    case "$protocol" in
        stun)
            # STUN/Voice - use hardcoded strategy
            proto_filter="--filter-l7=stun,discord"
            local full_filter="--out-range=-d$PKT_OUT $proto_filter --payload=stun,discord_ip_discovery"

            # Add hostlist/ipset if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Hardcoded STUN strategy
            strat_opts="$full_filter --lua-desync=fake:blob=fake_stun:repeats=6"
            ;;
        udp)
            # UDP/QUIC - use get_udp_strategy_options
            proto_filter="--filter-udp=443"
            local full_filter="--out-range=-d$PKT_OUT $proto_filter"

            # Add hostlist/ipset if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get UDP strategy options
            strat_opts=$(get_udp_strategy_options "$strategy_name" "$full_filter")
            ;;
        tcp|*)
            # TCP (default) - use get_tcp_strategy_options
            proto_filter="--filter-tcp=80,443"
            local full_filter="--out-range=-d$PKT_OUT $proto_filter"

            # Add hostlist/ipset if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get TCP strategy options
            strat_opts=$(get_tcp_strategy_options "$strategy_name" "$full_filter")
            ;;
    esac

    if [ -z "$strat_opts" ]; then
        log_msg "WARNING: No options for category $category with strategy $strategy_name"
        return
    fi

    # Add --new separator if not first strategy
    if [ $first -eq 0 ]; then
        OPTS="$OPTS --new"
    fi

    OPTS="$OPTS $strat_opts"
    first=0
    log_msg "Added category: $category (proto=$protocol, strategy=$strategy_name, filter=$filter_mode)"
}

# Parse categories from categories.txt file
# Format: CATEGORY|PROTOCOL|FILTER_MODE|HOSTLIST_FILE|STRATEGY_NAME
parse_categories() {
    local categories_file="$CATEGORIES_FILE"

    if [ ! -f "$categories_file" ]; then
        log_error "Categories file not found: $categories_file"
        return 1
    fi

    log_msg "Parsing categories from: $categories_file"

    # Read each line, skip comments and empty lines
    # NEW FORMAT: CATEGORY|PROTOCOL|FILTER_MODE|HOSTLIST_FILE|STRATEGY_NAME
    while IFS='|' read -r category protocol filter_mode hostlist strategy_name; do
        # Skip comments and empty lines
        case "$category" in
            "#"*|"") continue ;;
        esac

        # Trim whitespace from all fields
        category=$(echo "$category" | tr -d ' \t\r')
        protocol=$(echo "$protocol" | tr -d ' \t\r')
        filter_mode=$(echo "$filter_mode" | tr -d ' \t\r')
        hostlist=$(echo "$hostlist" | tr -d ' \t\r')
        strategy_name=$(echo "$strategy_name" | tr -d ' \t\r')

        # Skip disabled categories (strategy_name == "disabled")
        if [ "$strategy_name" = "disabled" ]; then
            log_debug "Skipping disabled category: $category"
            continue
        fi

        log_msg "Category: $category (proto=$protocol, filter=$filter_mode, hostlist=$hostlist, strategy=$strategy_name)"

        # Build options for this category
        build_category_options_single "$category" "$protocol" "$filter_mode" "$hostlist" "$strategy_name"

    done < "$categories_file"

    return 0
}

# Build options for category-based strategies (main entry point)
build_category_options() {
    first=1

    log_msg "Building category-based options..."

    # Try to parse categories.txt first (new format)
    if [ -f "$CATEGORIES_FILE" ]; then
        log_msg "Using categories.txt configuration"
        parse_categories

        # If no categories enabled, nfqws2 starts with base options only (no strategies)
        if [ $first -eq 1 ]; then
            log_msg "No enabled categories found, starting with base options only"
        fi
        return
    fi

    # Fallback to legacy config.sh variables
    log_msg "categories.txt not found, using legacy config variables"

    # YouTube TCP
    add_category "YouTube TCP" "$STRATEGY_YOUTUBE" "--filter-tcp=80,443" "youtube.txt"

    # YouTube QUIC/UDP
    add_category "YouTube QUIC" "$STRATEGY_YOUTUBE_UDP" "--filter-udp=443" "youtube.txt"

    # Discord TCP
    add_category "Discord TCP" "$STRATEGY_DISCORD" "--filter-tcp=80,443" "discord.txt"

    # Voice (Discord + Telegram) - STUN/Discord protocol detection
    if [ -n "$STRATEGY_DISCORD_VOICE_UDP" ] && [ "$STRATEGY_DISCORD_VOICE_UDP" != "none" ]; then
        if [ $first -eq 0 ]; then
            OPTS="$OPTS --new"
        fi
        OPTS="$OPTS --out-range=-d$PKT_OUT --filter-l7=stun,discord --payload=stun,discord_ip_discovery --lua-desync=fake:blob=fake_stun:repeats=6"
        first=0
        log_msg "Added Voice (STUN/Discord) (pkt=$PKT_OUT)"
    fi

    # Telegram TCP
    add_category "Telegram TCP" "$STRATEGY_TELEGRAM_TCP" "--filter-tcp=80,443" "telegram.txt"

    # WhatsApp TCP
    add_category "WhatsApp TCP" "$STRATEGY_WHATSAPP_TCP" "--filter-tcp=80,443" "whatsapp.txt"

    # Facebook TCP
    add_category "Facebook TCP" "$STRATEGY_FACEBOOK_TCP" "--filter-tcp=80,443" "facebook.txt"

    # Instagram TCP
    add_category "Instagram TCP" "$STRATEGY_INSTAGRAM_TCP" "--filter-tcp=80,443" "instagram.txt"

    # Twitter TCP
    add_category "Twitter TCP" "$STRATEGY_TWITTER_TCP" "--filter-tcp=80,443" "twitter.txt"

    # GitHub TCP
    add_category "GitHub TCP" "$STRATEGY_GITHUB_TCP" "--filter-tcp=443" "github.txt"

    # Steam TCP
    add_category "Steam TCP" "$STRATEGY_STEAM_TCP" "--filter-tcp=80,443" "steam.txt"

    # Twitch TCP
    add_category "Twitch TCP" "$STRATEGY_TWITCH_TCP" "--filter-tcp=443" "twitch.txt"

    # SoundCloud TCP
    add_category "SoundCloud TCP" "$STRATEGY_SOUNDCLOUD_TCP" "--filter-tcp=80,443" "soundcloud.txt"

    # Rutracker TCP
    add_category "Rutracker TCP" "$STRATEGY_RUTRACKER_TCP" "--filter-tcp=80,443" "rutracker.txt"

    # Other (Hostlist)
    add_category "Other HTTPS" "$STRATEGY_OTHER" "--filter-tcp=443" "other.txt"

    # If no categories configured, nfqws2 starts with base options only (no strategies)
    if [ $first -eq 1 ]; then
        log_msg "No categories configured, starting with base options only"
    fi
}

##########################################################################################
# MAIN OPTIONS BUILDER
##########################################################################################

# Build complete nfqws2 command line options
build_options() {
    log_section "Building nfqws2 options"

    # Base options with uid for privilege drop (1=system, 3003=inet)
    OPTS="--qnum=$QNUM --fwmark=$DESYNC_MARK --uid=1:3003"

    # IP cache for better performance
    OPTS="$OPTS --ipcache-lifetime=84600 --ipcache-hostname=1"

    # Add debug options
    local debug_opts=$(build_debug_opts)
    if [ -n "$debug_opts" ]; then
        OPTS="$OPTS $debug_opts"
    fi

    # Add Lua init options
    local lua_opts=$(build_lua_opts)
    if [ -n "$lua_opts" ]; then
        OPTS="$OPTS$lua_opts"
    fi

    # Add blob options
    local blob_opts=$(build_blob_opts)
    if [ -n "$blob_opts" ]; then
        OPTS="$OPTS$blob_opts"
    fi

    # Auto-detect categories mode if categories.txt exists
    if [ -f "$CATEGORIES_FILE" ]; then
        USE_CATEGORIES=1
    fi

    # Build strategy options based on mode
    if [ "$USE_CATEGORIES" = "1" ]; then
        log_msg "Mode: Category-based configuration"
        log_msg "Packet count (--out-range): $PKT_OUT"
        build_category_options
    else
        log_msg "Mode: Preset-based configuration"
        local preset_opts=$(build_preset_options)
        if [ $? -ne 0 ]; then
            return 1
        fi
        OPTS="$OPTS $preset_opts"
    fi

    # Log final statistics
    local new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
    local strategy_count=$((new_count + 1))
    log_msg "Total strategies configured: $strategy_count"
    log_msg "Final command length: $(echo "$OPTS" | wc -c) chars"

    # Clear old debug log
    > "$DEBUG_LOG" 2>/dev/null

    echo "$OPTS"
    return 0
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

    # Wait for startup
    sleep 3

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
