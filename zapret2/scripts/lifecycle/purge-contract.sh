#!/system/bin/sh
# Canonical contract for irreversible Zapret2 module cleanup.

Z2_PURGE_PROTOCOL_VERSION=1
Z2_PURGE_REQUEST_TTL_SECONDS=120
[ -n "${MODDIR:-}" ] || return 1 2>/dev/null || exit 1
Z2_PURGE_MODULES_DIR="${MODDIR%/*}"
Z2_PURGE_STORAGE_DIR="${Z2_PURGE_MODULES_DIR%/*}"
Z2_PURGE_MODULE_ID="${MODDIR##*/}"
[ "$Z2_PURGE_MODULES_DIR" = "$Z2_PURGE_STORAGE_DIR/modules" ] &&
    [ "$Z2_PURGE_STORAGE_DIR" = /data/adb ] &&
    [ "$Z2_PURGE_MODULE_ID" = zapret2 ] ||
    return 1 2>/dev/null || exit 1
Z2_PURGE_CANONICAL_MODULE_DIR="$MODDIR"
Z2_PURGE_CANONICAL_PENDING_DIR="$Z2_PURGE_STORAGE_DIR/modules_update/$Z2_PURGE_MODULE_ID"
Z2_PURGE_CANONICAL_STATE_DIR="/data/adb/zapret2-state"
Z2_PURGE_REQUEST="${PURGE_REQUEST:-$Z2_PURGE_CANONICAL_STATE_DIR/purge.request}"

z2_purge_is_decimal() {
    case "$1" in ""|*[!0-9]*) return 1 ;; *) return 0 ;; esac
}

z2_purge_is_safe_token() {
    case "$1" in ""|*[!A-Za-z0-9._-]*) return 1 ;; esac
    [ "${#1}" -le 128 ] 2>/dev/null
}

z2_purge_path_uid_is_root() {
    local path="$1" uid listing
    if command -v stat >/dev/null 2>&1; then
        uid="$(stat -c '%u' "$path" 2>/dev/null)" || return 1
        [ "$uid" = 0 ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    set -- $listing
    [ "$#" -ge 4 ] && [ "$3" = 0 ]
}

z2_purge_mode_is() {
    local path="$1" expected="$2" mode listing
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
        [ "$mode" = "$expected" ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    case "$expected:${listing%% *}" in
        600:-rw-------*|700:drwx------*) return 0 ;;
        *) return 1 ;;
    esac
}

z2_purge_state_dir_is_secure() {
    local resolved
    [ "$STATE_DIR" = "$Z2_PURGE_CANONICAL_STATE_DIR" ] || return 1
    [ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || return 1
    z2_purge_path_uid_is_root "$STATE_DIR" && z2_purge_mode_is "$STATE_DIR" 700 || return 1
    resolved="$(CDPATH=; cd "$STATE_DIR" 2>/dev/null && pwd -P)" || return 1
    [ "$resolved" = "$Z2_PURGE_CANONICAL_STATE_DIR" ]
}

z2_purge_request_is_secure() {
    [ -f "$Z2_PURGE_REQUEST" ] && [ ! -L "$Z2_PURGE_REQUEST" ] || return 1
    z2_purge_path_uid_is_root "$Z2_PURGE_REQUEST" &&
        z2_purge_mode_is "$Z2_PURGE_REQUEST" 600 && path_nlink_is_one "$Z2_PURGE_REQUEST"
}

z2_purge_read_request() {
    local key value version="" source="" token="" created="" boot="" module=""
    local seen="" size
    Z2_PURGE_REQUEST_SOURCE=""; Z2_PURGE_REQUEST_TOKEN=""; Z2_PURGE_REQUEST_CREATED=""
    Z2_PURGE_REQUEST_BOOT=""; Z2_PURGE_REQUEST_MODULE=""
    z2_purge_request_is_secure || return 1
    size="$(wc -c < "$Z2_PURGE_REQUEST" 2>/dev/null)" || return 1
    z2_purge_is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null &&
        [ "$size" -le 1024 ] 2>/dev/null || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1 ;; esac; version="$value"; seen="${seen}v" ;;
            source) case "$seen" in *s*) return 1 ;; esac; source="$value"; seen="${seen}s" ;;
            token) case "$seen" in *t*) return 1 ;; esac; token="$value"; seen="${seen}t" ;;
            created_epoch) case "$seen" in *c*) return 1 ;; esac; created="$value"; seen="${seen}c" ;;
            boot_id) case "$seen" in *b*) return 1 ;; esac; boot="$value"; seen="${seen}b" ;;
            module_dir) case "$seen" in *m*) return 1 ;; esac; module="$value"; seen="${seen}m" ;;
            *) return 1 ;;
        esac
    done < "$Z2_PURGE_REQUEST"
    [ "${#seen}" -eq 6 ] 2>/dev/null && [ "$version" = "$Z2_PURGE_PROTOCOL_VERSION" ] || return 1
    case "$source" in app|manager|cli) ;; *) return 1 ;; esac
    z2_purge_is_safe_token "$token" && z2_purge_is_decimal "$created" || return 1
    is_valid_boot_id "$boot" || return 1
    [ "$module" = "$Z2_PURGE_CANONICAL_MODULE_DIR" ] || return 1
    Z2_PURGE_REQUEST_SOURCE="$source"; Z2_PURGE_REQUEST_TOKEN="$token"
    Z2_PURGE_REQUEST_CREATED="$created"; Z2_PURGE_REQUEST_BOOT="$boot"
    Z2_PURGE_REQUEST_MODULE="$module"
}

z2_purge_request_is_live() {
    local now age
    z2_purge_read_request || return 1
    read_current_boot_id || return 1
    [ "$Z2_PURGE_REQUEST_BOOT" = "$CURRENT_BOOT_ID" ] || return 1
    now="$(date +%s 2>/dev/null)" || return 1
    z2_purge_is_decimal "$now" && [ "$now" -ge "$Z2_PURGE_REQUEST_CREATED" ] 2>/dev/null || return 1
    age=$((now - Z2_PURGE_REQUEST_CREATED))
    [ "$age" -le "$Z2_PURGE_REQUEST_TTL_SECONDS" ] 2>/dev/null
}

z2_purge_module_identity_is_exact() {
    local root="$1" prop="$1/module.prop"
    case "$root" in
        "$Z2_PURGE_CANONICAL_MODULE_DIR"|"$Z2_PURGE_CANONICAL_PENDING_DIR") ;;
        *) return 1 ;;
    esac
    [ -d "$root" ] && [ ! -L "$root" ] && z2_purge_path_uid_is_root "$root" || return 1
    [ -f "$prop" ] && [ ! -L "$prop" ] && z2_purge_path_uid_is_root "$prop" || return 1
    [ "$(grep -c '^id=' "$prop" 2>/dev/null)" = 1 ] && grep -qx 'id=zapret2' "$prop"
}

z2_purge_managed_tree_path() {
    local path="$1" parent name suffix
    case "$path" in
        "$Z2_PURGE_CANONICAL_MODULE_DIR"|"$Z2_PURGE_CANONICAL_PENDING_DIR"|\
        "$Z2_PURGE_CANONICAL_STATE_DIR") return 0 ;;
    esac
    parent="${path%/*}"
    name="${path##*/}"
    case "$parent:$name" in
        "$Z2_PURGE_STORAGE_DIR":zapret2-install.*) suffix="${name#zapret2-install.}" ;;
        "$Z2_PURGE_STORAGE_DIR":zapret2-recovery.*) suffix="${name#zapret2-recovery.}" ;;
        "$Z2_PURGE_MODULES_DIR":.zapret2-recovery-*) suffix="${name#.zapret2-recovery-}" ;;
        *) return 1 ;;
    esac
    z2_purge_is_safe_token "$suffix"
}

z2_purge_remove_managed_tree() {
    local path="$1"
    z2_purge_managed_tree_path "$path" || return 1
    [ -e "$path" ] || [ -L "$path" ] || return 0
    if [ -L "$path" ]; then
        rm -f "$path" 2>/dev/null || return 1
    else
        z2_purge_path_uid_is_root "$path" || return 1
        rm -rf "$path" 2>/dev/null || return 1
    fi
    [ ! -e "$path" ] && [ ! -L "$path" ]
}

z2_purge_remove_external_workspaces() {
    local path rc=0 restore_noglob=0
    case "$-" in *f*) restore_noglob=1; set +f ;; esac
    set -- \
        "$Z2_PURGE_STORAGE_DIR"/zapret2-install.* \
        "$Z2_PURGE_STORAGE_DIR"/zapret2-recovery.* \
        "$Z2_PURGE_MODULES_DIR"/.zapret2-recovery-*
    [ "$restore_noglob" = 1 ] && set -f
    for path in "$@"; do
        [ -e "$path" ] || [ -L "$path" ] || continue
        z2_purge_remove_managed_tree "$path" || rc=1
    done
    return "$rc"
}

z2_purge_remove_legacy_files() {
    local path rc=0 restore_noglob=0
    case "$-" in *f*) restore_noglob=1; set +f ;; esac
    set -- \
        /data/local/tmp/nfqws2.pid /data/local/tmp/zapret2-owner.state \
        /data/local/tmp/nfqws2-cmdline.txt /data/local/tmp/nfqws2-startup.log \
        /data/local/tmp/nfqws2-error.log /data/local/tmp/nfqws2-debug.log \
        /data/local/tmp/zapret2.log /data/local/tmp/zapret2.log.1 \
        /data/local/tmp/zapret2-runtime.owner /data/local/tmp/zapret2-user.conf \
        /data/local/tmp/zapret2-lifecycle.lock /data/local/tmp/zapret2-lifecycle.lock.*
    [ "$restore_noglob" = 1 ] && set -f
    for path in "$@"; do
        [ -e "$path" ] || [ -L "$path" ] || continue
        case "$path" in /data/local/tmp/nfqws2*|/data/local/tmp/zapret2*) ;; *) rc=1; continue ;; esac
        if [ -d "$path" ] && [ ! -L "$path" ]; then rm -rf "$path" 2>/dev/null || rc=1
        else rm -f "$path" 2>/dev/null || rc=1
        fi
    done
    return "$rc"
}
