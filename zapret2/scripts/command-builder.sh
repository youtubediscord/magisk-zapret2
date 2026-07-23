#!/system/bin/sh
# Strict preset compiler for Android nfqws2.
#
# The public machine protocol is intentionally line based. A compiled artifact
# stores one complete argument per line; neither this file nor zapret-start.sh
# evaluates or word-splits preset text.

COMMAND_BUILDER_CLI_MODE=0
COMMAND_BUILDER_ERROR_PREFIX=Z2_PRESET_ERROR
PRESET_MAX_BYTES=1048576
STRATEGY_CATALOG_MAX_BYTES=1048576
COMPILED_ARGV_MAX_BYTES=2097152

case "${1:-}" in
    --scan-presets-machine|--validate-preset-machine|--preflight-preset-machine|--preview-preset-machine|--validate-strategies-machine)
        COMMAND_BUILDER_CLI_MODE=1
        [ "$1" != --validate-strategies-machine ] || COMMAND_BUILDER_ERROR_PREFIX=Z2_STRATEGIES_ERROR
        ZAPRET_DIR="${2:-}"
        case "$ZAPRET_DIR" in
            /*) ;;
            *) printf '%s\tUNSAFE_ROOT\n' "$COMMAND_BUILDER_ERROR_PREFIX" >&2; exit 2 ;;
        esac
        case "$ZAPRET_DIR" in
            *'/../'*|*'/./'*|*/..|*/.)
                printf '%s\tUNSAFE_ROOT\n' "$COMMAND_BUILDER_ERROR_PREFIX" >&2
                exit 2
                ;;
        esac
        PRESETS_DIR="$ZAPRET_DIR/presets"
        LISTS_DIR="$ZAPRET_DIR/lists"
        SCRIPT_DIR="$ZAPRET_DIR/scripts"
        MODDIR="$(dirname "$ZAPRET_DIR")"
        if [ -f "$SCRIPT_DIR/common.sh" ] && [ ! -L "$SCRIPT_DIR/common.sh" ]; then
            . "$SCRIPT_DIR/common.sh" || exit 2
        fi
        log_msg() { :; }
        log_error() { :; }
        log_debug() { :; }
        ;;
    *)
        [ -n "${ZAPRET_DIR:-}" ] || { echo "ERROR: source common.sh first" >&2; exit 1; }
        ;;
esac

PRESET_VALIDATION_CODE=OK
PRESET_VALIDATION_DETAIL=
PRESET_DEPENDENCY_PATH=
PRESET_DEPENDENCY_RELATIVE=

preset_validation_fail() {
    PRESET_VALIDATION_CODE="$1"
    PRESET_VALIDATION_DETAIL="${2:-}"
    return 1
}

command_builder_safe_file_name_byte_length() {
    local value="$1" LC_ALL=C
    [ "${#value}" -le 255 ] 2>/dev/null
}

is_safe_preset_file_name() {
    local name="$1"
    [ -n "$name" ] && command_builder_safe_file_name_byte_length "$name" || return 1
    [ "${name# }" = "$name" ] && [ "${name% }" = "$name" ] || return 1
    case "$name" in
        _*|.|..|*/*|*\\*|*"'"*|*'"'*|*.TXT|*.Txt|*.tXt|*.txT|*.TXt|*.TxT|*.tXT) return 1 ;;
        *.txt) ;;
        *) return 1 ;;
    esac
    case "$name" in *[[:cntrl:]]*) return 1 ;; esac
    return 0
}

is_safe_dependency_name() {
    local name="$1"
    [ -n "$name" ] && command_builder_safe_file_name_byte_length "$name" || return 1
    [ "${name# }" = "$name" ] && [ "${name% }" = "$name" ] || return 1
    case "$name" in .|..|*/*|*\\*|*"'"*|*'"'*) return 1 ;; esac
    case "$name" in *[[:cntrl:]]*) return 1 ;; esac
    return 0
}

validate_preset_dependency() {
    local dependency_class="$1" raw="$2" relative= base=
    case "$dependency_class:$raw" in
        lua:@lua/*) relative="${raw#@lua/}"; base="$ZAPRET_DIR/lua" ;;
        blob:@bin/*) relative="${raw#@bin/}"; base="$ZAPRET_DIR/bin" ;;
        list:lists/*) relative="${raw#lists/}"; base="$LISTS_DIR" ;;
        *) preset_validation_fail UNSAFE_DEPENDENCY_PATH "$raw"; return 1 ;;
    esac
    is_safe_dependency_name "$relative" || {
        preset_validation_fail UNSAFE_DEPENDENCY_PATH "$raw"; return 1;
    }
    PRESET_DEPENDENCY_PATH="$base/$relative"
    PRESET_DEPENDENCY_RELATIVE="zapret2/${base##*/}/$relative"
    if [ -n "${PRESET_ALLOWED_DEPENDENCIES_FILE:-}" ]; then
        [ -f "$PRESET_ALLOWED_DEPENDENCIES_FILE" ] &&
            grep -Fqx "$PRESET_DEPENDENCY_RELATIVE" "$PRESET_ALLOWED_DEPENDENCIES_FILE" || {
                preset_validation_fail DEPENDENCY_NOT_DECLARED "$raw"; return 1;
            }
    fi
    [ ! -L "$PRESET_DEPENDENCY_PATH" ] || {
        preset_validation_fail DEPENDENCY_SYMLINK "$raw"; return 1;
    }
    [ -f "$PRESET_DEPENDENCY_PATH" ] || {
        preset_validation_fail DEPENDENCY_MISSING "$raw"; return 1;
    }
    [ -s "$PRESET_DEPENDENCY_PATH" ] || {
        preset_validation_fail DEPENDENCY_EMPTY "$raw"; return 1;
    }
    [ -r "$PRESET_DEPENDENCY_PATH" ] || {
        preset_validation_fail DEPENDENCY_UNREADABLE "$raw"; return 1;
    }
}

validate_filter_ports() {
    local list="$1" old_ifs item first last
    [ -n "$list" ] || return 1
    [ "$list" != '*' ] || return 0
    case "$list" in *[!0-9,:-]*|,*|*,|*,,*) return 1 ;; esac
    old_ifs="$IFS"; IFS=,; set -- $list; IFS="$old_ifs"
    [ "$#" -gt 0 ] || return 1
    for item in "$@"; do
        case "$item" in
            *-*) first="${item%%-*}"; last="${item#*-}"; case "$last" in *-*) return 1 ;; esac ;;
            *:*) first="${item%%:*}"; last="${item#*:}"; case "$last" in *:*) return 1 ;; esac ;;
            *) first="$item"; last="$item" ;;
        esac
        case "$first:$last" in *[!0-9:]*) return 1 ;; esac
        [ "$first" -ge 1 ] 2>/dev/null && [ "$last" -le 65535 ] 2>/dev/null &&
            [ "$first" -le "$last" ] 2>/dev/null || return 1
    done
}

validate_l7_filter() {
    local value="$1"
    case "$value" in
        stun|discord|stun,discord|discord,stun) return 0 ;;
        *) return 1 ;;
    esac
}

validate_strategy_blob_references() {
    local remaining="$1" token reference
    while [ -n "$remaining" ]; do
        token="${remaining%%:*}"
        case "$remaining" in *:*) remaining="${remaining#*:}" ;; *) remaining= ;; esac
        case "$token" in
            blob=*|fake_blob=*|pattern=*|seqovl_pattern=*) reference="${token#*=}" ;;
            *) continue ;;
        esac
        case "$reference" in
            0x*) continue ;;
            ''|*[!A-Za-z0-9_.-]*) preset_validation_fail INVALID_BLOB_REFERENCE "$reference"; return 1 ;;
        esac
        case "$declared_blobs" in
            *"|$reference|"*) ;;
            *) preset_validation_fail BLOB_REFERENCE_MISSING "$reference"; return 1 ;;
        esac
    done
}

validate_preset_file() {
    local preset_file="$1" logical_name="$2" candidate_name line cr size
    local in_profiles=0 profile_open=0 profile_name=0 profile_filter=0 profile_strategy=0 profile_skip=0
    local profiles=0 lua_count=0 blob_count=0 raw option blob_name
    local declared_blobs='|fake_default_http|fake_default_quic|fake_default_tls|'

    PRESET_VALIDATION_CODE=OK
    PRESET_VALIDATION_DETAIL=
    is_safe_preset_file_name "$logical_name" || {
        preset_validation_fail UNSAFE_PRESET_NAME "$logical_name"; return 1;
    }
    case "$preset_file" in
        "$PRESETS_DIR"/*) candidate_name="${preset_file#"$PRESETS_DIR"/}" ;;
        *) preset_validation_fail PRESET_NOT_DIRECT_CHILD "$preset_file"; return 1 ;;
    esac
    case "$candidate_name" in ''|*/*|*\\*) preset_validation_fail PRESET_NOT_DIRECT_CHILD "$preset_file"; return 1 ;; esac
    [ ! -L "$preset_file" ] || { preset_validation_fail PRESET_SYMLINK "$logical_name"; return 1; }
    [ -f "$preset_file" ] || { preset_validation_fail PRESET_MISSING "$logical_name"; return 1; }
    [ -s "$preset_file" ] || { preset_validation_fail PRESET_EMPTY "$logical_name"; return 1; }
    [ -r "$preset_file" ] || { preset_validation_fail PRESET_UNREADABLE "$logical_name"; return 1; }
    size="$(wc -c < "$preset_file" 2>/dev/null)" || {
        preset_validation_fail PRESET_UNREADABLE "$logical_name"; return 1;
    }
    case "$size" in ''|*[!0-9]*) preset_validation_fail PRESET_UNREADABLE "$logical_name"; return 1 ;; esac
    [ "$size" -le "$PRESET_MAX_BYTES" ] 2>/dev/null || {
        preset_validation_fail PRESET_TOO_LARGE "$logical_name"; return 1;
    }

    cr="$(printf '\r')"
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        case "$line" in ''|'#'*|';'*) continue ;; esac
        case "$line" in *[[:cntrl:]]*) preset_validation_fail UNSAFE_OPTION_VALUE "$logical_name"; return 1 ;; esac
        case "$line" in --ipcache*) preset_validation_fail FORBIDDEN_IPCACHE_OPTION "$logical_name"; return 1 ;; esac

        case "$line" in
            --lua-init=*)
                [ "$in_profiles" -eq 0 ] || { preset_validation_fail GLOBAL_OPTION_AFTER_PROFILE "$logical_name"; return 1; }
                raw="${line#--lua-init=}"
                validate_preset_dependency lua "$raw" || return 1
                lua_count=$((lua_count + 1))
                ;;
            --blob=*)
                [ "$in_profiles" -eq 0 ] || { preset_validation_fail GLOBAL_OPTION_AFTER_PROFILE "$logical_name"; return 1; }
                raw="${line#--blob=}"
                blob_name="${raw%%:*}"
                case "$blob_name" in ''|*[!A-Za-z0-9_.-]*) preset_validation_fail INVALID_BLOB "$logical_name"; return 1 ;; esac
                case "$declared_blobs" in
                    *"|$blob_name|"*) preset_validation_fail INVALID_BLOB "$logical_name"; return 1 ;;
                esac
                case "$raw" in
                    *:@bin/*) validate_preset_dependency blob "${raw##*:}" || return 1 ;;
                    *:0x*) ;;
                    *) preset_validation_fail INVALID_BLOB "$logical_name"; return 1 ;;
                esac
                declared_blobs="$declared_blobs$blob_name|"
                blob_count=$((blob_count + 1))
                ;;
            --ctrack-disable=*)
                [ "$in_profiles" -eq 0 ] || { preset_validation_fail GLOBAL_OPTION_AFTER_PROFILE "$logical_name"; return 1; }
                case "${line#*=}" in 0|1) ;; *) preset_validation_fail INVALID_OPTION_VALUE "$logical_name"; return 1 ;; esac
                ;;
            --name=*)
                [ "$profile_open" -eq 0 ] || { preset_validation_fail PROFILE_DUPLICATE_NAME "$logical_name"; return 1; }
                [ -n "${line#--name=}" ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                in_profiles=1; profile_open=1; profile_name=1
                ;;
            --skip)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                [ "$profile_skip" -eq 0 ] || { preset_validation_fail PROFILE_DUPLICATE_SKIP "$logical_name"; return 1; }
                profile_skip=1
                ;;
            --filter-tcp=*|--filter-udp=*)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                validate_filter_ports "${line#*=}" || { preset_validation_fail INVALID_FILTER "$logical_name"; return 1; }
                profile_filter=$((profile_filter + 1))
                ;;
            --filter-l7=*)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                validate_l7_filter "${line#*=}" || { preset_validation_fail INVALID_FILTER "$logical_name"; return 1; }
                profile_filter=$((profile_filter + 1))
                ;;
            --filter-l3=*)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                case "${line#*=}" in ipv4|ipv6|ipv4,ipv6|ipv6,ipv4) ;; *) preset_validation_fail INVALID_FILTER "$logical_name"; return 1 ;; esac
                ;;
            --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                option="${line%%=*}"; raw="${line#*=}"
                validate_preset_dependency list "$raw" || return 1
                ;;
            --hostlist-domains=*|--out-range=*|--payload=*)
                [ "$profile_open" -eq 1 ] && [ -n "${line#*=}" ] || {
                    preset_validation_fail INVALID_OPTION_VALUE "$logical_name"; return 1;
                }
                ;;
            --lua-desync=*)
                [ "$profile_open" -eq 1 ] && [ -n "${line#*=}" ] || {
                    preset_validation_fail PROFILE_STRATEGY_MISSING "$logical_name"; return 1;
                }
                validate_strategy_blob_references "$line" || return 1
                profile_strategy=$((profile_strategy + 1))
                ;;
            --new)
                [ "$profile_open" -eq 1 ] || { preset_validation_fail EMPTY_PROFILE "$logical_name"; return 1; }
                [ "$profile_name" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
                [ "$profile_filter" -gt 0 ] || { preset_validation_fail PROFILE_FILTER_MISSING "$logical_name"; return 1; }
                [ "$profile_strategy" -gt 0 ] || { preset_validation_fail PROFILE_STRATEGY_MISSING "$logical_name"; return 1; }
                profiles=$((profiles + 1)); profile_open=0; profile_name=0; profile_filter=0; profile_strategy=0; profile_skip=0
                ;;
            --wf-*|*windivert*) preset_validation_fail WINDOWS_OPTION_FORBIDDEN "$logical_name"; return 1 ;;
            *) preset_validation_fail UNKNOWN_OPTION "$logical_name"; return 1 ;;
        esac
    done < "$preset_file"

    [ "$profile_open" -eq 1 ] || { preset_validation_fail TRAILING_NEW "$logical_name"; return 1; }
    [ "$profile_name" -eq 1 ] || { preset_validation_fail PROFILE_NAME_MISSING "$logical_name"; return 1; }
    [ "$profile_filter" -gt 0 ] || { preset_validation_fail PROFILE_FILTER_MISSING "$logical_name"; return 1; }
    [ "$profile_strategy" -gt 0 ] || { preset_validation_fail PROFILE_STRATEGY_MISSING "$logical_name"; return 1; }
    profiles=$((profiles + 1))
    [ "$profiles" -gt 0 ] && [ "$lua_count" -gt 0 ] && [ "$blob_count" -gt 0 ] || {
        preset_validation_fail NO_VALID_OPTIONS "$logical_name"; return 1;
    }
}

normalize_port_union_file() {
    local input="$1"
    awk '
        function add(a,b) { n++; lo[n]=a+0; hi[n]=b+0 }
        {
            count=split($0, values, ",")
            for (i=1; i<=count; i++) {
                token=values[i]
                if (token == "*") { add(1,65535); continue }
                gsub(/-/, ":", token)
                parts=split(token, pair, ":")
                if (parts == 1) add(pair[1],pair[1]); else add(pair[1],pair[2])
            }
        }
        END {
            for (i=1; i<=n; i++) for (j=i+1; j<=n; j++)
                if (lo[j] < lo[i] || (lo[j] == lo[i] && hi[j] < hi[i])) {
                    t=lo[i]; lo[i]=lo[j]; lo[j]=t; t=hi[i]; hi[i]=hi[j]; hi[j]=t
                }
            out=""; have=0
            for (i=1; i<=n; i++) {
                if (!have) { a=lo[i]; b=hi[i]; have=1; continue }
                if (lo[i] <= b+1) { if (hi[i] > b) b=hi[i]; continue }
                token=(a==b ? a : a ":" b); out=out (out=="" ? "" : ",") token; a=lo[i]; b=hi[i]
            }
            if (have) { token=(a==b ? a : a ":" b); out=out (out=="" ? "" : ",") token }
            print out
        }
    ' "$input"
}

collect_capture_ports() {
    local preset_file="$1" temp_base tcp_raw udp_raw line cr
    local profile_skip=0 profile_tcp="" profile_udp="" profile_voice=0
    temp_base="${STATE_DIR:-${TMPDIR:-/tmp}}/z2-ports.$$"
    tcp_raw="$temp_base.tcp"; udp_raw="$temp_base.udp"
    : > "$tcp_raw" && : > "$udp_raw" || return 1
    cr="$(printf '\r')"
    flush_profile_ports() {
        if [ "$profile_skip" = 0 ]; then
            [ -z "$profile_tcp" ] || printf '%s\n' "$profile_tcp" >> "$tcp_raw" || return 1
            [ -z "$profile_udp" ] || printf '%s\n' "$profile_udp" >> "$udp_raw" || return 1
            [ "$profile_voice" = 0 ] || printf '%s\n' '3478,5349,19302' >> "$udp_raw" || return 1
        fi
        profile_skip=0; profile_tcp=""; profile_udp=""; profile_voice=0
    }
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        case "$line" in
            --new) flush_profile_ports || { rm -f "$tcp_raw" "$udp_raw"; return 1; } ;;
            --skip) profile_skip=1 ;;
            --filter-tcp=*)
                if [ -n "$profile_tcp" ]; then
                    profile_tcp="$profile_tcp
${line#--filter-tcp=}"
                else
                    profile_tcp="${line#--filter-tcp=}"
                fi
                ;;
            --filter-udp=*)
                if [ -n "$profile_udp" ]; then
                    profile_udp="$profile_udp
${line#--filter-udp=}"
                else
                    profile_udp="${line#--filter-udp=}"
                fi
                ;;
            --filter-l7=stun|--filter-l7=discord|--filter-l7=stun,discord|--filter-l7=discord,stun)
                profile_voice=1
                ;;
        esac
    done < "$preset_file"
    flush_profile_ports || { rm -f "$tcp_raw" "$udp_raw"; return 1; }
    COMPILED_TCP_PORTS="$(normalize_port_union_file "$tcp_raw")" || return 1
    COMPILED_UDP_PORTS="$(normalize_port_union_file "$udp_raw")" || return 1
    rm -f "$tcp_raw" "$udp_raw"
    [ -n "$COMPILED_TCP_PORTS$COMPILED_UDP_PORTS" ] || {
        preset_validation_fail NO_ENABLED_PROFILE
        return 1
    }
}

normalize_preset_argument() {
    local line="$1" raw option
    NORMALIZED_ARGUMENT="$line"
    case "$line" in
        --lua-init=*)
            raw="${line#--lua-init=}"; validate_preset_dependency lua "$raw" || return 1
            NORMALIZED_ARGUMENT="--lua-init=@$PRESET_DEPENDENCY_PATH"
            ;;
        --blob=*:@bin/*)
            raw="${line##*:}"; validate_preset_dependency blob "$raw" || return 1
            NORMALIZED_ARGUMENT="${line%:*}:@$PRESET_DEPENDENCY_PATH"
            ;;
        --hostlist=*|--hostlist-exclude=*|--ipset=*|--ipset-exclude=*)
            option="${line%%=*}"; raw="${line#*=}"; validate_preset_dependency list "$raw" || return 1
            NORMALIZED_ARGUMENT="$option=$PRESET_DEPENDENCY_PATH"
            ;;
    esac
}

compile_preset_artifact() {
    local preset_file="$1" logical_name="$2" artifact="$3" tmp line cr source_sha runtime_sha size
    validate_preset_file "$preset_file" "$logical_name" || return 1
    collect_capture_ports "$preset_file" || return 1
    source_sha="$(sha256sum "$preset_file" 2>/dev/null)" || return 1
    source_sha="${source_sha%% *}"
    case "$source_sha" in [0-9a-f][0-9a-f]*) [ "${#source_sha}" -eq 64 ] || return 1 ;; *) return 1 ;; esac
    runtime_sha="$(sha256sum "$RUNTIME_CONFIG" 2>/dev/null)" || return 1
    runtime_sha="${runtime_sha%% *}"
    case "$runtime_sha" in [0-9a-f][0-9a-f]*) [ "${#runtime_sha}" -eq 64 ] || return 1 ;; *) return 1 ;; esac
    tmp="$artifact.tmp.$$"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    {
        printf 'Z2_ARGV\t1\n'
        printf 'PRESET\t%s\nSHA256\t%s\nRUNTIME_SHA256\t%s\nTCP\t%s\nUDP\t%s\nARGS\n' \
            "$logical_name" "$source_sha" "$runtime_sha" "$COMPILED_TCP_PORTS" "$COMPILED_UDP_PORTS"
        printf '%s\n' "--qnum=${QNUM:-200}" "--fwmark=${DESYNC_MARK:-0x40000000}" "--uid=${NFQWS_UID:-0:0}"
        case "${LOG_MODE:-none}" in
            android) printf '%s\n' '--debug=android' ;;
            file) printf '%s\n' "--debug=@${DEBUG_LOG}" ;;
            syslog) printf '%s\n' '--debug=syslog' ;;
            none) ;;
            *) return 1 ;;
        esac
        cr="$(printf '\r')"
        while IFS= read -r line || [ -n "$line" ]; do
            line="${line%"$cr"}"
            case "$line" in ''|'#'*|';'*) continue ;; esac
            normalize_preset_argument "$line" || return 1
            printf '%s\n' "$NORMALIZED_ARGUMENT"
        done < "$preset_file"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    size="$(wc -c < "$tmp" 2>/dev/null)" || { rm -f "$tmp"; return 1; }
    case "$size" in ''|*[!0-9]*) rm -f "$tmp"; return 1 ;; esac
    [ "$size" -gt 0 ] && [ "$size" -le "$COMPILED_ARGV_MAX_BYTES" ] || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$artifact" || { rm -f "$tmp"; return 1; }
    COMPILED_ARGV_FILE="$artifact"
}

read_compiled_artifact_metadata() {
    local artifact="$1" line stage=0 seen_preset=0 seen_sha=0 seen_runtime_sha=0 seen_tcp=0 seen_udp=0 size tab key value
    COMPILED_PRESET=; COMPILED_SOURCE_SHA256=; COMPILED_RUNTIME_SHA256=; COMPILED_TCP_PORTS=; COMPILED_UDP_PORTS=
    [ -f "$artifact" ] && [ ! -L "$artifact" ] && [ -r "$artifact" ] || return 1
    size="$(wc -c < "$artifact" 2>/dev/null)" || return 1
    case "$size" in ''|*[!0-9]*) return 1 ;; esac
    [ "$size" -gt 0 ] && [ "$size" -le "$COMPILED_ARGV_MAX_BYTES" ] || return 1
    tab="$(printf '\t')"
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$stage" -eq 0 ]; then
            [ "$line" = "Z2_ARGV${tab}1" ] || return 1
            stage=1
            continue
        fi
        if [ "$stage" -eq 1 ]; then
            [ "$line" != ARGS ] || { stage=2; continue; }
            case "$line" in *"$tab"*) ;; *) return 1 ;; esac
            key="${line%%"$tab"*}"; value="${line#*"$tab"}"
            case "$key" in
                PRESET) [ "$seen_preset" -eq 0 ] || return 1; COMPILED_PRESET="$value"; seen_preset=1 ;;
                SHA256) [ "$seen_sha" -eq 0 ] || return 1; COMPILED_SOURCE_SHA256="$value"; seen_sha=1 ;;
                RUNTIME_SHA256) [ "$seen_runtime_sha" -eq 0 ] || return 1; COMPILED_RUNTIME_SHA256="$value"; seen_runtime_sha=1 ;;
                TCP) [ "$seen_tcp" -eq 0 ] || return 1; COMPILED_TCP_PORTS="$value"; seen_tcp=1 ;;
                UDP) [ "$seen_udp" -eq 0 ] || return 1; COMPILED_UDP_PORTS="$value"; seen_udp=1 ;;
                *) return 1 ;;
            esac
            continue
        fi
        case "$line" in --*) ;; *) return 1 ;; esac
    done < "$artifact"
    [ "$stage" -eq 2 ] && [ "$seen_preset$seen_sha$seen_runtime_sha$seen_tcp$seen_udp" = 11111 ] || return 1
    is_safe_preset_file_name "$COMPILED_PRESET" || return 1
    case "$COMPILED_SOURCE_SHA256" in *[!0-9a-f]*|'') return 1 ;; esac
    [ "${#COMPILED_SOURCE_SHA256}" -eq 64 ] || return 1
    case "$COMPILED_RUNTIME_SHA256" in *[!0-9a-f]*|'') return 1 ;; esac
    [ "${#COMPILED_RUNTIME_SHA256}" -eq 64 ] || return 1
    [ -z "$COMPILED_TCP_PORTS" ] ||
        validate_filter_ports "$(printf '%s' "$COMPILED_TCP_PORTS" | tr ':' '-')" || return 1
    [ -z "$COMPILED_UDP_PORTS" ] ||
        validate_filter_ports "$(printf '%s' "$COMPILED_UDP_PORTS" | tr ':' '-')" || return 1
    [ -n "$COMPILED_TCP_PORTS$COMPILED_UDP_PORTS" ] || return 1
}

run_compiled_artifact() {
    local artifact="$1" mode="$2" line in_args=0
    read_compiled_artifact_metadata "$artifact" || return 1
    set --
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$in_args" -eq 0 ]; then [ "$line" != ARGS ] || in_args=1; continue; fi
        case "$line" in
            --ipcache*) return 1 ;;
            --*) set -- "$@" "$line" ;;
            *) return 1 ;;
        esac
    done < "$artifact"
    [ "$#" -gt 3 ] || return 1
    case "$mode" in
        dry-run) "$NFQWS2" --dry-run "$@" ;;
        foreground) "$NFQWS2" "$@" ;;
        daemon)
            [ -n "${PIDFILE:-}" ] || return 1
            "$NFQWS2" --daemon "--pidfile=$PIDFILE" "$@" >/dev/null 2>&1 &
            LAUNCHED_PID=$!
            ;;
        background)
            "$NFQWS2" "$@" >/dev/null 2>&1 &
            LAUNCHED_PID=$!
            ;;
        *) return 1 ;;
    esac
}

nfqws_daemon_mode_supported() {
    local help_text="${NFQWS_HELP:-}"
    [ -n "$help_text" ] || help_text="$("$NFQWS2" --help 2>&1)" || return 1
    printf '%s\n' "$help_text" | grep -Fq -- '--daemon' &&
        printf '%s\n' "$help_text" | grep -Fq -- '--pidfile'
}

preview_compiled_artifact_machine() {
    local artifact="$1" logical_name="$2" line in_args=0 count=0
    read_compiled_artifact_metadata "$artifact" || return 1
    [ "$COMPILED_PRESET" = "$logical_name" ] || return 1
    printf 'Z2_COMMAND_PREVIEW\t1\t%s\tTCP=%s\tUDP=%s\n' \
        "$logical_name" "$COMPILED_TCP_PORTS" "$COMPILED_UDP_PORTS"
    printf 'Z2_COMMAND_EXECUTABLE\t%s\n' "$NFQWS2"
    # Preview is a pure projection of the packaged launcher contract. Runtime
    # capability checks and binary execution belong to preflight/start.
    printf 'Z2_COMMAND_ARGUMENT\t--daemon\n'
    printf 'Z2_COMMAND_ARGUMENT\t--pidfile=%s\n' "$PIDFILE"
    count=2
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$in_args" -eq 0 ]; then
            [ "$line" != ARGS ] || in_args=1
            continue
        fi
        case "$line" in --*) ;; *) return 1 ;; esac
        printf 'Z2_COMMAND_ARGUMENT\t%s\n' "$line"
        count=$((count + 1))
    done < "$artifact"
    [ "$count" -gt 3 ] || return 1
    printf 'Z2_COMMAND_SUMMARY\t1\tcount=%s\n' "$count"
}

scan_presets_machine() {
    local preset_file preset_name valid=0 quarantined=0 total=0
    [ -d "$PRESETS_DIR" ] && [ ! -L "$PRESETS_DIR" ] || {
        printf 'Z2_PRESET_ERROR\tPRESET_CATALOG_MISSING\n'; return 2;
    }
    for preset_file in "$PRESETS_DIR"/*.txt; do
        [ -e "$preset_file" ] || [ -L "$preset_file" ] || continue
        preset_name="${preset_file##*/}"
        case "$preset_name" in _*) continue ;; esac
        total=$((total + 1))
        if validate_preset_file "$preset_file" "$preset_name"; then
            valid=$((valid + 1)); printf 'Z2_PRESET\tVALID\tOK\t%s\n' "$preset_name"
        else
            quarantined=$((quarantined + 1)); printf 'Z2_PRESET\tQUARANTINED\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$preset_name"
        fi
    done
    printf 'Z2_PRESET_SUMMARY\t1\tvalid=%s\tquarantined=%s\ttotal=%s\n' "$valid" "$quarantined" "$total"
}

validate_preset_machine() {
    if validate_preset_file "$1" "$2"; then
        printf 'Z2_PRESET_VALIDATION\t1\tOK\t%s\n' "$2"; return 0
    fi
    printf 'Z2_PRESET_VALIDATION\t0\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$2"; return 1
}

validate_strategy_catalog_file() {
    local file="$1" line cr size section= seen='|' names=0 authors=0 labels=0 descriptions=0 blobs=0 strategies=0
    [ -f "$file" ] && [ ! -L "$file" ] && [ -s "$file" ] && [ -r "$file" ] || return 1
    size="$(wc -c < "$file" 2>/dev/null)" || return 1
    case "$size" in ''|*[!0-9]*) return 1 ;; esac
    [ "$size" -le "$STRATEGY_CATALOG_MAX_BYTES" ] || return 1
    cr="$(printf '\r')"
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        case "$line" in ''|'#'*|';'*) continue ;; esac
        case "$line" in
            '['*']')
                [ -z "$section" ] || {
                    [ "$names" -eq 1 ] && [ "$authors" -le 1 ] && [ "$labels" -le 1 ] &&
                        [ "$descriptions" -le 1 ] && [ "$blobs" -le 1 ] && [ "$strategies" -gt 0 ] || return 1
                }
                section="${line#[}"; section="${section%]}"
                case "$section" in ''|*[!A-Za-z0-9_.-]*) return 1 ;; esac
                case "$seen" in *"|$section|"*) return 1 ;; esac
                seen="$seen$section|"; names=0; authors=0; labels=0; descriptions=0; blobs=0; strategies=0
                ;;
            'name = '*) [ -n "$section" ] && [ -n "${line#name = }" ] || return 1; names=$((names + 1)) ;;
            'author = '*) [ -n "$section" ] && [ -n "${line#author = }" ] || return 1; authors=$((authors + 1)) ;;
            'label = '*) [ -n "$section" ] && [ -n "${line#label = }" ] || return 1; labels=$((labels + 1)) ;;
            'description = '*) [ -n "$section" ] && [ -n "${line#description = }" ] || return 1; descriptions=$((descriptions + 1)) ;;
            'blobs = '*)
                [ -n "$section" ] && [ -n "${line#blobs = }" ] || return 1
                validate_strategy_blob_list "${line#blobs = }" || return 1
                blobs=$((blobs + 1))
                ;;
            --lua-desync=*) [ -n "$section" ] && [ -n "${line#*=}" ] || return 1; case "$line" in *--ipcache*) return 1 ;; esac; strategies=$((strategies + 1)) ;;
            *) return 1 ;;
        esac
    done < "$file"
    [ -n "$section" ] && [ "$names" -eq 1 ] && [ "$authors" -le 1 ] && [ "$labels" -le 1 ] &&
        [ "$descriptions" -le 1 ] && [ "$blobs" -le 1 ] && [ "$strategies" -gt 0 ]
}

# Strategy catalogs contain hundreds of sections. Keep this validation in the
# current shell: a grep process for every `blobs =` row takes tens of seconds
# on process-heavy Android devices and blocks every other root-backed screen.
validate_strategy_blob_list() {
    local remaining="$1" item
    case "$remaining" in ''|,*|*,|*,,*) return 1 ;; esac
    trim_config_value_in_place "$remaining"
    [ "$CONFIG_VALUE_TRIMMED" = "$remaining" ] || return 1
    while [ -n "$remaining" ]; do
        item="${remaining%%,*}"
        case "$remaining" in *','*) remaining="${remaining#*,}" ;; *) remaining="" ;; esac
        trim_config_value_in_place "$item"
        item="$CONFIG_VALUE_TRIMMED"
        case "$item" in ''|*[!A-Za-z0-9_.-]*) return 1 ;; esac
    done
    return 0
}

validate_strategy_catalogs_machine() {
    local catalog
    for catalog in tcp udp voice http80; do
        validate_strategy_catalog_file "$ZAPRET_DIR/strategy-catalogs/$catalog.txt" || return 1
    done
}

if [ "$COMMAND_BUILDER_CLI_MODE" -eq 1 ]; then
    case "$1" in
        --scan-presets-machine)
            [ "$#" -eq 2 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            scan_presets_machine; exit $?
            ;;
        --validate-preset-machine)
            [ "$#" -eq 4 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            validate_preset_machine "$3" "$4"; exit $?
            ;;
        --preflight-preset-machine)
            [ "$#" -eq 4 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            load_effective_core_config_readonly || { printf 'Z2_PRESET_ERROR\tRUNTIME_UNAVAILABLE\n'; exit 2; }
            artifact="${STATE_DIR}/preset-preflight.$$"
            if compile_preset_artifact "$3" "$4" "$artifact" && run_compiled_artifact "$artifact" dry-run >/dev/null 2>&1; then
                rm -f "$artifact"; printf 'Z2_PRESET_VALIDATION\t1\tOK\t%s\n' "$4"; exit 0
            fi
            [ "$PRESET_VALIDATION_CODE" != OK ] || PRESET_VALIDATION_CODE=NFQWS_DRY_RUN_FAILED
            rm -f "$artifact"; printf 'Z2_PRESET_VALIDATION\t0\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$4"; exit 1
            ;;
        --preview-preset-machine)
            [ "$#" -eq 4 ] || { printf 'Z2_PRESET_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            load_effective_core_config_readonly || { printf 'Z2_PRESET_ERROR\tRUNTIME_UNAVAILABLE\n'; exit 2; }
            artifact="${STATE_DIR}/preset-preview.$$"
            state_file_target_is_safe "$artifact" || { printf 'Z2_PRESET_ERROR\tUNSAFE_PREVIEW_TARGET\n'; exit 2; }
            if compile_preset_artifact "$3" "$4" "$artifact" &&
                preview_compiled_artifact_machine "$artifact" "$4"; then
                rm -f "$artifact"; exit 0
            fi
            [ "$PRESET_VALIDATION_CODE" != OK ] || PRESET_VALIDATION_CODE=PREVIEW_FAILED
            rm -f "$artifact"
            printf 'Z2_COMMAND_PREVIEW\t0\t%s\t%s\n' "$PRESET_VALIDATION_CODE" "$4"
            exit 1
            ;;
        --validate-strategies-machine)
            [ "$#" -eq 2 ] || { printf 'Z2_STRATEGIES_ERROR\tINVALID_ARGUMENTS\n'; exit 2; }
            if validate_strategy_catalogs_machine; then printf 'Z2_STRATEGIES\tOK\n'; exit 0; fi
            printf 'Z2_STRATEGIES_ERROR\tINVALID_CONFIGURATION\n'; exit 1
            ;;
    esac
fi
