#!/system/bin/sh
# Read-only status derived from exact module ownership metadata and owned chains.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

MACHINE=0
MACHINE_VERSION=0
case "${1:-}" in
    "") ;;
    --machine) MACHINE=1; MACHINE_VERSION=1 ;;
    --machine-v2) MACHINE=1; MACHINE_VERSION=2 ;;
    *) echo "ERROR: usage: $0 [--machine|--machine-v2]" >&2; exit 2 ;;
esac

if ! update_lock_allows_status; then
    if [ "$MACHINE" = 1 ]; then
        [ "$MACHINE_VERSION" != 2 ] || echo "Z2_PROTOCOL=2"
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
        if [ "$MACHINE_VERSION" = 2 ]; then
            z2_error_set UPDATE UPDATE_BLOCKED STATUS_UPDATE 1
            z2_error_emit_machine
        fi
        echo "Z2_COMPLETE=1"
    else
        echo "Status: degraded"
        echo "Lifecycle status blocked by update serialization: $UPDATE_LOCK_ERROR"
    fi
    exit 2
fi

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

# Machine consumers already run an independent, rate-limited firewall watchdog.
# Bind the durable snapshot to the exact live owner generation and avoid dozens
# of repeated iptables subprocesses on the normal polling path.
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
Z2_STATUS=degraded

if [ "$Z2_OWNER_METADATA_VERIFIED" = 1 ] && [ "$Z2_RULESET_VERIFIED" = 1 ] &&
   [ "$Z2_QNUM" = "$OWNER_STATE_QNUM" ]; then
    Z2_ACTIVE=1
    Z2_NFQUEUE=1
    Z2_QUEUE_BYPASS=1
    Z2_STATUS=ok
elif [ "$Z2_OWNED" = 0 ]; then
    Z2_STATUS=stopped
    Z2_EXPECTED_RULES=0
    Z2_RULESET_VERIFIED=1
else
    Z2_NFQUEUE="$STATUS_FILE_NFQUEUE_SUPPORTED"
    Z2_QUEUE_BYPASS="$STATUS_FILE_QUEUE_BYPASS_SUPPORTED"
fi

if [ "$STATUS_FILE_ERROR_SCHEMA" = "$Z2_ERROR_SCHEMA_VERSION" ] &&
   [ "$STATUS_FILE_ERROR_CODE" != NONE ] &&
   z2_error_fields_are_valid "$STATUS_FILE_ERROR_DOMAIN" "$STATUS_FILE_ERROR_CODE" \
       "$STATUS_FILE_ERROR_STAGE" "$STATUS_FILE_ERROR_RETRYABLE"; then
    z2_error_set "$STATUS_FILE_ERROR_DOMAIN" "$STATUS_FILE_ERROR_CODE" \
        "$STATUS_FILE_ERROR_STAGE" "$STATUS_FILE_ERROR_RETRYABLE"
elif [ "$Z2_STATUS" = degraded ]; then
    z2_error_set STATUS STATUS_DEGRADED STATUS_QUERY 1
else
    z2_error_clear
fi

emit_machine() {
    [ "$MACHINE_VERSION" != 2 ] || echo "Z2_PROTOCOL=2"
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
    [ "$MACHINE_VERSION" != 2 ] || z2_error_emit_machine
    # Terminal sentinel lets strict callers reject truncated shell output.
    echo "Z2_COMPLETE=1"
}

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
