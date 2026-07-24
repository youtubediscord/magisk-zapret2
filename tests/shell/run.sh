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
assert_unsafe_machine_root --list-presets-machine Z2_PRESET_ERROR
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

# Process, firewall, owner, status, and recovery evidence in STATE_DIR is
# boot-local. A global sync here flushes unrelated Android filesystems and
# turns the number of firewall journal transitions into boot latency.
for script in "$ROOT/zapret2/scripts/common.sh" "$ROOT/zapret2/scripts/zapret-start.sh"; do
    if sed '/^[[:space:]]*#/d' "$script" |
       grep -Eq '(^|[;&|[:space:]])sync([;&|[:space:]]|$)'; then
        fail "boot-local lifecycle script invokes global sync: $script"
    fi
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

PRESET_LIST="$TMP/presets.list"
sh "$ROOT/zapret2/scripts/command-builder.sh" --list-presets-machine "$ROOT/zapret2" > "$PRESET_LIST" ||
    fail "trusted preset catalog could not be listed"
grep -Fxq "Z2_PRESET_SUMMARY$(printf '\t')2$(printf '\t')ready=$PRESET_TOTAL$(printf '\t')quarantined=0$(printf '\t')total=$PRESET_TOTAL" "$PRESET_LIST" ||
    fail "trusted preset list does not match the packaged catalog"

# These read-only commands run while the app's shared root-command gate is
# occupied. They must remain shell-builtin parsers rather than spawning one
# grep process for every catalog section, preset name, or dependency.
NO_GREP_BIN="$TMP/no-grep-bin"
mkdir -p "$NO_GREP_BIN"
printf '%s\n' '#!/bin/sh' 'exit 99' > "$NO_GREP_BIN/grep"
chmod 0755 "$NO_GREP_BIN/grep"
NO_GREP_STRATEGY_OUTPUT="$(PATH="$NO_GREP_BIN:$PATH" sh \
    "$ROOT/zapret2/scripts/command-builder.sh" --validate-strategies-machine "$ROOT/zapret2")" ||
    fail "strategy validation still depends on per-section grep processes"
[ "$NO_GREP_STRATEGY_OUTPUT" = "$STRATEGY_OUTPUT" ] || fail "fork-free strategy output changed"
NO_GREP_SCAN="$TMP/presets.no-grep.scan"
PATH="$NO_GREP_BIN:$PATH" sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --scan-presets-machine "$ROOT/zapret2" > "$NO_GREP_SCAN" ||
    fail "preset scan still depends on per-preset grep processes"
cmp "$PRESET_SCAN" "$NO_GREP_SCAN" >/dev/null || fail "fork-free preset scan output changed"
NO_GREP_LIST="$TMP/presets.no-grep.list"
PATH="$NO_GREP_BIN:$PATH" sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --list-presets-machine "$ROOT/zapret2" > "$NO_GREP_LIST" ||
    fail "trusted preset list depends on deep validation tools"
cmp "$PRESET_LIST" "$NO_GREP_LIST" >/dev/null || fail "fork-free preset list output changed"

FIXTURE="$TMP/compiler"
cp -R "$ROOT/zapret2" "$FIXTURE"
cat > "$FIXTURE/nfqws2" <<'EOF'
#!/bin/sh
# Preview is a pure compiler operation. Binary capability checks and dry-runs
# belong to the separate preflight/start boundary.
exit 97
EOF
chmod 0755 "$FIXTURE/nfqws2"
cat > "$FIXTURE/presets/TCP only.txt" <<'EOF'
# NFQWS2_TCP_PKT_OUT=20
# NFQWS2_TCP_PKT_IN=10
# NFQWS2_UDP_PKT_OUT=20
# NFQWS2_UDP_PKT_IN=10

--lua-init=@lua/zapret-lib.lua
--blob=zero:0x00

--name=TCP only
--filter-tcp=80
--lua-desync=pass
EOF
cat > "$FIXTURE/presets/UDP only.txt" <<'EOF'
# NFQWS2_TCP_PKT_OUT=20
# NFQWS2_TCP_PKT_IN=10
# NFQWS2_UDP_PKT_OUT=7
# NFQWS2_UDP_PKT_IN=3

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
chmod 0700 "$STATE_DIR"
. "$FIXTURE/scripts/common.sh"
. "$FIXTURE/scripts/command-builder.sh"
QNUM=200 DESYNC_MARK=0x40000000 NFQWS_UID=0:0 LOG_MODE=none

compile_preset_artifact "$FIXTURE/presets/TCP only.txt" "TCP only.txt" "$TMP/tcp.argv" ||
    fail "TCP-only preset did not compile"
read_compiled_artifact_metadata "$TMP/tcp.argv" || fail "TCP artifact metadata is invalid"
[ "$COMPILED_TCP_PORTS" = 80 ] && [ -z "$COMPILED_UDP_PORTS" ] &&
    [ "$COMPILED_TCP_PKT_OUT:$COMPILED_TCP_PKT_IN:$COMPILED_UDP_PKT_OUT:$COMPILED_UDP_PKT_IN" = 20:10:20:10 ] ||
    fail "TCP-only preset opened unexpected ports"
(
    compile_preset_artifact() { return 97; }
    ensure_compiled_artifact "$FIXTURE/presets/TCP only.txt" "TCP only.txt" "$TMP/tcp.argv"
) || fail "unchanged compiled preset was rebuilt instead of reusing its bound artifact"
sed '/--filter-tcp=80/a --filter-tcp=443' "$FIXTURE/presets/TCP only.txt" > "$FIXTURE/presets/Multi filter.txt"
compile_preset_artifact "$FIXTURE/presets/Multi filter.txt" "Multi filter.txt" "$TMP/multi-filter.argv" ||
    fail "multiple filters in one profile did not compile"
read_compiled_artifact_metadata "$TMP/multi-filter.argv" || fail "multi-filter metadata is invalid"
[ "$COMPILED_TCP_PORTS" = 80,443 ] && [ -z "$COMPILED_UDP_PORTS" ] ||
    fail "fork-free capture parser dropped a repeated protocol filter"
cp "$TMP/tcp.argv" "$TMP/tampered-ipcache.argv"
printf '%s\n' '--ipcache-hostname=1' >> "$TMP/tampered-ipcache.argv"
read_compiled_artifact_metadata "$TMP/tampered-ipcache.argv" || fail "tampered artifact fixture is structurally invalid"
assert_fails run_compiled_artifact "$TMP/tampered-ipcache.argv" dry-run

compile_preset_artifact "$FIXTURE/presets/UDP only.txt" "UDP only.txt" "$TMP/udp.argv" ||
    fail "UDP-only preset did not compile"
read_compiled_artifact_metadata "$TMP/udp.argv" || fail "UDP artifact metadata is invalid"
[ -z "$COMPILED_TCP_PORTS" ] && [ "$COMPILED_UDP_PORTS" = 443,3478,5349,19302 ] ||
    fail "voice UDP union is not exact"
[ "$COMPILED_TCP_PKT_OUT:$COMPILED_TCP_PKT_IN:$COMPILED_UDP_PKT_OUT:$COMPILED_UDP_PKT_IN" = 20:10:7:3 ] ||
    fail "protocol-specific capture policy was not preserved"
grep -Fxq -- '--name=Discord voice' "$TMP/udp.argv" || fail "argument with spaces was split"

PREVIEW_OUTPUT="$TMP/preview.out"
if ! STATE_DIR="$STATE_DIR" sh "$FIXTURE/scripts/command-builder.sh" \
    --preview-preset-machine "$FIXTURE" "$FIXTURE/presets/TCP only.txt" "TCP only.txt" \
    > "$PREVIEW_OUTPUT" 2>&1; then
    sed -n '1,80p' "$PREVIEW_OUTPUT" >&2
    fail "command preview rejected a valid unsaved candidate"
fi
grep -Fxq "Z2_COMMAND_PREVIEW$(printf '\t')2$(printf '\t')TCP only.txt$(printf '\t')TCP=80$(printf '\t')UDP=$(printf '\t')TCP_OUT=20$(printf '\t')TCP_IN=10$(printf '\t')UDP_OUT=20$(printf '\t')UDP_IN=10" \
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

assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'ensure_compiled_artifact'
assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'run_compiled_artifact'
assert_not_contains "$ROOT/zapret2/scripts/zapret-start.sh" '@config'
assert_not_contains "$ROOT/zapret2/scripts/command-builder.sh" 'eval '

Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/full-rollback.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/error-contract.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/purge-contract.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-safety.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/owner-generation.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/owner-state-v8.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/boot-recovery.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-lock-owner.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-status-v4.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-status-v5.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/lifecycle-status-v6.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/status-snapshot-fast-path.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/package-owner-protocol.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/runtime-config-contract.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/release-generation.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/transactional-start.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/firewall-reconciler.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/magisk-boot-installer.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/packaging-recovery-flow.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/preset-contract.sh"

echo "Shell integration tests passed"
