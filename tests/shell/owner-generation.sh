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
cp "$ROOT/zapret2/scripts/firewall-reconciler.sh" "$MOD/zapret2/scripts/firewall-reconciler.sh"
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
case " $* " in
    *' -t mangle -L OUTPUT -n '*) exit 0 ;;
    *' -t mangle -S '*)
        echo '-N ZAPRET2_OUT'
        echo '-N ZAPRET2_IN'
        [ "${Z2_SNAPSHOT_MODE:-ok}" = missing-anchor ] ||
            echo '-A OUTPUT -j ZAPRET2_OUT'
        echo '-A INPUT -j ZAPRET2_IN'
        if [ "${Z2_SNAPSHOT_MODE:-ok}" = wrong-payload ]; then
            echo '-A ZAPRET2_OUT -p tcp -m multiport --dports 80,443 -m connbytes --connbytes 1:21 --connbytes-dir original --connbytes-mode packets -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass'
        else
            echo '-A ZAPRET2_OUT -p tcp -m multiport --dports 80,443 -m connbytes --connbytes 1:20 --connbytes-dir original --connbytes-mode packets -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass'
        fi
        echo '-A ZAPRET2_OUT -p udp -m multiport --dports 443,3478,5349,19302 -m connbytes --connbytes 1:20 --connbytes-dir original --connbytes-mode packets -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass'
        echo '-A ZAPRET2_IN -p tcp -m multiport --sports 80,443 -m connbytes --connbytes 1:10 --connbytes-dir reply --connbytes-mode packets -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass'
        echo '-A ZAPRET2_IN -p udp -m multiport --sports 443,3478,5349,19302 -m connbytes --connbytes 1:10 --connbytes-dir reply --connbytes-mode packets -m mark ! --mark 0x40000000/0x40000000 -j NFQUEUE --queue-num 200 --queue-bypass'
        [ "${Z2_SNAPSHOT_MODE:-ok}" != extra-payload ] ||
            echo '-A ZAPRET2_OUT -j RETURN'
        ;;
    *' -t mangle -C OUTPUT -j ZAPRET2_OUT '*)
        [ "${Z2_SNAPSHOT_MODE:-ok}" != missing-anchor ]
        ;;
    *' -t mangle -C INPUT -j ZAPRET2_IN '*) exit 0 ;;
    *' -t mangle -C ZAPRET2_OUT '*)
        [ "${Z2_SNAPSHOT_MODE:-ok}" != wrong-payload ]
        ;;
    *' -t mangle -C ZAPRET2_IN '*) exit 0 ;;
    *' -t mangle -S ZAPRET2_OUT '*)
        echo '-N ZAPRET2_OUT'
        echo '-A ZAPRET2_OUT -p tcp -j NFQUEUE'
        echo '-A ZAPRET2_OUT -p udp -j NFQUEUE'
        [ "${Z2_SNAPSHOT_MODE:-ok}" != extra-payload ] ||
            echo '-A ZAPRET2_OUT -j RETURN'
        ;;
    *' -t mangle -S ZAPRET2_IN '*)
        echo '-N ZAPRET2_IN'
        echo '-A ZAPRET2_IN -p tcp -j NFQUEUE'
        echo '-A ZAPRET2_IN -p udp -j NFQUEUE'
        ;;
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

QNUM=200; PORTS_TCP=80,443; PORTS_UDP=443,3478,5349,19302; TCP_PKT_OUT=20; TCP_PKT_IN=10; UDP_PKT_OUT=20; UDP_PKT_IN=10; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1; IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1
ZAPRET2_LIFECYCLE_TOKEN=app-request-generation
export ZAPRET2_LIFECYCLE_TOKEN
prepare_new_firewall_identity || fail "schema-v8 firewall identity preparation failed"
[ "$FIREWALL_TAG:$ZAPRET2_OUT:$ZAPRET2_IN" = stable0001:ZAPRET2_OUT:ZAPRET2_IN ] ||
    fail "stable firewall identity changed"
[ "$PENDING_OWNER_GENERATION" = "$ZAPRET2_LIFECYCLE_TOKEN" ] ||
    fail "owner generation is not bound to the exact lifecycle request"
unset ZAPRET2_LIFECYCLE_TOKEN
prepare_owner_generation_spec 1 0 || fail "owner generation preparation failed"
write_owner_state 123 456 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 200 generation-a active || fail "owner publication failed"
read_owner_state || fail "owner v8 round trip failed"
[ "$OWNER_STATE_SCHEMA_VERSION:$OWNER_STATE_FIREWALL_TAG:$OWNER_STATE_OUT_CHAIN:$OWNER_STATE_IN_CHAIN" = 8:stable0001:ZAPRET2_OUT:ZAPRET2_IN ] ||
    fail "owner identity fields changed"

owner_family_generation_healthy iptables ipv4 || fail "healthy per-rule generation was rejected"
for mode in missing-anchor extra-payload wrong-payload; do
    Z2_SNAPSHOT_MODE="$mode"; export Z2_SNAPSHOT_MODE
    if owner_family_generation_healthy iptables ipv4; then fail "$mode generation was accepted"; fi
done
unset Z2_SNAPSHOT_MODE

echo "Owner generation shell tests passed"
