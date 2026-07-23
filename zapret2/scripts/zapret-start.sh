#!/system/bin/sh
# Transactional zapret2 start/replace lifecycle.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"
. "$SCRIPT_DIR/common.sh"
. "$SCRIPT_DIR/command-builder.sh"

set -f
REPLACE=0
CONTROLLED_TEARDOWN_STARTED=0
FIREWALL_MUTATED=0
PROBE_MUTATED=0
PROBE_TOOL=""
NEW_PID_PUBLISHED=0
LAUNCHED_PID=""
LAUNCHED_PID_START=""
LAUNCHED_ARGV_SHA256=""
LAUNCH_OWNS_PIDFILE=0
IPV4_BUILT=0
IPV6_BUILT=0
IPV4_RULES=0
IPV6_RULES=0
BUILD_TRACK_V4="$STATE_DIR/build-track.ipv4.$$"
BUILD_TRACK_V6="$STATE_DIR/build-track.ipv6.$$"
PROBE_TRACK_V4="$STATE_DIR/probe-track.ipv4.$$"
PROBE_TRACK_V6="$STATE_DIR/probe-track.ipv6.$$"
BUILD_TRACK_FILE=""
BUILD_TRACK_RECORD_ID=""
BUILD_TRACK_WRITE_SEQ=0
BUILD_TRACK_AMBIGUOUS=0
BUILD_TRACK_MODE=build
TRACK_CREATOR_START=""
TRACK_CREATOR_BOOT_ID=""
DIAGNOSTICS=""
FIREWALL_FAILURE_DETAIL=""
FIREWALL_FAILURE_STAGE=""
PRIOR_HEALTHY=0
PRIOR_TORN_DOWN=0
PRIOR_ARGV_FILE="$STATE_DIR/replace.argv.$$"
PRIOR_RULES_V4="$STATE_DIR/replace.v4.$$"
PRIOR_RULES_V6="$STATE_DIR/replace.v6.$$"
PRIOR_OWNER_FILE="$STATE_DIR/replace.owner.$$"
PRIOR_GENERATION=""
PRIOR_IPV6=0
PRIOR_QNUM=""
PRIOR_ARGV_SHA256=""

log_msg() {
    append_lifecycle_log "[INFO] $(date '+%Y-%m-%d %H:%M:%S') $1"
}

log_error() {
    append_lifecycle_log "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $1"
    if command -v log >/dev/null 2>&1; then log -p e -t Zapret2 "$1" 2>/dev/null; fi
}

log_debug() {
    [ "${DEBUG:-0}" = 1 ] && append_lifecycle_log "[DEBUG] $(date '+%Y-%m-%d %H:%M:%S') $1"
    return 0
}

log_section() { log_msg "==== $1 ===="; }

start_error_exit() {
    local domain="$1" code="$2" stage="$3" retryable="$4" message="$5"
    z2_error_set "$domain" "$code" "$stage" "$retryable" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED START 0
    z2_error_emit_machine
    echo "ERROR: $message"
    exit 1
}

set_default_config() {
    set_core_config_defaults
    HOSTLIST_MODE="none"
    HOSTLIST_FILES="youtube.txt"
}

load_config() {
    set_default_config
    load_effective_core_config || return 1
    log_msg "$(runtime_config_status_message)"
    log_msg "$(core_config_source_message)"
    return 0
}

normalize_positive_count() {
    local raw="$1" normalized
    COUNT_NORMALIZED=""
    is_decimal "$raw" || return 1
    normalized="$(printf '%s' "$raw" | sed 's/^0*//')"
    [ -n "$normalized" ] || normalized=0
    [ "${#normalized}" -le 9 ] || return 1
    [ "$normalized" -ge 1 ] 2>/dev/null || return 1
    COUNT_NORMALIZED="$normalized"
}

validate_port_list() {
    local list="$1" item first last old_ifs
    [ -n "$list" ] || return 0
    case "$list" in *[!0-9,:]*) return 1 ;; esac
    case "$list" in ,*|*,|*,,*) return 1 ;; esac
    old_ifs="$IFS"; IFS=,; set -- $list; IFS="$old_ifs"
    [ "$#" -gt 0 ] || return 1
    for item in "$@"; do
        case "$item" in
            *:*)
                first="${item%%:*}"; last="${item#*:}"
                case "$last" in *:*) return 1 ;; esac
                is_decimal "$first" && is_decimal "$last" || return 1
                [ "$first" -le 65535 ] 2>/dev/null || return 1
                [ "$last" -le 65535 ] 2>/dev/null || return 1
                [ "$first" -le "$last" ] 2>/dev/null || return 1
                ;;
            *)
                is_decimal "$item" || return 1
                [ "$item" -le 65535 ] 2>/dev/null || return 1
                ;;
        esac
    done
}

validate_mark() {
    local mark="$1" hex
    case "$mark" in
        0x*)
            hex="${mark#0x}"
            [ -n "$hex" ] || return 1
            case "$hex" in *[!0-9A-Fa-f]*) return 1 ;; esac
            ;;
        *) is_decimal "$mark" || return 1 ;;
    esac
}

validate_config() {
    normalize_qnum "$QNUM" || return 1
    QNUM="$QNUM_NORMALIZED"
    normalize_positive_count "$PKT_OUT" || return 1
    PKT_OUT="$COUNT_NORMALIZED"
    normalize_positive_count "$PKT_IN" || return 1
    PKT_IN="$COUNT_NORMALIZED"
    validate_mark "$DESYNC_MARK" || return 1
    case "$WIFI_ONLY" in 0|1) ;; *) return 1 ;; esac
    return 0
}

preflight_wifi_only() {
    case "$WIFI_ONLY" in
        0) return 0 ;;
        1)
            # There is no configured, verified Wi-Fi interface in the core
            # contract.  Queueing all interfaces would violate WIFI_ONLY.
            DIAGNOSTICS="${DIAGNOSTICS}WIFI_ONLY=1 requires verified interface scoping; startup refused; "
            return 1
            ;;
        *) return 1 ;;
    esac
}

preflight_files() {
    local path
    for path in "$ZAPRET_DIR/lua/zapret-lib.lua" "$ZAPRET_DIR/lua/zapret-antidpi.lua"; do
        [ -f "$path" ] && [ -r "$path" ] || return 1
    done
    return 0
}

prepare_options() {
    local capture="$ERROR_LOG.capture.$$" rcfile="$ERROR_LOG.rc.$$" dry_rc preset_file
    [ -f "$NFQWS2" ] && [ -x "$NFQWS2" ] || return 1
    NFQWS_HELP="$("$NFQWS2" --help 2>&1)"
    [ -n "$NFQWS_HELP" ] || return 1
    is_safe_preset_file_name "$ACTIVE_PRESET" || return 1
    preset_file="$PRESETS_DIR/$ACTIVE_PRESET"
    state_path_is_managed_file "$COMPILED_ARGV_FILE" || return 1
    compile_preset_artifact "$preset_file" "$ACTIVE_PRESET" "$COMPILED_ARGV_FILE" || return 1
    read_compiled_artifact_metadata "$COMPILED_ARGV_FILE" || return 1
    PORTS_TCP="$COMPILED_TCP_PORTS"
    PORTS_UDP="$COMPILED_UDP_PORTS"
    validate_port_list "$PORTS_TCP" || return 1
    validate_port_list "$PORTS_UDP" || return 1
    [ -n "$PORTS_TCP$PORTS_UDP" ] || return 1
    preflight_files || return 1
    prepare_private_runtime_file "$STARTUP_LOG" || return 1
    prepare_private_runtime_file "$ERROR_LOG" || return 1
    rm -f "$capture" "$rcfile" 2>/dev/null
    umask 077
    { run_compiled_artifact "$COMPILED_ARGV_FILE" dry-run >/dev/null; printf '%s\n' "$?" > "$rcfile"; } 2>&1 |
        tail -c 32768 > "$capture"
    dry_rc="$(cat "$rcfile" 2>/dev/null)"
    rm -f "$rcfile" 2>/dev/null
    is_decimal "$dry_rc" || { rm -f "$capture"; return 1; }
    chmod 0600 "$capture" 2>/dev/null || { rm -f "$capture"; return 1; }
    mv -f "$capture" "$ERROR_LOG" || { rm -f "$capture"; return 1; }
    [ "$dry_rc" -eq 0 ] 2>/dev/null || return 1
    compiled_source_binding_current || return 1
    {
        printf '%s\n' "$NFQWS2"
        if nfqws_daemon_mode_supported; then
            printf '%s\n' '--daemon' "--pidfile=$PIDFILE"
        fi
        awk 'found { print } $0 == "ARGS" { found=1 }' "$COMPILED_ARGV_FILE"
    } > "$CMDLINE_FILE.tmp.$$" || return 1
    chmod 0600 "$CMDLINE_FILE.tmp.$$" 2>/dev/null && mv -f "$CMDLINE_FILE.tmp.$$" "$CMDLINE_FILE" || {
        rm -f "$CMDLINE_FILE.tmp.$$"; return 1;
    }
    return 0
}

compiled_source_binding_current() {
    local current_source_sha current_runtime_sha
    read_compiled_artifact_metadata "$COMPILED_ARGV_FILE" || return 1
    [ "$ACTIVE_PRESET" = "$COMPILED_PRESET" ] || return 1
    current_source_sha="$(sha256sum "$PRESETS_DIR/$ACTIVE_PRESET" 2>/dev/null)" || return 1
    current_source_sha="${current_source_sha%% *}"
    [ "$current_source_sha" = "$COMPILED_SOURCE_SHA256" ] || return 1
    current_runtime_sha="$(sha256sum "$RUNTIME_CONFIG" 2>/dev/null)" || return 1
    current_runtime_sha="${current_runtime_sha%% *}"
    [ "$current_runtime_sha" = "$COMPILED_RUNTIME_SHA256" ]
}

count_family_rules() {
    local tool="$1" count=0 n chain
    for chain in "$ZAPRET2_OUT" "$ZAPRET2_IN"; do
        n="$("$tool" -t mangle -S "$chain" 2>/dev/null | grep -c "^-A $chain " || true)"
        is_decimal "$n" || n=0
        count=$((count + n))
    done
    printf '%s\n' "$count"
}

normal_health_ok() {
    HEALTH_PID=""; HEALTH_PID_START=""; HEALTH_GENERATION=""; HEALTH_IPV6=0; HEALTH_RULES=0
    read_verified_pidfile || return 1
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
    read_install_generation_meta && [ "$OWNER_STATE_INSTALL_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] || return 1
    [ "$OWNER_STATE_PHASE" = active ] || return 1
    [ "$OWNER_STATE_QNUM" = "$QNUM" ] || return 1
    command -v iptables >/dev/null 2>&1 || return 1
    owner_family_generation_healthy iptables ipv4 || return 1
    HEALTH_PID="$VERIFIED_PID"
    HEALTH_PID_START="$VERIFIED_PID_START"
    HEALTH_GENERATION="$OWNER_STATE_GENERATION"
    HEALTH_RULES="$OWNER_STATE_IPV4_RULES"
    if command -v ip6tables >/dev/null 2>&1; then
        owned_family_present ip6tables
        case $? in
            0) owner_family_generation_healthy ip6tables ipv6 || return 1; HEALTH_IPV6=1; HEALTH_RULES=$((OWNER_STATE_IPV4_RULES + OWNER_STATE_IPV6_RULES)) ;;
            1) ;;
            *) return 1 ;;
        esac
    elif [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ]; then
        return 1
    fi
    return 0
}

write_ok_status() {
    STATUS_RULES_OK="$1"; STATUS_RULES_FAIL=0; STATUS_RULES_TOTAL="$1"
    STATUS_ERRORS=""; STATUS_OWN_PID="$2"; STATUS_PID_VERIFIED=1; STATUS_QNUM="$QNUM"
    STATUS_OWN_PID_STARTTIME="$HEALTH_PID_START"; STATUS_OWNER_GENERATION="$HEALTH_GENERATION"
    STATUS_OWNER_METADATA_VERIFIED=1; STATUS_RULESET_VERIFIED=1; STATUS_RULES_EXPECTED="$1"
    STATUS_IPV4_ACTIVE=1; STATUS_IPV6_ACTIVE="$3"
    STATUS_IPV4_RULES="$OWNER_STATE_IPV4_RULES"; STATUS_IPV6_RULES="$OWNER_STATE_IPV6_RULES"
    STATUS_CHAINS=$((2 + 2 * STATUS_IPV6_ACTIVE)); STATUS_ANCHORS="$STATUS_CHAINS"
    STATUS_NFQUEUE_SUPPORTED=1; STATUS_QUEUE_BYPASS_SUPPORTED=1
    STATUS_CONNBYTES_SUPPORTED="${IPV4_CONNBYTES:-1}"
    STATUS_MULTIPORT_SUPPORTED="${IPV4_MULTIPORT:-1}"
    STATUS_MARK_SUPPORTED="${IPV4_MARK:-1}"
    STATUS_FALLBACK_MODE="${FALLBACK_MODE:-0}"
    STATUS_ERROR_DOMAIN=NONE; STATUS_ERROR_CODE=NONE
    STATUS_ERROR_STAGE=NONE; STATUS_ERROR_RETRYABLE=0
    STATUS_DIAGNOSTICS="$DIAGNOSTICS"
    write_iptables_status ok
}

rollback_start() {
    local rc=0
    ROLLBACK_ERRORS=""
    if [ "$PROBE_MUTATED" = 1 ]; then
        cleanup_probe_artifacts >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="probe cleanup failed"; }
    fi
    if [ "$FIREWALL_MUTATED" = 1 ]; then
        cleanup_tracked_family ip6tables >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="tracked IPv6 firewall cleanup failed"; }
        cleanup_tracked_family iptables >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }tracked IPv4 firewall cleanup failed"; }
        cleanup_owned_firewall >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="owned firewall cleanup failed"; }
    fi
    if [ "$NEW_PID_PUBLISHED" = 1 ]; then
        stop_pidfile_process >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }owned process cleanup failed"; }
    elif [ -n "$LAUNCHED_PID" ]; then
        stop_failed_fallback_launch "$LAUNCHED_PID" >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }failed launch process remains ambiguous"; }
    elif [ "$PRIOR_TORN_DOWN" = 1 ]; then
        stop_pidfile_process >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }prestate process cleanup failed"; }
    fi
    firewall_is_clean_after_rollback || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }owned firewall artifacts remain"; }
    scan_exact_owned_nfqws >/dev/null 2>&1
    [ -z "$OWNED_SCAN_PIDS" ] || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }module-owned process remains: $OWNED_SCAN_PIDS"; }
    [ "$rc" -ne 0 ] || retire_owner_metadata >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="ownership metadata cleanup failed"; }
    return "$rc"
}

discard_prior_snapshot() {
    state_path_is_managed_file "$PRIOR_ARGV_FILE" && rm -f "$PRIOR_ARGV_FILE" 2>/dev/null
    state_path_is_managed_file "$PRIOR_RULES_V4" && rm -f "$PRIOR_RULES_V4" 2>/dev/null
    state_path_is_managed_file "$PRIOR_RULES_V6" && rm -f "$PRIOR_RULES_V6" 2>/dev/null
    state_path_is_managed_file "$PRIOR_OWNER_FILE" && rm -f "$PRIOR_OWNER_FILE" 2>/dev/null
}

build_track_for_tool() {
    BUILD_TRACK_MODE=build
    case "$1" in iptables) BUILD_TRACK_FILE="$BUILD_TRACK_V4";; ip6tables) BUILD_TRACK_FILE="$BUILD_TRACK_V6";; *) return 1;; esac
}

probe_track_for_tool() {
    BUILD_TRACK_MODE=probe
    case "$1" in iptables) BUILD_TRACK_FILE="$PROBE_TRACK_V4";; ip6tables) BUILD_TRACK_FILE="$PROBE_TRACK_V6";; *) return 1;; esac
}

begin_tracked_family() {
    local tool="$1" tmp
    build_track_for_tool "$tool" || return 1
    state_path_is_managed_file "$BUILD_TRACK_FILE" || return 1
    [ ! -e "$BUILD_TRACK_FILE" ] && [ ! -L "$BUILD_TRACK_FILE" ] || return 1
    BUILD_TRACK_AMBIGUOUS=0
    TRACK_CREATOR_START="$(proc_starttime "$$")" || return 1
    read_current_boot_id || return 1
    TRACK_CREATOR_BOOT_ID="$CURRENT_BOOT_ID"
    BUILD_TRACK_WRITE_SEQ=$((BUILD_TRACK_WRITE_SEQ + 1))
    tmp="$BUILD_TRACK_FILE.$BUILD_TRACK_WRITE_SEQ.tmp"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    printf 'version=2\nmode=build\ntool=%s\nmodule_dir=%s\ncreator_pid=%s\ncreator_starttime=%s\nboot_id=%s\n' \
        "$tool" "$MODDIR" "$$" "$TRACK_CREATOR_START" "$TRACK_CREATOR_BOOT_ID" > "$tmp" || return 1
    publish_build_track_temp "$tmp"
}

begin_probe_track() {
    local tool="$1" tmp
    probe_track_for_tool "$tool" || return 1
    state_path_is_managed_file "$BUILD_TRACK_FILE" || return 1
    [ ! -e "$BUILD_TRACK_FILE" ] && [ ! -L "$BUILD_TRACK_FILE" ] || return 1
    BUILD_TRACK_AMBIGUOUS=0
    TRACK_CREATOR_START="$(proc_starttime "$$")" || return 1
    read_current_boot_id || return 1
    TRACK_CREATOR_BOOT_ID="$CURRENT_BOOT_ID"
    BUILD_TRACK_WRITE_SEQ=$((BUILD_TRACK_WRITE_SEQ + 1))
    tmp="$BUILD_TRACK_FILE.$BUILD_TRACK_WRITE_SEQ.tmp"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    printf 'version=2\nmode=probe\ntool=%s\nmodule_dir=%s\ncreator_pid=%s\ncreator_starttime=%s\nboot_id=%s\n' \
        "$tool" "$MODDIR" "$$" "$TRACK_CREATOR_START" "$TRACK_CREATOR_BOOT_ID" > "$tmp" || return 1
    publish_build_track_temp "$tmp"
}

validate_build_track() {
    local tool="$1"
    [ -n "$BUILD_TRACK_FILE" ] || return 1
    validate_track_journal_identity "$BUILD_TRACK_FILE" || return 1
    [ "$TRACK_JOURNAL_MODE" = "$BUILD_TRACK_MODE" ] && [ "$TRACK_JOURNAL_TOOL" = "$tool" ] &&
        [ "$TRACK_CREATOR_PID" = "$$" ] && [ "$TRACK_CREATOR_START" = "$(proc_starttime "$$")" ] &&
        read_current_boot_id && [ "$TRACK_BOOT_ID" = "$CURRENT_BOOT_ID" ] || return 1
    if [ "$BUILD_TRACK_MODE" = build ] && [ "$TRACK_FIREWALL_TAG" != none ]; then
        [ "$TRACK_FIREWALL_TAG" = "$FIREWALL_TAG" ] &&
            [ "$TRACK_OUT_CHAIN" = "$ZAPRET2_OUT" ] &&
            [ "$TRACK_IN_CHAIN" = "$ZAPRET2_IN" ] || return 1
    fi
    return 0
}

publish_build_track_temp() {
    local tmp="$1"
    chmod 0600 "$tmp" 2>/dev/null || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    sync >/dev/null 2>&1 || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    mv -f "$tmp" "$BUILD_TRACK_FILE" 2>/dev/null || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    sync >/dev/null 2>&1 || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    return 0
}

build_track_add_pending() {
    local payload="$1" tmp next
    validate_build_track "$BUILD_TRACK_TOOL" || return 1
    next="$(awk -F '|' '$1 == "record" { n=$2 } END { print n + 1 }' "$BUILD_TRACK_FILE")" || return 1
    BUILD_TRACK_WRITE_SEQ=$((BUILD_TRACK_WRITE_SEQ + 1))
    tmp="$BUILD_TRACK_FILE.$BUILD_TRACK_WRITE_SEQ.tmp"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    { cat "$BUILD_TRACK_FILE"; printf 'record|%s|pending|%s\n' "$next" "$payload"; } > "$tmp" || return 1
    publish_build_track_temp "$tmp" || return 1
    BUILD_TRACK_RECORD_ID="$next"
}

build_track_transition() {
    local id="$1" from="$2" to="$3" tmp
    validate_build_track "$BUILD_TRACK_TOOL" || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    BUILD_TRACK_WRITE_SEQ=$((BUILD_TRACK_WRITE_SEQ + 1))
    tmp="$BUILD_TRACK_FILE.$BUILD_TRACK_WRITE_SEQ.tmp"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    awk -F '|' -v OFS='|' -v id="$id" -v from="$from" -v to="$to" '
        $1 == "record" && $2 == id && $3 == from { $3=to; changed++ }
        { print }
        END { if (changed != 1) exit 1 }
    ' "$BUILD_TRACK_FILE" > "$tmp" || { BUILD_TRACK_AMBIGUOUS=1; return 1; }
    publish_build_track_temp "$tmp" || return 1
}

run_firewall_mutation() {
    local stage="$1" tool rc detail
    shift
    tool="$1"
    FIREWALL_FAILURE_DETAIL=""
    FIREWALL_FAILURE_STAGE=""
    : > "$ERROR_LOG" 2>/dev/null || return 1
    chmod 0600 "$ERROR_LOG" 2>/dev/null || return 1
    "$@" 2> "$ERROR_LOG"
    rc=$?
    [ "$rc" -ne 0 ] || return 0
    detail="$(head -c 512 "$ERROR_LOG" 2>/dev/null | tr '\r\n' '  ')"
    FIREWALL_FAILURE_STAGE="$stage"
    FIREWALL_FAILURE_DETAIL="$tool $stage failed (exit $rc)${detail:+: $detail}"
    return "$rc"
}

tracked_create_chain() {
    local tool="$1" chain="$2" id
    build_track_add_pending "chain|$chain" || return 1
    id="$BUILD_TRACK_RECORD_ID"
    if ! run_firewall_mutation BUILD_CHAIN "$tool" -t mangle -N "$chain"; then
        build_track_transition "$id" pending consumed || true
        return 1
    fi
    build_track_transition "$id" pending applied
}

tracked_append_anchor() {
    local tool="$1" builtin="$2" target="$3" id
    build_track_add_pending "anchor|$builtin|$target" || return 1
    id="$BUILD_TRACK_RECORD_ID"
    if ! run_firewall_mutation COMMIT_ANCHOR "$tool" -t mangle -A "$builtin" -j "$target"; then
        build_track_transition "$id" pending consumed || true
        return 1
    fi
    build_track_transition "$id" pending applied
}

cleanup_tracked_family() {
    local tool="$1" ids line record id state kind a b c d e f g h i j k rc=0
    build_track_for_tool "$tool" || return 1
    BUILD_TRACK_TOOL="$tool"
    [ -e "$BUILD_TRACK_FILE" ] || return 0
    validate_build_track "$tool" || return 1
    [ "$BUILD_TRACK_AMBIGUOUS" = 0 ] || return 1
    awk -F '|' '$1 == "record" && ($3 == "pending" || $3 == "consuming") { found=1 } END { exit !found }' "$BUILD_TRACK_FILE" && return 1
    ids="$(awk -F '|' '$1 == "record" && $3 == "applied" { id[++n]=$2 } END { for (; n>0; n--) print id[n] }' "$BUILD_TRACK_FILE")" || return 1
    for id in $ids; do
        line="$(awk -F '|' -v id="$id" '$1 == "record" && $2 == id { print; found++ } END { if (found != 1) exit 1 }' "$BUILD_TRACK_FILE")" || { rc=1; break; }
        IFS='|' read -r record id state kind a b c d e f g h i j k <<EOF
$line
EOF
        build_track_transition "$id" applied consuming || { rc=1; break; }
        case "$kind" in
            rule)
                if ! owner_rule_set "$tool" -D "$a" "$b" "$c" "$d" "$e" "$f" "$g" "$h" "$i" "$j" "$k"; then
                    owner_rule_set "$tool" -C "$a" "$b" "$c" "$d" "$e" "$f" "$g" "$h" "$i" "$j" "$k" && rc=1
                fi
                ;;
            anchor) delete_exact_anchor "$tool" "$a" "$b" || rc=1 ;;
            chain) if owned_chain_exists "$tool" "$a"; then "$tool" -t mangle -X "$a" >/dev/null 2>&1 || rc=1; fi ;;
            *) rc=1 ;;
        esac
        [ "$rc" -eq 0 ] || break
        build_track_transition "$id" consuming consumed || { rc=1; break; }
    done
    if [ "$rc" -eq 0 ]; then
        awk -F '|' '$1 == "record" && $3 != "consumed" { bad=1 } END { exit bad }' "$BUILD_TRACK_FILE" || rc=1
    fi
    if [ "$rc" -eq 0 ]; then
        rm -f "$BUILD_TRACK_FILE" 2>/dev/null || rc=1
        [ "$rc" -ne 0 ] || sync >/dev/null 2>&1 || rc=1
    fi
    return "$rc"
}

retire_committed_build_track() {
    local tool="$1" required="${2:-0}" tmp
    build_track_for_tool "$tool" || return 1
    if [ ! -e "$BUILD_TRACK_FILE" ] && [ ! -L "$BUILD_TRACK_FILE" ]; then [ "$required" = 0 ]; return; fi
    BUILD_TRACK_TOOL="$tool"
    validate_build_track "$tool" || return 1
    awk -F '|' '$1 == "record" && ($3 == "pending" || $3 == "consuming") { bad=1 } END { exit bad }' "$BUILD_TRACK_FILE" || return 1
    BUILD_TRACK_WRITE_SEQ=$((BUILD_TRACK_WRITE_SEQ + 1))
    tmp="$BUILD_TRACK_FILE.$BUILD_TRACK_WRITE_SEQ.tmp"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    awk -F '|' -v OFS='|' '$1 == "record" && $3 == "applied" { $3="consumed" } { print }' \
        "$BUILD_TRACK_FILE" > "$tmp" || return 1
    publish_build_track_temp "$tmp" || return 1
    validate_build_track "$tool" || return 1
    awk -F '|' '$1 == "record" && $3 != "consumed" { bad=1 } END { exit bad }' "$BUILD_TRACK_FILE" || return 1
    rm -f "$BUILD_TRACK_FILE" 2>/dev/null || return 1
    sync >/dev/null 2>&1
}

discard_build_tracks() {
    retire_committed_build_track iptables 1 || return 1
    retire_committed_build_track ip6tables "$IPV6_ACTIVE"
}

probe_rule_exec() {
    local tool="$1" op="$2" kind="$3" qnum="$4" pkt="$5" mark="$6"
    case "$kind" in
        queue_bypass) "$tool" -t mangle "$op" "$ZAPRET2_PROBE" -p tcp --dport 1 -j NFQUEUE --queue-num "$qnum" --queue-bypass >/dev/null 2>&1 ;;
        queue) "$tool" -t mangle "$op" "$ZAPRET2_PROBE" -p tcp --dport 1 -j NFQUEUE --queue-num "$qnum" >/dev/null 2>&1 ;;
        connbytes) "$tool" -t mangle "$op" "$ZAPRET2_PROBE" -p tcp --dport 1 -m connbytes --connbytes "1:$pkt" --connbytes-dir original --connbytes-mode packets -j ACCEPT >/dev/null 2>&1 ;;
        multiport) "$tool" -t mangle "$op" "$ZAPRET2_PROBE" -p tcp -m multiport --dports 80,443 -j ACCEPT >/dev/null 2>&1 ;;
        mark) "$tool" -t mangle "$op" "$ZAPRET2_PROBE" -p tcp --dport 1 -m mark ! --mark "$mark/$mark" -j ACCEPT >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

tracked_probe_append() {
    local tool="$1" kind="$2" id
    build_track_add_pending "probe_rule|$kind|$QNUM|$PKT_OUT|$DESYNC_MARK" || return 1
    id="$BUILD_TRACK_RECORD_ID"
    if ! probe_rule_exec "$tool" -A "$kind" "$QNUM" "$PKT_OUT" "$DESYNC_MARK"; then
        build_track_transition "$id" pending consumed || return 1
        return 2
    fi
    build_track_transition "$id" pending applied || return 1
    PROBE_RULE_RECORD_ID="$id"
}

consume_probe_rule() {
    local tool="$1" id="$2" kind="$3" qnum="$4" pkt="$5" mark="$6" rc=0
    build_track_transition "$id" applied consuming || return 1
    if ! probe_rule_exec "$tool" -D "$kind" "$qnum" "$pkt" "$mark"; then
        probe_rule_exec "$tool" -C "$kind" "$qnum" "$pkt" "$mark" && rc=1
    elif probe_rule_exec "$tool" -C "$kind" "$qnum" "$pkt" "$mark"; then
        rc=1
    fi
    [ "$rc" -eq 0 ] || return 1
    build_track_transition "$id" consuming consumed
}

consume_probe_chain() {
    local tool="$1" id="$2"
    build_track_transition "$id" applied consuming || return 1
    if owned_chain_exists "$tool" "$ZAPRET2_PROBE"; then
        "$tool" -t mangle -X "$ZAPRET2_PROBE" >/dev/null 2>&1 || return 1
    fi
    owned_chain_exists "$tool" "$ZAPRET2_PROBE" && return 1
    build_track_transition "$id" consuming consumed
}

cleanup_probe_track() {
    local tool="$1" ids line record id state kind a b c d rc=0
    probe_track_for_tool "$tool" || return 1
    BUILD_TRACK_TOOL="$tool"
    [ -e "$BUILD_TRACK_FILE" ] || return 0
    validate_build_track "$tool" || return 1
    [ "$BUILD_TRACK_AMBIGUOUS" = 0 ] || return 1
    awk -F '|' '$1 == "record" && ($3 == "pending" || $3 == "consuming") { found=1 } END { exit !found }' "$BUILD_TRACK_FILE" && return 1
    ids="$(awk -F '|' '$1 == "record" && $3 == "applied" { id[++n]=$2 } END { for (; n>0; n--) print id[n] }' "$BUILD_TRACK_FILE")" || return 1
    for id in $ids; do
        line="$(awk -F '|' -v id="$id" '$1 == "record" && $2 == id { print; found++ } END { if (found != 1) exit 1 }' "$BUILD_TRACK_FILE")" || { rc=1; break; }
        IFS='|' read -r record id state kind a b c d <<EOF
$line
EOF
        case "$kind" in
            probe_rule) consume_probe_rule "$tool" "$id" "$a" "$b" "$c" "$d" || rc=1 ;;
            chain) consume_probe_chain "$tool" "$id" || rc=1 ;;
            *) rc=1 ;;
        esac
        [ "$rc" -eq 0 ] || break
    done
    if [ "$rc" -eq 0 ]; then
        awk -F '|' '$1 == "record" && $3 != "consumed" { bad=1 } END { exit bad }' "$BUILD_TRACK_FILE" || rc=1
    fi
    if [ "$rc" -eq 0 ]; then rm -f "$BUILD_TRACK_FILE" 2>/dev/null || rc=1; [ "$rc" -ne 0 ] || sync >/dev/null 2>&1 || rc=1; fi
    return "$rc"
}

capture_prior_healthy_generation() {
    local first size tmp="$PRIOR_ARGV_FILE.tmp.$$" rules_tmp expected_rules
    PRIOR_HEALTHY=0
    # Absence of an exact active owner is not an error: the normal degraded or
    # stopped cleanup path can proceed.  Once exact owner/status evidence says
    # a healthy generation exists, every snapshot step is mandatory.
    if ! read_verified_pidfile || [ "$OWNER_STATE_PHASE" != active ]; then
        return 0
    fi
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
    read_install_generation_meta && [ "$OWNER_STATE_INSTALL_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] || return 1
    read_iptables_status || return 1
    [ "$STATUS_FILE_STATUS" = ok ] && [ "$STATUS_FILE_QNUM" = "$OWNER_STATE_QNUM" ] || return 1
    [ "$STATUS_FILE_RULESET_VERIFIED" = 1 ] && [ "$STATUS_FILE_OWNER_METADATA_VERIFIED" = 1 ] || return 1
    [ "$STATUS_FILE_IPV4_ACTIVE" = 1 ] || return 1
    case "$STATUS_FILE_IPV6_ACTIVE" in 0|1) ;; *) return 1 ;; esac
    expected_rules=$((OWNER_STATE_IPV4_RULES + OWNER_STATE_IPV6_RULES))
    [ "$STATUS_FILE_RULES_TOTAL" = "$expected_rules" ] || return 1
    [ "$STATUS_FILE_RULES_EXPECTED" = "$expected_rules" ] || return 1
    command -v iptables >/dev/null 2>&1 || return 1
    command -v cmp >/dev/null 2>&1 || return 1
    owner_family_generation_healthy iptables ipv4 || return 1
    if [ "$STATUS_FILE_IPV6_ACTIVE" = 1 ]; then
        command -v ip6tables >/dev/null 2>&1 || return 1
        owner_family_generation_healthy ip6tables ipv6 || return 1
    elif command -v ip6tables >/dev/null 2>&1; then
        owned_family_absent ip6tables || return 1
    fi
    HEALTH_PID="$VERIFIED_PID"; HEALTH_PID_START="$VERIFIED_PID_START"
    HEALTH_GENERATION="$OWNER_STATE_GENERATION"; HEALTH_IPV6="$STATUS_FILE_IPV6_ACTIVE"
    HEALTH_RULES="$expected_rules"
    PRIOR_GENERATION="$OWNER_STATE_GENERATION"
    PRIOR_IPV6="$STATUS_FILE_IPV6_ACTIVE"
    PRIOR_QNUM="$OWNER_STATE_QNUM"
    PRIOR_ARGV_SHA256="$OWNER_STATE_ARGV_SHA256"
    cp "$OWNER_STATE" "$PRIOR_OWNER_FILE" 2>/dev/null || return 1
    chmod 0600 "$PRIOR_OWNER_FILE" 2>/dev/null || { rm -f "$PRIOR_OWNER_FILE"; return 1; }
    state_path_is_managed_file "$PRIOR_ARGV_FILE" || return 1
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    tr '\000' '\n' < "/proc/$HEALTH_PID/cmdline" > "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    IFS= read -r first < "$tmp" || { rm -f "$tmp"; return 1; }
    [ "$first" = "$NFQWS2" ] || { rm -f "$tmp"; return 1; }
    size="$(wc -c < "$tmp" 2>/dev/null)" || { rm -f "$tmp"; return 1; }
    is_decimal "$size" && [ "$size" -gt 0 ] && [ "$size" -le "$RUNTIME_METADATA_MAX_BYTES" ] || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$PRIOR_ARGV_FILE" || { rm -f "$tmp"; return 1; }
    rules_tmp="$PRIOR_RULES_V4.tmp.$$"
    [ ! -e "$rules_tmp" ] && [ ! -L "$rules_tmp" ] || { discard_prior_snapshot; return 1; }
    { iptables -t mangle -S "$ZAPRET2_OUT"; iptables -t mangle -S "$ZAPRET2_IN"; } > "$rules_tmp" 2>/dev/null || { rm -f "$rules_tmp"; discard_prior_snapshot; return 1; }
    chmod 0600 "$rules_tmp" 2>/dev/null && mv -f "$rules_tmp" "$PRIOR_RULES_V4" || { rm -f "$rules_tmp"; discard_prior_snapshot; return 1; }
    if [ "$PRIOR_IPV6" = 1 ]; then
        rules_tmp="$PRIOR_RULES_V6.tmp.$$"
        [ ! -e "$rules_tmp" ] && [ ! -L "$rules_tmp" ] || { discard_prior_snapshot; return 1; }
        { ip6tables -t mangle -S "$ZAPRET2_OUT"; ip6tables -t mangle -S "$ZAPRET2_IN"; } > "$rules_tmp" 2>/dev/null || { rm -f "$rules_tmp"; discard_prior_snapshot; return 1; }
        chmod 0600 "$rules_tmp" 2>/dev/null && mv -f "$rules_tmp" "$PRIOR_RULES_V6" || { rm -f "$rules_tmp"; discard_prior_snapshot; return 1; }
    fi
    PRIOR_HEALTHY=1
}

restore_family_snapshot() {
    local tool="$1" file="$2" line chain spec
    [ -f "$file" ] && [ ! -L "$file" ] || return 1
    "$tool" -t mangle -N "$ZAPRET2_OUT" 2>/dev/null || return 1
    "$tool" -t mangle -N "$ZAPRET2_IN" 2>/dev/null || { destroy_owned_chain "$tool" "$ZAPRET2_OUT" >/dev/null 2>&1; return 1; }
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            "-N $ZAPRET2_OUT"|"-N $ZAPRET2_IN") continue ;;
            "-A $ZAPRET2_OUT "*) chain="$ZAPRET2_OUT"; spec="${line#"-A $ZAPRET2_OUT "}" ;;
            "-A $ZAPRET2_IN "*) chain="$ZAPRET2_IN"; spec="${line#"-A $ZAPRET2_IN "}" ;;
            *) return 1 ;;
        esac
        set -- $spec
        "$tool" -t mangle -A "$chain" "$@" 2>/dev/null || return 1
        "$tool" -t mangle -C "$chain" "$@" 2>/dev/null || return 1
    done < "$file"
    commit_family "$tool" || return 1
    [ "$(exact_anchor_count "$tool" OUTPUT "$ZAPRET2_OUT")" = 1 ] || return 1
    [ "$(exact_anchor_count "$tool" INPUT "$ZAPRET2_IN")" = 1 ] || return 1
    [ "$(owned_chain_reference_count "$tool" "$ZAPRET2_OUT")" = 1 ] || return 1
    [ "$(owned_chain_reference_count "$tool" "$ZAPRET2_IN")" = 1 ] || return 1
    verify_family_snapshot_exact "$tool" "$file"
}

verify_family_snapshot_exact() {
    local tool="$1" expected="$2" actual="$expected.verify.$$" rc=0
    state_path_is_managed_file "$actual" || return 1
    [ ! -e "$actual" ] && [ ! -L "$actual" ] || return 1
    { "$tool" -t mangle -S "$ZAPRET2_OUT"; "$tool" -t mangle -S "$ZAPRET2_IN"; } > "$actual" 2>/dev/null || {
        rm -f "$actual"
        return 1
    }
    chmod 0600 "$actual" 2>/dev/null || rc=1
    [ "$rc" -ne 0 ] || cmp -s "$expected" "$actual" 2>/dev/null || rc=1
    rm -f "$actual" 2>/dev/null || rc=1
    return "$rc"
}

restore_prior_healthy_generation() {
    local line first=1 candidate="" start="" n=0 restored_argv_sha256 live_owner
    [ "$PRIOR_HEALTHY" = 1 ] && [ "$PRIOR_TORN_DOWN" = 1 ] || return 1
    [ -f "$PRIOR_ARGV_FILE" ] && [ ! -L "$PRIOR_ARGV_FILE" ] || return 1
    set --
    while IFS= read -r line || [ -n "$line" ]; do
        if [ "$first" = 1 ]; then
            [ "$line" = "$NFQWS2" ] || return 1
            set -- "$line"
            first=0
        else
            [ -n "$line" ] || return 1
            set -- "$@" "$line"
        fi
    done < "$PRIOR_ARGV_FILE"
    [ "$#" -gt 1 ] || return 1
    cleanup_owned_firewall >/dev/null 2>&1 || return 1
    stop_all_exact_owned_nfqws >/dev/null 2>&1 || return 1
    scan_exact_owned_nfqws >/dev/null 2>&1
    [ -z "$OWNED_SCAN_PIDS" ] || return 1
    retire_owner_metadata >/dev/null 2>&1 || return 1
    QNUM="$PRIOR_QNUM"
    "$@" >/dev/null 2>&1 &
    LAUNCHED_PID=$!
    while [ "$n" -lt 10 ]; do
        candidate="$LAUNCHED_PID"
        if read_live_pidfile; then candidate="$LIVE_PIDFILE_PID"; fi
        start="$(proc_starttime "$candidate" 2>/dev/null)" || start=""
        if [ -n "$start" ] && verify_nfqws_pid "$candidate" "$start" "" "$QNUM"; then
            restored_argv_sha256="$(proc_cmdline_sha256 "$candidate" 2>/dev/null)" || restored_argv_sha256=""
            [ -n "$restored_argv_sha256" ] && [ "$restored_argv_sha256" = "$PRIOR_ARGV_SHA256" ] || return 1
            LAUNCHED_PID="$candidate"
            LAUNCHED_PID_START="$start"
            LAUNCHED_ARGV_SHA256="$restored_argv_sha256"
            write_numeric_pidfile "$candidate" || return 1
            live_owner="$OWNER_STATE"; OWNER_STATE="$PRIOR_OWNER_FILE"
            read_owner_state || { OWNER_STATE="$live_owner"; return 1; }
            OWNER_STATE="$live_owner"; owner_loaded_generation_for_write || return 1
            write_owner_state "$candidate" "$start" "$restored_argv_sha256" "$QNUM" "$PRIOR_GENERATION" active || return 1
            restore_family_snapshot iptables "$PRIOR_RULES_V4" || return 1
            if [ "$PRIOR_IPV6" = 1 ]; then
                restore_family_snapshot ip6tables "$PRIOR_RULES_V6" || return 1
            fi
            read_verified_pidfile || return 1
            [ "$VERIFIED_PID" = "$candidate" ] && [ "$VERIFIED_PID_START" = "$start" ] || return 1
            [ "$OWNER_STATE_QNUM" = "$PRIOR_QNUM" ] && [ "$OWNER_STATE_ARGV_SHA256" = "$PRIOR_ARGV_SHA256" ] || return 1
            verify_family_snapshot_exact iptables "$PRIOR_RULES_V4" || return 1
            [ "$PRIOR_IPV6" != 1 ] || verify_family_snapshot_exact ip6tables "$PRIOR_RULES_V6" || return 1
            HEALTH_PID="$VERIFIED_PID"; HEALTH_PID_START="$VERIFIED_PID_START"
            HEALTH_GENERATION="$PRIOR_GENERATION"; HEALTH_IPV6="$PRIOR_IPV6"
            HEALTH_RULES=$((OWNER_STATE_IPV4_RULES + OWNER_STATE_IPV6_RULES))
            PRIOR_TORN_DOWN=0
            NEW_PID_PUBLISHED=0
            LAUNCHED_PID=""
            return 0
        fi
        n=$((n + 1)); sleep 1
    done
    return 1
}

cleanup_probe_artifacts() {
    local rc=0
    if [ -n "$PROBE_TOOL" ] && command -v "$PROBE_TOOL" >/dev/null 2>&1; then
        cleanup_probe_track "$PROBE_TOOL" >/dev/null 2>&1 || rc=1
    fi
    [ "$rc" -ne 0 ] || PROBE_MUTATED=0
    return "$rc"
}

firewall_is_clean_after_rollback() {
    [ "${TEARDOWN_COMMIT_PROVEN:-0}" = 1 ] && return 0
    command -v iptables >/dev/null 2>&1 || return 1
    owned_family_absent iptables || return 1
    if command -v ip6tables >/dev/null 2>&1; then owned_family_absent ip6tables || return 1; fi
    if ! command -v ip6tables >/dev/null 2>&1 &&
       { [ "${IPV6_BUILT:-0}" = 1 ] || [ "${IPV6_ACTIVE:-0}" = 1 ] || [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ]; }; then
        return 1
    fi
    return 0
}

snapshot_owned_state() {
    SNAP_PID=""; SNAP_PID_START=""; SNAP_GENERATION=""; SNAP_PID_VERIFIED=0
    SNAP_IPV4=0; SNAP_IPV6=0; SNAP_RULES=0; SNAP_CHAINS=0; SNAP_ANCHORS=0
    if read_verified_pidfile; then
        SNAP_PID="$VERIFIED_PID"; SNAP_PID_START="$VERIFIED_PID_START"
        SNAP_GENERATION="$OWNER_STATE_GENERATION"; SNAP_PID_VERIFIED=1
    else
        scan_exact_owned_nfqws >/dev/null 2>&1
        SNAP_PID="$OWNED_SCAN_PIDS"
    fi
    if command -v iptables >/dev/null 2>&1 && owned_family_present iptables; then
        SNAP_IPV4=1
        SNAP_RULES=$((SNAP_RULES + $(count_family_rules iptables)))
        owned_chain_exists iptables "$ZAPRET2_OUT" && SNAP_CHAINS=$((SNAP_CHAINS + 1))
        owned_chain_exists iptables "$ZAPRET2_IN" && SNAP_CHAINS=$((SNAP_CHAINS + 1))
        SNAP_ANCHORS=$((SNAP_ANCHORS + $(exact_anchor_count iptables OUTPUT "$ZAPRET2_OUT") + $(exact_anchor_count iptables INPUT "$ZAPRET2_IN")))
    fi
    if command -v ip6tables >/dev/null 2>&1 && owned_family_present ip6tables; then
        SNAP_IPV6=1
        SNAP_RULES=$((SNAP_RULES + $(count_family_rules ip6tables)))
        owned_chain_exists ip6tables "$ZAPRET2_OUT" && SNAP_CHAINS=$((SNAP_CHAINS + 1))
        owned_chain_exists ip6tables "$ZAPRET2_IN" && SNAP_CHAINS=$((SNAP_CHAINS + 1))
        SNAP_ANCHORS=$((SNAP_ANCHORS + $(exact_anchor_count ip6tables OUTPUT "$ZAPRET2_OUT") + $(exact_anchor_count ip6tables INPUT "$ZAPRET2_IN")))
    elif ! command -v ip6tables >/dev/null 2>&1 &&
         { [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ] || [ "${IPV6_BUILT:-0}" = 1 ] || [ "${IPV6_ACTIVE:-0}" = 1 ]; }; then
        SNAP_IPV6=1
        DIAGNOSTICS="${DIAGNOSTICS}IPv6 owned-state presence cannot be disproved because ip6tables is unavailable; "
    fi
}

fail_start() {
    local message="$1" domain="${2:-LIFECYCLE}" code="${3:-LIFECYCLE_FAILED}"
    local stage="${4:-START}" retryable="${5:-0}" rollback_ready=1
    z2_error_set "$domain" "$code" "$stage" "$retryable" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED START 0
    if [ -n "$FIREWALL_FAILURE_DETAIL" ]; then
        message="$message; $FIREWALL_FAILURE_DETAIL"
    fi
    trap '' HUP INT TERM
    if ! rollback_legacy_migration; then
        rollback_ready=0
        message="$message; exact legacy-rule rollback failed; existing daemon retained"
    fi
    log_error "$message"
    if [ "$CONTROLLED_TEARDOWN_STARTED" = 0 ]; then
        if [ "$PROBE_MUTATED" = 1 ]; then
            cleanup_probe_artifacts >/dev/null 2>&1 || message="$message; disposable probe cleanup failed"
        fi
        discard_prior_snapshot
        release_lifecycle_lock
        trap - HUP INT TERM
        z2_error_emit_machine
        echo "ERROR: $message"
        exit 1
    fi
    if [ "$PROBE_MUTATED" = 1 ] || [ "$FIREWALL_MUTATED" = 1 ] || [ "$NEW_PID_PUBLISHED" = 1 ] || [ -n "$LAUNCHED_PID" ]; then
        rollback_start || {
            rollback_ready=0
            message="$message; rollback incomplete: $ROLLBACK_ERRORS"
        }
    fi
    if [ "$PRIOR_HEALTHY" = 1 ] && [ "$PRIOR_TORN_DOWN" = 1 ]; then
        if [ "$rollback_ready" = 1 ] && restore_prior_healthy_generation; then
            DIAGNOSTICS="${DIAGNOSTICS}replace failed; prior healthy generation restored; "
            write_ok_status "$HEALTH_RULES" "$HEALTH_PID" "$HEALTH_IPV6" >/dev/null 2>&1 || true
            discard_prior_snapshot
            release_lifecycle_lock
            trap - HUP INT TERM
            z2_error_emit_machine
            echo "ERROR: $message; prior healthy service restored"
            exit 1
        fi
        cleanup_owned_firewall >/dev/null 2>&1 || true
        if ! stop_pidfile_process >/dev/null 2>&1; then
            stop_failed_fallback_launch "$LAUNCHED_PID" >/dev/null 2>&1 || true
        fi
        retire_owner_metadata >/dev/null 2>&1 || true
        message="$message; prior healthy service restoration failed"
    fi
    set_owner_phase error >/dev/null 2>&1 || true
    snapshot_owned_state
    restore_status_facts
    STATUS_RULES_OK=0; STATUS_RULES_FAIL=1; STATUS_RULES_TOTAL="$SNAP_RULES"
    STATUS_ERRORS="$message"; STATUS_OWN_PID="$SNAP_PID"; STATUS_PID_VERIFIED="$SNAP_PID_VERIFIED"; STATUS_QNUM="${QNUM:-${STATUS_QNUM:-}}"
    STATUS_OWN_PID_STARTTIME="$SNAP_PID_START"; STATUS_OWNER_GENERATION="$SNAP_GENERATION"
    STATUS_OWNER_METADATA_VERIFIED="$SNAP_PID_VERIFIED"; STATUS_RULESET_VERIFIED=0; STATUS_RULES_EXPECTED=0
    STATUS_IPV4_ACTIVE="$SNAP_IPV4"; STATUS_IPV6_ACTIVE="$SNAP_IPV6"; STATUS_CHAINS="$SNAP_CHAINS"; STATUS_ANCHORS="$SNAP_ANCHORS"
    STATUS_IPV4_RULES=0; STATUS_IPV6_RULES=0
    [ -n "${IPV4_NFQUEUE:-}" ] && STATUS_NFQUEUE_SUPPORTED="$IPV4_NFQUEUE"
    [ -n "${IPV4_QUEUE_BYPASS:-}" ] && STATUS_QUEUE_BYPASS_SUPPORTED="$IPV4_QUEUE_BYPASS"
    [ -n "${IPV4_CONNBYTES:-}" ] && STATUS_CONNBYTES_SUPPORTED="$IPV4_CONNBYTES"
    [ -n "${IPV4_MULTIPORT:-}" ] && STATUS_MULTIPORT_SUPPORTED="$IPV4_MULTIPORT"
    [ -n "${IPV4_MARK:-}" ] && STATUS_MARK_SUPPORTED="$IPV4_MARK"
    STATUS_FALLBACK_MODE=0
    STATUS_ERROR_DOMAIN="$Z2_ERROR_DOMAIN"; STATUS_ERROR_CODE="$Z2_ERROR_CODE"
    STATUS_ERROR_STAGE="$Z2_ERROR_STAGE"; STATUS_ERROR_RETRYABLE="$Z2_ERROR_RETRYABLE"
    STATUS_DIAGNOSTICS="$DIAGNOSTICS"
    write_iptables_status error >/dev/null 2>&1 || true
    discard_prior_snapshot
    release_lifecycle_lock
    trap - HUP INT TERM
    z2_error_emit_machine
    echo "ERROR: $message"
    exit 1
}

handle_signal() {
    local signal="$1"
    fail_start "start interrupted by $signal" LIFECYCLE LIFECYCLE_FAILED START_SIGNAL 1
}

probe_family() {
    local tool="$1" result
    PROBE_NFQUEUE=0; PROBE_QUEUE_BYPASS=0; PROBE_CONNBYTES=0; PROBE_MULTIPORT=0; PROBE_MARK=0
    command -v "$tool" >/dev/null 2>&1 || return 2
    "$tool" -t mangle -L OUTPUT -n >/dev/null 2>&1 || return 2
    PROBE_MUTATED=1; PROBE_TOOL="$tool"
    begin_probe_track "$tool" || return 1
    BUILD_TRACK_TOOL="$tool"
    if ! tracked_create_chain "$tool" "$ZAPRET2_PROBE"; then cleanup_probe_track "$tool" >/dev/null 2>&1; return 1; fi
    PROBE_CHAIN_RECORD_ID="$BUILD_TRACK_RECORD_ID"

    tracked_probe_append "$tool" queue_bypass; result=$?
    if [ "$result" = 0 ]; then
        PROBE_NFQUEUE=1; PROBE_QUEUE_BYPASS=1
        consume_probe_rule "$tool" "$PROBE_RULE_RECORD_ID" queue_bypass "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || return 1
    elif [ "$result" = 2 ]; then
        tracked_probe_append "$tool" queue; result=$?
        if [ "$result" = 0 ]; then
            PROBE_NFQUEUE=1
            consume_probe_rule "$tool" "$PROBE_RULE_RECORD_ID" queue "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || return 1
        elif [ "$result" != 2 ]; then
            return 1
        fi
    else
        return 1
    fi
    tracked_probe_append "$tool" connbytes; result=$?
    if [ "$result" = 0 ]; then PROBE_CONNBYTES=1; consume_probe_rule "$tool" "$PROBE_RULE_RECORD_ID" connbytes "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || return 1
    elif [ "$result" != 2 ]; then return 1; fi
    tracked_probe_append "$tool" multiport; result=$?
    if [ "$result" = 0 ]; then PROBE_MULTIPORT=1; consume_probe_rule "$tool" "$PROBE_RULE_RECORD_ID" multiport "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || return 1
    elif [ "$result" != 2 ]; then return 1; fi
    tracked_probe_append "$tool" mark; result=$?
    if [ "$result" = 0 ]; then PROBE_MARK=1; consume_probe_rule "$tool" "$PROBE_RULE_RECORD_ID" mark "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || return 1
    elif [ "$result" != 2 ]; then return 1; fi
    consume_probe_chain "$tool" "$PROBE_CHAIN_RECORD_ID" || return 1
    cleanup_probe_track "$tool" || return 1
    PROBE_MUTATED=0; PROBE_TOOL=""
    return 0
}

append_nfqueue_rule() {
    local tool="$1" parent="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7" id chain ordinal
    if [ "$parent" = "$ZAPRET2_OUT" ]; then BUILD_OUT_RULES=$((BUILD_OUT_RULES + 1)); ordinal="$BUILD_OUT_RULES"
    else BUILD_IN_RULES=$((BUILD_IN_RULES + 1)); ordinal="$BUILD_IN_RULES"; fi
    owner_rule_chain "$parent" "$ordinal" || return 1; chain="$OWNER_RULE_CHAIN"
    tracked_create_chain "$tool" "$chain" || return 1
    tracked_append_anchor "$tool" "$parent" "$chain" || return 1
    build_track_add_pending "rule|$chain|$proto|$direction|$ports|$packet_count|$cb_dir|$QNUM|$DESYNC_MARK|$BUILD_CONNBYTES|$BUILD_MULTIPORT|$BUILD_MARK" || return 1
    id="$BUILD_TRACK_RECORD_ID"
    set -- -t mangle -A "$chain" -p "$proto"
    if [ "$BUILD_MULTIPORT" = 1 ]; then
        if [ "$direction" = out ]; then set -- "$@" -m multiport --dports "$ports"
        else set -- "$@" -m multiport --sports "$ports"; fi
    else
        if [ "$direction" = out ]; then set -- "$@" --dport "$ports"
        else set -- "$@" --sport "$ports"; fi
    fi
    if [ "$BUILD_CONNBYTES" = 1 ]; then
        set -- "$@" -m connbytes --connbytes "1:$packet_count" \
            --connbytes-dir "$cb_dir" --connbytes-mode packets
    fi
    if [ "$BUILD_MARK" = 1 ]; then
        set -- "$@" -m mark ! --mark "$DESYNC_MARK/$DESYNC_MARK"
    fi
    set -- "$@" -j NFQUEUE --queue-num "$QNUM" --queue-bypass
    if ! run_firewall_mutation BUILD_RULE "$tool" "$@"; then
        build_track_transition "$id" pending consumed || true
        return 1
    fi
    build_track_transition "$id" pending applied || return 1
    BUILD_RULES=$((BUILD_RULES + 1))
}

append_port_set() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    local old_ifs item
    [ -n "$ports" ] || return 0
    if [ "$BUILD_MULTIPORT" = 1 ]; then
        append_nfqueue_rule "$tool" "$chain" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir"
        return $?
    fi
    old_ifs="$IFS"; IFS=,; set -- $ports; IFS="$old_ifs"
    for item in "$@"; do
        append_nfqueue_rule "$tool" "$chain" "$proto" "$direction" "$item" "$packet_count" "$cb_dir" || return 1
    done
}

build_detached_family() {
    local tool="$1"
    BUILD_RULES=0; BUILD_OUT_RULES=0; BUILD_IN_RULES=0
    begin_tracked_family "$tool" || return 1
    BUILD_TRACK_TOOL="$tool"
    tracked_create_chain "$tool" "$ZAPRET2_OUT" || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    tracked_create_chain "$tool" "$ZAPRET2_IN" || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    append_port_set "$tool" "$ZAPRET2_OUT" tcp out "$PORTS_TCP" "$PKT_OUT" original || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    append_port_set "$tool" "$ZAPRET2_OUT" udp out "$PORTS_UDP" "$PKT_OUT" original || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    append_port_set "$tool" "$ZAPRET2_IN" tcp in "$PORTS_TCP" "$PKT_IN" reply || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    append_port_set "$tool" "$ZAPRET2_IN" udp in "$PORTS_UDP" "$PKT_IN" reply || { cleanup_tracked_family "$tool" >/dev/null 2>&1; return 1; }
    return 0
}

launch_nfqws2() {
    local daemon_supported=0 candidate n=0 start
    compiled_source_binding_current || return 1
    nfqws_daemon_mode_supported && daemon_supported=1
    [ ! -e "$PIDFILE" ] && [ ! -L "$PIDFILE" ] || return 1
    prepare_private_runtime_file "$STARTUP_LOG" || return 1
    prepare_private_runtime_file "$ERROR_LOG" || return 1
    if [ "$daemon_supported" = 1 ]; then
        LAUNCH_OWNS_PIDFILE=1
        run_compiled_artifact "$COMPILED_ARGV_FILE" daemon || return 1
    else
        DIAGNOSTICS="${DIAGNOSTICS}daemon options unavailable; using supervised background pid; "
        run_compiled_artifact "$COMPILED_ARGV_FILE" background || return 1
    fi
    LAUNCHED_PID_START="$(proc_starttime "$LAUNCHED_PID" 2>/dev/null)" || LAUNCHED_PID_START=""
    if [ -n "$LAUNCHED_PID_START" ]; then
        LAUNCHED_ARGV_SHA256="$(proc_cmdline_sha256 "$LAUNCHED_PID" 2>/dev/null)" || LAUNCHED_ARGV_SHA256=""
    fi
    while [ "$n" -lt 10 ]; do
        candidate="$LAUNCHED_PID"
        if [ "$daemon_supported" = 1 ]; then
            if read_live_pidfile; then candidate="$LIVE_PIDFILE_PID"; else candidate=""; fi
        fi
        if [ -n "$candidate" ]; then
            start="$(proc_starttime "$candidate")" || start=""
            if [ -n "$start" ] && verify_nfqws_pid "$candidate" "$start" "" "$QNUM"; then
                if ! publish_nfqws_owner "$candidate" "$VERIFIED_STARTTIME" "$QNUM" launched; then return 1; fi
                NEW_PID_PUBLISHED=1
                STARTED_PID="$candidate"; STARTED_PID_START="$VERIFIED_STARTTIME"
                return 0
            fi
        fi
        n=$((n + 1)); sleep 1
    done
    return 1
}

stop_failed_fallback_launch() {
    local pid="$1" start argv_sha256 rc=0
    [ -n "$pid" ] || return 0
    start="${LAUNCHED_PID_START:-}"
    argv_sha256="${LAUNCHED_ARGV_SHA256:-}"
    if [ -n "$start" ] && verify_nfqws_pid "$pid" "$start" "$argv_sha256" "$QNUM"; then
        stop_verified_nfqws_pid "$pid" "$start" "$argv_sha256" "$QNUM" >/dev/null 2>&1 || rc=1
    elif kill -0 "$pid" 2>/dev/null && [ "$(proc_starttime "$pid" 2>/dev/null)" = "$start" ]; then
        rc=1
    fi
    # A daemon may have forked before publishing a usable PID file.  Exact
    # argv0/executable scanning is the mandatory second rollback identity.
    stop_all_exact_owned_nfqws >/dev/null 2>&1 || rc=1
    if [ "$LAUNCH_OWNS_PIDFILE" = 1 ] && state_file_is_secure "$PIDFILE"; then
        rm -f "$PIDFILE" 2>/dev/null || rc=1
    fi
    return "$rc"
}

commit_family() {
    local tool="$1"
    build_track_for_tool "$tool" || return 1
    BUILD_TRACK_TOOL="$tool"
    tracked_append_anchor "$tool" OUTPUT "$ZAPRET2_OUT" || return 1
    tracked_append_anchor "$tool" INPUT "$ZAPRET2_IN" || return 1
    return 0
}

main() {
    REPAIR_RUNTIME_ONLY=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --replace) REPLACE=1 ;;
            --repair-runtime-only) REPAIR_RUNTIME_ONLY=1 ;;
            *) echo "ERROR: unknown argument: $1"; exit 2 ;;
        esac
        shift
    done
    ensure_state_dir ||
        start_error_exit STATE STATE_UNAVAILABLE START_STATE 0 \
            "insecure or unavailable zapret2 state directory: $STATE_DIR"
    acquire_lifecycle_lock ||
        start_error_exit LIFECYCLE LIFECYCLE_BUSY START_LOCK 1 "zapret2 lifecycle is busy"
    if [ -e "$MODDIR/disable" ] || [ -L "$MODDIR/disable" ]; then
        release_lifecycle_lock
        start_error_exit LIFECYCLE MODULE_DISABLED START_PREFLIGHT 0 \
            "start blocked because the module is disabled; re-enable it in the root manager first"
    fi
    if module_removal_pending; then
        release_lifecycle_lock
        start_error_exit LIFECYCLE MODULE_REMOVAL_PENDING START_PREFLIGHT 0 \
            "start blocked because Magisk scheduled the module for removal"
    fi
    # Update serialization is checked before log rotation, status writes,
    # configuration migration, probes, or any other lifecycle mutation.
    if ! update_lock_allows_start; then
        message="start blocked by update serialization: $UPDATE_LOCK_ERROR"
        release_lifecycle_lock
        start_error_exit UPDATE UPDATE_BLOCKED START_UPDATE 1 "$message"
    fi
    if ! audit_recovery_artifacts lifecycle; then
        release_lifecycle_lock
        start_error_exit LIFECYCLE RECOVERY_BLOCKED START_RECOVERY 0 \
            "${RECOVERY_ARTIFACT_DIAGNOSTIC:-recovery artifacts block start}"
    fi
    if ! uninstall_tombstone_allows_start; then
        message="start blocked by uninstall serialization: $UNINSTALL_TOMBSTONE_ERROR"
        release_lifecycle_lock
        start_error_exit LIFECYCLE UNINSTALL_BLOCKED START_UNINSTALL 1 "$message"
    fi

    # Authenticate the installer-owned generation before status/log/config or
    # firewall mutation. A malformed or replaced generation can never start a
    # teardown transaction.
    if ! read_install_generation_meta; then
        release_lifecycle_lock
        start_error_exit STATE STATE_UNAVAILABLE START_GENERATION 0 \
            "install generation metadata is missing, unsafe, or malformed"
    fi
    if [ "$REPAIR_RUNTIME_ONLY" = 1 ]; then
        if load_effective_core_config; then
            message="$(runtime_config_status_message)"
            release_lifecycle_lock || { echo "ERROR: runtime repair completed but lifecycle lock release failed"; exit 1; }
            echo "$message"
            exit 0
        fi
        message="runtime.ini repair failed: ${RUNTIME_CONFIG_ERROR:-invalid configuration}"
        release_lifecycle_lock >/dev/null 2>&1 || true
        echo "ERROR: $message"
        exit 1
    fi
    trap 'handle_signal HUP' HUP
    trap 'handle_signal INT' INT
    trap 'handle_signal TERM' TERM

    # Capture an independently verified old generation before reading the new
    # configuration, rotating logs, probing firewall capabilities, publishing
    # command metadata, or performing any other fallible lifecycle work.
    capture_prior_healthy_generation ||
        fail_start "cannot snapshot prior healthy generation before preflight" \
            LIFECYCLE PREFLIGHT_FAILED START_SNAPSHOT 0

    write_runtime_owner_marker ||
        fail_start "cannot publish secure runtime ownership marker" STATE STATE_UNAVAILABLE START_OWNER 0

    if ! prepare_lifecycle_log; then
        LOG_READY=0
        DIAGNOSTICS="lifecycle log unavailable or unsafe; "
        if command -v log >/dev/null 2>&1; then log -p w -t Zapret2 "Lifecycle file logging disabled: unsafe or unavailable path" 2>/dev/null; fi
    fi
    restore_status_facts

    [ -n "$UPDATE_LOCK_DIAGNOSTIC" ] && DIAGNOSTICS="${DIAGNOSTICS}${UPDATE_LOCK_DIAGNOSTIC}; "

    load_config ||
        fail_start "configuration load failed: ${RUNTIME_CONFIG_ERROR:-invalid configuration}" \
            CONFIG CONFIG_INVALID START_CONFIG 0
    validate_config ||
        fail_start "invalid core firewall configuration" CONFIG CONFIG_INVALID START_CONFIG 0
    preflight_wifi_only ||
        fail_start "WIFI_ONLY cannot be safely scoped to a verified Wi-Fi interface" \
            CONFIG PREFLIGHT_FAILED START_WIFI 0
    legacy_migrate_firewall ||
        fail_start "legacy firewall migration blocked: $LEGACY_MIGRATION_ERROR" \
            FIREWALL FIREWALL_CLEANUP_FAILED START_LEGACY 0
    [ "$LEGACY_MIGRATION_VERIFIED" = 1 ] ||
        fail_start "legacy firewall migration did not reach a verified commit" \
            FIREWALL POSTCONDITION_FAILED START_LEGACY 0

    if [ "$REPLACE" = 0 ] && normal_health_ok; then
        DIAGNOSTICS="already healthy; no process or firewall churn"
        write_ok_status "$HEALTH_RULES" "$HEALTH_PID" "$HEALTH_IPV6" || fail_start "cannot write lifecycle status"
        discard_prior_snapshot
        release_lifecycle_lock; trap - HUP INT TERM
        echo "Zapret2 is already running (PID: $HEALTH_PID)"
        exit 0
    fi

    prepare_options ||
        fail_start "nfqws2 preflight/dry-run failed" CONFIG PREFLIGHT_FAILED START_NFQWS_PREFLIGHT 0

    probe_family iptables ||
        fail_start "mandatory IPv4 NFQUEUE probe failed" \
            FIREWALL FIREWALL_PROBE_FAILED START_PROBE_IPV4 0
    IPV4_NFQUEUE="$PROBE_NFQUEUE"; IPV4_QUEUE_BYPASS="$PROBE_QUEUE_BYPASS"
    IPV4_CONNBYTES="$PROBE_CONNBYTES"; IPV4_MULTIPORT="$PROBE_MULTIPORT"; IPV4_MARK="$PROBE_MARK"
    [ "$IPV4_NFQUEUE" = 1 ] && [ "$IPV4_QUEUE_BYPASS" = 1 ] || fail_start "IPv4 NFQUEUE --queue-bypass is required"
    [ "$IPV4_CONNBYTES" = 1 ] || fail_start "IPv4 connbytes packet limiter is required for safe queueing"
    [ "$IPV4_MULTIPORT" = 1 ] || fail_start "IPv4 multiport match is required for exact scoped queueing"
    [ "$IPV4_MARK" = 1 ] || fail_start "IPv4 mark match is required to prevent NFQUEUE recirculation"
    FALLBACK_MODE=0

    IPV6_AVAILABLE=0
    probe_family ip6tables
    case $? in
        0)
            if [ "$PROBE_NFQUEUE" = 1 ] && [ "$PROBE_QUEUE_BYPASS" = 1 ] &&
               [ "$PROBE_CONNBYTES" = 1 ] && [ "$PROBE_MULTIPORT" = 1 ] && [ "$PROBE_MARK" = 1 ]; then
                IPV6_AVAILABLE=1; IPV6_CONNBYTES="$PROBE_CONNBYTES"; IPV6_MULTIPORT="$PROBE_MULTIPORT"; IPV6_MARK="$PROBE_MARK"
            else DIAGNOSTICS="${DIAGNOSTICS}IPv6 mandatory queue safety capability unavailable; IPv6 skipped; "; fi
            ;;
        2) DIAGNOSTICS="${DIAGNOSTICS}IPv6 unavailable; IPv6 skipped; " ;;
        *) fail_start "IPv6 detached probe cleanup failed" \
               FIREWALL FIREWALL_PROBE_FAILED START_PROBE_IPV6 0 ;;
    esac

    # Only now may a failure roll back module-owned process/chains.  Earlier
    # failures preserve the old owner/status generation exactly.
    CONTROLLED_TEARDOWN_STARTED=1
    FIREWALL_MUTATED=1
    cleanup_owned_firewall ||
        fail_start "cannot remove exact owned firewall artifacts" \
            FIREWALL FIREWALL_CLEANUP_FAILED START_CLEANUP 0
    stop_pidfile_process ||
        fail_start "cannot stop verified previous nfqws2 process" \
            PROCESS PROCESS_STOP_FAILED START_CLEANUP 0
    [ "$PRIOR_HEALTHY" != 1 ] || PRIOR_TORN_DOWN=1
    OWNER_WRITE_READY=0; OWNER_WRITE_QNUM=""; OWNER_WRITE_SOURCE_GENERATION=""
    prepare_new_firewall_identity ||
        fail_start "cannot allocate stable firewall generation identity" \
            FIREWALL PREFLIGHT_FAILED START_IDENTITY 0

    BUILD_CONNBYTES="$IPV4_CONNBYTES"; BUILD_MULTIPORT="$IPV4_MULTIPORT"; BUILD_MARK="$IPV4_MARK"
    build_detached_family iptables ||
        fail_start "cannot build detached IPv4 chains" \
            FIREWALL FIREWALL_BUILD_FAILED "START_IPV4_${FIREWALL_FAILURE_STAGE:-BUILD}" 1
    IPV4_RULES="$BUILD_RULES"; IPV4_BUILT=1

    if [ "$IPV6_AVAILABLE" = 1 ]; then
        BUILD_CONNBYTES="$IPV6_CONNBYTES"; BUILD_MULTIPORT="$IPV6_MULTIPORT"; BUILD_MARK="$IPV6_MARK"
        if build_detached_family ip6tables; then
            IPV6_RULES="$BUILD_RULES"; IPV6_BUILT=1
        else
            owned_family_absent ip6tables || fail_start "IPv6 chain build and tracked cleanup failed"
            DIAGNOSTICS="${DIAGNOSTICS}IPv6 chain build failed; IPv6 skipped; "
        fi
    fi

    launch_nfqws2 ||
        fail_start "nfqws2 did not produce a verified module-owned PID" \
            PROCESS PROCESS_LAUNCH_FAILED START_LAUNCH 1
    commit_family iptables ||
        fail_start "mandatory IPv4 anchor commit failed" \
            FIREWALL FIREWALL_COMMIT_FAILED START_COMMIT_IPV4 1
    IPV4_ACTIVE=1
    IPV6_ACTIVE=0
    if [ "$IPV6_BUILT" = 1 ]; then
        if commit_family ip6tables; then IPV6_ACTIVE=1
        else
            cleanup_tracked_family ip6tables >/dev/null 2>&1 || fail_start "IPv6 commit failed and tracked state could not be cleaned"
            owned_family_absent ip6tables || fail_start "IPv6 commit failure left owned state"
            republish_owner_ipv6_inactive || fail_start "IPv6 skip could not atomically republish the active owner generation"
            IPV6_RULES=0; IPV6_BUILT=0
            DIAGNOSTICS="${DIAGNOSTICS}IPv6 commit failed; verified clean and skipped; "
        fi
    fi

    set_owner_phase active ||
        fail_start "cannot mark verified nfqws2 owner active" \
            PROCESS POSTCONDITION_FAILED START_VERIFY 0
    normal_health_ok ||
        fail_start "post-commit ownership/ruleset verification failed" \
            LIFECYCLE POSTCONDITION_FAILED START_VERIFY 0
    [ "$HEALTH_PID" = "$STARTED_PID" ] && [ "$HEALTH_PID_START" = "$STARTED_PID_START" ] ||
        fail_start "post-commit PID identity changed"
    [ "$HEALTH_IPV6" = "$IPV6_ACTIVE" ] || fail_start "post-commit IPv6 state mismatch"

    TOTAL_RULES=$((IPV4_RULES + IPV6_RULES))
    write_ok_status "$TOTAL_RULES" "$STARTED_PID" "$IPV6_ACTIVE" || fail_start "cannot atomically write lifecycle status"
    discard_build_tracks || fail_start "cannot retire committed detached-build tracking"
    FIREWALL_MUTATED=0
    LAUNCHED_PID=""
    discard_prior_snapshot
    release_lifecycle_lock; trap - HUP INT TERM
    log_msg "Zapret2 started with verified PID $STARTED_PID"
    echo "Zapret2 started (PID: $STARTED_PID)"
    exit 0
}

main "$@"
