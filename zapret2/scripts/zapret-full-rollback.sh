#!/system/bin/sh
# Durable, fail-closed rollback of live Zapret2 effects. User strategy data is retained.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/common.sh"

RB_STATUS=error
RB_PROCESS_CLEAN=0
RB_FIREWALL_CLEAN=0
RB_ROLLBACK_ARMED=0
RB_HOSTS_PRESERVED=0
RB_USER_DATA_PRESERVED=1
RB_LEGACY_AMBIGUOUS=0
RB_DIAGNOSTIC="rollback failed"
RB_TOKEN=""
RB_PHASE=""
RB_LOCKED=0
RB_EMITTED=0
RB_ACTIVE_TEMP=""
RB_INSTALL_GENERATION=""
RB_INSTALL_ARCHIVE_SHA256=""

safe_diagnostic() {
    printf '%s' "$1" | tr '\r\n' '  ' | sed 's/[^A-Za-z0-9 .,:_+\/@=-]/_/g; s/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//' | cut -c1-240
}

emit_result() {
    local rc="$1"
    [ "$RB_EMITTED" = 0 ] || exit "$rc"
    RB_EMITTED=1
    RB_DIAGNOSTIC="$(safe_diagnostic "$RB_DIAGNOSTIC")"
    [ -n "$RB_DIAGNOSTIC" ] || RB_DIAGNOSTIC="unspecified"
    printf 'Z2_RB_STATUS=%s\n' "$RB_STATUS"
    printf 'Z2_RB_PROCESS_CLEAN=%s\n' "$RB_PROCESS_CLEAN"
    printf 'Z2_RB_FIREWALL_CLEAN=%s\n' "$RB_FIREWALL_CLEAN"
    printf 'Z2_RB_ROLLBACK_ARMED=%s\n' "$RB_ROLLBACK_ARMED"
    printf 'Z2_RB_HOSTS_PRESERVED=%s\n' "$RB_HOSTS_PRESERVED"
    printf 'Z2_RB_REBOOT_REQUIRED=1\n'
    printf 'Z2_RB_USER_DATA_PRESERVED=%s\n' "$RB_USER_DATA_PRESERVED"
    printf 'Z2_RB_LEGACY_AMBIGUOUS=%s\n' "$RB_LEGACY_AMBIGUOUS"
    printf 'Z2_RB_DIAGNOSTIC=%s\n' "$RB_DIAGNOSTIC"
    printf 'Z2_RB_COMPLETE=1\n'
    exit "$rc"
}

finish_result() {
    local rc="$1"
    trap '' HUP INT TERM
    cleanup_active_temp >/dev/null 2>&1 || true
    if [ "$RB_LOCKED" = 1 ] || [ "${LOCK_HELD:-0}" = 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || true
        RB_LOCKED=0
    elif [ -n "${LIFECYCLE_ACQUIRE_CANDIDATE:-}" ]; then
        abort_lifecycle_lock_acquire >/dev/null 2>&1 || true
    fi
    trap - HUP INT TERM
    emit_result "$rc"
}

blocked() { RB_STATUS=blocked; RB_DIAGNOSTIC="$1"; finish_result 2; }
failed() { RB_STATUS=error; RB_DIAGNOSTIC="$1"; finish_result 1; }
partial() { RB_STATUS=partial; RB_DIAGNOSTIC="$1"; finish_result 1; }

interrupted() {
    trap '' HUP INT TERM
    cleanup_active_temp >/dev/null 2>&1 || true
    if [ "$RB_LOCKED" = 1 ] && is_safe_token "$RB_TOKEN"; then
        # Complete the non-negotiable fence even when a catchable signal lands
        # between its individual atomic publications.
        arm_runtime_config >/dev/null 2>&1 || true
        arm_disable >/dev/null 2>&1 || true
        if regular_root_file "$MODDIR/disable"; then
            if [ -e "$FULL_ROLLBACK_TRANSACTION" ] || [ -L "$FULL_ROLLBACK_TRANSACTION" ]; then
                # Never regress a durable recovery phase when a signal lands
                # during hosts publication/removal or the final commit.
                if read_transaction >/dev/null 2>&1 && durability_sync >/dev/null 2>&1; then
                    RB_ROLLBACK_ARMED=1
                fi
            else
                case "$RB_PHASE" in
                    armed|legacy-clean|firewall-clean|process-clean|hosts-backed-up|hosts-preserved)
                        signal_phase="$RB_PHASE" ;;
                    *) signal_phase=armed ;;
                esac
                if write_transaction "$signal_phase" >/dev/null 2>&1 && durability_sync >/dev/null 2>&1; then
                    RB_ROLLBACK_ARMED=1
                fi
            fi
        fi
    fi
    if [ "${LEGACY_ROLLBACK_ARMED:-0}" = 1 ] || [ "${LEGACY_MARKER_PUBLISH_ATTEMPTED:-0}" = 1 ]; then
        rollback_legacy_migration >/dev/null 2>&1 || RB_LEGACY_AMBIGUOUS=1
    fi
    RB_STATUS=partial
    if [ "$RB_ROLLBACK_ARMED" = 1 ]; then
        RB_DIAGNOSTIC="rollback interrupted; durable disable fence and recovery journal retained"
    else
        RB_DIAGNOSTIC="rollback interrupted before the durable fence was fully verified"
    fi
    finish_result 1
}

regular_root_file() { [ -f "$1" ] && [ ! -L "$1" ] && path_uid_is_root "$1"; }

durability_sync() { command -v sync >/dev/null 2>&1 && sync >/dev/null 2>&1; }

cleanup_active_temp() {
    local path="$RB_ACTIVE_TEMP"
    [ -n "$path" ] || return 0
    case "$path" in
        "$STATE_DIR"/*|"$ZAPRET_DIR"/*|"$MODDIR"/.disable.full-rollback.*) ;;
        *) return 1 ;;
    esac
    if [ -e "$path" ] || [ -L "$path" ]; then
        [ -f "$path" ] && [ ! -L "$path" ] && path_uid_is_root "$path" || return 1
        rm -f "$path" 2>/dev/null || return 1
    fi
    RB_ACTIVE_TEMP=""
}

new_temp_nonce() {
    local value
    value="$(new_lifecycle_token)" || return 1
    is_safe_token "$value" || return 1
    printf '%s\n' "$value"
}

read_transaction() {
    local key value version="" module="" token="" phase="" seen=""
    regular_root_file "$FULL_ROLLBACK_TRANSACTION" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1;; esac; version="$value"; seen="${seen}v" ;;
            module_dir) case "$seen" in *m*) return 1;; esac; module="$value"; seen="${seen}m" ;;
            token) case "$seen" in *t*) return 1;; esac; token="$value"; seen="${seen}t" ;;
            phase) case "$seen" in *p*) return 1;; esac; phase="$value"; seen="${seen}p" ;;
            *) return 1 ;;
        esac
    done < "$FULL_ROLLBACK_TRANSACTION"
    [ "$seen" = vmtp ] && [ "$version" = "$FULL_ROLLBACK_VERSION" ] && [ "$module" = "$MODDIR" ] || return 1
    is_safe_token "$token" || return 1
    case "$phase" in armed|legacy-clean|firewall-clean|process-clean|hosts-backed-up|hosts-preserved) ;; *) return 1 ;; esac
    RB_TOKEN="$token"; RB_PHASE="$phase"
}

phase_rank() {
    case "$1" in
        armed) printf '1\n';; legacy-clean) printf '2\n';; firewall-clean) printf '3\n';;
        process-clean) printf '4\n';; hosts-backed-up) printf '5\n';; hosts-preserved) printf '6\n';;
        *) return 1;;
    esac
}

phase_at_least() {
    local have want
    have="$(phase_rank "$RB_PHASE")" || return 1
    want="$(phase_rank "$1")" || return 1
    [ "$have" -ge "$want" ] 2>/dev/null
}

write_transaction() {
    local phase="$1" nonce tmp requested_token existing_rank requested_rank
    requested_token="$RB_TOKEN"
    requested_rank="$(phase_rank "$phase")" || return 1
    if [ -e "$FULL_ROLLBACK_TRANSACTION" ] || [ -L "$FULL_ROLLBACK_TRANSACTION" ]; then
        read_transaction || return 1
        [ "$RB_TOKEN" = "$requested_token" ] || return 1
        existing_rank="$(phase_rank "$RB_PHASE")" || return 1
        [ "$existing_rank" -le "$requested_rank" ] 2>/dev/null || return 0
        [ "$existing_rank" -lt "$requested_rank" ] 2>/dev/null || return 0
    fi
    nonce="$(new_temp_nonce)" || return 1
    tmp="$FULL_ROLLBACK_TRANSACTION.tmp.$$.$RB_TOKEN.$nonce"
    state_file_target_is_safe "$FULL_ROLLBACK_TRANSACTION" || return 1
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    RB_ACTIVE_TEMP="$tmp"
    umask 077
    printf 'version=%s\nmodule_dir=%s\ntoken=%s\nphase=%s\n' "$FULL_ROLLBACK_VERSION" "$MODDIR" "$RB_TOKEN" "$phase" > "$tmp" || { cleanup_active_temp; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    mv -f "$tmp" "$FULL_ROLLBACK_TRANSACTION" || { cleanup_active_temp; return 1; }
    RB_ACTIVE_TEMP=""
    RB_PHASE="$phase"
}

meta_is_valid() {
    local key value version="" module="" complete="" generation="" archive="" seen=""
    regular_root_file "$FULL_ROLLBACK_META" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1;; esac; version="$value"; seen="${seen}v";;
            module_dir) case "$seen" in *m*) return 1;; esac; module="$value"; seen="${seen}m";;
            generation) case "$seen" in *g*) return 1;; esac; generation="$value"; seen="${seen}g";;
            archive_sha256) case "$seen" in *a*) return 1;; esac; archive="$value"; seen="${seen}a";;
            complete) case "$seen" in *c*) return 1;; esac; complete="$value"; seen="${seen}c";;
            token|completed_epoch|diagnostic) :;; *) return 1;; esac
    done < "$FULL_ROLLBACK_META"
    [ "$seen" = vmgac ] && [ "$version" = "$FULL_ROLLBACK_VERSION" ] && [ "$module" = "$MODDIR" ] && [ "$complete" = 1 ] &&
        [ "$generation" = "$RB_INSTALL_GENERATION" ] && [ "$archive" = "$RB_INSTALL_ARCHIVE_SHA256" ]
}

write_meta() {
    local nonce tmp
    nonce="$(new_temp_nonce)" || return 1
    tmp="$FULL_ROLLBACK_META.tmp.$$.$RB_TOKEN.$nonce"
    state_file_target_is_safe "$FULL_ROLLBACK_META" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    RB_ACTIVE_TEMP="$tmp"
    umask 077
    {
        printf 'version=%s\nmodule_dir=%s\ntoken=%s\n' "$FULL_ROLLBACK_VERSION" "$MODDIR" "$RB_TOKEN"
        printf 'generation=%s\narchive_sha256=%s\n' "$RB_INSTALL_GENERATION" "$RB_INSTALL_ARCHIVE_SHA256"
        printf 'completed_epoch=%s\ncomplete=1\ndiagnostic=full rollback complete; reboot required\n' "$(date +%s 2>/dev/null || echo 0)"
    } > "$tmp" || { cleanup_active_temp; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    mv -f "$tmp" "$FULL_ROLLBACK_META" || { cleanup_active_temp; return 1; }
    RB_ACTIVE_TEMP=""
}

preflight_runtime_config() {
    regular_root_file "$RUNTIME_CONFIG" && [ -r "$RUNTIME_CONFIG" ] || return 1
    awk '
        function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
        BEGIN { section=""; cores=0; autos=0 }
        { t=trim($0); if (t ~ /^\[[^]]+\]$/) { section=tolower(substr(t,2,length(t)-2)); if(section=="core") cores++; next }
          if (section=="core" && tolower(t) ~ /^autostart[ \t]*=/) autos++ }
        END { exit !(cores==1 && autos<=1) }
    ' "$RUNTIME_CONFIG" >/dev/null 2>&1
}

arm_runtime_config() {
    local nonce tmp
    nonce="$(new_temp_nonce)" || return 1
    tmp="$RUNTIME_CONFIG.full-rollback.tmp.$$.$RB_TOKEN.$nonce"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    RB_ACTIVE_TEMP="$tmp"
    awk '
        function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
        function close_core() { if (section=="core" && !autowritten) { print "autostart=0"; autowritten=1 } }
        BEGIN { section=""; autowritten=0 }
        {
          raw=$0; t=trim(raw)
          if (t ~ /^\[[^]]+\]$/) { close_core(); section=tolower(substr(t,2,length(t)-2)); print raw; next }
          if (section=="core" && tolower(t) ~ /^autostart[ \t]*=/) { if(!autowritten) print "autostart=0"; autowritten=1; next }
          if (section=="dns_manager" && tolower(t) ~ /^(dns_preset_index|selected_dns|selected_direct)[ \t]*=/) next
          print raw
        }
        END { close_core() }
    ' "$RUNTIME_CONFIG" > "$tmp" || { cleanup_active_temp; return 1; }
    chmod 0644 "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    mv -f "$tmp" "$RUNTIME_CONFIG" || { cleanup_active_temp; return 1; }
    RB_ACTIVE_TEMP=""
    regular_root_file "$RUNTIME_CONFIG"
}

arm_disable() {
    local path="$MODDIR/disable" nonce tmp size
    if [ -e "$path" ] || [ -L "$path" ]; then
        regular_root_file "$path" || return 1
        size="$(wc -c < "$path" 2>/dev/null)" || return 1
        [ "$size" = 0 ] || return 1
        chmod 0600 "$path" 2>/dev/null || return 1
        return 0
    fi
    nonce="$(new_temp_nonce)" || return 1
    tmp="$MODDIR/.disable.full-rollback.$$.$RB_TOKEN.$nonce"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    RB_ACTIVE_TEMP="$tmp"
    umask 077; : > "$tmp" || { cleanup_active_temp; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    ln "$tmp" "$path" 2>/dev/null || { cleanup_active_temp; return 1; }
    rm -f "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    RB_ACTIVE_TEMP=""
    regular_root_file "$path"
}

preflight_hosts() {
    local hosts="$MODDIR/system/etc/hosts" artifact
    if [ -e "$hosts" ] || [ -L "$hosts" ]; then regular_root_file "$hosts" || return 1; fi
    if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ] || [ -L "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
        regular_root_file "$FULL_ROLLBACK_HOSTS_BACKUP" || return 1
        if [ -e "$hosts" ] || [ -L "$hosts" ]; then
            case "$RB_PHASE" in process-clean|hosts-backed-up) : ;; *) return 1 ;; esac
            regular_root_file "$hosts" && cmp -s "$hosts" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
        fi
    fi
    for artifact in "$FULL_ROLLBACK_HOSTS_BACKUP".tmp.*; do
        [ -e "$artifact" ] || [ -L "$artifact" ] || continue
        case "$artifact" in "$FULL_ROLLBACK_HOSTS_BACKUP.tmp.$RB_TOKEN."*) ;;
            *) return 1 ;;
        esac
        regular_root_file "$artifact" || return 1
    done
}

preserve_hosts() {
    local hosts="$MODDIR/system/etc/hosts" artifact nonce tmp staged="" staged_count=0
    for artifact in "$FULL_ROLLBACK_HOSTS_BACKUP.tmp.$RB_TOKEN."*; do
        [ -e "$artifact" ] || [ -L "$artifact" ] || continue
        regular_root_file "$artifact" || return 1
        staged_count=$((staged_count + 1)); staged="$artifact"
    done
    [ "$staged_count" -le 1 ] || return 1
    if [ "$staged_count" = 1 ]; then
        if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
            cmp -s "$staged" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
            rm -f "$staged" 2>/dev/null || return 1
            durability_sync || return 1
        else
            regular_root_file "$hosts" && cmp -s "$hosts" "$staged" 2>/dev/null || return 1
            mv "$staged" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
            durability_sync || return 1
        fi
    fi
    if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
        regular_root_file "$FULL_ROLLBACK_HOSTS_BACKUP" || return 1
        if [ -e "$hosts" ] || [ -L "$hosts" ]; then
            case "$RB_PHASE" in process-clean|hosts-backed-up) : ;; *) return 1 ;; esac
            regular_root_file "$hosts" && cmp -s "$hosts" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
            if [ "$RB_PHASE" = process-clean ]; then
                durability_sync || return 1
                write_transaction hosts-backed-up || return 1
                durability_sync || return 1
            fi
            rm -f "$hosts" 2>/dev/null || return 1
            durability_sync || return 1
        fi
        RB_HOSTS_PRESERVED=1
        return 0
    fi
    if [ ! -e "$hosts" ] && [ ! -L "$hosts" ]; then RB_HOSTS_PRESERVED=1; return 0; fi
    nonce="$(new_temp_nonce)" || return 1
    tmp="$FULL_ROLLBACK_HOSTS_BACKUP.tmp.$RB_TOKEN.$nonce"
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    RB_ACTIVE_TEMP="$tmp"
    umask 077
    cp "$hosts" "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    regular_root_file "$tmp" && cmp -s "$hosts" "$tmp" 2>/dev/null || { cleanup_active_temp; return 1; }
    mv "$tmp" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || { cleanup_active_temp; return 1; }
    RB_ACTIVE_TEMP=""
    regular_root_file "$FULL_ROLLBACK_HOSTS_BACKUP" &&
        cmp -s "$hosts" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
    # Make the new private inode durable before recording that both paths are
    # expected, then make that recovery phase durable before unlinking source.
    durability_sync || return 1
    write_transaction hosts-backed-up || return 1
    durability_sync || return 1
    rm -f "$hosts" 2>/dev/null || return 1
    [ ! -e "$hosts" ] && [ ! -L "$hosts" ] || return 1
    durability_sync || return 1
    RB_HOSTS_PRESERVED=1
}

firewall_clean() {
    [ "${TEARDOWN_COMMIT_PROVEN:-0}" = 1 ] && return 0
    command -v iptables >/dev/null 2>&1 || return 1
    owned_family_absent iptables || return 1
    if command -v ip6tables >/dev/null 2>&1; then owned_family_absent ip6tables || return 1
    elif [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ]; then return 1; fi
    return 0
}

process_clean() { scan_exact_owned_nfqws >/dev/null 2>&1; [ -z "$OWNED_SCAN_PIDS" ]; }

cleanup_diagnostics() {
    local path
    for path in "$CMDLINE_FILE" "$STARTUP_LOG" "$ERROR_LOG" "$DEBUG_LOG"; do
        if state_file_is_secure "$path"; then rm -f "$path" 2>/dev/null || true; fi
    done
}

# Install signal handling before usage validation, lock acquisition/waiting, or
# any preflight. Every catchable termination therefore emits the same exact
# machine contract and retires only this process's lock-acquisition artifacts.
trap interrupted HUP INT TERM

[ "$#" = 1 ] && [ "$1" = --machine ] || {
    RB_STATUS=blocked
    RB_DIAGNOSTIC="usage: zapret-full-rollback.sh --machine"
    emit_result 2
}

ensure_state_dir || blocked "state directory is unavailable or unsafe"
acquire_lifecycle_lock || blocked "another lifecycle owner is active"
RB_LOCKED=1

if [ -e "$UPDATE_CLEANUP" ] || [ -L "$UPDATE_CLEANUP" ]; then
    consume_committed_update_cleanup_locked ||
        blocked "committed update cleanup is malformed, ambiguous, or could not be consumed; evidence was preserved"
fi

audit_recovery_artifacts full-rollback || blocked "recovery artifact blocks rollback: $RECOVERY_ARTIFACT_DIAGNOSTIC"
read_install_generation_meta || blocked "install generation metadata is missing, unsafe, or malformed"
RB_INSTALL_GENERATION="$INSTALL_META_GENERATION"; RB_INSTALL_ARCHIVE_SHA256="$INSTALL_META_ARCHIVE_SHA256"
update_lock_allows_stop >/dev/null 2>&1 || blocked "update serialization blocks rollback: $UPDATE_LOCK_ERROR"
{ [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; } && blocked "uninstall tombstone blocks rollback"
module_removal_pending && blocked "Magisk removal marker blocks rollback"

if [ -e "$FULL_ROLLBACK_TRANSACTION" ] || [ -L "$FULL_ROLLBACK_TRANSACTION" ]; then
    read_transaction || blocked "rollback transaction is malformed or unsafe"
else
    RB_TOKEN="$(new_lifecycle_token)"; is_safe_token "$RB_TOKEN" || failed "cannot create rollback token"
fi
if [ -e "$FULL_ROLLBACK_META" ] || [ -L "$FULL_ROLLBACK_META" ]; then meta_is_valid || blocked "rollback metadata is malformed or unsafe"; fi
preflight_runtime_config || blocked "runtime.ini is missing, unsafe, or ambiguous"
preflight_hosts || blocked "hosts overlay or existing backup is unsafe or conflicts"
load_effective_core_config_readonly >/dev/null 2>&1 || blocked "runtime.ini core values are invalid"
restore_status_facts
STOP_QNUM="${STATUS_FILE_QNUM:-${QNUM:-}}"; if read_owner_state >/dev/null 2>&1; then STOP_QNUM="$OWNER_STATE_QNUM"; fi

arm_runtime_config || failed "cannot atomically disable autostart in runtime.ini"
arm_disable || failed "cannot publish exact module disable fence"
write_transaction armed || failed "cannot publish rollback recovery journal"
durability_sync || failed "cannot durably publish rollback fence and recovery journal"
RB_ROLLBACK_ARMED=1

if ! phase_at_least process-clean; then
    if ! preflight_owned_process_cleanup; then partial "process ownership is ambiguous; firewall and listener retained: $PROCESS_CLEANUP_PREFLIGHT_ERROR"; fi
fi
if ! phase_at_least legacy-clean; then
    if ! audit_owned_firewall_for_cleanup "$STOP_QNUM"; then partial "persisted firewall generation is ambiguous; firewall and listener retained: $FIREWALL_CLEANUP_PREFLIGHT_ERROR"; fi
    if ! legacy_migrate_firewall || [ "$LEGACY_MIGRATION_VERIFIED" != 1 ]; then
        RB_LEGACY_AMBIGUOUS=1
        partial "legacy firewall ownership is ambiguous; daemon retained: ${LEGACY_MIGRATION_ERROR:-unverified legacy state}"
    fi
    write_transaction legacy-clean || failed "cannot advance rollback journal after legacy cleanup"
fi

if ! phase_at_least firewall-clean; then
    if ! audit_owned_firewall_for_cleanup "$STOP_QNUM"; then partial "persisted firewall generation is ambiguous; firewall and listener retained: $FIREWALL_CLEANUP_PREFLIGHT_ERROR"; fi
    if ! cleanup_owned_firewall || ! firewall_clean; then partial "verified owned firewall cleanup is incomplete; listener retained"; fi
    write_transaction firewall-clean || failed "cannot advance rollback journal after firewall cleanup"
else
    firewall_clean || partial "rollback journal says firewall-clean but a clean full snapshot cannot be proved"
fi
RB_FIREWALL_CLEAN=1

if ! phase_at_least process-clean; then
    if ! stop_pidfile_process || ! process_clean; then partial "verified module process cleanup is incomplete"; fi
    retire_owner_metadata >/dev/null 2>&1 || partial "process is stopped but ownership metadata remains ambiguous"
    write_transaction process-clean || failed "cannot advance rollback journal after process cleanup"
    durability_sync || failed "process-clean recovery phase could not be synchronized; hosts were not touched"
else
    process_clean || partial "rollback journal says process-clean but the process state is not clean"
fi
RB_PROCESS_CLEAN=1

if ! phase_at_least hosts-preserved; then
    preserve_hosts || partial "hosts overlay could not be copied to its protected backup"
    write_transaction hosts-preserved || failed "cannot advance rollback journal after hosts preservation"
else
    preserve_hosts || partial "hosts-preserved recovery state is inconsistent"
fi
durability_sync || failed "hosts preservation phase could not be synchronized; recovery journal retained"
cleanup_diagnostics
write_meta || failed "cleanup is verified but rollback metadata commit failed"
durability_sync || failed "rollback metadata could not be synchronized; recovery journal retained"
RB_COMMIT_TOKEN="$RB_TOKEN"
read_transaction && [ "$RB_TOKEN" = "$RB_COMMIT_TOKEN" ] || failed "rollback journal changed before commit"
rm -f "$FULL_ROLLBACK_TRANSACTION" 2>/dev/null || failed "rollback committed but recovery journal could not be retired"
if ! durability_sync; then
    RB_TOKEN="$RB_COMMIT_TOKEN"
    write_transaction hosts-preserved >/dev/null 2>&1 || true
    durability_sync >/dev/null 2>&1 || true
    failed "rollback journal removal could not be synchronized; recovery journal retained"
fi

RB_STATUS=complete
RB_DIAGNOSTIC="full rollback complete; reboot required; user strategies and lists preserved"
finish_result 0
