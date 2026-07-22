#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/lifecycle-lock-owner"
MOD="$CASE/module"
STATE="$CASE/state"
BOOT_A=11111111-1111-1111-1111-111111111111
BOOT_B=22222222-2222-2222-2222-222222222222

fail() { echo "FAIL: lifecycle-lock-owner: $*" >&2; exit 1; }

Z2_TEST_FAKE_FS_META=0
if ! command -v chmod >/dev/null 2>&1 || ! command -v stat >/dev/null 2>&1; then
    Z2_TEST_FAKE_FS_META=1
    chmod() { return 0; }
fi
if ! command -v sleep >/dev/null 2>&1; then sleep() { return 0; }; fi

mkdir -p "$MOD/zapret2/scripts" "$STATE"
chmod 0700 "$STATE"
STATE_DIR="$STATE" MODDIR="$MOD" ZAPRET_DIR="$MOD/zapret2" SCRIPT_DIR="$MOD/zapret2/scripts"
export STATE_DIR MODDIR ZAPRET_DIR SCRIPT_DIR
. "$ROOT/zapret2/scripts/common.sh"
if [ "$Z2_TEST_FAKE_FS_META" = 1 ]; then
    state_dir_is_secure() { [ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ]; }
    path_uid_is_root() { [ -e "$1" ] && [ ! -L "$1" ]; }
    path_mode_is_0600() { [ -f "$1" ] && [ ! -L "$1" ]; }
    path_nlink_is_one() { [ -f "$1" ] && [ ! -L "$1" ]; }
fi

Z2_LOCK_BOOT="$BOOT_A"
Z2_LOCK_BOOT_QUERY=ok
Z2_ANDROID_PROCESS=dead
read_current_boot_id() {
    [ "$Z2_LOCK_BOOT_QUERY" = ok ] || return 1
    CURRENT_BOOT_ID="$Z2_LOCK_BOOT"
}
proc_starttime() {
    case "$1" in
        "$$") printf '1000\n' ;;
        4242) [ "$Z2_ANDROID_PROCESS" = live ] && printf '424242\n' || return 1 ;;
        *) return 1 ;;
    esac
}
claim_lifecycle_gate() { return 0; }
release_lifecycle_gate() { return 0; }

write_shell_owner() {
    rm -rf "$LIFECYCLE_LOCK"
    mkdir "$LIFECYCLE_LOCK"
    printf 'pid=%s\nstarttime=1000\ntoken=shell-token\n' "$$" > "$LIFECYCLE_LOCK_OWNER"
    chmod 0600 "$LIFECYCLE_LOCK_OWNER"
}

write_android_owner() {
    owner_boot="$1"
    rm -rf "$LIFECYCLE_LOCK"
    mkdir "$LIFECYCLE_LOCK"
    cat > "$LIFECYCLE_LOCK_OWNER" <<EOF
version=1
kind=android-mutation
pid=4242
starttime=424242
boot_id=$owner_boot
token=android-token
module_dir=$MOD
EOF
    chmod 0600 "$LIFECYCLE_LOCK_OWNER"
}

reset_acquire() {
    LOCK_HELD=0; LOCK_OWNER_PID=""; LOCK_OWNER_START=""; LOCK_OWNER_TOKEN=""
    unset ZAPRET2_LIFECYCLE_TOKEN ZAPRET2_LIFECYCLE_OWNER_PID ZAPRET2_LIFECYCLE_OWNER_START
    LIFECYCLE_LOCK_WAIT_SECONDS="${1:-1}"
}

write_shell_owner
read_lock_owner || fail "exact shell owner was rejected"
[ "$LOCK_FILE_KIND:$LOCK_FILE_PID:$LOCK_FILE_START:$LOCK_FILE_TOKEN" = "shell:$$:1000:shell-token" ] || fail "shell owner fields changed"
lock_owner_alive || fail "live exact shell owner was classified stale"

cp "$LIFECYCLE_LOCK_OWNER" "$CASE/shell.valid"
{ printf 'future=bad\n'; cat "$CASE/shell.valid"; } > "$LIFECYCLE_LOCK_OWNER"
if read_lock_owner; then fail "unknown shell owner field was accepted"; fi
{ cat "$CASE/shell.valid"; printf 'token=duplicate\n'; } > "$LIFECYCLE_LOCK_OWNER"
if read_lock_owner; then fail "duplicate shell owner field was accepted"; fi
{ sed -n '2p' "$CASE/shell.valid"; sed -n '1p' "$CASE/shell.valid"; sed -n '3p' "$CASE/shell.valid"; } > "$LIFECYCLE_LOCK_OWNER"
if read_lock_owner; then fail "reordered shell owner was accepted"; fi

write_android_owner "$BOOT_A"
Z2_ANDROID_PROCESS=live
read_lock_owner || fail "exact Android mutation owner was rejected"
[ "$LOCK_FILE_KIND:$LOCK_FILE_BOOT:$LOCK_FILE_MODULE" = "android-mutation:$BOOT_A:$MOD" ] || fail "Android owner fields changed"
lock_owner_alive || fail "same-boot live Android owner was classified stale"
Z2_ANDROID_PROCESS=dead
if lock_owner_alive; then fail "same-boot dead Android owner was classified live"; fi
Z2_ANDROID_PROCESS=live; Z2_LOCK_BOOT="$BOOT_B"
if lock_owner_alive; then fail "cross-boot Android owner was classified live by reused PID"; fi
Z2_LOCK_BOOT="$BOOT_A"; Z2_LOCK_BOOT_QUERY=failed
lock_owner_alive || fail "unavailable boot identity did not fail closed"
Z2_LOCK_BOOT_QUERY=ok

for corruption in version kind module unknown duplicate reorder; do
    write_android_owner "$BOOT_A"
    cp "$LIFECYCLE_LOCK_OWNER" "$CASE/android.valid"
    case "$corruption" in
        version) sed 's/^version=.*/version=2/' "$CASE/android.valid" > "$LIFECYCLE_LOCK_OWNER" ;;
        kind) sed 's/^kind=.*/kind=foreign/' "$CASE/android.valid" > "$LIFECYCLE_LOCK_OWNER" ;;
        module) sed 's|^module_dir=.*|module_dir=/foreign/module|' "$CASE/android.valid" > "$LIFECYCLE_LOCK_OWNER" ;;
        unknown) { cat "$CASE/android.valid"; printf 'future=value\n'; } > "$LIFECYCLE_LOCK_OWNER" ;;
        duplicate) { cat "$CASE/android.valid"; printf 'token=duplicate\n'; } > "$LIFECYCLE_LOCK_OWNER" ;;
        reorder) { sed -n '2p' "$CASE/android.valid"; sed -n '1p' "$CASE/android.valid"; tail -n +3 "$CASE/android.valid"; } > "$LIFECYCLE_LOCK_OWNER" ;;
    esac
    chmod 0600 "$LIFECYCLE_LOCK_OWNER"
    if read_lock_owner; then fail "$corruption Android owner was accepted"; fi
done

# Acquisition preserves unknown/foreign evidence rather than treating parser
# failure as staleness.
write_android_owner "$BOOT_A"
sed 's/^module_dir=.*/module_dir=\/foreign\/module/' "$LIFECYCLE_LOCK_OWNER" > "$CASE/foreign"
mv "$CASE/foreign" "$LIFECYCLE_LOCK_OWNER"; chmod 0600 "$LIFECYCLE_LOCK_OWNER"
cp "$LIFECYCLE_LOCK_OWNER" "$CASE/foreign.before"
reset_acquire
if acquire_lifecycle_lock; then fail "foreign Android owner was reaped"; fi
cmp -s "$CASE/foreign.before" "$LIFECYCLE_LOCK_OWNER" || fail "foreign Android owner evidence changed"

# Exact dead and exact cross-boot Android leases are the only Android records
# eligible for stable double-observation quarantine.
for stale_case in dead cross-boot; do
    write_android_owner "$BOOT_A"
    Z2_LOCK_BOOT="$BOOT_A"; Z2_ANDROID_PROCESS=dead
    if [ "$stale_case" = cross-boot ]; then Z2_LOCK_BOOT="$BOOT_B"; Z2_ANDROID_PROCESS=live; fi
    reset_acquire 2
    acquire_lifecycle_lock || fail "$stale_case Android owner was not reaped"
    read_lock_owner || fail "$stale_case reap did not publish a shell owner"
    [ "$LOCK_FILE_KIND" = shell ] || fail "$stale_case reap published a non-shell owner"
    release_lifecycle_lock || fail "$stale_case replacement shell lock did not release"
done

write_android_owner "$BOOT_A"
Z2_LOCK_BOOT="$BOOT_A"; Z2_ANDROID_PROCESS=live
cp "$LIFECYCLE_LOCK_OWNER" "$CASE/live.before"
reset_acquire
if acquire_lifecycle_lock; then fail "same-boot live Android owner was reaped"; fi
cmp -s "$CASE/live.before" "$LIFECYCLE_LOCK_OWNER" || fail "live Android owner evidence changed"

rm -rf "$LIFECYCLE_LOCK"
echo "Lifecycle lock dual-owner shell tests passed"
