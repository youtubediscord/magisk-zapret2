#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/firewall-reconciler"
STATE="$CASE/state"
MOCK="$CASE/bin"
FW="$CASE/fw"

fail() { echo "FAIL: firewall-reconciler: $*" >&2; exit 1; }

mkdir -p "$STATE" "$MOCK" "$FW"

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
state="$Z2_MOCK_FW"
args=" $* "
case "$args" in
    *' -t mangle -L OUTPUT -n '*) exit 0 ;;
    *' -t mangle -C OUTPUT -j ZAPRET2_OUT '*) [ -f "$state/anchor.out" ] ;;
    *' -t mangle -C INPUT -j ZAPRET2_IN '*) [ -f "$state/anchor.in" ] ;;
    *' -t mangle -D OUTPUT -j ZAPRET2_OUT '*) rm -f "$state/anchor.out" ;;
    *' -t mangle -D INPUT -j ZAPRET2_IN '*) rm -f "$state/anchor.in" ;;
    *' -t mangle -S ZAPRET2_OUT '*)
        [ -f "$state/chain.out" ] || exit 1
        echo '-N ZAPRET2_OUT'
        [ ! -f "$state/rules.out" ] || cat "$state/rules.out"
        ;;
    *' -t mangle -S ZAPRET2_IN '*)
        [ -f "$state/chain.in" ] || exit 1
        echo '-N ZAPRET2_IN'
        [ ! -f "$state/rules.in" ] || cat "$state/rules.in"
        ;;
    *' -t mangle -C ZAPRET2_OUT '*)
        needle="-A ZAPRET2_OUT ${args#* -C ZAPRET2_OUT }"
        needle=${needle% }
        grep -Fqx -- "$needle" "$state/rules.out"
        ;;
    *' -t mangle -C ZAPRET2_IN '*)
        needle="-A ZAPRET2_IN ${args#* -C ZAPRET2_IN }"
        needle=${needle% }
        grep -Fqx -- "$needle" "$state/rules.in"
        ;;
    *' -t mangle -F ZAPRET2_OUT '*) : > "$state/rules.out" ;;
    *' -t mangle -F ZAPRET2_IN '*) : > "$state/rules.in" ;;
    *' -t mangle -X ZAPRET2_OUT '*)
        [ ! -s "$state/rules.out" ] && [ ! -f "$state/anchor.out" ] || exit 1
        rm -f "$state/chain.out" "$state/rules.out"
        ;;
    *' -t mangle -X ZAPRET2_IN '*)
        [ ! -s "$state/rules.in" ] && [ ! -f "$state/anchor.in" ] || exit 1
        rm -f "$state/chain.in" "$state/rules.in"
        ;;
    *' -t mangle -S '*)
        [ ! -f "$state/chain.out" ] || echo '-N ZAPRET2_OUT'
        [ ! -f "$state/chain.in" ] || echo '-N ZAPRET2_IN'
        [ ! -f "$state/anchor.out" ] || echo '-A OUTPUT -j ZAPRET2_OUT'
        [ ! -f "$state/anchor.in" ] || echo '-A INPUT -j ZAPRET2_IN'
        [ "${Z2_FOREIGN_REF:-0}" != 1 ] || echo '-A FORWARD -j ZAPRET2_OUT'
        [ ! -s "$state/rules.out" ] || cat "$state/rules.out"
        [ ! -s "$state/rules.in" ] || cat "$state/rules.in"
        ;;
    *) exit 1 ;;
esac
EOF

cat > "$MOCK/iptables-restore" <<'EOF'
#!/bin/sh
state="$Z2_MOCK_FW"
payload=$(cat)
count=0
[ ! -f "$state/restore.count" ] || IFS= read -r count < "$state/restore.count"
count=$((count + 1))
printf '%s\n' "$count" > "$state/restore.count"
case "$payload" in *Z2R_*) exit 90;; esac
if [ "${Z2_RESTORE_REJECT_CONNBYTES:-0}" = 1 ] &&
   printf '%s\n' "$payload" | grep -q -- '-m connbytes'; then
    exit 1
fi
case " $* " in *' --test '*) exit 0;; esac
[ "${Z2_RESTORE_FAIL_COMMIT:-0}" != 1 ] || exit 1
printf '%s\n' "$payload" | grep -F -- ':ZAPRET2_OUT ' >/dev/null || exit 1
: > "$state/chain.out"
printf '%s\n' "$payload" | grep -F -- '-A ZAPRET2_OUT ' > "$state/rules.out" || :
if printf '%s\n' "$payload" | grep -F -- ':ZAPRET2_IN ' >/dev/null; then
    : > "$state/chain.in"
    printf '%s\n' "$payload" | grep -F -- '-A ZAPRET2_IN ' > "$state/rules.in" || :
else
    rm -f "$state/chain.in" "$state/rules.in"
fi
printf '%s\n' "$payload" | grep -Fx -- '-A OUTPUT -j ZAPRET2_OUT' >/dev/null &&
    : > "$state/anchor.out"
if printf '%s\n' "$payload" | grep -Fx -- '-A INPUT -j ZAPRET2_IN' >/dev/null; then
    : > "$state/anchor.in"
else
    rm -f "$state/anchor.in"
fi
EOF

chmod 0755 "$MOCK/iptables" "$MOCK/iptables-restore"
PATH="$MOCK:$PATH"
STATE_DIR="$STATE"
Z2_MOCK_FW="$FW"
export PATH STATE_DIR Z2_MOCK_FW

state_path_is_managed_file() {
    case "$1" in "$STATE"/*) return 0;; *) return 1;; esac
}

. "$ROOT/zapret2/scripts/firewall-reconciler.sh"

PORTS_TCP=80,443
PORTS_UDP=443,3478,5349,19302
TCP_PKT_OUT=20
TCP_PKT_IN=10
UDP_PKT_OUT=12
UDP_PKT_IN=6
QNUM=200
DESYNC_MARK=0x40000000

z2_fw_reconcile_family iptables || fail "atomic restore reconcile failed"
[ "$Z2_FW_BACKEND:$Z2_FW_CONNBYTES:$Z2_FW_RULES:$Z2_FW_CHAINS:$Z2_FW_ANCHORS" = restore:1:4:2:2 ] ||
    fail "atomic restore result metadata changed"
[ -f "$FW/anchor.out" ] && [ -f "$FW/anchor.in" ] ||
    fail "atomic restore did not publish both anchors"
[ "$(cat "$FW/restore.count")" = 2 ] ||
    fail "atomic restore did not use exactly one test and one commit"
z2_fw_verify_family iptables 1 || fail "published family did not verify"

z2_fw_cleanup_family iptables || fail "stable namespace cleanup failed"
z2_fw_cleanup_family iptables || fail "stable namespace cleanup is not idempotent"
z2_fw_family_absent iptables || fail "stable namespace remains after cleanup"

rm -f "$FW"/*
Z2_RESTORE_REJECT_CONNBYTES=1
export Z2_RESTORE_REJECT_CONNBYTES
z2_fw_reconcile_family iptables || fail "connbytes fallback reconcile failed"
[ "$Z2_FW_CONNBYTES:$Z2_FW_RULES:$Z2_FW_CHAINS:$Z2_FW_ANCHORS" = 0:2:1:1 ] ||
    fail "outgoing-only fallback metadata changed"
[ -f "$FW/anchor.out" ] && [ ! -f "$FW/anchor.in" ] ||
    fail "outgoing-only fallback published an input anchor"
unset Z2_RESTORE_REJECT_CONNBYTES
z2_fw_cleanup_family iptables || fail "fallback cleanup failed"

rm -f "$FW"/*
Z2_RESTORE_FAIL_COMMIT=1
export Z2_RESTORE_FAIL_COMMIT
if z2_fw_reconcile_family iptables; then
    fail "failed COMMIT was accepted"
fi
[ "$(cat "$FW/restore.count")" = 2 ] ||
    fail "failed COMMIT incorrectly retried with a degraded topology"
z2_fw_family_absent iptables || fail "failed COMMIT left live firewall state"
unset Z2_RESTORE_FAIL_COMMIT

: > "$FW/chain.out"
: > "$FW/rules.out"
: > "$FW/anchor.out"
z2_fw_cleanup_family iptables || fail "interrupted publication did not converge to absent"
z2_fw_family_absent iptables || fail "interrupted publication retained stable state"

: > "$FW/chain.out"
: > "$FW/rules.out"
Z2_FOREIGN_REF=1
export Z2_FOREIGN_REF
if z2_fw_cleanup_family iptables; then
    fail "foreign reference to stable namespace was accepted"
fi
[ -f "$FW/chain.out" ] || fail "foreign-reference preflight partially mutated its chain"
unset Z2_FOREIGN_REF
z2_fw_cleanup_family iptables || fail "stable namespace did not recover after foreign reference disappeared"

z2_fw_restore_command() { printf '%s\n' missing-iptables-restore; }
set +e
z2_fw_reconcile_family iptables
rc=$?
set -e
[ "$rc" = 3 ] || fail "missing restore backend did not return capability status"
z2_fw_family_absent iptables || fail "missing restore backend mutated firewall state"

echo "Firewall reconciler shell tests passed"
