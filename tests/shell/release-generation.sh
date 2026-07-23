#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
CASE="${Z2_TEST_TMP:?}/release-generation"
SOURCE="$CASE/source"
TARGET="$CASE/target"

fail() { echo "FAIL: $*" >&2; exit 1; }

rm -rf "$CASE"
mkdir -p "$SOURCE/zapret2" "$TARGET"
cp "$ROOT/zapret2/runtime-manifest.tsv" "$SOURCE/zapret2/runtime-manifest.tsv"

while IFS='|' read -r class mode path extra || [ -n "$class$mode$path$extra" ]; do
    case "$class" in
        immutable-file|immutable-exec|runtime-dependency-immutable|preset-compatible|preset-quarantined)
            [ "$path" != customize.sh ] || continue
            [ "$path" != zapret2/runtime-manifest.tsv ] || continue
            parent=${path%/*}
            [ "$parent" != "$path" ] || parent=.
            mkdir -p "$SOURCE/$parent"
            if [ "$path" = zapret2/lifecycle-contract.version ]; then
                printf '%s\n' 2 > "$SOURCE/$path"
            else
                printf '%s\n' "release:$path" > "$SOURCE/$path"
            fi
            ;;
        abi-exec)
            for abi in arm64-v8a armeabi-v7a; do
                expanded=$(printf '%s\n' "$path" | sed "s/{abi}/$abi/")
                mkdir -p "$SOURCE/${expanded%/*}"
                printf '%s\n' "release:$expanded" > "$SOURCE/$expanded"
            done
            ;;
    esac
done < "$SOURCE/zapret2/runtime-manifest.tsv"

cp -R "$SOURCE/." "$TARGET"
. "$ROOT/zapret2/scripts/package-contract.sh"

package_contract_compare_release "$SOURCE" "$TARGET" ||
    fail "identical release generations were rejected"
package_contract_compare_release_all "$SOURCE" "$TARGET" ||
    fail "identical exhaustive release generations were rejected"

printf '%s\n' changed > "$TARGET/zapret2/runtime.ini"
package_contract_compare_release "$SOURCE" "$TARGET" ||
    fail "mutable runtime state changed the release identity"

printf '%s\n' mixed-generation > "$TARGET/zapret2/scripts/zapret-status.sh"
if package_contract_compare_release "$SOURCE" "$TARGET" >/dev/null 2>&1; then
    fail "mixed immutable generations were accepted"
fi
cp "$SOURCE/zapret2/scripts/zapret-status.sh" "$TARGET/zapret2/scripts/zapret-status.sh"

printf '%s\n' 1 > "$TARGET/zapret2/lifecycle-contract.version"
if package_contract_compare_release "$SOURCE" "$TARGET" >/dev/null 2>&1; then
    fail "wrong lifecycle contract generation was accepted"
fi

cp "$SOURCE/zapret2/lifecycle-contract.version" "$TARGET/zapret2/lifecycle-contract.version"
printf '%s\n' mixed-generation > "$TARGET/zapret2/lua/zapret-lib.lua"
package_contract_compare_release "$SOURCE" "$TARGET" ||
    fail "runtime publication proof unexpectedly expanded to every release file"
if package_contract_compare_release_all "$SOURCE" "$TARGET" >/dev/null 2>&1; then
    fail "exhaustive release comparison accepted mixed immutable bytes"
fi

echo "Release-generation tests passed"
