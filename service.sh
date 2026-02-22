#!/system/bin/sh
##########################################################################################
# Zapret2 Magisk Module - Service Script (runs at boot)
##########################################################################################

MODDIR="${0%/*}"
LOGFILE="/data/local/tmp/zapret2.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOGFILE"
    /system/bin/log -t "Zapret2" "$1" 2>/dev/null
}

log "=== Zapret2 service starting ==="

# Wait for boot to complete
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done

log "Boot completed, waiting for network..."

# Wait until network appears, up to 15 seconds
waited=0
while [ "$waited" -lt 15 ]; do
    if command -v ip >/dev/null 2>&1; then
        if [ -n "$(ip route show default 2>/dev/null)" ]; then
            log "Network is ready after ${waited}s"
            break
        fi
    elif [ -n "$(getprop net.dns1)" ]; then
        log "DNS property detected after ${waited}s"
        break
    fi

    sleep 1
    waited=$((waited + 1))
done

if [ "$waited" -ge 15 ]; then
    log "Network wait timeout reached, continuing startup"
fi

# Check if autostart is enabled
ZAPRET_DIR="$MODDIR/zapret2"
CONFIG="$ZAPRET_DIR/config.sh"

if [ -f "$CONFIG" ]; then
    . "$CONFIG"
fi

# Default autostart to enabled
AUTOSTART=${AUTOSTART:-1}

if [ "$AUTOSTART" = "1" ]; then
    log "Autostart enabled, launching zapret2..."
    "$ZAPRET_DIR/scripts/zapret-start.sh"
else
    log "Autostart disabled in config"
fi

log "=== Zapret2 service script finished ==="
