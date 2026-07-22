#!/system/bin/sh
# Magisk Action: two-press confirmation for the canonical irreversible purge.

MODDIR="${0%/*}"
PURGE_SCRIPT="$MODDIR/zapret2/scripts/lifecycle/zapret-purge.sh"

case "$MODDIR" in /data/adb/modules/zapret2) ;; *) echo "ERROR: unexpected module path" >&2; exit 1 ;; esac
[ -f "$PURGE_SCRIPT" ] && [ ! -L "$PURGE_SCRIPT" ] && [ -x "$PURGE_SCRIPT" ] || {
    echo "ERROR: canonical Zapret2 purge engine is unavailable" >&2
    exit 1
}

exec /system/bin/sh "$PURGE_SCRIPT" --magisk-action
