#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/package-owner-protocol"
mkdir -p "$CASE/zapret2/scripts"
cp "$ROOT/zapret2/runtime-manifest.tsv" "$CASE/zapret2/runtime-manifest.tsv"
cp "$ROOT/zapret2/scripts/package-contract.sh" "$CASE/zapret2/scripts/package-contract.sh"
. "$CASE/zapret2/scripts/package-contract.sh"
fail() { echo "FAIL: package-owner-protocol: $*" >&2; exit 1; }

GOOD="$CASE/manifest.good"
cp "$CASE/zapret2/runtime-manifest.tsv" "$GOOD"
package_contract_validate_manifest "$CASE" || fail "valid owner protocol rejected: $PACKAGE_CONTRACT_CODE"
sed 's/$/\r/' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
package_contract_validate_manifest "$CASE" || fail "CRLF manifest rejected: $PACKAGE_CONTRACT_CODE"

assert_rejected() {
    label="$1"
    if package_contract_validate_manifest "$CASE"; then fail "$label manifest was accepted"; fi
}

cp "$GOOD" "$CASE/zapret2/runtime-manifest.tsv"
dd if=/dev/zero bs=1024 count=257 2>/dev/null >> "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected oversized
[ "$PACKAGE_CONTRACT_CODE" = MANIFEST_TOO_LARGE ] || fail "oversized manifest reported $PACKAGE_CONTRACT_CODE"
sed '/^owner_protocol|/d' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected missing
sed '/^installed-exec|0755|zapret2\/nfqws2$/d' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected missing-installed-binary-contract
sed '/^immutable-file|0644|zapret2\/upstream-zapret2\.release$/d' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected missing-upstream-release
sed '/^immutable-file|0644|zapret2\/upstream-zapret2\.archive\.sha256$/d' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected missing-upstream-archive-digest
{ cat "$GOOD"; printf '%s\n' 'installed-exec|0755|zapret2/generated-helper'; } > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected arbitrary-installed-executable
{ cat "$GOOD"; printf 'immutable-file|0644|zapret2/tab\tname\n'; } > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected control-character-path
sed 's/^owner_protocol|7|/owner_protocol|6|/' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected legacy-v5
sed 's/^owner_protocol|7|zapret2-firewall$/owner_protocol|7|other/' "$GOOD" > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected mismatch
{ sed -n '2p' "$GOOD"; sed -n '1p' "$GOOD"; sed -n '3,$p' "$GOOD"; } > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected reordered
{ cat "$GOOD"; sed -n '2p' "$GOOD"; } > "$CASE/zapret2/runtime-manifest.tsv"
assert_rejected duplicate

GOOD_PROP="$CASE/module.prop.good"
cp "$ROOT/module.prop" "$GOOD_PROP"
cp "$GOOD_PROP" "$CASE/module.prop"
package_contract_validate_module_prop "$CASE" || fail "valid module.prop rejected: $PACKAGE_CONTRACT_CODE"
sed 's/^\(version=v[0-9][0-9.]*\)$/\1-dev.20260724123456.deadbeef/' \
    "$GOOD_PROP" > "$CASE/module.prop"
package_contract_validate_module_prop "$CASE" ||
    fail "valid dev module.prop rejected: $PACKAGE_CONTRACT_CODE"
assert_module_prop_rejected() {
    label="$1"
    if package_contract_validate_module_prop "$CASE"; then fail "$label module.prop was accepted"; fi
}
sed 's/^\(version=v[0-9][0-9.]*\)$/\1-beta.20260724123456.deadbeef/' \
    "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected foreign-prerelease-channel
sed 's/^\(version=v[0-9][0-9.]*\)$/\1-dev.2026072412345.deadbeef/' \
    "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected malformed-dev-timestamp
sed 's/^\(version=v[0-9][0-9.]*\)$/\1-dev.20260724123456.DEADBEEF/' \
    "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected malformed-dev-source
sed 's/^version=v/version=/' "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected noncanonical-version
sed 's/^versionCode=.*/versionCode=999/' "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected mismatched-version-code
{ cat "$GOOD_PROP"; sed -n '/^id=/p' "$GOOD_PROP"; } > "$CASE/module.prop"
assert_module_prop_rejected duplicate-property
sed 's#^updateJson=.*#updateJson=https://example.test/update.json#' "$GOOD_PROP" > "$CASE/module.prop"
assert_module_prop_rejected foreign-update-channel
{ cat "$GOOD_PROP"; printf '%s\n' 'webRoot=webroot'; } > "$CASE/module.prop"
assert_module_prop_rejected retired-webui-root
{ cat "$GOOD_PROP"; printf '\r'; } > "$CASE/module.prop"
assert_module_prop_rejected carriage-return
{ cat "$GOOD_PROP"; printf '\000'; } > "$CASE/module.prop"
assert_module_prop_rejected nul-byte
cp "$GOOD_PROP" "$CASE/module.prop"
dd if=/dev/zero bs=1024 count=5 2>/dev/null >> "$CASE/module.prop"
assert_module_prop_rejected oversized
[ "$PACKAGE_CONTRACT_CODE" = MODULE_PROP_TOO_LARGE ] || fail "oversized module.prop reported $PACKAGE_CONTRACT_CODE"

mkdir -p "$CASE/entrypoints/system/bin"
for command_name in start stop status restart full-rollback; do
    cp "$ROOT/system/bin/zapret2-$command_name" "$CASE/entrypoints/system/bin/zapret2-$command_name"
done
package_contract_validate_entrypoints "$CASE/entrypoints" ||
    fail "valid command entrypoints rejected: $PACKAGE_CONTRACT_CODE"
printf '\n' >> "$CASE/entrypoints/system/bin/zapret2-status"
if package_contract_validate_entrypoints "$CASE/entrypoints"; then
    fail "modified command entrypoint was accepted"
fi
[ "$PACKAGE_CONTRACT_CODE" = PACKAGE_ENTRYPOINT_BYTES ] ||
    fail "modified command entrypoint reported $PACKAGE_CONTRACT_CODE"

echo "Package owner protocol shell tests passed"
