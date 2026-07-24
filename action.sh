#!/system/bin/sh
# Root-manager Action: two-press confirmation for the irreversible purge.

MODDIR="${0%/*}"
PURGE_SCRIPT="$MODDIR/zapret2/scripts/lifecycle/zapret-purge.sh"
MODULE_PROP="$MODDIR/module.prop"

[ -d "$MODDIR" ] && [ ! -L "$MODDIR" ] &&
    [ -f "$MODULE_PROP" ] && [ ! -L "$MODULE_PROP" ] &&
    [ "$(grep -c '^id=' "$MODULE_PROP" 2>/dev/null)" = 1 ] &&
    grep -qx 'id=zapret2' "$MODULE_PROP" || {
    echo "ERROR: Zapret2 module identity is unavailable" >&2
    exit 1
}
[ -f "$PURGE_SCRIPT" ] && [ ! -L "$PURGE_SCRIPT" ] && [ -x "$PURGE_SCRIPT" ] || {
    echo "ERROR: Zapret2 purge engine is unavailable" >&2
    exit 1
}

exec /system/bin/sh "$PURGE_SCRIPT" --manager-action
