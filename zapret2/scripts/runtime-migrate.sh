#!/system/bin/sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"
. "$SCRIPT_DIR/common.sh"

RUNTIME_CONFIG_DEFAULT="$ZAPRET_DIR/runtime.ini"
MIGRATE_TMP=""
MIGRATE_PRESERVED=""
USE_BOOTSTRAP_INPUTS=1

cleanup_migration() {
    [ -z "$MIGRATE_TMP" ] || rm -f "$MIGRATE_TMP" 2>/dev/null
    [ -z "$MIGRATE_PRESERVED" ] || rm -f "$MIGRATE_PRESERVED" 2>/dev/null
}

write_runtime_ini() {
    local output_file="$1"
    {
        echo "# Zapret2 runtime configuration"
        if [ "$USE_BOOTSTRAP_INPUTS" = 1 ]; then
            echo "# Generated from validated bootstrap assignments; no input is executed."
        else
            echo "# Self-healed from built-in defaults; bootstrap files were not read."
        fi
        echo
        echo "[core]"
        echo "schema_version=1"
        echo "config_format=runtime-v1"
        if [ "$USE_BOOTSTRAP_INPUTS" = 1 ]; then
            echo "runtime_source=bootstrap-migration"
        else
            echo "runtime_source=self-healed-defaults"
        fi
        printf 'autostart=%s\nwifi_only=%s\ndebug=%s\nqnum=%s\n' "$AUTOSTART" "$WIFI_ONLY" "$DEBUG" "$QNUM"
        printf 'desync_mark=%s\nports_tcp=%s\nports_udp=%s\npkt_out=%s\npkt_in=%s\n' "$DESYNC_MARK" "$PORTS_TCP" "$PORTS_UDP" "$PKT_OUT" "$PKT_IN"
        printf 'strategy_preset=%s\npreset_mode=%s\npreset_file=%s\ncustom_cmdline_file=%s\n' "$STRATEGY_PRESET" "$PRESET_MODE" "$PRESET_FILE" "$CUSTOM_CMDLINE_FILE"
        printf 'nfqws_uid=%s\nlog_mode=%s\n' "$NFQWS_UID" "$LOG_MODE"
        if [ -n "$MIGRATE_PRESERVED" ] && [ -s "$MIGRATE_PRESERVED" ]; then
            echo
            cat "$MIGRATE_PRESERVED"
        fi
    } > "$output_file"
}

main() {
    local output_path="$RUNTIME_CONFIG_DEFAULT" output_dir

    while [ "$#" -gt 0 ]; do
        case "$1" in
            --config)
                [ "$#" -ge 2 ] || { echo "ERROR: --config requires a path" >&2; exit 2; }
                CONFIG="$2"; shift 2
                ;;
            --user-config)
                [ "$#" -ge 2 ] || { echo "ERROR: --user-config requires a path" >&2; exit 2; }
                USER_CONFIG="$2"; shift 2
                ;;
            --categories)
                [ "$#" -ge 2 ] || { echo "ERROR: --categories requires a path" >&2; exit 2; }
                CATEGORIES_FILE="$2"; shift 2
                ;;
            --defaults-only)
                USE_BOOTSTRAP_INPUTS=0; shift
                ;;
            --)
                shift
                [ "$#" -le 1 ] || { echo "ERROR: too many output paths" >&2; exit 2; }
                [ "$#" -eq 0 ] || output_path="$1"
                break
                ;;
            -*) echo "ERROR: unknown option: $1" >&2; exit 2 ;;
            *)
                output_path="$1"; shift
                [ "$#" -eq 0 ] || { echo "ERROR: too many output paths" >&2; exit 2; }
                ;;
        esac
    done

    output_dir="$(dirname "$output_path")"
    if [ ! -d "$output_dir" ] || [ -L "$output_dir" ]; then
        echo "ERROR: Output directory is missing or unsafe: $output_dir" >&2
        exit 1
    fi
    if [ -e "$output_path" ] || [ -L "$output_path" ]; then
        [ -f "$output_path" ] && [ ! -L "$output_path" ] &&
            path_uid_is_root "$output_path" && path_nlink_is_one "$output_path" &&
            runtime_config_mode_is_safe "$output_path" || {
            echo "ERROR: Refusing unsafe runtime output target: $output_path" >&2
            exit 1
        }
    fi

    set_core_config_defaults
    if [ "$USE_BOOTSTRAP_INPUTS" = 1 ]; then
        [ ! -e "$CONFIG" ] || parse_bootstrap_config_file "$CONFIG" 0 || {
            echo "ERROR: Unsafe or invalid bootstrap config: $CONFIG" >&2
            exit 1
        }
        if [ -e "$USER_CONFIG" ] || [ -L "$USER_CONFIG" ]; then
            parse_bootstrap_config_file "$USER_CONFIG" 1 || {
                echo "ERROR: User config must be root-owned, mode 0600, non-symlink, and contain only allowed assignments: $USER_CONFIG" >&2
                exit 1
            }
        fi
    elif [ -f "$output_path" ] && [ ! -L "$output_path" ]; then
        RUNTIME_CONFIG="$output_path"
        runtime_config_exists || {
            echo "ERROR: Existing runtime input is unsafe or exceeds the size limit" >&2
            exit 1
        }
        if ! apply_runtime_core_overrides && [ "$RUNTIME_CORE_REPAIR_MODE" != merged ]; then
            set_core_config_defaults
        fi
    fi
    normalize_unsupported_wifi_only || {
        echo "ERROR: WIFI_ONLY must be 0 or the legacy value 1" >&2
        exit 1
    }
    normalize_qnum "$QNUM" || { echo "ERROR: qnum must be decimal 1..65535" >&2; exit 1; }
    QNUM="$QNUM_NORMALIZED"

    umask 077
    MIGRATE_TMP="$(mktemp "$output_dir/.runtime.ini.XXXXXX" 2>/dev/null)" || {
        echo "ERROR: Cannot create private runtime config temporary file" >&2
        exit 1
    }
    trap cleanup_migration EXIT HUP INT TERM
    if [ "$USE_BOOTSTRAP_INPUTS" = 0 ] && [ -f "$output_path" ] && [ ! -L "$output_path" ]; then
        MIGRATE_PRESERVED="$(mktemp "$output_dir/.runtime.sections.XXXXXX" 2>/dev/null)" || {
            echo "ERROR: Cannot create private runtime section temporary file" >&2
            exit 1
        }
        awk '
            BEGIN { in_core=0; keep=0 }
            /^[[:space:]]*\[[^]]+\][[:space:]]*$/ {
                section=$0
                gsub(/^[[:space:]]*\[/, "", section)
                gsub(/\][[:space:]]*$/, "", section)
                if (tolower(section) == "core") { in_core=1; keep=0; next }
                in_core=0; keep=1
            }
            keep && !in_core { print }
        ' "$output_path" > "$MIGRATE_PRESERVED" || {
            echo "ERROR: Cannot preserve non-core runtime sections" >&2
            exit 1
        }
        chmod 0600 "$MIGRATE_PRESERVED" 2>/dev/null || exit 1
    fi
    write_runtime_ini "$MIGRATE_TMP" || { echo "ERROR: Cannot write runtime config" >&2; exit 1; }
    chmod 0644 "$MIGRATE_TMP" 2>/dev/null || { echo "ERROR: Cannot set runtime config permissions" >&2; exit 1; }
    mv -f "$MIGRATE_TMP" "$output_path" || { echo "ERROR: Cannot atomically publish runtime config" >&2; exit 1; }
    MIGRATE_TMP=""
    [ -z "$MIGRATE_PRESERVED" ] || rm -f "$MIGRATE_PRESERVED" 2>/dev/null || exit 1
    MIGRATE_PRESERVED=""
    trap - EXIT HUP INT TERM

    echo "Generated runtime config: $output_path"
    if [ "$USE_BOOTSTRAP_INPUTS" = 1 ]; then
        [ ! -e "$USER_CONFIG" ] || echo "Merged validated user overrides from: $USER_CONFIG"
    else
        echo "Bootstrap inputs were intentionally ignored"
    fi
    [ ! -f "$CATEGORIES_FILE" ] || echo "Category state remains external at: $CATEGORIES_FILE"
}

main "$@"
