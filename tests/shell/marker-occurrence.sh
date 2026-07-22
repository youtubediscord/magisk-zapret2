#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/marker-occurrence"
mkdir -p "$CASE"
fail() { echo "FAIL: marker-occurrence: $*" >&2; exit 1; }

SCRIPT_DIR="$ROOT/zapret2/scripts"
ZAPRET_DIR="$ROOT/zapret2"
MODDIR="$ROOT"
STATE_DIR="$CASE"
. "$ROOT/zapret2/scripts/common.sh"
TEARDOWN_JOURNAL="$CASE/wal"

# Identity behavior is what this focused test exercises.  A passthrough od
# stub keeps it self-contained on stripped host shells; production uses the
# real byte encoder and the same comparisons.
od() { cat; }
hex() { printf '%s' "$1" | od -An -v -tx1 | tr -d '[:space:]'; }
t='-A ZAPRET2_OUT -p tcp -m tcp --dport 443 -j NFQUEUE --queue-num 200 --queue-bypass'
s='-A ZAPRET2_OUT -p udp -m udp --dport 443 -j NFQUEUE --queue-num 200 --queue-bypass'
h='-A ZAPRET2_OUT -j FOREIGN_HEAD'
th=$(hex "$t"); shx=$(hex "$s")

cat > "$TEARDOWN_JOURNAL" <<EOF
record|1|applied|ipv4|rule|ZAPRET2_OUT|tcp|out|443|20|original|200|0x1|0|1|0|1|$th|Z2M4_1_token
record|2|pending|ipv4|rule|ZAPRET2_OUT|tcp|out|443|20|original|200|0x1|0|1|0|2|$th|Z2M4_2_token
record|3|pending|ipv4|rule|ZAPRET2_OUT|udp|out|443|20|original|200|0x1|0|1|0|3|$shx|Z2M4_3_token
EOF

SNAPSHOT="$h
$t
$t
$s
$t"
iptables() { [ "$*" = '-t mangle -S' ] || return 1; printf '%s\n' "$SNAPSHOT"; }
pos=$(locate_teardown_rule_block iptables ipv4 ZAPRET2_OUT 1) || fail "ordered suffix was not located"
[ "$pos" = 2 ] || fail "foreign head shifted occurrence identity to $pos"

# A second complete identical generation suffix is ambiguous and must not be
# selected by ordinal guesswork.
SNAPSHOT="$SNAPSHOT
$t
$t
$s"
if locate_teardown_rule_block iptables ipv4 ZAPRET2_OUT 1 >/dev/null; then fail "duplicate suffix was not rejected"; fi

marker=Z2M4_1_token
SNAPSHOT="-N $marker
-A $marker -j RETURN
-N ZAPRET2_OUT
-A ZAPRET2_OUT -j $marker
$t
-A ZAPRET2_OUT -j $marker
$h"
teardown_bracket_matches iptables ZAPRET2_OUT "$marker" "$th" || fail "exact bracket bytes were rejected"
SNAPSHOT="$(printf '%s\n' "$SNAPSHOT" | sed "s/--dport 443/--dport 444/")"
if teardown_bracket_matches iptables ZAPRET2_OUT "$marker" "$th"; then fail "changed middle bytes were accepted"; fi

echo "Marker occurrence shell tests passed"
