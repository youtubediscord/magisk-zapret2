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
    local operation="$1" expected="$2" output rc
    if output="$(sh "$ROOT/zapret2/scripts/command-builder.sh" "$operation" relative-root 2>&1)"; then
        fail "$operation accepted a relative machine root"
    else
        rc=$?
    fi
    [ "$rc" -eq 2 ] || fail "$operation unsafe-root rejection did not exit 2"
    [ "$output" = "$(printf '%s\tUNSAFE_ROOT' "$expected")" ] ||
        fail "$operation unsafe-root record is not protocol-specific"
}

[ "$(id -u)" = 0 ] || fail "run as root so root-owner/0600 migration checks are real"

assert_unsafe_machine_root --scan-presets-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --validate-preset-machine Z2_PRESET_ERROR
assert_unsafe_machine_root --validate-categories-machine Z2_CATEGORIES_ERROR
assert_unsafe_machine_root --validate-strategies-machine Z2_STRATEGIES_ERROR
assert_unsafe_machine_root --validate-cmdline-machine Z2_CMDLINE_ERROR

for script in "$ROOT"/*.sh "$ROOT"/zapret2/scripts/*.sh "$ROOT"/tests/shell/*.sh; do
    sh -n "$script" || fail "syntax: $script"
done

CATEGORY_FIXTURE="$TMP/category-zapret"
cp -R "$ROOT/zapret2" "$CATEGORY_FIXTURE"
CATEGORY_OUTPUT="$(sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE")" ||
    fail "valid category configuration was rejected"
[ "$CATEGORY_OUTPUT" = "$(printf 'Z2_CATEGORIES\tOK')" ] || fail "category validator output is not exact"
STRATEGY_OUTPUT="$(sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-strategies-machine "$CATEGORY_FIXTURE")" ||
    fail "valid strategy catalogs were rejected"
[ "$STRATEGY_OUTPUT" = "$(printf 'Z2_STRATEGIES\tOK')" ] || fail "strategy validator output is not exact"

CMDLINE_MACHINE_FIXTURE="$TMP/cmdline-machine"
cp -R "$ROOT/zapret2" "$CMDLINE_MACHINE_FIXTURE"
cat > "$CMDLINE_MACHINE_FIXTURE/nfqws2" <<'EOF'
#!/bin/sh
[ "${1:-}" = --dry-run ] || exit 2
case " $* " in *' --filter-tcp=bad '*) exit 1 ;; esac
exit 0
EOF
chmod 0755 "$CMDLINE_MACHINE_FIXTURE/nfqws2"
printf '%s\n' '--filter-tcp=443' > "$CMDLINE_MACHINE_FIXTURE/Valid.txt"
CMDLINE_MACHINE_OK="$(sh "$CMDLINE_MACHINE_FIXTURE/scripts/command-builder.sh" \
    --validate-cmdline-machine "$CMDLINE_MACHINE_FIXTURE" Valid.txt)" ||
    fail "valid custom cmdline machine validation failed"
[ "$CMDLINE_MACHINE_OK" = "$(printf 'Z2_CMDLINE\t1\tOK\tValid.txt')" ] ||
    fail "custom cmdline OK output is not exact"
printf '%s\n' '--filter-tcp=bad' > "$CMDLINE_MACHINE_FIXTURE/Invalid.txt"
if CMDLINE_MACHINE_INVALID="$(sh "$CMDLINE_MACHINE_FIXTURE/scripts/command-builder.sh" \
    --validate-cmdline-machine "$CMDLINE_MACHINE_FIXTURE" Invalid.txt)"; then
    fail "nfqws2 dry-run rejection was accepted"
else
    CMDLINE_MACHINE_RC=$?
fi
[ "$CMDLINE_MACHINE_RC" -eq 1 ] || fail "custom cmdline rejection did not exit 1"
[ "$CMDLINE_MACHINE_INVALID" = "$(printf 'Z2_CMDLINE\t1\tINVALID\tInvalid.txt')" ] ||
    fail "custom cmdline INVALID output is not exact"
chmod 0644 "$CMDLINE_MACHINE_FIXTURE/nfqws2"
if CMDLINE_MACHINE_ERROR="$(sh "$CMDLINE_MACHINE_FIXTURE/scripts/command-builder.sh" \
    --validate-cmdline-machine "$CMDLINE_MACHINE_FIXTURE" Valid.txt)"; then
    fail "unsafe nfqws2 binary mode was accepted"
else
    CMDLINE_MACHINE_RC=$?
fi
[ "$CMDLINE_MACHINE_RC" -eq 2 ] || fail "custom cmdline infrastructure error did not exit 2"
[ "$CMDLINE_MACHINE_ERROR" = "$(printf 'Z2_CMDLINE_ERROR\tBINARY_UNAVAILABLE')" ] ||
    fail "custom cmdline infrastructure error output is not exact"

cp "$CATEGORY_FIXTURE/strategies-tcp.ini" "$TMP/strategies-tcp.valid"
printf '%s\n' '[default]' 'args=--lua-desync=duplicate' >> "$CATEGORY_FIXTURE/strategies-tcp.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-strategies-machine "$CATEGORY_FIXTURE"
cp "$TMP/strategies-tcp.valid" "$CATEGORY_FIXTURE/strategies-tcp.ini"
sed '0,/^args=/s/^args=.*/args=/' "$TMP/strategies-tcp.valid" > "$CATEGORY_FIXTURE/strategies-tcp.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-strategies-machine "$CATEGORY_FIXTURE"
cp "$TMP/strategies-tcp.valid" "$CATEGORY_FIXTURE/strategies-tcp.ini"
cp "$CATEGORY_FIXTURE/categories.ini" "$TMP/categories.valid"
sed 's/^strategy=.*/strategy=disabled/' "$TMP/categories.valid" > "$CATEGORY_FIXTURE/categories.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE"
cp "$TMP/categories.valid" "$CATEGORY_FIXTURE/categories.ini"
awk 'BEGIN { removed=0 } !removed && /^protocol=/ { removed=1; next } { print }' \
    "$TMP/categories.valid" > "$CATEGORY_FIXTURE/categories.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE"
cp "$TMP/categories.valid" "$CATEGORY_FIXTURE/categories.ini"
awk 'BEGIN { removed=0 } !removed && /^filter_mode=/ { removed=1; next } { print }' \
    "$TMP/categories.valid" > "$CATEGORY_FIXTURE/categories.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE"
cp "$TMP/categories.valid" "$CATEGORY_FIXTURE/categories.ini"
sed '0,/^strategy=[^d]/s//strategy=missing_strategy/' "$TMP/categories.valid" > "$CATEGORY_FIXTURE/categories.ini"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE"
cp "$TMP/categories.valid" "$CATEGORY_FIXTURE/categories.ini"
rm -f "$CATEGORY_FIXTURE/lists/youtube.txt"
assert_fails sh "$CATEGORY_FIXTURE/scripts/command-builder.sh" --validate-categories-machine "$CATEGORY_FIXTURE"

RECOVERY_UPDATE_BINARY="$ROOT/META-INF/com/google/android/update-binary"
NORMALIZED_UPDATE_BINARY="$TMP/update-binary.lf"
tr -d '\r' < "$RECOVERY_UPDATE_BINARY" > "$NORMALIZED_UPDATE_BINARY"
[ "$(sed -n '1p' "$NORMALIZED_UPDATE_BINARY")" = '#!/sbin/sh' ] || fail "normalized recovery update-binary shebang is invalid"
if LC_ALL=C grep -q "$(printf '\r')" "$NORMALIZED_UPDATE_BINARY"; then fail "recovery update-binary normalization retained CR bytes"; fi

MIGRATE="$ROOT/zapret2/scripts/runtime-migrate.sh"
CONFIG="$TMP/config.sh"
USER_CONFIG="$TMP/user.conf"
OUTPUT="$TMP/runtime.ini"
CATEGORIES="$TMP/categories.ini"

cat > "$CONFIG" <<'EOF'
#!/system/bin/sh
AUTOSTART=1
WIFI_ONLY=1
QNUM=1
PRESET_MODE=file
PRESET_FILE="Preset File.txt"
CUSTOM_CMDLINE_FILE='Custom Options.txt'
EOF
cat > "$USER_CONFIG" <<'EOF'
QNUM=65535
LOG_MODE=none
EOF
: > "$CATEGORIES"
chmod 0600 "$CONFIG" "$USER_CONFIG" "$CATEGORIES"

sh "$MIGRATE" --config "$CONFIG" --user-config "$USER_CONFIG" --categories "$CATEGORIES" "$OUTPUT" >/dev/null
assert_contains "$OUTPUT" 'qnum=65535'
assert_contains "$OUTPUT" 'wifi_only=0'
assert_contains "$OUTPUT" 'preset_file=Preset File.txt'
assert_contains "$OUTPUT" 'custom_cmdline_file=Custom Options.txt'
[ "$(stat -c '%a' "$OUTPUT")" = 644 ] || fail "runtime.ini mode is not 0644"
[ -z "$(find "$TMP" -maxdepth 1 -name '.runtime.ini.*' -print -quit)" ] || fail "migration temp file leaked"

DEFAULTS_ONLY="$TMP/defaults-only.ini"
cat > "$DEFAULTS_ONLY" <<'EOF'
[core]
schema_version=1
config_format=runtime-v1
runtime_source=partial
qnum=333
[dns_manager]
selected=kept
EOF
sh "$MIGRATE" --defaults-only --config "$CONFIG" --user-config "$USER_CONFIG" "$DEFAULTS_ONLY" >/dev/null
assert_contains "$DEFAULTS_ONLY" 'qnum=333'
assert_contains "$DEFAULTS_ONLY" 'ports_tcp=80,443'
assert_contains "$DEFAULTS_ONLY" 'ports_udp=443'
assert_contains "$DEFAULTS_ONLY" 'strategy_preset=syndata_multisplit_tls_google_700'
assert_contains "$DEFAULTS_ONLY" 'runtime_source=self-healed-defaults'
assert_contains "$DEFAULTS_ONLY" '[dns_manager]'
assert_contains "$DEFAULTS_ONLY" 'selected=kept'
if grep -Fq 'qnum=65535' "$DEFAULTS_ONLY"; then fail "defaults-only self-heal revived bootstrap qnum"; fi

UNSAFE_RUNTIME_TARGET="$TMP/unsafe-runtime-target.ini"
UNSAFE_RUNTIME_LINK="$TMP/unsafe-runtime-link.ini"
printf '%s\n' 'sentinel=unchanged' > "$UNSAFE_RUNTIME_TARGET"
ln -s "$UNSAFE_RUNTIME_TARGET" "$UNSAFE_RUNTIME_LINK"
assert_fails sh "$MIGRATE" --defaults-only "$UNSAFE_RUNTIME_LINK"
assert_contains "$UNSAFE_RUNTIME_TARGET" 'sentinel=unchanged'
chmod 0666 "$UNSAFE_RUNTIME_TARGET"
assert_fails sh "$MIGRATE" --defaults-only "$UNSAFE_RUNTIME_TARGET"
chmod 0644 "$UNSAFE_RUNTIME_TARGET"

PWNED="$TMP/executed"
cat > "$USER_CONFIG" <<EOF
QNUM=\$(touch "$PWNED")
EOF
chmod 0600 "$USER_CONFIG"
assert_fails sh "$MIGRATE" --config "$CONFIG" --user-config "$USER_CONFIG" "$TMP/rejected.ini"
[ ! -e "$PWNED" ] || fail "bootstrap content was executed"

printf '%s\n' 'QNUM=200' > "$USER_CONFIG"
chmod 0644 "$USER_CONFIG"
assert_fails sh "$MIGRATE" --config "$CONFIG" --user-config "$USER_CONFIG" "$TMP/insecure.ini"
chmod 0600 "$USER_CONFIG"
ln -s "$USER_CONFIG" "$TMP/user-link.conf"
assert_fails sh "$MIGRATE" --config "$CONFIG" --user-config "$TMP/user-link.conf" "$TMP/symlink.ini"

SCRIPT_DIR="$ROOT/zapret2/scripts"
ZAPRET_DIR="$ROOT/zapret2"
MODDIR="$ROOT"
. "$ROOT/zapret2/scripts/common.sh"

PARTIAL_RUNTIME="$TMP/partial-runtime.ini"
cat > "$PARTIAL_RUNTIME" <<'EOF'
[core]
schema_version=1
config_format=runtime-v1
runtime_source=partial
qnum=333
[strategy_order]
tcp=kept
EOF
SAVED_RUNTIME_CONFIG="$RUNTIME_CONFIG"
SAVED_CONFIG="$CONFIG"
SAVED_USER_CONFIG="$USER_CONFIG"
LEGACY_BOOTSTRAP="$TMP/legacy-bootstrap.sh"
printf '%s\n' 'QNUM=65535' > "$LEGACY_BOOTSTRAP"
RUNTIME_CONFIG="$PARTIAL_RUNTIME"
CONFIG="$LEGACY_BOOTSTRAP"
USER_CONFIG="$TMP/does-not-exist.conf"
ensure_runtime_core_config || fail "partial runtime.ini did not self-heal"
assert_contains "$PARTIAL_RUNTIME" 'qnum=333'
assert_contains "$PARTIAL_RUNTIME" 'autostart=1'
assert_contains "$PARTIAL_RUNTIME" '[strategy_order]'
assert_contains "$PARTIAL_RUNTIME" 'tcp=kept'
[ "$RUNTIME_CONFIG_STATUS" = regenerated ] || fail "partial runtime.ini was not classified as regenerated"
RUNTIME_CONFIG="$SAVED_RUNTIME_CONFIG"
CONFIG="$SAVED_CONFIG"
USER_CONFIG="$SAVED_USER_CONFIG"

RUNTIME_CONFIG="$TMP/missing-runtime.ini"
CONFIG="$LEGACY_BOOTSTRAP"
load_effective_core_config_readonly >/dev/null 2>&1 && fail "read-only config path revived missing runtime from bootstrap"
[ ! -e "$RUNTIME_CONFIG" ] || fail "read-only config path mutated a missing runtime"
RUNTIME_CONFIG="$SAVED_RUNTIME_CONFIG"
CONFIG="$SAVED_CONFIG"

normalize_qnum 1 && [ "$QNUM_NORMALIZED" = 1 ] || fail "QNUM=1 rejected"
normalize_qnum 65535 && [ "$QNUM_NORMALIZED" = 65535 ] || fail "QNUM=65535 rejected"
assert_fails normalize_qnum 0
assert_fails normalize_qnum 65536
assert_fails normalize_qnum -1

canonical_mark 0 && [ "$MARK_CANONICAL" = 0x0 ] || fail "zero netfilter mark rejected"
canonical_mark 4294967295 && [ "$MARK_CANONICAL" = 0xffffffff ] || fail "maximum uint32 netfilter mark rejected"
assert_fails canonical_mark 4294967296
assert_fails canonical_mark -1

WIFI_ONLY=1
normalize_unsupported_wifi_only || fail "legacy WIFI_ONLY=1 normalization failed"
[ "$WIFI_ONLY" = 0 ] && [ "$WIFI_ONLY_LEGACY_NORMALIZED" = 1 ] || fail "legacy WIFI_ONLY=1 was not normalized"
WIFI_ONLY=invalid
assert_fails normalize_unsupported_wifi_only

decode_config_value '"Preset File.txt"' || fail "quoted filename decode failed"
[ "$CONFIG_VALUE_DECODED" = 'Preset File.txt' ] || fail "quoted filename decoded incorrectly"
apply_core_config_key preset_file "$CONFIG_VALUE_DECODED" || fail "safe preset filename rejected"
UTF8_TWO_BYTE="$(printf '\303\251')"
UTF8_SAFE_NAME=
UTF8_NAME_INDEX=0
while [ "$UTF8_NAME_INDEX" -lt 125 ]; do
    UTF8_SAFE_NAME="${UTF8_SAFE_NAME}${UTF8_TWO_BYTE}"
    UTF8_NAME_INDEX=$((UTF8_NAME_INDEX + 1))
done
apply_core_config_key preset_file "a${UTF8_SAFE_NAME}.txt" || fail "255-byte filename boundary rejected"
assert_fails apply_core_config_key preset_file "${UTF8_SAFE_NAME}${UTF8_TWO_BYTE}.txt"
decode_config_value '1' && [ "$CONFIG_VALUE_DECODED" = 1 ] || fail "non-empty unquoted scalar decode failed"
assert_fails decode_config_value "line1
line2"
assert_fails apply_core_config_key preset_file 'bad\name.txt'
assert_fails apply_core_config_key preset_file 'bad"name.txt'
assert_fails apply_core_config_key preset_file ' leading-space.txt'
assert_fails decode_config_value '"unmatched.txt'
assert_fails decode_config_value '"'

INVALID_CORE_KEY_RUNTIME="$TMP/invalid-core-key.ini"
cp "$ROOT/zapret2/runtime.ini" "$INVALID_CORE_KEY_RUNTIME"
printf '%s\n' 'poison|autostart=1' >> "$INVALID_CORE_KEY_RUNTIME"
RUNTIME_CONFIG="$INVALID_CORE_KEY_RUNTIME"
set_core_config_defaults
assert_fails apply_runtime_core_overrides
RUNTIME_CONFIG="$SAVED_RUNTIME_CONFIG"

log_msg() { :; }
log_error() { :; }
log_debug() { :; }
. "$ROOT/zapret2/scripts/command-builder.sh"

ZAPRET_DIR="$TMP/module"
PRESETS_DIR="$ZAPRET_DIR/presets"
LISTS_DIR="$ZAPRET_DIR/lists"
mkdir -p "$PRESETS_DIR" "$LISTS_DIR"
printf '%s\n' '--new' > "$PRESETS_DIR/Preset File.txt"
PRESET_FILE='Preset File.txt'
OPTS=''
build_preset_file_options || fail "preset path with spaces failed"
[ "$OPTS" = ' --new' ] || fail "preset options unexpected: $OPTS"

printf '%s\n' '--new' '--filter-tcp=443' > "$ZAPRET_DIR/Custom Options.txt"
CUSTOM_CMDLINE_FILE='Custom Options.txt'
OPTS=''
build_validated_cmdline_options || fail "validated cmdline rejected safe input"
case "$OPTS" in *'--filter-tcp=443'*) ;; *) fail "validated cmdline option missing" ;; esac
chmod 0600 "$ZAPRET_DIR/Custom Options.txt"
OPTS=''
build_validated_cmdline_options || fail "validated cmdline rejected safe root-only mode"
printf '%s\n' '--filter-tcp=443;touch=/tmp/no' > "$ZAPRET_DIR/Unsafe.txt"
CUSTOM_CMDLINE_FILE='Unsafe.txt'
assert_fails build_validated_cmdline_options
printf '%s\n' 'example.org' > "$LISTS_DIR/Referenced.txt"
printf '%s\n' '--hostlist=Referenced.txt' > "$ZAPRET_DIR/Relative.txt"
CUSTOM_CMDLINE_FILE='Relative.txt'
OPTS=''
build_validated_cmdline_options || fail "safe relative cmdline file reference was rejected"
case "$OPTS" in *"--hostlist=$LISTS_DIR/Referenced.txt"*) ;; *) fail "relative cmdline file reference was not normalized" ;; esac
printf '%s\n' '--hostlist=' > "$ZAPRET_DIR/Empty Reference.txt"
CUSTOM_CMDLINE_FILE='Empty Reference.txt'
assert_fails build_validated_cmdline_options
printf '%s\n' '--hostlist=../Referenced.txt' > "$ZAPRET_DIR/Traversal.txt"
CUSTOM_CMDLINE_FILE='Traversal.txt'
assert_fails build_validated_cmdline_options
printf '%s\n' 'example.org' > "$ZAPRET_DIR/Outside.txt"
printf '%s\n' "--hostlist=$ZAPRET_DIR/Outside.txt" > "$ZAPRET_DIR/Outside Reference.txt"
CUSTOM_CMDLINE_FILE='Outside Reference.txt'
assert_fails build_validated_cmdline_options
printf '%s\n' '--new' > "$ZAPRET_DIR/Writable.txt"
chmod 0666 "$ZAPRET_DIR/Writable.txt"
CUSTOM_CMDLINE_FILE='Writable.txt'
assert_fails build_validated_cmdline_options
printf '%s\n' '--new' > "$ZAPRET_DIR/Hardlinked.txt"
ln "$ZAPRET_DIR/Hardlinked.txt" "$ZAPRET_DIR/Hardlinked.alias"
CUSTOM_CMDLINE_FILE='Hardlinked.txt'
assert_fails build_validated_cmdline_options
head -c 262145 /dev/zero | tr '\000' a > "$ZAPRET_DIR/Oversized.txt"
chmod 0644 "$ZAPRET_DIR/Oversized.txt"
CUSTOM_CMDLINE_FILE='Oversized.txt'
assert_fails build_validated_cmdline_options

assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'capture_prior_healthy_generation'
assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'restore_prior_healthy_generation'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'shell_config_sets_key()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'recognized_owned_chain_rule_count()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'teardown_record_exists()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'delete_teardown_record()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'owned_family_healthy()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'legacy_direct_qnum_present()'
assert_not_contains "$ROOT/zapret2/scripts/common.sh" 'expected_owned_rule_exists()'
assert_not_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'log_legacy_conflict()'
assert_not_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'prior_family_is_healthy()'
assert_not_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'prior_chain_uses_exact_queue()'
assert_contains "$ROOT/zapret2/scripts/zapret-stop.sh" 'update_lock_allows_stop'
assert_contains "$ROOT/customize.sh" 'Refusing ZIP with unexpected module id'
assert_contains "$ROOT/customize.sh" 'Module ZIP has no nfqws2 binary'
assert_contains "$ROOT/customize.sh" 'Module extraction failed'
assert_contains "$ROOT/customize.sh" 'COMMIT_HANDOFF=1'
assert_contains "$ROOT/customize.sh" 'quiesce_live_module_before_publish'
assert_contains "$ROOT/customize.sh" 'snapshot_live_service_state'
assert_contains "$ROOT/customize.sh" 'restore_live_service_after_rollback'
assert_contains "$ROOT/customize.sh" 'normalize_staged_legacy_wifi_only'
assert_contains "$ROOT/customize.sh" 'zapret2/scripts/zapret-update-guard.sh'
assert_contains "$ROOT/customize.sh" 'package_contract_validate_exact_tree "$STAGE_PATH" package'
assert_contains "$ROOT/build.sh" "UPDATE_GUARD_ENTRY='zapret2/scripts/zapret-update-guard.sh'"
assert_contains "$ROOT/build.sh" 'package_contract_assemble_package "$PWD" "$PACKAGE_ASSEMBLY_ROOT"'
assert_contains "$ROOT/build.sh" 'package_contract_validate_exact_tree "$PACKAGE_VALIDATE_ROOT" package allow-meta'
assert_contains "$ROOT/build.sh" 'VERSION_CODE="${BASH_REMATCH[1]}"'
assert_contains "$ROOT/build.sh" '10#$VERSION_CODE > 2100000000'
if grep -Fq "tr -cd '0-9'" "$ROOT/build.sh"; then
    fail "local builder derives versionCode by concatenating unrelated version segments"
fi
assert_contains "$ROOT/.github/workflows/build.yml" "UPDATE_GUARD_ENTRY='zapret2/scripts/zapret-update-guard.sh'"
assert_contains "$ROOT/.github/workflows/build.yml" 'package_contract_assemble_package "$PWD" "$PACKAGE_ASSEMBLY_ROOT"'
assert_contains "$ROOT/.github/workflows/build.yml" 'package_contract_validate_exact_tree "$PACKAGE_VALIDATE_ROOT" package allow-meta'
assert_contains "$ROOT/customize.sh" 'Published module action entry is not executable'
assert_contains "$ROOT/action.sh" 'APP_COMPONENT="$APP_PACKAGE/.MainActivity"'
assert_contains "$ROOT/action.sh" 'zapret2-full-rollback'
if grep -Eqi 'KSUWebUI|WebUI' "$ROOT/action.sh" "$ROOT/customize.sh" "$ROOT/module.prop"; then
    fail "retired module WebUI instructions remain"
fi
assert_contains "$ROOT/customize.sh" 'for user_lua in zapret-custom.lua init_vars.lua'
assert_contains "$ROOT/customize.sh" 'MAX_PRESERVED_USER_LUA_BYTES=262144'
assert_contains "$ROOT/customize.sh" 'MAX_PRESERVED_COMMAND_LINE_BYTES=262144'
assert_contains "$ROOT/customize.sh" 'read_configured_cmdline_name'
assert_contains "$ROOT/customize.sh" 'Configured command line preserved'
assert_contains "$ROOT/customize.sh" 'case "$cmdline_mode" in 600|644)'
assert_contains "$ROOT/customize.sh" 'stat -c %a "$target"'
assert_contains "$ROOT/customize.sh" 'Refusing unsafe preserved Lua file'
assert_contains "$ROOT/customize.sh" 'Preserved Lua file exceeds 256 KiB'
assert_contains "$ROOT/customize.sh" 'The staged release'"'"'s core'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'refusing an implicit global fallback'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'is missing or unsafe'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'load_effective_core_config_readonly'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" '"$NFQWS2" --dry-run $built_options'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'if [ "$COMMAND_BUILDER_CLI_MODE" -eq 0 ]; then'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'Z2_CMDLINE_ERROR\tRUNTIME_UNAVAILABLE'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'Z2_CMDLINE_ERROR\tBINARY_UNAVAILABLE'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'Z2_CMDLINE_ERROR\tINTEGRITY_UNAVAILABLE'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'command -v sha256sum'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" 'command -v awk'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" '--validate-categories-machine'
assert_contains "$ROOT/zapret2/scripts/command-builder.sh" '--validate-strategies-machine'
assert_contains "$ROOT/customize.sh" '--validate-categories-machine'
is_canonical_nfqws_id 0 || fail "root nfqws UID rejected"
is_canonical_nfqws_id 2147483647 || fail "maximum nfqws UID rejected"
if is_canonical_nfqws_id 01 || is_canonical_nfqws_id 2147483648; then
    fail "noncanonical or overflowing nfqws UID accepted"
fi
if grep -Fq 'falling back to [default]' "$ROOT/zapret2/scripts/command-builder.sh"; then
    fail "category builder still silently substitutes a missing strategy"
fi
if grep -Fq 'for user_lua in zapret-lib.lua' "$ROOT/customize.sh"; then
    fail "installer preservation allowlist contains core Lua"
fi
assert_contains "$ROOT/build.sh" 'tr -d '\''\r'\'' < "$RECOVERY_UPDATE_BINARY"'
assert_contains "$ROOT/.github/workflows/build.yml" 'needs.fetch-upstream.outputs.sha'
assert_contains "$ROOT/.github/workflows/build.yml" 'zapret2/upstream-zapret2.commit'
assert_contains "$ROOT/upstream/fetch-release.sh" '$API_ROOT/releases/latest'
assert_contains "$ROOT/upstream/fetch-release.sh" 'verify_asset_digest "$ARCHIVE_DIGEST"'
assert_contains "$ROOT/upstream/android-binaries.tsv" 'arm64-v8a'
assert_contains "$ROOT/upstream/android-binaries.tsv" 'armeabi-v7a'
assert_contains "$ROOT/.github/workflows/build.yml" 'APK_SIGNING_CERT_SHA256'
assert_contains "$ROOT/.github/workflows/build.yml" '"$APKSIGNER" verify --verbose --print-certs'
assert_contains "$ROOT/.github/workflows/build.yml" 'sha256sum -c'
assert_contains "$ROOT/android-app/gradle/wrapper/gradle-wrapper.properties" 'distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06'
if grep -Eq 'uses: [^#[:space:]]+@v[0-9]' "$ROOT/.github/workflows/build.yml"; then
    fail "workflow contains a mutable major-version action reference"
fi
if grep -Eq 'wget|git ls-remote' "$ROOT/.github/workflows/build.yml"; then
    fail "workflow contains an unverified download"
fi
assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" 'module is disabled; re-enable it in the root manager first'
assert_contains "$ROOT/zapret2/scripts/zapret-start.sh" '--repair-runtime-only'
assert_contains "$ROOT/service.sh" '"$START_SCRIPT" --repair-runtime-only'
assert_contains "$ROOT/zapret2/scripts/zapret-full-rollback.sh" 'Z2_RB_REBOOT_REQUIRED=1'

make_installer_zip() {
    fixture="$1" module_id="$2" archive="$3"
    assembly="$fixture-assembled"
    mkdir -p "$fixture" "$assembly"
    cp "$ROOT/module.prop" "$ROOT/customize.sh" "$ROOT/service.sh" "$ROOT/uninstall.sh" "$ROOT/action.sh" "$fixture/"
    cp -R "$ROOT/system" "$ROOT/zapret2" "$fixture/"
    sed "s/^id=.*/id=$module_id/" "$ROOT/module.prop" > "$fixture/module.prop"
    mkdir -p "$fixture/zapret2/bin/armeabi-v7a"
    cp "$fixture/zapret2/bin/arm64-v8a/nfqws2" "$fixture/zapret2/bin/armeabi-v7a/nfqws2"
    (
        . "$fixture/zapret2/scripts/package-contract.sh"
        package_contract_assemble_package "$fixture" "$assembly"
    )
    (cd "$assembly" && zip -qr "$archive" module.prop customize.sh service.sh uninstall.sh action.sh system zapret2)
}

assert_wrapper_package() {
    archive="$1"
    expected="$TMP/wrapper.expected"
    actual="$TMP/wrapper.actual"
    listing="$TMP/wrapper.list"
    zipinfo -1 "$archive" > "$listing"
    grep -Fxq 'system/bin/' "$listing" || fail "wrapper directory missing from $archive"
    [ "$(grep -Fxc 'action.sh' "$listing")" -eq 1 ] || fail "action.sh is missing or duplicated"
    zipinfo -l "$archive" action.sh | grep -Eq '^-rwxr-xr-x[[:space:]]' || fail "action.sh is not regular 0755"
    [ "$(grep -Fxc 'zapret2/scripts/zapret-update-guard.sh' "$listing")" -eq 1 ] || fail "update guard is missing or duplicated"
    zipinfo -l "$archive" zapret2/scripts/zapret-update-guard.sh | grep -Eq '^-rwxr-xr-x[[:space:]]' || fail "update guard is not regular 0755"
    unzip -p "$archive" zapret2/scripts/zapret-update-guard.sh > "$actual"
    [ -s "$actual" ] || fail "update guard is empty"
    [ "$(sed -n '1p' "$actual")" = '#!/system/bin/sh' ] || fail "update guard shebang is invalid"
    [ "$(grep -Fxc 'zapret2/scripts/zapret-full-rollback.sh' "$listing")" -eq 1 ] || fail "rollback script is missing or duplicated"
    zipinfo -l "$archive" zapret2/scripts/zapret-full-rollback.sh | grep -Eq '^-rwxr-xr-x[[:space:]]' || fail "rollback script is not regular 0755"
    for command_name in start stop status restart full-rollback; do
        entry="system/bin/zapret2-$command_name"
        [ "$(grep -Fxc "$entry" "$listing")" -eq 1 ] || fail "wrapper entry is missing or duplicated: $entry"
        zipinfo -l "$archive" "$entry" | grep -Eq '^-rwxr-xr-x[[:space:]]' || fail "wrapper entry is not regular 0755: $entry"
        {
            printf '%s\n' '#!/system/bin/sh'
            printf 'exec /data/adb/modules/zapret2/zapret2/scripts/zapret-%s.sh "$@"\n' "$command_name"
        } > "$expected"
        unzip -p "$archive" "$entry" > "$actual"
        cmp -s "$expected" "$actual" || fail "wrapper bytes differ: $entry"
    done
}

SERVICE_GATE="$TMP/service-gate"
mkdir -p "$SERVICE_GATE"
cp "$ROOT/service.sh" "$SERVICE_GATE/service.sh"
: > "$SERVICE_GATE/disable"
sh "$SERVICE_GATE/service.sh" >/dev/null 2>&1 || fail "regular disable marker did not skip boot cleanly"
rm -f "$SERVICE_GATE/disable"
ln -s missing-disable-target "$SERVICE_GATE/disable"
if sh "$SERVICE_GATE/service.sh" >/dev/null 2>&1; then fail "symlink disable marker did not fail closed"; fi
rm -f "$SERVICE_GATE/disable"
mkdir "$SERVICE_GATE/disable"
if sh "$SERVICE_GATE/service.sh" >/dev/null 2>&1; then fail "special disable marker did not fail closed"; fi
rmdir "$SERVICE_GATE/disable"

run_customize_stubbed() {
    archive="$1" abi="$2"
    (
        MODPATH=/data/adb/modules_update/zapret2
        ZIPFILE="$archive"
        abort() { echo "$*" >&2; exit 1; }
        ui_print() { :; }
        getprop() { printf '%s\n' "$abi"; }
        set_perm() { return 0; }
        mktemp() {
            case "${1:-}" in
                -d)
                    case "${2:-}" in
                        *recovery*) suffix=recovery ;;
                        *) suffix=install ;;
                    esac
                    work="$TMP/installer-work-$abi-$suffix"
                    mkdir "$work"
                    printf '%s\n' "$work"
                    ;;
                *) command mktemp "$@" ;;
            esac
        }
        . "$ROOT/customize.sh"
    )
}

BAD_ID_ZIP="$TMP/bad-id.zip"
make_installer_zip "$TMP/bad-id" wrong.module "$BAD_ID_ZIP"
assert_wrapper_package "$BAD_ID_ZIP"
if run_customize_stubbed "$BAD_ID_ZIP" arm64-v8a >"$TMP/bad-id.log" 2>&1; then fail "installer accepted wrong module identity"; fi
assert_contains "$TMP/bad-id.log" 'unexpected module id'

BAD_ABI_ZIP="$TMP/bad-abi.zip"
make_installer_zip "$TMP/bad-abi" zapret2 "$BAD_ABI_ZIP"
assert_wrapper_package "$BAD_ABI_ZIP"
for bad_abi in mips64 x86 x86_64 arm64-custom armeabi-v7a-custom; do
    bad_abi_log="$TMP/bad-abi.$bad_abi.log"
    if run_customize_stubbed "$BAD_ABI_ZIP" "$bad_abi" >"$bad_abi_log" 2>&1; then
        fail "installer accepted unsupported ABI: $bad_abi"
    fi
    assert_contains "$bad_abi_log" 'Unsupported architecture'
done

MISSING_GUARD_ZIP="$TMP/missing-guard.zip"
make_installer_zip "$TMP/missing-guard" zapret2 "$MISSING_GUARD_ZIP"
zip -qd "$MISSING_GUARD_ZIP" zapret2/scripts/zapret-update-guard.sh
if run_customize_stubbed "$MISSING_GUARD_ZIP" arm64-v8a >"$TMP/missing-guard.log" 2>&1; then
    fail "installer accepted a package without the update guard"
fi
assert_contains "$TMP/missing-guard.log" 'zapret2/scripts/zapret-update-guard.sh'

Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/full-rollback.sh"
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
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/packaging-recovery-flow.sh"
Z2_TEST_TMP="$TMP" sh "$ROOT/tests/shell/preset-contract.sh"

echo "Shell integration tests passed"
