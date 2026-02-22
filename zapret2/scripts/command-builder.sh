#!/system/bin/sh
# Command builder for nfqws2
# Source: functions extracted from zapret-start.sh
# Requires: common.sh must be sourced first

[ -z "$ZAPRET_DIR" ] && { echo "ERROR: source common.sh first"; exit 1; }

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
    local args=""
    local cr

    if [ ! -f "$ini_file" ]; then
        log_debug "INI file not found: $ini_file"
        return
    fi

    # Handle "default" strategy - look up [default] section
    if [ "$strategy_name" = "default" ] || [ -z "$strategy_name" ]; then
        strategy_name="default"
    fi

    log_debug "Looking for strategy [$strategy_name] in $ini_file"

    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"

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
                args=*)
                    args="${line#args=}"
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

# Get TCP strategy options
# Usage: get_tcp_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args
get_tcp_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local requested_strategy="$strategy_name"

    local args=$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        echo "$filter $args"
    else
        if [ -n "$requested_strategy" ] && [ "$requested_strategy" != "default" ]; then
            log_msg "WARNING: TCP strategy [$requested_strategy] not found in $TCP_STRATEGIES_INI, falling back to [default]"
        fi
        # Fallback to default strategy
        args=$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "default")
        if [ -n "$args" ]; then
            echo "$filter $args"
        fi
    fi
}

# Get UDP strategy options
# Usage: get_udp_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args
get_udp_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local requested_strategy="$strategy_name"

    local args=$(get_strategy_args_from_ini "$UDP_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        echo "$filter $args"
    else
        if [ -n "$requested_strategy" ] && [ "$requested_strategy" != "default" ]; then
            log_msg "WARNING: UDP strategy [$requested_strategy] not found in $UDP_STRATEGIES_INI, falling back to [default]"
        fi
        # Fallback to default strategy
        args=$(get_strategy_args_from_ini "$UDP_STRATEGIES_INI" "default")
        if [ -n "$args" ]; then
            echo "$filter $args"
        fi
    fi
}

# Get STUN strategy options
# Usage: get_stun_strategy_options <strategy_name> <filter>
# Returns: filter + strategy args (STUN args include --payload)
get_stun_strategy_options() {
    local strategy_name="$1"
    local filter="$2"
    local requested_strategy="$strategy_name"

    local args=$(get_strategy_args_from_ini "$STUN_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        # STUN args already include --payload and --out-range, just add filter if any
        if [ -n "$filter" ]; then
            echo "$filter $args"
        else
            echo "$args"
        fi
    else
        if [ -n "$requested_strategy" ] && [ "$requested_strategy" != "default" ]; then
            log_msg "WARNING: STUN strategy [$requested_strategy] not found in $STUN_STRATEGIES_INI, falling back to [default]"
        fi
        # Fallback to default strategy
        args=$(get_strategy_args_from_ini "$STUN_STRATEGIES_INI" "default")
        if [ -n "$args" ]; then
            if [ -n "$filter" ]; then
                echo "$filter $args"
            else
                echo "$args"
            fi
        fi
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

    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
        lua_opts="$lua_opts --lua-init=@$ZAPRET_DIR/lua/custom_funcs.lua"
        log_debug "Added Lua: custom-funcs.lua"
    fi

    if [ -f "$ZAPRET_DIR/lua/zapret-auto.lua" ]; then
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
# RAW CMDLINE / PRESET FILE BUILDERS
##########################################################################################

# Returns 0 when PRESET_MODE requests raw command-line loading.
is_custom_cmdline_mode() {
    case "${PRESET_MODE:-categories}" in
        cmdline|manual|raw)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Read custom cmdline file and return normalized one-line options string.
# Remove inline comments while preserving hashes inside quotes.
# Hashes preceded by backslash are preserved too.
strip_inline_comments() {
    local line="$1"
    printf '%s\n' "$line" |
    awk '
    {
        out="";
        in_single=0;
        in_double=0;
        escaped=0;
        single_quote=sprintf("%c", 39);
        double_quote=sprintf("%c", 34);

        for (i = 1; i <= length($0); i++) {
            ch = substr($0, i, 1);

            if (escaped == 1) {
                out = out ch;
                escaped = 0;
                continue;
            }

            if (ch == "\\") {
                out = out ch;
                escaped = 1;
                continue;
            }

            if (in_single) {
                if (ch == single_quote) {
                    in_single = 0;
                }
                out = out ch;
                continue;
            }

            if (in_double) {
                if (ch == double_quote) {
                    in_double = 0;
                }
                out = out ch;
                continue;
            }

            if (ch == single_quote) {
                in_single = 1;
                out = out ch;
                continue;
            }

            if (ch == double_quote) {
                in_double = 1;
                out = out ch;
                continue;
            }

            if (ch == "#") {
                break;
            }

            out = out ch;
        }

        sub(/[[:space:]]*$/, "", out);
        print out;
    }
    '
}

normalize_custom_cmdline_file() {
    local file_path="$1"
    local cr
    local line=""
    local normalized=""

    cr=$(printf '\r')

    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$cr}"

        # Trim spaces/tabs, drop inline comments, and skip empty/comment lines
        line="$(printf '%s\n' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        line="$(strip_inline_comments "$line")"
        [ -z "$line" ] && continue
        case "$line" in
            "#"*|";"*)
                continue
                ;;
        esac

        # Keep user-friendly multiline syntax and optional continuation backslashes
        line="$(printf '%s\n' "$line" | sed 's/[[:space:]]*\\$//')"
        [ -z "$line" ] && continue

        if [ -z "$normalized" ]; then
            normalized="$line"
        else
            normalized="$normalized $line"
        fi
    done < "$file_path"

    printf '%s' "$normalized"
}

# Resolve CUSTOM_CMDLINE_FILE to absolute path.
resolve_custom_cmdline_file_path() {
    local cmdline_file="$1"

    case "$cmdline_file" in
        /*)
            echo "$cmdline_file"
            ;;
        *)
            echo "$ZAPRET_DIR/$cmdline_file"
            ;;
    esac
}

# Collect values for one option from a space-separated option list.
# Supports both styles: --opt=value and --opt value.
collect_option_values() {
    local opts="$1"
    local option_name="$2"
    local token=""
    local consume_next=0

    [ -z "$opts" ] && return

    # shellcheck disable=SC2086
    set -- $opts

    while [ "$#" -gt 0 ]; do
        token="$1"
        shift

        if [ "$consume_next" -eq 1 ]; then
            printf '%s\n' "$token"
            consume_next=0
            continue
        fi

        case "$token" in
            --${option_name}=*)
                printf '%s\n' "${token#--${option_name}=}"
                ;;
            --${option_name})
                if [ "$#" -gt 0 ]; then
                    consume_next=1
                fi
                ;;
        esac
    done
}

# Validate critical core options that must not be duplicated in raw mode.
# Duplicates usually indicate conflicting --qnum/--uid/--fwmark/--debug values.

# Read the last value for an option from an option list.
read_cmdline_option_value() {
    local opts="$1"
    local option_name="$2"

    collect_option_values "$opts" "$option_name" | tail -n 1
}

# Sync base variables (QNUM/DESYNC_MARK/NFQWS_UID/LOG_MODE) with custom cmdline values.
sync_cmdline_core_overrides() {
    is_custom_cmdline_mode || return 0

    local cmdline_file="${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}"
    local resolved_file=""
    local raw_args=""
    local value=""

    resolved_file="$(resolve_custom_cmdline_file_path "$cmdline_file")"
    [ -f "$resolved_file" ] || return 1
    [ -r "$resolved_file" ] || return 1

    raw_args="$(normalize_custom_cmdline_file "$resolved_file")"
    [ -n "$raw_args" ] || return 1

    value="$(read_cmdline_option_value "$raw_args" "qnum")"
    [ -n "$value" ] && QNUM="$value"

    value="$(read_cmdline_option_value "$raw_args" "fwmark")"
    [ -n "$value" ] && DESYNC_MARK="$value"

    value="$(read_cmdline_option_value "$raw_args" "uid")"
    [ -n "$value" ] && NFQWS_UID="$value"

    value="$(read_cmdline_option_value "$raw_args" "debug")"
    case "$value" in
        android|file|syslog|none)
            LOG_MODE="$value"
            ;;
        "")
            ;;
        *)
            log_debug "Ignoring unsupported --debug value in cmdline file: $value"
            ;;
    esac

    log_msg "Applied cmdline core overrides: QNUM=$QNUM DESYNC_MARK=$DESYNC_MARK NFQWS_UID=${NFQWS_UID:-0:0} LOG_MODE=${LOG_MODE:-none}"
}

validate_cmdline_core_options() {
    local opts="$1"
    local cmdline_path="$2"
    local duplicate_found=0
    local option_name=""
    local values=""
    local total_count=0
    local unique_count=0

    for option_name in qnum fwmark uid debug; do
        values="$(collect_option_values "$opts" "$option_name")"
        [ -z "$values" ] && continue

        total_count=$(printf '%s\n' "$values" | wc -l)
        unique_count=$(printf '%s\n' "$values" | sort -u | wc -l)

        if [ "$total_count" -gt 1 ] && [ "$unique_count" -gt 1 ]; then
            log_error "Conflicting --${option_name} in raw cmdline: $total_count occurrences, $unique_count values"
            duplicate_found=1
        fi
    done

    if [ "$duplicate_found" -ne 0 ]; then
        log_error "Remove duplicated core options from $cmdline_path or switch to categories/file mode."
        return 1
    fi

    return 0
}

# Strip core options that build_options() already provides as base options.
# This prevents duplicate --qnum/--fwmark/--uid/--debug/--ipcache-* in the
# final command line when the user's raw cmdline file includes them.
strip_core_options_from_cmdline() {
    local raw="$1"
    local token=""
    local result=""
    local skip_next=0

    # shellcheck disable=SC2086
    set -- $raw

    while [ "$#" -gt 0 ]; do
        token="$1"
        shift

        if [ "$skip_next" -eq 1 ]; then
            skip_next=0
            continue
        fi

        case "$token" in
            --qnum=*|--fwmark=*|--uid=*|--debug=*|--ipcache-lifetime=*|--ipcache-hostname=*)
                continue
                ;;
            --qnum|--fwmark|--uid|--debug|--ipcache-lifetime|--ipcache-hostname)
                # The value is the next token
                skip_next=1
                continue
                ;;
        esac

        if [ -z "$result" ]; then
            result="$token"
        else
            result="$result $token"
        fi
    done

    printf '%s' "$result"
}

# Build options from raw nfqws2 options file.
# Modifies global: OPTS
build_custom_cmdline_options() {
    local cmdline_file="${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}"
    local resolved_file=""
    local raw_args=""
    local first_word=""

    resolved_file="$(resolve_custom_cmdline_file_path "$cmdline_file")"

    if [ ! -f "$resolved_file" ]; then
        log_error "Custom cmdline file not found: $resolved_file"
        return 1
    fi
    if [ ! -r "$resolved_file" ]; then
        log_error "Custom cmdline file is not readable: $resolved_file"
        return 1
    fi

    raw_args="$(normalize_custom_cmdline_file "$resolved_file")"

    if [ -z "$raw_args" ]; then
        log_error "Custom cmdline file is empty: $resolved_file"
        return 1
    fi

    first_word="${raw_args%% *}"
    case "$first_word" in
        */nfqws2|nfqws2)
            raw_args="${raw_args#"$first_word"}"
            while [ "${raw_args# }" != "$raw_args" ]; do
                raw_args="${raw_args# }"
            done
            ;;
    esac

    if [ -z "$raw_args" ]; then
        log_error "Custom cmdline has no nfqws2 options: $resolved_file"
        return 1
    fi

    if ! validate_cmdline_core_options "$OPTS $raw_args" "$resolved_file"; then
        return 1
    fi

    # Strip core options already provided by build_options() base setup
    raw_args="$(strip_core_options_from_cmdline "$raw_args")"

    if [ -z "$raw_args" ]; then
        log_error "Custom cmdline has only core options (nothing left after stripping): $resolved_file"
        return 1
    fi

    OPTS="$OPTS $raw_args"
    log_msg "Loaded raw nfqws2 options from $resolved_file"
    return 0
}

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

# Resolve PRESET_FILE to absolute path.
resolve_preset_file_path() {
    local preset_name="$1"

    case "$preset_name" in
        /*)
            echo "$preset_name"
            ;;
        *)
            echo "$PRESETS_DIR/$preset_name"
            ;;
    esac
}

# Returns 0 if file exists and is readable.
is_readable_file() {
    local file_path="$1"
    [ -f "$file_path" ] && [ -r "$file_path" ]
}

# Build options from a Windows-style preset TXT file.
# Modifies global: OPTS, PRESET_HAS_LUA, PRESET_HAS_BLOB
build_preset_file_options() {
    local preset_name="${PRESET_FILE:-Default.txt}"
    local preset_file=""
    local line=""
    local resolved=""
    local cr
    local kept=0
    local skipped=0

    PRESET_HAS_LUA=0
    PRESET_HAS_BLOB=0

    preset_file="$(resolve_preset_file_path "$preset_name")"

    if [ ! -f "$preset_file" ]; then
        log_error "Preset file not found: $preset_file"
        return 1
    fi
    if [ ! -r "$preset_file" ]; then
        log_error "Preset file is not readable: $preset_file"
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

        # Normalize module-relative paths
        line=$(echo "$line" | sed \
            -e "s|@lua/|@$ZAPRET_DIR/lua/|g" \
            -e "s|@bin/|@$ZAPRET_DIR/bin/|g" \
            -e "s|=lists/|=$LISTS_DIR/|g" \
            -e "s|@lists/|@$LISTS_DIR/|g")

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

        # Validate path-based options and skip missing files.
        case "$line" in
            --lua-init=@*)
                resolved="${line#--lua-init=@}"
                if [ ! -f "$resolved" ]; then
                    log_msg "Skipping missing Lua file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
                if [ ! -r "$resolved" ]; then
                    log_msg "Skipping unreadable Lua file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
                PRESET_HAS_LUA=1
                ;;
            --blob=*:@*)
                resolved="${line##*:}"
                resolved="${resolved#@}"
                if [ ! -f "$resolved" ]; then
                    log_msg "Skipping missing blob file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
                if [ ! -r "$resolved" ]; then
                    log_msg "Skipping unreadable blob file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
                PRESET_HAS_BLOB=1
                ;;
            --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
                resolved="${line#*=}"
                if [ ! -f "$resolved" ]; then
                    log_msg "Skipping missing list file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
                if [ ! -r "$resolved" ]; then
                    log_msg "Skipping unreadable list file in preset: $resolved"
                    skipped=$((skipped + 1))
                    continue
                fi
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

        OPTS="$OPTS $line"
        kept=$((kept + 1))
    done < "$preset_file"

    log_msg "Preset options loaded: kept=$kept skipped=$skipped"

    if [ "$kept" -eq 0 ]; then
        log_error "Preset file produced zero valid options: $preset_file"
        return 1
    fi

    return 0
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
            if [ -n "$filter_file" ]; then
                filter_opts="--hostlist-domains=$filter_file"
                log_debug "Using hostlist-domains: $filter_file"
            fi
            ;;
        hostlist)
            if [ -n "$filter_file" ] && is_readable_file "$LISTS_DIR/$filter_file"; then
                filter_opts="--hostlist=$LISTS_DIR/$filter_file"
                log_debug "Using hostlist: $filter_file"
            elif [ -n "$filter_file" ] && [ -f "$LISTS_DIR/$filter_file" ]; then
                log_msg "WARNING: Hostlist file is not readable: $LISTS_DIR/$filter_file"
            elif [ -n "$filter_file" ]; then
                log_debug "Hostlist file not found: $LISTS_DIR/$filter_file"
            fi
            ;;
        ipset)
            # filter_file is the ipset filename directly from categories.ini
            if [ -n "$filter_file" ] && is_readable_file "$LISTS_DIR/$filter_file"; then
                filter_opts="--ipset=$LISTS_DIR/$filter_file"
                log_debug "Using ipset: $filter_file"
            elif [ -n "$filter_file" ] && [ -f "$LISTS_DIR/$filter_file" ]; then
                log_msg "WARNING: Ipset file is not readable: $LISTS_DIR/$filter_file"
            elif [ -n "$filter_file" ]; then
                log_debug "Ipset file not found: $LISTS_DIR/$filter_file"
            fi
            ;;
        none|*)
            # No filtering
            log_debug "No domain/IP filtering for this category"
            ;;
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

    # Skip if strategy_name is empty
    if [ -z "$strategy_name" ]; then
        log_msg "WARNING: Empty strategy_name for category: $category"
        return
    fi

    # Determine protocol filter based on PROTOCOL field (not category name!)
    local proto_filter=""
    local strat_opts=""
    case "$protocol" in
        stun)
            # STUN/Voice - use STUN-specific strategies
            # Note: --payload, --out-range, --lua-desync are ALL in strategy options
            # No proto_filter needed - --payload in strategy does the filtering
            local full_filter=""

            # Add filtering options if specified (usually none for STUN)
            local filter_opts=$(build_category_filter "$filter_mode" "$filter_value")
            if [ -n "$filter_opts" ]; then
                full_filter="$filter_opts"
            fi

            # Get STUN strategy options from strategies-stun.ini
            strat_opts=$(get_stun_strategy_options "$strategy_name" "$full_filter")
            ;;
        udp)
            # UDP/QUIC - use get_udp_strategy_options
            proto_filter="--filter-udp=53,443,1400,50000-51000"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add filtering options if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$filter_value")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get UDP strategy options
            strat_opts=$(get_udp_strategy_options "$strategy_name" "$full_filter")
            ;;
        tcp|*)
            # TCP (default) - use get_tcp_strategy_options
            proto_filter="--filter-tcp=53,80,443"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add filtering options if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$filter_value")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get TCP strategy options
            strat_opts=$(get_tcp_strategy_options "$strategy_name" "$full_filter")
            ;;
    esac

    if [ -z "$strat_opts" ]; then
        log_msg "WARNING: No options for category $category with strategy $strategy_name"
        return
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

    [ -z "$current_section" ] && return
    [ -z "$strategy" ] && return
    [ "$strategy" = "disabled" ] && return

    if [ -z "$effective_filter_mode" ]; then
        if [ -n "$hostlist_domains" ]; then
            effective_filter_mode="hostlist-domains"
        elif [ -n "$hostlist" ]; then
            effective_filter_mode="hostlist"
        elif [ -n "$ipset" ]; then
            effective_filter_mode="ipset"
        else
            effective_filter_mode="none"
        fi
    fi

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
        none|*)
            filter_file=""
            ;;
    esac

    case "$effective_filter_mode" in
        hostlist|ipset)
            if [ -z "$filter_file" ]; then
                log_msg "WARNING: Skipping category [$current_section]: filter_mode=$effective_filter_mode requires a file, but none is set"
                return
            fi
            if [ ! -f "$LISTS_DIR/$filter_file" ]; then
                log_msg "WARNING: Skipping category [$current_section]: $effective_filter_mode file not found: $LISTS_DIR/$filter_file"
                return
            fi
            if [ ! -r "$LISTS_DIR/$filter_file" ]; then
                log_msg "WARNING: Skipping category [$current_section]: $effective_filter_mode file is not readable: $LISTS_DIR/$filter_file"
                return
            fi
            ;;
    esac

    build_category_options_single "$current_section" "$protocol" "$effective_filter_mode" "$filter_file" "$strategy"
}

# Parse categories.ini file and build options for each category
# Modifies global: OPTS, first
# New format supports: hostlist, ipset, hostlist-domains, filter_mode fields
parse_categories() {
    local ini_file="$CATEGORIES_FILE"
    local current_section=""
    local protocol="tcp"
    local hostlist=""
    local ipset=""
    local hostlist_domains=""
    local filter_mode=""
    local strategy=""
    local line=""
    local key=""
    local value=""
    local cr

    if [ ! -f "$ini_file" ]; then
        log_error "Categories file not found: $ini_file"
        return 1
    fi

    log_msg "Parsing categories from: $ini_file"

    cr=$(printf '\r')
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"

        case "$line" in
            ""|"#"*|";"*)
                continue
                ;;
            "["*"]")
                process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$hostlist_domains" "$filter_mode" "$strategy"

                current_section="${line#[}"
                current_section="${current_section%]}"
                protocol="tcp"
                hostlist=""
                ipset=""
                hostlist_domains=""
                filter_mode=""
                strategy=""
                log_debug "Found section: $current_section"
                continue
                ;;
            *=*)
                key="${line%%=*}"
                value="${line#*=}"

                case "$key" in
                    protocol)
                        protocol="$value"
                        ;;
                    hostlist)
                        hostlist="$value"
                        ;;
                    ipset)
                        ipset="$value"
                        ;;
                    hostlist-domains|hostlist_domains)
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
        esac
    done < "$ini_file"

    process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$hostlist_domains" "$filter_mode" "$strategy"
    return 0
}

##########################################################################################
# MAIN CATEGORY BUILDER
##########################################################################################

build_category_options() {
    first=1

    if [ ! -f "$CATEGORIES_FILE" ]; then
        log_error "Categories file not found: $CATEGORIES_FILE"
        log_msg "Using [default] strategy from strategies-tcp.ini"
        # Use default TCP strategy as fallback
        local default_args=$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "default")
        if [ -n "$default_args" ]; then
            OPTS="$OPTS --out-range=-n$PKT_OUT --filter-tcp=80,443 $default_args"
            first=0
        fi
        return
    fi

    log_msg "Building category-based options from categories.ini..."
    parse_categories

    if [ $first -eq 0 ]; then
        log_msg "Categories loaded successfully"
    else
        log_msg "No enabled categories found, using [default] strategy"
        local default_args=$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "default")
        if [ -n "$default_args" ]; then
            OPTS="$OPTS --out-range=-n$PKT_OUT --filter-tcp=80,443 $default_args"
        fi
    fi
}

##########################################################################################
# MAIN OPTIONS BUILDER
##########################################################################################

build_options() {
    log_section "Building nfqws2 options"

    if is_custom_cmdline_mode; then
        if ! sync_cmdline_core_overrides; then
            log_error "Core override sync from cmdline failed; fallback mode settings may be used"
        fi
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

    local used_cmdline_mode=0
    local used_preset_mode=0

    if is_custom_cmdline_mode; then
        log_msg "Mode: Raw command-line configuration"
        log_msg "Command file: ${CUSTOM_CMDLINE_FILE:-$ZAPRET_DIR/cmdline.txt}"

        if build_custom_cmdline_options; then
            used_cmdline_mode=1
        else
            log_error "Raw command-line mode failed, falling back to categories.ini"
        fi
    fi

    if [ "$used_cmdline_mode" -eq 0 ] && is_preset_file_mode; then
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
            log_error "Preset file mode failed, falling back to categories.ini"
        fi
    fi

    if [ "$used_cmdline_mode" -eq 0 ] && [ "$used_preset_mode" -eq 0 ]; then
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
        build_category_options
    fi

    # Log final statistics
    local new_count=$(echo "$OPTS" | grep -o '\--new' | wc -l)
    local strategy_count=$((new_count + 1))
    log_msg "Total strategies configured: $strategy_count"
    log_msg "Final command length: $(echo "$OPTS" | wc -c) chars"

    # Clear old debug log
    > "$DEBUG_LOG" 2>/dev/null

    echo "$OPTS"
    return 0
}
