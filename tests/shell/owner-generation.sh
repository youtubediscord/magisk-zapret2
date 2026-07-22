#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/owner-generation"
MOD="$CASE/module"
STATE="$CASE/state"
MOCK="$CASE/bin"
fail() { echo "FAIL: owner-generation: $*" >&2; exit 1; }
command -v chmod >/dev/null 2>&1 || chmod() { :; }
command -v sync >/dev/null 2>&1 || sync() { :; }

mkdir -p "$MOD/zapret2/scripts" "$STATE" "$MOCK"
cp "$ROOT/zapret2/scripts/common.sh" "$MOD/zapret2/scripts/common.sh"
: > "$MOD/zapret2/nfqws2"
cat > "$MOD/zapret2/install-generation.meta" <<EOF
version=1
module_dir=$MOD
generation=owner-test-install
archive_sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
EOF
chmod 0600 "$MOD/zapret2/install-generation.meta"

cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
emit() {
    echo "-N $Z2_OUT_CHAIN"; echo "-N $Z2_IN_CHAIN"
    for n in 1 2; do echo "-N Z2R_${Z2_FIREWALL_TAG}_O$n"; done
    for n in 1 2; do echo "-N Z2R_${Z2_FIREWALL_TAG}_I$n"; done
    echo "-A OUTPUT -j $Z2_OUT_CHAIN"
    [ "${Z2_SNAPSHOT_MODE:-ok}" != duplicate-anchor ] || echo "-A OUTPUT -j $Z2_OUT_CHAIN"
    echo "-A INPUT -j $Z2_IN_CHAIN"
    for n in 1 2; do
        [ "${Z2_SNAPSHOT_MODE:-ok}:$n" = missing-jump:2 ] || echo "-A $Z2_OUT_CHAIN -j Z2R_${Z2_FIREWALL_TAG}_O$n"
    done
    for n in 1 2; do echo "-A $Z2_IN_CHAIN -j Z2R_${Z2_FIREWALL_TAG}_I$n"; done
    echo "-A Z2R_${Z2_FIREWALL_TAG}_O1 -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num 200 --queue-bypass"
    echo "-A Z2R_${Z2_FIREWALL_TAG}_O2 -p udp -m multiport --dports 443,3478,5349,19302 -j NFQUEUE --queue-num 200 --queue-bypass"
    echo "-A Z2R_${Z2_FIREWALL_TAG}_I1 -p tcp -m multiport --sports 80,443 -j NFQUEUE --queue-num 200 --queue-bypass"
    echo "-A Z2R_${Z2_FIREWALL_TAG}_I2 -p udp -m multiport --sports 443,3478,5349,19302 -j NFQUEUE --queue-num 200 --queue-bypass"
    [ "${Z2_SNAPSHOT_MODE:-ok}" != extra-payload ] || echo "-A Z2R_${Z2_FIREWALL_TAG}_O1 -j RETURN"
}
case " $* " in
    ' -t mangle -S ') emit; exit 0 ;;
    *' -C Z2R_'*) [ "${Z2_SNAPSHOT_MODE:-ok}" != wrong-payload ]; exit $? ;;
    *) exit 1 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"
chmod 0755 "$MOCK/iptables" "$MOCK/ip6tables"

export STATE_DIR="$STATE" SCRIPT_DIR="$MOD/zapret2/scripts" ZAPRET_DIR="$MOD/zapret2" MODDIR="$MOD" PATH="$MOCK:$PATH"
. "$SCRIPT_DIR/common.sh"
# Host-independent security shims keep this pure parser/firewall-identity test
# executable in the stripped Git shell; ownership/mode checks have dedicated
# installer/package tests.
path_uid_is_root() { :; }; path_mode_is_0600() { :; }; path_nlink_is_one() { :; }
state_file_is_secure() { [ -f "$1" ]; }; state_file_target_is_safe() { [ ! -L "$1" ]; }
read_current_boot_id() { CURRENT_BOOT_ID=11111111-1111-1111-1111-111111111111; }
if ! command -v sha256sum >/dev/null 2>&1; then sha256sum() { cat >/dev/null; echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; }; fi
new_lifecycle_token() { printf '%s\n' 'AbCdEf1234-generation'; }

QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443,3478,5349,19302; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1; IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1
prepare_new_firewall_identity || fail "schema-v7 firewall identity preparation failed"
[ "$FIREWALL_TAG:$ZAPRET2_OUT:$ZAPRET2_IN" = AbCdEf1234:Z2O_AbCdEf1234:Z2I_AbCdEf1234 ] || fail "dynamic firewall identity changed"
prepare_owner_generation_spec 1 0 || fail "owner generation preparation failed"
write_owner_state 123 456 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 200 generation-a active || fail "owner publication failed"
read_owner_state || fail "owner v7 round trip failed"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_FIREWALL_TAG:$OWNER_STATE_OUT_CHAIN:$OWNER_STATE_IN_CHAIN" = 7:AbCdEf1234:Z2O_AbCdEf1234:Z2I_AbCdEf1234 ] || fail "owner identity fields changed"

Z2_FIREWALL_TAG="$FIREWALL_TAG"; Z2_OUT_CHAIN="$ZAPRET2_OUT"; Z2_IN_CHAIN="$ZAPRET2_IN"
export Z2_FIREWALL_TAG Z2_OUT_CHAIN Z2_IN_CHAIN
owner_family_generation_healthy iptables ipv4 || fail "healthy per-rule generation was rejected"
for mode in missing-jump duplicate-anchor extra-payload wrong-payload; do
    Z2_SNAPSHOT_MODE="$mode"; export Z2_SNAPSHOT_MODE
    if owner_family_generation_healthy iptables ipv4; then fail "$mode generation was accepted"; fi
done
unset Z2_SNAPSHOT_MODE

for parent in "$ZAPRET2_OUT" "$ZAPRET2_IN"; do
    owner_rule_chain "$parent" 1 || fail "per-rule chain derivation failed"
    case "$OWNER_RULE_CHAIN" in Z2R_AbCdEf1234_[OI]1) ;; *) fail "unsafe per-rule chain name: $OWNER_RULE_CHAIN";; esac
done

echo "Owner generation shell tests passed"
