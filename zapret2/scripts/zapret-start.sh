#!/system/bin/sh
##########################################################################################
# Zapret2 Start Script
##########################################################################################

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"

NFQWS2="$ZAPRET_DIR/nfqws2"
PIDFILE="/data/local/tmp/nfqws2.pid"
LOGFILE="/data/local/tmp/zapret2.log"
CONFIG="$ZAPRET_DIR/config.sh"

##########################################################################################
# Logging
##########################################################################################

log_msg() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1" >> "$LOGFILE"
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

log_error() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [ERROR] $1" >> "$LOGFILE"
    /system/bin/log -t "Zapret2" "ERROR: $1" 2>/dev/null
}

##########################################################################################
# Load configuration
##########################################################################################

# Default values
QNUM=200
DESYNC_MARK=0x40000000
PORTS_TCP="80,443"
PORTS_UDP="443"
PKT_OUT=20
PKT_IN=10
STRATEGY_PRESET="youtube"
LOG_MODE="android"
USE_HOSTLIST=0
USE_IPSET=0

if [ -f "$CONFIG" ]; then
    . "$CONFIG"
    log_msg "Loaded config from $CONFIG"
else
    log_msg "Using default configuration"
fi

##########################################################################################
# Check if already running
##########################################################################################

if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE")
    if [ -d "/proc/$PID" ]; then
        log_msg "Already running with PID $PID"
        echo "Zapret2 is already running (PID: $PID)"
        exit 0
    else
        rm -f "$PIDFILE"
    fi
fi

##########################################################################################
# Check binary
##########################################################################################

if [ ! -f "$NFQWS2" ]; then
    log_error "nfqws2 binary not found at $NFQWS2"
    echo "ERROR: nfqws2 binary not found!"
    echo "Please download from GitHub releases:"
    echo "https://github.com/bol-van/zapret/releases"
    exit 1
fi

if [ ! -x "$NFQWS2" ]; then
    chmod 755 "$NFQWS2"
fi

# Verify binary works
log_msg "Testing nfqws2 binary..."
HELP_OUT=$($NFQWS2 --help 2>&1 | head -1)
if [ -z "$HELP_OUT" ]; then
    log_error "nfqws2 binary does not respond to --help"
else
    log_msg "nfqws2 responds: $HELP_OUT"
fi

##########################################################################################
# Fix permissions for dropped privileges
# nfqws2 drops to uid 1:3003 after start, needs +x on all directories
##########################################################################################

fix_permissions() {
    log_msg "Fixing permissions for non-root access..."

    # ALL parent directories in path need +x for traversal by uid 1
    chmod 755 /data 2>/dev/null
    chmod 755 /data/adb 2>/dev/null
    chmod 755 /data/adb/modules 2>/dev/null
    chmod 755 "$MODDIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/lua" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/bin" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/lists" 2>/dev/null
    chmod 755 "$ZAPRET_DIR/scripts" 2>/dev/null

    # Lua files - READ for all (fopen "rb")
    chmod 644 "$ZAPRET_DIR/lua/"*.lua 2>/dev/null
    chmod 644 "$ZAPRET_DIR/lua/"*.lua.gz 2>/dev/null

    # Blob files - READ for all
    chmod 644 "$ZAPRET_DIR/bin/"*.bin 2>/dev/null

    # Hostlist files - READ for all
    chmod 644 "$ZAPRET_DIR/lists/"*.txt 2>/dev/null

    # Auto-hostlist - WRITE permission (if used)
    touch "$ZAPRET_DIR/lists/autohostlist.txt" 2>/dev/null
    chmod 666 "$ZAPRET_DIR/lists/autohostlist.txt" 2>/dev/null

    # Binary needs execute
    chmod 755 "$NFQWS2" 2>/dev/null

    # Fix SELinux context (critical for Android!)
    chcon -R u:object_r:system_file:s0 "$MODDIR" 2>/dev/null

    log_msg "Permissions and SELinux context fixed"
}

fix_permissions

##########################################################################################
# Apply iptables rules
##########################################################################################

apply_iptables() {
    log_msg "Applying iptables rules..."

    # Enable liberal TCP tracking (important for RST with wrong ACK)
    sysctl -w net.netfilter.nf_conntrack_tcp_be_liberal=1 2>/dev/null

    # OUTPUT chain - outgoing TCP
    iptables -t mangle -A OUTPUT \
        -p tcp -m multiport --dports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    if [ $? -eq 0 ]; then
        log_msg "  Added TCP OUTPUT rule"
    else
        log_error "  Failed to add TCP OUTPUT rule"
    fi

    # OUTPUT chain - outgoing UDP (QUIC)
    iptables -t mangle -A OUTPUT \
        -p udp -m multiport --dports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_OUT --connbytes-dir=original --connbytes-mode=packets \
        -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    if [ $? -eq 0 ]; then
        log_msg "  Added UDP OUTPUT rule"
    else
        log_error "  Failed to add UDP OUTPUT rule (connbytes may not be supported)"
    fi

    # INPUT chain - incoming TCP (for autohostlist detection)
    iptables -t mangle -A INPUT \
        -p tcp -m multiport --sports $PORTS_TCP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    if [ $? -eq 0 ]; then
        log_msg "  Added TCP INPUT rule"
    fi

    # INPUT chain - incoming UDP
    iptables -t mangle -A INPUT \
        -p udp -m multiport --sports $PORTS_UDP \
        -m connbytes --connbytes 1:$PKT_IN --connbytes-dir=reply --connbytes-mode=packets \
        -j NFQUEUE --queue-num $QNUM --queue-bypass 2>/dev/null

    if [ $? -eq 0 ]; then
        log_msg "  Added UDP INPUT rule"
    fi

    log_msg "iptables rules applied"
}

##########################################################################################
# Load strategies helper
##########################################################################################

STRATEGIES_FILE="$ZAPRET_DIR/strategies.sh"
LISTS_DIR="$ZAPRET_DIR/lists"

if [ -f "$STRATEGIES_FILE" ]; then
    . "$STRATEGIES_FILE"
fi

##########################################################################################
# Build nfqws2 options based on strategy preset or categories
##########################################################################################

build_options() {
    # Base options with uid for privilege drop (1=system, 3003=inet)
    OPTS="--qnum=$QNUM --fwmark=$DESYNC_MARK --uid=1:3003"

    # PKT_COUNT controls how many packets to process per connection
    # This is set dynamically from WebUI config (default: 4)
    PKT_COUNT="${PKT_COUNT:-4}"
    log_msg "Packet count (--out-range): $PKT_COUNT"

    # IP cache for better performance
    OPTS="$OPTS --ipcache-lifetime=84600 --ipcache-hostname=1"

    # Debug mode - default OFF for production
    NFQWS_LOG="/data/local/tmp/nfqws2-debug.log"
    case "$LOG_MODE" in
        android)
            OPTS="$OPTS --debug=android"
            log_msg "Debug: android logcat"
            ;;
        file)
            OPTS="$OPTS --debug=@$NFQWS_LOG"
            log_msg "Debug: file ($NFQWS_LOG)"
            ;;
        none|*)
            # No debug output (default for production)
            log_msg "Debug: disabled"
            ;;
    esac

    # Clear old debug log on start
    > "$NFQWS_LOG" 2>/dev/null

    # Lua init files (order matters: lib first, then antidpi)
    if [ -f "$ZAPRET_DIR/lua/zapret-lib.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-lib.lua"
    fi
    if [ -f "$ZAPRET_DIR/lua/zapret-antidpi.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-antidpi.lua"
    fi
    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-auto.lua"
    fi

    # Load blobs from blobs.txt (with correct paths)
    BLOBS_FILE="$ZAPRET_DIR/blobs.txt"
    if [ -f "$BLOBS_FILE" ]; then
        log_msg "Loading blobs from $BLOBS_FILE"
        while IFS= read -r line || [ -n "$line" ]; do
            # Skip empty lines and comments
            case "$line" in
                ""|\#*) continue ;;
            esac
            # Replace @bin/ with full path
            blob_opt=$(echo "$line" | sed "s|@bin/|@$ZAPRET_DIR/bin/|g")
            OPTS="$OPTS $blob_opt"
        done < "$BLOBS_FILE"
    else
        log_msg "No blobs.txt found, using built-in blobs only"
    fi

    # Check if using category-based configuration
    if [ "$USE_CATEGORIES" = "1" ]; then
        log_msg "Using category-based configuration (USE_CATEGORIES=1)"
        build_category_options
        return
    fi

    log_msg "Using preset-based configuration (STRATEGY_PRESET=$STRATEGY_PRESET)"
    # Strategy presets (legacy mode)
    case "$STRATEGY_PRESET" in
        youtube)
            # YouTube strategy with syndata + multisplit
            OPTS="$OPTS \
--filter-tcp=443 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
            ;;

        discord)
            # Discord TLS + Voice UDP
            OPTS="$OPTS \
--filter-tcp=443 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld \
--new \
--filter-udp=19294-50100 \
--payload=stun \
--lua-desync=fake:blob=quic1:repeats=6"
            ;;

        all)
            # Aggressive multi-protocol bypass
            OPTS="$OPTS \
--filter-tcp=80 \
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
            OPTS="$OPTS $CUSTOM_STRATEGY"
            ;;

        *)
            log_error "Unknown strategy preset: $STRATEGY_PRESET"
            exit 1
            ;;
    esac

    echo "$OPTS"
}

##########################################################################################
# Build options from per-category strategies
##########################################################################################

build_category_options() {
    first=1

    log_msg "Building category options..."

    # Helper function to add a category
    add_category() {
        local name="$1"
        local strategy="$2"
        local filter="$3"
        local hostlist="$4"

        if [ -n "$strategy" ] && [ "$strategy" != "none" ]; then
            # Build full filter with --out-range (from PKT_COUNT config)
            local full_filter="--out-range=-d$PKT_COUNT $filter"
            if [ -n "$hostlist" ] && [ -f "$LISTS_DIR/$hostlist" ]; then
                full_filter="$full_filter --hostlist=$LISTS_DIR/$hostlist"
            fi
            local strat_opts=$(get_strategy_options "$strategy" "$full_filter")
            if [ -n "$strat_opts" ]; then
                if [ $first -eq 0 ]; then
                    OPTS="$OPTS --new"
                fi
                OPTS="$OPTS $strat_opts"
                first=0
                log_msg "Added $name: $strategy (pkt=$PKT_COUNT)"
            else
                log_msg "WARNING: No options for $name: $strategy"
            fi
        fi
    }

    # YouTube TCP
    add_category "YouTube TCP" "$STRATEGY_YOUTUBE" "--filter-tcp=80,443" "youtube.txt"

    # YouTube QUIC/UDP
    add_category "YouTube QUIC" "$STRATEGY_YOUTUBE_UDP" "--filter-udp=443" "youtube.txt"

    # Discord TCP
    add_category "Discord TCP" "$STRATEGY_DISCORD" "--filter-tcp=80,443" "discord.txt"

    # Discord Voice UDP
    if [ -n "$STRATEGY_DISCORD_VOICE_UDP" ] && [ "$STRATEGY_DISCORD_VOICE_UDP" != "none" ]; then
        if [ $first -eq 0 ]; then
            OPTS="$OPTS --new"
        fi
        OPTS="$OPTS --filter-udp=19294-50100 --lua-desync=fake:blob=fake_stun:repeats=6"
        first=0
        log_msg "Added Discord Voice UDP"
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

    # If nothing configured, use default
    if [ $first -eq 1 ]; then
        log_msg "No categories configured, using default YouTube strategy"
        OPTS="$OPTS \
--filter-tcp=443 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
    fi

    # Count strategies (count --new occurrences + 1)
    new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
    strategy_count=$((new_count + 1))
    log_msg "Total strategies configured: $strategy_count"
    log_msg "Final OPTS length: $(echo "$OPTS" | wc -c) chars"

    echo "$OPTS"
}

##########################################################################################
# Start nfqws2
##########################################################################################

start_nfqws2() {
    log_msg "Starting nfqws2..."

    OPTS=$(build_options)

    # Save full command line to file for WebUI access
    CMDLINE_FILE="/data/local/tmp/nfqws2-cmdline.txt"
    echo "$NFQWS2 $OPTS" > "$CMDLINE_FILE"

    log_msg "========================================"
    log_msg "FULL COMMAND LINE:"
    log_msg "$NFQWS2 $OPTS"
    log_msg "========================================"
    log_msg "Command saved to: $CMDLINE_FILE"

    # Output files for parsing
    STARTUP_LOG="/data/local/tmp/nfqws2-startup.log"
    ERROR_LOG="/data/local/tmp/nfqws2-error.log"

    # Clear previous logs
    > "$STARTUP_LOG"
    > "$ERROR_LOG"

    # Start nfqws2 and capture both stdout and stderr
    $NFQWS2 $OPTS >"$STARTUP_LOG" 2>"$ERROR_LOG" &
    PID=$!

    # Wait for startup and initial output
    sleep 3

    # Parse startup output
    if [ -f "$STARTUP_LOG" ] && [ -s "$STARTUP_LOG" ]; then
        log_msg "=== nfqws2 startup output ==="
        while IFS= read -r line; do
            log_msg "  $line"
        done < "$STARTUP_LOG"
        log_msg "=== end startup output ==="
    fi

    # Check for errors in stderr
    if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
        log_msg "=== nfqws2 errors ==="
        while IFS= read -r line; do
            # Check if it's a real error or just info
            case "$line" in
                *error*|*Error*|*ERROR*|*fail*|*Fail*|*FAIL*|*cannot*|*Cannot*)
                    log_error "  $line"
                    ;;
                *)
                    log_msg "  $line"
                    ;;
            esac
        done < "$ERROR_LOG"
        log_msg "=== end errors ==="
    fi

    if [ -d "/proc/$PID" ]; then
        echo $PID > "$PIDFILE"
        log_msg "nfqws2 started successfully (PID: $PID)"

        # Count strategies from options
        new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
        strategy_count=$((new_count + 1))
        log_msg "Active strategies: $strategy_count"

        echo "Zapret2 started (PID: $PID)"
        echo "Strategies: $strategy_count"
    else
        log_error "nfqws2 failed to start (PID $PID exited)"

        # Show error details
        if [ -f "$ERROR_LOG" ] && [ -s "$ERROR_LOG" ]; then
            log_error "Error details:"
            tail -10 "$ERROR_LOG" | while IFS= read -r line; do
                log_error "  $line"
            done
        fi

        echo "ERROR: Failed to start nfqws2"
        echo "Check logs: $LOGFILE"
        echo "Error log: $ERROR_LOG"
        exit 1
    fi
}

##########################################################################################
# Main
##########################################################################################

log_msg "=========================================="
log_msg "Starting Zapret2 DPI bypass"
log_msg "Strategy: $STRATEGY_PRESET"
log_msg "=========================================="

apply_iptables
start_nfqws2

log_msg "Zapret2 started successfully"
