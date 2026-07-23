#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}; CASE="$TMP/wal-validator"; mkdir -p "$CASE"
fail() { echo "FAIL: wal-validator: $*" >&2; exit 1; }
SCRIPT_DIR="$ROOT/zapret2/scripts"; ZAPRET_DIR="$ROOT/zapret2"; MODDIR="$ROOT"; STATE_DIR="$CASE"
. "$ROOT/zapret2/scripts/common.sh"
od() { cat >/dev/null; printf 'aa\n'; }; chmod() { :; }; sync() { :; }
state_file_is_secure() { [ -f "$1" ]; }; path_mode_is_0600() { :; }; state_file_target_is_safe() { :; }
owner_state_is_current_boot() { :; }
read_current_boot_id() { CURRENT_BOOT_ID=00000000-0000-0000-0000-000000000000; }

OWNER_STATE_GENERATION=generation1
OWNER_STATE_FIREWALL_FINGERPRINT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
OWNER_STATE_BOOT_ID=00000000-0000-0000-0000-000000000000
OWNER_STATE_FIREWALL_TAG=AbCdEf1234; OWNER_STATE_OUT_CHAIN=Z2O_AbCdEf1234; OWNER_STATE_IN_CHAIN=Z2I_AbCdEf1234
FIREWALL_TAG="$OWNER_STATE_FIREWALL_TAG"; ZAPRET2_OUT="$OWNER_STATE_OUT_CHAIN"; ZAPRET2_IN="$OWNER_STATE_IN_CHAIN"
OWNER_STATE_IPV4_ACTIVE=1; OWNER_STATE_IPV6_ACTIVE=0
OWNER_STATE_IPV4_CONNBYTES=1; OWNER_STATE_IPV4_MULTIPORT=1; OWNER_STATE_IPV4_MARK=1
OWNER_STATE_IPV4_RULES=4; OWNER_STATE_IPV6_RULES=0
OWNER_STATE_IPV6_CONNBYTES=0; OWNER_STATE_IPV6_MULTIPORT=0; OWNER_STATE_IPV6_MARK=0
OWNER_STATE_PORTS_TCP=80:65535; OWNER_STATE_PORTS_UDP=443:65535; OWNER_STATE_STUN_PORTS=0
OWNER_STATE_PKT_OUT=20; OWNER_STATE_PKT_IN=10; OWNER_STATE_QNUM=200; OWNER_STATE_DESYNC_MARK=0x40000000
TEARDOWN_JOURNAL="$CASE/wal"

iptables() {
    [ "$*" = '-t mangle -S' ] || return 1
    if [ "$OWNER_STATE_IPV4_CONNBYTES" = 0 ]; then
        cat <<'EOF'
-N Z2O_AbCdEf1234
-N Z2R_AbCdEf1234_O1
-N Z2R_AbCdEf1234_O2
-A OUTPUT -j Z2O_AbCdEf1234
-A Z2O_AbCdEf1234 -j Z2R_AbCdEf1234_O1
-A Z2O_AbCdEf1234 -j Z2R_AbCdEf1234_O2
-A Z2R_AbCdEf1234_O1 -p tcp -m multiport --dports 80:65535 -j NFQUEUE
-A Z2R_AbCdEf1234_O2 -p udp -m multiport --dports 443:65535 -j NFQUEUE
EOF
        return
    fi
    cat <<'EOF'
-N Z2O_AbCdEf1234
-N Z2I_AbCdEf1234
-N Z2R_AbCdEf1234_O1
-N Z2R_AbCdEf1234_O2
-N Z2R_AbCdEf1234_I1
-N Z2R_AbCdEf1234_I2
-A OUTPUT -j Z2O_AbCdEf1234
-A INPUT -j Z2I_AbCdEf1234
-A Z2O_AbCdEf1234 -j Z2R_AbCdEf1234_O1
-A Z2O_AbCdEf1234 -j Z2R_AbCdEf1234_O2
-A Z2I_AbCdEf1234 -j Z2R_AbCdEf1234_I1
-A Z2I_AbCdEf1234 -j Z2R_AbCdEf1234_I2
-A Z2R_AbCdEf1234_O1 -p tcp -m multiport --dports 80:65535 -j NFQUEUE
-A Z2R_AbCdEf1234_O2 -p udp -m multiport --dports 443:65535 -j NFQUEUE
-A Z2R_AbCdEf1234_I1 -p tcp -m multiport --sports 80:65535 -j NFQUEUE
-A Z2R_AbCdEf1234_I2 -p udp -m multiport --sports 443:65535 -j NFQUEUE
EOF
    [ "${SNAP_EXTRA:-0}" != 1 ] || echo '-A Z2O_AbCdEf1234 -j FOREIGN'
}

create_teardown_operation_journal || fail "could not create authoritative manifest"
validate_teardown_operation_journal || fail "fresh manifest rejected"
cp "$TEARDOWN_JOURNAL" "$CASE/good"

rm -f "$TEARDOWN_JOURNAL"; SNAP_EXTRA=1
if create_teardown_operation_journal; then fail "foreign rule present in publication snapshot was accepted"; fi
SNAP_EXTRA=0; cp "$CASE/good" "$TEARDOWN_JOURNAL"

head -n 7 "$CASE/good" > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "header-only WAL accepted"; fi
awk 'NR!=8' "$CASE/good" > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "truncated WAL accepted"; fi
{ head -n 7 "$CASE/good"; sed -n '9p' "$CASE/good"; sed -n '8p' "$CASE/good"; tail -n +10 "$CASE/good"; } > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "reordered WAL accepted"; fi
{ cat "$CASE/good"; tail -n 1 "$CASE/good"; } > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "extra WAL record accepted"; fi
{ head -n 8 "$CASE/good"; sed -n '8p' "$CASE/good"; tail -n +9 "$CASE/good"; } > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "duplicate WAL record accepted"; fi
sed '8s/Z2M4_1_/Z2M4_9_/' "$CASE/good" > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "foreign marker identity accepted"; fi
sed 's/|80:65535|/|80:65534|/' "$CASE/good" > "$TEARDOWN_JOURNAL"
if validate_teardown_operation_journal; then fail "port set differing from owner metadata accepted"; fi

rm -f "$TEARDOWN_JOURNAL"
OWNER_STATE_IPV4_CONNBYTES=0
OWNER_STATE_IPV4_RULES=2
create_teardown_operation_journal || fail "could not create connbytes fallback WAL"
validate_teardown_operation_journal || fail "connbytes fallback WAL was rejected"
if grep -Fq '|INPUT|' "$TEARDOWN_JOURNAL" || grep -Fq 'Z2I_AbCdEf1234' "$TEARDOWN_JOURNAL"; then
    fail "connbytes fallback WAL contains an incoming topology"
fi

echo "WAL validator shell tests passed"
