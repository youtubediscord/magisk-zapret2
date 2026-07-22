#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/owner-state-v7"
MOD="$CASE/module"
STATE="$CASE/state"
MOCK="$CASE/bin"
BOOT_A=11111111-1111-1111-1111-111111111111
BOOT_B=22222222-2222-2222-2222-222222222222

fail() { echo "FAIL: owner-state-v7: $*" >&2; exit 1; }
chmod() { :; }
sync() { :; }
sha256sum() { cat >/dev/null; echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; }
mkdir -p "$MOD/zapret2/scripts" "$STATE" "$MOCK"
chmod 0700 "$STATE"
cp "$ROOT/zapret2/scripts/common.sh" "$MOD/zapret2/scripts/common.sh"
: > "$MOD/zapret2/nfqws2"
cat > "$MOD/zapret2/install-generation.meta" <<EOF
version=1
module_dir=$MOD
generation=owner-v7-install
archive_sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
EOF
chmod 0600 "$MOD/zapret2/install-generation.meta"
cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 0755 "$MOCK/iptables"

export STATE_DIR="$STATE" SCRIPT_DIR="$MOD/zapret2/scripts" ZAPRET_DIR="$MOD/zapret2" MODDIR="$MOD" PATH="$MOCK:$PATH"
. "$SCRIPT_DIR/common.sh"
state_file_is_secure() { [ -f "$1" ]; }
path_mode_is_0600() { :; }
path_uid_is_root() { :; }
path_nlink_is_one() { :; }
state_file_target_is_safe() { [ ! -L "$1" ]; }
Z2_TEST_CURRENT_BOOT="$BOOT_A"
Z2_TEST_PROCESS=dead
Z2_TEST_SCAN=clean
Z2_TEST_FIREWALL=absent
Z2_TEST_LOCKED=0
read_current_boot_id() {
    [ "${Z2_TEST_BOOT_QUERY:-ok}" = ok ] || return 1
    CURRENT_BOOT_ID="$Z2_TEST_CURRENT_BOOT"
}
verify_nfqws_pid() { [ "$Z2_TEST_PROCESS" = live ]; }
scan_exact_owned_nfqws() {
    OWNED_SCAN_PIDS=""
    case "$Z2_TEST_SCAN" in
        clean) return 0 ;;
        live) OWNED_SCAN_PIDS=123; return 0 ;;
        *) return 1 ;;
    esac
}
owned_family_present() {
    case "$Z2_TEST_FIREWALL" in absent) return 1;; present) return 0;; *) return 2;; esac
}
caller_holds_exact_lifecycle_lock() { [ "$Z2_TEST_LOCKED" = 1 ]; }

QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
FIREWALL_TAG=AbCdEf1234; ZAPRET2_OUT=Z2O_AbCdEf1234; ZAPRET2_IN=Z2I_AbCdEf1234; PENDING_OWNER_GENERATION=owner-v7
IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1; IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1
ARGV_SHA256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
prepare_owner_generation_spec 1 0 || fail "could not prepare canonical owner generation"
write_owner_state 123 456 "$ARGV_SHA256" 200 owner-v7 active || fail "could not write v7 owner"
chmod 0600 "$OWNER_STATE"
cp "$OWNER_STATE" "$CASE/owner.v7"
sed 's/^version=7$/version=6/; s/^argv_sha256=/argv_hex=/' "$CASE/owner.v7" > "$CASE/owner.v6"
sed 's/^version=6$/version=5/; /^firewall_tag=/d; /^out_chain=/d; /^in_chain=/d' "$CASE/owner.v6" > "$CASE/owner.v5"
sed 's/^version=5$/version=4/; /^boot_id=/d' "$CASE/owner.v5" > "$CASE/owner.v4"
sed -n '1,7p;9p' "$CASE/owner.v5" | sed 's/^version=5$/version=3/' > "$CASE/owner.v3"
chmod 0600 "$CASE/owner.v5"
chmod 0600 "$CASE/owner.v4"
chmod 0600 "$CASE/owner.v3"
chmod 0600 "$CASE/owner.v6"

[ "$(sed -n '1p' "$CASE/owner.v7")" = version=7 ] || fail "writer did not publish v7"
[ "$(awk -F= '{ printf "%s%s", NR==1?"":"|", $1 }' "$CASE/owner.v7")" = "$OWNER_STATE_V7_FIELD_SEQUENCE" ] || fail "v7 field order is not canonical"
[ "$(awk -F= '{ printf "%s%s", NR==1?"":"|", $1 }' "$CASE/owner.v6")" = "$OWNER_STATE_V6_FIELD_SEQUENCE" ] || fail "v6 field order is not canonical"
[ "$(awk -F= '{ printf "%s%s", NR==1?"":"|", $1 }' "$CASE/owner.v5")" = "$OWNER_STATE_V5_FIELD_SEQUENCE" ] || fail "legacy v5 fixture is not canonical"
grep -Fqx "boot_id=$BOOT_A" "$CASE/owner.v7" || fail "writer did not bind current boot"
grep -Fqx "argv_sha256=$ARGV_SHA256" "$CASE/owner.v7" || fail "writer did not publish the command digest"
if grep -q '^argv_hex=' "$CASE/owner.v7"; then fail "v7 duplicated the full process command"; fi
[ "$(wc -c < "$CASE/owner.v7")" -lt 4096 ] || fail "v7 owner metadata is not compact"
OWNER_WRITE_QNUM=""; OWNER_WRITE_PORTS_TCP=""; OWNER_WRITE_PORTS_UDP=""; OWNER_WRITE_STUN_PORTS=""
OWNER_WRITE_PKT_OUT=""; OWNER_WRITE_PKT_IN=""; OWNER_WRITE_DESYNC_MARK=""; OWNER_WRITE_READY=0
read_owner_state && owner_state_is_current_boot || fail "v7 write/read round trip failed"
owner_loaded_generation_for_write || fail "current owner could not be loaded for rewrite"
write_owner_state 123 456 "$ARGV_SHA256" 200 owner-v7 active || fail "canonical owner rewrite failed"
cmp -s "$CASE/owner.v7" "$OWNER_STATE" || fail "v7 byte-for-byte round trip changed"
oversized_generation="$(awk 'BEGIN { for (i=0; i<65536; i++) printf "a" }')"
if write_owner_state 123 456 "$ARGV_SHA256" 200 "$oversized_generation" active; then
    fail "oversized v7 owner metadata was published"
fi
cmp -s "$CASE/owner.v7" "$OWNER_STATE" || fail "rejected oversized owner changed the committed record"

assert_owner_rejected() {
    candidate="$1"
    cp "$candidate" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
    if read_owner_state; then fail "malformed owner was accepted: $candidate"; fi
}
sed '/^firewall_tag=/d' "$CASE/owner.v7" > "$CASE/missing"
sed 's/^firewall_tag=.*/firewall_tag=unsafe!/' "$CASE/owner.v7" > "$CASE/malformed"
sed 's/^out_chain=.*/out_chain=Z2O_wrongchain/' "$CASE/owner.v7" > "$CASE/bad-out-chain"
sed 's/^in_chain=.*/in_chain=Z2I_wrongchain/' "$CASE/owner.v7" > "$CASE/bad-in-chain"
sed 's/^argv_sha256=.*/argv_sha256=ABCDEF/' "$CASE/owner.v7" > "$CASE/bad-argv-digest"
sed '/^firewall_tag=/p' "$CASE/owner.v7" > "$CASE/duplicate"
cp "$CASE/owner.v7" "$CASE/unknown"; printf 'future=value\n' >> "$CASE/unknown"
for candidate in "$CASE/missing" "$CASE/malformed" "$CASE/bad-out-chain" "$CASE/bad-in-chain" "$CASE/bad-argv-digest" "$CASE/duplicate" "$CASE/unknown"; do
    chmod 0600 "$candidate"; assert_owner_rejected "$candidate"
done

cp "$CASE/owner.v4" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
read_owner_state || fail "exact legacy v4 could not be classified"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_LEGACY" = 4:1 ] || fail "v4 was not marked legacy"
if owner_state_is_current_boot; then fail "legacy v4 was accepted as current/healthy"; fi
cp "$CASE/owner.v3" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
read_owner_state || fail "exact legacy v3 could not be classified"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_LEGACY" = 3:1 ] || fail "v3 was not marked legacy"
if owner_state_is_current_boot; then fail "legacy v3 was accepted as current/healthy"; fi
cp "$CASE/owner.v5" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
read_owner_state || fail "exact same-boot legacy v5 could not be classified"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_LEGACY" = 5:1 ] || fail "same-boot v5 was not marked legacy"
if owner_state_is_current_boot; then fail "same-boot legacy v5 was accepted as current/healthy"; fi
Z2_TEST_CURRENT_BOOT="$BOOT_B"
read_owner_state || fail "exact cross-boot legacy v5 could not be classified"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_LEGACY" = 5:1 ] || fail "cross-boot v5 was not marked legacy"
if owner_state_is_current_boot; then fail "cross-boot legacy v5 was accepted as current/healthy"; fi
Z2_TEST_CURRENT_BOOT="$BOOT_A"
cp "$CASE/owner.v6" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
read_owner_state || fail "exact same-boot legacy v6 could not be classified"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_LEGACY" = 6:1 ] || fail "v6 was not marked legacy"
if owner_state_is_current_boot; then fail "legacy v6 was accepted as current/healthy"; fi

reset_case() {
    cp "$1" "$OWNER_STATE"; chmod 0600 "$OWNER_STATE"
    rm -f "$PIDFILE" "$TEARDOWN_JOURNAL"
    Z2_TEST_PROCESS=dead; Z2_TEST_SCAN=clean; Z2_TEST_FIREWALL=absent
    Z2_TEST_LOCKED=0; Z2_TEST_BOOT_QUERY=ok; Z2_TEST_CURRENT_BOOT="$BOOT_A"
}
assert_preserved_failure() {
    if recover_stale_owner_publication; then fail "$1 unexpectedly succeeded"; fi
    [ -f "$OWNER_STATE" ] || fail "$1 retired owner evidence"
}

reset_case "$CASE/owner.v4"
recover_stale_owner_publication || fail "clean legacy classification before lock failed"
[ -f "$OWNER_STATE" ] || fail "legacy owner retired without lifecycle lock"
Z2_TEST_LOCKED=1
recover_stale_owner_publication || fail "locked clean legacy retirement failed"
[ ! -e "$OWNER_STATE" ] || fail "locked clean legacy owner was retained"

reset_case "$CASE/owner.v4"; Z2_TEST_SCAN=live
assert_preserved_failure "live legacy process"
reset_case "$CASE/owner.v4"; Z2_TEST_SCAN=query-failed
assert_preserved_failure "legacy process query failure"
reset_case "$CASE/owner.v4"; Z2_TEST_FIREWALL=present
assert_preserved_failure "legacy firewall presence"
reset_case "$CASE/owner.v4"; Z2_TEST_FIREWALL=query-failed
assert_preserved_failure "legacy firewall query failure"

reset_case "$CASE/owner.v4"; Z2_TEST_SCAN=live; Z2_TEST_LOCKED=1
INSTALL_DEFER_LEGACY_OWNER_RECOVERY=1
audit_recovery_artifacts install || fail "direct install could not defer an exact live legacy owner"
[ -f "$OWNER_STATE" ] || fail "direct-install deferral mutated legacy owner evidence"
INSTALL_DEFER_LEGACY_OWNER_RECOVERY=0
if audit_recovery_artifacts install; then fail "legacy deferral remained active after one-shot flag cleared"; fi
[ -f "$OWNER_STATE" ] || fail "post-deferral failure mutated legacy owner evidence"

reset_case "$CASE/owner.v7"; Z2_TEST_PROCESS=live
recover_stale_owner_publication || fail "same-boot live v7 was not recognized"
[ -f "$OWNER_STATE" ] || fail "same-boot live owner was retired"

# A modules_update installer loads helpers from a candidate path while owner v7
# still names the canonical live binary.  The audit-local override authenticates
# that live publication without mutating the staged NFQWS2 global on either
# success or failure.
reset_case "$CASE/owner.v7"; Z2_TEST_PROCESS=live
live_nfqws="$MOD/zapret2/nfqws2"; staged_nfqws="$CASE/staged/zapret2/nfqws2"
NFQWS2="$staged_nfqws"
audit_recovery_artifacts install "$live_nfqws" || fail "live-path install audit rejected exact running owner"
[ "$NFQWS2" = "$staged_nfqws" ] || fail "successful live-path audit leaked its override"
Z2_TEST_PROCESS=dead
if audit_recovery_artifacts install "$live_nfqws"; then fail "live-path audit accepted same-boot dead owner"; fi
[ "$NFQWS2" = "$staged_nfqws" ] || fail "failed live-path audit leaked its override"
NFQWS2="$live_nfqws"

reset_case "$CASE/owner.v7"
assert_preserved_failure "same-boot dead v7"
reset_case "$CASE/owner.v6"; Z2_TEST_PROCESS=live; Z2_TEST_SCAN=live; Z2_TEST_LOCKED=1
assert_preserved_failure "same-boot live legacy v6"
reset_case "$CASE/owner.v6"; Z2_TEST_LOCKED=1
assert_preserved_failure "same-boot dead legacy v6"
reset_case "$CASE/owner.v5"; Z2_TEST_PROCESS=live; Z2_TEST_SCAN=live; Z2_TEST_LOCKED=1
assert_preserved_failure "same-boot live legacy v5"
reset_case "$CASE/owner.v5"; Z2_TEST_LOCKED=1
assert_preserved_failure "same-boot dead legacy v5"
reset_case "$CASE/owner.v5"; Z2_TEST_CURRENT_BOOT="$BOOT_B"; Z2_TEST_SCAN=live
assert_preserved_failure "cross-boot live v5"
reset_case "$CASE/owner.v5"; Z2_TEST_CURRENT_BOOT="$BOOT_B"; Z2_TEST_LOCKED=1
printf 'status=ok\n' > "$STATUS_SNAPSHOT"; chmod 0600 "$STATUS_SNAPSHOT"
BOOT_STALE_RUNTIME_RECOVERY=1
recover_stale_owner_publication || fail "locked clean cross-boot retirement failed"
[ ! -e "$OWNER_STATE" ] || fail "locked clean cross-boot owner was retained"
[ ! -e "$STATUS_SNAPSHOT" ] || fail "locked clean cross-boot status was retained"
[ "$STALE_OWNER_PUBLICATION_RETIRED" = 1 ] || fail "cross-boot retirement was not reported"
BOOT_STALE_RUNTIME_RECOVERY=0
echo "Owner state v7/legacy-v6 shell tests passed"
