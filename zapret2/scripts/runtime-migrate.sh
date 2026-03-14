#!/system/bin/sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
CONFIG="$ZAPRET_DIR/config.sh"
USER_CONFIG="/data/local/tmp/zapret2-user.conf"
CATEGORIES_FILE="$ZAPRET_DIR/categories.ini"
RUNTIME_CONFIG_DEFAULT="$ZAPRET_DIR/runtime.ini"

set_default_config() {
    AUTOSTART=1
    WIFI_ONLY=0
    DEBUG=0
    QNUM=200
    DESYNC_MARK=0x40000000
    PORTS_TCP="53,80,443"
    PORTS_UDP="123,443,50000:65535"
    PKT_OUT=20
    PKT_IN=10
    STRATEGY_PRESET="youtube"
    PRESET_MODE="categories"
    PRESET_FILE="Default.txt"
    CUSTOM_CMDLINE_FILE="cmdline.txt"
    NFQWS_UID="0:0"
    LOG_MODE="none"
}

write_runtime_ini() {
    local output_file="$1"

    cat > "$output_file" <<EOF
# Zapret2 runtime configuration
# runtime.ini is the authoritative live source for [core] runtime values.
# config.sh and /data/local/tmp/zapret2-user.conf are bootstrap inputs only.
# categories.ini remains the active category-state source.

[core]
schema_version=1
config_format=runtime-v1
runtime_source=bootstrap-migration
autostart=${AUTOSTART:-1}
wifi_only=${WIFI_ONLY:-0}
debug=${DEBUG:-0}
qnum=${QNUM:-200}
desync_mark=${DESYNC_MARK:-0x40000000}
ports_tcp=${PORTS_TCP:-53,80,443}
ports_udp=${PORTS_UDP:-123,443,50000:65535}
pkt_out=${PKT_OUT:-20}
pkt_in=${PKT_IN:-10}
strategy_preset=${STRATEGY_PRESET:-youtube}
preset_mode=${PRESET_MODE:-categories}
preset_file=${PRESET_FILE:-Default.txt}
custom_cmdline_file=${CUSTOM_CMDLINE_FILE:-cmdline.txt}
nfqws_uid=${NFQWS_UID:-0:0}
log_mode=${LOG_MODE:-none}
bootstrap_config_path=$CONFIG
bootstrap_user_config_path=$USER_CONFIG
categories_state_path=$CATEGORIES_FILE
EOF
}

main() {
    local output_path="${1:-$RUNTIME_CONFIG_DEFAULT}"
    local output_dir="$(dirname "$output_path")"
    local tmp_path="${output_path}.tmp.$$"
    local backup_path="${output_path}.bak"

    if [ ! -d "$output_dir" ]; then
        echo "ERROR: Output directory does not exist: $output_dir" >&2
        exit 1
    fi

    set_default_config

    [ -f "$CONFIG" ] && . "$CONFIG"
    [ -f "$USER_CONFIG" ] && . "$USER_CONFIG"

    write_runtime_ini "$tmp_path"

    if [ -f "$output_path" ]; then
        cp "$output_path" "$backup_path" 2>/dev/null
    fi

    mv "$tmp_path" "$output_path"

    echo "Generated runtime config: $output_path"
    if [ -f "$backup_path" ]; then
        echo "Previous runtime config backup: $backup_path"
    fi
    if [ -f "$USER_CONFIG" ]; then
        echo "Merged user overrides from: $USER_CONFIG"
    fi
    if [ -f "$CATEGORIES_FILE" ]; then
        echo "Category state remains external at: $CATEGORIES_FILE"
    fi
}

main "$@"
