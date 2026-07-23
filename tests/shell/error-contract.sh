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

z2_error_set FIREWALL FIREWALL_BUILD_FAILED START_IPV4_BUILD_RULE 1 ||
    fail "valid error record was rejected"
actual="$(z2_error_emit_machine)"
expected='Z2_ERROR_SCHEMA=1
Z2_ERROR_DOMAIN=FIREWALL
Z2_ERROR_CODE=FIREWALL_BUILD_FAILED
Z2_ERROR_STAGE=START_IPV4_BUILD_RULE
Z2_ERROR_RETRYABLE=1'
[ "$actual" = "$expected" ] || fail "machine error record changed"

z2_error_set NONE FIREWALL_BUILD_FAILED START_IPV4_BUILD_RULE 1 &&
    fail "non-NONE error accepted a NONE domain"
z2_error_set FIREWALL NONE NONE 0 &&
    fail "NONE error accepted a non-NONE domain"
z2_error_set PROCESS FIREWALL_BUILD_FAILED START_IPV4_BUILD_RULE 1 &&
    fail "firewall code accepted a process domain"
z2_error_set FIREWALL FIREWALL_BUILD_FAILED 'bad-stage' 1 &&
    fail "unsafe stage was accepted"

STATUS_QNUM=200
STATUS_ERROR_DOMAIN=FIREWALL
STATUS_ERROR_CODE=FIREWALL_BUILD_FAILED
STATUS_ERROR_STAGE=START_IPV4_BUILD_RULE
STATUS_ERROR_RETRYABLE=1
write_iptables_status error || fail "typed status snapshot write failed"
read_iptables_status || fail "typed status snapshot read failed"
[ "$STATUS_FILE_ERROR_SCHEMA:$STATUS_FILE_ERROR_DOMAIN:$STATUS_FILE_ERROR_CODE" = \
    "1:FIREWALL:FIREWALL_BUILD_FAILED" ] || fail "typed status identity was not preserved"
[ "$STATUS_FILE_ERROR_STAGE:$STATUS_FILE_ERROR_RETRYABLE" = "START_IPV4_BUILD_RULE:1" ] ||
    fail "typed status recovery metadata was not preserved"

echo "Error contract shell tests passed"
