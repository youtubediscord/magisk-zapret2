#!/bin/sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd -P)"
TMP_ROOT="${Z2_TEST_TMP:-$(mktemp -d)}"
OWN_TMP=0
if [ -z "${Z2_TEST_TMP:-}" ]; then OWN_TMP=1; fi
cleanup() { [ "$OWN_TMP" -eq 0 ] || rm -rf "$TMP_ROOT"; }
trap cleanup EXIT HUP INT TERM

fail() { echo "preset-contract: $*" >&2; exit 1; }
assert_invalid_code() {
    expected="$1"; shift
    output="$TMP_ROOT/preset-contract-output"
    if "$@" > "$output" 2>&1; then fail "accepted invalid preset; expected $expected"; fi
    grep -Fq "$expected" "$output" || fail "wrong validation reason; expected $expected"
}

[ "$(grep -c '^preset-compatible|0644|' "$ROOT/zapret2/runtime-manifest.tsv")" -eq 98 ] || fail "compatible manifest count"
[ "$(grep -c '^preset-quarantined|0644|' "$ROOT/zapret2/runtime-manifest.tsv")" -eq 0 ] || fail "quarantined manifest count"
if grep -R -q -- '--lua-init=@lua/custom_diag.lua' "$ROOT/zapret2/presets"; then fail "custom_diag reference remains"; fi
[ "$(grep -Rl -- '--lua-init=@lua/fakemultisplit.lua' "$ROOT/zapret2/presets" | grep -v '/_' | wc -l)" -eq 98 ] || fail "fakemultisplit missing from a preset"
[ "$(grep -Rl -- '--lua-init=@lua/fakemultidisorder.lua' "$ROOT/zapret2/presets" | grep -v '/_' | wc -l)" -eq 98 ] || fail "fakemultidisorder missing from a preset"
if grep -R -q -- '^--wf-' "$ROOT/zapret2/presets"; then fail "WinDivert filter option remains"; fi
if grep -R -q -- '^--in-range=' "$ROOT/zapret2/presets"; then fail "inbound range remains"; fi
if grep -R -q -- '^--lua-desync=circular:' "$ROOT/zapret2/presets"; then fail "circular strategy remains"; fi
if grep -R -q -- '--ipcache' "$ROOT/zapret2/presets"; then fail "forbidden ipcache option remains"; fi
if grep -R -q -- 'russia-youtube-ipset.txt' "$ROOT/zapret2/presets"; then fail "stale russia-youtube ipset name remains"; fi
for preset in "$ROOT"/zapret2/presets/*.txt; do
    case "${preset##*/}" in
        _*) ;;
        *)
            awk '
                /^--new$/ {
                    if (names != 1) exit 1
                    names=0
                    next
                }
                /^--name=/ {
                    if ($0 == "--name=") exit 1
                    names++
                }
                END { if (names != 1) exit 1 }
            ' "$preset" || fail "profile without exactly one name: $preset"
            ;;
    esac
    awk '
        /^[[:space:]]*$/ { blanks++; if (blanks > 1) exit 1; next }
        { blanks=0 }
    ' "$preset" || fail "consecutive blank lines: $preset"
done
grep -Fxq -- '--name=youtube.com (интерфейс)' \
    "$ROOT/zapret2/presets/Default v1 (game filter).txt" || fail "default profile names not restored"
common_blob_hash=
for preset in "$ROOT"/zapret2/presets/*.txt; do
    case "${preset##*/}" in _*) continue ;; esac
    [ "$(grep -c '^--blob=' "$preset")" -eq 68 ] || fail "incomplete common blob block: $preset"
    blob_hash="$(grep '^--blob=' "$preset" | sha256sum | awk '{print $1}')"
    if [ -z "$common_blob_hash" ]; then
        common_blob_hash="$blob_hash"
    else
        [ "$blob_hash" = "$common_blob_hash" ] || fail "different common blob block: $preset"
    fi
done

import_source="$TMP_ROOT/import-source"
import_destination="$TMP_ROOT/import-package/presets"
mkdir -p "$import_source" "$import_destination"
printf '%s\n' \
    '--lua-init=@lua/fakemultisplit.lua' \
    '--lua-init=@lua/fakemultidisorder.lua' \
    '--name=Профиль с пробелом' \
    '--skip' \
    '--ipcache-hostname=0' \
    '--ipset=lists/russia-youtube-rtmps.txt' \
    '--lua-desync=pass' > "$import_source/Fixture.txt"
printf '%s\n' \
    '--blob=a:0x00' \
    '--name=Blob A' \
    '--filter-tcp=443' \
    '--lua-desync=fake:blob=a' > "$import_source/BlobA.txt"
printf '%s\n' \
    '--blob=b:0x01' \
    '--name=Blob B' \
    '--filter-udp=443' \
    '--lua-desync=fake:blob=b' > "$import_source/BlobB.txt"
python3 "$ROOT/zapret2/scripts/sync-winws2-presets.py" \
    "$import_source" --destination "$import_destination" >/dev/null
grep -Fxq -- '--name=Профиль с пробелом' "$import_destination/Fixture.txt" || fail "importer removed profile name"
grep -Fxq -- '--skip' "$import_destination/Fixture.txt" || fail "importer removed profile skip"
grep -Fxq -- '--lua-init=@lua/fakemultisplit.lua' "$import_destination/Fixture.txt" || fail "importer removed Android fakemultisplit"
grep -Fxq -- '--lua-init=@lua/fakemultidisorder.lua' "$import_destination/Fixture.txt" || fail "importer removed Android fakemultidisorder"
if grep -Fq -- '--ipcache' "$import_destination/Fixture.txt"; then fail "importer preserved ipcache"; fi
grep -Fxq -- '--ipset=lists/ipset-russia-youtube-rtmps.txt' \
    "$import_destination/Fixture.txt" || fail "importer did not normalize Android list name"
for imported_preset in "$import_destination"/*.txt; do
    grep -Fxq -- '--blob=a:0x00' "$imported_preset" || fail "importer did not propagate common blob a"
    grep -Fxq -- '--blob=b:0x01' "$imported_preset" || fail "importer did not propagate common blob b"
done
conflict_source="$TMP_ROOT/import-conflict"
conflict_destination="$TMP_ROOT/import-conflict-package/presets"
mkdir -p "$conflict_source" "$conflict_destination"
printf '%s\n' '--blob=same:0x00' '--name=One' '--filter-tcp=443' '--lua-desync=pass' > "$conflict_source/One.txt"
printf '%s\n' '--blob=same:0x01' '--name=Two' '--filter-tcp=443' '--lua-desync=pass' > "$conflict_source/Two.txt"
if python3 "$ROOT/zapret2/scripts/sync-winws2-presets.py" \
    "$conflict_source" --destination "$conflict_destination" >/dev/null 2>&1; then
    fail "importer accepted conflicting common blob definitions"
fi

scan="$TMP_ROOT/repository-preset-scan"
sh "$ROOT/zapret2/scripts/command-builder.sh" --scan-presets-machine "$ROOT/zapret2" > "$scan"
[ "$(awk -F '\t' '$1 == "Z2_PRESET" && $2 == "VALID" { n++ } END { print n+0 }' "$scan")" -eq 98 ] || fail "scanner valid count"
[ "$(awk -F '\t' '$1 == "Z2_PRESET" && $2 == "QUARANTINED" { n++ } END { print n+0 }' "$scan")" -eq 0 ] || fail "scanner quarantined count"
grep -Fq 'valid=98' "$scan" || fail "scanner summary valid count"
grep -Fq 'quarantined=0' "$scan" || fail "scanner summary quarantined count"
grep -Fq 'total=98' "$scan" || fail "scanner summary total count"

. "$ROOT/zapret2/scripts/package-contract.sh"
package_contract_validate_manifest "$ROOT" || fail "manifest invalid: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
# The checkout belongs to the CI runner, while machine catalog validation
# intentionally accepts only root-owned installed files. The complete catalog
# is therefore validated below against the assembled root-owned package tree,
# not against source-control ownership metadata.
utf8_two_byte="$(printf '\303\251')"
utf8_safe_name=
utf8_name_index=0
while [ "$utf8_name_index" -lt 125 ]; do
    utf8_safe_name="${utf8_safe_name}${utf8_two_byte}"
    utf8_name_index=$((utf8_name_index + 1))
done
package_contract_safe_preset_name "a${utf8_safe_name}.txt" || fail "255-byte preset name rejected"
for unsafe_preset_name in \
    "${utf8_safe_name}${utf8_two_byte}.txt" 'UPPER.TXT' '_internal.txt' 'bad"name.txt' "bad'name.txt"
do
    if package_contract_safe_preset_name "$unsafe_preset_name"; then
        fail "unsafe package preset name accepted: $unsafe_preset_name"
    fi
done
if package_contract_safe_relative_path "zapret2/${utf8_safe_name}${utf8_two_byte}xxxx"; then
    fail "overlong manifest path component accepted"
fi

candidate_root="$TMP_ROOT/preset-candidate-root"
mkdir -p "$candidate_root/presets" "$candidate_root/lua" "$candidate_root/bin" "$candidate_root/lists"
printf 'lua\n' > "$candidate_root/lua/core.lua"
printf 'blob\n' > "$candidate_root/bin/blob.bin"
printf 'example.com\n' > "$candidate_root/lists/list.txt"
candidate="$candidate_root/presets/_Safe.candidate.1.txt"
write_valid_candidate() {
    printf '%s\n' \
        '--lua-init=@lua/core.lua' \
        '--blob=x:@bin/blob.bin' \
        '--name=Safe profile' \
        '--filter-tcp=443' \
        '--hostlist=lists/list.txt' \
        '--lua-desync=pass' > "$candidate"
}
validate_candidate() {
    sh "$ROOT/zapret2/scripts/command-builder.sh" --validate-preset-machine "$candidate_root" "$candidate" 'Safe.txt'
}
write_valid_candidate
validate_candidate | grep -Fq 'Z2_PRESET_VALIDATION' || fail "valid candidate rejected"

printf '%s\n' '--ipcache-hostname=0' > "$candidate"
assert_invalid_code FORBIDDEN_IPCACHE_OPTION validate_candidate
printf '%s\n' '--ipcache-lifetime=1' > "$candidate"
assert_invalid_code FORBIDDEN_IPCACHE_OPTION validate_candidate
write_valid_candidate
sed -i 's/--lua-desync=pass/--lua-desync=fake:blob=missing/' "$candidate"
assert_invalid_code BLOB_REFERENCE_MISSING validate_candidate
write_valid_candidate
sed -i 's/--lua-desync=pass/--lua-desync=fake:blob=fake_default_tls/' "$candidate"
validate_candidate >/dev/null || fail "built-in nfqws blob was rejected"
printf '%s\n' '--lua-init=@lua/../core.lua' > "$candidate"
assert_invalid_code UNSAFE_DEPENDENCY_PATH validate_candidate
printf '%s\n' '--lua-init=@/absolute.lua' > "$candidate"
assert_invalid_code UNSAFE_DEPENDENCY_PATH validate_candidate
printf '%s\n' '--lua-init=@lua/missing.lua' > "$candidate"
assert_invalid_code DEPENDENCY_MISSING validate_candidate
: > "$candidate_root/lua/empty.lua"
printf '%s\n' '--lua-init=@lua/empty.lua' > "$candidate"
assert_invalid_code DEPENDENCY_EMPTY validate_candidate
rm -f "$candidate_root/lua/empty.lua"
ln -s core.lua "$candidate_root/lua/link.lua"
printf '%s\n' '--lua-init=@lua/link.lua' > "$candidate"
assert_invalid_code DEPENDENCY_SYMLINK validate_candidate
rm -f "$candidate_root/lua/link.lua"
cp "$candidate_root/lua/core.lua" "$candidate_root/lua/unreadable.lua"
chmod 000 "$candidate_root/lua/unreadable.lua"
if [ ! -r "$candidate_root/lua/unreadable.lua" ]; then
    printf '%s\n' '--lua-init=@lua/unreadable.lua' > "$candidate"
    assert_invalid_code DEPENDENCY_UNREADABLE validate_candidate
fi
chmod 0644 "$candidate_root/lua/unreadable.lua"
: > "$candidate"
assert_invalid_code PRESET_EMPTY validate_candidate
write_valid_candidate
assert_invalid_code PRESET_NOT_DIRECT_CHILD sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --validate-preset-machine "$candidate_root" "$candidate_root/nested/Safe.txt" 'Safe.txt'
assert_invalid_code UNSAFE_PRESET_NAME sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --validate-preset-machine "$candidate_root" "$candidate" '../Safe.txt'
assert_invalid_code UNSAFE_PRESET_NAME sh "$ROOT/zapret2/scripts/command-builder.sh" \
    --validate-preset-machine "$candidate_root" "$candidate" "${utf8_safe_name}${utf8_two_byte}.txt"

declared="$TMP_ROOT/declared-dependencies"
printf '%s\n' 'zapret2/lua/core.lua' > "$declared"
write_valid_candidate
PRESET_ALLOWED_DEPENDENCIES_FILE="$declared"
export PRESET_ALLOWED_DEPENDENCIES_FILE
assert_invalid_code DEPENDENCY_NOT_DECLARED validate_candidate
unset PRESET_ALLOWED_DEPENDENCIES_FILE

source_root="$TMP_ROOT/manifest-source"
package_root="$TMP_ROOT/manifest-package"
mkdir -p "$source_root" "$package_root"
cp "$ROOT/module.prop" "$ROOT/customize.sh" "$ROOT/service.sh" "$ROOT/uninstall.sh" "$ROOT/action.sh" "$source_root/"
cp -R "$ROOT/system" "$ROOT/zapret2" "$source_root/"
printf '%064d\n' 0 > "$source_root/zapret2/upstream-zapret2.commit"
mkdir -p "$source_root/zapret2/bin/arm64-v8a" "$source_root/zapret2/bin/armeabi-v7a"
cp /bin/true "$source_root/zapret2/bin/arm64-v8a/nfqws2"
cp /bin/true "$source_root/zapret2/bin/armeabi-v7a/nfqws2"
package_contract_assemble_package "$source_root" "$package_root" || fail "fixture assembly: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_all "$package_root" package || fail "fixture package: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_exact_tree "$package_root" package || fail "fixture exact tree: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_modes "$package_root" package || fail "fixture modes: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
cp "$package_root/zapret2/runtime-manifest.tsv" "$TMP_ROOT/runtime-manifest.good"
printf '%s\n' 'immutable-file|0644|zapret2' >> "$package_root/zapret2/runtime-manifest.tsv"
if package_contract_validate_manifest "$package_root"; then fail "manifest file/child collision accepted"; fi
[ "$PACKAGE_CONTRACT_CODE" = MANIFEST_PATH_COLLISION ] || fail "wrong manifest collision failure: $PACKAGE_CONTRACT_CODE"
cp "$TMP_ROOT/runtime-manifest.good" "$package_root/zapret2/runtime-manifest.tsv"

installed_root="$TMP_ROOT/installed-runtime-selection"
cp -R "$package_root" "$installed_root"
rm -f "$installed_root/customize.sh"
cp "$installed_root/zapret2/bin/arm64-v8a/nfqws2" "$installed_root/zapret2/nfqws2"
chmod 0755 "$installed_root/zapret2/nfqws2"
custom_preset="$installed_root/zapret2/presets/My custom.txt"
cp "$installed_root/zapret2/presets/Default v1 (game filter).txt" "$custom_preset"
printf '%s\n' '# user-owned preset' >> "$custom_preset"
chmod 0644 "$custom_preset"
sed -i 's/^active_preset=.*/active_preset=My custom.txt/' "$installed_root/zapret2/runtime.ini"
package_contract_validate_tree "$installed_root" installed || fail "configured custom preset rejected: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_catalog "$installed_root" || fail "valid custom preset rejected: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
package_contract_validate_exact_tree "$installed_root" installed || fail "custom preset omitted from installed closure: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
cp "$custom_preset" "$TMP_ROOT/custom-preset.good"
printf '%s\n' '--ipcache-hostname=1' > "$custom_preset"
if package_contract_validate_catalog "$installed_root"; then fail "invalid custom preset accepted"; fi
[ "$PACKAGE_CONTRACT_CODE" = PRESET_INVALID ] || fail "wrong custom preset failure: $PACKAGE_CONTRACT_CODE"
cp "$TMP_ROOT/custom-preset.good" "$custom_preset"
rm -f "$custom_preset"
if package_contract_validate_tree "$installed_root" installed; then fail "missing active preset accepted"; fi
[ "$PACKAGE_CONTRACT_CODE" = RUNTIME_PRESET_UNSAFE ] || fail "wrong active preset failure: $PACKAGE_CONTRACT_CODE"
cp "$package_root/zapret2/scripts/command-builder.sh" "$TMP_ROOT/command-builder.good"
printf '\r\n' >> "$package_root/zapret2/scripts/command-builder.sh"
if package_contract_validate_all "$package_root" package; then fail "CRLF shell executable accepted"; fi
[ "$PACKAGE_CONTRACT_CODE" = SHELL_EXEC_CR ] || fail "wrong shell executable failure: $PACKAGE_CONTRACT_CODE"
cp "$TMP_ROOT/command-builder.good" "$package_root/zapret2/scripts/command-builder.sh"

if command -v zip >/dev/null 2>&1 && command -v zipinfo >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
    archive="$TMP_ROOT/manifest-package.zip"
    (cd "$package_root" && zip -qr "$archive" module.prop customize.sh service.sh uninstall.sh action.sh system zapret2)
    names="$TMP_ROOT/manifest-package.names"
    zipinfo -1 "$archive" > "$names"
    package_contract_validate_zip_names "$package_root" "$names" || fail "fixture ZIP names: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    printf '%s\n' 'META-INF' 'META-INF/child' >> "$names"
    if package_contract_validate_zip_names "$package_root" "$names"; then fail "ZIP file/child collision accepted"; fi
    [ "$PACKAGE_CONTRACT_CODE" = ZIP_PATH_COLLISION ] || fail "wrong ZIP collision failure: $PACKAGE_CONTRACT_CODE"
    zipinfo -1 "$archive" > "$names"
    printf '%s\n' 'META-INF/child' 'META-INF' >> "$names"
    if package_contract_validate_zip_names "$package_root" "$names"; then fail "reverse-order ZIP file/child collision accepted"; fi
    [ "$PACKAGE_CONTRACT_CODE" = ZIP_PATH_COLLISION ] || fail "wrong reverse ZIP collision failure: $PACKAGE_CONTRACT_CODE"
    zipinfo -1 "$archive" > "$names"
    printf '%s\n' 'META-INF/' 'META-INF/' >> "$names"
    if package_contract_validate_zip_names "$package_root" "$names"; then fail "duplicate ZIP directory accepted"; fi
    [ "$PACKAGE_CONTRACT_CODE" = ZIP_DUPLICATE_ENTRY ] || fail "wrong duplicate ZIP failure: $PACKAGE_CONTRACT_CODE"
    zipinfo -1 "$archive" > "$names"
    printf '%s\n' rogue > "$package_root/undeclared.txt"
    (cd "$package_root" && zip -q "$archive" undeclared.txt)
    zipinfo -1 "$archive" > "$names"
    if package_contract_validate_zip_names "$package_root" "$names"; then fail "undeclared ZIP entry accepted"; fi
    zip -qd "$archive" undeclared.txt
    rm -f "$package_root/undeclared.txt"
    zipinfo -1 "$archive" > "$names"
    extracted="$TMP_ROOT/manifest-package-extracted"
    mkdir -p "$extracted"
    unzip -q "$archive" -d "$extracted"
    package_contract_validate_all "$extracted" package || fail "fixture ZIP content: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    package_contract_validate_exact_tree "$extracted" package || fail "fixture ZIP exact tree: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
    printf '%s\n' rogue > "$extracted/zapret2/undeclared.txt"
    if package_contract_validate_exact_tree "$extracted" package; then fail "undeclared extracted entry accepted"; fi
    rm -f "$extracted/zapret2/undeclared.txt"
    package_contract_validate_modes "$extracted" package || fail "fixture ZIP modes: $PACKAGE_CONTRACT_CODE $PACKAGE_CONTRACT_DETAIL"
fi

rm -f "$package_root/zapret2/lua/zapret-auto.lua"
if package_contract_validate_tree "$package_root" package; then fail "missing zapret-auto accepted"; fi

echo "Preset and runtime manifest contract tests passed"
