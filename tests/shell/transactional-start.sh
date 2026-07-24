#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/transactional-start"
MOD="$CASE/module"
SCRIPTS="$MOD/zapret2/scripts"
STATE="$CASE/state"
LOG="$CASE/calls"

fail() { echo "FAIL: transactional-start: $*" >&2; exit 1; }
mkdir -p "$SCRIPTS" "$STATE"
chmod 0700 "$STATE"
cp "$ROOT/zapret2/scripts/common.sh" "$SCRIPTS/common.sh"
cp "$ROOT/zapret2/scripts/command-builder.sh" "$SCRIPTS/command-builder.sh"
sed '$d' "$ROOT/zapret2/scripts/zapret-start.sh" > "$SCRIPTS/zapret-start-defs.sh"

cat > "$SCRIPTS/scenario.sh" <<'EOF'
#!/bin/sh
set -eu
. "$(dirname "$0")/zapret-start-defs.sh"
LOG=${Z2_START_TEST_LOG:?}
log_error() { printf 'log:%s\n' "$1" >> "$LOG"; }
rollback_legacy_migration() { echo legacy >> "$LOG"; return 0; }
release_lifecycle_lock() { echo release >> "$LOG"; return 0; }
write_ok_status() { echo status-ok >> "$LOG"; return 0; }
set_owner_phase() { return 0; }
restore_status_facts() { return 0; }
snapshot_owned_state() {
    SNAP_PID=""; SNAP_PID_START=""; SNAP_GENERATION=""; SNAP_PID_VERIFIED=0
    SNAP_IPV4=0; SNAP_IPV6=0; SNAP_RULES=0; SNAP_CHAINS=0; SNAP_ANCHORS=0
}
write_iptables_status() { echo status-error >> "$LOG"; return 0; }

case "$1" in
    pre)
        CONTROLLED_TEARDOWN_STARTED=0
        rollback_start() { echo unexpected-rollback >> "$LOG"; return 0; }
        fail_start preflight-failure
        ;;
    post|signal)
        CONTROLLED_TEARDOWN_STARTED=1; FIREWALL_MUTATED=1
        rollback_start() { echo rollback-new >> "$LOG"; FIREWALL_MUTATED=0; return 0; }
        if [ "$1" = signal ]; then handle_signal TERM; else fail_start post-teardown-failure; fi
        ;;
    mismatch)
        ensure_state_dir() { return 0; }
        acquire_lifecycle_lock() { return 0; }
        audit_recovery_artifacts() { return 0; }
        uninstall_tombstone_allows_start() { return 0; }
        read_install_generation_meta() { echo metadata-rejected >> "$LOG"; return 1; }
        read_verified_pidfile() {
            OWNER_STATE_PHASE=active
            OWNER_STATE_SCHEMA_VERSION="$OWNER_STATE_VERSION"
            return 0
        }
        write_runtime_owner_marker() { echo MUTATION-owner-marker >> "$LOG"; return 0; }
        prepare_lifecycle_log() { echo MUTATION-log >> "$LOG"; return 0; }
        main
        ;;
    fast-match|fast-mismatch)
        REPLACE=1
        FAST_REPLACE_BASELINE=1
        FAST_REPLACE_FIREWALL_FINGERPRINT=matching-firewall
        FAST_REPLACE_IPV6_ACTIVE=1
        FAST_REPLACE_IPV4_CONNBYTES=1
        FAST_REPLACE_IPV6_CONNBYTES=1
        z2_fw_tool_available() { [ "$1" = ip6tables ]; }
        z2_fw_restore_available() { [ "$1" = ip6tables ]; }
        prepare_new_firewall_identity() {
            FIREWALL_TAG=stable0001
            ZAPRET2_OUT=ZAPRET2_OUT
            ZAPRET2_IN=ZAPRET2_IN
            PENDING_OWNER_GENERATION=request-generation
            return 0
        }
        prepare_owner_generation_spec() {
            OWNER_WRITE_IPV4_RULES=4
            OWNER_WRITE_IPV6_RULES=4
            if [ "$1" = 1 ] && [ "$2" = 1 ] && [ "$PORTS_TCP" = 80,443 ]; then
                OWNER_WRITE_FIREWALL_FINGERPRINT=matching-firewall
            else
                OWNER_WRITE_FIREWALL_FINGERPRINT=different-firewall
            fi
            return 0
        }
        PORTS_TCP=80,443
        if [ "$1" = fast-mismatch ]; then PORTS_TCP=443; fi
        if prepare_fast_replace_candidate; then
            [ "$1" = fast-match ] || exit 3
            [ "$FAST_REPLACE_READY" = 1 ] &&
                [ "$IPV4_RULES:$IPV6_RULES:$IPV6_ACTIVE" = 4:4:1 ]
        else
            [ "$1" = fast-mismatch ] && [ "$FAST_REPLACE_READY" = 0 ]
        fi
        ;;
    *) exit 2 ;;
esac
EOF
chmod 0755 "$SCRIPTS/scenario.sh"

run_failure() {
    scenario="$1"
    : > "$LOG"
    set +e
    Z2_START_TEST_LOG="$LOG" STATE_DIR="$STATE" sh "$SCRIPTS/scenario.sh" "$scenario" > "$CASE/$scenario.out" 2>&1
    rc=$?
    set -e
    [ "$rc" = 1 ] || fail "$scenario did not fail transactionally"
}

printf 'generation=prior-generation\n' > "$CASE/prior-owner"
prior_hash=$(sha256sum "$CASE/prior-owner" | awk '{print $1}')
run_failure pre
[ "$prior_hash" = "$(sha256sum "$CASE/prior-owner" | awk '{print $1}')" ] || fail "pre-teardown failure changed prior generation"
! grep -q '^unexpected-' "$LOG" || fail "pre-teardown failure entered rollback/restore"

run_failure post
grep -Fxq rollback-new "$LOG" || fail "post-teardown failure did not roll back new state"
! grep -Fq exact-prior-restored "$LOG" ||
    fail "post-teardown failure restored an obsolete generation"

run_failure mismatch
grep -Fxq metadata-rejected "$LOG" || fail "install generation mismatch was not checked"
! grep -q '^MUTATION-' "$LOG" || fail "install generation mismatch mutated lifecycle state"

run_failure signal
grep -Fxq rollback-new "$LOG" || fail "signal did not roll back new state"
! grep -Fq exact-prior-restored "$LOG" || fail "signal restored an obsolete generation"

Z2_START_TEST_LOG="$LOG" STATE_DIR="$STATE" sh "$SCRIPTS/scenario.sh" fast-match ||
    fail "matching authenticated firewall topology did not select daemon-only replacement"
Z2_START_TEST_LOG="$LOG" STATE_DIR="$STATE" sh "$SCRIPTS/scenario.sh" fast-mismatch ||
    fail "changed firewall topology did not fall back to the full transaction"

echo "Transactional start shell tests passed"
