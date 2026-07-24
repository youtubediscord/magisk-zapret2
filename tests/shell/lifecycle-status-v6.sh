#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
CASE="${Z2_TEST_TMP:?}/lifecycle-status-v6"
STATE="$CASE/state"
OUTPUT="$CASE/status.out"
OWNER="$STATE/lifecycle.lock/owner"
TOKEN=app.statusowner

fail() { echo "FAIL: lifecycle-status-v6: $*" >&2; exit 1; }

mkdir -p "$STATE/lifecycle.lock"
chmod 0700 "$STATE" "$STATE/lifecycle.lock"
BOOT_ID=$(sed -n '1p' /proc/sys/kernel/random/boot_id)
STARTTIME=$(awk '{print $22}' "/proc/$$/stat")
cat > "$OWNER" <<EOF
version=1
kind=android-mutation
pid=$$
starttime=$STARTTIME
boot_id=$BOOT_ID
token=$TOKEN
module_dir=$ROOT
EOF
chmod 0600 "$OWNER"
cp "$OWNER" "$CASE/owner.before"

run_status() {
    expected_rc="$1"
    shift
    rc=0
    STATE_DIR="$STATE" MODDIR="$ROOT" ZAPRET_DIR="$ROOT/zapret2" "$@" \
        sh "$ROOT/zapret2/scripts/zapret-status.sh" --machine-v6 > "$OUTPUT" 2>&1 || rc=$?
    [ "$rc" -eq "$expected_rc" ] ||
        fail "status returned $rc instead of $expected_rc"
    [ "$(tail -n 1 "$OUTPUT")" = "Z2_COMPLETE=1" ] ||
        fail "v6 status completion marker is missing or non-terminal"
    grep -Fxq "Z2_PROTOCOL=6" "$OUTPUT" || fail "v6 protocol marker is missing"
    cmp -s "$CASE/owner.before" "$OWNER" ||
        fail "read-only status modified lifecycle ownership"
}

run_status 1 env \
    ZAPRET2_LIFECYCLE_TOKEN="$TOKEN" \
    ZAPRET2_LIFECYCLE_OWNER_PID="$$" \
    ZAPRET2_LIFECYCLE_OWNER_START="$STARTTIME"
grep -Fxq "Z2_STATUS=stopped" "$OUTPUT" ||
    fail "caller-owned stopped state was not observed"
grep -Fxq "Z2_LIFECYCLE_STATE=owned" "$OUTPUT" ||
    fail "exact caller ownership was not authenticated"
grep -Fxq "Z2_LIFECYCLE_OWNER_KIND=android-mutation" "$OUTPUT" ||
    fail "caller-owned lifecycle kind is wrong"
grep -Fxq "Z2_UPDATE_BLOCKED=0" "$OUTPUT" ||
    fail "caller-owned observation was blocked from its own transaction"

run_status 2 env \
    ZAPRET2_LIFECYCLE_TOKEN=app.wrongtoken \
    ZAPRET2_LIFECYCLE_OWNER_PID="$$" \
    ZAPRET2_LIFECYCLE_OWNER_START="$STARTTIME"
grep -Fxq "Z2_LIFECYCLE_STATE=active" "$OUTPUT" ||
    fail "mismatched caller was not kept behind the active-owner barrier"
grep -Fxq "Z2_UPDATE_BLOCKED=1" "$OUTPUT" ||
    fail "foreign active owner did not block mutations"
grep -Fxq "Z2_ERROR_CODE=LIFECYCLE_ACTIVE" "$OUTPUT" ||
    fail "foreign active owner did not emit the typed busy result"

echo "Lifecycle status v6 tests passed"
