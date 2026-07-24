#!/system/bin/sh
# Serialized status derived from exact lifecycle, process, and ruleset ownership.
# Machine v4/v5 may retire only a proven-stale lifecycle owner through
# common.sh's shared acquisition/recovery protocol before observing runtime
# state. Machine v5 is the complete app-facing service-health contract.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

MACHINE=0
MACHINE_VERSION=0
case "${1:-}" in
    "") ;;
    --machine) MACHINE=1; MACHINE_VERSION=1 ;;
    --machine-v3) MACHINE=1; MACHINE_VERSION=3 ;;
    --machine-v4) MACHINE=1; MACHINE_VERSION=4 ;;
    --machine-v5) MACHINE=1; MACHINE_VERSION=5 ;;
    *) echo "ERROR: usage: $0 [--machine|--machine-v3|--machine-v4|--machine-v5]" >&2; exit 2 ;;
esac

STATUS_LOCK_HELD=0
Z2_LIFECYCLE_STATE=unknown
Z2_LIFECYCLE_OWNER_KIND=unknown

cleanup_status_lock() {
    if [ "$STATUS_LOCK_HELD" = 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || true
        STATUS_LOCK_HELD=0
    fi
}

emit_lifecycle_barrier() {
    local state="$1" kind="$2" code detail
    case "$state" in
        active)
            code=LIFECYCLE_ACTIVE
            detail="Another verified lifecycle owner is active"
            ;;
        ambiguous)
            code=LIFECYCLE_AMBIGUOUS
            detail="Lifecycle ownership metadata is unsafe or cannot be authenticated"
            ;;
        *)
            state=recovery_failed
            code=LIFECYCLE_RECOVERY_FAILED
            detail="A proven stale lifecycle owner could not be recovered safely"
            ;;
    esac
    z2_error_set LIFECYCLE "$code" LIFECYCLE_OBSERVE "$detail" || exit 2
    echo "Z2_PROTOCOL=$MACHINE_VERSION"
    echo "Z2_STATUS=degraded"
    echo "Z2_OWNED=1"
    echo "Z2_PROCESS=0"
    echo "Z2_ACTIVE=0"
    echo "Z2_PID="
    echo "Z2_PID_VERIFIED=0"
    echo "Z2_PID_STARTTIME="
    echo "Z2_OWNER_GENERATION="
    echo "Z2_OWNER_METADATA_VERIFIED=0"
    echo "Z2_QNUM="
    echo "Z2_IPV4=0"
    echo "Z2_IPV6=0"
    echo "Z2_RULES=0"
    echo "Z2_EXPECTED_RULES=0"
    echo "Z2_IPV4_RULES=0"
    echo "Z2_IPV6_RULES=0"
    echo "Z2_RULESET_VERIFIED=0"
    echo "Z2_NFQUEUE=0"
    echo "Z2_QUEUE_BYPASS=0"
    echo "Z2_UPDATE_BLOCKED=1"
    echo "Z2_UNINSTALL_TOMBSTONE=0"
    echo "Z2_LIFECYCLE_STATE=$state"
    echo "Z2_LIFECYCLE_OWNER_KIND=$kind"
    if [ "$MACHINE_VERSION" = 5 ]; then
        echo "Z2_CHAINS=0"
        echo "Z2_ANCHORS=0"
    fi
    z2_error_emit_machine
    echo "Z2_COMPLETE=1"
}

prepare_v4_status_lock() {
    local observed
    classify_lifecycle_lock
    observed="$LIFECYCLE_OBSERVED_STATE"
    Z2_LIFECYCLE_OWNER_KIND="$LIFECYCLE_OBSERVED_KIND"
    case "$observed" in
        active|ambiguous)
            Z2_LIFECYCLE_STATE="$observed"
            return 1
            ;;
        idle|stale) ;;
        *)
            Z2_LIFECYCLE_STATE=ambiguous
            Z2_LIFECYCLE_OWNER_KIND=unknown
            return 1
            ;;
    esac

    LIFECYCLE_LOCK_WAIT_SECONDS=3
    if acquire_lifecycle_lock; then
        STATUS_LOCK_HELD=1
        Z2_LIFECYCLE_STATE=idle
        [ "$observed" != stale ] || Z2_LIFECYCLE_STATE=recovered
        Z2_LIFECYCLE_OWNER_KIND=none
        return 0
    fi
    abort_lifecycle_lock_acquire >/dev/null 2>&1 || true
    classify_lifecycle_lock
    Z2_LIFECYCLE_OWNER_KIND="$LIFECYCLE_OBSERVED_KIND"
    case "$LIFECYCLE_OBSERVED_STATE" in
        active) Z2_LIFECYCLE_STATE=active ;;
        ambiguous) Z2_LIFECYCLE_STATE=ambiguous ;;
        *) Z2_LIFECYCLE_STATE=recovery_failed ;;
    esac
    return 1
}

case "$MACHINE_VERSION" in 4|5)
    trap cleanup_status_lock EXIT
    trap 'cleanup_status_lock; exit 2' HUP INT TERM
    if ! prepare_v4_status_lock; then
        emit_lifecycle_barrier "$Z2_LIFECYCLE_STATE" "$Z2_LIFECYCLE_OWNER_KIND"
        exit 2
    fi
    ;;
esac

CONFIG_VALID=1
STATE_DIR_SECURE=0
state_dir_is_secure && STATE_DIR_SECURE=1
load_effective_core_config_readonly >/dev/null 2>&1 || CONFIG_VALID=0
normalize_qnum "${QNUM:-}" >/dev/null 2>&1 || CONFIG_VALID=0
[ "$CONFIG_VALID" = 0 ] || QNUM="$QNUM_NORMALIZED"
is_decimal "${PKT_OUT:-}" && is_decimal "${PKT_IN:-}" || CONFIG_VALID=0
read_iptables_status >/dev/null 2>&1 || true

Z2_PID=""
Z2_PID_STARTTIME=""
Z2_OWNER_GENERATION=""
Z2_PID_VERIFIED=0
Z2_OWNER_METADATA_VERIFIED=0
Z2_PROCESS=0
Z2_ORPHANS=""
Z2_QNUM="${STATUS_FILE_QNUM:-${QNUM:-}}"
Z2_UPDATE_BLOCKED=0
Z2_UNINSTALL_TOMBSTONE=0
{ [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; } && Z2_UNINSTALL_TOMBSTONE=1
module_removal_pending && Z2_UNINSTALL_TOMBSTONE=1

if read_verified_pidfile && [ "$OWNER_STATE_PHASE" = active ] &&
   [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] && read_install_generation_meta &&
   [ "$OWNER_STATE_INSTALL_GENERATION" = "$INSTALL_META_GENERATION" ] &&
   [ "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ]; then
    Z2_PID="$VERIFIED_PID"
    Z2_PID_STARTTIME="$VERIFIED_PID_START"
    Z2_OWNER_GENERATION="$OWNER_STATE_GENERATION"
    Z2_QNUM="$OWNER_STATE_QNUM"
    Z2_PID_VERIFIED=1
    Z2_OWNER_METADATA_VERIFIED=1
    Z2_PROCESS=1
else
    # A status poll is an observer, not a recovery audit. Full /proc discovery
    # belongs to start/stop/recovery; running it every few seconds makes Android
    # root IPC scale with every process on the device.
    if [ "$MACHINE" != 1 ]; then
        scan_exact_owned_nfqws >/dev/null 2>&1
        Z2_ORPHANS="$OWNED_SCAN_PIDS"
        set -- $OWNED_SCAN_PIDS
        if [ "$#" -eq 1 ]; then
            Z2_PID="$1"
            Z2_PID_STARTTIME="$(proc_starttime "$1" 2>/dev/null)"
            Z2_PROCESS=1
        elif [ "$#" -gt 1 ]; then
            Z2_PROCESS=1
        fi
    fi
fi

Z2_IPV4=0
Z2_IPV6=0
Z2_IPV4_RULES=0
Z2_IPV6_RULES=0
IPV4_VERIFIED=0
IPV6_VERIFIED=0
IPV6_UNKNOWN=0
Z2_FAST_SNAPSHOT=0

# The module lifecycle boundary is the single firewall authority. Bind its
# durable snapshot to the exact live owner generation so app polling never
# needs a second owner.meta/iptables interpreter.
if [ "$MACHINE" = 1 ] && [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] &&
   [ "$STATUS_FILE_STATUS" = ok ] &&
   [ "$STATUS_FILE_OWN_PID" = "$Z2_PID" ] &&
   [ "$STATUS_FILE_OWN_PID_STARTTIME" = "$Z2_PID_STARTTIME" ] &&
   [ "$STATUS_FILE_OWNER_GENERATION" = "$Z2_OWNER_GENERATION" ] &&
   [ "$STATUS_FILE_QNUM" = "$Z2_QNUM" ] &&
   [ "$STATUS_FILE_OWNER_METADATA_VERIFIED" = 1 ] &&
   [ "$STATUS_FILE_RULESET_VERIFIED" = 1 ] &&
   [ "$STATUS_FILE_IPV4_ACTIVE" = "$OWNER_STATE_IPV4_ACTIVE" ] &&
   [ "$STATUS_FILE_IPV6_ACTIVE" = "$OWNER_STATE_IPV6_ACTIVE" ] &&
   [ "$STATUS_FILE_IPV4_RULES" = "$OWNER_STATE_IPV4_RULES" ] &&
   [ "$STATUS_FILE_IPV6_RULES" = "$OWNER_STATE_IPV6_RULES" ] &&
   [ "$STATUS_FILE_RULES_TOTAL" = "$STATUS_FILE_RULES_EXPECTED" ] &&
   [ "$STATUS_FILE_RULES_EXPECTED" = "$((OWNER_STATE_IPV4_RULES + OWNER_STATE_IPV6_RULES))" ]; then
    Z2_FAST_SNAPSHOT=1
    Z2_IPV4="$STATUS_FILE_IPV4_ACTIVE"
    Z2_IPV6="$STATUS_FILE_IPV6_ACTIVE"
    Z2_IPV4_RULES="$STATUS_FILE_IPV4_RULES"
    Z2_IPV6_RULES="$STATUS_FILE_IPV6_RULES"
    IPV4_VERIFIED="$Z2_IPV4"
    [ "$Z2_IPV6" = 0 ] || IPV6_VERIFIED=1
elif [ "$MACHINE" = 1 ] && [ "$Z2_PROCESS" = 0 ] &&
     [ ! -e "$PIDFILE" ] && [ ! -L "$PIDFILE" ] &&
     [ ! -e "$OWNER_STATE" ] && [ ! -L "$OWNER_STATE" ] &&
     [ "$STATUS_FILE_STATUS" = stopped ] &&
     [ "$STATUS_FILE_RULES_TOTAL" = 0 ] &&
     [ "$STATUS_FILE_RULESET_VERIFIED" = 1 ]; then
    Z2_FAST_SNAPSHOT=1
    IPV4_VERIFIED=1
fi

if [ "$Z2_FAST_SNAPSHOT" = 0 ] && command -v iptables >/dev/null 2>&1; then
    if owned_family_present iptables; then
        Z2_IPV4=1
        Z2_IPV4_RULES=$(( $(chain_owned_rule_count iptables "$ZAPRET2_OUT") + $(chain_owned_rule_count iptables "$ZAPRET2_IN") ))
        if [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] && [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] &&
           owner_family_generation_healthy iptables ipv4; then IPV4_VERIFIED=1; fi
    elif zapret2_namespace_present iptables; then
        # A detached failed-build generation is owned residue even without a
        # publishable owner.meta. Never report this kernel state as stopped.
        Z2_IPV4=1
    fi
fi

if [ "$Z2_FAST_SNAPSHOT" = 0 ] && command -v ip6tables >/dev/null 2>&1; then
    if owned_family_present ip6tables; then
        Z2_IPV6=1
        Z2_IPV6_RULES=$(( $(chain_owned_rule_count ip6tables "$ZAPRET2_OUT") + $(chain_owned_rule_count ip6tables "$ZAPRET2_IN") ))
        if [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] && [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] &&
           owner_family_generation_healthy ip6tables ipv6; then IPV6_VERIFIED=1; fi
    elif zapret2_namespace_present ip6tables; then
        Z2_IPV6=1
    fi
elif [ "$STATUS_FILE_IPV6_ACTIVE" = 1 ]; then
    IPV6_UNKNOWN=1
fi

Z2_RULES=$((Z2_IPV4_RULES + Z2_IPV6_RULES))
Z2_EXPECTED_RULES=0
if [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] && [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ]; then
    Z2_EXPECTED_RULES=$((OWNER_STATE_IPV4_RULES + OWNER_STATE_IPV6_RULES))
else
    case "$STATUS_FILE_RULES_EXPECTED" in
        ''|*[!0-9]*) Z2_EXPECTED_RULES=0 ;;
        *) Z2_EXPECTED_RULES="$STATUS_FILE_RULES_EXPECTED" ;;
    esac
fi
Z2_RULESET_VERIFIED=0
if [ "$IPV4_VERIFIED" = 1 ] && [ "$IPV6_UNKNOWN" = 0 ]; then
    if { [ "${OWNER_STATE_IPV6_ACTIVE:-0}" = 0 ] && [ "$Z2_IPV6" = 0 ]; } ||
       { [ "${OWNER_STATE_IPV6_ACTIVE:-0}" = 1 ] && [ "$IPV6_VERIFIED" = 1 ]; }; then
        [ "$Z2_RULES" = "$Z2_EXPECTED_RULES" ] && Z2_RULESET_VERIFIED=1
    fi
fi

Z2_OWNED=0
[ "$Z2_PROCESS" = 1 ] && Z2_OWNED=1
[ "$Z2_IPV4" = 1 ] && Z2_OWNED=1
[ "$Z2_IPV6" = 1 ] && Z2_OWNED=1
[ "$IPV6_UNKNOWN" = 1 ] && Z2_OWNED=1
[ -e "$PIDFILE" ] && Z2_OWNED=1
[ -e "$OWNER_STATE" ] && Z2_OWNED=1
[ "$Z2_UNINSTALL_TOMBSTONE" = 1 ] && Z2_OWNED=1
[ "$STATE_DIR_SECURE" = 1 ] || {
    if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then Z2_OWNED=1; fi
}
Z2_ACTIVE=0
Z2_NFQUEUE=0
Z2_QUEUE_BYPASS=0
Z2_CHAINS=0
Z2_ANCHORS=0
Z2_STATUS=degraded

if [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] && [ "$Z2_RULESET_VERIFIED" = 1 ] &&
   [ "$Z2_QNUM" = "$OWNER_STATE_QNUM" ]; then
    Z2_ACTIVE=1
    Z2_NFQUEUE=1
    Z2_QUEUE_BYPASS=1
    Z2_STATUS=ok
    Z2_CHAINS=$((1 + OWNER_STATE_IPV4_CONNBYTES))
    if [ "$OWNER_STATE_IPV6_ACTIVE" = 1 ]; then
        Z2_CHAINS=$((Z2_CHAINS + 1 + OWNER_STATE_IPV6_CONNBYTES))
    fi
    Z2_ANCHORS="$Z2_CHAINS"
elif [ "$Z2_OWNED" = 0 ]; then
    Z2_STATUS=stopped
    Z2_EXPECTED_RULES=0
    Z2_RULESET_VERIFIED=1
else
    Z2_NFQUEUE="$STATUS_FILE_NFQUEUE_SUPPORTED"
    Z2_QUEUE_BYPASS="$STATUS_FILE_QUEUE_BYPASS_SUPPORTED"
fi

if [ "$CONFIG_VALID" = 0 ]; then
    runtime_config_error_code "$RUNTIME_CONFIG_ERROR"
    z2_error_set CONFIG "$RUNTIME_CONFIG_ERROR_CODE" RUNTIME_PARSE \
        "${RUNTIME_CONFIG_ERROR:-runtime.ini validation failed}"
elif [ "$STATUS_FILE_ERROR_SCHEMA" = "$Z2_ERROR_SCHEMA_VERSION" ] &&
   [ "$STATUS_FILE_ERROR_STATUS" = ERROR ] &&
   z2_error_fields_are_valid "$STATUS_FILE_ERROR_STATUS" "$STATUS_FILE_ERROR_DOMAIN" \
       "$STATUS_FILE_ERROR_STAGE" "$STATUS_FILE_ERROR_CODE" "$STATUS_FILE_ERROR_DETAIL"; then
    z2_error_set "$STATUS_FILE_ERROR_DOMAIN" "$STATUS_FILE_ERROR_CODE" \
        "$STATUS_FILE_ERROR_STAGE" "$STATUS_FILE_ERROR_DETAIL"
elif [ "$Z2_STATUS" = degraded ]; then
    z2_error_set STATUS STATUS_DEGRADED STATUS_QUERY \
        "Service state is degraded; inspect the lifecycle log for full details"
else
    z2_error_clear
fi

emit_machine() {
    case "$MACHINE_VERSION" in 3|4|5) echo "Z2_PROTOCOL=$MACHINE_VERSION" ;; esac
    echo "Z2_STATUS=$Z2_STATUS"
    echo "Z2_OWNED=$Z2_OWNED"
    echo "Z2_PROCESS=$Z2_PROCESS"
    echo "Z2_ACTIVE=$Z2_ACTIVE"
    echo "Z2_PID=$Z2_PID"
    echo "Z2_PID_VERIFIED=$Z2_PID_VERIFIED"
    echo "Z2_PID_STARTTIME=$Z2_PID_STARTTIME"
    echo "Z2_OWNER_GENERATION=$Z2_OWNER_GENERATION"
    echo "Z2_OWNER_METADATA_VERIFIED=$Z2_OWNER_METADATA_VERIFIED"
    echo "Z2_QNUM=$Z2_QNUM"
    echo "Z2_IPV4=$Z2_IPV4"
    echo "Z2_IPV6=$Z2_IPV6"
    echo "Z2_RULES=$Z2_RULES"
    echo "Z2_EXPECTED_RULES=$Z2_EXPECTED_RULES"
    echo "Z2_IPV4_RULES=$Z2_IPV4_RULES"
    echo "Z2_IPV6_RULES=$Z2_IPV6_RULES"
    echo "Z2_RULESET_VERIFIED=$Z2_RULESET_VERIFIED"
    echo "Z2_NFQUEUE=$Z2_NFQUEUE"
    echo "Z2_QUEUE_BYPASS=$Z2_QUEUE_BYPASS"
    echo "Z2_UPDATE_BLOCKED=$Z2_UPDATE_BLOCKED"
    echo "Z2_UNINSTALL_TOMBSTONE=$Z2_UNINSTALL_TOMBSTONE"
    case "$MACHINE_VERSION" in 4|5)
        echo "Z2_LIFECYCLE_STATE=$Z2_LIFECYCLE_STATE"
        echo "Z2_LIFECYCLE_OWNER_KIND=$Z2_LIFECYCLE_OWNER_KIND"
        ;;
    esac
    if [ "$MACHINE_VERSION" = 5 ]; then
        echo "Z2_CHAINS=$Z2_CHAINS"
        echo "Z2_ANCHORS=$Z2_ANCHORS"
    fi
    case "$MACHINE_VERSION" in 3|4|5) z2_error_emit_machine ;; esac
    # Terminal sentinel lets strict callers reject truncated shell output.
    echo "Z2_COMPLETE=1"
}

case "$MACHINE_VERSION:$STATUS_LOCK_HELD" in 4:1|5:1)
    if release_lifecycle_lock; then
        STATUS_LOCK_HELD=0
    else
        emit_lifecycle_barrier recovery_failed "$Z2_LIFECYCLE_OWNER_KIND"
        exit 2
    fi
    ;;
esac

if [ "$MACHINE" = 1 ]; then
    emit_machine
else
    echo "=========================================="
    echo " Zapret2 Status"
    echo "=========================================="
    echo "Status: $Z2_STATUS"
    echo "Verified process: $Z2_PROCESS (PID: ${Z2_PID:-none}, metadata: $Z2_OWNER_METADATA_VERIFIED)"
    [ -n "$Z2_ORPHANS" ] && echo "Exact module-owned recovery candidates: $Z2_ORPHANS"
    echo "Owned IPv4: $Z2_IPV4 ($Z2_IPV4_RULES rules)"
    echo "Owned IPv6: $Z2_IPV6 ($Z2_IPV6_RULES rules)"
    echo "Ruleset verified: $Z2_RULESET_VERIFIED ($Z2_RULES/$Z2_EXPECTED_RULES)"
    echo "Queue: ${Z2_QNUM:-unknown}"
    echo "Config source: $CORE_CONFIG_SOURCE_PATH"
    echo "Runtime config status: $RUNTIME_CONFIG_STATUS"
    [ "$Z2_UNINSTALL_TOMBSTONE" = 1 ] && echo "Removal gate: active"
    if [ -f "$LOGFILE" ] && [ ! -L "$LOGFILE" ]; then
        echo ""
        echo "Last log entries:"
        tail -10 "$LOGFILE" 2>/dev/null
    fi
fi

case "$Z2_STATUS" in
    ok) exit 0 ;;
    stopped) exit 1 ;;
    *) exit 2 ;;
esac
