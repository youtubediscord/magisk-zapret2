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

    local args=$(get_strategy_args_from_ini "$TCP_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        echo "$filter $args"
    else
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

    local args=$(get_strategy_args_from_ini "$UDP_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        echo "$filter $args"
    else
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

    local args=$(get_strategy_args_from_ini "$STUN_STRATEGIES_INI" "$strategy_name")

    if [ -n "$args" ]; then
        # STUN args already include --payload and --out-range, just add filter if any
        if [ -n "$filter" ]; then
            echo "$filter $args"
        else
            echo "$args"
        fi
    else
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
# PRESET FILE BUILDERS
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
            --new|--lua-init=*|--blob=*|--ctrack-disable=*|--ipcache-lifetime=*|--ipcache-hostname|--ipcache-hostname=*|--filter-tcp=*|--filter-udp=*|--filter-l7=*|--hostlist=*|--hostlist-domains=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*|--out-range=*|--payload=*|--lua-desync=*)
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
# Arguments: category, protocol, filter_mode, hostlist, strategy_name
# Modifies global: OPTS, first
build_category_options_single() {
    local category="$1"
    local protocol="$2"
    local filter_mode="$3"
    local hostlist="$4"
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

            # Add hostlist/ipset if specified (usually none for STUN)
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
            if [ -n "$filter_opts" ]; then
                full_filter="$filter_opts"
            fi

            # Get STUN strategy options from strategies-stun.ini
            strat_opts=$(get_stun_strategy_options "$strategy_name" "$full_filter")
            ;;
        udp)
            # UDP/QUIC - use get_udp_strategy_options
            proto_filter="--filter-udp=443,1400,50000-51000"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add hostlist/ipset if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
            if [ -n "$filter_opts" ]; then
                full_filter="$full_filter $filter_opts"
            fi

            # Get UDP strategy options
            strat_opts=$(get_udp_strategy_options "$strategy_name" "$full_filter")
            ;;
        tcp|*)
            # TCP (default) - use get_tcp_strategy_options
            proto_filter="--filter-tcp=80,443"
            local full_filter="--out-range=-n$PKT_OUT $proto_filter"

            # Add hostlist/ipset if specified
            local filter_opts=$(build_category_filter "$filter_mode" "$hostlist")
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
    local filter_mode="$5"
    local strategy="$6"
    local filter_file=""
    local effective_filter_mode="$filter_mode"

    [ -z "$current_section" ] && return
    [ -z "$strategy" ] && return
    [ "$strategy" = "disabled" ] && return

    if [ -z "$effective_filter_mode" ]; then
        if [ -n "$hostlist" ]; then
            effective_filter_mode="hostlist"
        elif [ -n "$ipset" ]; then
            effective_filter_mode="ipset"
        else
            effective_filter_mode="none"
        fi
    fi

    case "$effective_filter_mode" in
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

    build_category_options_single "$current_section" "$protocol" "$effective_filter_mode" "$filter_file" "$strategy"
}

# Parse categories.txt INI file and build options for each category
# Modifies global: OPTS, first
# New format supports: hostlist, ipset, filter_mode fields
parse_categories() {
    local ini_file="$CATEGORIES_FILE"
    local current_section=""
    local protocol="tcp"
    local hostlist=""
    local ipset=""
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
                process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$filter_mode" "$strategy"

                current_section="${line#[}"
                current_section="${current_section%]}"
                protocol="tcp"
                hostlist=""
                ipset=""
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

    process_category_section "$current_section" "$protocol" "$hostlist" "$ipset" "$filter_mode" "$strategy"
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

    # Base options
    OPTS="--qnum=$QNUM --fwmark=$DESYNC_MARK"

    # Optional privilege drop (1=system, 3003=inet)
    local effective_uid="${NFQWS_UID-}"
    if [ -n "$effective_uid" ]; then
        OPTS="$OPTS --uid=$effective_uid"
    else
        log_msg "Privilege drop disabled: nfqws2 will run as root"
    fi

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
            log_error "Preset file mode failed, falling back to categories.ini"
        fi
    fi

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
