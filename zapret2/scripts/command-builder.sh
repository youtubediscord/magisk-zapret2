#!/system/bin/sh
# Command builder for nfqws2
# Source: functions extracted from zapret-start.sh
# Requires: common.sh must be sourced first for normal runtime use.  The
# machine interfaces below bootstrap only the immutable paths they need:
#   --scan-presets-machine <zapret-dir>
#   --validate-preset-machine <zapret-dir> <candidate-path> <logical-name>
#   --validate-categories-machine <zapret-dir>
#   --validate-strategies-machine <zapret-dir>
#   --validate-cmdline-machine <zapret-dir> <file-name>
COMMAND_BUILDER_CLI_MODE=0
COMMAND_BUILDER_ERROR_PREFIX=Z2_PRESET_ERROR
STRATEGY_CATALOG_MAX_BYTES=1048576
CATEGORIES_MAX_BYTES=1048576
CUSTOM_CMDLINE_MAX_BYTES=262144
case "${1:-}" in
    --scan-presets-machine|--validate-preset-machine|--validate-categories-machine|--validate-strategies-machine|--validate-cmdline-machine)
        COMMAND_BUILDER_CLI_MODE=1
        case "$1" in
            --validate-categories-machine) COMMAND_BUILDER_ERROR_PREFIX=Z2_CATEGORIES_ERROR ;;
            --validate-strategies-machine) COMMAND_BUILDER_ERROR_PREFIX=Z2_STRATEGIES_ERROR ;;
            --validate-cmdline-machine) COMMAND_BUILDER_ERROR_PREFIX=Z2_CMDLINE_ERROR ;;
        esac
        ZAPRET_DIR="${2:-}"
        case "$ZAPRET_DIR" in
            /*) ;;
            *) printf '%s\tUNSAFE_ROOT\n' "$COMMAND_BUILDER_ERROR_PREFIX" >&2; exit 2 ;;
        esac
        case "$ZAPRET_DIR" in *'/../'*|*'/./'*|*/..|*/.)
            printf '%s\tUNSAFE_ROOT\n' "$COMMAND_BUILDER_ERROR_PREFIX" >&2
            exit 2
            ;;
        esac
        PRESETS_DIR="$ZAPRET_DIR/presets"
        LISTS_DIR="$ZAPRET_DIR/lists"
        if [ "$1" = --validate-categories-machine ] || [ "$1" = --validate-strategies-machine ] ||
           [ "$1" = --validate-cmdline-machine ]; then
            SCRIPT_DIR="$ZAPRET_DIR/scripts"
            MODDIR="$(dirname "$ZAPRET_DIR")"
            . "$SCRIPT_DIR/common.sh" || exit 2
            [ "$1" != --validate-categories-machine ] || set_core_config_defaults
        fi
        log_msg() { :; }
        log_error() { :; }
        log_debug() { :; }
        ;;
    *)
        [ -z "$ZAPRET_DIR" ] && { echo "ERROR: source common.sh first"; exit 1; }
        ;;
esac

##########################################################################################
# INI PARSERS
##########################################################################################

# Get strategy args from INI file
# Usage: get_strategy_args_from_ini <ini_file> <strategy_name>
# Returns: args value for the strategy, or empty if not found
get_strategy_args_from_ini() {
    local ini_file="$1"
    local strategy_name="$2"
    local current_section=""
    local line=""
    local key=""
    local args=""
    local cr size

    is_readable_file "$ini_file" || { log_error "INI file is missing or unsafe: $ini_file"; return 1; }
    size="$(wc -c < "$ini_file" 2>/dev/null)" || return 1
    case "$size" in ""|*[!0-9]*) return 1 ;; esac
    [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le "$STRATEGY_CATALOG_MAX_BYTES" ] 2>/dev/null || return 1

    [ -n "$strategy_name" ] || { log_error "Empty strategy name"; return 1; }

    log_debug "Looking for strategy [$strategy_name] in $ini_file"

    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        trim_config_value_in_place "$line"
        line="$CONFIG_VALUE_TRIMMED"

        case "$line" in
            ""|"#"*|";"*)
                continue
                ;;
            "["*"]")
                current_section="${line#[}"
                current_section="${current_section%]}"
                continue
                ;;
        esac

        if [ "$current_section" = "$strategy_name" ]; then
            case "$line" in
                *=*)
                    trim_config_value_in_place "${line%%=*}"
                    key="$CONFIG_VALUE_TRIMMED"
                    [ "$key" = args ] || continue
                    decode_config_value "${line#*=}" || {
                        log_error "Invalid quoted args in [$strategy_name] from $ini_file"
                        return 1
                    }
                    args="$CONFIG_VALUE_DECODED"
                    echo "$args"
                    log_debug "Found args for [$strategy_name]"
                    return
                    ;;
            esac
        fi
    done < "$ini_file"

    log_debug "No args found for [$strategy_name]"
    echo ""
}

validate_strategy_ini_file() {
    local ini_file="$1"
    local current_section=""
    local line=""
    local key=""
    local value=""
    local seen_sections="|"
    local seen_keys="|"
    local section_count=0
    local usable_count=0
    local args_count=0
    local cr
    local size

    is_readable_file "$ini_file" || return 1
    size="$(wc -c < "$ini_file" 2>/dev/null)" || return 1
    case "$size" in ""|*[!0-9]*) return 1 ;; esac
    [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le "$STRATEGY_CATALOG_MAX_BYTES" ] 2>/dev/null || return 1
    cr="$(printf '\r')"
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        trim_config_value_in_place "$line"
        line="$CONFIG_VALUE_TRIMMED"
        case "$line" in
            ""|"#"*|";"*) continue ;;
            "["*"]")
                if [ -n "$current_section" ]; then
                    [ "$args_count" -eq 1 ] || return 1
                    [ "$current_section" = disabled ] || usable_count=$((usable_count + 1))
                fi
                current_section="${line#[}"
                current_section="${current_section%]}"
                case "$current_section" in ""|*[!A-Za-z0-9_]*) return 1 ;; esac
                case "$seen_sections" in *"|$current_section|"*) return 1 ;; esac
                seen_sections="${seen_sections}${current_section}|"
                seen_keys="|"
                args_count=0
                section_count=$((section_count + 1))
                ;;
            *=*)
                [ -n "$current_section" ] || return 1
                trim_config_value_in_place "${line%%=*}"
                key="$CONFIG_VALUE_TRIMMED"
                case "$key" in ""|*[!A-Za-z0-9_-]*) return 1 ;; esac
                case "$seen_keys" in *"|$key|"*) return 1 ;; esac
                seen_keys="${seen_keys}${key}|"
                decode_config_value "${line#*=}" || return 1
                value="$CONFIG_VALUE_DECODED"
                if [ "$key" = args ]; then
                    if [ "$current_section" = disabled ]; then
                        [ -z "$value" ] || return 1
                    else
                        [ -n "$value" ] && [ "${#value}" -le 65536 ] || return 1
                    fi
                    args_count=1
                fi
                ;;
            *) return 1 ;;
        esac
    done < "$ini_file"
    [ "$section_count" -gt 0 ] && [ "$args_count" -eq 1 ] || return 1
    [ "$current_section" = disabled ] || usable_count=$((usable_count + 1))
    [ "$usable_count" -gt 0 ]
}

validate_strategy_catalogs_machine() {
    validate_strategy_ini_file "$ZAPRET_DIR/strategies-tcp.ini" || return 1
    validate_strategy_ini_file "$ZAPRET_DIR/strategies-udp.ini" || return 1
    validate_strategy_ini_file "$ZAPRET_DIR/strategies-stun.ini" || return 1
    return 0
}

# Get TCP strategy options
# Usage: get_tcp_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args
get_tcp_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local args=""
    args="$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "$strategy_name")" || return 1
    [ -n "$args" ] || {
        log_error "TCP strategy [$strategy_name] is missing or empty in $TCP_STRATEGIES_INI"
        return 1
    }
    echo "$filter $args"
}

# Get UDP strategy options
# Usage: get_udp_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args
get_udp_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local args=""
    args="$(get_strategy_args_from_ini "$UDP_STRATEGIES_INI" "$strategy_name")" || return 1
    [ -n "$args" ] || {
        log_error "UDP strategy [$strategy_name] is missing or empty in $UDP_STRATEGIES_INI"
        return 1
    }
    echo "$filter $args"
}

# Get STUN strategy options
# Usage: get_stun_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args (STUN args include --payload)
get_stun_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local args=""
    args="$(get_strategy_args_from_ini "$STUN_STRATEGIES_INI" "$strategy_name")" || return 1
    [ -n "$args" ] || {
        log_error "STUN strategy [$strategy_name] is missing or empty in $STUN_STRATEGIES_INI"
        return 1
    }
    # STUN args already include --payload and --out-range, just add filter if any.
    if [ -n "$filter" ]; then
        echo "$filter $args"
    else
        echo "$args"
    fi
}

##########################################################################################
# LUA INITIALIZATION
##########################################################################################

# Build Lua init options
build_lua_opts() {
    local lua_opts=""

    # Load Lua files in correct order: lib -> antidpi -> auto
    if [ -f "$ZAPRET_DIR/lua/zapret-lib.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-lib.lua"
        log_debug "Added Lua: zapret-lib.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-antidpi.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-antidpi.lua"
        log_debug "Added Lua: zapret-antidpi.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-auto.lua"
        log_debug "Added Lua: zapret-auto.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/custom_funcs.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/custom_funcs.lua"
        log_debug "Added Lua: custom_funcs.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-multishake.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/zapret-multishake.lua"
        log_debug "Added Lua: zapret-multishake.lua"
    fi

    echo "$lua_opts"
}

##########################################################################################
# BLOB LOADING
##########################################################################################

# Build blob options from blobs.txt
build_blob_opts() {
    local blob_opts=""

    if [ ! -f "$BLOBS_FILE" ]; then
        log_msg "No blobs.txt found, using built-in blobs only"
        echo ""
        return
    fi

    log_msg "Loading blobs from $BLOBS_FILE"

    while IFS= read -r line || [ -n "$line" ]; do
        # Skip empty lines and comments
        case "$line" in
            ""|\#*) continue ;;
        esac

        # Replace @bin/ with full path
        local blob_opt=$(echo "$line" | sed "s|@bin/|@$ZAPRET_DIR/bin/|g")
        blob_opts="$blob_opts $blob_opt"
    done < "$BLOBS_FILE"

    echo "$blob_opts"
}

##########################################################################################
# DEBUG OPTIONS
##########################################################################################

# Build debug/logging options
build_debug_opts() {
    local debug_opts=""

    case "$LOG_MODE" in
        android)
            debug_opts="--debug=android"
            log_msg "Debug output: android logcat"
            ;;
        file)
            debug_opts="--debug=@$DEBUG_LOG"
            log_msg "Debug output: file ($DEBUG_LOG)"
            ;;
        syslog)
            debug_opts="--debug=syslog"
            log_msg "Debug output: syslog"
            ;;
        none|*)
            # No debug output (default for production)
            log_msg "Debug output: disabled"
            ;;
    esac

    echo "$debug_opts"
}

##########################################################################################
# PRESET FILE BUILDER
##########################################################################################

# Returns 0 when PRESET_MODE requests file-based preset loading.
is_preset_file_mode() {
    case "${PRESET_MODE:-categories}" in
        file|preset|txt)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

is_cmdline_mode() {
    [ "${PRESET_MODE:-categories}" = cmdline ]
}

# Stable result exported by every preset validation call.
PRESET_VALIDATION_CODE="OK"
PRESET_VALIDATION_DETAIL=""

preset_validation_fail() {
    PRESET_VALIDATION_CODE="$1"
    PRESET_VALIDATION_DETAIL="${2:-}"
    return 1
}

command_builder_safe_file_name_byte_length() {
    local value="$1"
    local LC_ALL=C
    [ "${#value}" -le 255 ] 2>/dev/null
}

is_safe_preset_file_name() {
    local name="$1"
    [ -n "$name" ] && command_builder_safe_file_name_byte_length "$name" || return 1
    [ "$name" != "." ] && [ "$name" != ".." ] || return 1
    [ "${name# }" = "$name" ] && [ "${name% }" = "$name" ] || return 1
    case "$name" in
        _*|*/*|*\\*|*"'"*|*'"'*|*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT) return 1 ;;
        *.txt) ;;
        *) return 1 ;;
    esac
    if LC_ALL=C printf '%s' "$name" | grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

is_safe_dependency_name() {
    local name="$1"
    [ -n "$name" ] && command_builder_safe_file_name_byte_length "$name" || return 1
    [ "$name" != "." ] && [ "$name" != ".." ] || return 1
    [ "${name# }" = "$name" ] && [ "${name% }" = "$name" ] || return 1
    case "$name" in */*|*\\*|*"'"*|*'"'*) return 1 ;; esac
    if LC_ALL=C printf '%s' "$name" | grep -q '[[:cntrl:]]'; then return 1; fi
    return 0
}

validate_preset_dependency() {
    local dependency_class="$1"
    local raw="$2"
    local relative=""
    local base=""

    case "$dependency_class:$raw" in
        lua:@lua/*)
            relative="${raw#@lua/}"
            base="$ZAPRET_DIR/lua"
            ;;
        blob:@bin/*)
            relative="${raw#@bin/}"
            base="$ZAPRET_DIR/bin"
            ;;
        list:lists/*)
            relative="${raw#lists/}"
            base="$LISTS_DIR"
            ;;
        *)
            preset_validation_fail "UNSAFE_DEPENDENCY_PATH" "$raw"
            return 1
            ;;
    esac

    if ! is_safe_dependency_name "$relative"; then
        preset_validation_fail "UNSAFE_DEPENDENCY_PATH" "$raw"
        return 1
    fi

    PRESET_DEPENDENCY_PATH="$base/$relative"
    PRESET_DEPENDENCY_RELATIVE="zapret2/${base##*/}/$relative"
    if [ -n "${PRESET_ALLOWED_DEPENDENCIES_FILE:-}" ]; then
        if [ ! -f "$PRESET_ALLOWED_DEPENDENCIES_FILE" ] ||
           ! grep -Fqx "$PRESET_DEPENDENCY_RELATIVE" "$PRESET_ALLOWED_DEPENDENCIES_FILE"; then
            preset_validation_fail "DEPENDENCY_NOT_DECLARED" "$raw"
            return 1
        fi
    fi
    if [ -L "$PRESET_DEPENDENCY_PATH" ]; then
        preset_validation_fail "DEPENDENCY_SYMLINK" "$raw"
        return 1
    fi
    if [ ! -f "$PRESET_DEPENDENCY_PATH" ]; then
        preset_validation_fail "DEPENDENCY_MISSING" "$raw"
        return 1
    fi
    if [ ! -s "$PRESET_DEPENDENCY_PATH" ]; then
        preset_validation_fail "DEPENDENCY_EMPTY" "$raw"
        return 1
    fi
    if [ ! -r "$PRESET_DEPENDENCY_PATH" ]; then
        preset_validation_fail "DEPENDENCY_UNREADABLE" "$raw"
        return 1
    fi
    return 0
}

# Resolve PRESET_FILE to a direct child of the packaged preset catalog.
resolve_preset_file_path() {
    local preset_name="$1"
    is_safe_preset_file_name "$preset_name" || return 1
    echo "$PRESETS_DIR/$preset_name"
}

# Returns 0 only for a root-owned, single-link readable regular file with the
# same 0600/0644 mode contract enforced by the Android protected-file reader.
is_readable_file() {
    local file_path="$1"
    [ -f "$file_path" ] && [ ! -L "$file_path" ] && [ -r "$file_path" ] &&
        case "$(stat -c '%u:%a:%h' "$file_path" 2>/dev/null)" in
            0:600:1|0:644:1) true ;;
            *) false ;;
        esac
}

# Validate one direct-child Windows-style preset TXT file and optionally append
# its normalized Android options.  Dependencies are confined to direct regular
# files below the release-owned lua/bin/lists roots.
# Modifies: PRESET_VALIDATION_*, PRESET_HAS_LUA, PRESET_HAS_BLOB, and OPTS when
# append_options=1.
validate_preset_file() {
    local preset_file="$1"
    local logical_name="$2"
    local append_options="${3:-0}"
    local candidate_name=""
    local line=""
    local resolved=""
    local raw=""
    local option=""
    local cr
    local kept=0
    local skipped=0

    PRESET_VALIDATION_CODE="OK"
    PRESET_VALIDATION_DETAIL=""
    PRESET_HAS_LUA=0
    PRESET_HAS_BLOB=0

    if ! is_safe_preset_file_name "$logical_name"; then
        preset_validation_fail "UNSAFE_PRESET_NAME" "$logical_name"
        return 1
    fi
    case "$preset_file" in
        "$PRESETS_DIR"/*) candidate_name="${preset_file#"$PRESETS_DIR"/}" ;;
        *) preset_validation_fail "PRESET_NOT_DIRECT_CHILD" "$preset_file"; return 1 ;;
    esac
    case "$candidate_name" in ""|*/*|*\\*) preset_validation_fail "PRESET_NOT_DIRECT_CHILD" "$preset_file"; return 1 ;; esac
    if [ -L "$preset_file" ]; then
        preset_validation_fail "PRESET_SYMLINK" "$logical_name"
        return 1
    fi
    if [ ! -f "$preset_file" ]; then
        preset_validation_fail "PRESET_MISSING" "$logical_name"
        return 1
    fi
    if [ ! -s "$preset_file" ]; then
        preset_validation_fail "PRESET_EMPTY" "$logical_name"
        return 1
    fi
    if [ ! -r "$preset_file" ]; then
        preset_validation_fail "PRESET_UNREADABLE" "$logical_name"
        return 1
    fi

    log_msg "Loading preset file: $preset_file"
    cr=$(printf '\r')

    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"

        # Trim leading spaces/tabs
        while [ "${line# }" != "$line" ]; do
            line="${line# }"
        done
        while [ "${line#	}" != "$line" ]; do
            line="${line#	}"
        done

        [ -z "$line" ] && continue
        case "$line" in
            "#"*|";"*)
                continue
                ;;
        esac

        # Skip Windows-only options
        case "$line" in
            --wf-*|*windivert*)
                skipped=$((skipped + 1))
                continue
                ;;
        esac

        # Accept only options known to work in Android nfqws2 mode
        case "$line" in
            --new|--lua-init=*|--blob=*|--ctrack-disable=*|--ipcache-lifetime=*|--ipcache-hostname|--ipcache-hostname=*|--filter-l3=*|--filter-tcp=*|--filter-udp=*|--filter-l7=*|--hostlist=*|--hostlist-domains=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*|--out-range=*|--payload=*|--lua-desync=*)
                ;;
            *)
                log_debug "Skipping unsupported preset option: $line"
                skipped=$((skipped + 1))
                continue
                ;;
        esac

        # Validate and normalize path-based options.
        case "$line" in
            --lua-init=*)
                raw="${line#--lua-init=}"
                validate_preset_dependency lua "$raw" || return 1
                line="--lua-init=@$PRESET_DEPENDENCY_PATH"
                PRESET_HAS_LUA=1
                ;;
            --blob=*:@*)
                raw="${line##*:}"
                validate_preset_dependency blob "$raw" || return 1
                line="${line%:*}:@$PRESET_DEPENDENCY_PATH"
                PRESET_HAS_BLOB=1
                ;;
            --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
                option="${line%%=*}"
                raw="${line#*=}"
                validate_preset_dependency list "$raw" || return 1
                line="$option=$PRESET_DEPENDENCY_PATH"
                ;;
            --blob=*)
                PRESET_HAS_BLOB=1
                ;;
        esac

        # Avoid duplicate separators
        if [ "$line" = "--new" ]; then
            case "$OPTS" in
                *" --new")
                    skipped=$((skipped + 1))
                    continue
                    ;;
            esac
        fi

        if [ "$append_options" -eq 1 ]; then
            OPTS="$OPTS $line"
        fi
        kept=$((kept + 1))
    done < "$preset_file"

    log_msg "Preset options loaded: kept=$kept skipped=$skipped"

    if [ "$kept" -eq 0 ]; then
        preset_validation_fail "NO_VALID_OPTIONS" "$logical_name"
        return 1
    fi

    return 0
}

# Build options from the selected packaged preset using the same validator used
# by catalog scans and pre-save candidate validation.
build_preset_file_options() {
    local preset_name="${PRESET_FILE:-Default.txt}"
    local preset_file=""

    preset_file="$(resolve_preset_file_path "$preset_name")" || {
        PRESET_VALIDATION_CODE="UNSAFE_PRESET_NAME"
        PRESET_VALIDATION_DETAIL="$preset_name"
        log_error "Unsafe preset name: $preset_name"
        return 1
    }
    if ! validate_preset_file "$preset_file" "$preset_name" 1; then
        log_error "Preset validation failed ($PRESET_VALIDATION_CODE): $PRESET_VALIDATION_DETAIL"
        return 1
    fi
    return 0
}

scan_presets_machine() {
    local preset_file=""
    local preset_name=""
    local valid=0
    local quarantined=0
    local total=0

    [ -d "$PRESETS_DIR" ] && [ ! -L "$PRESETS_DIR" ] || {
        printf 'Z2_PRESET_ERROR\tPRESET_CATALOG_MISSING\n'
        return 2
    }

    for preset_file in "$PRESETS_DIR"/*.txt; do
        [ -e "$preset_file" ] || [ -L "$preset_file" ] || continue
        preset_name="${preset_file##*/}"
        case "$preset_name" in _*) continue ;; esac
        total=$((total + 1))
        if validate_preset_file "$preset_file" "$preset_name" 0; then
            valid=$((valid + 1))
            printf 'Z2_PRESET\tVALID\tOK\t%s\n' "$preset_name"
        else
            quarantined=$((quarantined + 1))
            printf 'Z2_PRESET\tQUARANTINED\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$preset_name"
        fi
    done
    printf 'Z2_PRESET_SUMMARY\t1\tvalid=%s\tquarantined=%s\ttotal=%s\n' "$valid" "$quarantined" "$total"
    return 0
}

validate_preset_machine() {
    local candidate_path="$1"
    local logical_name="$2"
    if validate_preset_file "$candidate_path" "$logical_name" 0; then
        printf 'Z2_PRESET_VALIDATION\t1\tOK\t%s\n' "$logical_name"
        return 0
    fi
    printf 'Z2_PRESET_VALIDATION\t0\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$logical_name"
    return 1
}

# Load a custom option file without eval/source. Each non-comment line is one
# supported nfqws2 argument. Whitespace and shell syntax are rejected; qnum,
# fwmark, uid and debug remain controlled by the validated core config.
build_validated_cmdline_options() {
    local name="${CUSTOM_CMDLINE_FILE:-cmdline.txt}" file line cr kept=0 resolved original_resolved
    local reference_root reference_child
    local file_option=0
    local before after before_sha after_sha device inode uid mode links size
    is_safe_cmdline_file_name "$name" || {
        log_error "Custom cmdline has an invalid file name: $name"
        return 1
    }
    file="$ZAPRET_DIR/$name"
    [ -f "$file" ] && [ ! -L "$file" ] && [ -r "$file" ] || {
        log_error "Custom cmdline is missing, unreadable, or not a regular single file: $file"
        return 1
    }
    before="$(stat -c '%d:%i:%u:%a:%h:%s' "$file" 2>/dev/null)" || {
        log_error "Custom cmdline metadata is unavailable: $file"
        return 1
    }
    IFS=: read -r device inode uid mode links size <<EOF
$before
EOF
    [ -n "$device" ] && [ -n "$inode" ] && [ "$uid" = 0 ] && [ "$links" = 1 ] || {
        log_error "Custom cmdline is not a root-owned single-link file: $file"
        return 1
    }
    case "$mode" in 600|644) ;; *)
        log_error "Custom cmdline has an unsafe mode: $file"
        return 1
        ;;
    esac
    case "$size" in ""|*[!0-9]*)
        log_error "Custom cmdline size is invalid: $file"
        return 1
        ;;
    esac
    [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le "$CUSTOM_CMDLINE_MAX_BYTES" ] 2>/dev/null || {
        log_error "Custom cmdline is empty or exceeds 256 KiB: $file"
        return 1
    }
    command -v sha256sum >/dev/null 2>&1 || {
        log_error "Custom cmdline integrity verifier is unavailable"
        return 1
    }
    before_sha="$(sha256sum "$file" 2>/dev/null | awk 'NR == 1 { print $1 }')" || {
        log_error "Custom cmdline integrity could not be measured: $file"
        return 1
    }
    is_lower_sha256 "$before_sha" || {
        log_error "Custom cmdline integrity result is invalid: $file"
        return 1
    }
    cr="$(printf '\r')"
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        line="$(trim_config_value "$line")"
        case "$line" in ""|"#"*|";"*) continue ;; esac
        case "$line" in *[!A-Za-z0-9_@%+=:,./-]*)
            log_error "Unsafe custom cmdline token rejected: $line"
            return 1
            ;;
        esac
        case "$line" in
            --new|--lua-init=@*|--blob=*|--ctrack-disable=*|--ipcache-lifetime=*|--ipcache-hostname|--ipcache-hostname=*|--filter-l3=*|--filter-tcp=*|--filter-udp=*|--filter-l7=*|--hostlist=*|--hostlist-domains=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*|--out-range=*|--payload=*|--lua-desync=*) ;;
            *) log_error "Unsupported custom cmdline option: $line"; return 1 ;;
        esac
        file_option=0
        reference_root=""
        case "$line" in
            --lua-init=@*)
                resolved="${line#--lua-init=@}"; file_option=1; reference_root="$ZAPRET_DIR/lua"
                ;;
            --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
                resolved="${line#*=}"; file_option=1; reference_root="$ZAPRET_DIR/lists"
                ;;
            --blob=*:@*)
                resolved="${line##*:@}"; file_option=1; reference_root="$ZAPRET_DIR/bin"
                ;;
            *) resolved="" ;;
        esac
        if [ "$file_option" -eq 1 ]; then
            [ -n "$resolved" ] || {
                log_error "Custom cmdline contains an empty file reference: $line"
                return 1
            }
            original_resolved="$resolved"
            case "$resolved" in
                .|..|./*|../*|*'/./'*|*'/../'*|*/.|*/..)
                    log_error "Custom cmdline contains a traversing file reference: $resolved"
                    return 1
                    ;;
                /*) ;;
                lua/*|bin/*|lists/*) resolved="$ZAPRET_DIR/$resolved" ;;
                *)
                    resolved="$reference_root/$resolved"
                    ;;
            esac
            case "$resolved" in
                "$reference_root"/*)
                    reference_child="${resolved#"$reference_root"/}"
                    case "$reference_child" in ""|*/*)
                        log_error "Custom cmdline file reference is not a direct approved child: $resolved"
                        return 1
                        ;;
                    esac
                    ;;
                *)
                    log_error "Custom cmdline file reference is outside its approved root: $resolved"
                    return 1
                    ;;
            esac
            case "$line" in
                --lua-init=@*) case "$reference_child" in *.lua) ;; *)
                    log_error "Custom cmdline Lua reference must be a direct .lua file: $resolved"
                    return 1
                    ;;
                esac ;;
                --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
                    case "$reference_child" in *.txt) ;; *)
                        log_error "Custom cmdline list reference must be a direct .txt file: $resolved"
                        return 1
                        ;;
                    esac
                    ;;
            esac
            line="${line%"$original_resolved"}$resolved"
            if ! is_readable_file "$resolved"; then
                log_error "Custom cmdline references an unsafe or unreadable file: $resolved"
                return 1
            fi
        fi
        OPTS="$OPTS $line"
        kept=$((kept + 1))
    done < "$file"
    after="$(stat -c '%d:%i:%u:%a:%h:%s' "$file" 2>/dev/null)" || {
        log_error "Custom cmdline metadata could not be revalidated: $file"
        return 1
    }
    [ "$after" = "$before" ] || {
        log_error "Custom cmdline changed while it was being validated: $file"
        return 1
    }
    after_sha="$(sha256sum "$file" 2>/dev/null | awk 'NR == 1 { print $1 }')" || {
        log_error "Custom cmdline integrity could not be revalidated: $file"
        return 1
    }
    [ "$after_sha" = "$before_sha" ] || {
        log_error "Custom cmdline content changed while it was being validated: $file"
        return 1
    }
    [ "$kept" -gt 0 ] || { log_error "Custom cmdline contains no valid options: $file"; return 1; }
    log_msg "Validated custom cmdline options: $kept"
}

##########################################################################################
# CATEGORY BUILDERS
##########################################################################################

build_category_filter() {
    local filter_mode="$1"
    local filter_file="$2"
    local filter_opts=""

    case "$filter_mode" in
        hostlist-domains)
            [ -n "$filter_file" ] || { log_error "hostlist-domains filter is empty"; return 1; }
            case "$filter_file" in *[!A-Za-z0-9.,_-]*|,*|*,|*,,*)
                log_error "hostlist-domains filter is invalid: $filter_file"
                return 1
                ;;
            esac
            filter_opts="--hostlist-domains=$filter_file"
            log_debug "Using hostlist-domains: $filter_file"
            ;;
        hostlist)
            is_safe_category_txt_name "$filter_file" || { log_error "Hostlist file name is invalid: $filter_file"; return 1; }
            is_readable_file "$LISTS_DIR/$filter_file" || { log_error "Hostlist file is missing or unsafe: $LISTS_DIR/$filter_file"; return 1; }
            filter_opts="--hostlist=$LISTS_DIR/$filter_file"
            log_debug "Using hostlist: $filter_file"
            ;;
        ipset)
            is_safe_category_txt_name "$filter_file" || { log_error "Ipset file name is invalid: $filter_file"; return 1; }
            is_readable_file "$LISTS_DIR/$filter_file" || { log_error "Ipset file is missing or unsafe: $LISTS_DIR/$filter_file"; return 1; }
            filter_opts="--ipset=$LISTS_DIR/$filter_file"
            log_debug "Using ipset: $filter_file"
            ;;
        none)
            [ -z "$filter_file" ] || { log_error "filter_mode=none has unexpected filter data"; return 1; }
            log_debug "No domain/IP filtering for this category"
            ;;
        *) log_error "Unsupported category filter mode: $filter_mode"; return 1 ;;
    esac

    echo "$filter_opts"
}

# Build options for a single category
# Arguments: category, protocol, filter_mode, filter_value, strategy_name
# Modifies global: OPTS, first
build_category_options_single() {
    local category="$1"
    local protocol="$2"
    local filter_mode="$3"
    local filter_value="$4"
    local strategy_name="$5"

    if [ -z "$strategy_name" ]; then
        log_error "Empty strategy_name for enabled category: $category"
        return 1
    fi
    case "$strategy_name" in *[!A-Za-z0-9_]*) log_error "Invalid strategy name for category $category: $strategy_name"; return 1 ;; esac

    # Determine protocol filter based on PROTOCOL field (not category name!)
    local proto_filter=""
    local strat_opts=""
    case "$protocol" in
        stun)
            # STUN/Voice - L7 detection only, no port filter
            # nfqws2 identifies STUN by --payload= in strategy args, not by port
            local full_filter=""

            # Add filtering options if specified (usually none for STUN)
            local filter_opts=""
            filter_opts="$(build_category_filter "$filter_mode" "$filter_value")" || return 1
            if [ -n "$filter_opts" ]; then
                full_filter="$filter_opts"
            fi

            # Get STUN strategy options from strategies-stun.ini
            strat_opts="$(get_stun_strategy_options "$strategy_name" "$full_filter")" || return 1
            ;;
        udp)
            # UDP/QUIC - use get_udp_strategy_options
            proto_filter="--filter-udp=53,123,443,1400,50000-65535"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add filtering options if specified
            local filter_opts=""
            filter_opts="$(build_category_filter "$filter_mode" "$filter_value")" || return 1
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get UDP strategy options
            strat_opts="$(get_udp_strategy_options "$strategy_name" "$full_filter")" || return 1
            ;;
        tcp)
            # TCP - use get_tcp_strategy_options
            proto_filter="--filter-tcp=53,80,443,5222"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add filtering options if specified
            local filter_opts=""
            filter_opts="$(build_category_filter "$filter_mode" "$filter_value")" || return 1
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get TCP strategy options
            strat_opts="$(get_tcp_strategy_options "$strategy_name" "$full_filter")" || return 1
            ;;
        *) log_error "Unsupported protocol for category $category: $protocol"; return 1 ;;
    esac

    if [ -z "$strat_opts" ]; then
        log_error "No options for category $category with strategy $strategy_name"
        return 1
    fi

    # Add --new separator if not first strategy
    if [ $first -eq 0 ]; then
        OPTS="$OPTS --new"
    fi

    OPTS="$OPTS $strat_opts"
    first=0
    log_msg "Added category: $category (proto=$protocol, strategy=$strategy_name, filter=$filter_mode)"
}

##########################################################################################
# CATEGORIES PARSER
##########################################################################################

# Build options for a parsed category section.
is_safe_category_txt_name() {
    local normalized
    is_safe_runtime_file_name "$1" || return 1
    normalized="$(LC_ALL=C printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" || return 1
    case "$normalized" in *.txt) return 0 ;; *) return 1 ;; esac
}

process_category_section() {
    local current_section="$1"
    local protocol="$2"
    local hostlist="$3"
    local ipset="$4"
    local hostlist_domains="$5"
    local filter_mode="$6"
    local strategy="$7"
    local filter_file=""
    local effective_filter_mode="$filter_mode"

    [ -z "$current_section" ] && return 0
    case "$current_section" in *[!A-Za-z0-9_]*) log_error "Invalid category section name: $current_section"; return 1 ;; esac
    [ -n "$protocol" ] || { log_error "Category [$current_section] has no explicit protocol"; return 1; }
    [ -n "$strategy" ] || { log_error "Category [$current_section] has no explicit strategy"; return 1; }
    [ -n "$filter_mode" ] || { log_error "Category [$current_section] has no explicit filter_mode"; return 1; }
    case "$strategy" in *[!A-Za-z0-9_]*) log_error "Category [$current_section] has an invalid strategy name: $strategy"; return 1 ;; esac
    case "$protocol" in tcp|udp|stun) ;; *) log_error "Category [$current_section] has an unsupported protocol: $protocol"; return 1 ;; esac

    case "$effective_filter_mode" in
        hostlist-domains)
            filter_file="$hostlist_domains"
            ;;
        ipset)
            filter_file="$ipset"
            ;;
        hostlist)
            filter_file="$hostlist"
            ;;
        none)
            filter_file=""
            ;;
        *) log_error "Category [$current_section] has an unsupported filter mode: $effective_filter_mode"; return 1 ;;
    esac

    case "$effective_filter_mode" in
        hostlist)
            [ -n "$hostlist" ] || { log_error "Category [$current_section] has no active hostlist binding"; return 1; }
            ;;
        ipset)
            [ -n "$ipset" ] || { log_error "Category [$current_section] has no active ipset binding"; return 1; }
            ;;
        hostlist-domains)
            [ -n "$hostlist_domains" ] || { log_error "Category [$current_section] has no active hostlist-domains binding"; return 1; }
            ;;
        none) ;;
    esac

    if [ -n "$hostlist" ]; then
        is_safe_category_txt_name "$hostlist" || { log_error "Category [$current_section] has an invalid hostlist file name"; return 1; }
    fi
    if [ -n "$ipset" ]; then
        is_safe_category_txt_name "$ipset" || { log_error "Category [$current_section] has an invalid ipset file name"; return 1; }
    fi
    if [ -n "$hostlist_domains" ]; then
        case "$hostlist_domains" in *[!A-Za-z0-9.,_-]*|,*|*,|*,,*)
            log_error "Category [$current_section] has invalid hostlist domains"
            return 1
            ;;
        esac
    fi

    [ "$strategy" = disabled ] && return 0
    build_category_options_single "$current_section" "$protocol" "$effective_filter_mode" "$filter_file" "$strategy" || return 1
    return 0
}

# Parse categories.ini file and build options for each category
# Modifies global: OPTS, first
# New format supports: hostlist, ipset, hostlist-domains, filter_mode fields
parse_categories() {
    local ini_file="$CATEGORIES_FILE"
    local current_section=""
    local protocol=""
    local hostlist=""
    local ipset=""
    local hostlist_domains=""
    local filter_mode=""
    local strategy=""
    local line=""
    local key=""
    local semantic_key=""
    local value=""
    local seen_keys="|"
    local seen_sections="|"
    local cr

    if [ ! -f "$ini_file" ]; then
        log_error "Categories file not found: $ini_file"
        return 1
    fi

    log_msg "Parsing categories from: $ini_file"

    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        trim_config_value_in_place "$line"
        line="$CONFIG_VALUE_TRIMMED"

        case "$line" in
            ""|"#"*|";"*)
                continue
                ;;
            "["*"]")
                process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$hostlist_domains" "$filter_mode" "$strategy" || return 1

                current_section="${line#[}"
                current_section="${current_section%]}"
                case "$current_section" in ""|*[!A-Za-z0-9_]*) log_error "Invalid category section: $current_section"; return 1 ;; esac
                case "$seen_sections" in *"|$current_section|"*) log_error "Duplicate category section: $current_section"; return 1 ;; esac
                seen_sections="${seen_sections}${current_section}|"
                seen_keys="|"
                protocol=""
                hostlist=""
                ipset=""
                hostlist_domains=""
                filter_mode=""
                strategy=""
                log_debug "Found section: $current_section"
                continue
                ;;
            *=*)
                trim_config_value_in_place "${line%%=*}"
                key="$CONFIG_VALUE_TRIMMED"
                decode_config_value "${line#*=}" || {
                    log_error "Invalid quoted value in [$current_section] from $ini_file"
                    return 1
                }
                value="$CONFIG_VALUE_DECODED"

                [ -n "$current_section" ] || { log_error "Category key appears before the first section: $key"; return 1; }
                case "$key" in
                    protocol|hostlist|ipset|filter_mode|strategy) semantic_key="$key" ;;
                    hostlist-domains|hostlist_domains) semantic_key="hostlist-domains" ;;
                    *) log_error "Unknown category key [$key] in [$current_section]"; return 1 ;;
                esac
                case "$seen_keys" in *"|$semantic_key|"*) log_error "Duplicate category key [$semantic_key] in [$current_section]"; return 1 ;; esac
                seen_keys="${seen_keys}${semantic_key}|"

                case "$semantic_key" in
                    protocol)
                        protocol="$value"
                        ;;
                    hostlist)
                        hostlist="$value"
                        ;;
                    ipset)
                        ipset="$value"
                        ;;
                    hostlist-domains)
                        hostlist_domains="$value"
                        ;;
                    filter_mode)
                        filter_mode="$value"
                        ;;
                    strategy)
                        strategy="$value"
                        ;;
                esac
                ;;
            *)
                log_error "Malformed categories.ini line in [$current_section]: $line"
                return 1
                ;;
        esac
    done < "$ini_file"

    process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$hostlist_domains" "$filter_mode" "$strategy" || return 1
    return 0
}

##########################################################################################
# MAIN CATEGORY BUILDER
##########################################################################################

build_category_options() {
    local size
    first=1

    if ! is_readable_file "$CATEGORIES_FILE"; then
        log_error "Categories file not found: $CATEGORIES_FILE"
        return 1
    fi
    size="$(wc -c < "$CATEGORIES_FILE" 2>/dev/null)" || return 1
    case "$size" in ""|*[!0-9]*) return 1 ;; esac
    [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le "$CATEGORIES_MAX_BYTES" ] 2>/dev/null || {
        log_error "Categories file size is outside the supported range"
        return 1
    }

    log_msg "Building category-based options from categories.ini..."
    parse_categories || return 1

    if [ $first -eq 0 ]; then
        log_msg "Categories loaded successfully"
    else
        log_error "No enabled categories are configured; refusing an implicit global fallback"
        return 1
    fi
    return 0
}

##########################################################################################
# MAIN OPTIONS BUILDER
##########################################################################################

build_options() {
    log_msg "==== Building nfqws2 options ===="

    validate_strategy_catalogs_machine || {
        log_error "Strategy catalog validation failed"
        return 1
    }

    if runtime_config_exists; then
        log_msg "Detected runtime config: $RUNTIME_CONFIG"
        log_msg "Using runtime.ini [core] for core runtime values; category building still uses categories.ini"
    fi

    # Base options
    OPTS="--qnum=$QNUM --fwmark=$DESYNC_MARK"

    # UID/GID for nfqws2 sandbox.
    # Important: if --uid is omitted, upstream defaults to 0x7FFFFFFF:0x7FFFFFFF.
    local effective_uid="${NFQWS_UID:-0:0}"
    OPTS="$OPTS --uid=$effective_uid"

    # IP cache for better performance
    OPTS="$OPTS --ipcache-lifetime=84600 --ipcache-hostname=1"

    # Add debug options
    local debug_opts=$(build_debug_opts)
    if [ -n "$debug_opts" ]; then
        OPTS="$OPTS $debug_opts"
    fi

    local used_preset_mode=0

    if is_preset_file_mode; then
        log_msg "Mode: Preset file configuration"
        log_msg "Preset file: ${PRESET_FILE:-Default.txt}"

        if build_preset_file_options; then
            used_preset_mode=1

            # In preset mode Lua init comes only from the preset file.
            if [ "${PRESET_HAS_LUA:-0}" -ne 1 ]; then
                log_msg "WARNING: Preset has no valid --lua-init lines"
            fi

            # If preset has no blob lines, add blobs.txt defaults.
            if [ "${PRESET_HAS_BLOB:-0}" -ne 1 ]; then
                local blob_opts_fallback=$(build_blob_opts)
                if [ -n "$blob_opts_fallback" ]; then
                    OPTS="$OPTS$blob_opts_fallback"
                    log_msg "Preset has no --blob, using blobs.txt"
                fi
            fi
        else
            log_error "Selected preset file mode failed"
            return 1
        fi
    fi

    if is_cmdline_mode; then
        log_msg "Mode: validated custom cmdline"
        build_validated_cmdline_options || return 1
        used_preset_mode=1
    fi

    case "${PRESET_MODE:-categories}" in
        categories|file|preset|txt|cmdline) ;;
        *) log_error "Unsupported preset_mode: $PRESET_MODE"; return 1 ;;
    esac

    if [ "$used_preset_mode" -eq 0 ]; then
        # Add Lua init options
        local lua_opts=$(build_lua_opts)
        if [ -n "$lua_opts" ]; then
            OPTS="$OPTS$lua_opts"
        fi

        # Add blob options
        local blob_opts=$(build_blob_opts)
        if [ -n "$blob_opts" ]; then
            OPTS="$OPTS$blob_opts"
        fi

        # Build strategy options from categories.ini
        log_msg "Mode: Category-based configuration (categories.ini)"
        log_msg "Packet count (--out-range): $PKT_OUT"
        build_category_options || return 1
    fi

    # Log final statistics
    local new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
    local strategy_count=$((new_count + 1))
    log_msg "Total strategies configured: $strategy_count"
    log_msg "Final command length: $(echo "$OPTS" | wc -c) chars"

    # Runtime common.sh publishes the final executable command atomically after
    # the dry-run succeeds. Machine validators must remain read-only.
    if [ "$COMMAND_BUILDER_CLI_MODE" -eq 0 ]; then
        prepare_private_runtime_file "$DEBUG_LOG" || return 1
        log_msg "Active command will be published to $CMDLINE_FILE"
    fi

    echo "$OPTS"
    return 0
}

if [ "$COMMAND_BUILDER_CLI_MODE" -eq 1 ]; then
    case "$1" in
        --scan-presets-machine)
            [ "$#" -eq 2 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            scan_presets_machine
            exit $?
            ;;
        --validate-preset-machine)
            [ "$#" -eq 4 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            validate_preset_machine "$3" "$4"
            exit $?
            ;;
        --validate-categories-machine)
            [ "$#" -eq 2 ] || { printf 'Z2_CATEGORIES_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            OPTS=""
            if validate_strategy_catalogs_machine && build_category_options; then
                printf 'Z2_CATEGORIES\tOK\n'
                exit 0
            fi
            printf 'Z2_CATEGORIES_ERROR\tINVALID_CONFIGURATION\n'
            exit 1
            ;;
        --validate-strategies-machine)
            [ "$#" -eq 2 ] || { printf 'Z2_STRATEGIES_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            if validate_strategy_catalogs_machine; then
                printf 'Z2_STRATEGIES\tOK\n'
                exit 0
            fi
            printf 'Z2_STRATEGIES_ERROR\tINVALID_CONFIGURATION\n'
            exit 1
            ;;
        --validate-cmdline-machine)
            [ "$#" -eq 3 ] || { printf 'Z2_CMDLINE_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            CUSTOM_CMDLINE_FILE="$3"
            load_effective_core_config_readonly || {
                printf 'Z2_CMDLINE_ERROR\tRUNTIME_UNAVAILABLE\n'
                exit 2
            }
            PRESET_MODE=cmdline
            CUSTOM_CMDLINE_FILE="$3"
            # A validator must not open the configured debug sink. Logging
            # does not affect packet-option validity.
            LOG_MODE=none
            nfqws_meta="$(stat -c '%u:%a:%h' "$NFQWS2" 2>/dev/null)" || nfqws_meta=""
            if [ "$nfqws_meta" != 0:755:1 ] || [ ! -f "$NFQWS2" ] || [ -L "$NFQWS2" ] ||
               [ ! -x "$NFQWS2" ]; then
                printf 'Z2_CMDLINE_ERROR\tBINARY_UNAVAILABLE\n'
                exit 2
            fi
            if ! command -v sha256sum >/dev/null 2>&1 ||
               ! command -v awk >/dev/null 2>&1; then
                printf 'Z2_CMDLINE_ERROR\tINTEGRITY_UNAVAILABLE\n'
                exit 2
            fi
            OPTS=""
            if ! build_validated_cmdline_options; then
                printf 'Z2_CMDLINE\t1\tINVALID\t%s\n' "$CUSTOM_CMDLINE_FILE"
                exit 1
            fi
            built_options="$(build_options)" || {
                printf 'Z2_CMDLINE_ERROR\tBUILD_UNAVAILABLE\n'
                exit 2
            }
            [ -n "$built_options" ] || {
                printf 'Z2_CMDLINE_ERROR\tEMPTY_BUILD\n'
                exit 2
            }
            if "$NFQWS2" --dry-run $built_options >/dev/null 2>&1; then
                printf 'Z2_CMDLINE\t1\tOK\t%s\n' "$CUSTOM_CMDLINE_FILE"
                exit 0
            fi
            printf 'Z2_CMDLINE\t1\tINVALID\t%s\n' "$CUSTOM_CMDLINE_FILE"
            exit 1
            ;;
    esac
fi
