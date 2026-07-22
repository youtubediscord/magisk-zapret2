#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd -P)
TMP=${Z2_TEST_TMP:-"$ROOT/.tmp-update-cleanup-v2.$$"}
STATE="$TMP/update-cleanup-state"
MOD="$TMP/update-cleanup-module"
REAL1="/data/adb/modules/.zapret2-update-cleanup-shell-$$"
REAL2="/data/adb/modules/.zapret2-backup-cleanup-shell-$$"
fail() { echo "FAIL: $*" >&2; exit 1; }
trap 'rm -rf "$REAL1" "$REAL2"' EXIT HUP INT TERM
mkdir -p "$STATE" "$MOD/zapret2/scripts"
chmod 0700 "$STATE"

STATE_DIR="$STATE" MODDIR="$MOD" ZAPRET_DIR="$MOD/zapret2" SCRIPT_DIR="$MOD/zapret2/scripts"
export STATE_DIR MODDIR ZAPRET_DIR SCRIPT_DIR
. "$ROOT/zapret2/scripts/common.sh"

boot=$(cat /proc/sys/kernel/random/boot_id)
digest=$(printf x | sha256sum | awk '{print $1}')
write_v2() {
    cat > "$UPDATE_CLEANUP" <<EOF
version=2
owner_pid=$$
owner_starttime=1
owner_created_epoch=2
owner_boot_id=$boot
owner_token=cleanup-token
transaction_digest=$digest
cleanup_count=2
cleanup_1=$REAL1
cleanup_2=$REAL2
EOF
    chmod 0600 "$UPDATE_CLEANUP"
}
write_v1() {
    cat > "$UPDATE_CLEANUP" <<EOF
version=1
owner_pid=$$
owner_starttime=1
owner_created_epoch=2
owner_token=legacy-cleanup-token
transaction_digest=$digest
cleanup_count=1
cleanup_1=/data/adb/modules/.zapret2-failed-cleanup-test
EOF
    chmod 0600 "$UPDATE_CLEANUP"
}

write_v2
mkdir -p "$REAL1" "$REAL2"
: > "$REAL1/sentinel"; : > "$REAL2/sentinel"
read_update_cleanup_file || fail "canonical cleanup v2 was rejected: $UPDATE_CLEANUP_ERROR"
[ "$UPDATE_CLEANUP_LEGACY" = 0 ] || fail "v2 cleanup classified legacy"
audit_recovery_artifacts uninstall || fail "cleanup-only uninstall audit was blocked"
[ "$RECOVERY_ARTIFACT_CLASS" = update ] || fail "cleanup-only artifact class is not update"
if audit_recovery_artifacts lifecycle; then fail "ordinary lifecycle admitted pending cleanup"; fi

caller_holds_exact_lifecycle_lock() { return 0; }
consume_committed_update_cleanup_locked || fail "cleanup v2 with allowlisted directories was not consumed"
[ ! -e "$REAL1" ] && [ ! -e "$REAL2" ] || fail "allowlisted cleanup directories were retained"
[ ! -e "$UPDATE_CLEANUP" ] || fail "terminal cleanup evidence was not retired"

write_v1
read_update_cleanup_file || fail "exact legacy cleanup v1 was rejected"
[ "$UPDATE_CLEANUP_LEGACY" = 1 ] || fail "v1 cleanup was not classified legacy"
consume_committed_update_cleanup_locked || fail "authenticated legacy cleanup retry was not consumed"

write_v2
sed "s|cleanup_2=.*|cleanup_2=$REAL1|" "$UPDATE_CLEANUP" > "$UPDATE_CLEANUP.tmp"
mv "$UPDATE_CLEANUP.tmp" "$UPDATE_CLEANUP"; chmod 0600 "$UPDATE_CLEANUP"
if read_update_cleanup_file; then fail "duplicate cleanup paths were accepted"; fi
[ -e "$UPDATE_CLEANUP" ] || fail "ambiguous cleanup evidence was removed"

write_v2
sed 's|cleanup_1=.*|cleanup_1=/data/adb/modules/zapret2|' "$UPDATE_CLEANUP" > "$UPDATE_CLEANUP.tmp"
mv "$UPDATE_CLEANUP.tmp" "$UPDATE_CLEANUP"; chmod 0600 "$UPDATE_CLEANUP"
if consume_committed_update_cleanup_locked; then fail "non-allowlisted cleanup path was consumed"; fi
[ -e "$UPDATE_CLEANUP" ] || fail "malformed cleanup evidence was removed"

rm -f "$UPDATE_CLEANUP"
write_v2
rm -rf "$REAL1" "$REAL2"
mkdir -p "$(dirname "$REAL1")"
ln -s / "$REAL1"
if consume_committed_update_cleanup_locked; then fail "symlink cleanup directory was consumed"; fi
[ -L "$REAL1" ] && [ -e "$UPDATE_CLEANUP" ] || fail "symlink ambiguity was not preserved"
rm -f "$REAL1" "$UPDATE_CLEANUP"

write_v2
mkdir -p "$REAL1" "$REAL2"
chown 1:1 "$REAL1"
if consume_committed_update_cleanup_locked; then fail "non-root-owned cleanup directory was consumed"; fi
[ -d "$REAL1" ] && [ -d "$REAL2" ] && [ -e "$UPDATE_CLEANUP" ] ||
    fail "ownership ambiguity did not preserve cleanup directories and evidence"
chown 0:0 "$REAL1"
command rm -rf "$REAL1" "$REAL2"
command rm -f "$UPDATE_CLEANUP"

write_v2
mkdir -p "$REAL1" "$REAL2"
Z2_INJECT_CLEANUP_TOCTOU=1
rm() {
    if [ "$Z2_INJECT_CLEANUP_TOCTOU" = 1 ] && [ "${1:-}" = -rf ] && [ "${2:-}" = "$REAL1" ]; then
        Z2_INJECT_CLEANUP_TOCTOU=0
        command rm "$@"
        printf 'future_field=changed-during-cleanup\n' >> "$UPDATE_CLEANUP"
        return 0
    fi
    command rm "$@"
}
if consume_committed_update_cleanup_locked; then fail "cleanup metadata TOCTOU was accepted"; fi
[ ! -e "$REAL1" ] && [ -d "$REAL2" ] && [ -e "$UPDATE_CLEANUP" ] ||
    fail "TOCTOU did not stop before the next path and preserve evidence"
Z2_INJECT_CLEANUP_TOCTOU=0
command rm -rf "$REAL2"
command rm -f "$UPDATE_CLEANUP"

rm -f "$UPDATE_CLEANUP"
cat > "$UPDATE_LOCK" <<EOF
version=1
pid=$$
starttime=1
created_epoch=2
token=legacy-lock-token
module_dir=$MODDIR
EOF
chmod 0600 "$UPDATE_LOCK"
if update_lock_allows_start; then fail "legacy bootless lock was auto-stolen"; fi
[ -e "$UPDATE_LOCK" ] || fail "legacy bootless lock was not preserved"

echo "update cleanup v2 shell contract: PASS"
