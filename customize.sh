#!/system/bin/sh

##########################################################################################
# Zapret2 Magisk Module - Installation Script
##########################################################################################

SKIPUNZIP=1
umask 077

case "$MODPATH" in
    /data/adb/modules/zapret2|/data/adb/modules_update/zapret2) ;;
    *) abort "! Refusing unsafe or unexpected module identity/path: $MODPATH" ;;
esac

[ -f "$ZIPFILE" ] && [ ! -L "$ZIPFILE" ] || abort "! Module ZIP is missing or unsafe"
INSTALL_TMP=""
RECOVERY_ROOT=""
STAGE_PATH=""
ORIGINAL_MODULE_BACKUP=""
FAILED_MODULE_BACKUP=""
INSTALL_COMMITTED=0
COMMIT_HANDOFF=0
OVERWRITE_STARTED=0
ORIGINAL_WAS_PRESENT=0
LOCK_ACQUIRED=0
TOMBSTONE_RESTORE_REQUIRED=0
LIVE_PRIOR_SERVICE_STATE="none"
LIVE_SERVICE_RESTORE_REQUIRED=0
LIVE_STATUS_SNAPSHOT=""
DISABLE_MARKER_BAK=""
ROLLBACK_META_BAK=""
ROLLBACK_HOSTS_BAK=""
ROLLBACK_ARTIFACT_RESTORE_REQUIRED=0
ROLLBACK_GENERATION_PENDING=0
ROLLBACK_GENERATION=""
ROLLBACK_ARCHIVE_SHA256=""
INSTALL_DEFER_LEGACY_OWNER_RECOVERY=1
INSTALL_GENERATION_META_REL="zapret2/install-generation.meta"
INSTALL_GENERATION_META=""
EXISTING_MODPATH="/data/adb/modules/zapret2"
LIVE_ZAPRET_DIR="$EXISTING_MODPATH/zapret2"
LIVE_NFQWS2="$LIVE_ZAPRET_DIR/nfqws2"

restore_cleared_tombstone() {
    [ "$TOMBSTONE_RESTORE_REQUIRED" -eq 1 ] || return 0
    [ -f "$RECOVERY_ROOT/uninstall.tombstone" ] && [ ! -L "$RECOVERY_ROOT/uninstall.tombstone" ] || return 1
    if [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; then
        [ -f "$UNINSTALL_TOMBSTONE" ] && [ ! -L "$UNINSTALL_TOMBSTONE" ] &&
            state_file_is_secure "$UNINSTALL_TOMBSTONE" &&
            cmp -s "$RECOVERY_ROOT/uninstall.tombstone" "$UNINSTALL_TOMBSTONE" || return 1
        TOMBSTONE_RESTORE_REQUIRED=0
        return 0
    fi
    cp "$RECOVERY_ROOT/uninstall.tombstone" "$UNINSTALL_TOMBSTONE" 2>/dev/null || return 1
    chmod 0600 "$UNINSTALL_TOMBSTONE" 2>/dev/null || return 1
    state_file_is_secure "$UNINSTALL_TOMBSTONE" || return 1
    cmp -s "$RECOVERY_ROOT/uninstall.tombstone" "$UNINSTALL_TOMBSTONE" || return 1
    TOMBSTONE_RESTORE_REQUIRED=0
}

restore_overwritten_module() {
    [ "$OVERWRITE_STARTED" -eq 1 ] || return 0
    if [ "$ORIGINAL_WAS_PRESENT" -eq 1 ]; then
        if [ -e "$ORIGINAL_MODULE_BACKUP" ] || [ -L "$ORIGINAL_MODULE_BACKUP" ]; then
            [ -d "$ORIGINAL_MODULE_BACKUP" ] && [ ! -L "$ORIGINAL_MODULE_BACKUP" ] || return 1
            if [ -e "$MODPATH" ] || [ -L "$MODPATH" ]; then
                [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || return 1
                [ ! -e "$FAILED_MODULE_BACKUP" ] && [ ! -L "$FAILED_MODULE_BACKUP" ] || return 1
                mv "$MODPATH" "$FAILED_MODULE_BACKUP" 2>/dev/null || return 1
            fi
            mv "$ORIGINAL_MODULE_BACKUP" "$MODPATH" 2>/dev/null || return 1
            [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || return 1
        else
            # The signal/failure happened before the original tree was moved.
            [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || return 1
        fi
    elif [ -e "$MODPATH" ] || [ -L "$MODPATH" ]; then
        [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || return 1
        [ ! -e "$FAILED_MODULE_BACKUP" ] && [ ! -L "$FAILED_MODULE_BACKUP" ] || return 1
        mv "$MODPATH" "$FAILED_MODULE_BACKUP" 2>/dev/null || return 1
    fi
    OVERWRITE_STARTED=0
}

restore_retired_rollback_generation() {
    [ "$ROLLBACK_ARTIFACT_RESTORE_REQUIRED" -eq 1 ] || return 0

    if [ -e "$ROLLBACK_HOSTS_BAK" ] || [ -L "$ROLLBACK_HOSTS_BAK" ]; then
        [ -f "$ROLLBACK_HOSTS_BAK" ] && [ ! -L "$ROLLBACK_HOSTS_BAK" ] || return 1
        [ ! -e "$FULL_ROLLBACK_HOSTS_BACKUP" ] && [ ! -L "$FULL_ROLLBACK_HOSTS_BACKUP" ] || return 1
        mv "$ROLLBACK_HOSTS_BAK" "$FULL_ROLLBACK_HOSTS_BACKUP" 2>/dev/null || return 1
    fi
    if [ -e "$ROLLBACK_META_BAK" ] || [ -L "$ROLLBACK_META_BAK" ]; then
        [ -f "$ROLLBACK_META_BAK" ] && [ ! -L "$ROLLBACK_META_BAK" ] || return 1
        [ ! -e "$FULL_ROLLBACK_META" ] && [ ! -L "$FULL_ROLLBACK_META" ] || return 1
        mv "$ROLLBACK_META_BAK" "$FULL_ROLLBACK_META" 2>/dev/null || return 1
    fi

    audit_live_install_recovery_artifacts || return 1
    [ "$RECOVERY_ARTIFACT_CLASS" = rollback-complete ] || return 1
    read_completed_rollback_meta || return 1
    [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] || return 1
    ROLLBACK_ARTIFACT_RESTORE_REQUIRED=0
}

snapshot_live_service_state() {
    live_status="$EXISTING_MODPATH/zapret2/scripts/zapret-status.sh"
    [ -f "$live_status" ] && [ ! -L "$live_status" ] || return 1
    rm -f "$LIVE_STATUS_SNAPSHOT" 2>/dev/null || return 1
    /system/bin/sh "$live_status" --machine > "$LIVE_STATUS_SNAPSHOT" 2>/dev/null
    live_status_rc=$?
    [ -f "$LIVE_STATUS_SNAPSHOT" ] && [ ! -L "$LIVE_STATUS_SNAPSHOT" ] || return 1
    [ "$(grep -c '^Z2_COMPLETE=1$' "$LIVE_STATUS_SNAPSHOT" 2>/dev/null)" = 1 ] || return 1
    if [ "$live_status_rc" -eq 0 ] &&
       [ "$(grep -c '^Z2_STATUS=ok$' "$LIVE_STATUS_SNAPSHOT" 2>/dev/null)" = 1 ]; then
        LIVE_PRIOR_SERVICE_STATE="running"
        return 0
    fi
    if [ "$live_status_rc" -eq 1 ] &&
       [ "$(grep -c '^Z2_STATUS=stopped$' "$LIVE_STATUS_SNAPSHOT" 2>/dev/null)" = 1 ]; then
        LIVE_PRIOR_SERVICE_STATE="stopped"
        return 0
    fi
    LIVE_PRIOR_SERVICE_STATE="degraded"
    return 1
}

restore_live_service_after_rollback() {
    [ "$LIVE_SERVICE_RESTORE_REQUIRED" -eq 1 ] || return 0
    [ "$LIVE_PRIOR_SERVICE_STATE" = running ] || return 1
    live_start="$MODPATH/zapret2/scripts/zapret-start.sh"
    live_status="$MODPATH/zapret2/scripts/zapret-status.sh"
    for live_required in "$live_start" "$live_status" "$MODPATH/zapret2/scripts/common.sh" "$MODPATH/zapret2/nfqws2"; do
        [ -f "$live_required" ] && [ ! -L "$live_required" ] || return 1
    done
    /system/bin/sh "$live_start" >/dev/null 2>&1 || return 1
    rm -f "$LIVE_STATUS_SNAPSHOT" 2>/dev/null || return 1
    /system/bin/sh "$live_status" --machine > "$LIVE_STATUS_SNAPSHOT" 2>/dev/null || return 1
    [ "$(grep -c '^Z2_STATUS=ok$' "$LIVE_STATUS_SNAPSHOT" 2>/dev/null)" = 1 ] || return 1
    [ "$(grep -c '^Z2_COMPLETE=1$' "$LIVE_STATUS_SNAPSHOT" 2>/dev/null)" = 1 ] || return 1
    LIVE_SERVICE_RESTORE_REQUIRED=0
}

rollback_serialization_is_held() {
    [ "$LOCK_ACQUIRED" -eq 1 ] || return 1
    read_lock_owner && lock_owner_alive || return 1
    [ "$LOCK_FILE_PID" = "$LOCK_OWNER_PID" ] &&
        [ "$LOCK_FILE_START" = "$LOCK_OWNER_START" ] &&
        [ "$LOCK_FILE_TOKEN" = "$LOCK_OWNER_TOKEN" ]
}

remove_private_installer_tree() {
    tree="$1"
    kind="$2"
    case "$kind:$tree" in
        install:/data/adb/zapret2-install.*|recovery:/data/adb/zapret2-recovery.*) ;;
        *) return 1 ;;
    esac
    [ -e "$tree" ] || [ -L "$tree" ] || return 0
    [ -d "$tree" ] && [ ! -L "$tree" ] || return 1
    rm -rf "$tree" 2>/dev/null || return 1
    [ ! -e "$tree" ] && [ ! -L "$tree" ]
}

finish_install() {
    rc=$?
    manual_repair_required=0
    trap - EXIT HUP INT TERM
    if [ "$rc" -ne 0 ] && [ "$INSTALL_COMMITTED" -ne 1 ] &&
       { [ "$TOMBSTONE_RESTORE_REQUIRED" -eq 1 ] || [ "$OVERWRITE_STARTED" -eq 1 ] ||
         [ "$LIVE_SERVICE_RESTORE_REQUIRED" -eq 1 ] ||
         [ "$ROLLBACK_ARTIFACT_RESTORE_REQUIRED" -eq 1 ]; }; then
        # Never move the tombstone or MODPATH unless this installer still owns
        # the lifecycle serialization. Signals are ignored during the later
        # commit/unlock handoff, so this refusal is a final defensive barrier.
        if ! rollback_serialization_is_held; then
            rc=1
            manual_repair_required=1
            ui_print "! Unsafe unlocked rollback was refused; recovery data remains at $RECOVERY_ROOT"
        else
            if ! restore_cleared_tombstone; then
                rc=1
                manual_repair_required=1
                ui_print "! Uninstall tombstone recovery requires manual repair from $RECOVERY_ROOT"
            fi
            if ! restore_overwritten_module; then
                rc=1
                manual_repair_required=1
                ui_print "! Module or completed-rollback recovery requires manual repair from $RECOVERY_ROOT"
            elif ! restore_retired_rollback_generation; then
                rc=1
                manual_repair_required=1
                ui_print "! Completed-rollback recovery requires manual repair from $RECOVERY_ROOT"
            elif ! restore_live_service_after_rollback; then
                rc=1
                manual_repair_required=1
                ui_print "! Previous running service could not be restored; recovery requires manual repair from $RECOVERY_ROOT"
            fi
        fi
    fi
    if [ "$LOCK_ACQUIRED" -eq 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || rc=1
        LOCK_ACQUIRED=0
    fi
    if [ -n "$INSTALL_TMP" ] && ! remove_private_installer_tree "$INSTALL_TMP" install; then
        rc=1
        ui_print "! Private install workspace could not be removed safely: $INSTALL_TMP"
    fi
    if [ -n "$RECOVERY_ROOT" ] && [ -d "$RECOVERY_ROOT" ]; then
        if [ "$manual_repair_required" -eq 0 ] &&
           { [ "$INSTALL_COMMITTED" -eq 0 ] || [ "$rc" -eq 0 ]; }; then
            remove_private_installer_tree "$RECOVERY_ROOT" recovery || {
                rc=1
                ui_print "! Obsolete recovery data could not be removed from $RECOVERY_ROOT"
            }
        else
            ui_print "! Recovery data was preserved at $RECOVERY_ROOT"
        fi
    fi
    exit "$rc"
}

trap finish_install EXIT
trap 'ui_print "! Installation interrupted by HUP"; exit 1' HUP
trap 'ui_print "! Installation interrupted by INT"; exit 1' INT
trap 'ui_print "! Installation interrupted by TERM"; exit 1' TERM

INSTALL_TMP="$(mktemp -d /data/adb/zapret2-install.XXXXXX 2>/dev/null)" || abort "! Cannot create private install workspace"
chmod 0700 "$INSTALL_TMP" || abort "! Cannot secure install workspace"
RECOVERY_ROOT="$(mktemp -d /data/adb/zapret2-recovery.XXXXXX 2>/dev/null)" || abort "! Cannot create durable recovery workspace"
chmod 0700 "$RECOVERY_ROOT" || abort "! Cannot secure durable recovery workspace"
STAGE_PATH="$INSTALL_TMP/module"
ORIGINAL_MODULE_BACKUP="$RECOVERY_ROOT/original-module"
FAILED_MODULE_BACKUP="$RECOVERY_ROOT/failed-module"
LIVE_STATUS_SNAPSHOT="$INSTALL_TMP/live-status.snapshot"
DISABLE_MARKER_BAK="$RECOVERY_ROOT/disable"
ROLLBACK_META_BAK="$RECOVERY_ROOT/full-rollback.meta"
ROLLBACK_HOSTS_BAK="$RECOVERY_ROOT/hosts.rollback.backup"
INSTALL_GENERATION_META="$STAGE_PATH/$INSTALL_GENERATION_META_REL"

mkdir "$STAGE_PATH" || abort "! Cannot create staged module tree"

ZIP_LIST="$INSTALL_TMP/zip.list"
ZIP_NAMES="$INSTALL_TMP/zip.names"
ZIP_EARLY_NAMES="$INSTALL_TMP/zip.early-names"
unzip -l "$ZIPFILE" > "$ZIP_LIST" 2>/dev/null || abort "! Cannot inspect module ZIP"
awk '
    /^[[:space:]]*--------/ {
        separators++
        if (separators == 2) exit
        next
    }
    separators == 1 {
        name=$0
        sub(/^[[:space:]]*[0-9]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "", name)
        if (name != "") print name
    }
' "$ZIP_LIST" > "$ZIP_EARLY_NAMES" || abort "! Cannot parse module ZIP entries"
[ -s "$ZIP_EARLY_NAMES" ] || abort "! Module ZIP entry list is empty"
while IFS= read -r zip_entry || [ -n "$zip_entry" ]; do
    case "$zip_entry" in
        /*|..|../*|*/..|*/../*) abort "! Module ZIP contains an unsafe path" ;;
    esac
done < "$ZIP_EARLY_NAMES"

# Establish module identity before loading any executable package contract.
# This limit is installer-owned and must not depend on data from the archive.
INSTALLER_MODULE_PROP_MAX_BYTES=4096
module_prop_count="$(grep -Fxc 'module.prop' "$ZIP_EARLY_NAMES" 2>/dev/null)"
[ "$module_prop_count" = 1 ] || abort "! Module ZIP has a missing or ambiguous module identity"
module_prop_declared_bytes="$(awk '$NF == "module.prop" { print $1 }' "$ZIP_LIST")"
case "$module_prop_declared_bytes" in ''|*[!0-9]*) abort "! Invalid declared module identity size" ;; esac
if [ "$module_prop_declared_bytes" -le 0 ] 2>/dev/null ||
   [ "$module_prop_declared_bytes" -gt "$INSTALLER_MODULE_PROP_MAX_BYTES" ] 2>/dev/null; then
    abort "! Module identity exceeds the size limit"
fi
unzip -p "$ZIPFILE" module.prop > "$INSTALL_TMP/module.prop" 2>/dev/null || abort "! Cannot read module identity"
module_prop_bytes="$(wc -c < "$INSTALL_TMP/module.prop" 2>/dev/null)" || abort "! Cannot size module identity"
case "$module_prop_bytes" in ''|*[!0-9]*) abort "! Invalid extracted module identity size" ;; esac
[ "$module_prop_bytes" = "$module_prop_declared_bytes" ] || abort "! Module identity size does not match ZIP metadata"
grep -qx 'id=zapret2' "$INSTALL_TMP/module.prop" || abort "! Refusing ZIP with unexpected module id"

CONTRACT_BOOTSTRAP_ROOT="$INSTALL_TMP/package-contract"
mkdir -p "$CONTRACT_BOOTSTRAP_ROOT/zapret2/scripts" || abort "! Cannot prepare package contract"
for bootstrap_entry in zapret2/runtime-manifest.tsv zapret2/scripts/package-contract.sh; do
    count="$(awk -v p="$bootstrap_entry" '$NF == p { n++ } END { print n+0 }' "$ZIP_LIST")"
    [ "$count" = 1 ] || abort "! Package contract bootstrap is missing or ambiguous: $bootstrap_entry"
    bootstrap_declared_bytes="$(awk -v p="$bootstrap_entry" '$NF == p { print $1 }' "$ZIP_LIST")"
    case "$bootstrap_declared_bytes" in ''|*[!0-9]*) abort "! Invalid declared bootstrap size: $bootstrap_entry" ;; esac
    [ "$bootstrap_declared_bytes" -le 262144 ] || abort "! Package contract bootstrap is too large: $bootstrap_entry"
    unzip -p "$ZIPFILE" "$bootstrap_entry" > "$CONTRACT_BOOTSTRAP_ROOT/$bootstrap_entry" 2>/dev/null ||
        abort "! Cannot extract package contract bootstrap: $bootstrap_entry"
    [ -s "$CONTRACT_BOOTSTRAP_ROOT/$bootstrap_entry" ] || abort "! Empty package contract bootstrap: $bootstrap_entry"
    bootstrap_bytes="$(wc -c < "$CONTRACT_BOOTSTRAP_ROOT/$bootstrap_entry" 2>/dev/null)" ||
        abort "! Cannot size package contract bootstrap: $bootstrap_entry"
    case "$bootstrap_bytes" in ''|*[!0-9]*) abort "! Invalid package contract bootstrap size: $bootstrap_entry" ;; esac
    [ "$bootstrap_bytes" -le 262144 ] || abort "! Package contract bootstrap is too large: $bootstrap_entry"
    if [ "$bootstrap_entry" = zapret2/scripts/package-contract.sh ]; then
        [ "$(sed -n '1p' "$CONTRACT_BOOTSTRAP_ROOT/$bootstrap_entry")" = '#!/system/bin/sh' ] ||
            abort "! Package contract bootstrap has an invalid shebang"
        if LC_ALL=C grep -q "$(printf '\r')" "$CONTRACT_BOOTSTRAP_ROOT/$bootstrap_entry"; then
            abort "! Package contract bootstrap contains CR bytes"
        fi
    fi
done
manifest_owner_protocol="$(sed -n '2p' "$CONTRACT_BOOTSTRAP_ROOT/zapret2/runtime-manifest.tsv")" ||
    abort "! Cannot read package owner protocol"
manifest_owner_protocol="${manifest_owner_protocol%"$(printf '\r')"}"
[ "$manifest_owner_protocol" = 'owner_protocol|6|zapret2-firewall' ] ||
    abort "! Package owner protocol is missing or incompatible (required: 6)"
. "$CONTRACT_BOOTSTRAP_ROOT/zapret2/scripts/package-contract.sh" || abort "! Cannot load package contract"
package_contract_extract_zip_names "$ZIP_LIST" "$ZIP_NAMES" ||
    abort "! Cannot parse module ZIP entries: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_zip_names "$CONTRACT_BOOTSTRAP_ROOT" "$ZIP_NAMES" ||
    abort "! Module ZIP violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
for generated_only in disable zapret2/install-generation.meta; do
    count="$(awk -v p="$generated_only" '$NF == p { n++ } END { print n+0 }' "$ZIP_LIST")"
    [ "$count" = 0 ] || abort "! Module ZIP contains installer-owned state: $generated_only"
done
package_contract_validate_module_prop "$INSTALL_TMP" ||
    abort "! Module identity is invalid: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"

# Detect architecture
ARCH=$(getprop ro.product.cpu.abi)
ui_print "- Detected architecture: $ARCH"

case "$ARCH" in
    arm64-v8a)
        ARCH_DIR="arm64-v8a"
        ;;
    armeabi-v7a|armeabi)
        ARCH_DIR="armeabi-v7a"
        ;;
    *)
        abort "! Unsupported architecture: $ARCH"
        ;;
esac

ui_print "- Installing Zapret2 for $ARCH_DIR"

# Load the package's lifecycle helpers before reading any mutable live module
# data. The lifecycle lock acquired here is held across every snapshot, staged
# restore, live-tree mutation, recovery-artifact retirement, and final commit.
COMMON_BOOTSTRAP="$INSTALL_TMP/common.sh"
unzip -p "$ZIPFILE" zapret2/scripts/common.sh > "$COMMON_BOOTSTRAP" 2>/dev/null || abort "! Cannot extract lifecycle helpers"
[ -s "$COMMON_BOOTSTRAP" ] && [ ! -L "$COMMON_BOOTSTRAP" ] || abort "! Extracted lifecycle helpers are empty or unsafe"
[ "$(sed -n '1p' "$COMMON_BOOTSTRAP")" = '#!/system/bin/sh' ] || abort "! Lifecycle helpers have an invalid shebang"
if LC_ALL=C grep -q "$(printf '\r')" "$COMMON_BOOTSTRAP"; then
    abort "! Lifecycle helpers contain CR bytes"
fi
chmod 0600 "$COMMON_BOOTSTRAP" || abort "! Cannot secure lifecycle helpers"

ZAPRET_DIR="$STAGE_PATH/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
COMMON_SCRIPT="$COMMON_BOOTSTRAP"
MODDIR="/data/adb/modules/zapret2"
# The installer owns this privileged namespace. Never let a root-manager or
# caller-provided environment redirect lifecycle state before common.sh is
# loaded; the shared helper intentionally supports STATE_DIR overrides only
# for isolated callers such as tests.
STATE_DIR="/data/adb/zapret2-state"
. "$COMMON_SCRIPT" || abort "! Failed to load secure lifecycle helpers"
command -v audit_recovery_artifacts >/dev/null 2>&1 || abort "! Lifecycle helpers lack the recovery-artifact audit"
if ! ensure_state_dir; then
    abort "! Failed to establish the root-only Zapret2 state directory"
fi

# Runtime ownership always belongs to the active canonical module tree.  A
# standard Magisk update executes from modules_update, so the package helpers'
# staged NFQWS2 path must never be used to authenticate the still-running live
# service.  The override is local to each audit; the staged value never changes.
audit_live_install_recovery_artifacts() {
    # The expected runtime path is dynamically scoped inside the audit.  No
    # global package path is changed, including on an error or signal exit.
    audit_recovery_artifacts install "$LIVE_NFQWS2"
}

mode_is_0600() {
    local path="$1" mode listing
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
        [ "$mode" = 600 ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    set -- $listing
    case "${1:-}" in -rw-------*) return 0 ;; *) return 1 ;; esac
}

valid_archive_sha256() {
    [ "${#1}" -eq 64 ] 2>/dev/null || return 1
    case "$1" in *[!0-9a-f]*) return 1 ;; *) return 0 ;; esac
}

compute_archive_sha256() {
    local value
    command -v sha256sum >/dev/null 2>&1 || return 1
    value="$(sha256sum "$ZIPFILE" 2>/dev/null | awk 'NR == 1 { print $1 }')" || return 1
    valid_archive_sha256 "$value" || return 1
    printf '%s\n' "$value"
}

read_completed_rollback_meta() {
    local key value version="" module="" token="" generation="" archive=""
    local completed="" complete="" diagnostic="" seen="" size
    state_file_is_secure "$FULL_ROLLBACK_META" && [ -r "$FULL_ROLLBACK_META" ] || return 1
    size="$(wc -c < "$FULL_ROLLBACK_META" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le 4096 ] 2>/dev/null || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1 ;; esac; version="$value"; seen="${seen}v" ;;
            module_dir) case "$seen" in *m*) return 1 ;; esac; module="$value"; seen="${seen}m" ;;
            token) case "$seen" in *t*) return 1 ;; esac; token="$value"; seen="${seen}t" ;;
            generation) case "$seen" in *g*) return 1 ;; esac; generation="$value"; seen="${seen}g" ;;
            archive_sha256) case "$seen" in *a*) return 1 ;; esac; archive="$value"; seen="${seen}a" ;;
            completed_epoch) case "$seen" in *e*) return 1 ;; esac; completed="$value"; seen="${seen}e" ;;
            complete) case "$seen" in *c*) return 1 ;; esac; complete="$value"; seen="${seen}c" ;;
            diagnostic) case "$seen" in *d*) return 1 ;; esac; diagnostic="$value"; seen="${seen}d" ;;
            *) return 1 ;;
        esac
    done < "$FULL_ROLLBACK_META"
    [ "${#seen}" -eq 8 ] 2>/dev/null &&
        [ "$version" = "$FULL_ROLLBACK_VERSION" ] && [ "$module" = "/data/adb/modules/zapret2" ] &&
        is_safe_token "$token" && is_safe_token "$generation" && [ "${#generation}" -le 128 ] 2>/dev/null &&
        valid_archive_sha256 "$archive" && is_decimal "$completed" && [ "$complete" = 1 ] &&
        [ -n "$diagnostic" ] || return 1
    ROLLBACK_META_GENERATION="$generation"
    ROLLBACK_META_ARCHIVE_SHA256="$archive"
}

authenticate_completed_rollback_generation() {
    read_completed_rollback_meta || return 1
    read_install_generation_meta "$EXISTING_MODPATH/$INSTALL_GENERATION_META_REL" || return 1
    [ "$ROLLBACK_META_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] || return 1
    ROLLBACK_GENERATION="$ROLLBACK_META_GENERATION"
    ROLLBACK_ARCHIVE_SHA256="$ROLLBACK_META_ARCHIVE_SHA256"
    ROLLBACK_GENERATION_PENDING=1
}

write_install_generation_meta() {
    local generation archive tmp
    generation="$(new_lifecycle_token)" || return 1
    is_safe_token "$generation" && [ "${#generation}" -le 128 ] 2>/dev/null || return 1
    archive="$(compute_archive_sha256)" || return 1
    tmp="$STAGE_PATH/zapret2/.install-generation.meta.$$.$generation"
    [ ! -e "$INSTALL_GENERATION_META" ] && [ ! -L "$INSTALL_GENERATION_META" ] || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    {
        printf 'version=1\n'
        printf 'module_dir=/data/adb/modules/zapret2\n'
        printf 'generation=%s\n' "$generation"
        printf 'archive_sha256=%s\n' "$archive"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv "$tmp" "$INSTALL_GENERATION_META" 2>/dev/null || { rm -f "$tmp"; return 1; }
    read_install_generation_meta "$INSTALL_GENERATION_META" &&
        [ "$INSTALL_META_GENERATION" = "$generation" ] &&
        [ "$INSTALL_META_ARCHIVE_SHA256" = "$archive" ]
}

retire_authenticated_rollback_generation() {
    [ "$ROLLBACK_GENERATION_PENDING" -eq 1 ] || return 0
    audit_live_install_recovery_artifacts || return 1
    [ "$RECOVERY_ARTIFACT_CLASS" = rollback-complete ] || return 1
    read_completed_rollback_meta || return 1
    [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
        [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] || return 1

    [ ! -e "$ROLLBACK_META_BAK" ] && [ ! -L "$ROLLBACK_META_BAK" ] || return 1
    [ ! -e "$ROLLBACK_HOSTS_BAK" ] && [ ! -L "$ROLLBACK_HOSTS_BAK" ] || return 1
    ROLLBACK_ARTIFACT_RESTORE_REQUIRED=1
    if [ -e "$FULL_ROLLBACK_HOSTS_BACKUP" ] || [ -L "$FULL_ROLLBACK_HOSTS_BACKUP" ]; then
        state_file_is_secure "$FULL_ROLLBACK_HOSTS_BACKUP" || return 1
        mv "$FULL_ROLLBACK_HOSTS_BACKUP" "$ROLLBACK_HOSTS_BAK" 2>/dev/null || return 1
    fi
    mv "$FULL_ROLLBACK_META" "$ROLLBACK_META_BAK" 2>/dev/null || return 1
    audit_live_install_recovery_artifacts || return 1
    [ "$RECOVERY_ARTIFACT_CLASS" = clean ] || return 1
    ROLLBACK_GENERATION_PENDING=0
}

if ! acquire_lifecycle_lock; then
    abort "! Zapret2 lifecycle is busy; mutable installation state was not read"
fi
LOCK_ACQUIRED=1

if [ -e "$EXISTING_MODPATH" ] || [ -L "$EXISTING_MODPATH" ]; then
    [ -d "$EXISTING_MODPATH" ] && [ ! -L "$EXISTING_MODPATH" ] || abort "! Refusing unsafe existing module tree"
fi

if ! audit_live_install_recovery_artifacts; then
    abort "! Recovery artifacts block installation: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
fi
case "$RECOVERY_ARTIFACT_CLASS" in
    clean) ;;
    rollback-complete)
        authenticate_completed_rollback_generation || abort "! Completed rollback does not authenticate to the installed generation/archive"
        ;;
    *) abort "! Unexpected recovery-artifact class blocks installation: $RECOVERY_ARTIFACT_CLASS" ;;
esac

if [ -e "$UPDATE_LOCK" ] || [ -L "$UPDATE_LOCK" ] ||
   [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ]; then
    abort "! App hot update is active or requires recovery; mutable installation state was not read"
fi

# Backup user settings before extraction (for updates)
USER_CATEGORIES_INI_BAK="$RECOVERY_ROOT/categories.ini.bak"
USER_RUNTIME_INI_BAK="$RECOVERY_ROOT/runtime.ini.bak"
USER_CONFIG_SH_BAK="$RECOVERY_ROOT/config.sh.bak"
USER_CMDLINE_BAK="$RECOVERY_ROOT/custom-cmdline.bak"
USER_LISTS_BAK="$RECOVERY_ROOT/lists.bak"
USER_LUA_BAK="$RECOVERY_ROOT/user-lua.bak"
MAX_PRESERVED_USER_LUA_BYTES=262144
MAX_PRESERVED_COMMAND_LINE_BYTES=262144
USER_CMDLINE_NAME=""

read_configured_cmdline_name() {
    package_contract_runtime_core_value "$1" custom_cmdline_file
}

configured_cmdline_name_is_safe() {
    package_contract_safe_cmdline_name "$1"
}

if [ -e "$EXISTING_MODPATH" ] || [ -L "$EXISTING_MODPATH" ]; then
    [ -d "$EXISTING_MODPATH" ] && [ ! -L "$EXISTING_MODPATH" ] || abort "! Refusing unsafe existing module tree"
fi

if [ -e "$EXISTING_MODPATH/disable" ] || [ -L "$EXISTING_MODPATH/disable" ]; then
    disable_size=""
    [ -f "$EXISTING_MODPATH/disable" ] && [ ! -L "$EXISTING_MODPATH/disable" ] &&
        path_uid_is_root "$EXISTING_MODPATH/disable" || abort "! Refusing unsafe existing disable marker"
    disable_size="$(wc -c < "$EXISTING_MODPATH/disable" 2>/dev/null)" || abort "! Cannot inspect existing disable marker"
    [ "$disable_size" = 0 ] || abort "! Refusing non-empty existing disable marker"
    cp "$EXISTING_MODPATH/disable" "$DISABLE_MARKER_BAK" || abort "! Failed to snapshot existing disable marker"
    chmod 0600 "$DISABLE_MARKER_BAK" || abort "! Failed to secure disable-marker snapshot"
    [ -f "$DISABLE_MARKER_BAK" ] && [ ! -L "$DISABLE_MARKER_BAK" ] &&
        path_uid_is_root "$DISABLE_MARKER_BAK" && cmp -s "$EXISTING_MODPATH/disable" "$DISABLE_MARKER_BAK" ||
        abort "! Failed to verify disable-marker snapshot"
fi

for preserved in categories.ini runtime.ini config.sh; do
    path="$EXISTING_MODPATH/zapret2/$preserved"
    [ ! -e "$path" ] && [ ! -L "$path" ] && continue
    [ -f "$path" ] && [ ! -L "$path" ] || abort "! Refusing unsafe preserved file: $path"
done
if [ -f "$EXISTING_MODPATH/zapret2/categories.ini" ]; then
    ui_print "- Backing up user strategy settings..."
    cp "$EXISTING_MODPATH/zapret2/categories.ini" "$USER_CATEGORIES_INI_BAK" || abort "! Failed to back up categories.ini"
fi
if [ -f "$EXISTING_MODPATH/zapret2/runtime.ini" ]; then
    ui_print "- Backing up user runtime settings..."
    cp "$EXISTING_MODPATH/zapret2/runtime.ini" "$USER_RUNTIME_INI_BAK" || abort "! Failed to back up runtime.ini"
fi
if [ -f "$EXISTING_MODPATH/zapret2/config.sh" ]; then
    ui_print "- Backing up user bootstrap settings..."
    cp "$EXISTING_MODPATH/zapret2/config.sh" "$USER_CONFIG_SH_BAK" || abort "! Failed to back up config.sh"
fi
if [ -f "$EXISTING_MODPATH/zapret2/runtime.ini" ]; then
    USER_CMDLINE_NAME="$(read_configured_cmdline_name "$EXISTING_MODPATH")" ||
        abort "! Existing runtime.ini has no unique custom command-line file binding"
    configured_cmdline_name_is_safe "$USER_CMDLINE_NAME" ||
        abort "! Existing runtime.ini has an unsafe custom command-line file binding"
    path="$EXISTING_MODPATH/zapret2/$USER_CMDLINE_NAME"
    if [ -e "$path" ] || [ -L "$path" ]; then
        [ -f "$path" ] && [ ! -L "$path" ] && path_uid_is_root "$path" ||
            abort "! Refusing unsafe configured command-line file: $path"
        [ "$(stat -c %h "$path" 2>/dev/null)" = 1 ] ||
            abort "! Refusing hard-linked configured command-line file: $path"
        cmdline_mode="$(stat -c %a "$path" 2>/dev/null)" ||
            abort "! Cannot read configured command-line file mode: $path"
        case "$cmdline_mode" in 600|644) ;; *)
            abort "! Refusing configured command-line file with unsafe mode: $path"
            ;;
        esac
        cmdline_size="$(wc -c < "$path" 2>/dev/null)" ||
            abort "! Cannot measure configured command-line file: $path"
        case "$cmdline_size" in ''|*[!0-9]*) abort "! Invalid configured command-line file size: $path" ;; esac
        [ "$cmdline_size" -le "$MAX_PRESERVED_COMMAND_LINE_BYTES" ] ||
            abort "! Configured command-line file exceeds 256 KiB: $path"
        ui_print "- Backing up configured command line..."
        cp "$path" "$USER_CMDLINE_BAK" || abort "! Failed to back up configured command-line file"
        chmod 0600 "$USER_CMDLINE_BAK" || abort "! Failed to secure command-line backup"
        cmp -s "$path" "$USER_CMDLINE_BAK" || abort "! Failed to verify command-line backup"
    fi
fi
# Only these explicitly user-owned Lua extension points cross an update. Core
# Lua and every other packaged Lua file always come from the new release.
for user_lua in zapret-custom.lua init_vars.lua; do
    path="$EXISTING_MODPATH/zapret2/lua/$user_lua"
    [ ! -e "$path" ] && [ ! -L "$path" ] && continue
    [ -f "$path" ] && [ ! -L "$path" ] || abort "! Refusing unsafe preserved Lua file: $path"
    lua_size="$(wc -c < "$path" 2>/dev/null)" || abort "! Cannot measure preserved Lua file: $path"
    case "$lua_size" in ''|*[!0-9]*) abort "! Invalid preserved Lua file size: $path" ;; esac
    [ "$lua_size" -le "$MAX_PRESERVED_USER_LUA_BYTES" ] || abort "! Preserved Lua file exceeds 256 KiB: $path"
    [ -d "$USER_LUA_BAK" ] || mkdir -p "$USER_LUA_BAK" || abort "! Failed to create user Lua backup"
    cp "$path" "$USER_LUA_BAK/$user_lua" || abort "! Failed to back up $user_lua"
    chmod 0600 "$USER_LUA_BAK/$user_lua" || abort "! Failed to secure $user_lua backup"
    cmp -s "$path" "$USER_LUA_BAK/$user_lua" || abort "! Failed to verify $user_lua backup"
done
if [ -e "$EXISTING_MODPATH/zapret2/lists" ] || [ -L "$EXISTING_MODPATH/zapret2/lists" ]; then
    [ -d "$EXISTING_MODPATH/zapret2/lists" ] && [ ! -L "$EXISTING_MODPATH/zapret2/lists" ] || abort "! Refusing unsafe hostlist directory"
    if find "$EXISTING_MODPATH/zapret2/lists" -type l -print -quit | grep -q .; then abort "! Refusing symlink in preserved hostlists"; fi
    ui_print "- Backing up user hostlists..."
    mkdir -p "$USER_LISTS_BAK" || abort "! Failed to create hostlist backup"
    cp -R "$EXISTING_MODPATH/zapret2/lists/." "$USER_LISTS_BAK/" || abort "! Failed to back up hostlists"
fi

# Extract everything into a private tree.  MODPATH is not touched until this
# tree, preserved user data, architecture binary, wrappers, and permissions
# have all been validated.
ui_print "- Extracting staged module files..."
unzip -o "$ZIPFILE" -x 'META-INF' 'META-INF/' 'META-INF/*' -d "$STAGE_PATH" >&2 || abort "! Module extraction failed"
if find "$STAGE_PATH" -type l -print -quit | grep -q .; then abort "! Module ZIP extracted an unexpected symlink"; fi
grep -qx 'id=zapret2' "$STAGE_PATH/module.prop" || abort "! Extracted module identity is invalid"
package_contract_validate_exact_tree "$STAGE_PATH" package ||
    abort "! Extracted module contains undeclared entries: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_all "$STAGE_PATH" package ||
    abort "! Extracted module violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
[ -s "$STAGE_PATH/zapret2/scripts/zapret-update-guard.sh" ] || abort "! Update guard script is empty"
[ "$(sed -n '1p' "$STAGE_PATH/zapret2/scripts/zapret-update-guard.sh")" = '#!/system/bin/sh' ] || abort "! Update guard script has an invalid shebang"
if LC_ALL=C grep -q "$(printf '\r')" "$STAGE_PATH/zapret2/scripts/zapret-update-guard.sh"; then
    abort "! Update guard script contains CR bytes"
fi
[ "$(sed -n '1p' "$STAGE_PATH/zapret2/scripts/zapret-full-rollback.sh")" = '#!/system/bin/sh' ] || abort "! Full rollback script has an invalid shebang"
if LC_ALL=C grep -q "$(printf '\r')" "$STAGE_PATH/zapret2/scripts/zapret-full-rollback.sh"; then
    abort "! Full rollback script contains CR bytes"
fi
for purge_script in \
    "$STAGE_PATH/zapret2/scripts/lifecycle/purge-contract.sh" \
    "$STAGE_PATH/zapret2/scripts/lifecycle/zapret-purge.sh"; do
    [ -s "$purge_script" ] && [ ! -L "$purge_script" ] || abort "! Purge lifecycle script is missing or unsafe"
    [ "$(sed -n '1p' "$purge_script")" = '#!/system/bin/sh' ] || abort "! Purge lifecycle script has an invalid shebang"
    if LC_ALL=C grep -q "$(printf '\r')" "$purge_script"; then
        abort "! Purge lifecycle script contains CR bytes"
    fi
done
if [ -e "$DISABLE_MARKER_BAK" ] || [ -L "$DISABLE_MARKER_BAK" ]; then
    [ -f "$DISABLE_MARKER_BAK" ] && [ ! -L "$DISABLE_MARKER_BAK" ] || abort "! Disable-marker snapshot became unsafe"
    [ ! -e "$STAGE_PATH/disable" ] && [ ! -L "$STAGE_PATH/disable" ] || abort "! Package contains an unexpected disable marker"
    cp "$DISABLE_MARKER_BAK" "$STAGE_PATH/disable" || abort "! Failed to preserve module disable marker"
    chmod 0600 "$STAGE_PATH/disable" || abort "! Failed to secure preserved disable marker"
    cmp -s "$DISABLE_MARKER_BAK" "$STAGE_PATH/disable" || abort "! Failed to verify preserved disable marker"
fi

# Restore user strategy settings if backup exists
if [ -f "$USER_CATEGORIES_INI_BAK" ]; then
    ui_print "- Restoring user strategy settings..."
    cp "$USER_CATEGORIES_INI_BAK" "$STAGE_PATH/zapret2/categories.ini" || abort "! Failed to restore categories.ini"
    ui_print "  [OK] Strategy settings preserved"
fi

# Restore user runtime settings if backup exists
if [ -f "$USER_RUNTIME_INI_BAK" ]; then
    ui_print "- Restoring user runtime settings..."
    cp "$USER_RUNTIME_INI_BAK" "$STAGE_PATH/zapret2/runtime.ini" || abort "! Failed to restore runtime.ini"
    ui_print "  [OK] Runtime settings preserved"
fi

# Restore user bootstrap settings if backup exists. The persistent
# /data/local/tmp/zapret2-user.conf file is outside MODPATH and is untouched.
if [ -f "$USER_CONFIG_SH_BAK" ]; then
    ui_print "- Restoring user bootstrap settings..."
    cp "$USER_CONFIG_SH_BAK" "$STAGE_PATH/zapret2/config.sh" || abort "! Failed to restore config.sh"
    ui_print "  [OK] Bootstrap settings preserved"
fi

if [ -f "$USER_CMDLINE_BAK" ]; then
    ui_print "- Restoring configured command line..."
    configured_cmdline_name_is_safe "$USER_CMDLINE_NAME" ||
        abort "! Configured command-line file binding changed or became unsafe"
    target="$STAGE_PATH/zapret2/$USER_CMDLINE_NAME"
    [ ! -e "$target" ] && [ ! -L "$target" ] ||
        abort "! Configured command-line target collides with the release package"
    cp "$USER_CMDLINE_BAK" "$target" || abort "! Failed to restore configured command-line file"
    chmod 0644 "$target" || abort "! Failed to set configured command-line permissions"
    [ -f "$target" ] && [ ! -L "$target" ] && path_uid_is_root "$target" &&
        [ "$(stat -c %h "$target" 2>/dev/null)" = 1 ] &&
        [ "$(stat -c %a "$target" 2>/dev/null)" = 644 ] &&
        cmp -s "$USER_CMDLINE_BAK" "$target" ||
        abort "! Failed to verify restored configured command-line file"
    ui_print "  [OK] Configured command line preserved"
fi

# Restore only the approved user extension points. The staged release's core
# Lua files are deliberately never replaced by an older installation.
if [ -d "$USER_LUA_BAK" ]; then
    ui_print "- Restoring approved user Lua extensions..."
    for user_lua in zapret-custom.lua init_vars.lua; do
        backup="$USER_LUA_BAK/$user_lua"
        [ ! -e "$backup" ] && [ ! -L "$backup" ] && continue
        [ -f "$backup" ] && [ ! -L "$backup" ] || abort "! Unsafe user Lua backup: $backup"
        lua_size="$(wc -c < "$backup" 2>/dev/null)" || abort "! Cannot measure user Lua backup: $backup"
        case "$lua_size" in ''|*[!0-9]*) abort "! Invalid user Lua backup size: $backup" ;; esac
        [ "$lua_size" -le "$MAX_PRESERVED_USER_LUA_BYTES" ] || abort "! User Lua backup exceeds 256 KiB: $backup"
        target="$STAGE_PATH/zapret2/lua/$user_lua"
        cp "$backup" "$target" || abort "! Failed to restore $user_lua"
        cmp -s "$backup" "$target" || abort "! Failed to verify restored $user_lua"
    done
    ui_print "  [OK] Approved user Lua extensions preserved"
fi

# Normalize the unsupported legacy value inside the private staged tree before
# any boot path can consume it. Other runtime sections remain unchanged.
normalize_staged_legacy_wifi_only() {
    runtime_file="$STAGE_PATH/zapret2/runtime.ini"
    bootstrap_file="$STAGE_PATH/zapret2/config.sh"
    runtime_tmp="$INSTALL_TMP/runtime.ini.normalized"
    bootstrap_tmp="$INSTALL_TMP/config.sh.normalized"
    if [ -f "$runtime_file" ]; then
        awk '
            {
                trimmed=$0
                sub(/^[[:space:]]*/, "", trimmed)
                sub(/[[:space:]]*$/, "", trimmed)
                if (trimmed ~ /^\[[^]]+\]$/) section=trimmed
                if (section == "[core]" && trimmed ~ /^wifi_only[[:space:]]*=[[:space:]]*1$/) {
                    print "wifi_only=0"
                    next
                }
                print
            }
        ' "$runtime_file" > "$runtime_tmp" || return 1
        mv -f "$runtime_tmp" "$runtime_file" || return 1
    fi
    if [ -f "$bootstrap_file" ]; then
        sed 's/^[[:space:]]*WIFI_ONLY[[:space:]]*=[[:space:]]*1[[:space:]]*$/WIFI_ONLY=0/' \
            "$bootstrap_file" > "$bootstrap_tmp" || return 1
        mv -f "$bootstrap_tmp" "$bootstrap_file" || return 1
    fi
}

normalize_staged_legacy_wifi_only || abort "! Failed to normalize legacy WIFI_ONLY=1 before boot"

# Restore the complete list directory so custom files and edits survive an
# update. New distribution files remain present unless an old file replaces
# the same path intentionally.
if [ -d "$USER_LISTS_BAK" ]; then
    ui_print "- Restoring user hostlists..."
    mkdir -p "$STAGE_PATH/zapret2/lists" || abort "! Failed to create hostlist directory"
    cp -R "$USER_LISTS_BAK/." "$STAGE_PATH/zapret2/lists/" || abort "! Failed to restore hostlists"
    ui_print "  [OK] Hostlists preserved"
fi

# Copy architecture-specific binary
if [ -f "$STAGE_PATH/zapret2/bin/$ARCH_DIR/nfqws2" ]; then
    [ ! -L "$STAGE_PATH/zapret2/bin/$ARCH_DIR/nfqws2" ] || abort "! Architecture binary is an unsafe symlink"
    cp "$STAGE_PATH/zapret2/bin/$ARCH_DIR/nfqws2" "$STAGE_PATH/zapret2/nfqws2" || abort "! Failed to install nfqws2 for $ARCH_DIR"
    [ -s "$STAGE_PATH/zapret2/nfqws2" ] || abort "! Installed nfqws2 is empty"
    ui_print "- Copied nfqws2 binary for $ARCH_DIR"
else
    abort "! Module ZIP has no nfqws2 binary for $ARCH_DIR"
fi

# Set permissions
ui_print "- Setting permissions..."

# Set directory permissions (0755 = rwxr-xr-x)
find "$STAGE_PATH" -type d -exec chmod 0755 {} \; || abort "! Failed to set directory permissions"

# Set file permissions (0644 = rw-r--r--)
find "$STAGE_PATH" -type f -exec chmod 0644 {} \; || abort "! Failed to set file permissions"

# Apply the authoritative manifest modes after user-data restoration and
# architecture selection. Optional user files retain the conservative 0644
# default above; every contract entry receives its exact declared mode.
package_contract_apply_modes "$STAGE_PATH" installed ||
    abort "! Failed to apply runtime manifest modes: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_modes "$STAGE_PATH" installed ||
    abort "! Runtime manifest mode verification failed: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
if [ -e "$STAGE_PATH/disable" ] || [ -L "$STAGE_PATH/disable" ]; then
    [ -f "$STAGE_PATH/disable" ] && [ ! -L "$STAGE_PATH/disable" ] || abort "! Preserved disable marker became unsafe"
    set_perm "$STAGE_PATH/disable" 0 0 0600 || abort "! Failed to set disable-marker permissions"
    disable_size="$(wc -c < "$STAGE_PATH/disable" 2>/dev/null)" || abort "! Cannot revalidate preserved disable marker"
    [ "$disable_size" = 0 ] && path_uid_is_root "$STAGE_PATH/disable" && mode_is_0600 "$STAGE_PATH/disable" ||
        abort "! Preserved disable marker is not the exact safe root-private marker"
fi
category_validation="$(/system/bin/sh "$STAGE_PATH/zapret2/scripts/command-builder.sh" \
    --validate-categories-machine "$STAGE_PATH/zapret2")" ||
    abort "! Active category configuration is invalid or references unsafe/missing data"
[ "$category_validation" = "$(printf 'Z2_CATEGORIES\tOK')" ] ||
    abort "! Category validator returned an invalid machine result"

write_install_generation_meta || abort "! Failed to publish authenticated install generation"
PUBLISHED_INSTALL_GENERATION="$INSTALL_META_GENERATION"
PUBLISHED_INSTALL_ARCHIVE_SHA256="$INSTALL_META_ARCHIVE_SHA256"

# Make sure bin and lua directories are accessible
chmod 0755 "$STAGE_PATH/system" "$STAGE_PATH/system/bin" "$STAGE_PATH/zapret2/bin" "$STAGE_PATH/zapret2/lua" "$STAGE_PATH/zapret2/lists" "$STAGE_PATH/zapret2/scripts" "$STAGE_PATH/zapret2" || abort "! Failed to secure module directories"

# Set read permissions on all data files
find "$STAGE_PATH/zapret2/bin" -type f -name '*.bin' -exec chmod 0644 {} \; || abort "! Failed to set blob permissions"
find "$STAGE_PATH/zapret2/lua" -type f -name '*.lua' -exec chmod 0644 {} \; || abort "! Failed to set Lua permissions"
find "$STAGE_PATH/zapret2/lists" -type f -name '*.txt' -exec chmod 0644 {} \; || abort "! Failed to set hostlist permissions"

# Check kernel requirements
ui_print "- Checking kernel requirements..."

# Check for NFQUEUE support (legacy + modern indicators)
NFQUEUE_SUPPORTED=0

if [ -f /proc/net/netfilter/nf_queue ] || [ -f /proc/net/netfilter/nfnetlink_queue ]; then
    NFQUEUE_SUPPORTED=1
elif grep -qs NFQUEUE /proc/net/ip_tables_targets /proc/net/ip6_tables_targets; then
    NFQUEUE_SUPPORTED=1
fi

if [ "$NFQUEUE_SUPPORTED" -eq 1 ]; then
    ui_print "  [OK] NFQUEUE support found"
else
    ui_print "  [!] NFQUEUE support not detected"
    ui_print "      Checked: nf_queue, nfnetlink_queue, ip_tables_targets"
    ui_print "      Module may not work on this kernel"
fi

# Check iptables
if command -v iptables >/dev/null 2>&1; then
    ui_print "  [OK] iptables found"
else
    ui_print "  [!] iptables not found"
fi

# Validate the regular wrappers shipped as static package assets. A symlink
# changes $0 as observed by the target script and breaks its relative paths.
verify_command_wrapper() {
    wrapper_root="$1"
    wrapper_name="$2"
    wrapper="$wrapper_root/system/bin/zapret2-$wrapper_name"
    target="/data/adb/modules/zapret2/zapret2/scripts/zapret-$wrapper_name.sh"
    expected_wrapper="$INSTALL_TMP/zapret2-$wrapper_name.expected"
    [ -f "$wrapper" ] && [ ! -L "$wrapper" ] && [ -x "$wrapper" ] || return 1
    {
        printf '%s\n' '#!/system/bin/sh'
        printf 'exec %s "$@"\n' "$target"
    } > "$expected_wrapper" || return 1
    cmp -s "$expected_wrapper" "$wrapper"
}

[ -d "$STAGE_PATH/system/bin" ] && [ ! -L "$STAGE_PATH/system" ] && [ ! -L "$STAGE_PATH/system/bin" ] || abort "! Static command wrapper directory is missing or unsafe"
for command_name in start stop status restart full-rollback; do
    verify_command_wrapper "$STAGE_PATH" "$command_name" || abort "! Invalid static $command_name command wrapper"
done

ZAPRET_DIR="$STAGE_PATH/zapret2"
SCRIPT_DIR="$ZAPRET_DIR/scripts"
STAGED_COMMON_SCRIPT="$SCRIPT_DIR/common.sh"
[ -f "$STAGED_COMMON_SCRIPT" ] && [ ! -L "$STAGED_COMMON_SCRIPT" ] &&
    cmp -s "$COMMON_BOOTSTRAP" "$STAGED_COMMON_SCRIPT" || abort "! Staged lifecycle helpers changed after lock acquisition"

clear_prior_uninstall_tombstone() {
    tombstone_backup="$RECOVERY_ROOT/uninstall.tombstone"
    [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ] || return 0
    [ ! -L "$UNINSTALL_TOMBSTONE" ] && state_file_is_secure "$UNINSTALL_TOMBSTONE" || return 1
    read_uninstall_tombstone || return 1
    [ "$UNINSTALL_FILE_MODULE" = "/data/adb/modules/zapret2" ] || return 1
    uninstall_tombstone_owner_alive && return 1
    [ "$LOCK_HELD" = 1 ] && read_lock_owner || return 1
    [ "$LOCK_FILE_PID" = "$$" ] && [ "$LOCK_FILE_START" = "$(proc_starttime "$$")" ] || return 1
    if [ -e "$UPDATE_LOCK" ] || [ -L "$UPDATE_LOCK" ] ||
       [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ]; then
        return 1
    fi
    for artifact in "$UPDATE_TRANSACTION".tmp "$UPDATE_TRANSACTION".tmp.* "$UNINSTALL_TOMBSTONE".tmp "$UNINSTALL_TOMBSTONE".tmp.*; do
        if [ -e "$artifact" ] || [ -L "$artifact" ]; then
            return 1
        fi
    done
    cp "$UNINSTALL_TOMBSTONE" "$tombstone_backup" || return 1
    chmod 0600 "$tombstone_backup" || return 1
    cmp -s "$UNINSTALL_TOMBSTONE" "$tombstone_backup" || return 1
    # Arm rollback before removing the live tombstone. The trap therefore
    # restores it even if a signal arrives during or immediately after rm.
    TOMBSTONE_RESTORE_REQUIRED=1
    rm -f "$UNINSTALL_TOMBSTONE" || return 1
    [ ! -e "$UNINSTALL_TOMBSTONE" ] && [ ! -L "$UNINSTALL_TOMBSTONE" ] || return 1
}

live_install_firewall_is_clean() {
    local family_state
    if ! command -v iptables >/dev/null 2>&1 ||
       ! iptables -t mangle -S OUTPUT >/dev/null 2>&1; then
        return 1
    fi
    owned_family_present iptables; family_state=$?
    [ "$family_state" = 1 ] || return 1

    if command -v ip6tables >/dev/null 2>&1; then
        ip6tables -t mangle -S OUTPUT >/dev/null 2>&1 || return 1
        owned_family_present ip6tables; family_state=$?
        [ "$family_state" = 1 ] || return 1
    else
        read_iptables_status >/dev/null 2>&1 || true
        [ "${STATUS_FILE_IPV6_ACTIVE:-0}" = 1 ] && return 1
    fi
    return 0
}

verify_live_module_quiescent() {
    local live_binary live_pids
    live_binary="$LIVE_NFQWS2"
    scan_exact_owned_nfqws_for_path "$live_binary" >/dev/null 2>&1 || return 1
    live_pids="$OWNED_SCAN_PIDS"

    [ -z "$live_pids" ] || return 1
    [ ! -e "$PIDFILE" ] && [ ! -L "$PIDFILE" ] || return 1
    [ ! -e "$OWNER_STATE" ] && [ ! -L "$OWNER_STATE" ] || return 1
    live_install_firewall_is_clean
}

quiesce_live_module_before_publish() {
    local live_common live_stop live_status live_start live_binary live_required
    # A normal Magisk update publishes under modules_update and cannot replace
    # the live executable. Direct/live installs must first stop the exact old
    # module while inheriting this installer's lifecycle lock, then prove both
    # its process and owned firewall family are gone before the first move.
    [ "$MODPATH" = "$EXISTING_MODPATH" ] || return 0

    if [ "$ORIGINAL_WAS_PRESENT" -eq 1 ]; then
        live_common="$EXISTING_MODPATH/zapret2/scripts/common.sh"
        live_stop="$EXISTING_MODPATH/zapret2/scripts/zapret-stop.sh"
        live_status="$EXISTING_MODPATH/zapret2/scripts/zapret-status.sh"
        live_start="$EXISTING_MODPATH/zapret2/scripts/zapret-start.sh"
        live_binary="$EXISTING_MODPATH/zapret2/nfqws2"
        for live_required in "$live_common" "$live_stop" "$live_status" "$live_start" "$live_binary"; do
            [ -f "$live_required" ] && [ ! -L "$live_required" ] || return 1
        done
        snapshot_live_service_state || return 1
        if [ "$LIVE_PRIOR_SERVICE_STATE" = running ]; then
            LIVE_SERVICE_RESTORE_REQUIRED=1
            ui_print "- Stopping the active module before replacing its binary..."
            /system/bin/sh "$live_stop" || return 1
            rollback_serialization_is_held || return 1
        fi
    fi

    verify_live_module_quiescent
}

# Revalidate serialization and all recovery evidence immediately before the
# first live mutation. Mutable user state was snapshotted only while this same
# lock was continuously held.
rollback_serialization_is_held || abort "! Installation lost lifecycle serialization before commit"
if ! audit_live_install_recovery_artifacts; then
    abort "! Recovery artifacts changed before installation mutation: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
fi
case "$RECOVERY_ARTIFACT_CLASS:$ROLLBACK_GENERATION_PENDING" in
    clean:0) ;;
    rollback-complete:1)
        read_completed_rollback_meta &&
            [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
            [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] ||
            abort "! Completed rollback identity changed before installation mutation"
        ;;
    *) abort "! Recovery-artifact class changed before installation mutation" ;;
esac

# A Magisk install must never publish over an active or recoverable app hot
# update. This gate runs while the lifecycle lock is held and before the first
# mutation of MODPATH; presence, including a symlink, is an unconditional
# refusal so the staged tree and durable prior tree remain untouched.
if [ -e "$UPDATE_LOCK" ] || [ -L "$UPDATE_LOCK" ] ||
   [ -e "$UPDATE_TRANSACTION" ] || [ -L "$UPDATE_TRANSACTION" ]; then
    abort "! App hot update is active or requires recovery; staged installation was not committed"
fi

if [ -e "$MODPATH" ] || [ -L "$MODPATH" ]; then
    [ -d "$MODPATH" ] && [ ! -L "$MODPATH" ] || abort "! Refusing unsafe installation target"
    ORIGINAL_WAS_PRESENT=1
fi

# Clear only a proven stale uninstall gate while serialization is held. Its
# durable copy is already armed for rollback before the live file is removed.
if ! clear_prior_uninstall_tombstone; then
    abort "! Prior uninstall tombstone is active or unsafe; the previous module tree was not changed"
fi

if ! quiesce_live_module_before_publish; then
    abort "! Active module could not be stopped and verified clean; its live binary was not replaced"
fi
if [ "$MODPATH" = "$EXISTING_MODPATH" ]; then
    INSTALL_DEFER_LEGACY_OWNER_RECOVERY=0
    if ! audit_live_install_recovery_artifacts; then
        abort "! Quiesced owner recovery audit failed: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
    fi
    case "$RECOVERY_ARTIFACT_CLASS:$ROLLBACK_GENERATION_PENDING" in
        clean:0) ;;
        rollback-complete:1)
            read_completed_rollback_meta &&
                [ "$ROLLBACK_META_GENERATION" = "$ROLLBACK_GENERATION" ] &&
                [ "$ROLLBACK_META_ARCHIVE_SHA256" = "$ROLLBACK_ARCHIVE_SHA256" ] ||
                abort "! Completed rollback identity changed during direct-install quiesce"
            ;;
        *) abort "! Recovery-artifact class changed after direct-install quiesce" ;;
    esac
fi

OVERWRITE_STARTED=1
if [ "$ORIGINAL_WAS_PRESENT" -eq 1 ]; then
    mv "$MODPATH" "$ORIGINAL_MODULE_BACKUP" || abort "! Failed to preserve the previous module tree"
fi
mv "$STAGE_PATH" "$MODPATH" || abort "! Failed to atomically publish the staged module tree"

package_contract_validate_tree "$MODPATH" installed ||
    abort "! Published module violates runtime manifest: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_modes "$MODPATH" installed ||
    abort "! Published module has invalid runtime manifest modes: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
[ -f "$MODPATH/$INSTALL_GENERATION_META_REL" ] && [ ! -L "$MODPATH/$INSTALL_GENERATION_META_REL" ] &&
    read_install_generation_meta "$MODPATH/$INSTALL_GENERATION_META_REL" &&
    [ "$INSTALL_META_GENERATION" = "$PUBLISHED_INSTALL_GENERATION" ] &&
    [ "$INSTALL_META_ARCHIVE_SHA256" = "$PUBLISHED_INSTALL_ARCHIVE_SHA256" ] ||
    abort "! Published install generation failed authentication"
if [ -f "$DISABLE_MARKER_BAK" ] && [ ! -L "$DISABLE_MARKER_BAK" ]; then
    [ -f "$MODPATH/disable" ] && [ ! -L "$MODPATH/disable" ] &&
        path_uid_is_root "$MODPATH/disable" && mode_is_0600 "$MODPATH/disable" &&
        cmp -s "$DISABLE_MARKER_BAK" "$MODPATH/disable" || abort "! Published module lost the exact disable marker"
elif [ -e "$MODPATH/disable" ] || [ -L "$MODPATH/disable" ]; then
    abort "! Published module acquired an unexpected disable marker"
fi
[ -x "$MODPATH/action.sh" ] || abort "! Published module action entry is not executable"
[ -s "$MODPATH/zapret2/scripts/zapret-update-guard.sh" ] && [ -x "$MODPATH/zapret2/scripts/zapret-update-guard.sh" ] || abort "! Published update guard script is empty or not executable"
[ -x "$MODPATH/zapret2/scripts/zapret-full-rollback.sh" ] || abort "! Published full rollback script is not executable"
for purge_script in \
    "$MODPATH/zapret2/scripts/lifecycle/purge-contract.sh" \
    "$MODPATH/zapret2/scripts/lifecycle/zapret-purge.sh"; do
    [ -s "$purge_script" ] && [ -x "$purge_script" ] && [ ! -L "$purge_script" ] ||
        abort "! Published purge lifecycle script is missing, unsafe, or not executable"
done
for command_name in start stop status restart full-rollback; do
    verify_command_wrapper "$MODPATH" "$command_name" || abort "! Published command wrapper verification failed: $command_name"
done

# Arm the commit handoff while the lifecycle lock is still held. From this
# point through INSTALL_COMMITTED there must be no signal trap capable of
# moving MODPATH: a signal is either handled above while locked or ignored.
COMMIT_HANDOFF=1
trap '' HUP INT TERM
if ! audit_live_install_recovery_artifacts; then
    abort "! Recovery-artifact commit audit failed: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
fi
case "$RECOVERY_ARTIFACT_CLASS:$ROLLBACK_GENERATION_PENDING" in
    clean:0) ;;
    rollback-complete:1) ;;
    *) abort "! Recovery-artifact class changed at installation commit" ;;
esac
retire_authenticated_rollback_generation || abort "! Authenticated prior rollback generation could not be retired safely"
if ! audit_live_install_recovery_artifacts || [ "$RECOVERY_ARTIFACT_CLASS" != clean ]; then
    abort "! Recovery artifacts are not clean after installation commit retirement: ${RECOVERY_ARTIFACT_DIAGNOSTIC:-unknown state}"
fi
if ! release_lifecycle_lock; then
    abort "! Lifecycle lock release failed; the previous module tree will be restored"
fi
LOCK_ACQUIRED=0
INSTALL_COMMITTED=1
COMMIT_HANDOFF=0

remove_private_installer_tree "$RECOVERY_ROOT" recovery || abort "! Installed successfully but could not remove the obsolete recovery tree"
remove_private_installer_tree "$INSTALL_TMP" install || abort "! Installed successfully but could not remove the private install tree"
trap - EXIT HUP INT TERM

ui_print ""
ui_print "===================================="
ui_print " Zapret2 installed successfully!"
ui_print "===================================="
ui_print ""
ui_print " Control: install/open the signed Zapret2 Android app"
ui_print ""
ui_print " Terminal commands:"
ui_print "   zapret2-start   - Start"
ui_print "   zapret2-stop    - Stop"
ui_print "   zapret2-restart - Restart (fast by default)"
ui_print "   zapret2-status  - Status"
ui_print ""
ui_print " Config files:"
ui_print "   Runtime:    $MODPATH/zapret2/runtime.ini"
ui_print "   Categories: $MODPATH/zapret2/categories.ini"
ui_print "   TCP:        $MODPATH/zapret2/strategies-tcp.ini"
ui_print "   UDP:        $MODPATH/zapret2/strategies-udp.ini"
ui_print "   STUN:       $MODPATH/zapret2/strategies-stun.ini"
ui_print ""
