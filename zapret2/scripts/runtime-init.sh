#!/system/bin/sh
# Create the v1 preset runtime from built-in defaults. This is initialization,
# not migration: no previous configuration or bootstrap input is read.

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
output_path="${1:-$ZAPRET_DIR/runtime.ini}"
output_dir="$(dirname "$output_path")"

[ "$#" -le 1 ] || { echo "ERROR: usage: runtime-init.sh [runtime.ini]" >&2; exit 2; }
[ -d "$output_dir" ] && [ ! -L "$output_dir" ] || {
    echo "ERROR: runtime directory is missing or unsafe" >&2; exit 1;
}
case "$output_path" in "$output_dir/runtime.ini") ;; *) echo "ERROR: unsafe runtime path" >&2; exit 1 ;; esac
if [ -e "$output_path" ] || [ -L "$output_path" ]; then
    [ -f "$output_path" ] && [ ! -L "$output_path" ] || {
        echo "ERROR: unsafe runtime target" >&2; exit 1;
    }
fi

tmp="$output_path.tmp.$$"
[ ! -e "$tmp" ] && [ ! -L "$tmp" ] || exit 1
trap 'rm -f "$tmp" 2>/dev/null' EXIT HUP INT TERM
umask 077
{
    echo '# Zapret2 preset runtime'
    echo '[core]'
    echo 'schema_version=1'
    echo 'config_format=runtime-v1'
    echo 'runtime_source=builtin-defaults'
    echo 'autostart=1'
    echo 'wifi_only=0'
    echo 'debug=0'
    echo 'qnum=200'
    echo 'desync_mark=0x40000000'
    echo 'pkt_out=20'
    echo 'pkt_in=10'
    echo 'active_preset=Default v1 (game filter).txt'
    echo 'nfqws_uid=0:0'
    echo 'log_mode=none'
} > "$tmp" || exit 1
chmod 0644 "$tmp" 2>/dev/null || exit 1
mv -f "$tmp" "$output_path" || exit 1
tmp=
trap - EXIT HUP INT TERM
echo "Initialized runtime config: $output_path"
