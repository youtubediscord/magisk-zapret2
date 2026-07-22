#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d "${TMPDIR:-/tmp}/zapret2-shell-tests.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }
assert_not_contains() { ! grep -Fq -- "$2" "$1" || fail "$1 unexpectedly contains: $2"; }
assert_fails() { "$@" >/dev/null 2>&1 && fail "command unexpectedly succeeded: $*"; return 0; }

assert_unsafe_machine_root() {
    operation="$1" expected="$2" output="" rc=0
    output="$(sh "$ROOT/zapret2/scripts/command-builder.sh" "$operation" relative-root 2>&1)" || rc=$?
    [ "$rc" -eq 2 ] || fail "$operation unsafe-root rejection did not exit 2"
    [ "$output" = "$(printf '%s\tUNSAFE_ROOT' "$expected")" ] ||
        fail "$operation unsafe-root record is not protocol-specific"
}

[ "$(id -u)" = 0 ] || fail "run as root so ownership checks are real"

assert_unsafe_machine_root --scan-presets-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --validate-preset-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --preflight-preset-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --preview-preset-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --validate-strategies-machine Z2_STRATEGIES_ERROR

for script in "$ROOT"/*.sh "$ROOT"/zapret2/scripts/*.sh "$ROOT"/tests/shell/*.sh; do
    case "$(sed -n '1p' "$script")" in
        *bash*) bash -n "$script" || fail "syntax: $script" ;;
        *) sh -n "$script" || fail "syntax: $script" ;;
    esac
done

for retired in \
    "$ROOT/zapret2/config.sh" \
    "$ROOT/zapret2/categories.ini" \
    "$ROOT/zapret2/strategies-tcp.ini" \
    "$ROOT/zapret2/strategies-udp.ini" \
    "$ROOT/zapret2/strategies-stun.ini" \
    "$ROOT/zapret2/blobs.txt" \
    "$ROOT/zapret2/scripts/runtime-migrate.sh"; do
    [ ! -e "$retired" ] && [ ! -L "$retired" ] || fail "retired file remains: $retired"
done

if grep -R --include='*.txt' -n -- '--ipcache' "$ROOT/zapret2/presets" "$ROOT/zapret2/strategy-catalogs" >/dev/null; then
    fail "forbidden Android ipcache option remains"
fi

STRATEGY_OUTPUT="$(sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --validate-strategies-machine "$ROOT/zapret2")" || fail "strategy catalogs were rejected"
[ "$STRATEGY_OUTPUT" = "$(printf 'Z2_STRATEGIES\tOK')" ] || fail "strategy output is not exact"

BAD_STRATEGIES="$TMP/bad-strategies"
cp -R "$ROOT/zapret2" "$BAD_STRATEGIES"
printf '%s\n' 'name = duplicate metadata must fail' >> "$BAD_STRATEGIES/strategy-catalogs/http80.txt"
assert_fails sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --validate-strategies-machine "$BAD_STRATEGIES"

PRESET_SCAN="$TMP/presets.scan"
sh "$ROOT/zapret2/scripts/command-builder.sh" --scan-presets-machine "$ROOT/zapret2" > "$PRESET_SCAN" ||
    fail "preset catalog was rejected"
PRESET_TOTAL="$(find "$ROOT/zapret2/presets" -maxdepth 1 -type f -name '*.txt' | wc -l)"
grep -Fxq "Z2_PRESET_SUMMARY$(printf '\t')1$(printf '\t')valid=$PRESET_TOTAL$(printf '\t')quarantined=0$(printf '\t')total=$PRESET_TOTAL" "$PRESET_SCAN" ||
    fail "preset summary does not match the packaged catalog"

FIXTURE="$TMP/compiler"
cp -R "$ROOT/zapret2" "$FIXTURE"
cat > "$FIXTURE/nfqws2" <<'EOF'
#!/bin/sh
if [ "${1:-}" = --help ]; then
    printf '%s\n' '--daemon' '--pidfile'
fi
exit 0
EOF
chmod 0755 "$FIXTURE/nfqws2"
cat > "$FIXTURE/presets/TCP only.txt" <<'EOF'
--lua-init=@lua/zapret-lib.lua
--blob=zero:0x00

--name=TCP only
--filter-tcp=80
--lua-desync=pass
EOF
cat > "$FIXTURE/presets/UDP only.txt" <<'EOF'
--lua-init=@lua/zapret-lib.lua
--blob=zero:0x00

--name=Discord voice
--filter-udp=443
--filter-l7=stun,discord
--lua-desync=pass
EOF

SCRIPT_DIR="$FIXTURE/scripts"
ZAPRET_DIR="$FIXTURE"
MODDIR="$TMP"
STATE_DIR="$TMP/state"
mkdir -p "$STATE_DIR"
. "$FIXTURE/scripts/common.sh"
. "$FIXTURE/scripts/command-builder.sh"
QNUM=200 DESYNC_MARK=0x40000000 NFQWS_UID=0:0 LOG_MODE=none

compile_preset_artifact "$FIXTURE/presets/TCP only.txt" "TCP only.txt" "$TMP/tcp.argv" ||
    fail "TCP-only preset did not compile"
read_compiled_artifact_metadata "$TMP/tcp.argv" || fail "TCP artifact metadata is invalid"
[ "$COMPILED_TCP_PORTS" = 80 ] && [ -z "$COMPILED_UDP_PORTS" ] ||
    fail "TCP-only preset opened unexpected ports"
cp "$TMP/tcp.argv" "$TMP/tampered-ipcache.argv"
printf '%s\n' '--ipcache-hostname=1' >> "$TMP/tampered-ipcache.argv"
read_compiled_artifact_metadata "$TMP/tampered-ipcache.argv" || fail "tampered artifact fixture is structurally invalid"
assert_fails run_compiled_artifact "$TMP/tampered-ipcache.argv" dry-run

compile_preset_artifact "$FIXTURE/presets/UDP only.txt" "UDP only.txt" "$TMP/udp.argv" ||
    fail "UDP-only preset did not compile"
read_compiled_artifact_metadata "$TMP/udp.argv" || fail "UDP artifact metadata is invalid"
[ -z "$COMPILED_TCP_PORTS" ] && [ "$COMPILED_UDP_PORTS" = 443,3478,5349,19302 ] ||
    fail "voice UDP union is not exact"
grep -Fxq -- '--name=Discord voice' "$TMP/udp.argv" || fail "argument with spaces was split"

PREVIEW_OUTPUT="$TMP/preview.out"
STATE_DIR="$STATE_DIR" sh "$FIXTURE/scripts/command-builder.sh" \
    --preview-preset-machine "$FIXTURE" "$FIXTURE/presets/TCP only.txt" "TCP only.txt" > "$PREVIEW_OUTPUT" ||
    fail "command preview rejected a valid unsaved candidate"
grep -Fxq "Z2_COMMAND_PREVIEW$(printf '\t')1$(printf '\t')TCP only.txt$(printf '\t')TCP=80$(printf '\t')UDP=" \
    "$PREVIEW_OUTPUT" || fail "command preview port metadata is not exact"
grep -Fxq "Z2_COMMAND_EXECUTABLE$(printf '\t')$FIXTURE/nfqws2" "$PREVIEW_OUTPUT" ||
    fail "command preview executable is not exact"
grep -Fxq "Z2_COMMAND_ARGUMENT$(printf '\t')--daemon" "$PREVIEW_OUTPUT" ||
    fail "command preview omitted launcher daemon mode"
grep -Fxq "Z2_COMMAND_ARGUMENT$(printf '\t')--pidfile=$STATE_DIR/nfqws2.pid" "$PREVIEW_OUTPUT" ||
    fail "command preview omitted launcher pidfile"
grep -Fxq "Z2_COMMAND_ARGUMENT$(printf '\t')--name=TCP only" "$PREVIEW_OUTPUT" ||
    fail "command preview split an argument containing spaces"
grep -Eq "^Z2_COMMAND_SUMMARY$(printf '\t')1$(printf '\t')count=[0-9][0-9]*$" "$PREVIEW_OUTPUT" ||
    fail "command preview summary is missing"
PREVIEW_COUNT="$(sed -n 's/^Z2_COMMAND_SUMMARY[[:space:]]1[[:space:]]count=//p' "$PREVIEW_OUTPUT")"
PREVIEW_ARGUMENTS="$(grep -c "^Z2_COMMAND_ARGUMENT$(printf '\t')" "$PREVIEW_OUTPUT")"
[ "$PREVIEW_COUNT" = "$PREVIEW_ARGUMENTS" ] && [ "$PREVIEW_COUNT" -gt 3 ] ||
    fail "command preview argument count is not exact"

sed '/--name=TCP only/a --skip' "$FIXTURE/presets/TCP only.txt" > "$TMP/disabled.txt"
assert_fails compile_preset_artifact "$TMP/disabled.txt" "TCP only.txt" "$TMP/disabled.argv"
sed '/--name=TCP only/a --ipcache-hostname' "$FIXTURE/presets/TCP only.txt" > "$FIXTURE/presets/IPCache.txt"
assert_fails validate_preset_file "$FIXTURE/presets/IPCache.txt" "IPCache.txt"
[ "$PRESET_VALIDATION_CODE" = FORBIDDEN_IPCACHE_OPTION ] || fail "ipcache rejection is not typed"

RUNTIME_TARGET="$TMP/runtime.ini"
sh "$ROOT/zapret2/scripts/runtime-init.sh" "$RUNTIME_TARGET" || fail "runtime initialization failed"
grep -Fxq 'active_preset=Default v1 (game filter).txt' "$RUNTIME_TARGET" ||
    fail "runtime default preset is not Default v1"
assert_not_contains "$RUNTIME_TARGET" 'preset_mode='
assert_not_contains "$RUNTIME_TARGET" 'strategy_preset='
assert_not_contains "$RUNTIME_TARGET" 'ports_tcp='
assert_not_contains "$RUNTIME_TARGET" 'ports_udp='

assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'compile_preset_artifact'
assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'run_compiled_artifact'
assert_not_contains "$ROOT/zapret2/scripts/zapret-start.sh" '@config'
assert_not_contains "$ROOT/zapret2/scripts/command-builder.sh" 'eval '

Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/full-rollback.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/purge-contract.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-safety.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/marker-occurrence.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/wal-validator.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/owner-generation.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/owner-state-v6.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/boot-recovery.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-lock-owner.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/package-owner-protocol.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/update-cleanup-v2.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/transactional-start.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/detached-build.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/magisk-boot-installer.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/packaging-recovery-flow.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/preset-contract.sh"

echo "Shell integration tests passed"
