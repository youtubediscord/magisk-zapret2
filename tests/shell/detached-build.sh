#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/detached-build"
MOD="$CASE/module"
STATE="$CASE/state"
MOCK="$CASE/bin"
fail() { echo "FAIL: detached-build: $*" >&2; exit 1; }
read_count() { local value=0; [ ! -f "$1" ] || IFS= read -r value < "$1"; printf '%s\n' "$value"; }
command -v chmod >/dev/null 2>&1 || chmod() { :; }

mkdir -p "$MOD/zapret2/scripts" "$MOD/zapret2/lists" "$STATE" "$MOCK"
chmod 0700 "$STATE"
cp "$ROOT/zapret2/scripts/common.sh" "$ROOT/zapret2/scripts/command-builder.sh" "$MOD/zapret2/scripts/"
sed -e '/^SCRIPT_DIR=/d' -e '/^ZAPRET_DIR=/d' -e '/^MODDIR=/d' -e '/^main "\$@"$/d' \
    "$ROOT/zapret2/scripts/zapret-start.sh" > "$MOD/zapret2/scripts/start-functions.sh"

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
name=$(basename "$0")
prefix="$Z2_FW_STATE.$name"
get() { v=0; [ ! -f "$1" ] || IFS= read -r v < "$1"; printf '%s\n' "$v"; }
inc() { v=$(get "$1"); v=$((v + 1)); printf '%s\n' "$v" > "$1"; printf '%s\n' "$v"; }
dec() { v=$(get "$1"); [ "$v" -gt 0 ] || return 1; v=$((v - 1)); printf '%s\n' "$v" > "$1"; }
args=" $* "
[ "$args" != " -t mangle -S " ] || [ "${Z2_FAIL_BASELINE_TOOL:-}" != "$name" ] || exit 1
case "$args" in
    *' -N '*)
        n=$(inc "$prefix.n")
        [ "$n" != "${Z2_FAIL_N:-0}" ] || exit 1
        chain=${args##* -N }; chain=${chain%% *}; : > "$prefix.chain.$chain"; exit 0 ;;
    *' -A ZAPRET2_PROBE '*)
        n=$(inc "$prefix.probe_append")
        [ "$n" != "${Z2_FAIL_PROBE_APPEND:-0}" ] || exit 1
        count=$(get "$prefix.probe_rules"); printf '%s\n' $((count + 1)) > "$prefix.probe_rules"; exit 0 ;;
    *' -D ZAPRET2_PROBE '*)
        n=$(inc "$prefix.probe_delete")
        [ "$n" != "${Z2_FAIL_PROBE_DELETE:-0}" ] || exit 1
        count=$(get "$prefix.probe_rules"); [ "$count" -gt 0 ] || exit 1
        printf '%s\n' $((count - 1)) > "$prefix.probe_rules"; exit 0 ;;
    *' -C ZAPRET2_PROBE '*) [ "$(get "$prefix.probe_rules")" -gt 0 ] ;;
    *' -A Z2R_'*' -j NFQUEUE '*)
        n=$(inc "$prefix.append")
        [ "$n" != "${Z2_FAIL_APPEND:-0}" ] || {
            echo "mock xtables lock contention" >&2
            exit 1
        }
        chain=${args##* -A }; chain=${chain%% *}
        inc "$prefix.rules" >/dev/null; inc "$prefix.rules.$chain" >/dev/null; exit 0 ;;
    *' -D Z2R_'*' -j NFQUEUE '*)
        inc "$prefix.rule_d" >/dev/null
        chain=${args##* -D }; chain=${chain%% *}
        dec "$prefix.rules.$chain" && dec "$prefix.rules"; exit $? ;;
    *' -C Z2R_'*' -j NFQUEUE '*) chain=${args##* -C }; chain=${chain%% *}; [ "$(get "$prefix.rules.$chain")" -gt 0 ] ;;
    *' -A Z2O_'*' -j Z2R_'*|*' -A Z2I_'*' -j Z2R_'*)
        n=$(inc "$prefix.jump_append"); [ "$n" != "${Z2_FAIL_JUMP:-0}" ] || exit 1
        parent=${args##* -A }; parent=${parent%% *}; inc "$prefix.jumps" >/dev/null; inc "$prefix.jumps.$parent" >/dev/null; exit 0 ;;
    *' -D Z2O_'*' -j Z2R_'*|*' -D Z2I_'*' -j Z2R_'*)
        parent=${args##* -D }; parent=${parent%% *}; inc "$prefix.jump_d" >/dev/null
        dec "$prefix.jumps.$parent" && dec "$prefix.jumps"; exit $? ;;
    *' -C Z2O_'*' -j Z2R_'*|*' -C Z2I_'*' -j Z2R_'*)
        parent=${args##* -C }; parent=${parent%% *}; [ "$(get "$prefix.jumps.$parent")" -gt 0 ] ;;
    *' -A OUTPUT -j Z2O_'*|*' -A INPUT -j Z2I_'*)
        n=$(inc "$prefix.anchor_append")
        [ "$n" != "${Z2_FAIL_ANCHOR:-0}" ] || exit 1
        case "$args" in *' -A OUTPUT '*) builtin=OUTPUT;; *) builtin=INPUT;; esac
        count=$(get "$prefix.anchor.$builtin"); printf '%s\n' $((count + 1)) > "$prefix.anchor.$builtin"; exit 0 ;;
    *' -D OUTPUT -j Z2O_'*|*' -D INPUT -j Z2I_'*)
        inc "$prefix.anchor_d" >/dev/null
        case "$args" in *' -D OUTPUT '*) builtin=OUTPUT;; *) builtin=INPUT;; esac
        count=$(get "$prefix.anchor.$builtin"); [ "$count" -gt 0 ] || exit 1
        printf '%s\n' $((count - 1)) > "$prefix.anchor.$builtin"; exit 0 ;;
    *' -C OUTPUT -j Z2O_'*|*' -C INPUT -j Z2I_'*)
        case "$args" in *' -C OUTPUT '*) builtin=OUTPUT;; *) builtin=INPUT;; esac
        [ "$(get "$prefix.anchor.$builtin")" -gt 0 ] ;;
    *' -X '*)
        chain=${args##* -X }; chain=${chain%% *}
        if [ "$chain" = ZAPRET2_PROBE ]; then
            inc "$prefix.probe_x" >/dev/null
            [ "$(get "$prefix.probe_rules")" = 0 ] || exit 1
            [ "${Z2_FAIL_PROBE_X:-0}" != 1 ] || exit 1
        else
            [ "$(get "$prefix.rules.$chain")" = 0 ] || exit 1
            [ "$(get "$prefix.jumps.$chain")" = 0 ] || exit 1
        fi
        rm -f "$prefix.chain.$chain"; exit 0 ;;
    *' -S Z2O_'*|*' -S Z2I_'*|*' -S Z2R_'*|*' -S ZAPRET2_PROBE '*)
        chain=${args##* -S }; chain=${chain%% *}
        [ -e "$prefix.chain.$chain" ] || exit 1
        echo "-N $chain"
        count=$(get "$prefix.jumps.$chain")
        case "$chain" in "$Z2_OUT_CHAIN") side=O;; "$Z2_IN_CHAIN") side=I;; *) side=;; esac
        while [ -n "$side" ] && [ "$count" -gt 0 ]; do echo "-A $chain -j Z2R_${Z2_FIREWALL_TAG}_${side}$count"; count=$((count - 1)); done
        exit 0 ;;
    *' -S OUTPUT '*|*' -S INPUT '*)
        case "$args" in *' -S OUTPUT '*) builtin=OUTPUT; target="$Z2_OUT_CHAIN";; *) builtin=INPUT; target="$Z2_IN_CHAIN";; esac
        count=$(get "$prefix.anchor.$builtin")
        while [ "$count" -gt 0 ]; do echo "-A $builtin -j $target"; count=$((count - 1)); done
        exit 0 ;;
    *' -F '*)
        chain=${args##* -F }; chain=${chain%% *}
        printf '%s\n' 0 > "$prefix.rules.$chain"
        printf '%s\n' 0 > "$prefix.jumps.$chain"
        exit 0 ;;
    *' -S '*)
        for file in "$prefix".chain.*; do [ -e "$file" ] || continue; echo "-N ${file##*.chain.}"; done
        count=$(get "$prefix.anchor.OUTPUT"); while [ "$count" -gt 0 ]; do echo "-A OUTPUT -j $Z2_OUT_CHAIN"; count=$((count - 1)); done
        count=$(get "$prefix.anchor.INPUT"); while [ "$count" -gt 0 ]; do echo "-A INPUT -j $Z2_IN_CHAIN"; count=$((count - 1)); done
        for parent in "$Z2_OUT_CHAIN" "$Z2_IN_CHAIN"; do
            case "$parent" in "$Z2_OUT_CHAIN") side=O;; *) side=I;; esac
            count=$(get "$prefix.jumps.$parent")
            while [ "$count" -gt 0 ]; do echo "-A $parent -j Z2R_${Z2_FIREWALL_TAG}_${side}$count"; count=$((count - 1)); done
        done
        exit 0 ;;
    *) exit 0 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"

cat > "$MOCK/sync" <<'EOF'
#!/bin/sh
count=0; [ ! -f "$Z2_SYNC_COUNT" ] || IFS= read -r count < "$Z2_SYNC_COUNT"
count=$((count + 1)); printf '%s\n' "$count" > "$Z2_SYNC_COUNT"
[ "$count" != "${Z2_FAIL_SYNC:-0}" ]
EOF

cat > "$MOCK/mv" <<'EOF'
#!/bin/sh
count=0; [ ! -f "$Z2_MV_COUNT" ] || IFS= read -r count < "$Z2_MV_COUNT"
count=$((count + 1)); printf '%s\n' "$count" > "$Z2_MV_COUNT"
[ "$count" != "${Z2_FAIL_MV:-0}" ] || exit 1
exec /bin/mv "$@"
EOF
cat > "$MOCK/stat" <<'EOF'
#!/bin/sh
case "$1:$2" in
    -c:%u) echo 0 ;;
    -c:%a) if [ -d "$3" ]; then echo 700; else echo 600; fi ;;
    -c:%h) echo 1 ;;
    *) exit 1 ;;
esac
EOF
chmod 0755 "$MOCK/iptables" "$MOCK/ip6tables" "$MOCK/sync" "$MOCK/mv" "$MOCK/stat"

SCRIPT_DIR="$MOD/zapret2/scripts"
ZAPRET_DIR="$MOD/zapret2"
MODDIR="$MOD"
STATE_DIR="$STATE"
PATH="$MOCK:$PATH"
Z2_FW_STATE="$CASE/fw"
Z2_SYNC_COUNT="$CASE/sync.count"
Z2_MV_COUNT="$CASE/mv.count"
export SCRIPT_DIR ZAPRET_DIR MODDIR STATE_DIR PATH Z2_FW_STATE Z2_SYNC_COUNT Z2_MV_COUNT
. "$MOD/zapret2/scripts/start-functions.sh"
Z2_TEST_BOOT_ID=11111111-1111-1111-1111-111111111111
read_current_boot_id() { is_valid_boot_id "$Z2_TEST_BOOT_ID" || return 1; CURRENT_BOOT_ID="$Z2_TEST_BOOT_ID"; }
scan_exact_owned_nfqws() { OWNED_SCAN_PIDS="${Z2_MOCK_OWNED_PROCESS:-}"; printf '%s\n' "$OWNED_SCAN_PIDS"; }
QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443,3478,5349,19302; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
BUILD_CONNBYTES=1; BUILD_MULTIPORT=1; BUILD_MARK=1
new_lifecycle_token() { printf '%s\n' 'AbCdEf1234-generation'; }
prepare_new_firewall_identity || fail "could not prepare schema-v7 firewall identity"
Z2_OUT_CHAIN="$ZAPRET2_OUT"; Z2_IN_CHAIN="$ZAPRET2_IN"
Z2_FIREWALL_TAG="$FIREWALL_TAG"
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN Z2_OUT_CHAIN Z2_IN_CHAIN Z2_FIREWALL_TAG

reset_all() {
    set +f
    rm -f "$CASE"/fw.* "$STATE"/build-track.* "$STATE"/probe-track.* "$Z2_SYNC_COUNT" "$Z2_MV_COUNT"
    rm -rf "$LIFECYCLE_LOCK"
    set -f
    unset Z2_FAIL_N Z2_FAIL_APPEND Z2_FAIL_JUMP Z2_FAIL_ANCHOR Z2_FAIL_SYNC Z2_FAIL_MV Z2_FAIL_PROBE_APPEND Z2_FAIL_PROBE_DELETE Z2_FAIL_PROBE_X Z2_MOCK_OWNED_PROCESS Z2_FAIL_BASELINE_TOOL
    Z2_TEST_BOOT_ID=11111111-1111-1111-1111-111111111111
    BUILD_TRACK_AMBIGUOUS=0
    LOCK_HELD=0; LOCK_OWNER_PID=""; LOCK_OWNER_START=""; LOCK_OWNER_TOKEN=""
}

assert_clean() {
    local tool="$1"
    [ -z "$(find "$CASE" -maxdepth 1 -name "fw.$tool.chain.*" -print -quit)" ] || fail "$tool left a chain"
    [ "$(read_count "$CASE/fw.$tool.rules")" = 0 ] || fail "$tool left owned rules"
    [ "$(read_count "$CASE/fw.$tool.anchor.OUTPUT")" = 0 ] || fail "$tool left OUTPUT anchor"
    [ "$(read_count "$CASE/fw.$tool.anchor.INPUT")" = 0 ] || fail "$tool left INPUT anchor"
    build_track_for_tool "$tool"; [ ! -e "$BUILD_TRACK_FILE" ] || fail "$tool left a completed cleanup journal"
}

# A preset may be genuinely single-protocol. An empty opposite protocol is a
# no-op and must not create a wildcard or malformed firewall rule.
reset_all
PORTS_UDP=""
validate_port_list "$PORTS_UDP" || fail "empty optional UDP port set was rejected"
build_detached_family iptables || fail "TCP-only detached build failed"
[ "$(read_count "$CASE/fw.iptables.rules")" = 2 ] || fail "TCP-only build created extra rules"
cleanup_tracked_family iptables || fail "TCP-only cleanup failed"
assert_clean iptables
PORTS_TCP=""
PORTS_UDP=443
validate_port_list "$PORTS_TCP" || fail "empty optional TCP port set was rejected"
build_detached_family iptables || fail "UDP-only detached build failed"
[ "$(read_count "$CASE/fw.iptables.rules")" = 2 ] || fail "UDP-only build created extra rules"
cleanup_tracked_family iptables || fail "UDP-only cleanup failed"
assert_clean iptables
PORTS_TCP=80,443
PORTS_UDP=443,3478,5349,19302

# Magisk removal is authorized by its durable remove marker, not by possibly
# damaged lifecycle journals. It removes only exact module-created jumps and
# the reserved chain namespace; no broad table flush is allowed.
reset_all
build_detached_family iptables || fail "Magisk namespace purge fixture build failed"
commit_family iptables || fail "Magisk namespace purge fixture commit failed"
purge_zapret2_namespace iptables || fail "Magisk namespace purge failed"
zapret2_namespace_present iptables && fail "Magisk namespace purge left owned objects"
[ "$(read_count "$CASE/fw.iptables.anchor.OUTPUT")" = 0 ] || fail "Magisk purge left OUTPUT anchor"
[ "$(read_count "$CASE/fw.iptables.anchor.INPUT")" = 0 ] || fail "Magisk purge left INPUT anchor"

if [ "${Z2_NEW_ONLY:-0}" != 1 ]; then
for tool in iptables ip6tables; do
    position=1
    while [ "$position" -le 6 ]; do
        reset_all; Z2_FAIL_N="$position"; export Z2_FAIL_N
        build_detached_family "$tool" && fail "$tool -N $position unexpectedly succeeded"
        assert_clean "$tool"
        position=$((position + 1))
    done

    position=1
    while [ "$position" -le 4 ]; do
        reset_all; Z2_FAIL_JUMP="$position"; export Z2_FAIL_JUMP
        build_detached_family "$tool" && fail "$tool per-rule jump -A $position unexpectedly succeeded"
        assert_clean "$tool"
        position=$((position + 1))
    done

    position=1
    while [ "$position" -le 4 ]; do
        reset_all; Z2_FAIL_APPEND="$position"; export Z2_FAIL_APPEND
        build_detached_family "$tool" && fail "$tool rule -A $position unexpectedly succeeded"
        [ "$FIREWALL_FAILURE_STAGE" = BUILD_RULE ] ||
            fail "$tool rule -A $position lost its typed mutation stage"
        case "$FIREWALL_FAILURE_DETAIL" in
            *"mock xtables lock contention"*) ;;
            *) fail "$tool rule -A $position suppressed the firewall diagnostic" ;;
        esac
        assert_clean "$tool"
        position=$((position + 1))
    done

    position=1
    while [ "$position" -le 2 ]; do
        reset_all
        build_detached_family "$tool" || fail "$tool anchor fixture build failed"
        Z2_FAIL_ANCHOR="$position"; export Z2_FAIL_ANCHOR
        commit_family "$tool" && fail "$tool anchor -A $position unexpectedly succeeded"
        cleanup_tracked_family "$tool" || fail "$tool anchor failure rollback failed"
        cleanup_tracked_family "$tool" || fail "$tool second anchor cleanup was not idempotent"
        assert_clean "$tool"
        position=$((position + 1))
    done
done

# Non-multiport expansion produces twelve separately journaled rules and remains
# exactly reversible.  A second cleanup must be a no-op.
reset_all
BUILD_MULTIPORT=0
build_detached_family iptables || fail "non-multiport build failed"
[ "$(read_count "$CASE/fw.iptables.rules")" = 12 ] || fail "non-multiport expansion did not create 12 rules"
cleanup_tracked_family iptables || fail "non-multiport cleanup failed"
deletes=$(read_count "$CASE/fw.iptables.rule_d")
[ "$deletes" = 12 ] || fail "non-multiport cleanup deleted $deletes rules"
cleanup_tracked_family iptables || fail "non-multiport retry failed"
[ "$(read_count "$CASE/fw.iptables.rule_d")" = "$deletes" ] || fail "cleanup replayed consumed non-multiport rules"
assert_clean iptables
BUILD_MULTIPORT=1

# Successful ownership handoff first durably consumes every record, so a
# resurrected/late cleanup journal cannot replay committed rules or anchors.
reset_all
build_detached_family iptables || fail "committed-retire fixture build failed"
commit_family iptables || fail "committed-retire fixture commit failed"
retire_committed_build_track iptables 1 || fail "committed journal retirement failed"
rule_deletes=$(read_count "$CASE/fw.iptables.rule_d")
anchor_deletes=$(read_count "$CASE/fw.iptables.anchor_d")
cleanup_tracked_family iptables || fail "retired journal cleanup was not a no-op"
[ "$(read_count "$CASE/fw.iptables.rule_d")" = "$rule_deletes" ] || fail "retired journal replayed a rule"
[ "$(read_count "$CASE/fw.iptables.anchor_d")" = "$anchor_deletes" ] || fail "retired journal replayed an anchor"
[ "$(read_count "$CASE/fw.iptables.rules")" = 4 ] || fail "committed retirement removed owned rules"
[ "$(read_count "$CASE/fw.iptables.jumps")" = 4 ] || fail "committed retirement removed per-rule jumps"
[ "$(read_count "$CASE/fw.iptables.anchor.OUTPUT")" = 1 ] || fail "committed retirement removed OUTPUT anchor"
[ "$(read_count "$CASE/fw.iptables.anchor.INPUT")" = 1 ] || fail "committed retirement removed INPUT anchor"

# A concurrent identical rule is appended after the owned rule.  Exact -D removes
# the earlier owned instance once; chain removal then fails closed.  The consuming
# record prevents a retry from deleting the foreign duplicate.
reset_all
build_detached_family iptables || fail "foreign-rule fixture build failed"
owner_rule_chain "$ZAPRET2_OUT" 1 || fail "foreign-rule subchain identity failed"
owner_rule_once iptables -A "$OWNER_RULE_CHAIN" tcp out "$PORTS_TCP" "$PKT_OUT" original "$QNUM" "$DESYNC_MARK" 1 1 1 || fail "foreign identical rule insert failed"
cleanup_tracked_family iptables && fail "foreign identical rule was silently accepted"
foreign_rules=$(read_count "$CASE/fw.iptables.rules")
foreign_deletes=$(read_count "$CASE/fw.iptables.rule_d")
[ "$foreign_rules" = 1 ] || fail "foreign identical rule was not preserved"
cleanup_tracked_family iptables && fail "consuming foreign-rule journal unexpectedly replayed"
[ "$(read_count "$CASE/fw.iptables.rules")" = 1 ] || fail "retry deleted foreign identical rule"
[ "$(read_count "$CASE/fw.iptables.rule_d")" = "$foreign_deletes" ] || fail "retry issued another foreign-rule deletion"

# The same invariant applies to an identical concurrent anchor.
reset_all
build_detached_family iptables || fail "foreign-anchor fixture build failed"
commit_family iptables || fail "foreign-anchor fixture commit failed"
iptables -t mangle -A OUTPUT -j "$ZAPRET2_OUT" || fail "foreign identical anchor insert failed"
cleanup_tracked_family iptables && fail "foreign identical anchor was silently accepted"
anchor_deletes=$(read_count "$CASE/fw.iptables.anchor_d")
[ "$(read_count "$CASE/fw.iptables.anchor.OUTPUT")" = 1 ] || fail "foreign identical anchor was not preserved"
cleanup_tracked_family iptables && fail "consuming foreign-anchor journal unexpectedly replayed"
[ "$(read_count "$CASE/fw.iptables.anchor.OUTPUT")" = 1 ] || fail "retry deleted foreign identical anchor"
[ "$(read_count "$CASE/fw.iptables.anchor_d")" = "$anchor_deletes" ] || fail "retry issued another foreign-anchor deletion"

# Durable-write failures before publication prevent mutation.  Failures while
# publishing applied leave pending/applied ambiguity evidence and never replay it.
for fail_at in 3 4 6; do
    reset_all; Z2_FAIL_SYNC="$fail_at"; export Z2_FAIL_SYNC
    build_detached_family iptables && fail "sync fault $fail_at unexpectedly succeeded"
    build_track_for_tool iptables; [ -e "$BUILD_TRACK_FILE" ] || fail "sync fault $fail_at lost its journal"
    cleanup_tracked_family iptables && fail "sync fault $fail_at ambiguity was replayed"
    if [ "$fail_at" -le 4 ]; then [ ! -e "$CASE/fw.iptables.chain.$ZAPRET2_OUT" ] || fail "sync fault $fail_at mutated before durable pending"; fi
done
for fail_at in 2 3; do
    reset_all; Z2_FAIL_MV="$fail_at"; export Z2_FAIL_MV
    build_detached_family iptables && fail "journal rename fault $fail_at unexpectedly succeeded"
    build_track_for_tool iptables; [ -e "$BUILD_TRACK_FILE" ] || fail "rename fault $fail_at lost its base journal"
    cleanup_tracked_family iptables && fail "rename fault $fail_at ambiguity was replayed"
    if [ "$fail_at" = 2 ]; then [ ! -e "$CASE/fw.iptables.chain.$ZAPRET2_OUT" ] || fail "rename fault mutated before pending publication"; fi
done

# Explicitly model process interruption at both ambiguity barriers.  Neither a
# pending mutation nor an already-consuming cleanup record may be executed.
reset_all
begin_tracked_family iptables || fail "pending interruption setup failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "pending interruption record failed"
cleanup_tracked_family iptables && fail "pending interruption was replayed"
[ ! -e "$CASE/fw.iptables.chain.$ZAPRET2_OUT" ] || fail "pending interruption created a chain"

reset_all
build_detached_family iptables || fail "consuming interruption setup failed"
last_id=$(awk -F '|' '$1 == "record" { id=$2 } END { print id }' "$BUILD_TRACK_FILE")
build_track_transition "$last_id" applied consuming || fail "consuming interruption transition failed"
before=$(read_count "$CASE/fw.iptables.rule_d")
cleanup_tracked_family iptables && fail "consuming interruption was replayed"
[ "$(read_count "$CASE/fw.iptables.rule_d")" = "$before" ] || fail "consuming interruption issued a delete"
fi

arm_lifecycle_lock() {
    local start token=test-track-lock
    start=$(proc_starttime "$$") || fail "cannot read test owner starttime"
    mkdir -p "$LIFECYCLE_LOCK"
    printf 'pid=%s\nstarttime=%s\ntoken=%s\n' "$$" "$start" "$token" > "$LIFECYCLE_LOCK_OWNER"
    LOCK_HELD=1; LOCK_OWNER_PID=$$; LOCK_OWNER_START=$start; LOCK_OWNER_TOKEN=$token
}

drop_lifecycle_lock() {
    rm -rf "$LIFECYCLE_LOCK"
    LOCK_HELD=0; LOCK_OWNER_PID=""; LOCK_OWNER_START=""; LOCK_OWNER_TOKEN=""
}

mock_reboot_clean_firewall() {
    set +f; rm -f "$CASE"/fw.*; set -f
    Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
}

assert_different_boot_retires() {
    local journal="$1"
    mock_reboot_clean_firewall
    for scope in lifecycle full-rollback install uninstall; do
        audit_recovery_artifacts "$scope" || fail "different-boot clean $scope preflight failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
        [ "$RECOVERY_ARTIFACT_CLASS" = clean ] || fail "different-boot $scope preflight was not clean"
    done
    [ -e "$journal" ] || fail "pre-lock audit retired a journal without serialization"
    arm_lifecycle_lock
    audit_recovery_artifacts lifecycle || fail "locked different-boot journal recovery failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
    [ ! -e "$journal" ] && [ ! -L "$journal" ] || fail "locked different-boot recovery did not retire journal"
    drop_lifecycle_lock
}

# Installation sources common.sh in a fresh shell after the previous module has
# been removed. A valid cross-boot journal must authenticate its own dynamic
# firewall generation instead of depending on globals that only existed in the
# interrupted zapret-start process.
reset_all
begin_tracked_family iptables || fail "fresh-installer recovery fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "fresh-installer recovery journal failed"
fresh_journal="$BUILD_TRACK_FILE"
FIREWALL_TAG=""
ZAPRET2_OUT=ZAPRET2_OUT
ZAPRET2_IN=ZAPRET2_IN
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN
assert_different_boot_retires "$fresh_journal"
FIREWALL_TAG="$Z2_FIREWALL_TAG"
ZAPRET2_OUT="$Z2_OUT_CHAIN"
ZAPRET2_IN="$Z2_IN_CHAIN"
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN

# The self-contained identity is still fail-closed: mixed generations cannot
# authenticate, and a fresh installer must detect dynamic residue without
# knowing the interrupted process's tag.
reset_all
begin_tracked_family iptables || fail "mixed-generation fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "mixed-generation output record failed"
build_track_add_pending "chain|$ZAPRET2_IN" || fail "mixed-generation input record failed"
mixed_journal="$BUILD_TRACK_FILE"
sed "s/$ZAPRET2_IN/Z2I_ZzYyXxWwVv/" "$mixed_journal" > "$mixed_journal.bad"
mv "$mixed_journal.bad" "$mixed_journal"
chmod 0600 "$mixed_journal"
Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
audit_recovery_artifacts install && fail "mixed firewall generations authenticated"
[ -e "$mixed_journal" ] || fail "mixed-generation evidence was deleted"

reset_all
begin_tracked_family iptables || fail "fresh-installer residue fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "fresh-installer residue journal failed"
residue_journal="$BUILD_TRACK_FILE"
iptables -t mangle -N "$ZAPRET2_OUT" || fail "fresh-installer residue chain failed"
FIREWALL_TAG=""
ZAPRET2_OUT=ZAPRET2_OUT
ZAPRET2_IN=ZAPRET2_IN
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN
Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
arm_lifecycle_lock
audit_recovery_artifacts install && fail "fresh installer ignored dynamic firewall residue"
[ -e "$residue_journal" ] || fail "residue journal was deleted"
drop_lifecycle_lock
FIREWALL_TAG="$Z2_FIREWALL_TAG"
ZAPRET2_OUT="$Z2_OUT_CHAIN"
ZAPRET2_IN="$Z2_IN_CHAIN"
export FIREWALL_TAG ZAPRET2_OUT ZAPRET2_IN

# Every non-track wildcard in the shared recovery inventory must expand even
# when zapret-start has disabled globbing. Enumeration must restore that caller
# option on success, and every scope must observe the artifact.
for artifact in \
    "$UNINSTALL_TOMBSTONE.tmp.case" "$STATE/.uninstall.tombstone.case" \
    "$LIFECYCLE_LOCK_REAPER.case" "$LIFECYCLE_LOCK_REAPER_RECOVERY.case" \
    "$LIFECYCLE_LOCK_QUARANTINE.case" "$STATE/lifecycle.lock.candidate.case" "$STATE/.lifecycle.lock.case" \
    "$FULL_ROLLBACK_TRANSACTION.tmp.case" "$STATE/.full-rollback.transaction.case" \
    "$FULL_ROLLBACK_META.tmp.case" "$STATE/.full-rollback.meta.case" \
    "$FULL_ROLLBACK_HOSTS_BACKUP.tmp.case" "$STATE/.hosts.rollback.backup.case"; do
    reset_all
    : > "$artifact"; chmod 0600 "$artifact"
    case "$-" in *f*) ;; *) fail "noglob unexpectedly disabled before enumerating $artifact";; esac
    enumerate_recovery_artifacts > "$CASE/enumerated.out" || fail "enumeration failed for $artifact"
    case "$-" in *f*) ;; *) fail "enumeration leaked a noglob option change for $artifact";; esac
    grep -Fxq "$artifact" "$CASE/enumerated.out" || fail "wildcard recovery artifact was not enumerated: $artifact"
    for scope in lifecycle full-rollback install uninstall; do
        audit_recovery_artifacts "$scope" && fail "$scope ignored wildcard recovery artifact: $artifact"
        case "$-" in *f*) ;; *) fail "$scope audit leaked a noglob option change";; esac
    done
    rm -f "$artifact" "$CASE/enumerated.out"
done

# Creator PID/starttime plus boot identity bind every journal. A live creator
# remains a hard gate. A same-boot dead creator is observed twice and may be
# retired only after the same clean proof and exact lifecycle-lock ownership.
reset_all
begin_tracked_family iptables || fail "identity fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "identity fixture pending failed"
journal="$BUILD_TRACK_FILE"
audit_recovery_artifacts lifecycle && fail "same-boot pending journal was accepted"
old_journal="$STATE/build-track.ipv4.99999999"
sed 's/^creator_pid=.*/creator_pid=99999999/' "$journal" > "$old_journal"
chmod 0600 "$old_journal"
rm -f "$journal"
audit_recovery_artifacts lifecycle || fail "same-boot dead creator preflight failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
[ -e "$old_journal" ] || fail "pre-lock same-boot audit retired a journal"
arm_lifecycle_lock
audit_recovery_artifacts lifecycle || fail "locked same-boot dead creator recovery failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
[ ! -e "$old_journal" ] && [ ! -L "$old_journal" ] ||
    fail "locked same-boot dead creator recovery did not retire journal"
drop_lifecycle_lock

# The validator accepts the exact journal grammar emitted for a ranged port
# list. This fixture mirrors the device report that exposed a reader/writer
# contract mismatch around 80:65535.
reset_all
device_journal="$STATE/build-track.ipv4.99999995"
cat > "$device_journal" <<EOF
version=2
mode=build
tool=iptables
module_dir=$MOD
creator_pid=99999995
creator_starttime=2374
boot_id=$Z2_TEST_BOOT_ID
record|1|applied|chain|Z2O_e4755ceb92
record|2|applied|chain|Z2I_e4755ceb92
record|3|applied|chain|Z2R_e4755ceb92_O1
record|4|applied|anchor|Z2O_e4755ceb92|Z2R_e4755ceb92_O1
record|5|pending|rule|Z2R_e4755ceb92_O1|tcp|out|80:65535|20|original|200|0x40000000|1|1|1
EOF
chmod 0600 "$device_journal"
validate_track_journal_identity "$device_journal" ||
    fail "ranged-port device journal was rejected"
[ "$TRACK_FIREWALL_TAG" = e4755ceb92 ] && [ "$TRACK_JOURNAL_TOOL" = iptables ] ||
    fail "ranged-port device journal identity was parsed incorrectly"
rm -f "$device_journal"

# Terminal build journals do not need a family baseline: zero records or only
# consumed records prove that no unfinished mutation remains. This is the
# recovery regression for both build-track.ipv4 and build-track.ipv6 on devices
# where a frontend exists but its kernel family query fails.
for terminal_tool in iptables ip6tables; do
    reset_all
    begin_tracked_family "$terminal_tool" || fail "$terminal_tool terminal fixture begin failed"
    terminal_source="$BUILD_TRACK_FILE"
    case "$terminal_tool" in
        iptables) terminal_journal="$STATE/build-track.ipv4.99999998" ;;
        ip6tables) terminal_journal="$STATE/build-track.ipv6.99999998" ;;
    esac
    sed 's/^creator_pid=.*/creator_pid=99999998/' "$terminal_source" > "$terminal_journal"
    chmod 0600 "$terminal_journal"
    rm -f "$terminal_source"
    Z2_FAIL_BASELINE_TOOL="$terminal_tool"; export Z2_FAIL_BASELINE_TOOL
    Z2_MOCK_OWNED_PROCESS=4242; export Z2_MOCK_OWNED_PROCESS
    audit_recovery_artifacts install ||
        fail "$terminal_tool terminal same-boot preflight failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
    [ -e "$terminal_journal" ] || fail "$terminal_tool terminal pre-lock audit deleted evidence"
    arm_lifecycle_lock
    audit_recovery_artifacts install ||
        fail "$terminal_tool terminal locked recovery failed: $RECOVERY_ARTIFACT_DIAGNOSTIC"
    [ ! -e "$terminal_journal" ] && [ ! -L "$terminal_journal" ] ||
        fail "$terminal_tool terminal journal was not retired"
    drop_lifecycle_lock
done

# Once exact owner/process/topology verification has made owner.meta
# authoritative, an unfinished journal from its dead start process is obsolete.
# The active nfqws process must no longer make stale-track recovery self-blocking.
(
    reset_all
    begin_tracked_family iptables || fail "published-owner track fixture begin failed"
    BUILD_TRACK_TOOL=iptables
    build_track_add_pending "chain|$ZAPRET2_OUT" ||
        fail "published-owner track fixture pending record failed"
    published_source="$BUILD_TRACK_FILE"
    published_journal="$STATE/build-track.ipv4.99999996"
    sed 's/^creator_pid=.*/creator_pid=99999996/' "$published_source" > "$published_journal"
    chmod 0600 "$published_journal"
    rm -f "$published_source"
    Z2_MOCK_OWNED_PROCESS=4242; export Z2_MOCK_OWNED_PROCESS
    authenticated_published_owner_generation_healthy() {
        OWNER_STATE_FIREWALL_TAG="$Z2_FIREWALL_TAG"
        return 0
    }
    arm_lifecycle_lock
    audit_recovery_artifacts install ||
        fail "authenticated published owner did not supersede its stale track: $RECOVERY_ARTIFACT_DIAGNOSTIC"
    [ ! -e "$published_journal" ] && [ ! -L "$published_journal" ] ||
        fail "authenticated published owner left its stale track"
    drop_lifecycle_lock
)

for corruption in tamper missing; do
    reset_all
    begin_tracked_family iptables || fail "$corruption boot-id fixture begin failed"
    BUILD_TRACK_TOOL=iptables
    build_track_add_pending "chain|$ZAPRET2_OUT" || fail "$corruption boot-id pending failed"
    journal="$BUILD_TRACK_FILE"
    if [ "$corruption" = tamper ]; then sed 's/^boot_id=.*/boot_id=not-a-boot-id/' "$journal" > "$journal.bad"
    else sed '/^boot_id=/d' "$journal" > "$journal.bad"; fi
    mv "$journal.bad" "$journal"; chmod 0600 "$journal"
    Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
    audit_recovery_artifacts lifecycle && fail "$corruption boot identity was accepted"
    [ -e "$journal" ] || fail "$corruption boot journal was deleted"
done

reset_all
begin_tracked_family iptables || fail "existing-chain fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "existing-chain pending failed"
journal="$BUILD_TRACK_FILE"
Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
iptables -t mangle -N "$ZAPRET2_OUT" || fail "existing-chain fixture mutation failed"
arm_lifecycle_lock
audit_recovery_artifacts lifecycle && fail "different-boot journal retired over an existing chain"
[ -e "$journal" ] || fail "existing-chain journal was deleted"
drop_lifecycle_lock

reset_all
begin_tracked_family iptables || fail "existing-process fixture begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_OUT" || fail "existing-process pending failed"
journal="$BUILD_TRACK_FILE"
Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
Z2_MOCK_OWNED_PROCESS=4242; export Z2_MOCK_OWNED_PROCESS
arm_lifecycle_lock
audit_recovery_artifacts lifecycle && fail "different-boot journal retired over an owned process"
[ -e "$journal" ] || fail "existing-process journal was deleted"
drop_lifecycle_lock

# A different-boot clean proof begins with a successful whole mangle-table
# baseline for every available family.  A failed IPv4 or IPv6 -S query is
# uncertainty, never evidence of absence, in every recovery scope.
for baseline_tool in iptables ip6tables; do
    reset_all
    begin_tracked_family "$baseline_tool" || fail "$baseline_tool baseline fixture begin failed"
    BUILD_TRACK_TOOL="$baseline_tool"
    build_track_add_pending "chain|$ZAPRET2_OUT" || fail "$baseline_tool baseline fixture pending failed"
    journal="$BUILD_TRACK_FILE"
    Z2_TEST_BOOT_ID=22222222-2222-2222-2222-222222222222
    Z2_FAIL_BASELINE_TOOL="$baseline_tool"; export Z2_FAIL_BASELINE_TOOL
    arm_lifecycle_lock
    for scope in lifecycle full-rollback install uninstall; do
        audit_recovery_artifacts "$scope" && fail "$baseline_tool failed baseline was accepted in $scope"
        [ "$RECOVERY_ARTIFACT_CLASS" = unsafe ] || fail "$baseline_tool failed baseline class changed in $scope"
        [ -e "$journal" ] || fail "$baseline_tool failed baseline retired journal in $scope"
    done
    drop_lifecycle_lock
done

# A complete capability probe uses the same durable state machine for -N, all
# five -A/-D pairs, and -X, and retires only an all-consumed journal.
reset_all
probe_family iptables || fail "journaled capability probe failed"
[ "$PROBE_NFQUEUE:$PROBE_QUEUE_BYPASS:$PROBE_CONNBYTES:$PROBE_MULTIPORT:$PROBE_MARK" = 1:1:1:1:1 ] || fail "probe capability result changed"
probe_track_for_tool iptables
[ ! -e "$BUILD_TRACK_FILE" ] || fail "successful probe left its journal"
[ ! -e "$CASE/fw.iptables.chain.ZAPRET2_PROBE" ] || fail "successful probe left its chain"
[ "$(read_count "$CASE/fw.iptables.probe_rules")" = 0 ] || fail "successful probe left a rule"

# Model SIGKILL immediately after every probe append and after every exact
# delete. The fixture creator is still live, so re-entry on the same boot
# refuses; a mock reboot clears netfilter and permits serialized retirement.
for kind in queue_bypass queue connbytes multiport mark; do
    reset_all
    begin_probe_track iptables || fail "$kind pre-append interruption begin failed"
    BUILD_TRACK_TOOL=iptables
    tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "$kind pre-append interruption chain failed"
    build_track_add_pending "probe_rule|$kind|$QNUM|$PKT_OUT|$DESYNC_MARK" || fail "$kind pre-append pending record failed"
    journal="$BUILD_TRACK_FILE"
    audit_recovery_artifacts lifecycle && fail "$kind pre-append interruption passed on same boot"
    assert_different_boot_retires "$journal"

    reset_all
    begin_probe_track iptables || fail "$kind append interruption begin failed"
    BUILD_TRACK_TOOL=iptables
    tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "$kind append interruption chain failed"
    tracked_probe_append iptables "$kind" || fail "$kind append interruption mutation failed"
    journal="$BUILD_TRACK_FILE"
    audit_recovery_artifacts lifecycle && fail "$kind append interruption passed on same boot"
    assert_different_boot_retires "$journal"

    reset_all
    begin_probe_track iptables || fail "$kind delete interruption begin failed"
    BUILD_TRACK_TOOL=iptables
    tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "$kind delete interruption chain failed"
    tracked_probe_append iptables "$kind" || fail "$kind delete interruption append failed"
    id="$PROBE_RULE_RECORD_ID"
    build_track_transition "$id" applied consuming || fail "$kind delete interruption transition failed"
    probe_rule_exec iptables -D "$kind" "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || fail "$kind interrupted exact delete failed"
    journal="$BUILD_TRACK_FILE"
    audit_recovery_artifacts lifecycle && fail "$kind delete interruption passed on same boot"
    assert_different_boot_retires "$journal"
done

# Cover the chain boundaries before -N and around exact -X as well.
reset_all
begin_probe_track iptables || fail "pre-create probe interruption begin failed"
BUILD_TRACK_TOOL=iptables
build_track_add_pending "chain|$ZAPRET2_PROBE" || fail "pre-create probe pending failed"
journal="$BUILD_TRACK_FILE"
audit_recovery_artifacts lifecycle && fail "pre-create probe interruption passed on same boot"
assert_different_boot_retires "$journal"

reset_all
begin_probe_track iptables || fail "pre-delete probe chain begin failed"
BUILD_TRACK_TOOL=iptables
tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "pre-delete probe chain create failed"
id="$BUILD_TRACK_RECORD_ID"
build_track_transition "$id" applied consuming || fail "pre-delete probe chain transition failed"
journal="$BUILD_TRACK_FILE"
audit_recovery_artifacts lifecycle && fail "pre-delete probe chain interruption passed on same boot"
assert_different_boot_retires "$journal"

reset_all
begin_probe_track iptables || fail "post-delete probe chain begin failed"
BUILD_TRACK_TOOL=iptables
tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "post-delete probe chain create failed"
id="$BUILD_TRACK_RECORD_ID"
build_track_transition "$id" applied consuming || fail "post-delete probe chain transition failed"
iptables -t mangle -X "$ZAPRET2_PROBE" || fail "post-delete probe chain exact delete failed"
journal="$BUILD_TRACK_FILE"
audit_recovery_artifacts lifecycle && fail "post-delete probe chain interruption passed on same boot"
assert_different_boot_retires "$journal"

# Identical foreign probe state is never replayed: one exact delete consumes
# the owned earlier instance, observes the duplicate, and freezes at consuming.
reset_all
begin_probe_track iptables || fail "foreign probe fixture begin failed"
BUILD_TRACK_TOOL=iptables
tracked_create_chain iptables "$ZAPRET2_PROBE" || fail "foreign probe chain failed"
tracked_probe_append iptables mark || fail "foreign probe append failed"
probe_rule_exec iptables -A mark "$QNUM" "$PKT_OUT" "$DESYNC_MARK" || fail "foreign identical probe append failed"
cleanup_probe_track iptables && fail "foreign identical probe rule was silently deleted"
probe_deletes=$(read_count "$CASE/fw.iptables.probe_delete")
[ "$(read_count "$CASE/fw.iptables.probe_rules")" = 1 ] || fail "foreign identical probe rule did not survive"
cleanup_probe_track iptables && fail "consuming probe journal replayed"
[ "$(read_count "$CASE/fw.iptables.probe_delete")" = "$probe_deletes" ] || fail "repeated probe cleanup issued another delete"
[ "$(read_count "$CASE/fw.iptables.probe_rules")" = 1 ] || fail "repeated probe cleanup deleted foreign state"

echo "Detached build shell tests passed"
