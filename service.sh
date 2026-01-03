#!/system/bin/sh
##########################################################################################
# Zapret2 Magisk Module - Service Script (runs at boot)
##########################################################################################

MODDIR="${0%/*}"
LOGFILE="/data/local/tmp/zapret2.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $1" >> "$LOGFILE"
    log -t "Zapret2" "$1" 2>/dev/null
}

log "=== Zapret2 service starting ==="

# Wait for boot to complete
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done

log "Boot completed, waiting for network..."

# Wait for network
sleep 15

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
