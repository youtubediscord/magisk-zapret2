#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
CASE="${Z2_TEST_TMP:?}/lifecycle-status-v5"
STATE="$CASE/state"
OUTPUT="$CASE/status.out"
AUDIT_MARKER="$CASE/deep-firewall-audit.called"
FAKE_BIN="$CASE/fake-bin"

fail() { echo "FAIL: lifecycle-status-v5: $*" >&2; exit 1; }

mkdir -p "$STATE"
chmod 0700 "$STATE"
mkdir -p "$FAKE_BIN"
for tool in iptables ip6tables; do
    printf '#!/bin/sh\n: > "%s"\nexit 99\n' "$AUDIT_MARKER" > "$FAKE_BIN/$tool"
    chmod 0755 "$FAKE_BIN/$tool"
done
PATH="$FAKE_BIN:$PATH"
export PATH

if grep -Fq "acquire_lifecycle_lock" "$ROOT/zapret2/scripts/zapret-status.sh"; then
    fail "v5 status observer still acquires the mutation lock"
fi
if grep -Eq 'scan_exact_owned_nfqws|owned_family_present|owner_family_generation_healthy' \
    "$ROOT/zapret2/scripts/zapret-status.sh"; then
    fail "status observer still contains a deep process or firewall audit"
fi

run_status() {
    rc=0
    STATE_DIR="$STATE" MODDIR="$ROOT" ZAPRET_DIR="$ROOT/zapret2" \
        sh "$ROOT/zapret2/scripts/zapret-status.sh" --machine-v5 > "$OUTPUT" 2>&1 || rc=$?
    [ "$rc" -le 2 ] || fail "status returned an invalid exit code"
    [ "$(tail -n 1 "$OUTPUT")" = "Z2_COMPLETE=1" ] ||
        fail "v5 status completion marker is missing or non-terminal"
    grep -Fxq "Z2_PROTOCOL=5" "$OUTPUT" || fail "v5 protocol marker is missing"
    grep -Eq '^Z2_CHAINS=[0-9]+$' "$OUTPUT" || fail "v5 chain count is missing"
    grep -Eq '^Z2_ANCHORS=[0-9]+$' "$OUTPUT" || fail "v5 anchor count is missing"
    [ "$(grep -c '^Z2_CHAINS=' "$OUTPUT")" = 1 ] || fail "v5 chain count is not unique"
    [ "$(grep -c '^Z2_ANCHORS=' "$OUTPUT")" = 1 ] || fail "v5 anchor count is not unique"
}

run_status
[ ! -e "$AUDIT_MARKER" ] ||
    fail "machine status invoked a deep firewall audit"

mkdir "$STATE/lifecycle.lock"
printf 'foreign=unsafe\n' > "$STATE/lifecycle.lock/owner"
chmod 0600 "$STATE/lifecycle.lock/owner"
cp "$STATE/lifecycle.lock/owner" "$CASE/ambiguous.before"

run_status
grep -Fxq "Z2_LIFECYCLE_STATE=ambiguous" "$OUTPUT" ||
    fail "malformed owner was not reported ambiguous"
grep -Fxq "Z2_CHAINS=0" "$OUTPUT" ||
    fail "blocked v5 observation did not publish zero chains"
grep -Fxq "Z2_ANCHORS=0" "$OUTPUT" ||
    fail "blocked v5 observation did not publish zero anchors"
cmp -s "$CASE/ambiguous.before" "$STATE/lifecycle.lock/owner" ||
    fail "ambiguous lifecycle evidence was modified"

echo "Lifecycle status v5 tests passed"
