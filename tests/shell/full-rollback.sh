#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/full-rollback"
MOD="$CASE/module"
STATE="$CASE/state"
MOCK="$CASE/bin"
LOG="$CASE/iptables.mutations"
OUT="$CASE/out"
CLEANUP_REAL1="/data/adb/modules/.zapret2-update-full-rollback-$$"
CLEANUP_REAL2="/data/adb/modules/.zapret2-backup-full-rollback-$$"

fail() { echo "FAIL: rollback: $*" >&2; exit 1; }
assert_line() { grep -Fxq -- "$2" "$1" || fail "missing $2"; }
trap 'rm -rf "$CLEANUP_REAL1" "$CLEANUP_REAL2"' EXIT HUP INT TERM

mkdir -p "$MOD/zapret2/scripts" "$MOD/zapret2/lists" "$MOD/system/etc" "$STATE" "$MOCK"
chmod 0700 "$STATE"
cp "$ROOT/zapret2/scripts/common.sh" "$MOD/zapret2/scripts/common.sh"
cp "$ROOT/zapret2/scripts/zapret-full-rollback.sh" "$MOD/zapret2/scripts/zapret-full-rollback.sh"
cat > "$MOD/zapret2/runtime.ini" <<'EOF'
[core]
autostart=1
qnum=200
ports_tcp=80,443
ports_udp=443
pkt_out=20
pkt_in=10
[dns_manager]
dns_preset_index=2
selected_dns=one|two
selected_direct=three
[strategy_order]
tcp=custom-user-order
EOF
printf '%s\n' 'user-list-content' > "$MOD/zapret2/lists/user.txt"
printf '%s\n' '127.0.0.1 localhost' '1.1.1.1 example.test' > "$MOD/system/etc/hosts"
chmod 0644 "$MOD/zapret2/runtime.ini" "$MOD/system/etc/hosts"
cat > "$MOD/zapret2/install-generation.meta" <<EOF
version=1
module_dir=$MOD
generation=test-install-generation
archive_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
EOF
chmod 0600 "$MOD/zapret2/install-generation.meta"

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
if [ "${Z2_MOCK_AMBIGUOUS:-0}" = 1 ]; then
    case "$*" in
        *'-S ZAPRET2_OUT') echo '-N ZAPRET2_OUT'; exit 0 ;;
        *'-S ZAPRET2_IN'|*'-S ZAPRET2_PROBE') exit 1 ;;
        *'-S') echo '-N ZAPRET2_OUT'; echo '-A FORWARD -j ZAPRET2_OUT'; exit 0 ;;
    esac
fi
case "$*" in
    *' -F '*|*' -X '*|*' -D '*) echo "$*" >> "${Z2_MOCK_LOG:?}"; exit 0 ;;
    *'-S ZAPRET2_OUT'|*'-S ZAPRET2_IN'|*'-S ZAPRET2_PROBE') exit 1 ;;
    *'-S') exit 0 ;;
    *) exit 1 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"
cat > "$MOCK/sync" <<'EOF'
#!/bin/sh
[ "${Z2_MOCK_SYNC_FAIL:-0}" != 1 ] || exit 1
if [ -n "${Z2_MOCK_SYNC_COUNT:-}" ]; then
    count=0
    [ ! -f "$Z2_MOCK_SYNC_COUNT" ] || IFS= read -r count < "$Z2_MOCK_SYNC_COUNT"
    count=$((count + 1))
    printf '%s\n' "$count" > "$Z2_MOCK_SYNC_COUNT"
    if [ "$count" = "${Z2_MOCK_SYNC_SIGNAL_AT:-0}" ]; then
        kill -TERM "$PPID"
    fi
fi
exit 0
EOF
chmod 0755 "$MOCK/iptables" "$MOCK/ip6tables"
chmod 0755 "$MOCK/sync"

before_list=$(sha256sum "$MOD/zapret2/lists/user.txt" | awk '{print $1}')

# Every noncanonical recovery namespace is evidence, never scratch space for
# rollback. Exercise update, lifecycle, dot-temp, reaper and quarantine forms.
for recovery_artifact in \
    update.cleanup \
    update.transaction.tmp.orphan .update.transaction.orphan \
    update.lock.reaper.orphan update.lock.quarantine.orphan \
    lifecycle.lock.reaper.recovery.orphan lifecycle.lock.quarantine.orphan \
    .full-rollback.transaction.orphan full-rollback.meta.tmp.orphan; do
    : > "$STATE/$recovery_artifact"
    chmod 0600 "$STATE/$recovery_artifact"
    set +e
    PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
    rc=$?
    set -e
    [ "$rc" = 2 ] || fail "recovery artifact was not blocked: $recovery_artifact"
    assert_line "$OUT" 'Z2_RB_STATUS=blocked'
    [ -f "$STATE/$recovery_artifact" ] || fail "rollback deleted recovery evidence: $recovery_artifact"
    [ ! -e "$MOD/disable" ] || fail "artifact preflight mutated disable fence: $recovery_artifact"
    rm -f "$STATE/$recovery_artifact"
done

# A malformed rollback journal is recovery evidence. It must fail closed before
# autostart or the module disable fence is changed, and it must remain available
# for diagnosis instead of being overwritten by a fresh transaction.
cat > "$STATE/full-rollback.transaction" <<EOF
version=1
module_dir=$MOD
token=malformed-journal
phase=unknown
EOF
chmod 0600 "$STATE/full-rollback.transaction"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
rc=$?
set -e
[ "$rc" = 2 ] || fail "malformed rollback journal was not blocked"
[ "$(wc -l < "$OUT")" = 10 ] || fail "blocked journal output is not exactly ten fields"
assert_line "$OUT" 'Z2_RB_STATUS=blocked'
assert_line "$OUT" 'Z2_RB_COMPLETE=1'
[ -f "$STATE/full-rollback.transaction" ] || fail "malformed rollback journal was discarded"
[ ! -e "$MOD/disable" ] || fail "malformed journal preflight mutated disable fence"
grep -Fqx 'autostart=1' "$MOD/zapret2/runtime.ini" || fail "malformed journal preflight mutated runtime.ini"
rm -f "$STATE/full-rollback.transaction"

start=$(awk '{print $22}' "/proc/$$/stat")
cat > "$STATE/update.lock" <<EOF
version=1
pid=$$
starttime=$start
created_epoch=1
token=active-update
module_dir=$MOD
EOF
chmod 0600 "$STATE/update.lock"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
rc=$?
set -e
[ "$rc" = 2 ] || fail "active update was not blocked"
assert_line "$OUT" 'Z2_RB_STATUS=blocked'
[ ! -e "$MOD/disable" ] || fail "blocked rollback mutated disable fence"
rm -f "$STATE/update.lock"

# A valid committed cleanup is consumed under the rollback lifecycle lock
# before any rollback mutation. A second recovery artifact keeps this case at
# the preflight boundary so only cleanup consumption is under test.
mkdir -p /data/adb/modules "$CLEANUP_REAL1" "$CLEANUP_REAL2"
cleanup_digest=$(printf cleanup-transaction | sha256sum | awk '{print $1}')
cleanup_boot=$(cat /proc/sys/kernel/random/boot_id)
cat > "$STATE/update.cleanup" <<EOF
version=2
owner_pid=$$
owner_starttime=$start
owner_created_epoch=1
owner_boot_id=$cleanup_boot
owner_token=full-rollback-cleanup
transaction_digest=$cleanup_digest
cleanup_count=2
cleanup_1=$CLEANUP_REAL1
cleanup_2=$CLEANUP_REAL2
EOF
chmod 0600 "$STATE/update.cleanup"
: > "$STATE/update.transaction.tmp.post-cleanup-gate"
chmod 0600 "$STATE/update.transaction.tmp.post-cleanup-gate"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
rc=$?
set -e
[ "$rc" = 2 ] || fail "valid committed cleanup did not return to guarded preflight"
assert_line "$OUT" 'Z2_RB_STATUS=blocked'
[ ! -e "$CLEANUP_REAL1" ] && [ ! -e "$CLEANUP_REAL2" ] || fail "full rollback retained valid committed cleanup directories"
[ ! -e "$STATE/update.cleanup" ] || fail "full rollback retained consumed cleanup evidence"
[ -e "$STATE/update.transaction.tmp.post-cleanup-gate" ] || fail "full rollback removed unrelated recovery evidence"
rm -f "$STATE/update.transaction.tmp.post-cleanup-gate"

# A failed durability barrier retains the exact disable fence and journal.
exec 9< "$MOD/system/etc/hosts"
hosts_inode_before=$(stat -c %i "$MOD/system/etc/hosts")
hosts_mode_before=$(stat -c %a "$MOD/system/etc/hosts")
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" Z2_MOCK_SYNC_FAIL=1 sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
rc=$?
set -e
[ "$rc" = 1 ] || fail "sync failure did not fail rollback"
assert_line "$OUT" 'Z2_RB_STATUS=error'
[ -f "$MOD/disable" ] && [ -f "$STATE/full-rollback.transaction" ] || fail "sync failure did not retain fence/journal"

# Interrupt at the process-clean durability barrier. No hosts backup artifact
# may be created before that recovery phase has been synchronized.
rm -f "$CASE/sync.count"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" \
    Z2_MOCK_SYNC_COUNT="$CASE/sync.count" Z2_MOCK_SYNC_SIGNAL_AT=2 \
    sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT.signal-process"
rc=$?
set -e
[ "$rc" = 1 ] || fail "process-clean sync signal did not interrupt rollback"
assert_line "$OUT.signal-process" 'Z2_RB_STATUS=partial'
[ -f "$MOD/system/etc/hosts" ] && [ ! -e "$STATE/hosts.rollback.backup" ] || fail "hosts were touched before process-clean phase durability"
grep -Fqx 'phase=process-clean' "$STATE/full-rollback.transaction" || fail "process-clean phase was not retained"

# The next sync is the backup-publication durability barrier. Source must still
# exist beside the verified backup and the process-clean journal is recoverable.
rm -f "$CASE/sync.count"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" \
    Z2_MOCK_SYNC_COUNT="$CASE/sync.count" Z2_MOCK_SYNC_SIGNAL_AT=3 \
    sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT.signal-hosts"
rc=$?
set -e
[ "$rc" = 1 ] || fail "hosts publication signal did not interrupt rollback"
assert_line "$OUT.signal-hosts" 'Z2_RB_STATUS=partial'
[ -f "$MOD/system/etc/hosts" ] && [ -f "$STATE/hosts.rollback.backup" ] || fail "source was unlinked before backup durability/journal ordering"
grep -Fqx 'phase=process-clean' "$STATE/full-rollback.transaction" || fail "signal regressed or advanced an uncommitted hosts phase"

PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
[ "$(wc -l < "$OUT")" = 10 ] || fail "machine output is not exactly ten fields"
assert_line "$OUT" 'Z2_RB_STATUS=complete'
assert_line "$OUT" 'Z2_RB_PROCESS_CLEAN=1'
assert_line "$OUT" 'Z2_RB_FIREWALL_CLEAN=1'
assert_line "$OUT" 'Z2_RB_ROLLBACK_ARMED=1'
assert_line "$OUT" 'Z2_RB_HOSTS_PRESERVED=1'
assert_line "$OUT" 'Z2_RB_REBOOT_REQUIRED=1'
assert_line "$OUT" 'Z2_RB_USER_DATA_PRESERVED=1'
assert_line "$OUT" 'Z2_RB_COMPLETE=1'
[ -f "$MOD/disable" ] && [ ! -L "$MOD/disable" ] || fail "disable fence missing"
[ -f "$STATE/hosts.rollback.backup" ] || fail "hosts backup missing"
grep -Fqx '1.1.1.1 example.test' "$STATE/hosts.rollback.backup" || fail "hosts bytes not preserved"
[ ! -e "$MOD/system/etc/hosts" ] || fail "hosts overlay was not moved"
[ "$(stat -c %a "$STATE/hosts.rollback.backup")" = 600 ] || fail "hosts backup is not root-private"
[ "$(stat -c %i "$STATE/hosts.rollback.backup")" != "$hosts_inode_before" ] || fail "hosts live inode was moved instead of copied"
[ "$(stat -Lc %i "/proc/$$/fd/9")" = "$hosts_inode_before" ] || fail "open hosts inode changed"
[ "$(stat -Lc %a "/proc/$$/fd/9")" = "$hosts_mode_before" ] || fail "live hosts inode mode changed"
grep -Fqx '1.1.1.1 example.test' "/proc/$$/fd/9" || fail "open hosts inode contents changed"
exec 9<&-
grep -Fqx 'autostart=0' "$MOD/zapret2/runtime.ini" || fail "autostart was not disabled"
! grep -Eq '^(dns_preset_index|selected_dns|selected_direct)=' "$MOD/zapret2/runtime.ini" || fail "DNS UI selections remain"
grep -Fqx 'tcp=custom-user-order' "$MOD/zapret2/runtime.ini" || fail "unrelated runtime data changed"
[ "$before_list" = "$(sha256sum "$MOD/zapret2/lists/user.txt" | awk '{print $1}')" ] || fail "user list changed"
[ -f "$STATE/full-rollback.meta" ] && [ ! -e "$STATE/full-rollback.transaction" ] || fail "commit artifacts invalid"
grep -Fqx 'generation=test-install-generation' "$STATE/full-rollback.meta" || fail "rollback meta does not bind install generation"
grep -Fqx 'archive_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$STATE/full-rollback.meta" || fail "rollback meta does not bind archive hash"

# Idempotent completion keeps the original protected backup.
backup_hash=$(sha256sum "$STATE/hosts.rollback.backup" | awk '{print $1}')
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
assert_line "$OUT" 'Z2_RB_STATUS=complete'
[ "$backup_hash" = "$(sha256sum "$STATE/hosts.rollback.backup" | awk '{print $1}')" ] || fail "idempotent run overwrote hosts backup"

# A signal while waiting for a foreign lifecycle lock still emits the exact
# machine contract and must not retire the foreign lock.
mkdir "$STATE/lifecycle.lock"
cat > "$STATE/lifecycle.lock/owner" <<EOF
pid=$$
starttime=$start
token=foreign-lock
EOF
chmod 0600 "$STATE/lifecycle.lock/owner"
PATH="$MOCK:$PATH" STATE_DIR="$STATE" LIFECYCLE_LOCK_WAIT_SECONDS=30 Z2_MOCK_LOG="$LOG" \
    sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT.signal" &
signal_pid=$!
sleep 1
kill -TERM "$signal_pid"
set +e
wait "$signal_pid"
rc=$?
set -e
[ "$rc" = 1 ] || fail "early signal did not return failure"
[ "$(wc -l < "$OUT.signal")" = 10 ] || fail "early signal output is not exactly ten fields"
assert_line "$OUT.signal" 'Z2_RB_STATUS=partial'
assert_line "$OUT.signal" 'Z2_RB_COMPLETE=1'
[ -f "$STATE/lifecycle.lock/owner" ] || fail "signal cleanup retired a foreign lifecycle lock"
rm -f "$STATE/lifecycle.lock/owner"
rmdir "$STATE/lifecycle.lock"

# A foreign reference to a same-named chain must leave the chain untouched and
# retain the durable fence/journal for recovery.
rm -f "$STATE/full-rollback.meta" "$STATE/legacy-direct-rules.migrated" "$MOD/disable"
rm -rf "$STATE/hosts.rollback.backup"
mkdir -p "$MOD/system/etc"
printf '%s\n' '127.0.0.1 localhost' > "$MOD/system/etc/hosts"
sed -i 's/^autostart=0$/autostart=1/' "$MOD/zapret2/runtime.ini"
# Publish a real exact-path process and matching owner generation. This makes
# the firewall foreign-reference audit, rather than process preflight, the
# reason rollback stops.
cp "$(command -v sh)" "$MOD/zapret2/nfqws2"
chmod 0755 "$MOD/zapret2/nfqws2"
"$MOD/zapret2/nfqws2" -c 'trap "exit 0" TERM INT; while :; do sleep 1; done' --qnum=200 &
nf_pid=$!
nf_start=$(awk '{print $22}' "/proc/$nf_pid/stat")
nf_argv=$(od -An -v -tx1 "/proc/$nf_pid/cmdline" | tr -d '[:space:]')
printf '%s\n' "$nf_pid" > "$STATE/nfqws.pid"
cat > "$STATE/runtime.owner" <<EOF
version=1
module_dir=$MOD
nfqws=$MOD/zapret2/nfqws2
EOF
STATE_DIR="$STATE" SCRIPT_DIR="$MOD/zapret2/scripts" ZAPRET_DIR="$MOD/zapret2" MODDIR="$MOD" \
    sh -c '. "$SCRIPT_DIR/common.sh"; QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000; IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1; IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1; prepare_owner_generation_spec 1 0 && write_owner_state "$1" "$2" "$3" 200 foreign-audit active' \
    sh "$nf_pid" "$nf_start" "$nf_argv" || fail "could not publish valid v6 owner generation"
chmod 0600 "$STATE/nfqws.pid" "$STATE/runtime.owner" "$STATE/owner.meta"
# Runtime configuration may change after publication; rollback must continue
# to audit/delete only the persisted generation (qnum 200 / original ports).
sed -i 's/^qnum=200$/qnum=333/; s/^ports_tcp=80,443$/ports_tcp=8080,8443/; s/^ports_udp=443$/ports_udp=8443/' "$MOD/zapret2/runtime.ini"
: > "$LOG"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" Z2_MOCK_AMBIGUOUS=1 sh "$MOD/zapret2/scripts/zapret-full-rollback.sh" --machine > "$OUT"
rc=$?
set -e
[ "$rc" = 1 ] || fail "ambiguous chain did not return partial"
assert_line "$OUT" 'Z2_RB_STATUS=partial'
[ ! -s "$LOG" ] || fail "ambiguous chain was mutated"
[ -r "/proc/$nf_pid/stat" ] || fail "foreign-reference audit killed the verified process"
[ -f "$MOD/disable" ] && [ -f "$STATE/full-rollback.transaction" ] || fail "partial rollback did not retain fence/journal"
kill -TERM "$nf_pid" 2>/dev/null || true
wait "$nf_pid" 2>/dev/null || true

# zapret-stop must reject corrupt PID publication before any legacy/current
# firewall mutation.
cp "$ROOT/zapret2/scripts/zapret-stop.sh" "$MOD/zapret2/scripts/zapret-stop.sh"
printf '%s\n' "$$" > "$STATE/nfqws.pid"
chmod 0600 "$STATE/nfqws.pid"
rm -f "$STATE/runtime.owner" "$STATE/owner.meta" "$STATE/full-rollback.transaction"
: > "$LOG"
set +e
PATH="$MOCK:$PATH" STATE_DIR="$STATE" Z2_MOCK_LOG="$LOG" sh "$MOD/zapret2/scripts/zapret-stop.sh" > "$OUT.stop"
rc=$?
set -e
[ "$rc" = 1 ] || fail "corrupt PID publication did not block stop"
[ ! -s "$LOG" ] || fail "corrupt PID publication allowed firewall mutation"

echo "Full rollback shell tests passed"
