#!/system/bin/sh
# Transactional stop: detach owned firewall first, then stop only a verified PID.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

log_msg() {
    append_lifecycle_log "$(date '+%Y-%m-%d %H:%M:%S') [STOP] $1"
    if command -v log >/dev/null 2>&1; then log -t Zapret2 "$1" 2>/dev/null; fi
}

stop_error_exit() {
    local domain="$1" code="$2" stage="$3" retryable="$4" message="$5"
    z2_error_set "$domain" "$code" "$stage" "$message" ||
        z2_error_set LIFECYCLE LIFECYCLE_FAILED STOP "$message"
    z2_error_emit_machine
    echo "ERROR: $message"
    exit 1
}

write_stop_status() {
    local state="$1" message="$2"
    restore_status_facts
    if read_owner_state >/dev/null 2>&1; then STATUS_QNUM="$OWNER_STATE_QNUM"; fi
    STATUS_RULES_OK=0
    if [ "$state" = stopped ]; then STATUS_RULES_FAIL=0; STATUS_RULES_TOTAL=0
    else STATUS_RULES_FAIL=1; STATUS_RULES_TOTAL=1; fi
    STATUS_ERRORS="$message"; STATUS_OWN_PID=""; STATUS_OWN_PID_STARTTIME=""; STATUS_OWNER_GENERATION=""
    STATUS_PID_VERIFIED=0; STATUS_OWNER_METADATA_VERIFIED=0
    STATUS_RULESET_VERIFIED=1; STATUS_RULES_EXPECTED=0; STATUS_QNUM="${STOP_QNUM:-${STATUS_QNUM:-${QNUM:-}}}"
    STATUS_IPV4_ACTIVE=0; STATUS_IPV6_ACTIVE=0; STATUS_CHAINS=0; STATUS_ANCHORS=0
    STATUS_IPV4_RULES=0; STATUS_IPV6_RULES=0
    STATUS_NFQUEUE_SUPPORTED="${STATUS_NFQUEUE_SUPPORTED:-0}"
    STATUS_QUEUE_BYPASS_SUPPORTED="${STATUS_QUEUE_BYPASS_SUPPORTED:-0}"
    STATUS_CONNBYTES_SUPPORTED="${STATUS_CONNBYTES_SUPPORTED:-0}"
    STATUS_MULTIPORT_SUPPORTED="${STATUS_MULTIPORT_SUPPORTED:-0}"
    STATUS_MARK_SUPPORTED="${STATUS_MARK_SUPPORTED:-0}"
    STATUS_FALLBACK_MODE=0; STATUS_DIAGNOSTICS="$message"
    if [ "$state" = stopped ]; then
        STATUS_ERROR_STATUS=OK; STATUS_ERROR_DOMAIN=NONE; STATUS_ERROR_CODE=NONE
        STATUS_ERROR_STAGE=NONE; STATUS_ERROR_DETAIL=""
    else
        STATUS_ERROR_STATUS=ERROR
        STATUS_ERROR_DOMAIN="${STOP_ERROR_DOMAIN:-LIFECYCLE}"
        STATUS_ERROR_CODE="${STOP_ERROR_CODE:-LIFECYCLE_FAILED}"
        STATUS_ERROR_STAGE="${STOP_ERROR_STAGE:-STOP}"
        STATUS_ERROR_DETAIL="$(z2_error_detail_normalize "$message")"
    fi
    write_iptables_status "$state"
}

remove_transient_diagnostics() {
    local path rc=0
    [ "${STOP_RUNTIME_OWNED:-0}" = 1 ] || return 0
    for path in "$CMDLINE_FILE" "$STARTUP_LOG" "$ERROR_LOG" "$DEBUG_LOG"; do
        if [ -e "$path" ] || [ -L "$path" ]; then rm -f "$path" 2>/dev/null || rc=1; fi
    done
    return "$rc"
}

stop_interrupted() {
    local message="stop interrupted by signal"
    trap '' HUP INT TERM
    if ! rollback_legacy_migration; then
        message="$message; exact legacy-rule rollback failed; daemon retained"
    fi
    STOP_ERROR_DOMAIN=LIFECYCLE
    STOP_ERROR_CODE=LIFECYCLE_FAILED
    STOP_ERROR_STAGE=STOP_SIGNAL
    write_stop_status error "$message" >/dev/null 2>&1 || true
    release_lifecycle_lock
    trap - HUP INT TERM
    exit 1
}

firewall_is_clean() {
    command -v iptables >/dev/null 2>&1 || return 1
    owned_family_absent iptables || return 1
    if command -v ip6tables >/dev/null 2>&1; then
        owned_family_absent ip6tables || return 1
    elif [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ]; then
        return 1
    fi
    return 0
}

main() {
    ensure_state_dir ||
        stop_error_exit STATE STATE_UNAVAILABLE STOP_STATE 0 \
            "insecure or unavailable zapret2 state directory: $STATE_DIR"
    acquire_lifecycle_lock ||
        stop_error_exit LIFECYCLE LIFECYCLE_BUSY STOP_LOCK 1 "zapret2 lifecycle is busy"
    if ! audit_recovery_artifacts lifecycle; then
        message="stop blocked by recovery state: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery artifact}"
        release_lifecycle_lock
        stop_error_exit LIFECYCLE RECOVERY_BLOCKED STOP_RECOVERY 0 "$message"
    fi
    if ! uninstall_tombstone_allows_stop; then
        message="stop blocked by uninstall serialization: $UNINSTALL_TOMBSTONE_ERROR"
        release_lifecycle_lock
        stop_error_exit LIFECYCLE UNINSTALL_BLOCKED STOP_UNINSTALL 1 "$message"
    fi
    trap stop_interrupted HUP INT TERM
    if ! prepare_lifecycle_log; then
        LOG_READY=0
        if command -v log >/dev/null 2>&1; then log -p w -t Zapret2 "Lifecycle file logging disabled: unsafe or unavailable path" 2>/dev/null; fi
    fi
    # This is read-only; it supplies the queue number for legacy numeric PID
    # files without creating/migrating configuration during a stop operation.
    load_effective_core_config_readonly >/dev/null 2>&1 || true
    restore_status_facts
    STOP_RUNTIME_OWNED=0
    if read_runtime_owner_marker >/dev/null 2>&1 || read_owner_state >/dev/null 2>&1 || read_verified_pidfile >/dev/null 2>&1; then
        STOP_RUNTIME_OWNED=1
    fi
    restore_status_facts
    STOP_QNUM="${STATUS_FILE_QNUM:-${QNUM:-}}"
    if read_owner_state >/dev/null 2>&1; then STOP_QNUM="$OWNER_STATE_QNUM"; fi
    log_msg "Stopping Zapret2"
    [ -z "$UNINSTALL_TOMBSTONE_DIAGNOSTIC" ] || log_msg "$UNINSTALL_TOMBSTONE_DIAGNOSTIC"

    rc=0
    errors=""
    STOP_ERROR_DOMAIN=NONE; STOP_ERROR_CODE=NONE
    STOP_ERROR_STAGE=NONE; STOP_ERROR_RETRYABLE=0
    # Prove the complete process identity/publication before legacy migration
    # or any current-chain mutation. The later stop consumes this same snapshot
    # and generation, so a replacement process cannot be killed after teardown.
    if ! preflight_owned_process_cleanup; then
        errors="process cleanup preflight blocked: $PROCESS_CLEANUP_PREFLIGHT_ERROR; firewall and daemon teardown were not attempted"
        STOP_ERROR_DOMAIN=PROCESS; STOP_ERROR_CODE=PROCESS_STOP_FAILED; STOP_ERROR_STAGE=STOP_PREFLIGHT
    elif ! audit_owned_firewall_for_cleanup "$STOP_QNUM"; then
        errors="firewall generation preflight blocked: $FIREWALL_CLEANUP_PREFLIGHT_ERROR; firewall and daemon teardown were not attempted"
        STOP_ERROR_DOMAIN=FIREWALL; STOP_ERROR_CODE=FIREWALL_CLEANUP_FAILED; STOP_ERROR_STAGE=STOP_PREFLIGHT
    elif ! legacy_migrate_firewall; then
        errors="legacy firewall migration blocked: $LEGACY_MIGRATION_ERROR; daemon teardown was not attempted"
        STOP_ERROR_DOMAIN=FIREWALL; STOP_ERROR_CODE=FIREWALL_CLEANUP_FAILED; STOP_ERROR_STAGE=STOP_LEGACY
    elif [ "$LEGACY_MIGRATION_VERIFIED" != 1 ]; then
        errors="legacy firewall migration did not reach a verified commit; daemon teardown was not attempted"
        STOP_ERROR_DOMAIN=FIREWALL; STOP_ERROR_CODE=POSTCONDITION_FAILED; STOP_ERROR_STAGE=STOP_LEGACY
    fi
    if [ -n "$errors" ]; then
        trap '' HUP INT TERM
        if ! rollback_legacy_migration; then
            errors="$errors; exact legacy-rule rollback failed"
        fi
        write_stop_status error "$errors" >/dev/null 2>&1 || true
        release_lifecycle_lock
        trap - HUP INT TERM
        log_msg "ERROR: $errors"
        z2_error_set "$STOP_ERROR_DOMAIN" "$STOP_ERROR_CODE" "$STOP_ERROR_STAGE" "$errors" ||
            z2_error_set LIFECYCLE LIFECYCLE_FAILED STOP "$errors"
        z2_error_emit_machine
        echo "ERROR: Zapret2 stop incomplete: $errors"
        exit 1
    fi
    firewall_detached=0
    if ! cleanup_owned_firewall; then
        rc=1
        STOP_ERROR_DOMAIN=FIREWALL; STOP_ERROR_CODE=FIREWALL_CLEANUP_FAILED; STOP_ERROR_STAGE=STOP_FIREWALL
        if [ -n "$errors" ]; then errors="$errors; owned firewall cleanup failed"
        else errors="owned firewall cleanup failed: ${FIREWALL_CLEANUP_PREFLIGHT_ERROR:-ambiguous ownership}; daemon teardown was not attempted"; fi
    elif ! firewall_is_clean; then
        rc=1
        STOP_ERROR_DOMAIN=FIREWALL; STOP_ERROR_CODE=POSTCONDITION_FAILED; STOP_ERROR_STAGE=STOP_FIREWALL
        if [ -n "$errors" ]; then errors="$errors; owned firewall artifacts remain"
        else errors="owned firewall artifacts remain; daemon teardown was not attempted"; fi
    else
        firewall_detached=1
    fi

    if [ "$firewall_detached" = 1 ] && ! stop_pidfile_process; then
        rc=1
        STOP_ERROR_DOMAIN=PROCESS; STOP_ERROR_CODE=PROCESS_STOP_FAILED; STOP_ERROR_STAGE=STOP_PROCESS
        if [ -n "$errors" ]; then errors="$errors; verified process stop failed"
        else errors="verified process stop failed"; fi
    fi

    if [ "$firewall_detached" = 1 ] && read_verified_pidfile; then
        rc=1
        STOP_ERROR_DOMAIN=PROCESS; STOP_ERROR_CODE=POSTCONDITION_FAILED; STOP_ERROR_STAGE=STOP_VERIFY
        if [ -n "$errors" ]; then errors="$errors; verified nfqws2 process remains"
        else errors="verified nfqws2 process remains"; fi
    elif [ -e "$PIDFILE" ]; then
        if read_live_pidfile; then
            rc=1
            STOP_ERROR_DOMAIN=PROCESS; STOP_ERROR_CODE=POSTCONDITION_FAILED; STOP_ERROR_STAGE=STOP_VERIFY
            if [ -n "$errors" ]; then errors="$errors; unverified live PID remains"
            else errors="unverified live PID remains"; fi
        fi
    fi

    if [ "$rc" -eq 0 ]; then
        if ! retire_owner_metadata; then
            rc=1
            if [ -n "$errors" ]; then errors="$errors; ownership metadata retained because process identity is ambiguous"
            else errors="ownership metadata retained because process identity is ambiguous"; fi
        fi
    fi

    if [ "$rc" -eq 0 ]; then
        if ! remove_transient_diagnostics; then
            log_msg "WARNING: one or more transient diagnostics could not be removed safely"
        fi
        if ! write_stop_status stopped ""; then
            # Process and firewall cleanup is already verified.  A diagnostic
            # status-write failure must not turn a completed stop into an
            # unverifiable process-cleanup failure after metadata retirement.
            log_msg "WARNING: stopped state is clean but status file update failed"
        fi
    else
        write_stop_status error "$errors" >/dev/null 2>&1 || true
    fi

    release_lifecycle_lock
    trap - HUP INT TERM
    if [ "$rc" -eq 0 ]; then
        log_msg "Zapret2 stopped and owned state is clean"
        echo "Zapret2 stopped"
    else
        log_msg "ERROR: $errors"
        z2_error_set "$STOP_ERROR_DOMAIN" "$STOP_ERROR_CODE" "$STOP_ERROR_STAGE" "$errors" ||
            z2_error_set LIFECYCLE LIFECYCLE_FAILED STOP "$errors"
        z2_error_emit_machine
        echo "ERROR: Zapret2 stop incomplete: $errors"
    fi
    exit "$rc"
}

main "$@"
