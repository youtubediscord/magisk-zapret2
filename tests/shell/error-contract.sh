#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
STATE="$TMP/error-contract-state"

fail() { echo "FAIL: error-contract: $*" >&2; exit 1; }

mkdir -p "$STATE"
chmod 0700 "$STATE"
SCRIPT_DIR="$ROOT/zapret2/scripts"
ZAPRET_DIR="$ROOT/zapret2"
MODDIR="$ROOT"
STATE_DIR="$STATE"
export SCRIPT_DIR ZAPRET_DIR MODDIR STATE_DIR
. "$ROOT/zapret2/scripts/common.sh"

# Content/protocol assertions are portable; privileged ownership is covered by
# the lifecycle integration suite.
if [ "$(id -u)" != 0 ]; then
    path_uid_is_root() { return 0; }
fi

z2_error_set FIREWALL FUTURE_FIREWALL_FAILURE START_IPV4_BUILD_RULE \
    "iptables rejected a future rule" ||
    fail "valid error record was rejected"
actual="$(z2_error_emit_machine)"
expected='Z2_ERROR_SCHEMA=1
Z2_ERROR_STATUS=ERROR
Z2_ERROR_DOMAIN=FIREWALL
Z2_ERROR_STAGE=START_IPV4_BUILD_RULE
Z2_ERROR_CODE=FUTURE_FIREWALL_FAILURE
Z2_ERROR_DETAIL=iptables rejected a future rule'
[ "$actual" = "$expected" ] || fail "machine error record changed"

z2_error_set NONE FIREWALL_BUILD_FAILED START_IPV4_BUILD_RULE "detail" &&
    fail "non-NONE error accepted a NONE domain"
z2_error_set FIREWALL NONE NONE "detail" &&
    fail "NONE error accepted a non-NONE domain"
z2_error_set FIREWALL FIREWALL_BUILD_FAILED 'bad-stage' "detail" &&
    fail "unsafe stage was accepted"
z2_error_set FIREWALL FIREWALL_BUILD_FAILED START_IPV4_BUILD_RULE "" &&
    fail "empty error detail was accepted"

STATUS_QNUM=200
STATUS_ERROR_STATUS=ERROR
STATUS_ERROR_DOMAIN=FIREWALL
STATUS_ERROR_CODE=FUTURE_FIREWALL_FAILURE
STATUS_ERROR_STAGE=START_IPV4_BUILD_RULE
STATUS_ERROR_DETAIL="iptables rejected a future rule"
write_iptables_status error || fail "typed status snapshot write failed"
read_iptables_status || fail "typed status snapshot read failed"
[ "$STATUS_FILE_ERROR_SCHEMA:$STATUS_FILE_ERROR_DOMAIN:$STATUS_FILE_ERROR_CODE" = \
    "1:FIREWALL:FUTURE_FIREWALL_FAILURE" ] || fail "typed status identity was not preserved"
[ "$STATUS_FILE_ERROR_STATUS:$STATUS_FILE_ERROR_STAGE:$STATUS_FILE_ERROR_DETAIL" = \
    "ERROR:START_IPV4_BUILD_RULE:iptables rejected a future rule" ] ||
    fail "typed status recovery metadata was not preserved"

echo "Error contract shell tests passed"
