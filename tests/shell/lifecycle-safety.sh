#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/lifecycle-safety"
MOCK="$CASE/bin"
mkdir -p "$MOCK"

fail() { echo "FAIL: lifecycle-safety: $*" >&2; exit 1; }

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
case "${Z2_QUERY_MODE:-clean}" in
    fail) exit 42 ;;
    present)
        printf '%s\n' "-N ${ZAPRET2_OUT:?}" "-A OUTPUT -j $ZAPRET2_OUT"
        exit 0
        ;;
    clean) exit 0 ;;
esac
EOF
chmod 0755 "$MOCK/iptables"

SCRIPT_DIR="$ROOT/zapret2/scripts"
ZAPRET_DIR="$ROOT/zapret2"
MODDIR="$ROOT"
STATE_DIR="$CASE/state"
mkdir -p "$STATE_DIR"
chmod 0700 "$STATE_DIR"
PATH="$MOCK:$PATH"
export PATH STATE_DIR
. "$ROOT/zapret2/scripts/common.sh"
FIREWALL_TAG=testsafety
ZAPRET2_OUT="Z2O_$FIREWALL_TAG"
ZAPRET2_IN="Z2I_$FIREWALL_TAG"
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN

# A stopped service must not perform the expensive exact identity proof for
# every Android PID. The shell-builtin cmdline prefilter admits the current
# process only when its actual argv0 prefix is selected.
(
    CURRENT_ARGV0="$(proc_argv0 "$$")" || fail "current argv0 unavailable"
    NFQWS2="$CURRENT_ARGV0"
    proc_cmdline_may_match_nfqws "$$" || fail "exact argv0 candidate was filtered out"
    NFQWS2="${CURRENT_ARGV0}.not-the-current-process"
    if proc_cmdline_may_match_nfqws "$$"; then fail "non-candidate argv0 prefix was admitted"; fi
    NFQWS2=/definitely/not/a/zapret2/process
    verify_nfqws_pid() { fail "strict PID proof ran for a non-candidate process"; }
    scan_exact_owned_nfqws >/dev/null || fail "empty exact process scan failed"
    [ -z "$OWNED_SCAN_PIDS" ] || fail "empty exact process scan reported an owner"
)

Z2_QUERY_MODE=fail; export Z2_QUERY_MODE
set +e
owned_family_present iptables
rc=$?
set -e
[ "$rc" = 2 ] || fail "query failure was not tri-state error"
if owned_family_absent iptables; then fail "query failure was accepted as absence"; fi

Z2_QUERY_MODE=clean; export Z2_QUERY_MODE
set +e
owned_family_present iptables
rc=$?
set -e
[ "$rc" = 1 ] || fail "clean snapshot was not absence"
owned_family_absent iptables || fail "clean snapshot absence was rejected"

Z2_QUERY_MODE=present; export Z2_QUERY_MODE
owned_family_present iptables || fail "owned chain/anchor was not detected"
if owned_family_absent iptables; then fail "owned state was accepted as absent"; fi

LEGACY_QNUM=200
Z2_QUERY_MODE=fail; export Z2_QUERY_MODE
if legacy_direct_qnum_count iptables >/dev/null 2>&1; then
    fail "legacy full-list query failure was masked by its counting pipeline"
fi

[ "$(teardown_phase_rank pending)" = 1 ] || fail "teardown pending rank"
[ "$(teardown_phase_rank consuming-ipv4)" -lt "$(teardown_phase_rank consumed-ipv4)" ] || fail "IPv4 teardown ranks regress"
[ "$(teardown_phase_rank consumed-ipv4)" -lt "$(teardown_phase_rank consuming-ipv6)" ] || fail "family teardown ranks regress"
[ "$(teardown_phase_rank consumed-ipv6)" -lt "$(teardown_phase_rank consumed)" ] || fail "teardown commit rank regresses"

grep -Fq 'boot_id=%s' "$ROOT/zapret2/scripts/common.sh" || fail "owner publication is not boot-bound"
grep -Fq 'return 2' "$ROOT/zapret2/scripts/common.sh" || fail "tri-state query error is absent"
grep -Fq 'phase_at_least process-clean' "$ROOT/zapret2/scripts/zapret-full-rollback.sh" || fail "rollback resume gates are absent"

# The operation WAL must use bracket markers for exact D reconciliation and
# rename tombstones for exact X reconciliation without xt_comment.
grep -Fq 'prepare_teardown_marker' "$ROOT/zapret2/scripts/common.sh" || fail "rule/anchor bracket protocol is absent"
grep -Fq 'consume_bracketed_teardown_target' "$ROOT/zapret2/scripts/common.sh" || fail "bracketed D recovery is absent"
grep -Fq -- '-E "$chain" "$tomb"' "$ROOT/zapret2/scripts/common.sh" || fail "chain rename tombstone protocol is absent"
grep -Fq 'target-consumed' "$ROOT/zapret2/scripts/common.sh" || fail "post-D/X durable state is absent"
grep -Fq 'validate_teardown_operation_journal && return 0' "$ROOT/zapret2/scripts/common.sh" || fail "authenticated partial WAL admission is absent"
grep -Fq 'locate_teardown_rule_block' "$ROOT/zapret2/scripts/common.sh" || fail "ordered generation occurrence lookup is absent"
grep -Fq 'teardown_bracket_matches' "$ROOT/zapret2/scripts/common.sh" || fail "immediate pre-D byte verification is absent"

echo "Lifecycle safety shell tests passed"
