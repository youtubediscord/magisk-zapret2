#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
FIXTURES="$ROOT/tests/fixtures/runtime-config"
TMP_ROOT="${Z2_TEST_TMP:-$(mktemp -d "${TMPDIR:-/tmp}/zapret2-runtime-contract.XXXXXX")}"
if [ -z "${Z2_TEST_TMP:-}" ]; then
    trap 'rm -rf "$TMP_ROOT"' EXIT HUP INT TERM
fi

fail() { echo "FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$ROOT/zapret2/scripts"
ZAPRET_DIR="$ROOT/zapret2"
. "$SCRIPT_DIR/common.sh"

# Content fixtures are intentionally portable across unprivileged JVM and
# root-owned device tests. File ownership itself is covered by lifecycle tests.
path_uid_is_root() { return 0; }

classify_runtime_file() {
    RUNTIME_CONFIG="$1"
    set_core_config_defaults
    RUNTIME_CONFIG_ERROR=""
    if apply_runtime_core_overrides; then
        printf '%s\n' VALID
        return 0
    fi
    case "$RUNTIME_CONFIG_ERROR" in
        "unsupported runtime.ini schema_version"|"unsupported runtime.ini config_format")
            printf '%s\n' UNSUPPORTED_SCHEMA
            ;;
        *) printf '%s\n' MALFORMED ;;
    esac
}

if [ "${1:-}" = --file ]; then
    [ "$#" -eq 2 ] || fail "usage: runtime-config-contract.sh --file PATH"
    classify_runtime_file "$2"
    exit 0
fi
if [ "${1:-}" = --values ]; then
    [ "$#" -eq 2 ] || fail "usage: runtime-config-contract.sh --values PATH"
    RUNTIME_CONFIG="$2"
    set_core_config_defaults
    RUNTIME_CONFIG_ERROR=""
    apply_runtime_core_overrides || fail "cannot emit values for an invalid runtime fixture"
    printf 'schema_version\t1\n'
    printf 'config_format\truntime-v1\n'
    printf 'runtime_source\t%s\n' "$RUNTIME_SOURCE"
    printf 'autostart\t%s\n' "$AUTOSTART"
    printf 'wifi_only\t%s\n' "$WIFI_ONLY"
    printf 'debug\t%s\n' "$DEBUG"
    printf 'qnum\t%s\n' "$QNUM"
    printf 'desync_mark\t%s\n' "$DESYNC_MARK"
    printf 'pkt_out\t%s\n' "$PKT_OUT"
    printf 'pkt_in\t%s\n' "$PKT_IN"
    printf 'active_preset\t%s\n' "$ACTIVE_PRESET"
    printf 'nfqws_uid\t%s\n' "$NFQWS_UID"
    printf 'log_mode\t%s\n' "$LOG_MODE"
    exit 0
fi
[ "$#" -eq 0 ] || fail "unexpected arguments"

tab="$(printf '\t')"
while IFS="$tab" read -r fixture expected extra || [ -n "${fixture:-}" ]; do
    case "$fixture" in ""|\#*) continue ;; esac
    [ -z "${extra:-}" ] || fail "invalid fixture manifest row: $fixture"
    path="$FIXTURES/$fixture"
    [ -f "$path" ] || fail "fixture is missing: $fixture"
    actual="$(classify_runtime_file "$path")"
    [ "$actual" = "$expected" ] ||
        fail "$fixture: shell classified $actual, expected $expected"
done < "$FIXTURES/manifest.tsv"

runtime_tool_path=$PATH
if [ "$(id -u)" != 0 ]; then
    mock_bin="$TMP_ROOT/runtime-config-mock-bin"
    mkdir -p "$mock_bin"
    real_stat="$(command -v stat)" || fail "host stat is unavailable"
    cat > "$mock_bin/stat" <<'EOF'
#!/bin/sh
case "$1:$2" in
    -c:%u) printf '%s\n' 0; exit 0 ;;
    -c:%u:%a:%h:%s)
        shift 2
        meta="$("$Z2_REAL_STAT" -c '%a:%h:%s' "$1")" || exit 1
        printf '0:%s\n' "$meta"
        exit 0
        ;;
    -c:%d:%i:%u:%a:%h:%s)
        shift 2
        meta="$("$Z2_REAL_STAT" -c '%d:%i:%a:%h:%s' "$1")" || exit 1
        device="${meta%%:*}"
        rest="${meta#*:}"
        inode="${rest%%:*}"
        rest="${rest#*:}"
        printf '%s:%s:0:%s\n' "$device" "$inode" "$rest"
        exit 0
        ;;
esac
exec "$Z2_REAL_STAT" "$@"
EOF
    chmod 0755 "$mock_bin/stat"
    runtime_tool_path="$mock_bin:$PATH"
    export Z2_REAL_STAT="$real_stat"
fi

repair_target="$TMP_ROOT/runtime-repair/runtime.ini"
mkdir -p "$(dirname "$repair_target")"
cp "$FIXTURES/invalid-missing-key.ini" "$repair_target"
cat >> "$repair_target" <<'EOF'

[dns_manager]
selected_dns=cloudflare|google
EOF
chmod 0644 "$repair_target"
PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --repair "$repair_target" >/dev/null || fail "dedicated runtime repair failed"
[ "$(classify_runtime_file "$repair_target")" = VALID ] ||
    fail "dedicated runtime repair did not produce a valid core"
grep -Fxq '[dns_manager]' "$repair_target" &&
    grep -Fxq 'selected_dns=cloudflare|google' "$repair_target" ||
    fail "dedicated runtime repair discarded a non-core section"
grep -Fxq 'runtime_source=self-healed-runtime' "$repair_target" ||
    fail "repair provenance is not explicit"
inspect_output="$TMP_ROOT/runtime-repair.inspect"
PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --inspect-machine "$repair_target" > "$inspect_output" ||
    fail "runtime inspect machine protocol failed"
grep -Fxq "Z2_ERROR_STATUS=OK" "$inspect_output" ||
    fail "runtime inspect did not emit a successful diagnostic envelope"
grep -Eq '^Z2_RUNTIME_SHA256	[0-9a-f]{64}$' "$inspect_output" ||
    fail "runtime inspect omitted the validated content identity"
grep -Fxq "Z2_RUNTIME_CORE${tab}active_preset${tab}Default v1 (game filter).txt" \
    "$inspect_output" || fail "runtime inspect omitted the active preset"

invalid_target="$TMP_ROOT/runtime-repair/invalid/runtime.ini"
mkdir -p "$(dirname "$invalid_target")"
cp "$FIXTURES/invalid-qnum.ini" "$invalid_target"
chmod 0644 "$invalid_target"
invalid_output="$TMP_ROOT/runtime-repair.invalid.inspect"
PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --inspect-machine "$invalid_target" > "$invalid_output" ||
    fail "invalid runtime inspection command failed"
grep -Fxq "Z2_ERROR_STATUS=ERROR" "$invalid_output" &&
    grep -Fxq "Z2_ERROR_DOMAIN=CONFIG" "$invalid_output" &&
    grep -Fxq "Z2_ERROR_STAGE=RUNTIME_PARSE" "$invalid_output" &&
    grep -Fxq "Z2_ERROR_CODE=INVALID_QNUM" "$invalid_output" ||
    fail "invalid qnum did not produce the generic diagnostic envelope"
detail="$(sed -n 's/^Z2_ERROR_DETAIL=//p' "$invalid_output")"
[ "$detail" = "qnum=70000, expected 1..65535" ] &&
    [ "${#detail}" -le 512 ] ||
    fail "runtime diagnostic detail is empty or unbounded"
case "$detail" in *"
"*|*""*) fail "runtime diagnostic detail is not single-line" ;; esac

commit_target="$TMP_ROOT/runtime-commit/runtime.ini"
mkdir -p "$(dirname "$commit_target")"
cp "$FIXTURES/valid-default.ini" "$commit_target"
chmod 0644 "$commit_target"
valid_candidate="$commit_target.candidate.123.456"
cp "$FIXTURES/valid-quoted-preset.ini" "$valid_candidate"
chmod 0644 "$valid_candidate"
stale_recovery_candidate="$commit_target.candidate.111.222"
cp "$FIXTURES/valid-default.ini" "$stale_recovery_candidate"
chmod 0644 "$stale_recovery_candidate"
commit_digest="$(sha256sum "$commit_target")"
commit_digest="${commit_digest%% *}"
PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --commit-candidate "$valid_candidate" "$commit_digest" "$commit_target" \
    > "$TMP_ROOT/runtime-commit.valid" ||
    fail "valid runtime candidate commit failed"
[ ! -e "$valid_candidate" ] && [ ! -e "$stale_recovery_candidate" ] &&
    [ "$(classify_runtime_file "$commit_target")" = VALID ] ||
    fail "valid runtime candidate was not atomically published"
grep -Fxq "Z2_ERROR_STATUS=OK" "$TMP_ROOT/runtime-commit.valid" ||
    fail "valid runtime commit omitted the successful envelope"

before_invalid_commit="$(sha256sum "$commit_target")"
before_invalid_commit="${before_invalid_commit%% *}"
invalid_candidate="$commit_target.candidate.789.012"
cp "$FIXTURES/invalid-qnum.ini" "$invalid_candidate"
chmod 0644 "$invalid_candidate"
if PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --commit-candidate "$invalid_candidate" "$before_invalid_commit" "$commit_target" \
    > "$TMP_ROOT/runtime-commit.invalid"; then
    fail "invalid runtime candidate commit succeeded"
fi
after_invalid_commit="$(sha256sum "$commit_target")"
after_invalid_commit="${after_invalid_commit%% *}"
[ "$after_invalid_commit" = "$before_invalid_commit" ] ||
    fail "invalid runtime candidate replaced the authoritative runtime"
grep -Fxq "Z2_ERROR_STATUS=ERROR" "$TMP_ROOT/runtime-commit.invalid" &&
    grep -Fxq "Z2_ERROR_STAGE=RUNTIME_COMMIT" "$TMP_ROOT/runtime-commit.invalid" ||
    fail "invalid runtime candidate omitted the failure envelope"
rm -f "$invalid_candidate"

stale_candidate="$commit_target.candidate.345.678"
cp "$FIXTURES/valid-default.ini" "$stale_candidate"
chmod 0644 "$stale_candidate"
if PATH="$runtime_tool_path" sh "$ROOT/zapret2/scripts/runtime-config.sh" \
    --commit-candidate "$stale_candidate" \
    0000000000000000000000000000000000000000000000000000000000000000 \
    "$commit_target" > "$TMP_ROOT/runtime-commit.stale"; then
    fail "stale runtime candidate commit succeeded"
fi
grep -Fxq "Z2_ERROR_CODE=RUNTIME_SOURCE_CHANGED" "$TMP_ROOT/runtime-commit.stale" ||
    fail "stale runtime candidate did not report compare-and-swap failure"
rm -f "$stale_candidate"

init_target="$TMP_ROOT/runtime-init/runtime.ini"
mkdir -p "$(dirname "$init_target")"
sh "$ROOT/zapret2/scripts/runtime-init.sh" "$init_target" >/dev/null ||
    fail "runtime initialization wrapper failed"
[ "$(classify_runtime_file "$init_target")" = VALID ] ||
    fail "runtime initialization wrapper did not produce a valid core"

echo "Runtime config contract tests passed"
