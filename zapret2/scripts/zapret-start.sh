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
    # Base options
    OPTS="--qnum=$QNUM --fwmark=$DESYNC_MARK"

    # Debug mode - only enable file debug for now (safest option)
    case "$LOG_MODE" in
        file)
            OPTS="$OPTS --debug=/data/local/tmp/nfqws2-debug.log"
            ;;
        # android and syslog may not be supported, skip for now
    esac

    # Lua init files
    if [ -f "$ZAPRET_DIR/lua/zapret-lib.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-lib.lua"
    fi
    if [ -f "$ZAPRET_DIR/lua/zapret-antidpi.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-antidpi.lua"
    fi
    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
        OPTS="$OPTS --lua-init=@$ZAPRET_DIR/lua/zapret-auto.lua"
    fi

    # Check if using category-based configuration
    if [ "$USE_CATEGORIES" = "1" ]; then
        build_category_options
        return
    fi

    # Strategy presets (legacy mode)
    case "$STRATEGY_PRESET" in
        youtube)
            OPTS="$OPTS \
--filter-tcp=80,443 --filter-l7=http,tls \
--out-range=-d10 \
--payload=tls_client_hello,http_req \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
            ;;

        discord)
            OPTS="$OPTS \
--filter-tcp=80,443 --filter-l7=tls \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld \
--new \
--filter-udp=19294-50100 \
--payload=stun \
--lua-desync=fake:blob=fake_stun:repeats=6"
            ;;

        all)
            OPTS="$OPTS \
--filter-tcp=80 --filter-l7=http \
--out-range=-d10 \
--payload=http_req \
--lua-desync=fake:blob=fake_default_http:tcp_md5 \
--lua-desync=multisplit:pos=method+2 \
--new \
--filter-tcp=443 --filter-l7=tls \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:ip_autottl=-1,3-20:ip6_autottl=-1,3-20:repeats=6:tcp_ack=-66000 \
--lua-desync=multidisorder:pos=1,midsld:seqovl=680:seqovl_pattern=tls_google \
--new \
--filter-udp=443 --filter-l7=quic \
--payload=quic_initial \
--lua-desync=fake:blob=fake_default_quic:repeats=6"
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

    # YouTube TLS
    if [ -n "$STRATEGY_YOUTUBE" ] && [ "$STRATEGY_YOUTUBE" != "none" ]; then
        filter="--filter-tcp=443 --filter-l7=tls"
        if [ -f "$LISTS_DIR/youtube.txt" ]; then
            filter="$filter --hostlist=$LISTS_DIR/youtube.txt"
        fi
        strat_opts=$(get_strategy_options "$STRATEGY_YOUTUBE" "$filter")
        if [ -n "$strat_opts" ]; then
            if [ $first -eq 0 ]; then
                OPTS="$OPTS --new"
            fi
            OPTS="$OPTS $strat_opts"
            first=0
            log_msg "Added YouTube strategy: $STRATEGY_YOUTUBE"
        fi
    fi

    # Discord TLS
    if [ -n "$STRATEGY_DISCORD" ] && [ "$STRATEGY_DISCORD" != "none" ]; then
        filter="--filter-tcp=443 --filter-l7=tls"
        if [ -f "$LISTS_DIR/discord.txt" ]; then
            filter="$filter --hostlist=$LISTS_DIR/discord.txt"
        fi
        strat_opts=$(get_strategy_options "$STRATEGY_DISCORD" "$filter")
        if [ -n "$strat_opts" ]; then
            if [ $first -eq 0 ]; then
                OPTS="$OPTS --new"
            fi
            OPTS="$OPTS $strat_opts"
            first=0
            log_msg "Added Discord strategy: $STRATEGY_DISCORD"
        fi

        # Discord Voice UDP
        if [ "$STRATEGY_DISCORD" = "fake_x6_stun_discord" ] || [ -n "$STRATEGY_DISCORD_VOICE" ]; then
            OPTS="$OPTS --new --filter-udp=19294-50100 --payload=stun --lua-desync=fake:blob=fake_stun:repeats=6"
            log_msg "Added Discord Voice UDP"
        fi
    fi

    # Telegram TLS
    if [ -n "$STRATEGY_TELEGRAM" ] && [ "$STRATEGY_TELEGRAM" != "none" ]; then
        filter="--filter-tcp=443 --filter-l7=tls"
        if [ -f "$LISTS_DIR/telegram.txt" ]; then
            filter="$filter --hostlist=$LISTS_DIR/telegram.txt"
        fi
        strat_opts=$(get_strategy_options "$STRATEGY_TELEGRAM" "$filter")
        if [ -n "$strat_opts" ]; then
            if [ $first -eq 0 ]; then
                OPTS="$OPTS --new"
            fi
            OPTS="$OPTS $strat_opts"
            first=0
            log_msg "Added Telegram strategy: $STRATEGY_TELEGRAM"
        fi
    fi

    # Other sites TLS
    if [ -n "$STRATEGY_OTHER" ] && [ "$STRATEGY_OTHER" != "none" ]; then
        filter="--filter-tcp=443 --filter-l7=tls"
        if [ -f "$LISTS_DIR/other.txt" ]; then
            filter="$filter --hostlist=$LISTS_DIR/other.txt"
        fi
        strat_opts=$(get_strategy_options "$STRATEGY_OTHER" "$filter")
        if [ -n "$strat_opts" ]; then
            if [ $first -eq 0 ]; then
                OPTS="$OPTS --new"
            fi
            OPTS="$OPTS $strat_opts"
            first=0
            log_msg "Added Other strategy: $STRATEGY_OTHER"
        fi
    fi

    # UDP/QUIC
    if [ -n "$STRATEGY_UDP" ] && [ "$STRATEGY_UDP" != "none" ]; then
        filter="--filter-udp=443 --filter-l7=quic"
        strat_opts=$(get_strategy_options "$STRATEGY_UDP" "$filter")
        if [ -n "$strat_opts" ]; then
            if [ $first -eq 0 ]; then
                OPTS="$OPTS --new"
            fi
            OPTS="$OPTS $strat_opts"
            first=0
            log_msg "Added UDP strategy: $STRATEGY_UDP"
        fi
    fi

    # If nothing configured, use default
    if [ $first -eq 1 ]; then
        log_msg "No categories configured, using default YouTube strategy"
        OPTS="$OPTS \
--filter-tcp=443 --filter-l7=tls \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
    fi

    echo "$OPTS"
}

##########################################################################################
# Start nfqws2
##########################################################################################

start_nfqws2() {
    log_msg "Starting nfqws2..."

    OPTS=$(build_options)
    log_msg "Options: $OPTS"
    log_msg "Full command: $NFQWS2 $OPTS"

    # Try to run nfqws2 and capture any immediate errors
    $NFQWS2 $OPTS 2>>/data/local/tmp/nfqws2-error.log &
    PID=$!

    sleep 2

    if [ -d "/proc/$PID" ]; then
        echo $PID > "$PIDFILE"
        log_msg "nfqws2 started successfully (PID: $PID)"
        echo "Zapret2 started (PID: $PID)"
        echo "Strategy: $STRATEGY_PRESET"
    else
        log_error "nfqws2 failed to start (PID $PID exited)"
        # Check for error output
        if [ -f /data/local/tmp/nfqws2-error.log ]; then
            ERRMSG=$(tail -5 /data/local/tmp/nfqws2-error.log)
            log_error "Error output: $ERRMSG"
        fi
        echo "ERROR: Failed to start nfqws2"
        echo "Check logs: $LOGFILE"
        echo "Error log: /data/local/tmp/nfqws2-error.log"
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
