#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/packaging-recovery"
STATE="$CASE/state"
AUDIT_MOD="$CASE/audit-module"
FIXTURE="$CASE/package"
PACKAGE_SOURCE="$CASE/package-source"
ARCHIVE="$CASE/module.zip"
MOCK="$CASE/bin"
LIVE=/data/adb/modules/zapret2
UPDATE=/data/adb/modules_update/zapret2
LIVE_STATE=/data/adb/zapret2-state
UNINSTALL_CLEANUP=/data/adb/modules/.zapret2-failed-packaging-uninstall-cleanup
SYSTEM_CREATED=0
FIXTURE_OWNED=0
LIVE_TEST_PID=""

fail() { echo "FAIL: packaging recovery: $*" >&2; exit 1; }

cleanup() {
    [ "$FIXTURE_OWNED" = 1 ] || return 0
    if [ -n "$LIVE_TEST_PID" ]; then
        kill "$LIVE_TEST_PID" 2>/dev/null || true
        wait "$LIVE_TEST_PID" 2>/dev/null || true
    fi
    rm -rf "$LIVE" "$UPDATE" "$LIVE_STATE" "$UNINSTALL_CLEANUP" "$CASE"
    if [ "$SYSTEM_CREATED" = 1 ]; then
        rm -f /system/bin/sh
        rmdir /system/bin 2>/dev/null || true
        rmdir /system 2>/dev/null || true
    fi
}

# Destructive cleanup is armed only after a root-only, exact-target preflight.
# A failure before this point must never install a trap that removes paths the
# fixture has not proven it owns.
[ "$(id -u)" = 0 ] || fail "run as root"
for path in "$LIVE" "$UPDATE" "$LIVE_STATE" "$UNINSTALL_CLEANUP" "$CASE"; do
    [ ! -e "$path" ] && [ ! -L "$path" ] || fail "test path already exists: $path"
done
FIXTURE_OWNED=1
trap cleanup EXIT HUP INT TERM
mkdir -p /data/adb/modules /data/adb/modules_update "$CASE" "$STATE" "$AUDIT_MOD/zapret2" "$MOCK"
chmod 0700 "$STATE"

# Regression for trap ordering: a recursively invoked fixture must reject the
# already-existing target before arming cleanup, leaving foreign/preflight data
# untouched.  The outer fixture owns and removes this sentinel afterward.
mkdir -p "$LIVE"
: > "$LIVE/preflight-sentinel"
if Z2_TEST_TMP="$TMP" sh "$0" >/dev/null 2>&1; then fail "recursive preflight unexpectedly accepted an existing target"; fi
[ -f "$LIVE/preflight-sentinel" ] || fail "failed preflight deleted an existing target"
rm -rf "$LIVE"

# Exercise the shared recovery-artifact classifier, including malformed and
# mixed evidence. These are behavioral checks, not source-string assertions.
STATE_DIR="$STATE"
ZAPRET_DIR="$AUDIT_MOD/zapret2"
MODDIR="$AUDIT_MOD"
SCRIPT_DIR="$ROOT/zapret2/scripts"
. "$ROOT/zapret2/scripts/common.sh"
audit_recovery_artifacts install || fail "clean install audit was blocked"
[ "$RECOVERY_ARTIFACT_CLASS" = clean ] || fail "clean audit class"

: > "$UPDATE_LOCK"
chmod 0600 "$UPDATE_LOCK"
if audit_recovery_artifacts install; then fail "update recovery artifact was accepted"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = update ] || fail "update audit class"
rm -f "$UPDATE_LOCK"

# Canonical update transaction is admitted to lifecycle only after the exact
# live lock PID/start/token and transaction owner binding are verified.
owner_start=$(awk '{print $22}' "/proc/$$/stat")
owner_boot=$(cat /proc/sys/kernel/random/boot_id)
cat > "$UPDATE_LOCK" <<EOF
version=2
pid=$$
starttime=$owner_start
created_epoch=123
boot_id=$owner_boot
token=authorized-token
module_dir=$MODDIR
EOF
cat > "$UPDATE_TRANSACTION" <<EOF
version=3
transaction_id=$$-12345
phase=prepared
created_epoch=123
pre_update_state=stopped
disable_marker_expectation=absent
owner_pid=$$
owner_starttime=$owner_start
owner_created_epoch=123
owner_boot_id=$owner_boot
module_dir=$MODDIR
update_dir=/data/adb/modules/.zapret2-update-$$-12345
backup_dir=/data/adb/modules/.zapret2-backup-$$-12345
failed_dir=/data/adb/modules/.zapret2-failed-$$-12345
EOF
chmod 0600 "$UPDATE_LOCK" "$UPDATE_TRANSACTION"
cp "$UPDATE_TRANSACTION" "$CASE/update-transaction.valid"

# The shared v2 golden fixture is accepted after binding its module path to
# this isolated shell fixture.
sed "s|^module_dir=.*|module_dir=$MODDIR|" "$ROOT/tests/fixtures/update-transaction-v2.golden" > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
read_update_transaction_gate_file || fail "shared v2 transaction fixture was rejected"
cp "$CASE/update-transaction.valid" "$UPDATE_TRANSACTION"; chmod 0600 "$UPDATE_TRANSACTION"

for phase in prepared stopped candidate_ready active_move_intent active_moved candidate_active verified restored \
    restore_copying restore_candidate_ready restore_active_moved restore_candidate_active; do
    sed "s/^phase=.*/phase=$phase/" "$CASE/update-transaction.valid" > "$UPDATE_TRANSACTION"
    chmod 0600 "$UPDATE_TRANSACTION"
    read_update_transaction_gate_file || fail "v3 phase was rejected: $phase"
done
cp "$CASE/update-transaction.valid" "$UPDATE_TRANSACTION"; chmod 0600 "$UPDATE_TRANSACTION"

grep -v '^disable_marker_expectation=' "$CASE/update-transaction.valid" > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
if read_update_transaction_gate_file; then fail "missing disable-marker expectation was accepted"; fi
{ cat "$CASE/update-transaction.valid"; echo disable_marker_expectation=absent; } > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
if read_update_transaction_gate_file; then fail "duplicate disable-marker expectation was accepted"; fi
{ cat "$CASE/update-transaction.valid"; echo future=value; } > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
if read_update_transaction_gate_file; then fail "unknown v2 transaction field was accepted"; fi
sed 's/^pre_update_state=.*/pre_update_state=running/; s/^disable_marker_expectation=.*/disable_marker_expectation=present/' \
    "$CASE/update-transaction.valid" > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
if read_update_transaction_gate_file; then fail "PRESENT+RUNNING transaction was accepted"; fi
cp "$CASE/update-transaction.valid" "$UPDATE_TRANSACTION"; chmod 0600 "$UPDATE_TRANSACTION"
unset ZAPRET2_UPDATE_TOKEN ZAPRET2_UPDATE_OWNER_PID ZAPRET2_UPDATE_OWNER_START ZAPRET2_UPDATE_OWNER_CREATED ZAPRET2_UPDATE_OWNER_BOOT
if update_lock_allows_stop; then fail "normal stop was authorized for active update transaction"; fi
if audit_recovery_artifacts lifecycle; then fail "normal lifecycle audit admitted update transaction"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = update ] || fail "authorized transaction fixture was not classified as update"
ZAPRET2_UPDATE_TOKEN=authorized-token ZAPRET2_UPDATE_OWNER_PID=$$ ZAPRET2_UPDATE_OWNER_START=$owner_start ZAPRET2_UPDATE_OWNER_CREATED=123 ZAPRET2_UPDATE_OWNER_BOOT=$owner_boot
export ZAPRET2_UPDATE_TOKEN ZAPRET2_UPDATE_OWNER_PID ZAPRET2_UPDATE_OWNER_START ZAPRET2_UPDATE_OWNER_CREATED ZAPRET2_UPDATE_OWNER_BOOT
for owner_field in owner_pid owner_starttime owner_created_epoch owner_boot_id; do
    sed "s/^$owner_field=.*/$owner_field=999999/" "$CASE/update-transaction.valid" > "$UPDATE_TRANSACTION"
    chmod 0600 "$UPDATE_TRANSACTION"
    if update_lock_allows_stop; then fail "wrong transaction $owner_field was authorized"; fi
done
cp "$CASE/update-transaction.valid" "$UPDATE_TRANSACTION"; chmod 0600 "$UPDATE_TRANSACTION"
update_lock_allows_stop || fail "exact update transaction owner could not authorize stop"
audit_recovery_artifacts lifecycle || fail "authorized update transaction was blocked by recovery audit"
sed 's/^phase=.*/phase=active_move_intent/' "$CASE/update-transaction.valid" > "$UPDATE_TRANSACTION"
chmod 0600 "$UPDATE_TRANSACTION"
update_lock_allows_stop || fail "exact live owner could not authorize active_move_intent lifecycle"
audit_recovery_artifacts lifecycle || fail "authorized active_move_intent transaction was blocked"
unset ZAPRET2_UPDATE_TOKEN ZAPRET2_UPDATE_OWNER_PID ZAPRET2_UPDATE_OWNER_START ZAPRET2_UPDATE_OWNER_CREATED ZAPRET2_UPDATE_OWNER_BOOT
rm -f "$UPDATE_LOCK" "$UPDATE_TRANSACTION"

cat > "$FULL_ROLLBACK_TRANSACTION" <<EOF
version=1
module_dir=$MODDIR
token=partial-token
phase=armed
EOF
chmod 0600 "$FULL_ROLLBACK_TRANSACTION"
if audit_recovery_artifacts uninstall; then fail "partial rollback was accepted by uninstall"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = rollback-partial ] || fail "partial rollback audit class"
rm -f "$FULL_ROLLBACK_TRANSACTION"

cat > "$FULL_ROLLBACK_META" <<EOF
version=1
module_dir=$MODDIR
token=done-token
generation=generation-one
archive_sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
completed_epoch=1
complete=1
diagnostic=full rollback complete; reboot required
EOF
chmod 0600 "$FULL_ROLLBACK_META"
audit_recovery_artifacts uninstall || fail "valid completed rollback was blocked"
[ "$RECOVERY_ARTIFACT_CLASS" = rollback-complete ] || fail "completed rollback audit class"

# Completed rollback evidence is not a general lifecycle bypass.  Only the
# exact live uninstall tombstone owner, bound to the active lifecycle lock, is
# admitted; install accepts only a canonical authenticated dead-owner tombstone.
if audit_recovery_artifacts lifecycle; then fail "completed rollback without tombstone admitted ordinary lifecycle"; fi
cat > "$UNINSTALL_TOMBSTONE" <<EOF
version=1
pid=$$
starttime=$owner_start
token=uninstall-owner-token
module_dir=$MODDIR
EOF
mkdir "$LIFECYCLE_LOCK"
cat > "$LIFECYCLE_LOCK_OWNER" <<EOF
pid=$$
starttime=$owner_start
token=uninstall-owner-token
EOF
chmod 0600 "$UNINSTALL_TOMBSTONE" "$LIFECYCLE_LOCK_OWNER"
ZAPRET2_UNINSTALL_TOKEN=uninstall-owner-token
ZAPRET2_UNINSTALL_OWNER_PID=$$
ZAPRET2_UNINSTALL_OWNER_START=$owner_start
export ZAPRET2_UNINSTALL_TOKEN ZAPRET2_UNINSTALL_OWNER_PID ZAPRET2_UNINSTALL_OWNER_START
audit_recovery_artifacts lifecycle || fail "exact active uninstall owner was blocked from completed rollback lifecycle"
if audit_recovery_artifacts install; then fail "active uninstall tombstone was accepted by install"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = unsafe ] || fail "active tombstone was not classified unsafe"
unset ZAPRET2_UNINSTALL_TOKEN ZAPRET2_UNINSTALL_OWNER_PID ZAPRET2_UNINSTALL_OWNER_START
rm -rf "$LIFECYCLE_LOCK" "$UNINSTALL_TOMBSTONE"

cat > "$UNINSTALL_TOMBSTONE" <<EOF
version=1
pid=99999999
starttime=1
token=stale-uninstall-token
module_dir=$MODDIR
EOF
chmod 0600 "$UNINSTALL_TOMBSTONE"
audit_recovery_artifacts install || fail "stale authenticated canonical dead-owner tombstone was blocked"
[ "$RECOVERY_ARTIFACT_CLASS" = rollback-complete ] || fail "stale canonical tombstone changed rollback-complete classification"
rm -f "$UNINSTALL_TOMBSTONE"

cat > "$UNINSTALL_TOMBSTONE" <<EOF
version=1
pid=99999999
starttime=1
token=foreign-uninstall-token
module_dir=/data/adb/modules/foreign
EOF
chmod 0600 "$UNINSTALL_TOMBSTONE"
if audit_recovery_artifacts install; then fail "foreign uninstall tombstone was accepted"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = unsafe ] || fail "foreign tombstone was not classified unsafe"
rm -f "$UNINSTALL_TOMBSTONE"

: > "$UPDATE_LOCK"
chmod 0600 "$UPDATE_LOCK"
if audit_recovery_artifacts install; then fail "mixed update/rollback recovery was accepted"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = mixed ] || fail "mixed recovery audit class"
rm -f "$UPDATE_LOCK" "$FULL_ROLLBACK_META"
ln -s missing-target "$FULL_ROLLBACK_META"
if audit_recovery_artifacts uninstall; then fail "unsafe symlink recovery artifact was accepted"; fi
[ "$RECOVERY_ARTIFACT_CLASS" = unsafe ] || fail "unsafe recovery audit class"
rm -f "$FULL_ROLLBACK_META"

# Build a realistic installer archive from the current package contract.
mkdir -p "$FIXTURE" "$PACKAGE_SOURCE"
cp "$ROOT/module.prop" "$ROOT/customize.sh" "$ROOT/service.sh" "$ROOT/uninstall.sh" \
    "$ROOT/action.sh" "$PACKAGE_SOURCE/"
cp -R "$ROOT/system" "$ROOT/zapret2" "$PACKAGE_SOURCE/"
mkdir -p "$PACKAGE_SOURCE/zapret2/bin/arm64-v8a" "$PACKAGE_SOURCE/zapret2/bin/armeabi-v7a"
cp /bin/true "$PACKAGE_SOURCE/zapret2/bin/arm64-v8a/nfqws2"
cp "$PACKAGE_SOURCE/zapret2/bin/arm64-v8a/nfqws2" "$PACKAGE_SOURCE/zapret2/bin/armeabi-v7a/nfqws2"
printf '%s\n' b78b52c4cd7f843da3ff0848a3430afbd401bdf2 > "$PACKAGE_SOURCE/zapret2/upstream-zapret2.commit"
. "$PACKAGE_SOURCE/zapret2/scripts/package-contract.sh"
package_contract_assemble_package "$PACKAGE_SOURCE" "$FIXTURE" ||
    fail "cannot assemble installer fixture: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
(cd "$FIXTURE" && zip -qr "$ARCHIVE" module.prop customize.sh service.sh uninstall.sh action.sh system zapret2)

run_installer() {
    rm -rf "$UPDATE"
    mkdir -p "$UPDATE"
    unzip -oq "$ARCHIVE" customize.sh -d "$UPDATE"
    (
        MODPATH="$UPDATE"
        ZIPFILE="$ARCHIVE"
        BOOTMODE=true
        ARCH=arm64
        export MODPATH ZIPFILE BOOTMODE ARCH
        abort() { echo "$*" >&2; rm -rf "$MODPATH"; exit 1; }
        ui_print() { :; }
        . "$UPDATE/customize.sh"
    ) || return $?
    # Magisk removes installer-only files after customize.sh returns.
    rm -f "$UPDATE/customize.sh"
}

# The installer is an Android program and invokes its validated package
# helpers through the platform shell. Establish that platform contract before
# the first installer run, not midway through the recovery scenario.
if [ ! -e /system ]; then
    mkdir -p /system/bin
    ln -s /bin/sh /system/bin/sh
    SYSTEM_CREATED=1
elif [ ! -x /system/bin/sh ]; then
    fail "/system exists without a usable /system/bin/sh"
fi

# A module removed by an older release can leave a malformed interrupted-build
# journal behind. A fresh installer may retire it only after proving the whole
# reserved namespace is empty; visible residue must preserve the evidence.
cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
case "$*" in
    *'-t mangle -S'*)
        [ "${Z2_TEST_NAMESPACE:-0}" != 1 ] || printf '%s\n' '-N Z2O_AbCdEf1234'
        exit 0
        ;;
    *) exit 1 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"
chmod 0755 "$MOCK/iptables" "$MOCK/ip6tables"
mkdir -p "$LIVE_STATE"
chmod 0700 "$LIVE_STATE"
printf '%s\n' 'malformed interrupted build evidence' > "$LIVE_STATE/build-track.ipv4.4115"
chmod 0600 "$LIVE_STATE/build-track.ipv4.4115"
Z2_TEST_NAMESPACE=1
export Z2_TEST_NAMESPACE
set +e
PATH="$MOCK:$PATH" run_installer
residue_install_rc=$?
set -e
unset Z2_TEST_NAMESPACE
[ "$residue_install_rc" = 1 ] || fail "fresh install discarded a track while firewall residue remained"
[ -f "$LIVE_STATE/build-track.ipv4.4115" ] ||
    fail "blocked fresh install deleted malformed track evidence"

# With no live module, process, mixed recovery artifact, or reserved firewall
# object, the track is now orphaned and cannot protect any remaining mutation.
PATH="$MOCK:$PATH" run_installer || fail "fresh standard install did not recover orphaned track"
[ ! -e "$LIVE_STATE/build-track.ipv4.4115" ] &&
    [ ! -L "$LIVE_STATE/build-track.ipv4.4115" ] ||
    fail "fresh standard install left orphaned track evidence"
[ -f "$UPDATE/zapret2/install-generation.meta" ] || fail "fresh install generation was not published"
grep -Eq '^archive_sha256=[0-9a-f]{64}$' "$UPDATE/zapret2/install-generation.meta" || fail "archive hash is invalid"
[ ! -e "$UPDATE/customize.sh" ] || fail "installer-only customize.sh remained in the installed shape"
mv "$UPDATE" "$LIVE"

# A root-manager disable fence on a complete live module must survive a
# standard modules_update install byte-for-byte.
: > "$LIVE/disable"
chmod 0600 "$LIVE/disable"
run_installer || fail "disabled standard install failed"
[ -f "$UPDATE/disable" ] && [ ! -L "$UPDATE/disable" ] || fail "disable marker was not preserved"
[ ! -s "$UPDATE/disable" ] && [ "$(stat -c '%a' "$UPDATE/disable")" = 600 ] || fail "disable marker contract changed"
[ -f "$UPDATE/zapret2/install-generation.meta" ] || fail "install generation was not published"
grep -Eq '^archive_sha256=[0-9a-f]{64}$' "$UPDATE/zapret2/install-generation.meta" || fail "archive hash is invalid"

# Promote the staged tree, complete an actual full rollback, uninstall it, then
# simulate root-manager removal and reinstall. Completed generation/hosts
# evidence must be retired; the uninstall tombstone must be authenticated and
# cleared by the reinstall; the old disable fence must not resurrect.
rm -rf "$LIVE"
mv "$UPDATE" "$LIVE"
mkdir -p "$LIVE/system/etc" "$LIVE_STATE"
chmod 0700 "$LIVE_STATE"

# A standard Magisk update is staged below modules_update while the currently
# installed service can remain live. Owner v7 must authenticate against the
# exact packaged live path, never the candidate/staging binary, and the
# installer must neither stop nor rewrite that live publication.
rm -f "$LIVE/disable"
cp "$LIVE/zapret2/nfqws2" "$CASE/nfqws2.packaged"
cp /bin/sh "$LIVE/zapret2/nfqws2"
chmod 0755 "$LIVE/zapret2/nfqws2"
"$LIVE/zapret2/nfqws2" -c 'while :; do sleep 1; done' --qnum=200 &
LIVE_TEST_PID=$!
sleep 1
kill -0 "$LIVE_TEST_PID" 2>/dev/null || fail "live owner fixture process did not remain running"
(
    STATE_DIR="$LIVE_STATE"
    MODDIR="$LIVE"
    ZAPRET_DIR="$LIVE/zapret2"
    SCRIPT_DIR="$LIVE/zapret2/scripts"
    export STATE_DIR MODDIR ZAPRET_DIR SCRIPT_DIR
    . "$LIVE/zapret2/scripts/common.sh"
    QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
    FIREWALL_TAG=AbCdEf1234; ZAPRET2_OUT=Z2O_AbCdEf1234; ZAPRET2_IN=Z2I_AbCdEf1234
    IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1
    IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1
    prepare_owner_generation_spec 1 0 || exit 41
    owner_starttime=$(proc_starttime "$LIVE_TEST_PID") || exit 42
    owner_argv_sha256=$(proc_cmdline_sha256 "$LIVE_TEST_PID") || exit 43
    write_owner_state "$LIVE_TEST_PID" "$owner_starttime" "$owner_argv_sha256" 200 running-modules-update active || exit 44
    write_numeric_pidfile "$LIVE_TEST_PID" || exit 45
) || fail "could not publish exact live owner v7 fixture"
grep -Fxq "exe=$LIVE/zapret2/nfqws2" "$LIVE_STATE/owner.meta" || fail "live owner fixture used a non-canonical exe"
cp "$LIVE_STATE/owner.meta" "$CASE/running-owner.before"
run_installer || fail "standard modules_update install rejected a valid running live owner"
kill -0 "$LIVE_TEST_PID" 2>/dev/null || fail "standard modules_update install stopped the live service"
cmp -s "$CASE/running-owner.before" "$LIVE_STATE/owner.meta" || fail "standard modules_update install rewrote live owner metadata"
[ -d "$UPDATE" ] && [ ! -L "$UPDATE" ] || fail "running modules_update candidate was not staged"
kill "$LIVE_TEST_PID" 2>/dev/null || fail "could not stop live owner test process"
wait "$LIVE_TEST_PID" 2>/dev/null || true
LIVE_TEST_PID=""
rm -f "$LIVE_STATE/owner.meta" "$LIVE_STATE/nfqws2.pid"
cp "$CASE/nfqws2.packaged" "$LIVE/zapret2/nfqws2"
chmod 0755 "$LIVE/zapret2/nfqws2"
rm -rf "$UPDATE"

printf '%s\n' '127.0.0.1 localhost' '1.1.1.1 preserved.test' > "$LIVE/system/etc/hosts"
chmod 0644 "$LIVE/system/etc/hosts"
cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
case "$*" in
    *' -F '*|*' -X '*|*' -D '*) exit 0 ;;
    *'-S ZAPRET2_OUT'|*'-S ZAPRET2_IN'|*'-S ZAPRET2_PROBE') exit 1 ;;
    *'-S OUTPUT'|*'-S INPUT') exit 0 ;;
    *'-S') exit 0 ;;
    *) exit 1 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"
chmod 0755 "$MOCK/iptables" "$MOCK/ip6tables"
cleanup_boot=$(cat /proc/sys/kernel/random/boot_id)
cat > "$LIVE_STATE/update.cleanup" <<EOF
version=2
owner_pid=$$
owner_starttime=1
owner_created_epoch=2
owner_boot_id=$cleanup_boot
owner_token=packaging-cleanup
transaction_digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
cleanup_count=1
cleanup_1=/data/adb/modules/.zapret2-backup-packaging-cleanup
EOF
chmod 0600 "$LIVE_STATE/update.cleanup"
PATH="$MOCK:$PATH" STATE_DIR="$LIVE_STATE" sh "$LIVE/zapret2/scripts/zapret-full-rollback.sh" --machine > "$CASE/rollback.out" || fail "full rollback failed"
[ ! -e "$LIVE_STATE/update.cleanup" ] || fail "full rollback did not consume committed cleanup evidence"
grep -Fxq 'Z2_RB_STATUS=complete' "$CASE/rollback.out" || fail "rollback did not complete"
[ -f "$LIVE_STATE/full-rollback.meta" ] && [ -f "$LIVE_STATE/hosts.rollback.backup" ] || fail "rollback evidence missing"

printf '%s\n' 'version=2' 'owner_pid=broken' > "$LIVE_STATE/update.cleanup"
chmod 0600 "$LIVE_STATE/update.cleanup"
set +e
PATH="$MOCK:$PATH" MODPATH="$LIVE" sh "$LIVE/uninstall.sh" > "$CASE/uninstall-malformed-cleanup.out" 2>&1
malformed_cleanup_rc=$?
set -e
[ "$malformed_cleanup_rc" = 1 ] || fail "uninstall accepted malformed committed cleanup"
[ -e "$LIVE_STATE/update.cleanup" ] || fail "uninstall discarded malformed cleanup evidence"
[ ! -e "$LIVE_STATE/uninstall.tombstone" ] || fail "blocked uninstall published a tombstone"
rm -f "$LIVE_STATE/update.cleanup"
mkdir -p "$UNINSTALL_CLEANUP"
printf '%s\n' retained > "$UNINSTALL_CLEANUP/sentinel"
cat > "$LIVE_STATE/update.cleanup" <<EOF
version=2
owner_pid=$$
owner_starttime=1
owner_created_epoch=2
owner_boot_id=$cleanup_boot
owner_token=uninstall-cleanup
transaction_digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
cleanup_count=1
cleanup_1=$UNINSTALL_CLEANUP
EOF
chmod 0600 "$LIVE_STATE/update.cleanup"
printf '%s\n' 'foreign state must survive' > "$LIVE_STATE/unknown.child"
chmod 0600 "$LIVE_STATE/unknown.child"
set +e
PATH="$MOCK:$PATH" MODPATH="$LIVE" sh "$LIVE/uninstall.sh" > "$CASE/uninstall-partial.out" 2>&1
partial_uninstall_rc=$?
set -e
[ "$partial_uninstall_rc" = 1 ] || fail "uninstall reported success while preserving an unknown state child"
[ -f "$LIVE_STATE/unknown.child" ] || fail "uninstall deleted an unknown state child"
[ -f "$LIVE_STATE/uninstall.tombstone" ] || fail "partial uninstall did not preserve its start gate"
grep -Fq 'uninstall cleanup is partial' "$CASE/uninstall-partial.out" || fail "partial uninstall did not report an explicit partial result"
if grep -Fq 'stopped, verified clean, and uninstalled' "$CASE/uninstall-partial.out"; then
    fail "partial uninstall emitted the full-success message"
fi
rm -f "$LIVE_STATE/unknown.child"
PATH="$MOCK:$PATH" MODPATH="$LIVE" sh "$LIVE/uninstall.sh" > "$CASE/uninstall.out" || fail "uninstall retry after removing unknown state failed"
[ ! -e "$UNINSTALL_CLEANUP" ] && [ ! -e "$LIVE_STATE/update.cleanup" ] || fail "uninstall did not consume committed cleanup"
[ ! -e "$LIVE_STATE/full-rollback.meta" ] && [ ! -e "$LIVE_STATE/hosts.rollback.backup" ] || fail "completed rollback generation was not retired"
[ -f "$LIVE_STATE/uninstall.tombstone" ] || fail "uninstall tombstone missing"

rm -rf "$LIVE"
run_installer || fail "reinstall after rollback/uninstall failed"
[ ! -e "$UPDATE/disable" ] && [ ! -L "$UPDATE/disable" ] || fail "stale rollback disable marker resurrected"
[ ! -e "$LIVE_STATE/uninstall.tombstone" ] && [ ! -L "$LIVE_STATE/uninstall.tombstone" ] || fail "authenticated stale tombstone was not cleared"
STATE_DIR="$LIVE_STATE"
ZAPRET_DIR="$UPDATE/zapret2"
MODDIR=/data/adb/modules/zapret2
SCRIPT_DIR="$UPDATE/zapret2/scripts"
. "$UPDATE/zapret2/scripts/common.sh"
audit_recovery_artifacts install || fail "reinstall left recovery artifacts"
[ "$RECOVERY_ARTIFACT_CLASS" = clean ] || fail "reinstall recovery state is not clean"

# Magisk's Delete button publishes the durable remove marker and invokes
# uninstall.sh at the next boot before deleting the module directory. That
# explicit authority must purge the whole private state tree even when an old
# interrupted build journal is malformed; process/firewall cleanup is still
# verified independently first.
mv "$UPDATE" "$LIVE"
: > "$LIVE/remove"
chmod 0600 "$LIVE/remove"
printf '%s\n' 'malformed interrupted build evidence' > "$LIVE_STATE/build-track.ipv4.4115"
chmod 0600 "$LIVE_STATE/build-track.ipv4.4115"
PATH="$MOCK:$PATH" MODPATH="$LIVE" sh "$LIVE/uninstall.sh" > "$CASE/magisk-remove.out" ||
    fail "Magisk removal path did not force-clean private state"
[ ! -e "$LIVE_STATE" ] && [ ! -L "$LIVE_STATE" ] ||
    fail "Magisk removal left the private state directory"
grep -Fq 'all Zapret2 service, firewall, and private state was removed' "$CASE/magisk-remove.out" ||
    fail "Magisk removal did not report full private-state cleanup"

echo "Packaging recovery flow tests passed"
