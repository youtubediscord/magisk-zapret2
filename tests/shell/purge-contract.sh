#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
CONTRACT="$ROOT/zapret2/scripts/lifecycle/purge-contract.sh"
PURGE="$ROOT/zapret2/scripts/lifecycle/zapret-purge.sh"
ACTION="$ROOT/action.sh"

fail() { echo "FAIL: purge-contract: $*" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "missing contract fragment: $2"; }

[ -f "$CONTRACT" ] && [ ! -L "$CONTRACT" ] && [ -x "$CONTRACT" ] ||
    fail "purge contract is not a regular executable"
[ -f "$PURGE" ] && [ ! -L "$PURGE" ] && [ -x "$PURGE" ] ||
    fail "purge entry is not a regular executable"
[ "$(sed -n '1p' "$CONTRACT")" = '#!/system/bin/sh' ] || fail "contract shebang"
[ "$(sed -n '1p' "$PURGE")" = '#!/system/bin/sh' ] || fail "purge shebang"

# Pure allowlist/token helpers can be exercised without touching Android paths.
STATE_DIR=/data/adb/zapret2-state
is_valid_boot_id() { case "$1" in ""|*[!A-Za-z0-9._-]*) return 1 ;; *) return 0 ;; esac; }
read_current_boot_id() { CURRENT_BOOT_ID=test-boot; }
. "$CONTRACT"

z2_purge_is_safe_token app.1234.safe-token || fail "safe token rejected"
if z2_purge_is_safe_token '../escape'; then fail "unsafe token accepted"; fi
z2_purge_managed_tree_path /data/adb/modules/zapret2 || fail "canonical module path rejected"
z2_purge_managed_tree_path /data/adb/modules_update/zapret2 || fail "pending module path rejected"
z2_purge_managed_tree_path /data/adb/zapret2-recovery.test || fail "recovery workspace rejected"
if z2_purge_managed_tree_path /data/adb/modules/zapret2-copy; then
    fail "module prefix was accepted as the canonical module"
fi
if z2_purge_managed_tree_path /data/adb/zapret2-install.test/escape; then
    fail "nested path was accepted as an installer workspace"
fi
if z2_purge_managed_tree_path /data/adb/modules/.zapret2-recovery-../escape; then
    fail "unsafe recovery workspace suffix was accepted"
fi
if z2_purge_managed_tree_path /data/adb/modules; then fail "broad module root accepted"; fi
if z2_purge_managed_tree_path /data/adb; then fail "broad adb root accepted"; fi

# The irreversible path is one implementation shared by APK and Magisk, and
# the APK-preservation bit is produced by the script rather than trusted input.
assert_contains "$ACTION" 'zapret2/scripts/lifecycle/zapret-purge.sh'
assert_contains "$ACTION" 'exec /system/bin/sh "$PURGE_SCRIPT" --magisk-action'
assert_contains "$PURGE" 'Z2_PURGE_APK_TOUCHED=0'
assert_contains "$PURGE" 'remove_request_if_exact ||'
assert_contains "$PURGE" 'publish_remove_marker ||'
assert_contains "$PURGE" '/system/bin/sh "$UNINSTALL_SCRIPT"'
assert_contains "$PURGE" 'z2_purge_remove_external_workspaces'
assert_contains "$PURGE" 'z2_purge_remove_legacy_files'
assert_contains "$PURGE" 'z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_PENDING_DIR"'
assert_contains "$PURGE" 'z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_MODULE_DIR"'
assert_contains "$PURGE" 'z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_STATE_DIR"'
assert_contains "$PURGE" 'Z2_PURGE_REBOOT_REQUIRED=%s'
assert_contains "$PURGE" 'pm clear --user "$user" "$package"'

consume_line=$(grep -nF 'remove_request_if_exact ||' "$PURGE" | head -n 1 | cut -d: -f1)
marker_line=$(grep -nF 'publish_remove_marker ||' "$PURGE" | head -n 1 | cut -d: -f1)
module_line=$(grep -nF 'z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_MODULE_DIR"' "$PURGE" | head -n 1 | cut -d: -f1)
state_line=$(grep -nF 'z2_purge_remove_managed_tree "$Z2_PURGE_CANONICAL_STATE_DIR"' "$PURGE" | head -n 1 | cut -d: -f1)
uninstall_line=$(grep -nF '/system/bin/sh "$UNINSTALL_SCRIPT"' "$PURGE" | head -n 1 | cut -d: -f1)
[ "$consume_line" -lt "$uninstall_line" ] || fail "one-time request is not consumed before uninstall"
[ "$uninstall_line" -lt "$marker_line" ] || fail "removal marker is published before verified uninstall"
[ "$module_line" -lt "$state_line" ] || fail "state directory is not the final managed tree removed"

grep -Fq '"$PURGE_REQUEST"' "$ROOT/uninstall.sh" ||
    fail "normal uninstall does not retire an abandoned purge request"

echo "Purge contract shell tests passed"
