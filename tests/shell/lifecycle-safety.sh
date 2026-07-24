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
esac
case " $* " in
    *' -t mangle -L OUTPUT -n '*) exit 0 ;;
    *' -t mangle -S ZAPRET2_OUT '*)
        case "${Z2_QUERY_MODE:-clean}" in
            present|foreign) echo '-N ZAPRET2_OUT'; exit 0 ;;
            *) exit 1 ;;
        esac
        ;;
    *' -t mangle -S ZAPRET2_IN '*) exit 1 ;;
esac
case "${Z2_QUERY_MODE:-clean}: $* " in
    present:*' -t mangle -C OUTPUT -j ZAPRET2_OUT '*) exit 0 ;;
    present:*' -t mangle -S ')
        printf '%s\n' '-N ZAPRET2_OUT' '-A OUTPUT -j ZAPRET2_OUT'
        exit 0
        ;;
    foreign:*' -t mangle -S ')
        printf '%s\n' '-N ZAPRET2_OUT' '-A FORWARD -j ZAPRET2_OUT'
        exit 0
        ;;
    clean:*' -t mangle -S ') exit 0 ;;
    *) exit 1 ;;
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

Z2_QUERY_MODE=foreign; export Z2_QUERY_MODE
if z2_fw_cleanup_is_unambiguous iptables; then
    fail "foreign reference to the stable namespace passed cleanup preflight"
fi

LEGACY_QNUM=200
Z2_QUERY_MODE=fail; export Z2_QUERY_MODE
if legacy_direct_qnum_count iptables >/dev/null 2>&1; then
    fail "legacy full-list query failure was masked by its counting pipeline"
fi

grep -Fq 'boot_id=%s' "$ROOT/zapret2/scripts/common.sh" || fail "owner publication is not boot-bound"
grep -Fq 'return 2' "$ROOT/zapret2/scripts/common.sh" || fail "tri-state query error is absent"
grep -Fq 'phase_at_least process-clean' "$ROOT/zapret2/scripts/zapret-full-rollback.sh" || fail "rollback resume gates are absent"

# Boot-local firewall state is reconstructed from one stable namespace. It has
# no durability journal and rejects ambiguous foreign references before delete.
grep -Fq 'z2_fw_cleanup_is_unambiguous' "$ROOT/zapret2/scripts/firewall-reconciler.sh" ||
    fail "stable namespace cleanup preflight is absent"
grep -Fq -- '--test --noflush' "$ROOT/zapret2/scripts/firewall-reconciler.sh" ||
    fail "whole-batch restore validation is absent"
if grep -Eq 'prepare_teardown_marker|consume_bracketed_teardown_target|target-consumed' \
    "$ROOT/zapret2/scripts/firewall-reconciler.sh"; then
    fail "firewall reconciler contains obsolete teardown WAL machinery"
fi

echo "Lifecycle safety shell tests passed"
