#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
CASE="${Z2_TEST_TMP:?}/lifecycle-status-v4"
STATE="$CASE/state"
OUTPUT="$CASE/status.out"

fail() { echo "FAIL: lifecycle-status-v4: $*" >&2; exit 1; }

owner_pid=""
cleanup() {
    [ -z "$owner_pid" ] || kill "$owner_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

mkdir -p "$STATE"
chmod 0700 "$STATE"

run_status() {
    rc=0
    STATE_DIR="$STATE" MODDIR="$ROOT" ZAPRET_DIR="$ROOT/zapret2" \
        sh "$ROOT/zapret2/scripts/zapret-status.sh" --machine-v4 > "$OUTPUT" 2>&1 || rc=$?
    [ "$rc" -le 2 ] || fail "status returned an invalid exit code"
    [ "$(tail -n 1 "$OUTPUT")" = "Z2_COMPLETE=1" ] ||
        fail "v4 status completion marker is missing or non-terminal"
    grep -Fxq "Z2_PROTOCOL=4" "$OUTPUT" || fail "v4 protocol marker is missing"
    if grep -Fq "acquire_lifecycle_lock" "$ROOT/zapret2/scripts/zapret-status.sh"; then
        fail "status observer still acquires the mutation lock"
    fi
}

sleep 30 &
owner_pid=$!
owner_start=$(
    stat_line=$(cat "/proc/$owner_pid/stat")
    tail_fields=${stat_line##*) }
    set -- $tail_fields
    shift 19
    printf '%s\n' "$1"
)
mkdir "$STATE/lifecycle.lock"
printf 'pid=%s\nstarttime=%s\ntoken=status-live-owner\n' "$owner_pid" "$owner_start" \
    > "$STATE/lifecycle.lock/owner"
chmod 0600 "$STATE/lifecycle.lock/owner"

run_status
grep -Fxq "Z2_LIFECYCLE_STATE=active" "$OUTPUT" ||
    fail "live exact owner was not reported active"
grep -Fxq "Z2_LIFECYCLE_OWNER_KIND=shell" "$OUTPUT" ||
    fail "live exact owner kind was not reported"
[ -d "$STATE/lifecycle.lock" ] || fail "status removed a live owner"

kill "$owner_pid" >/dev/null 2>&1 || true
wait "$owner_pid" 2>/dev/null || true
owner_pid=""

run_status
grep -Fxq "Z2_LIFECYCLE_STATE=recovery_failed" "$OUTPUT" ||
    fail "proven stale owner was not reported for explicit recovery"
[ -d "$STATE/lifecycle.lock" ] ||
    fail "read-only status removed a proven stale owner"

rm -rf "$STATE/lifecycle.lock"

boot_id=$(cat /proc/sys/kernel/random/boot_id)
mkdir "$STATE/lifecycle.lock"
printf 'version=1\nkind=android-mutation\npid=2147483647\nstarttime=1\nboot_id=%s\ntoken=dead-android-owner\nmodule_dir=%s\n' \
    "$boot_id" "$ROOT" > "$STATE/lifecycle.lock/owner"
chmod 0600 "$STATE/lifecycle.lock/owner"

run_status
grep -Fxq "Z2_LIFECYCLE_STATE=recovery_failed" "$OUTPUT" ||
    fail "proven stale Android owner was not reported for explicit recovery"
[ -d "$STATE/lifecycle.lock" ] ||
    fail "read-only status removed a proven stale Android owner"

rm -rf "$STATE/lifecycle.lock"

mkdir "$STATE/lifecycle.lock"
printf 'foreign=unsafe\n' > "$STATE/lifecycle.lock/owner"
chmod 0600 "$STATE/lifecycle.lock/owner"
cp "$STATE/lifecycle.lock/owner" "$CASE/ambiguous.before"

run_status
grep -Fxq "Z2_LIFECYCLE_STATE=ambiguous" "$OUTPUT" ||
    fail "malformed owner was not reported ambiguous"
grep -Fxq "Z2_LIFECYCLE_OWNER_KIND=unknown" "$OUTPUT" ||
    fail "malformed owner kind was not fail-closed"
cmp -s "$CASE/ambiguous.before" "$STATE/lifecycle.lock/owner" ||
    fail "ambiguous lifecycle evidence was modified"

echo "Lifecycle status v4 tests passed"
