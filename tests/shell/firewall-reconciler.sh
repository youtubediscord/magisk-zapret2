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

# Real backends re-render committed rules in their own save format instead of
# echoing the authored batch text: libxt_connbytes saves --connbytes-mode
# before --connbytes-dir, and nft-backed builds translate a single-port
# multiport match into the plain tcp match. Both mocks re-render through this
# shared filter so verification is exercised against kernel output, not
# against the module's own serialization.
cat > "$CASE/render-saved-rules" <<'EOF'
#!/bin/sh
sed \
    -e 's/--connbytes \([0-9:]*\) --connbytes-dir \([a-z]*\) --connbytes-mode \([a-z]*\)/--connbytes \1 --connbytes-mode \3 --connbytes-dir \2/' \
    -e 's/-p tcp -m multiport --dports \([0-9]*\) /-p tcp -m tcp --dport \1 /'
EOF
chmod 0755 "$CASE/render-saved-rules"

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
state="$Z2_MOCK_FW"
args=" $* "
printf '%s\n' "$*" >> "$state/iptables.args"
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
        needle=$(printf '%s\n' "${needle% }" | "$Z2_MOCK_RENDER")
        grep -Fqx -- "$needle" "$state/rules.out"
        ;;
    *' -t mangle -C ZAPRET2_IN '*)
        needle="-A ZAPRET2_IN ${args#* -C ZAPRET2_IN }"
        needle=$(printf '%s\n' "${needle% }" | "$Z2_MOCK_RENDER")
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
case " $* " in
    *' --help '*)
        count=0
        [ ! -f "$state/restore-help.count" ] ||
            IFS= read -r count < "$state/restore-help.count"
        printf '%s\n' $((count + 1)) > "$state/restore-help.count"
        [ "${Z2_RESTORE_WAIT_SUPPORTED:-1}" = 1 ] &&
            echo 'Usage: iptables-restore [ --wait seconds ] [ --test ] [ --noflush ]' ||
            echo 'Usage: iptables-restore [ --test ] [ --noflush ]'
        exit 0
        ;;
esac
payload=$(cat)
count=0
[ ! -f "$state/restore.count" ] || IFS= read -r count < "$state/restore.count"
count=$((count + 1))
printf '%s\n' "$count" > "$state/restore.count"
printf '%s\n' "$*" >> "$state/restore.args"
printf '%s\n' "$payload" > "$state/restore.payload.$count"
if [ -f "$state/lock.remaining" ]; then
    IFS= read -r remaining < "$state/lock.remaining"
    if [ "$remaining" -gt 0 ]; then
        printf '%s\n' $((remaining - 1)) > "$state/lock.remaining"
        echo 'Another app is currently holding the xtables lock.' >&2
        exit 4
    fi
fi
case "$payload" in *Z2R_*) exit 90;; esac
if [ "${Z2_RESTORE_REJECT_CONNBYTES:-0}" = 1 ] &&
   printf '%s\n' "$payload" | grep -q -- '-m connbytes'; then
    echo 'connbytes match is unavailable' >&2
    exit 1
fi
[ "${Z2_RESTORE_REJECT_ALL:-0}" != 1 ] || {
    printf 'vendor parser rejected ruleset\033[31m\n' >&2
    exit 1
}
case " $* " in *' --test '*) exit 0;; esac
[ "${Z2_RESTORE_FAIL_COMMIT:-0}" != 1 ] || {
    echo 'vendor backend rejected COMMIT' >&2
    exit 1
}
if printf '%s\n' "$payload" | grep -Fx -- '-D OUTPUT -j ZAPRET2_OUT' >/dev/null; then
    rm -f "$state/anchor.out"
fi
if printf '%s\n' "$payload" | grep -Fx -- '-D INPUT -j ZAPRET2_IN' >/dev/null; then
    rm -f "$state/anchor.in"
fi
if printf '%s\n' "$payload" | grep -Fx -- '-X ZAPRET2_OUT' >/dev/null; then
    rm -f "$state/chain.out" "$state/rules.out"
fi
if printf '%s\n' "$payload" | grep -Fx -- '-X ZAPRET2_IN' >/dev/null; then
    rm -f "$state/chain.in" "$state/rules.in"
fi
if printf '%s\n' "$payload" | grep -F -- ':ZAPRET2_OUT ' >/dev/null; then
    : > "$state/chain.out"
    printf '%s\n' "$payload" | grep -F -- '-A ZAPRET2_OUT ' |
        "$Z2_MOCK_RENDER" > "$state/rules.out" || :
fi
if printf '%s\n' "$payload" | grep -F -- ':ZAPRET2_IN ' >/dev/null; then
    : > "$state/chain.in"
    printf '%s\n' "$payload" | grep -F -- '-A ZAPRET2_IN ' |
        "$Z2_MOCK_RENDER" > "$state/rules.in" || :
fi
printf '%s\n' "$payload" | grep -Fx -- '-A OUTPUT -j ZAPRET2_OUT' >/dev/null &&
    : > "$state/anchor.out"
if printf '%s\n' "$payload" | grep -Fx -- '-A INPUT -j ZAPRET2_IN' >/dev/null; then
    : > "$state/anchor.in"
fi
if [ "${Z2_CORRUPT_AFTER_COMMIT:-0}" = 1 ] &&
   printf '%s\n' "$payload" | grep -F -- ':ZAPRET2_OUT ' >/dev/null; then
    sed '$d' "$state/rules.out" > "$state/rules.out.corrupt"
    mv "$state/rules.out.corrupt" "$state/rules.out"
fi
EOF

chmod 0755 "$MOCK/iptables" "$MOCK/iptables-restore"
PATH="$MOCK:$PATH"
STATE_DIR="$STATE"
Z2_MOCK_FW="$FW"
Z2_MOCK_RENDER="$CASE/render-saved-rules"
export PATH STATE_DIR Z2_MOCK_FW Z2_MOCK_RENDER

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
[ "$(cat "$FW/restore-help.count")" = 1 ] ||
    fail "restore wait capability was probed more than once per backend"
grep -Fqx -- '--wait 5 --test --noflush' "$FW/restore.args" ||
    fail "native restore lock wait was not used for validation"
grep -Fqx -- '--wait 5 --noflush' "$FW/restore.args" ||
    fail "native restore lock wait was not used for commit"
: > "$FW/iptables.args"
z2_fw_verify_family iptables 1 || fail "published family did not verify"
[ "$(wc -l < "$FW/iptables.args")" = 1 ] ||
    fail "final family verification used more than one kernel snapshot"
grep -Fqx -- '-t mangle -S' "$FW/iptables.args" ||
    fail "final family verification did not use one complete mangle snapshot"

grep -q -- '--connbytes-mode packets --connbytes-dir original' "$FW/rules.out" ||
    fail "mock backend did not re-render published rules in kernel save order"
cp "$FW/rules.out" "$FW/rules.out.published"
printf '%s\n' '-A ZAPRET2_OUT -p tcp -m multiport --dports 80,443 -j RETURN' >> "$FW/rules.out"
if z2_fw_verify_family iptables 1; then
    fail "foreign rule inside the owned chain was accepted"
fi
case "$Z2_FW_VERIFY_DETAIL" in
    *'reason=FOREIGN_OR_UNEXPECTED_RULE'*) ;;
    *) fail "foreign rule lost its typed reason: $Z2_FW_VERIFY_DETAIL" ;;
esac
mv "$FW/rules.out.published" "$FW/rules.out"
z2_fw_verify_family iptables 1 ||
    fail "restored owned chain did not verify after foreign rule removal"

z2_fw_cleanup_is_unambiguous iptables || fail "published baseline audit failed"
z2_fw_save_audit iptables || fail "published baseline audit was not retained"
PORTS_TCP=443
z2_fw_reconcile_family iptables audited ||
    fail "audited atomic replacement failed"
[ "$(cat "$FW/restore.count")" = 4 ] ||
    fail "atomic replacement did not use one test and one commit"
for command in \
    '-D OUTPUT -j ZAPRET2_OUT' \
    '-D INPUT -j ZAPRET2_IN' \
    '-F ZAPRET2_OUT' \
    '-X ZAPRET2_OUT' \
    ':ZAPRET2_OUT - [0:0]'; do
    grep -Fqx -- "$command" "$FW/restore.payload.4" ||
        fail "atomic replacement batch omitted: $command"
done
PORTS_TCP=80,443

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
case "$Z2_FW_FALLBACK_DETAIL" in
    *'connbytes match is unavailable'*) ;;
    *) fail "connbytes fallback diagnostic was not preserved" ;;
esac
unset Z2_RESTORE_REJECT_CONNBYTES
z2_fw_cleanup_family iptables || fail "fallback cleanup failed"

rm -f "$FW"/*
Z2_RESTORE_WAIT_SUPPORTED=0
export Z2_RESTORE_WAIT_SUPPORTED
z2_fw_reset_restore_wait_capabilities
z2_fw_reconcile_family iptables || fail "legacy restore without --wait failed"
if grep -Fq -- '--wait' "$FW/restore.args"; then
    fail "legacy restore received unsupported --wait option"
fi
[ "$(cat "$FW/restore.count")" = 2 ] ||
    fail "legacy restore did not use one validation and one commit"
z2_fw_cleanup_family iptables || fail "legacy restore cleanup failed"

rm -f "$FW"/*
printf '%s\n' 2 > "$FW/lock.remaining"
z2_fw_lock_retry_pause() { :; }
z2_fw_reconcile_family iptables || fail "legacy xtables lock wait did not recover"
[ "$(cat "$FW/restore.count")" = 4 ] ||
    fail "legacy xtables lock wait did not retry only the two lock failures"
z2_fw_cleanup_family iptables || fail "legacy lock-wait cleanup failed"

rm -f "$FW"/*
printf '%s\n' 20 > "$FW/lock.remaining"
if z2_fw_reconcile_family iptables; then
    fail "exhausted legacy xtables lock wait was accepted"
fi
[ "$Z2_FW_FAILURE_CLASS" = LOCK_TIMEOUT ] ||
    fail "exhausted legacy xtables lock wait lost its failure class"
[ "$(cat "$FW/restore.count")" = 6 ] ||
    fail "legacy xtables lock wait was not bounded to five seconds"
z2_fw_family_absent iptables || fail "lock timeout left live firewall state"
unset Z2_RESTORE_WAIT_SUPPORTED
z2_fw_reset_restore_wait_capabilities

rm -f "$FW"/*
Z2_RESTORE_REJECT_ALL=1
export Z2_RESTORE_REJECT_ALL
if z2_fw_reconcile_family iptables; then
    fail "unsupported baseline ruleset was accepted"
fi
[ "$Z2_FW_FAILURE_CLASS" = RULESET_REJECTED ] ||
    fail "unsupported baseline ruleset lost its failure class"
case "$Z2_FW_ERROR_DETAIL" in
    *'test failed'*'vendor parser rejected ruleset'*)
        case "$Z2_FW_ERROR_DETAIL" in
            *"$(printf '\033')"*) fail "backend control character escaped normalization" ;;
        esac
        ;;
    *) fail "unsupported baseline ruleset diagnostic was discarded" ;;
esac
z2_fw_family_absent iptables || fail "unsupported baseline ruleset left live firewall state"
unset Z2_RESTORE_REJECT_ALL

rm -f "$FW"/*
Z2_RESTORE_FAIL_COMMIT=1
export Z2_RESTORE_FAIL_COMMIT
if z2_fw_reconcile_family iptables; then
    fail "failed COMMIT was accepted"
fi
[ "$(cat "$FW/restore.count")" = 2 ] ||
    fail "failed COMMIT incorrectly retried with a degraded topology"
z2_fw_family_absent iptables || fail "failed COMMIT left live firewall state"
[ "$Z2_FW_FAILURE_CLASS" = PUBLICATION_FAILED ] ||
    fail "failed COMMIT did not retain its failure class"
case "$Z2_FW_ERROR_DETAIL" in
    *'commit failed'*'vendor backend rejected COMMIT'*) ;;
    *) fail "failed COMMIT diagnostic was discarded" ;;
esac
unset Z2_RESTORE_FAIL_COMMIT

rm -f "$FW"/*
Z2_CORRUPT_AFTER_COMMIT=1
export Z2_CORRUPT_AFTER_COMMIT
if z2_fw_reconcile_family iptables; then
    fail "corrupted post-publication topology was accepted"
fi
[ "$Z2_FW_FAILURE_CLASS" = POSTCONDITION_FAILED ] ||
    fail "post-publication mismatch lost its failure class"
case "$Z2_FW_ERROR_DETAIL" in
    *'reason=OUT_RULE_COUNT:1'*) ;;
    *) fail "post-publication mismatch lost its typed reason: $Z2_FW_ERROR_DETAIL" ;;
esac
z2_fw_family_absent iptables ||
    fail "post-publication mismatch did not converge to absent state"
unset Z2_CORRUPT_AFTER_COMMIT

if find "$STATE" -maxdepth 1 -type f -name 'firewall-*' | grep -q .; then
    fail "firewall transaction left private temporary files"
fi

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
