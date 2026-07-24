#!/system/bin/sh
# Idempotent zapret2 start/replace lifecycle.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"
MODDIR="$(dirname "$ZAPRET_DIR")"
. "$SCRIPT_DIR/common.sh"
. "$SCRIPT_DIR/command-builder.sh"

set -f
REPLACE=0
CONTROLLED_TEARDOWN_STARTED=0
FIREWALL_MUTATED=0
NEW_PID_PUBLISHED=0
LAUNCHED_PID=""
LAUNCHED_PID_START=""
LAUNCHED_ARGV_SHA256=""
LAUNCH_OWNS_PIDFILE=0
IPV4_BUILT=0
IPV6_BUILT=0
IPV4_RULES=0
IPV6_RULES=0
DIAGNOSTICS=""

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
    local domain="$1" code="$2" stage="$3" message="$5"
    z2_error_set "$domain" "$code" "$stage" "$message" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED START "$message"
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
    TCP_PKT_OUT="$COMPILED_TCP_PKT_OUT"
    TCP_PKT_IN="$COMPILED_TCP_PKT_IN"
    UDP_PKT_OUT="$COMPILED_UDP_PKT_OUT"
    UDP_PKT_IN="$COMPILED_UDP_PKT_IN"
    # Legacy direct-rule recovery predates protocol-specific capture policy.
    PKT_OUT="$TCP_PKT_OUT"
    PKT_IN="$TCP_PKT_IN"
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
    STATUS_CHAINS=$((1 + IPV4_CONNBYTES + STATUS_IPV6_ACTIVE * (1 + IPV6_CONNBYTES)))
    STATUS_ANCHORS="$STATUS_CHAINS"
    STATUS_NFQUEUE_SUPPORTED=1; STATUS_QUEUE_BYPASS_SUPPORTED=1
    STATUS_CONNBYTES_SUPPORTED="${IPV4_CONNBYTES:-1}"
    STATUS_MULTIPORT_SUPPORTED="${IPV4_MULTIPORT:-1}"
    STATUS_MARK_SUPPORTED="${IPV4_MARK:-1}"
    STATUS_FALLBACK_MODE="${FALLBACK_MODE:-0}"
    STATUS_ERROR_STATUS=OK; STATUS_ERROR_DOMAIN=NONE; STATUS_ERROR_CODE=NONE
    STATUS_ERROR_STAGE=NONE; STATUS_ERROR_DETAIL=""
    STATUS_DIAGNOSTICS="$DIAGNOSTICS"
    write_iptables_status ok
}

rollback_start() {
    local rc=0
    ROLLBACK_ERRORS=""
    if [ "$FIREWALL_MUTATED" = 1 ]; then
        cleanup_owned_firewall >/dev/null 2>&1 ||
            { rc=1; ROLLBACK_ERRORS="stable firewall namespace cleanup failed"; }
    fi
    if [ "$NEW_PID_PUBLISHED" = 1 ]; then
        stop_pidfile_process >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }owned process cleanup failed"; }
    elif [ -n "$LAUNCHED_PID" ]; then
        stop_failed_fallback_launch "$LAUNCHED_PID" >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }failed launch process remains ambiguous"; }
    fi
    firewall_is_clean_after_rollback || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }owned firewall artifacts remain"; }
    scan_exact_owned_nfqws >/dev/null 2>&1
    [ -z "$OWNED_SCAN_PIDS" ] || { rc=1; ROLLBACK_ERRORS="${ROLLBACK_ERRORS}${ROLLBACK_ERRORS:+; }module-owned process remains: $OWNED_SCAN_PIDS"; }
    [ "$rc" -ne 0 ] || retire_owner_metadata >/dev/null 2>&1 || { rc=1; ROLLBACK_ERRORS="ownership metadata cleanup failed"; }
    return "$rc"
}

firewall_is_clean_after_rollback() {
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
        iptables -t mangle -S "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
            SNAP_CHAINS=$((SNAP_CHAINS + 1))
        iptables -t mangle -S "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 &&
            SNAP_CHAINS=$((SNAP_CHAINS + 1))
        iptables -t mangle -C OUTPUT -j "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
            SNAP_ANCHORS=$((SNAP_ANCHORS + 1))
        iptables -t mangle -C INPUT -j "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 &&
            SNAP_ANCHORS=$((SNAP_ANCHORS + 1))
    fi
    if command -v ip6tables >/dev/null 2>&1 && owned_family_present ip6tables; then
        SNAP_IPV6=1
        SNAP_RULES=$((SNAP_RULES + $(count_family_rules ip6tables)))
        ip6tables -t mangle -S "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
            SNAP_CHAINS=$((SNAP_CHAINS + 1))
        ip6tables -t mangle -S "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 &&
            SNAP_CHAINS=$((SNAP_CHAINS + 1))
        ip6tables -t mangle -C OUTPUT -j "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
            SNAP_ANCHORS=$((SNAP_ANCHORS + 1))
        ip6tables -t mangle -C INPUT -j "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 &&
            SNAP_ANCHORS=$((SNAP_ANCHORS + 1))
    elif ! command -v ip6tables >/dev/null 2>&1 &&
         { [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ] || [ "${IPV6_BUILT:-0}" = 1 ] || [ "${IPV6_ACTIVE:-0}" = 1 ]; }; then
        SNAP_IPV6=1
        DIAGNOSTICS="${DIAGNOSTICS}IPv6 owned-state presence cannot be disproved because ip6tables is unavailable; "
    fi
}

fail_start() {
    local message="$1" domain="${2:-LIFECYCLE}" code="${3:-LIFECYCLE_FAILED}"
    local stage="${4:-START}"
    z2_error_set "$domain" "$code" "$stage" "$message" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED START "$message"
    trap '' HUP INT TERM
    if ! rollback_legacy_migration; then
        message="$message; exact legacy-rule rollback failed; existing daemon retained"
    fi
    log_error "$message"
    if [ "$CONTROLLED_TEARDOWN_STARTED" = 1 ] &&
       { [ "$FIREWALL_MUTATED" = 1 ] || [ "$NEW_PID_PUBLISHED" = 1 ] ||
         [ -n "$LAUNCHED_PID" ]; }; then
        rollback_start ||
            message="$message; rollback incomplete: $ROLLBACK_ERRORS"
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
    STATUS_FALLBACK_MODE="${FALLBACK_MODE:-0}"
    z2_error_set "$domain" "$code" "$stage" "$message" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED START "$message"
    STATUS_ERROR_STATUS="$Z2_ERROR_STATUS"
    STATUS_ERROR_DOMAIN="$Z2_ERROR_DOMAIN"; STATUS_ERROR_CODE="$Z2_ERROR_CODE"
    STATUS_ERROR_STAGE="$Z2_ERROR_STAGE"; STATUS_ERROR_DETAIL="$Z2_ERROR_DETAIL"
    STATUS_DIAGNOSTICS="$DIAGNOSTICS"
    write_iptables_status error >/dev/null 2>&1 || true
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

    write_runtime_owner_marker ||
        fail_start "cannot publish secure runtime ownership marker" STATE STATE_UNAVAILABLE START_OWNER 0

    if ! prepare_lifecycle_log; then
        LOG_READY=0
        DIAGNOSTICS="lifecycle log unavailable or unsafe; "
        if command -v log >/dev/null 2>&1; then log -p w -t Zapret2 "Lifecycle file logging disabled: unsafe or unavailable path" 2>/dev/null; fi
    fi
    restore_status_facts

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
        release_lifecycle_lock; trap - HUP INT TERM
        echo "Zapret2 is already running (PID: $HEALTH_PID)"
        exit 0
    fi

    prepare_options ||
        fail_start "nfqws2 preflight/dry-run failed" CONFIG PREFLIGHT_FAILED START_NFQWS_PREFLIGHT 0

    command -v z2_fw_reconcile_family >/dev/null 2>&1 ||
        fail_start "firewall reconciler is unavailable" \
            FIREWALL FIREWALL_BACKEND_UNAVAILABLE START_FIREWALL_BACKEND 0
    z2_fw_restore_available iptables ||
        fail_start "iptables-restore is required by the Android firewall backend" \
            FIREWALL FIREWALL_BACKEND_UNAVAILABLE START_FIREWALL_BACKEND 0
    audit_owned_firewall_for_cleanup "$QNUM" ||
        fail_start "stable firewall namespace cleanup is unsafe: $FIREWALL_CLEANUP_PREFLIGHT_ERROR" \
            FIREWALL FIREWALL_CLEANUP_FAILED START_CLEANUP 0

    # From this point failures converge to the clean stopped state. Kernel
    # firewall state is derived entirely from the validated preset and is
    # never restored from a boot-local transaction journal.
    CONTROLLED_TEARDOWN_STARTED=1
    FIREWALL_MUTATED=1
    cleanup_owned_firewall ||
        fail_start "cannot clean the stable Zapret2 firewall namespace" \
            FIREWALL FIREWALL_CLEANUP_FAILED START_CLEANUP 0
    stop_pidfile_process ||
        fail_start "cannot stop verified previous nfqws2 process" \
            PROCESS PROCESS_STOP_FAILED START_CLEANUP 0
    OWNER_WRITE_READY=0; OWNER_WRITE_QNUM=""; OWNER_WRITE_SOURCE_GENERATION=""
    prepare_new_firewall_identity ||
        fail_start "cannot initialize stable firewall ownership" \
            FIREWALL PREFLIGHT_FAILED START_IDENTITY 0

    IPV4_NFQUEUE=1; IPV4_QUEUE_BYPASS=1; IPV4_MULTIPORT=1; IPV4_MARK=1
    z2_fw_reconcile_family iptables ||
        fail_start "atomic IPv4 firewall publication failed" \
            FIREWALL FIREWALL_BUILD_FAILED START_FIREWALL_IPV4 1
    IPV4_CONNBYTES="$Z2_FW_CONNBYTES"
    IPV4_RULES="$Z2_FW_RULES"; IPV4_BUILT=1; IPV4_ACTIVE=1
    FALLBACK_MODE=0
    if [ "$IPV4_CONNBYTES" != 1 ]; then
        FALLBACK_MODE=1
        DIAGNOSTICS="${DIAGNOSTICS}IPv4 connbytes unavailable; using outgoing-only interception; "
    fi

    IPV6_ACTIVE=0; IPV6_BUILT=0; IPV6_RULES=0
    IPV6_CONNBYTES=0; IPV6_MULTIPORT=1; IPV6_MARK=1
    if z2_fw_tool_available ip6tables && z2_fw_restore_available ip6tables; then
        if z2_fw_reconcile_family ip6tables; then
            IPV6_CONNBYTES="$Z2_FW_CONNBYTES"
            IPV6_RULES="$Z2_FW_RULES"; IPV6_BUILT=1; IPV6_ACTIVE=1
            if [ "$IPV6_CONNBYTES" != 1 ]; then
                FALLBACK_MODE=1
                DIAGNOSTICS="${DIAGNOSTICS}IPv6 connbytes unavailable; using outgoing-only interception; "
            fi
        else
            z2_fw_cleanup_family ip6tables >/dev/null 2>&1 ||
                fail_start "failed IPv6 publication could not converge to absent state"
            DIAGNOSTICS="${DIAGNOSTICS}IPv6 firewall publication failed; IPv6 skipped; "
        fi
    else
        DIAGNOSTICS="${DIAGNOSTICS}IPv6 restore backend unavailable; IPv6 skipped; "
    fi

    # Queue bypass keeps traffic flowing between atomic firewall publication
    # and the verified listener becoming ready.
    launch_nfqws2 ||
        fail_start "nfqws2 did not produce a verified module-owned PID" \
            PROCESS PROCESS_LAUNCH_FAILED START_LAUNCH 1

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
    FIREWALL_MUTATED=0
    LAUNCHED_PID=""
    release_lifecycle_lock; trap - HUP INT TERM
    log_msg "Zapret2 started with verified PID $STARTED_PID"
    echo "Zapret2 started (PID: $STARTED_PID)"
    exit 0
}

main "$@"
