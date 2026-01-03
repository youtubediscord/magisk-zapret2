#!/system/bin/sh
##########################################################################################
# Zapret2 Magisk Module - Uninstall Script
##########################################################################################

MODPATH="${MODPATH:-/data/adb/modules/zapret2}"

# Stop the daemon
if [ -f "$MODPATH/zapret2/scripts/zapret-stop.sh" ]; then
    sh "$MODPATH/zapret2/scripts/zapret-stop.sh"
fi

# Clean up files
rm -f /data/local/tmp/zapret2.log
rm -f /data/local/tmp/nfqws2.pid
rm -f /data/local/tmp/nfqws2-debug.log

echo "Zapret2 uninstalled"
