#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
HARNESS=$ROOT/tests/device/smoke.sh
TMP=$(mktemp -d "${TMPDIR:-/tmp}/zapret2-device-host-tests.XXXXXX")
[ "${KEEP_DEVICE_TEST_TMP:-0}" = 1 ] || trap 'rm -rf "$TMP"' EXIT HUP INT TERM
MUTATION_ONLY=${DEVICE_TEST_MUTATION_ONLY:-0}
MUTATION_CASES=${DEVICE_TEST_MUTATION_CASES:-}
UNINSTALL_ONLY=${DEVICE_TEST_UNINSTALL_ONLY:-0}
case "$MUTATION_ONLY" in 0|1) ;; *) printf 'FAIL: DEVICE_TEST_MUTATION_ONLY must be 0 or 1\n' >&2; exit 1;; esac
case "$UNINSTALL_ONLY" in 0|1) ;; *) printf 'FAIL: DEVICE_TEST_UNINSTALL_ONLY must be 0 or 1\n' >&2; exit 1;; esac
[ "$MUTATION_ONLY:$UNINSTALL_ONLY" != 1:1 ] || { printf 'FAIL: focused test modes are mutually exclusive\n' >&2; exit 1; }
[ -z "$MUTATION_CASES" ] || [ "$MUTATION_ONLY" = 1 ] || { printf 'FAIL: DEVICE_TEST_MUTATION_CASES requires DEVICE_TEST_MUTATION_ONLY=1\n' >&2; exit 1; }

# Some Windows Git shells omit chmod even though their NTFS executable check
# already treats generated scripts as executable. Keep the host-only test
# portable without weakening the harness itself.
if ! command -v chmod >/dev/null 2>&1; then
    mkdir "$TMP/host-bin"
    printf '%s\n' '#!/bin/sh' 'exit 0' > "$TMP/host-bin/chmod"
    PATH=$TMP/host-bin:$PATH
    export PATH
fi
if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1 && ! command -v openssl >/dev/null 2>&1; then
    [ -d "$TMP/host-bin" ] || mkdir "$TMP/host-bin"
    cat > "$TMP/host-bin/sha256sum" <<'EOF'
#!/bin/sh
set -eu
hash=$(certutil.exe -hashfile "$1" SHA256 | tr -d '\r ' | grep -E '^[0-9A-Fa-f]{64}$' | tr 'A-F' 'a-f')
[ "${#hash}" -eq 64 ]
printf '%s  %s\n' "$hash" "$1"
EOF
    PATH=$TMP/host-bin:$PATH
    export PATH
fi
if ! command -v od >/dev/null 2>&1; then
    [ -d "$TMP/host-bin" ] || mkdir "$TMP/host-bin"
    printf '%s\n' '#!/bin/sh' "printf ' 0123456789abcdef0123456789abcdef\\n'" > "$TMP/host-bin/od"
    PATH=$TMP/host-bin:$PATH
    export PATH
fi

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_fails() { "$@" >/dev/null 2>&1 && fail "command unexpectedly succeeded: $*"; return 0; }
hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
    else openssl dgst -sha256 "$1" | awk '{print $NF}'
    fi
}

FAKE_ADB=$TMP/fake-adb
cat > "$FAKE_ADB" <<'EOF'
#!/bin/sh
set -u
: "${FAKE_ADB_LOG:?}"
: "${FAKE_STATE_DIR:?}"
mkdir -p "$FAKE_STATE_DIR"
[ -f "$FAKE_STATE_DIR/service" ] || printf 'stopped\n' > "$FAKE_STATE_DIR/service"
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"

query_footer() {
    qf_rc=${1:-0}
    printf '\nZ2_QUERY_RC=%s\nZ2_QUERY_COMPLETE=1\n' "$qf_rc"
}

write_object() {
    wo_name=$1 wo_path=$2 wo_type=$3 wo_link=$4 wo_nlink=$5 wo_mode=$6 wo_uid=$7 wo_gid=$8 wo_hash=$9
    shift 9; wo_size=$1
    printf 'path=%s\ntype=%s\nsymlink=%s\nnlink=%s\nmode=%s\nuid=%s\ngid=%s\nhash=%s\nsize=%s\nreadable=1\n' \
        "$wo_path" "$wo_type" "$wo_link" "$wo_nlink" "$wo_mode" "$wo_uid" "$wo_gid" "$wo_hash" "$wo_size" > "$FAKE_STATE_DIR/$wo_name.object"
    wo_local=$FAKE_STATE_DIR/remote$wo_path
    /usr/bin/mkdir -p "${wo_local%/*}"
    if [ "$wo_type" = directory ]; then /usr/bin/mkdir -p "$wo_local"; else : > "$wo_local"; fi
}

object_value() {
    ov_name=$1 ov_key=$2
    sed -n "s/^$ov_key=//p" "$FAKE_STATE_DIR/$ov_name.object"
}

set_object_value() {
    sov_name=$1 sov_key=$2 sov_value=$3 sov_tmp=$FAKE_STATE_DIR/$sov_name.object.tmp
    awk -F= -v key="$sov_key" -v value="$sov_value" '$1 == key { print key "=" value; next } { print }' "$FAKE_STATE_DIR/$sov_name.object" > "$sov_tmp" && mv "$sov_tmp" "$FAKE_STATE_DIR/$sov_name.object"
}

ensure_object() {
    eo_name=$1
    [ -f "$FAKE_STATE_DIR/$eo_name.object" ] || write_object "$@"
}

object_file_value() {
    ofv_file=$1 ofv_key=$2
    sed -n "s/^$ofv_key=//p" "$ofv_file"
}

object_for_path() {
    ofp_path=$1
    for ofp_file in "$FAKE_STATE_DIR"/*.object; do
        [ -f "$ofp_file" ] || continue
        [ "$(object_file_value "$ofp_file" path)" = "$ofp_path" ] || continue
        printf '%s\n' "$ofp_file"
        return 0
    done
    return 1
}

remove_object() {
    ro_name=$1
    [ -f "$FAKE_STATE_DIR/$ro_name.object" ] || return 0
    ro_path=$(object_value "$ro_name" path)
    /usr/bin/rm -rf "$FAKE_STATE_DIR/remote$ro_path"
    /usr/bin/rm -f "$FAKE_STATE_DIR/$ro_name.object"
}

object_name_for_path() {
    onfp_path=$1
    onfp_file=$(object_for_path "$onfp_path") || return 1
    onfp_name=${onfp_file##*/}; onfp_name=${onfp_name%.object}
    printf '%s\n' "$onfp_name"
}

trace_primitive() { printf 'OP %s %s\n' "${FAKE_SCRIPT_KIND:-direct}" "$*" >> "$FAKE_STATE_DIR/primitive.log"; }

z2_test() {
    zt_invert=0
    if [ "${1:-}" = '!' ]; then zt_invert=1; shift; fi
    case "${1:-}" in
        -e|-f|-d|-L|-r)
            zt_op=$1 zt_local=$2 zt_path=${zt_local#"$FAKE_STATE_DIR/remote"}
            zt_name=$(object_name_for_path "$zt_path") || zt_name=
            zt_ok=1
            if [ -n "$zt_name" ]; then
                case "$zt_op" in
                    -e) zt_ok=0 ;;
                    -f) [ "$(object_value "$zt_name" type)" = file ] && zt_ok=0 ;;
                    -d) [ "$(object_value "$zt_name" type)" = directory ] && zt_ok=0 ;;
                    -L) [ "$(object_value "$zt_name" symlink)" = 1 ] && zt_ok=0 ;;
                    -r) [ "$(object_value "$zt_name" readable)" = 1 ] && zt_ok=0 ;;
                esac
            fi
            [ "$zt_invert" = 0 ] && return "$zt_ok"
            [ "$zt_ok" = 0 ] && return 1
            return 0
            ;;
    esac
    if [ "$zt_invert" = 1 ]; then /usr/bin/test ! "$@"; else /usr/bin/test "$@"; fi
}

z2_stat() {
    [ "$1" = -c ] || return 2
    zs_format=$2 zs_local=$3 zs_path=${zs_local#"$FAKE_STATE_DIR/remote"}
    trace_primitive "stat $zs_path $zs_format"
    zs_name=$(object_name_for_path "$zs_path") || return 1
    case "$zs_format" in
        %a:%u:%g) printf '%s:%s:%s\n' "$(object_value "$zs_name" mode)" "$(object_value "$zs_name" uid)" "$(object_value "$zs_name" gid)" ;;
        %h) printf '%s\n' "$(object_value "$zs_name" nlink)" ;;
        %h:%a:%u:%g) printf '%s:%s:%s:%s\n' "$(object_value "$zs_name" nlink)" "$(object_value "$zs_name" mode)" "$(object_value "$zs_name" uid)" "$(object_value "$zs_name" gid)" ;;
        %h:%a:%u:%g:%s) printf '%s:%s:%s:%s:%s\n' "$(object_value "$zs_name" nlink)" "$(object_value "$zs_name" mode)" "$(object_value "$zs_name" uid)" "$(object_value "$zs_name" gid)" "$(object_value "$zs_name" size)" ;;
        *) return 2 ;;
    esac
    case "${FAKE_RAW_CASE:-none}:$FAKE_SCRIPT_KIND:$zs_name" in
        e73:shell-cleanup:*|e83:root-cleanup:root-dir|e88:root-cleanup:*-private|e104:copy:*-shell|e117:copy:*-private|e144:apk-install:apk-private) return 1 ;;
    esac
    case "${FAKE_TOMBSTONE_CASE:-ok}:$FAKE_SCRIPT_KIND:$zs_name" in state_stat:uninstall-audit:state-dir|stat:uninstall-audit:uninstall-tombstone) return 1;; esac
    return 0
}

z2_sha256sum() {
    zh_local=$1 zh_path=${zh_local#"$FAKE_STATE_DIR/remote"}
    zh_name=$(object_name_for_path "$zh_path") || return 1
    trace_primitive "hash $zh_path"
    printf '%s  %s\n' "$(object_value "$zh_name" hash)" "$zh_local"
    # e135 is injected only here, after the preceding module stat succeeds, so
    # its modeled remote exit is deterministically the hash failure at 135.
    case "${FAKE_RAW_CASE:-none}:$FAKE_SCRIPT_KIND:$zh_name" in
        e110:copy:*-shell|e119:copy:*-private|e135:module-install:module-private|e147:apk-install:apk-private) return 1 ;;
    esac
    return 0
}

z2_mkdir() {
    zm_local=$1 zm_path=${zm_local#"$FAKE_STATE_DIR/remote"}
    trace_primitive "mkdir $zm_path"
    write_object root-dir "$zm_path" directory 0 2 755 2000 2000 none 0
    [ "${FAKE_RAW_CASE:-none}" = e122 ] && return 1
    return 0
}

z2_chown() {
    zc_owner=$1 zc_local=$2 zc_path=${zc_local#"$FAKE_STATE_DIR/remote"}
    zc_name=$(object_name_for_path "$zc_path") || return 1
    trace_primitive "chown $zc_path $zc_owner"
    set_object_value "$zc_name" uid "${zc_owner%%:*}"; set_object_value "$zc_name" gid "${zc_owner#*:}"
    case "${FAKE_RAW_CASE:-none}:$FAKE_SCRIPT_KIND" in e123:root-create|e114:copy) return 1;; esac
    return 0
}

z2_chmod() {
    zcm_mode=$1 zcm_local=$2 zcm_path=${zcm_local#"$FAKE_STATE_DIR/remote"}
    zcm_name=$(object_name_for_path "$zcm_path") || return 1
    zcm_mode=${zcm_mode#0}
    trace_primitive "chmod $zcm_path $zcm_mode"
    set_object_value "$zcm_name" mode "$zcm_mode"
    if [ "$FAKE_SCRIPT_KIND" = root-create ]; then
        case "${FAKE_STAGE_FAULT:-none}:${FAKE_RAW_CASE:-none}" in root_missing:*|*:e125) remove_object root-dir;; root_type:* ) set_object_value root-dir type file;; root_symlink:* ) set_object_value root-dir symlink 1;; root_mode:*|*:e126) set_object_value root-dir mode 755;; root_uid:* ) set_object_value root-dir uid 2000;; root_gid:* ) set_object_value root-dir gid 2000;; esac
    elif [ "$FAKE_SCRIPT_KIND" = copy ]; then
        case "${FAKE_STAGE_FAULT:-none}:${FAKE_RAW_CASE:-none}" in dest_missing:* ) remove_object "$zcm_name";; dest_type:*|*:e116) set_object_value "$zcm_name" type directory;; dest_symlink:* ) set_object_value "$zcm_name" symlink 1;; dest_nlink:*|*:e118) set_object_value "$zcm_name" nlink 2;; dest_mode:* ) set_object_value "$zcm_name" mode 644;; dest_uid:* ) set_object_value "$zcm_name" uid 2000;; dest_gid:* ) set_object_value "$zcm_name" gid 2000;; dest_size:* ) set_object_value "$zcm_name" size 1;; dest_hash:*|*:e120) set_object_value "$zcm_name" hash eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee;; esac
    fi
    case "${FAKE_RAW_CASE:-none}:$FAKE_SCRIPT_KIND" in e124:root-create|e115:copy) return 1;; esac
    return 0
}

z2_cp() {
    zcp_src_local=$1 zcp_dst_local=$2
    zcp_src=${zcp_src_local#"$FAKE_STATE_DIR/remote"}; zcp_dst=${zcp_dst_local#"$FAKE_STATE_DIR/remote"}
    zcp_src_name=$(object_name_for_path "$zcp_src") || return 1
    case "$zcp_dst" in */module.zip) zcp_dst_name=module-private;; */control.apk) zcp_dst_name=apk-private;; *) return 2;; esac
    trace_primitive "cp $zcp_src $zcp_dst"
    write_object "$zcp_dst_name" "$zcp_dst" "$(object_value "$zcp_src_name" type)" "$(object_value "$zcp_src_name" symlink)" "$(object_value "$zcp_src_name" nlink)" "$(object_value "$zcp_src_name" mode)" "$(object_value "$zcp_src_name" uid)" "$(object_value "$zcp_src_name" gid)" "$(object_value "$zcp_src_name" hash)" "$(object_value "$zcp_src_name" size)"
    [ "${FAKE_RAW_CASE:-none}" = e113 ] && return 1
    return 0
}

z2_rm() {
    [ "$1" = -f ] && shift
    for zr_local in "$@"; do zr_path=${zr_local#"$FAKE_STATE_DIR/remote"}; trace_primitive "rm $zr_path"; zr_name=$(object_name_for_path "$zr_path") || continue; remove_object "$zr_name"; done
    # Exit 90 is used twice by the production cleanup. e90 intentionally models
    # the second branch: the exact rm primitive fails after entry validation.
    case "${FAKE_RAW_CASE:-none}:$FAKE_SCRIPT_KIND" in e75:shell-cleanup|e90:root-cleanup) return 1;; esac
    return 0
}

z2_rmdir() {
    zrd_local=$1 zrd_path=${zrd_local#"$FAKE_STATE_DIR/remote"}; trace_primitive "rmdir $zrd_path"
    remove_object root-dir
    [ "${FAKE_RAW_CASE:-none}" = e91 ] && return 1
    return 0
}

z2_install_module() {
    zi_local=$1 zi_path=${zi_local#"$FAKE_STATE_DIR/remote"}; trace_primitive "install-module $zi_path"
    zi_name=$(object_name_for_path "$zi_path") || return 1
    object_value "$zi_name" hash > "$FAKE_STATE_DIR/module-hash"
    printf 'fixture installer\n'
    [ "${FAKE_RAW_CASE:-none}" = e137 ] && return 1
    return 0
}

z2_pm_install() {
    trace_primitive 'pm-install'
    object_value apk-private hash > "$FAKE_STATE_DIR/apk-hash"
    printf 'Success\n'
    [ "${FAKE_RAW_CASE:-none}" = e149 ] && return 1
    return 0
}

z2_cat() { printf 'fixture-bytes\n'; }
z2_readlink() { return 1; }
z2_ls() {
    zls_local=${3:-${2:-${1:-}}}; zls_path=${zls_local#"$FAKE_STATE_DIR/remote"}; trace_primitive "list $zls_path"
    for zls_file in "$FAKE_STATE_DIR"/*.object; do [ -f "$zls_file" ] || continue; zls_obj_path=$(object_file_value "$zls_file" path); case "$zls_obj_path" in "$zls_path"/*) printf '%s\n' "${zls_obj_path##*/}";; esac; done
    case "${FAKE_TOMBSTONE_CASE:-ok}:$FAKE_SCRIPT_KIND" in query:uninstall-audit) return 1;; esac
}

write_proc_stat() {
    wps_local=$1 wps_start=$2
    printf '4321 (fixture) S' > "$wps_local"
    wps_field=4
    while [ "$wps_field" -lt 22 ]; do printf ' 0' >> "$wps_local"; wps_field=$((wps_field + 1)); done
    printf ' %s\n' "$wps_start" >> "$wps_local"
}

prepare_uninstall_raw_state() {
    purs_case=${FAKE_TOMBSTONE_CASE:-ok}
    for purs_name in uninstall-tombstone unknown-state proc-dir proc-stat; do remove_object "$purs_name"; done
    ensure_object state-dir /data/adb/zapret2-state directory 0 2 700 0 0 none 0
    set_object_value state-dir type directory
    set_object_value state-dir symlink 0
    set_object_value state-dir mode 700
    set_object_value state-dir uid 0
    set_object_value state-dir gid 0
    write_object uninstall-tombstone /data/adb/zapret2-state/uninstall.tombstone file 0 1 600 0 0 none 128
    purs_version=1 purs_pid=4321 purs_start=98765 purs_token=fixture-token
    purs_module=/data/adb/modules/zapret2
    case "$purs_case" in
        version) purs_version=2 ;;
        pid) purs_pid=not-decimal ;;
        start) purs_start=not-decimal ;;
        token) purs_token='bad token' ;;
        module|foreign) purs_module=/data/adb/modules/not-zapret2 ;;
    esac
    purs_file=$FAKE_STATE_DIR/remote/data/adb/zapret2-state/uninstall.tombstone
    {
        printf 'version=%s\n' "$purs_version"
        printf 'pid=%s\n' "$purs_pid"
        printf 'starttime=%s\n' "$purs_start"
        printf 'token=%s\n' "$purs_token"
        printf 'module_dir=%s\n' "$purs_module"
    } > "$purs_file"
    case "$purs_case" in
        malformed|schema) sed '/^token=/d' "$purs_file" > "$purs_file.tmp"; mv "$purs_file.tmp" "$purs_file" ;;
        duplicate) printf 'version=%s\n' "$purs_version" >> "$purs_file" ;;
        reordered) awk 'NR == 2 { second=$0; next } NR == 3 { print; print second; next } { print }' "$purs_file" > "$purs_file.tmp"; mv "$purs_file.tmp" "$purs_file" ;;
        unknown) printf 'unknown=1\n' >> "$purs_file" ;;
        state_missing) remove_object state-dir ;;
        state_symlink) set_object_value state-dir symlink 1 ;;
        state_mode) set_object_value state-dir mode 755 ;;
        entries) write_object unknown-state /data/adb/zapret2-state/unknown file 0 1 600 0 0 none 1 ;;
        tombstone_missing) remove_object uninstall-tombstone ;;
        symlink) set_object_value uninstall-tombstone symlink 1 ;;
        type) set_object_value uninstall-tombstone type directory ;;
        mode) set_object_value uninstall-tombstone mode 644 ;;
    esac
    case "$purs_case" in
        live|proc_different|proc_unreadable|proc_malformed)
            write_object proc-dir /proc/4321 directory 0 2 555 0 0 none 0
            write_object proc-stat /proc/4321/stat file 0 1 444 0 0 none 128
            purs_proc_start=99999
            [ "$purs_case" = live ] && purs_proc_start=98765
            [ "$purs_case" = proc_malformed ] && purs_proc_start=not-decimal
            write_proc_stat "$FAKE_STATE_DIR/remote/proc/4321/stat" "$purs_proc_start"
            [ "$purs_case" = proc_unreadable ] && set_object_value proc-stat readable 0
            ;;
        proc_orphan)
            write_object proc-stat /proc/4321/stat file 0 1 444 0 0 none 128
            write_proc_stat "$FAKE_STATE_DIR/remote/proc/4321/stat" 99999
            ;;
    esac
}

run_uninstall_audit_query() {
    ruaq_wrapped=$1
    prepare_uninstall_raw_state
    FAKE_SCRIPT_KIND=uninstall-audit
    ruaq_command=$(printf '%s\n' "$ruaq_wrapped" | sed -e '1d' -e '/^z2_query_rc=/,$d' -e '1s/^{ //' -e '$s/; }$//')
    ruaq_translated=$(printf '%s\n' "$ruaq_command" | sed \
        -e "s#/data/#$FAKE_STATE_DIR/remote/data/#g" \
        -e "s#/proc/#$FAKE_STATE_DIR/remote/proc/#g" \
        -e 's/\[ /z2_test /g' -e 's/ \]/ /g' \
        -e 's/stat -c/z2_stat -c/g' -e 's/ls -A /z2_ls -A /g' \
        -e "s#z2_tombstone_module\" = $FAKE_STATE_DIR/remote/data/adb/modules/zapret2#z2_tombstone_module\" = /data/adb/modules/zapret2#")
    ( eval "$ruaq_translated" )
    ruaq_rc=$?
    query_footer "$ruaq_rc"
    return 0
}

prepare_raw_state() {
    prs_kind=$1 prs_cmd=$2 prs_raw=${FAKE_RAW_CASE:-none}
    case "$prs_kind" in
        root-create)
            prs_root=$(printf '%s\n' "$prs_cmd" | sed -n 's/^\[ ! -e \([^ ]*\) \].*/\1/p')
            case "${FAKE_STAGE_FAULT:-none}:$prs_raw" in root_existing:*|*:e121) write_object root-dir "$prs_root" directory 0 2 700 0 0 none 0;; esac
            ;;
        copy)
            prs_source=$(printf '%s\n' "$prs_cmd" | sed -n '/^cp \/data\/local\/tmp\/zapret2-device-smoke-/ { s/^cp \([^ ]*\) \([^ ]*\).*/\1/; p; }')
            case "$prs_source" in *.module.zip) prs_source_name=module-shell; prs_private_name=module-private; prs_dest=$(printf '%s\n' "$prs_cmd" | sed -n '/^cp \/data\/local\/tmp\/zapret2-device-smoke-/ { s/^cp \([^ ]*\) \([^ ]*\).*/\2/; p; }');; *.control.apk) prs_source_name=apk-shell; prs_private_name=apk-private; prs_dest=$(printf '%s\n' "$prs_cmd" | sed -n '/^cp \/data\/local\/tmp\/zapret2-device-smoke-/ { s/^cp \([^ ]*\) \([^ ]*\).*/\2/; p; }');; *) return 92;; esac
            case "${FAKE_STAGE_FAULT:-none}:$prs_raw" in
                source_missing:* ) remove_object "$prs_source_name";; source_type:*|*:e103) set_object_value "$prs_source_name" type directory;; source_symlink:* ) set_object_value "$prs_source_name" symlink 1;; source_nlink:*|*:e105) set_object_value "$prs_source_name" nlink 2;; source_mode:*|*:e106) set_object_value "$prs_source_name" mode 666;; source_uid:*|*:e107) set_object_value "$prs_source_name" uid 12345;; source_gid:*|*:e108) set_object_value "$prs_source_name" gid 12345;; source_size:*|*:e109) set_object_value "$prs_source_name" size 0;; source_hash:*|*:e111) set_object_value "$prs_source_name" hash ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff;;
                *:e101) set_object_value root-dir type file;; *:e102) set_object_value root-dir mode 755;;
                dest_exists:*|*:e112) write_object "$prs_private_name" "$prs_dest" file 0 1 600 0 0 none 1;;
            esac
            ;;
        module-install)
            case "$prs_raw" in e131) set_object_value root-dir type file;; e132) set_object_value root-dir mode 755;; e133) set_object_value module-private type directory;; e134) set_object_value module-private nlink 2;; e136) set_object_value module-private hash dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd;; esac
            [ "${FAKE_INSTALL_HASH_SWAP:-none}" = module ] && set_object_value module-private hash dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
            ;;
        apk-install)
            case "$prs_raw" in e141) set_object_value root-dir type file;; e142) set_object_value root-dir mode 755;; e143) set_object_value apk-private type directory;; e145) set_object_value apk-private nlink 2;; e146) set_object_value apk-private size 0;; e148) set_object_value apk-private hash cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc;; esac
            [ "${FAKE_INSTALL_HASH_SWAP:-none}" = apk ] && set_object_value apk-private hash cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
            ;;
        shell-cleanup)
            case "${FAKE_CLEANUP_FAULT:-none}:$prs_raw" in shell_symlink:*|*:e71) set_object_value module-shell symlink 1;; shell_type:*|*:e72) set_object_value module-shell type directory;; shell_nlink:*|*:e74) set_object_value module-shell nlink 2;; esac
            ;;
        root-cleanup)
            case "${FAKE_CLEANUP_FAULT:-none}:$prs_raw" in
                root_symlink:*|*:e81) set_object_value root-dir symlink 1;; root_type:*|*:e82) set_object_value root-dir type file;; root_mode:*|*:e84) set_object_value root-dir mode 755;; root_uid:* ) set_object_value root-dir uid 2000;; root_gid:* ) set_object_value root-dir gid 2000;;
                unknown_entry:*|*:e86) write_object unknown-root "$(object_value root-dir path)/unknown" file 0 1 600 0 0 none 1;; private_type:*|*:e87) set_object_value module-private type directory;; private_symlink:* ) set_object_value module-private symlink 1;; private_nlink:*|*:e89) set_object_value module-private nlink 2;; private_mode:* ) set_object_value module-private mode 644;; private_uid:* ) set_object_value module-private uid 2000;; private_gid:* ) set_object_value module-private gid 2000;;
            esac
            ;;
    esac
    return 0
}

emit_stage_schema_fault() {
    essf_kind=$1 essf_file=$2
    case "${FAKE_STAGE_SCHEMA:-ok}:$essf_kind" in
        root_missing:root-create) sed '/^Z2_ROOT_STAGE_SECURE=/d' "$essf_file";;
        root_duplicate:root-create) sed '/^Z2_ROOT_STAGE_COMPLETE=/i Z2_ROOT_STAGE_SECURE=1' "$essf_file";;
        root_reordered:root-create) awk 'NR == 1 { first=$0; next } NR == 2 { print; print first; next } { print }' "$essf_file";;
        root_unknown:root-create) sed '/^Z2_ROOT_STAGE_COMPLETE=/i Z2_ROOT_STAGE_UNKNOWN=1' "$essf_file";;
        copy_missing:copy) sed '/^Z2_STAGE_PRIVATE_SECURE=/d' "$essf_file";;
        copy_duplicate:copy) sed '/^Z2_STAGE_COPY_COMPLETE=/i Z2_STAGE_PRIVATE_SECURE=1' "$essf_file";;
        copy_reordered:copy) awk 'NR == 1 { first=$0; next } NR == 2 { print; print first; next } { print }' "$essf_file";;
        copy_unknown:copy) sed '/^Z2_STAGE_COPY_COMPLETE=/i Z2_STAGE_UNKNOWN=1' "$essf_file";;
        *) cat "$essf_file";;
    esac
}

raw_case_kind() {
    case "$1" in
        e71|e72|e73|e74|e75) printf 'shell-cleanup\n' ;;
        e81|e82|e83|e84|e86|e87|e88|e89|e90|e91) printf 'root-cleanup\n' ;;
        e101|e102|e103|e104|e105|e106|e107|e108|e109|e110|e111|e112|e113|e114|e115|e116|e117|e118|e119|e120) printf 'copy\n' ;;
        e121|e122|e123|e124|e125|e126) printf 'root-create\n' ;;
        e131|e132|e133|e134|e135|e136|e137) printf 'module-install\n' ;;
        e141|e142|e143|e144|e145|e146|e147|e148|e149) printf 'apk-install\n' ;;
        *) return 1 ;;
    esac
}

run_remote_script() {
    rrs_kind=$1 rrs_cmd=$2
    prepare_raw_state "$rrs_kind" "$rrs_cmd" || return $?
    FAKE_SCRIPT_KIND=$rrs_kind
    rrs_raw=${FAKE_RAW_CASE:-none}
    rrs_raw_kind=$(raw_case_kind "$rrs_raw" 2>/dev/null || true)
    rrs_raw_active=0
    [ "$rrs_kind" != "$rrs_raw_kind" ] || rrs_raw_active=1
    rrs_translated=$(printf '%s\n' "$rrs_cmd" | sed \
        -e "s#/data/#$FAKE_STATE_DIR/remote/data/#g" \
        -e 's/\[ /z2_test /g' -e 's/ \]/ /g' \
        -e 's/stat -c/z2_stat -c/g' -e 's/sha256sum /z2_sha256sum /g' \
        -e 's/^mkdir /z2_mkdir /' -e 's/^cp /z2_cp /' \
        -e 's/chown /z2_chown /g' -e 's/chmod /z2_chmod /g' \
        -e 's/rm -f /z2_rm -f /g' -e 's/rmdir /z2_rmdir /g' \
        -e 's/magisk --install-module /z2_install_module /g' \
        -e 's/pm install -r -S /z2_pm_install /g' \
        -e 's/^cat \([^ ]*\) |/z2_cat \1 |/' \
        -e 's/ls -A /z2_ls -A /g' -e 's/readlink /z2_readlink /g')
    rrs_output=$FAKE_STATE_DIR/script-output.$$
    set +e
    ( eval "$rrs_translated" ) > "$rrs_output" 2>&1
    rrs_rc=$?
    set -e
    if [ "$rrs_raw_active" -eq 1 ] && [ ! -e "$FAKE_STATE_DIR/raw-result" ]; then
        printf 'case=%s\nkind=%s\nrc=%s\n' "$rrs_raw" "$rrs_kind" "$rrs_rc" > "$FAKE_STATE_DIR/raw-result"
        trace_primitive "raw-result case=$rrs_raw kind=$rrs_kind rc=$rrs_rc"
    fi
    rrs_public=$FAKE_STATE_DIR/script-public.$$
    sed "s#$FAKE_STATE_DIR/remote##g" "$rrs_output" > "$rrs_public"
    if [ "$rrs_rc" -eq 0 ]; then emit_stage_schema_fault "$rrs_kind" "$rrs_public"; else cat "$rrs_public"; fi
    /usr/bin/rm -f "$rrs_output" "$rrs_public"
    return "$rrs_rc"
}

emit_remote_path_state() {
    erps_cmd=$1
    erps_path=$(printf '%s\n' "$erps_cmd" | sed -n 's/^if \[ -L \([^ ]*\) \];.*/\1/p')
    [ -n "$erps_path" ] || return 92
    erps_missing=MISSING
    case "$erps_cmd" in *'else echo ABSENT; fi') erps_missing=ABSENT;; esac
    erps_file=$(object_for_path "$erps_path") || { printf '%s\n' "$erps_missing"; return 0; }
    [ "$(object_file_value "$erps_file" symlink)" = 0 ] || { printf 'LINK\n'; return 0; }
    case "$erps_cmd" in
        *'elif [ -d '*) [ "$(object_file_value "$erps_file" type)" = directory ] || { printf 'MISSING\n'; return 0; } ;;
        *'elif [ -f '*) [ "$(object_file_value "$erps_file" type)" = file ] || { printf 'MISSING\n'; return 0; } ;;
        *'elif [ -e '*) ;;
        *) return 92 ;;
    esac
    printf '%s:%s:%s\n' "$(object_file_value "$erps_file" mode)" "$(object_file_value "$erps_file" uid)" "$(object_file_value "$erps_file" gid)"
}

case "${FAKE_IPV6_AVAILABLE:-1}" in 0|1) ;; *) exit 92;; esac
case "${FAKE_QUERY_MODE:-ok}" in ok|truncated|rc) ;; *) exit 92;; esac
case "${FAKE_UPDATE_RECOVERY_STATE:-none}" in none|lock|transaction|cleanup) ;; *) exit 92;; esac
case "${FAKE_STATUS_SCHEMA:-ok}" in ok|missing|duplicate|reordered|unknown) ;; *) exit 92;; esac
case "${FAKE_ROLLBACK_SCHEMA:-ok}" in ok|missing|duplicate|reordered|unknown) ;; *) exit 92;; esac
case "${FAKE_STAGE_SCHEMA:-ok}" in ok|root_missing|root_duplicate|root_reordered|root_unknown|copy_missing|copy_duplicate|copy_reordered|copy_unknown) ;; *) exit 92;; esac
case "${FAKE_STAGE_FAULT:-none}" in none|root_existing|root_missing|root_type|root_symlink|root_mode|root_uid|root_gid|source_missing|source_type|source_symlink|source_nlink|source_mode|source_uid|source_gid|source_size|source_hash|dest_exists|dest_missing|dest_type|dest_symlink|dest_nlink|dest_mode|dest_uid|dest_gid|dest_size|dest_hash) ;; *) exit 92;; esac
case "${FAKE_INSTALL_HASH_SWAP:-none}" in none|module|apk) ;; *) exit 92;; esac
case "${FAKE_CLEANUP_FAULT:-none}" in none|shell_symlink|shell_type|shell_nlink|root_symlink|root_type|root_mode|root_uid|root_gid|unknown_entry|private_type|private_symlink|private_nlink|private_mode|private_uid|private_gid) ;; *) exit 92;; esac
case "${FAKE_FIREWALL_DIRTY:-none}" in none|v4|v6) ;; *) exit 92;; esac
case "${FAKE_DUMP_FAILURE:-none}" in none|v4|v6) ;; *) exit 92;; esac
case "${FAKE_REBOOTED:-0}" in 0|1) ;; *) exit 92;; esac
case "${FAKE_BOOT_SCHEMA:-ok}" in ok|malformed|missing|duplicate|reordered|unknown|rc) ;; *) exit 92;; esac
case "${FAKE_TOMBSTONE_CASE:-ok}" in ok|state_missing|state_symlink|state_stat|state_mode|query|entries|tombstone_missing|symlink|type|stat|mode|malformed|schema|duplicate|reordered|unknown|version|pid|start|token|module|foreign|live|proc_different|proc_unreadable|proc_malformed|proc_orphan) ;; *) exit 92;; esac
case "${FAKE_RAW_CASE:-none}" in none|e71|e72|e73|e74|e75|e81|e82|e83|e84|e86|e87|e88|e89|e90|e91|e101|e102|e103|e104|e105|e106|e107|e108|e109|e110|e111|e112|e113|e114|e115|e116|e117|e118|e119|e120|e121|e122|e123|e124|e125|e126|e131|e132|e133|e134|e135|e136|e137|e141|e142|e143|e144|e145|e146|e147|e148|e149) ;; *) exit 92;; esac
case "${FAKE_ROOT_DENIED:-0}:${FAKE_UNINSTALLED:-0}:${FAKE_UNINSTALL_AUDIT_BAD:-0}" in [01]:[01]:[01]) ;; *) exit 92;; esac

ensure_object module-dir /data/adb/modules/zapret2 directory 0 2 755 0 0 none 0
ensure_object state-dir /data/adb/zapret2-state directory 0 2 700 0 0 none 0
ensure_object module-prop /data/adb/modules/zapret2/module.prop file 0 1 644 0 0 none 128
ensure_object runtime /data/adb/modules/zapret2/zapret2/runtime.ini file 0 1 644 0 0 none 128
ensure_object categories /data/adb/modules/zapret2/zapret2/categories.ini file 0 1 644 0 0 none 128
ensure_object generation /data/adb/modules/zapret2/zapret2/install-generation.meta file 0 1 600 0 0 none 128
ensure_object wrapper-start /data/adb/modules/zapret2/system/bin/zapret2-start file 0 1 755 0 0 none 128
ensure_object wrapper-stop /data/adb/modules/zapret2/system/bin/zapret2-stop file 0 1 755 0 0 none 128
ensure_object wrapper-restart /data/adb/modules/zapret2/system/bin/zapret2-restart file 0 1 755 0 0 none 128
ensure_object wrapper-status /data/adb/modules/zapret2/system/bin/zapret2-status file 0 1 755 0 0 none 128
ensure_object wrapper-rollback /data/adb/modules/zapret2/system/bin/zapret2-full-rollback file 0 1 755 0 0 none 128
ensure_object script-start /data/adb/modules/zapret2/zapret2/scripts/zapret-start.sh file 0 1 755 0 0 none 128
ensure_object script-stop /data/adb/modules/zapret2/zapret2/scripts/zapret-stop.sh file 0 1 755 0 0 none 128
ensure_object script-restart /data/adb/modules/zapret2/zapret2/scripts/zapret-restart.sh file 0 1 755 0 0 none 128
ensure_object script-status /data/adb/modules/zapret2/zapret2/scripts/zapret-status.sh file 0 1 755 0 0 none 128
ensure_object script-rollback /data/adb/modules/zapret2/zapret2/scripts/zapret-full-rollback.sh file 0 1 755 0 0 none 128
ensure_object binary /data/adb/modules/zapret2/zapret2/bin/arm64-v8a/nfqws2 file 0 1 755 0 0 none 128

emit_status() {
    es_state=$(cat "$FAKE_STATE_DIR/service")
    case "$es_state" in
        running)
            es_status=ok; es_owned=1; es_process=1; es_active=1; es_pid=4242; es_verified=1; es_generation=fixture-generation; es_ipv4=1
            if [ "${FAKE_IPV6_AVAILABLE:-1}" = 1 ]; then es_ipv6=1; es_rules=12; es_expected=12; es_ipv4_rules=6; es_ipv6_rules=6
            else es_ipv6=0; es_rules=6; es_expected=6; es_ipv4_rules=6; es_ipv6_rules=0
            fi
            ;;
        stopped|rolledback) es_status=stopped; es_owned=0; es_process=0; es_active=0; es_pid=; es_verified=0; es_generation=; es_ipv4=0; es_ipv6=0; es_rules=0; es_expected=0; es_family_rules=0 ;;
        *) exit 93 ;;
    esac
    [ "$es_state" = running ] || { es_ipv4_rules=0; es_ipv6_rules=0; }
    es_file=$FAKE_STATE_DIR/status.$$
    cat > "$es_file" <<STATUS
Z2_STATUS=$es_status
Z2_OWNED=$es_owned
Z2_PROCESS=$es_process
Z2_ACTIVE=$es_active
Z2_PID=$es_pid
Z2_PID_VERIFIED=$es_verified
Z2_PID_STARTTIME=$es_pid
Z2_OWNER_GENERATION=$es_generation
Z2_OWNER_METADATA_VERIFIED=$es_verified
Z2_QNUM=200
Z2_IPV4=$es_ipv4
Z2_IPV6=$es_ipv6
Z2_RULES=$es_rules
Z2_EXPECTED_RULES=$es_expected
Z2_IPV4_RULES=$es_ipv4_rules
Z2_IPV6_RULES=$es_ipv6_rules
Z2_RULESET_VERIFIED=1
Z2_NFQUEUE=1
Z2_QUEUE_BYPASS=1
Z2_UPDATE_BLOCKED=0
Z2_UNINSTALL_TOMBSTONE=0
Z2_COMPLETE=1
STATUS
    case "${FAKE_STATUS_SCHEMA:-ok}" in
        ok) cat "$es_file" ;;
        missing) sed '$d' "$es_file" ;;
        duplicate) cat "$es_file"; printf 'Z2_COMPLETE=1\n' ;;
        reordered) awk 'NR == 1 { first=$0; next } NR == 2 { print; print first; next } { print }' "$es_file" ;;
        unknown) sed '$d' "$es_file"; printf 'Z2_STATUS_UNKNOWN=1\nZ2_COMPLETE=1\n' ;;
    esac
    rm -f "$es_file"
    [ "$es_status" = ok ] && return 0
    return 1
}

emit_boot_query() {
    ebq_before=11111111-1111-4111-8111-111111111111
    ebq_after=22222222-2222-4222-8222-222222222222
    [ "${FAKE_REBOOTED:-0}" = 1 ] && ebq_value=$ebq_after || ebq_value=$ebq_before
    ebq_file=$FAKE_STATE_DIR/boot-query.$$
    printf 'Z2_UNINSTALL_BOOT_ID=%s\nZ2_UNINSTALL_BOOT_COMPLETE=1\n' "$ebq_value" > "$ebq_file"
    case "${FAKE_BOOT_SCHEMA:-ok}" in
        ok) cat "$ebq_file" ;;
        malformed) sed 's/^Z2_UNINSTALL_BOOT_ID=.*/Z2_UNINSTALL_BOOT_ID=NOT-A-BOOT-ID/' "$ebq_file" ;;
        missing) sed '$d' "$ebq_file" ;;
        duplicate) cat "$ebq_file"; sed -n '1p' "$ebq_file" ;;
        reordered) awk 'NR == 1 { first=$0; next } NR == 2 { print; print first }' "$ebq_file" ;;
        unknown) sed '$d' "$ebq_file"; printf 'Z2_UNINSTALL_BOOT_UNKNOWN=1\nZ2_UNINSTALL_BOOT_COMPLETE=1\n' ;;
        rc) printf 'boot id unavailable\n'; rm -f "$ebq_file"; query_footer 9; return 0 ;;
    esac
    rm -f "$ebq_file"
    query_footer 0
}

handle_query() {
    hq_cmd=$1
    case "${FAKE_QUERY_MODE:-ok}" in
        truncated) printf 'TRUNCATED_QUERY\n'; return 0 ;;
        rc) printf 'query failed\n'; query_footer 9; return 0 ;;
        ok) ;;
    esac
    [ -z "${FAKE_QUERY_STDOUT:-}" ] || printf '%s\n' "$FAKE_QUERY_STDOUT"
    case "$hq_cmd" in
        *'Z2_UPDATE_RECOVERY_COMPLETE=1'*)
            hq_lock=absent hq_transaction=absent hq_cleanup=absent
            case "${FAKE_UPDATE_RECOVERY_STATE:-none}" in
                lock) hq_lock=present ;;
                transaction) hq_transaction=present ;;
                cleanup) hq_cleanup=present ;;
            esac
            printf 'Z2_UPDATE_LOCK=%s\nZ2_UPDATE_TRANSACTION=%s\nZ2_UPDATE_CLEANUP=%s\nZ2_UPDATE_RECOVERY_COMPLETE=1\n' \
                "$hq_lock" "$hq_transaction" "$hq_cleanup"
            query_footer 0; return 0
            ;;
        *'for proc in /proc/[0-9]*'*)
            if [ "${FAKE_UNINSTALL_AUDIT_BAD:-0}" = 1 ]; then
                printf 'Z2_PROC_OWNER_META=absent\nZ2_PROC_PIDFILE=absent\nZ2_PROC_MATCHES=1\nZ2_PROC_AUDIT_COMPLETE=1\n'
            else
                printf 'Z2_PROC_OWNER_META=absent\nZ2_PROC_PIDFILE=absent\nZ2_PROC_MATCHES=0\nZ2_PROC_AUDIT_COMPLETE=1\n'
            fi
            query_footer 0; return 0
            ;;
        *'cat /proc/sys/kernel/random/boot_id'*) trace_primitive boot-id-read; emit_boot_query; return 0 ;;
        *'z2_uid=$(id -u)'*) printf 'Z2_UNINSTALL_ROOT_UID=0\nZ2_UNINSTALL_ROOT_COMPLETE=1\n'; query_footer 0; return 0 ;;
        *'for z2_wrapper in /system/bin/zapret2-start'*)
            trace_primitive uninstall-path-audit
            [ "${FAKE_UNINSTALLED:-0}" = 1 ] || { printf 'paths still present\n'; query_footer 1; return 0; }
            printf 'Z2_UNINSTALL_LIVE_MODULE=absent\nZ2_UNINSTALL_UPDATE_MODULE=absent\nZ2_UNINSTALL_WRAPPERS=absent\nZ2_UNINSTALL_PATHS_COMPLETE=1\n'; query_footer 0; return 0
            ;;
        *'z2_state_entries=$(ls -A /data/adb/zapret2-state'*)
            trace_primitive uninstall-tombstone-read
            [ "${FAKE_UNINSTALLED:-0}" = 1 ] || { printf 'state unavailable\n'; query_footer 1; return 0; }
            run_uninstall_audit_query "$hq_cmd"; return 0
            ;;
        *'command -v iptables-save'*'iptables-save'*)
            [ "${FAKE_DUMP_FAILURE:-none}" = v4 ] && { printf 'iptables-save failed\n'; query_footer 9; return 0; }
            [ "${FAKE_FIREWALL_DIRTY:-none}" = v4 ] && printf ':ZAPRET2_OUT - [0:0]\n-A OUTPUT -j ZAPRET2_OUT\n'
            query_footer 0; return 0
            ;;
        *'command -v ip6tables-save'*'ip6tables-save'*)
            [ "${FAKE_IPV6_AVAILABLE:-1}" = 1 ] || { printf 'ip6tables unavailable\n'; query_footer 127; return 0; }
            [ "${FAKE_DUMP_FAILURE:-none}" = v6 ] && { printf 'ip6tables-save failed\n'; query_footer 9; return 0; }
            [ "${FAKE_FIREWALL_DIRTY:-none}" = v6 ] && printf ':ZAPRET2_IN - [0:0]\n-A INPUT -j ZAPRET2_IN\n'
            query_footer 0; return 0
            ;;
        *"printf 'Z2_IP6TABLES_SAVE=not_available\\n'"*) printf 'Z2_IP6TABLES_SAVE=not_available\n'; query_footer 0; return 0 ;;
        *'{ id; }'*) printf 'uid=0(root) gid=0(root)\n'; query_footer 0; return 0 ;;
        *'{ uname -a; }'*) printf 'Linux fake 6.1 arm64\n'; query_footer 0; return 0 ;;
        *'{ getenforce 2>/dev/null || echo unknown; }'*) printf 'Enforcing\n'; query_footer 0; return 0 ;;
        *'{ magisk -v; magisk -V; }'*) printf '27.0\n27000\n'; query_footer 0; return 0 ;;
        *'cat /data/adb/modules/zapret2/module.prop'*) printf 'id=zapret2\nversion=fixture\n'; query_footer 0; return 0 ;;
        *'cat /data/adb/modules/zapret2/zapret2/install-generation.meta'*)
            printf 'version=1\nmodule_dir=/data/adb/modules/zapret2\ngeneration=fixture\narchive_sha256=0000000000000000000000000000000000000000000000000000000000000000\ncompleted_epoch=1\ncomplete=1\n'; query_footer 0; return 0
            ;;
        *'sha256sum /data/adb/modules/zapret2/module.prop'*) printf '000000 fixture\n'; query_footer 0; return 0 ;;
        *'ls -lan /data/adb/zapret2-state'*) trace_primitive uninstall-state-listing; printf 'fixture state listing\n'; query_footer 0; return 0 ;;
        *'ls -lan /data/adb/modules/zapret2 /data/adb/modules/zapret2/zapret2 /data/adb/modules/zapret2/system/bin'*) printf 'fixture module listing\n'; query_footer 0; return 0 ;;
        *'ps -A 2>/dev/null | grep nfqws2 || true'*) query_footer 0; return 0 ;;
        *'for f in /proc/net/ip_tables_targets'*) printf '===netfilter\nNFQUEUE\n'; query_footer 0; return 0 ;;
        *'sha256sum /system/etc/hosts'*) printf '000000 /system/etc/hosts\n'; query_footer 0; return 0 ;;
        *'mount 2>/dev/null | grep -E'*) printf 'fixture /system mount\n'; query_footer 0; return 0 ;;
        *) printf 'UNKNOWN ROOT QUERY:\n%s\n' "$hq_cmd" >&2; return 92 ;;
    esac
}

if [ "$#" -eq 1 ] && [ "$1" = devices ]; then
    printf 'List of devices attached\nTEST-SERIAL\tdevice\n'
    exit 0
fi
if [ "$#" -eq 1 ] && [ "$1" = version ]; then printf 'Android Debug Bridge fake\n'; exit 0; fi

if [ "${1:-}" = -s ] && [ "${2:-}" = TEST-SERIAL ]; then
    shift 2
else
    exit 91
fi

case "${1:-}" in
    get-state) [ "$#" -eq 1 ] || exit 92; printf 'device\n'; exit 0 ;;
    get-serialno) [ "$#" -eq 1 ] || exit 92; printf 'TEST-SERIAL\n'; exit 0 ;;
    push)
        [ "$#" -eq 3 ] || exit 92
        case "$3" in
            /data/local/tmp/zapret2-device-smoke-[0-9a-f]*.module.zip) push_name=module-shell ;;
            /data/local/tmp/zapret2-device-smoke-[0-9a-f]*.control.apk) push_name=apk-shell ;;
            *) exit 92 ;;
        esac
        push_hash=$(sha256sum "$2" | awk '{print $1}') || exit 92
        push_size=$(wc -c < "$2") || exit 92
        write_object "$push_name" "$3" file 0 1 644 2000 2000 "$push_hash" "$push_size"
        printf '%s\n' "$push_hash" > "$FAKE_STATE_DIR/$push_name.expected"
        printf '1 file pushed\n'; exit 0
        ;;
esac

if [ "${1:-}" = shell ]; then
    shift
    if [ "${1:-}" = getprop ]; then
        case "${2:-}" in
            ro.build.version.sdk) printf '35\n' ;;
            ro.product.cpu.abi) printf 'arm64-v8a\n' ;;
            ro.build.fingerprint) printf 'fixture/device/release\n' ;;
            *) exit 92 ;;
        esac
        exit 0
    fi
    if [ "${1:-}" = pm ] && [ "${2:-}" = path ] && [ "${3:-}" = com.zapret2.app ] && [ "$#" -eq 3 ]; then
        printf 'package:/data/app/fixture/base.apk\n'; exit 0
    fi
    if [ "${1:-}" = pm ]; then exit 92; fi
    if [ "${1:-}" = su ] && [ "${2:-}" = -c ] && [ "${3:-}" = /system/bin/sh ]; then
        cmd=$(cat)
        printf 'ROOT_COMMAND:%s\n' "$cmd" >> "$FAKE_ADB_LOG"
        if [ "${FAKE_ROOT_DENIED:-0}" = 1 ]; then exit 1; fi
        case "$cmd" in set\ +e*) handle_query "$cmd"; exit $?;; esac
        case "$cmd" in ip6tables\ *) [ "${FAKE_IPV6_AVAILABLE:-1}" = 1 ] || exit 127;; esac
        case "$cmd" in
            'id -u') printf '0\n'; exit 0 ;;
            'id') printf 'uid=0(root) gid=0(root)\n'; exit 0 ;;
            'magisk -V') printf '27000\n'; exit 0 ;;
            'magisk -v; magisk -V') printf '27.0\n27000\n'; exit 0 ;;
            'command -v /system/bin/zapret2-status') printf '/system/bin/zapret2-status\n'; exit 0 ;;
            '/system/bin/zapret2-status --machine') emit_status; exit $? ;;
            '/system/bin/zapret2-stop') printf 'stopped\n' > "$FAKE_STATE_DIR/service"; printf 'stopped\n'; exit 0 ;;
            '/system/bin/zapret2-start'|'/system/bin/zapret2-restart') printf 'running\n' > "$FAKE_STATE_DIR/service"; printf 'running\n'; exit 0 ;;
            '/system/bin/zapret2-full-rollback --machine')
                printf 'rolledback\n' > "$FAKE_STATE_DIR/service"
                rb_file=$FAKE_STATE_DIR/rollback.$$
                printf 'Z2_RB_STATUS=complete\nZ2_RB_PROCESS_CLEAN=1\nZ2_RB_FIREWALL_CLEAN=1\nZ2_RB_ROLLBACK_ARMED=1\nZ2_RB_HOSTS_PRESERVED=1\nZ2_RB_REBOOT_REQUIRED=1\nZ2_RB_USER_DATA_PRESERVED=1\nZ2_RB_LEGACY_AMBIGUOUS=0\nZ2_RB_DIAGNOSTIC=fixture\nZ2_RB_COMPLETE=1\n' > "$rb_file"
                case "${FAKE_ROLLBACK_SCHEMA:-ok}" in
                    ok) cat "$rb_file" ;;
                    missing) sed '$d' "$rb_file" ;;
                    duplicate) cat "$rb_file"; printf 'Z2_RB_COMPLETE=1\n' ;;
                    reordered) awk 'NR == 1 { first=$0; next } NR == 2 { print; print first; next } { print }' "$rb_file" ;;
                    unknown) sed '$d' "$rb_file"; printf 'Z2_RB_UNKNOWN=1\nZ2_RB_COMPLETE=1\n' ;;
                esac
                rm -f "$rb_file"
                exit 0
                ;;
            *'then echo LINK; elif ['*'stat -c %a:%u:%g'*) emit_remote_path_state "$cmd"; exit $? ;;
            *'/data/adb/modules/zapret2/remove'*'echo PRESENT'*) printf 'ABSENT\n'; exit 0 ;;
            'command -v iptables && iptables --version') printf '/system/bin/iptables\niptables v1.8.9\n'; exit 0 ;;
            'command -v ip6tables && ip6tables --version')
                [ "${FAKE_IPV6_AVAILABLE:-1}" = 1 ] || exit 127
                printf '/system/bin/ip6tables\nip6tables v1.8.9\n'; exit 0
                ;;
            *'-j NFQUEUE -h'*) printf 'NFQUEUE options: --queue-num --queue-bypass\n'; exit 0 ;;
            *'-m connbytes -h'*) printf 'connbytes options: --connbytes --connbytes-mode\n'; exit 0 ;;
            *'-m multiport -h'*) printf 'multiport options: --dports --sports\n'; exit 0 ;;
            *'-m mark -h'*) printf 'mark options: --mark\n'; exit 0 ;;
            *'mkdir /data/adb/zapret2-device-smoke-'*) run_remote_script root-create "$cmd"; exit $? ;;
            *'cp /data/local/tmp/zapret2-device-smoke-'*) run_remote_script copy "$cmd"; exit $? ;;
            *'magisk --install-module /data/adb/zapret2-device-smoke-'*) run_remote_script module-install "$cmd"; exit $? ;;
            *'| pm install -r -S '*) run_remote_script apk-install "$cmd"; exit $? ;;
            *"sed -n 's/^archive_sha256=//p' /data/adb/modules/zapret2/zapret2/install-generation.meta"*) trace_primitive installed-module-hash; cat "$FAKE_STATE_DIR/module-hash"; exit 0 ;;
            'sha256sum /data/app/fixture/base.apk') trace_primitive installed-apk-hash; cat "$FAKE_STATE_DIR/apk-hash"; printf '  /data/app/fixture/base.apk\n'; exit 0 ;;
            *'for z2_shell_file in /data/local/tmp/zapret2-device-smoke-'*) run_remote_script shell-cleanup "$cmd"; exit $? ;;
            *'z2_cleanup_seen=0'*'/data/adb/zapret2-device-smoke-'*) run_remote_script root-cleanup "$cmd"; exit $? ;;
            *) printf 'UNKNOWN ROOT COMMAND:\n%s\n' "$cmd" >&2; exit 92 ;;
        esac
    fi
fi

exit 92
EOF
chmod 0755 "$FAKE_ADB"
FAKE_STATE_DIR=$TMP/fake-state
export FAKE_STATE_DIR

assert_no_device_mutation() {
    andm_log=$1
    if grep -E '^ROOT_COMMAND:/system/bin/zapret2-(stop|start|restart)( |$)|^ROOT_COMMAND:/system/bin/zapret2-full-rollback --machine| push .* /data/local/tmp/zapret2-device-smoke-|^magisk --install-module|pm install -r|^(mkdir|touch|mv|cp|chmod|chown|kill|rm|rmdir) |^(iptables|ip6tables).* -[ADNFXI]' "$andm_log" >/dev/null 2>&1; then
        fail "device mutation appeared in fake ADB log"
    fi
}

assert_no_installer_or_service_toggle() {
    anist_log=$1
    if grep -E '^ROOT_COMMAND:/system/bin/zapret2-(stop|start|restart)( |$)| push .* /data/local/tmp/zapret2-device-smoke-|magisk --install-module|pm install -r' "$anist_log" >/dev/null 2>&1; then
        fail "unexpected installer or service toggle appeared in fake ADB log"
    fi
}

assert_malformed_schema_shape() {
    amss_file=$1 amss_family=$2 amss_mode=$3
    [ -f "$amss_file" ] || fail "$amss_family/$amss_mode emitter did not create schema evidence"
    case "$amss_family:$amss_mode" in
        status:missing)
            if grep -q '^Z2_COMPLETE=' "$amss_file"; then fail "status/missing still emitted its sentinel"; fi
            ;;
        status:duplicate)
            [ "$(grep -c '^Z2_COMPLETE=1$' "$amss_file")" -eq 2 ] || fail "status/duplicate did not duplicate its sentinel"
            ;;
        status:reordered)
            [ "$(sed -n '1p' "$amss_file")" = Z2_OWNED=0 ] || fail "status/reordered did not move Z2_OWNED first"
            [ "$(sed -n '2p' "$amss_file")" = Z2_STATUS=stopped ] || fail "status/reordered did not move Z2_STATUS second"
            ;;
        status:unknown)
            grep -Fxq Z2_STATUS_UNKNOWN=1 "$amss_file" || fail "status/unknown did not emit its unknown field"
            [ "$(tail -n 1 "$amss_file")" = Z2_COMPLETE=1 ] || fail "status/unknown lost its terminal sentinel"
            ;;
        rollback:missing)
            if grep -q '^Z2_RB_COMPLETE=' "$amss_file"; then fail "rollback/missing still emitted its sentinel"; fi
            ;;
        rollback:duplicate)
            [ "$(grep -c '^Z2_RB_COMPLETE=1$' "$amss_file")" -eq 2 ] || fail "rollback/duplicate did not duplicate its sentinel"
            ;;
        rollback:reordered)
            [ "$(sed -n '1p' "$amss_file")" = Z2_RB_PROCESS_CLEAN=1 ] || fail "rollback/reordered did not move process state first"
            [ "$(sed -n '2p' "$amss_file")" = Z2_RB_STATUS=complete ] || fail "rollback/reordered did not move status second"
            ;;
        rollback:unknown)
            grep -Fxq Z2_RB_UNKNOWN=1 "$amss_file" || fail "rollback/unknown did not emit its unknown field"
            [ "$(tail -n 1 "$amss_file")" = Z2_RB_COMPLETE=1 ] || fail "rollback/unknown lost its terminal sentinel"
            ;;
        *) fail "unsupported malformed schema assertion: $amss_family/$amss_mode" ;;
    esac
}

new_case_state() {
    ncs_dir=$1 ncs_service=$2
    [ ! -e "$ncs_dir" ] || fail "isolated fake state already exists: $ncs_dir"
    mkdir -p "$ncs_dir"
    printf '%s\n' "$ncs_service" > "$ncs_dir/service"
}

run_update_case() {
    ruc_state=$1 ruc_log=$2 ruc_evidence=$3
    shift 3
    env FAKE_STATE_DIR="$ruc_state" FAKE_ADB_LOG="$ruc_log" ADB_BIN="$FAKE_ADB" "$@" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$ruc_evidence" --stage update \
        --allow-mutations --allow-update --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN \
        --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
        --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH"
}

run_rollback_case() {
    rrc_state=$1 rrc_log=$2 rrc_evidence=$3
    shift 3
    env FAKE_STATE_DIR="$rrc_state" FAKE_ADB_LOG="$rrc_log" ADB_BIN="$FAKE_ADB" "$@" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$rrc_evidence" --stage full-rollback \
        --allow-mutations --allow-full-rollback --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
}

wait_case_batch() {
    for wcb_pid in $CASE_PIDS; do
        wait "$wcb_pid" || fail "isolated fake case failed: pid $wcb_pid"
    done
    CASE_PIDS=
    CASE_COUNT=0
}

sh -n "$HARNESS"
sh -n "$0"

if grep -Fq 'zapret-update-guard.sh' "$HARNESS"; then
    fail "device harness invokes or names the internal update guard"
fi
if grep -Eq 'magisk --remove-modules|touch .*/data/adb/modules/zapret2/remove|> .*/data/adb/modules/zapret2/remove' "$HARNESS"; then
    fail "device harness automates an unsafe root-manager uninstall mechanism"
fi

LOG=$TMP/default.log
: > "$LOG"
FAKE_ADB_LOG=$LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/evidence" >/dev/null
assert_no_device_mutation "$LOG"
[ "$(sed -n 's/^stage=//p' "$TMP/evidence/sequence.state")" = preflight ] || fail "preflight sequence state missing"
[ -f "$TMP/evidence/preflight/before-state/iptables-save.txt" ] || fail "before-state firewall evidence missing"
[ -f "$TMP/evidence/preflight/status.machine" ] || fail "strict status evidence missing"

LOG=$TMP/dry-run.log
: > "$LOG"
FAKE_ADB_LOG=$LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/evidence" --stage stop --dry-run \
    --allow-mutations --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
assert_no_device_mutation "$LOG"
[ "$(sed -n 's/^stage=//p' "$TMP/evidence/sequence.state")" = preflight ] || fail "dry-run advanced sequence state"

LOG=$TMP/missing-flag.log
: > "$LOG"
assert_fails env FAKE_ADB_LOG=$LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/evidence" --stage stop \
    --ack-disposable-device TEST-SERIAL --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
[ ! -s "$LOG" ] || fail "missing mutation flag reached ADB"

LOG=$TMP/missing-serial.log
: > "$LOG"
assert_fails env FAKE_ADB_LOG=$LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --evidence-dir "$TMP/evidence" --stage stop --allow-mutations \
    --ack-disposable-device TEST-SERIAL --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
[ ! -s "$LOG" ] || fail "missing serial reached ADB"

LOG=$TMP/root-denied.log
: > "$LOG"
assert_fails env FAKE_ADB_LOG=$LOG FAKE_ROOT_DENIED=1 ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/root-denied-evidence"
assert_no_device_mutation "$LOG"

LOG=$TMP/incomplete-artifacts.log
: > "$LOG"
printf 'not-a-release\n' > "$TMP/module.zip"
assert_fails env FAKE_ADB_LOG=$LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/incomplete-evidence" \
    --module-zip "$TMP/module.zip" --module-sha256 0000000000000000000000000000000000000000000000000000000000000000
[ ! -s "$LOG" ] || fail "incomplete artifact identity reached ADB"

# Unknown ADB/device/root/package-manager operations are denied by default.
assert_fails env FAKE_ADB_LOG=$LOG "$FAKE_ADB" -s TEST-SERIAL shell getprop ro.unknown.property
assert_fails env FAKE_ADB_LOG=$LOG "$FAKE_ADB" -s TEST-SERIAL shell pm clear com.zapret2.app
assert_fails env FAKE_ADB_LOG=$LOG sh -c 'printf "%s\n" "unknown-root-command" | "$1" -s TEST-SERIAL shell su -c /system/bin/sh' sh "$FAKE_ADB"

# Exercise the complete ordered flow against exact fake artifacts.
MODULE_ARTIFACT=$TMP/zapret2-magisk-release.zip
APK_ARTIFACT=$TMP/zapret2-control-release.apk
printf 'exact module release fixture\n' > "$MODULE_ARTIFACT"
printf 'exact apk release fixture\n' > "$APK_ARTIFACT"
MODULE_HASH=$(hash_file "$MODULE_ARTIFACT")
APK_HASH=$(hash_file "$APK_ARTIFACT")
FLOW_EVIDENCE=$TMP/flow-evidence
FLOW_LOG=$TMP/flow.log
: > "$FLOW_LOG"
printf 'stopped\n' > "$FAKE_STATE_DIR/service"

FAKE_ADB_LOG=$FLOW_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" \
    --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
    --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH" >/dev/null

for FLOW_STAGE in stop start restart; do
    FAKE_ADB_LOG=$FLOW_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage "$FLOW_STAGE" \
        --allow-mutations --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
done
cp -R "$FLOW_EVIDENCE" "$TMP/restart-template-evidence"

if [ "$MUTATION_ONLY" = 0 ]; then
# The device-side update preflight refuses each unresolved owner/update
# recovery artifact before staging or either installer is reached.
for UPDATE_RECOVERY_STATE in lock transaction cleanup; do
    UPDATE_RECOVERY_STATE_DIR=$TMP/update-recovery-$UPDATE_RECOVERY_STATE-state
    UPDATE_RECOVERY_LOG=$TMP/update-recovery-$UPDATE_RECOVERY_STATE.log
    UPDATE_RECOVERY_EVIDENCE=$TMP/update-recovery-$UPDATE_RECOVERY_STATE-evidence
    new_case_state "$UPDATE_RECOVERY_STATE_DIR" stopped
    cp -R "$TMP/restart-template-evidence" "$UPDATE_RECOVERY_EVIDENCE"
    : > "$UPDATE_RECOVERY_LOG"
    if run_update_case "$UPDATE_RECOVERY_STATE_DIR" "$UPDATE_RECOVERY_LOG" "$UPDATE_RECOVERY_EVIDENCE" \
        "FAKE_UPDATE_RECOVERY_STATE=$UPDATE_RECOVERY_STATE" >/dev/null 2>&1; then
        fail "device update accepted pending $UPDATE_RECOVERY_STATE recovery state"
    fi
    if grep -E 'magisk --install-module|pm install -r|Z2_ROOT_STAGE_PATH=' "$UPDATE_RECOVERY_LOG" >/dev/null 2>&1; then
        fail "pending $UPDATE_RECOVERY_STATE recovery state reached update staging"
    fi
    [ "$(sed -n 's/^stage=//p' "$UPDATE_RECOVERY_EVIDENCE/sequence.state")" = restart ] ||
        fail "pending $UPDATE_RECOVERY_STATE recovery state advanced the device sequence"
done

# The update-specific gate must fail before any ADB call.
GATE_LOG=$TMP/update-gate.log
: > "$GATE_LOG"
assert_fails env FAKE_ADB_LOG=$GATE_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage update \
    --allow-mutations --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN \
    --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
    --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH"
[ ! -s "$GATE_LOG" ] || fail "missing update-specific gate reached ADB"

# An anomalous staged file must stop before either installer is reached.
cp -R "$FLOW_EVIDENCE" "$TMP/anomalous-update-evidence"
ANOMALY_LOG=$TMP/anomalous-update.log
: > "$ANOMALY_LOG"
assert_fails env FAKE_ADB_LOG=$ANOMALY_LOG FAKE_STAGE_FAULT=source_hash ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/anomalous-update-evidence" --stage update \
    --allow-mutations --allow-update --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN \
    --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
    --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH"
if grep -E 'magisk --install-module|pm install -r' "$ANOMALY_LOG" >/dev/null 2>&1; then fail "staging anomaly reached an installer"; fi

FAKE_ADB_LOG=$FLOW_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage update \
    --allow-mutations --allow-update --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN \
    --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
    --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH" >/dev/null
[ "$(sed -n 's/^stage=//p' "$FLOW_EVIDENCE/sequence.state")" = update ] || fail "update sequence state missing"
grep -Eq 'Z2_ROOT_STAGE_PATH=/data/adb/zapret2-device-smoke-[0-9a-f]{32}' "$FLOW_LOG" || fail "random root-private staging path was not used"
grep -Fq 'chown 0:0' "$FLOW_LOG" || fail "root staging ownership was not established"
grep -Fq 'chmod 0700' "$FLOW_LOG" || fail "root staging mode was not established"
grep -Fq 'cat /data/adb/zapret2-device-smoke-' "$FLOW_LOG" || fail "APK was not streamed from root-private staging"
cp -R "$FLOW_EVIDENCE" "$TMP/update-template-evidence"

# Every supported malformed rollback schema reaches the exact rollback command,
# emits its requested shape, and fails before advancing the copied sequence.
for ROLLBACK_SCHEMA in missing duplicate reordered unknown; do
    BAD_ROLLBACK_EVIDENCE=$TMP/bad-rollback-$ROLLBACK_SCHEMA-evidence
    BAD_ROLLBACK_LOG=$TMP/bad-rollback-$ROLLBACK_SCHEMA.log
    cp -R "$FLOW_EVIDENCE" "$BAD_ROLLBACK_EVIDENCE"
    : > "$BAD_ROLLBACK_LOG"
    assert_fails env FAKE_ADB_LOG=$BAD_ROLLBACK_LOG FAKE_ROLLBACK_SCHEMA=$ROLLBACK_SCHEMA ADB_BIN=$FAKE_ADB sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$BAD_ROLLBACK_EVIDENCE" --stage full-rollback \
        --allow-mutations --allow-full-rollback --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
    [ -s "$BAD_ROLLBACK_LOG" ] || fail "rollback/$ROLLBACK_SCHEMA failed before reaching fake ADB"
    [ "$(grep -Fc 'ROOT_COMMAND:/system/bin/zapret2-full-rollback --machine' "$BAD_ROLLBACK_LOG")" -eq 1 ] ||
        fail "rollback/$ROLLBACK_SCHEMA did not reach exactly one rollback command envelope"
    assert_no_installer_or_service_toggle "$BAD_ROLLBACK_LOG"
    assert_malformed_schema_shape \
        "$BAD_ROLLBACK_EVIDENCE/stages/full-rollback/full-rollback.machine" rollback "$ROLLBACK_SCHEMA"
    [ "$(sed -n 's/^stage=//p' "$BAD_ROLLBACK_EVIDENCE/sequence.state")" = update ] ||
        fail "rollback/$ROLLBACK_SCHEMA advanced sequence"
done

FAKE_ADB_LOG=$FLOW_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage full-rollback \
    --allow-mutations --allow-full-rollback --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
[ -f "$FLOW_EVIDENCE/stages/full-rollback/process-audit.txt" ] || fail "rollback /proc ownership audit missing"
grep -Fxq Z2_PROC_MATCHES=0 "$FLOW_EVIDENCE/stages/full-rollback/process-audit.txt" || fail "rollback process audit not clean"

FAKE_ADB_LOG=$FLOW_LOG ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage uninstall \
    --allow-mutations --allow-uninstall --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null

run_uninstall_rejection() {
    rur_name=$1
    shift
    rur_evidence=$TMP/uninstall-reject-$rur_name-evidence
    rur_log=$TMP/uninstall-reject-$rur_name.log
    cp -R "$FLOW_EVIDENCE" "$rur_evidence"
    : > "$rur_log"
    assert_fails env FAKE_ADB_LOG="$rur_log" FAKE_UNINSTALLED=1 "$@" ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$rur_evidence" --stage uninstall-verify
    [ "$(sed -n 's/^stage=//p' "$rur_evidence/sequence.state")" = uninstall ] ||
        fail "rejected uninstall verification advanced sequence: $rur_name"
    assert_no_device_mutation "$rur_log"
}

# A root-manager removal without a new boot identity is not a verified uninstall.
run_uninstall_rejection no-reboot FAKE_REBOOTED=0

# The post-reboot identity is an exact two-field query schema and canonical UUID.
for BOOT_SCHEMA in malformed missing duplicate reordered unknown rc; do
    run_uninstall_rejection boot-$BOOT_SCHEMA FAKE_REBOOTED=1 FAKE_BOOT_SCHEMA="$BOOT_SCHEMA"
done

# The exact metadata cross-binds serial/run, raw file name/size/hash, and the
# canonical value inside the private boot-before query.
for TAMPER_KIND in run-binding meta-boot raw-boot; do
    TAMPER_EVIDENCE=$TMP/uninstall-reject-checkpoint-$TAMPER_KIND-evidence
    TAMPER_LOG=$TMP/uninstall-reject-checkpoint-$TAMPER_KIND.log
    cp -R "$FLOW_EVIDENCE" "$TAMPER_EVIDENCE"
    TAMPER_META=$TAMPER_EVIDENCE/stages/uninstall/uninstall-reboot.meta
    TAMPER_RAW=$TAMPER_EVIDENCE/stages/uninstall/boot-before.txt
    case "$TAMPER_KIND" in
        run-binding) sed 's/^run_created_utc=.*/run_created_utc=tampered/' "$TAMPER_META" > "$TAMPER_EVIDENCE/uninstall-reboot.tmp" ;;
        meta-boot) sed 's/^pre_boot_id=.*/pre_boot_id=33333333-3333-4333-8333-333333333333/' "$TAMPER_META" > "$TAMPER_EVIDENCE/uninstall-reboot.tmp" ;;
        raw-boot) sed 's/^Z2_UNINSTALL_BOOT_ID=.*/Z2_UNINSTALL_BOOT_ID=33333333-3333-4333-8333-333333333333/' "$TAMPER_RAW" > "$TAMPER_EVIDENCE/boot-before.tmp" ;;
    esac
    case "$TAMPER_KIND" in
        raw-boot) mv "$TAMPER_EVIDENCE/boot-before.tmp" "$TAMPER_RAW" ;;
        *) mv "$TAMPER_EVIDENCE/uninstall-reboot.tmp" "$TAMPER_META" ;;
    esac
    : > "$TAMPER_LOG"
    assert_fails env FAKE_ADB_LOG="$TAMPER_LOG" FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$TAMPER_EVIDENCE" --stage uninstall-verify
    if grep -q '^ROOT_COMMAND:' "$TAMPER_LOG"; then fail "tampered uninstall checkpoint reached a root query: $TAMPER_KIND"; fi
    assert_no_device_mutation "$TAMPER_LOG"
    [ "$(sed -n 's/^stage=//p' "$TAMPER_EVIDENCE/sequence.state")" = uninstall ] ||
        fail "tampered uninstall checkpoint advanced sequence: $TAMPER_KIND"
done

# Raw tombstone/query states are evaluated only by the production verifier.
for TOMBSTONE_CASE in state_missing state_symlink state_stat state_mode query entries tombstone_missing symlink type stat mode malformed schema duplicate reordered unknown version pid start token module foreign live proc_unreadable proc_malformed proc_orphan; do
    run_uninstall_rejection tombstone-$TOMBSTONE_CASE FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE="$TOMBSTONE_CASE"
done

PROC_DIFFERENT_EVIDENCE=$TMP/uninstall-proc-different-evidence
PROC_DIFFERENT_LOG=$TMP/uninstall-proc-different.log
cp -R "$FLOW_EVIDENCE" "$PROC_DIFFERENT_EVIDENCE"
: > "$PROC_DIFFERENT_LOG"
env FAKE_ADB_LOG="$PROC_DIFFERENT_LOG" FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=proc_different ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$PROC_DIFFERENT_EVIDENCE" --stage uninstall-verify >/dev/null
[ "$(sed -n 's/^stage=//p' "$PROC_DIFFERENT_EVIDENCE/sequence.state")" = uninstall-verify ] ||
    fail "readable foreign proc starttime was not accepted as a dead tombstone owner"

# Mutation adequacy: production rejects each raw state above, while temporary
# copies with the boot or exact root-query predicate removed must reach a later
# read-only primitive in the fake operation trace.
make_uninstall_mutant() {
    mum_output=$1 mum_kind=$2
    awk -v kind="$mum_kind" '
        kind == "boot-change" && index($0, "[ \"$vu_post_boot\" != \"$vu_pre_boot\" ] || fail") {
            print "    : # mutation test: reboot-change predicate removed"
            changed++
            next
        }
        kind == "root-state-stat" && /exit 162/ { sub("exit 162", ":"); changed++ }
        kind == "root-file-stat" && /exit 167/ { sub("exit 167", ":"); changed++ }
        kind == "root-mode" && /exit 168/ { sub("exit 168", ":"); changed++ }
        kind == "root-schema" && /exit 170/ { sub("exit 170", ":"); changed++ }
        (kind == "root-module" || kind == "root-module-single") && /exit 181/ { sub("exit 181", ":"); changed++ }
        kind == "root-module" && index($0, "Z2_UNINSTALL_TOMBSTONE_MODULE_DIR=/data/adb/modules/zapret2") {
            sub(" Z2_UNINSTALL_TOMBSTONE_MODULE_DIR=/data/adb/modules/zapret2", "")
            changed++
        }
        kind == "root-proc-unreadable" && /exit 182/ { sub("exit 182", ":"); changed++ }
        kind == "root-proc-malformed" && /exit 184/ { sub("exit 184", ":"); changed++ }
        kind == "root-proc-live" && /exit 185/ {
            sub("exit 185", ":")
            changed++
        }
        { print }
        END {
            expected = (kind == "root-module" ? 2 : 1)
            if (changed != expected) exit 91
        }
    ' "$HARNESS" > "$mum_output" || fail "could not instrument uninstall mutant: $mum_kind"
    chmod 0755 "$mum_output"
}

MUTANT_DIR=$TMP/uninstall-mutants
mkdir "$MUTANT_DIR"
for MUTANT_KIND in boot-change root-state-stat root-file-stat root-mode root-schema root-module root-proc-unreadable root-proc-malformed root-proc-live; do
    MUTANT_HARNESS=$MUTANT_DIR/$MUTANT_KIND.sh
    MUTANT_EVIDENCE=$MUTANT_DIR/$MUTANT_KIND-evidence
    MUTANT_LOG=$MUTANT_DIR/$MUTANT_KIND.log
    cp -R "$FLOW_EVIDENCE" "$MUTANT_EVIDENCE"
    : > "$MUTANT_LOG"
    make_uninstall_mutant "$MUTANT_HARNESS" "$MUTANT_KIND"
    case "$MUTANT_KIND" in
        boot-change)
            MUTANT_PATTERN='^OP direct uninstall-path-audit$'
            MUTANT_ENV='FAKE_REBOOTED=0'
            ;;
        root-state-stat)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=state_stat'
            ;;
        root-file-stat)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=stat'
            ;;
        root-mode)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=mode'
            ;;
        root-schema)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=duplicate'
            ;;
        root-module)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=module'
            ;;
        root-proc-unreadable)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=proc_unreadable'
            ;;
        root-proc-malformed)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=proc_malformed'
            ;;
        root-proc-live)
            MUTANT_PATTERN='^OP direct uninstall-state-listing$'
            MUTANT_ENV='FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=live'
            ;;
    esac
    if [ "$MUTANT_KIND" = root-module ]; then
        SINGLE_HARNESS=$MUTANT_DIR/root-module-single.sh
        SINGLE_EVIDENCE=$MUTANT_DIR/root-module-single-evidence
        SINGLE_LOG=$MUTANT_DIR/root-module-single.log
        cp -R "$FLOW_EVIDENCE" "$SINGLE_EVIDENCE"
        : > "$SINGLE_LOG"
        make_uninstall_mutant "$SINGLE_HARNESS" root-module-single
        assert_fails env FAKE_ADB_LOG="$SINGLE_LOG" FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 FAKE_TOMBSTONE_CASE=module ADB_BIN="$FAKE_ADB" sh "$SINGLE_HARNESS" \
            --serial TEST-SERIAL --evidence-dir "$SINGLE_EVIDENCE" --stage uninstall-verify
        [ "$(sed -n 's/^stage=//p' "$SINGLE_EVIDENCE/sequence.state")" = uninstall ] ||
            fail "single root-module mutant bypassed the paired host validator"
    fi
    MUTANT_BEFORE=$(grep -Ec "$MUTANT_PATTERN" "$FAKE_STATE_DIR/primitive.log" 2>/dev/null || true)
    # MUTANT_ENV contains only fixed test-owned assignments selected above.
    env FAKE_ADB_LOG="$MUTANT_LOG" FAKE_UNINSTALLED=1 $MUTANT_ENV ADB_BIN="$FAKE_ADB" sh "$MUTANT_HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$MUTANT_EVIDENCE" --stage uninstall-verify >/dev/null
    MUTANT_AFTER=$(grep -Ec "$MUTANT_PATTERN" "$FAKE_STATE_DIR/primitive.log" 2>/dev/null || true)
    [ "$MUTANT_AFTER" -eq $((MUTANT_BEFORE + 1)) ] ||
        fail "uninstall mutation survivor did not reach its later primitive: $MUTANT_KIND"
    [ "$(sed -n 's/^stage=//p' "$MUTANT_EVIDENCE/sequence.state")" = uninstall-verify ] ||
        fail "uninstall mutation survivor did not complete: $MUTANT_KIND"
done

# Manual root-manager uninstall verification rejects an adversarial /proc audit.
cp -R "$FLOW_EVIDENCE" "$TMP/bad-uninstall-evidence"
assert_fails env FAKE_ADB_LOG=$FLOW_LOG FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 FAKE_UNINSTALL_AUDIT_BAD=1 ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$TMP/bad-uninstall-evidence" --stage uninstall-verify
[ "$(sed -n 's/^stage=//p' "$TMP/bad-uninstall-evidence/sequence.state")" = uninstall ] || fail "bad uninstall audit advanced sequence"

FAKE_ADB_LOG=$FLOW_LOG FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 ADB_BIN=$FAKE_ADB sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$FLOW_EVIDENCE" --stage uninstall-verify >/dev/null
[ "$(sed -n 's/^stage=//p' "$FLOW_EVIDENCE/sequence.state")" = uninstall-verify ] || fail "manual uninstall verification did not complete"
BOOT_TRANSITION=$FLOW_EVIDENCE/stages/uninstall-verify/boot-transition.meta
[ -f "$BOOT_TRANSITION" ] && [ ! -L "$BOOT_TRANSITION" ] || fail "uninstall boot transition evidence is missing or unsafe"
BOOT_PRE=$(sed -n 's/^pre_boot_id=//p' "$BOOT_TRANSITION")
BOOT_POST=$(sed -n 's/^post_boot_id=//p' "$BOOT_TRANSITION")
[ -n "$BOOT_PRE" ] && [ -n "$BOOT_POST" ] && [ "$BOOT_PRE" != "$BOOT_POST" ] || fail "uninstall boot transition evidence does not prove a reboot"
UNINSTALL_STATE_AUDIT=$FLOW_EVIDENCE/stages/uninstall-verify/state-audit.txt
for UNINSTALL_PAIR in Z2_UNINSTALL_TOMBSTONE_VERSION=1 Z2_UNINSTALL_TOMBSTONE_PID=4321 Z2_UNINSTALL_TOMBSTONE_STARTTIME=98765 Z2_UNINSTALL_TOMBSTONE_TOKEN=fixture-token Z2_UNINSTALL_TOMBSTONE_MODULE_DIR=/data/adb/modules/zapret2 Z2_UNINSTALL_TOMBSTONE_OWNER=dead; do
    grep -Fxq "$UNINSTALL_PAIR" "$UNINSTALL_STATE_AUDIT" || fail "canonical uninstall tombstone evidence is missing: $UNINSTALL_PAIR"
done
[ "$UNINSTALL_ONLY" = 0 ] || { printf 'PASS: focused uninstall verification contracts\n'; exit 0; }

# Both supported malformed root-query envelopes reach the query validator and
# fail read-only. The rc case must preserve the exact error footer.
for QUERY_MODE in truncated rc; do
    QUERY_LOG=$TMP/query-$QUERY_MODE.log
    QUERY_EVIDENCE=$TMP/query-$QUERY_MODE-evidence
    : > "$QUERY_LOG"
    assert_fails env FAKE_ADB_LOG=$QUERY_LOG FAKE_QUERY_MODE=$QUERY_MODE ADB_BIN=$FAKE_ADB sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$QUERY_EVIDENCE"
    [ -s "$QUERY_LOG" ] || fail "query/$QUERY_MODE failed before reaching fake ADB"
    grep -Fq 'ROOT_COMMAND:set +e' "$QUERY_LOG" || fail "query/$QUERY_MODE did not reach a root-query envelope"
    grep -Fq 'Z2_QUERY_COMPLETE=1' "$QUERY_LOG" || fail "query/$QUERY_MODE did not receive the expected query wrapper"
    assert_no_device_mutation "$QUERY_LOG"
    QUERY_RESULT=$QUERY_EVIDENCE/preflight/before-state/root-id.txt
    [ -f "$QUERY_RESULT" ] || fail "query/$QUERY_MODE did not reach root-id evidence capture"
    case "$QUERY_MODE" in
        truncated)
            grep -Fxq TRUNCATED_QUERY "$QUERY_RESULT" || fail "query/truncated did not emit truncated payload"
            if grep -q '^Z2_QUERY_' "$QUERY_RESULT"; then fail "query/truncated unexpectedly emitted a footer"; fi
            ;;
        rc)
            grep -Fxq 'query failed' "$QUERY_RESULT" || fail "query/rc did not emit its diagnostic"
            [ "$(tail -n 2 "$QUERY_RESULT" | sed -n '1p')" = Z2_QUERY_RC=9 ] || fail "query/rc lost its error code footer"
            [ "$(tail -n 1 "$QUERY_RESULT")" = Z2_QUERY_COMPLETE=1 ] || fail "query/rc lost its completion sentinel"
            ;;
    esac
done

# Every supported malformed status schema reaches the machine-status parser,
# emits the requested shape, and fails without a lifecycle or installer call.
for STATUS_SCHEMA in missing duplicate reordered unknown; do
    STATUS_LOG=$TMP/status-$STATUS_SCHEMA.log
    STATUS_EVIDENCE=$TMP/status-$STATUS_SCHEMA-evidence
    : > "$STATUS_LOG"
    assert_fails env FAKE_ADB_LOG=$STATUS_LOG FAKE_STATUS_SCHEMA=$STATUS_SCHEMA ADB_BIN=$FAKE_ADB sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$STATUS_EVIDENCE"
    [ -s "$STATUS_LOG" ] || fail "status/$STATUS_SCHEMA failed before reaching fake ADB"
    grep -Fq 'ROOT_COMMAND:/system/bin/zapret2-status --machine' "$STATUS_LOG" ||
        fail "status/$STATUS_SCHEMA did not reach the machine-status command envelope"
    assert_no_device_mutation "$STATUS_LOG"
    assert_malformed_schema_shape "$STATUS_EVIDENCE/preflight/status.machine" status "$STATUS_SCHEMA"
done

# Every declared fake mode is deny-by-default.
MODE_STATE=$TMP/mode-state
MODE_LOG=$TMP/mode.log
new_case_state "$MODE_STATE" stopped
: > "$MODE_LOG"
MODE_COUNT=0
for MODE_VAR in FAKE_QUERY_MODE FAKE_UPDATE_RECOVERY_STATE FAKE_STATUS_SCHEMA FAKE_ROLLBACK_SCHEMA FAKE_STAGE_SCHEMA FAKE_STAGE_FAULT FAKE_INSTALL_HASH_SWAP FAKE_CLEANUP_FAULT FAKE_FIREWALL_DIRTY FAKE_DUMP_FAILURE FAKE_RAW_CASE FAKE_IPV6_AVAILABLE FAKE_REBOOTED FAKE_BOOT_SCHEMA FAKE_TOMBSTONE_CASE; do
    assert_fails env FAKE_STATE_DIR="$MODE_STATE" FAKE_ADB_LOG="$MODE_LOG" "$MODE_VAR=bogus" "$FAKE_ADB" devices
    MODE_COUNT=$((MODE_COUNT + 1))
done
[ "$(wc -l < "$MODE_LOG" | tr -d ' ')" -eq "$MODE_COUNT" ] || fail "invalid fake mode did not reach deny-by-default validation"
fi

make_instrumented_harness() {
    mih_output=$1 mih_exit=$2 mih_mutate=$3 mih_paired=${4:-0}
    awk -v exit_code="$mih_exit" -v mutate="$mih_mutate" -v paired="$mih_paired" '
        /preflight_device "\$pd_stage_dir\/read-only-preflight"/ { print "IPV6_READY=1"; next }
        {
            if (paired == 1 && exit_code == 120 && $0 ~ /\[ "\$vscs_actual" = "\$vscs_expected" \] \|\|/) {
                print "    : # mutation test: paired copy-schema validator removed"
                getline
                next
            }
            if (mutate == 1 && exit_code == 90) {
                if ($0 ~ /rm -f .*exit 90/) sub("exit 90", ":")
            } else if (mutate == 1) {
                gsub("exit " exit_code, ":")
            }
            print
        }
    ' "$HARNESS" > "$mih_output"
    chmod 0755 "$mih_output"
}

run_mutation_harness() {
    rmh_script=$1 rmh_state=$2 rmh_log=$3 rmh_evidence=$4 rmh_case=$5
    env FAKE_STATE_DIR="$rmh_state" FAKE_ADB_LOG="$rmh_log" FAKE_RAW_CASE="$rmh_case" ADB_BIN="$FAKE_ADB" sh "$rmh_script" \
        --serial TEST-SERIAL --evidence-dir "$rmh_evidence" --stage update \
        --allow-mutations --allow-update --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN \
        --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
        --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH"
}

mutation_trace_pattern() {
    case "$1" in
        71) printf '%s\n' '^OP shell-cleanup stat .*[.]control[.]apk ' ;;
        72|73|74) printf '%s\n' '^OP shell-cleanup rm .*[.]module[.]zip$' ;;
        75) printf '%s\n' '^OP module-install install-module ' ;;
        # exit 81 is the body of an if/elif guard.  Removing it makes that
        # branch complete successfully; the elif body (and its rm) is skipped.
        81) printf '%s\n' '^OP direct installed-module-hash$' ;;
        82|83|84|87|88|89) printf '%s\n' '^OP root-cleanup rm .*/module[.]zip$' ;;
        86) printf '%s\n' '^OP root-cleanup stat .*/unknown ' ;;
        90) printf '%s\n' '^OP root-cleanup rmdir ' ;;
        91|149) printf '%s\n' '^OP direct installed-module-hash$' ;;
        101|102|103|104|105|106|107|108|109|110|111|112) printf '%s\n' '^OP copy cp .*module[.]zip$' ;;
        113) printf '%s\n' '^OP copy chown .*/module[.]zip ' ;;
        114) printf '%s\n' '^OP copy chmod .*/module[.]zip ' ;;
        115|116) printf '%s\n' '^OP copy stat .*/module[.]zip ' ;;
        117|118) printf '%s\n' '^OP copy hash .*/module[.]zip$' ;;
        119|120) printf '%s\n' '^OP copy cp .*control[.]apk$' ;;
        121) printf '%s\n' '^OP root-create mkdir ' ;;
        122) printf '%s\n' '^OP root-create chown ' ;;
        123) printf '%s\n' '^OP root-create chmod ' ;;
        124|125) printf '%s\n' '^OP root-create stat ' ;;
        126) printf '%s\n' '^OP copy stat ' ;;
        131) printf '%s\n' '^OP module-install stat .*/zapret2-device-smoke-' ;;
        132|133) printf '%s\n' '^OP module-install stat .*/module[.]zip ' ;;
        134) printf '%s\n' '^OP module-install hash .*/module[.]zip$' ;;
        135|136) printf '%s\n' '^OP module-install install-module ' ;;
        137) printf '%s\n' '^OP apk-install pm-install$' ;;
        141) printf '%s\n' '^OP apk-install stat .*/zapret2-device-smoke-' ;;
        142|143) printf '%s\n' '^OP apk-install stat .*/control[.]apk ' ;;
        144|145|146|147|148) printf '%s\n' '^OP apk-install pm-install$' ;;
        *) fail "no mutation trace pattern for exit $1" ;;
    esac
}

exercise_exit_mutant() {
    eem_case=$1 eem_kind=$2 eem_exit=${1#e} eem_pattern=$(mutation_trace_pattern "${1#e}")
    eem_base_state=$TMP/mutation-$eem_exit-base-state
    eem_base_evidence=$TMP/mutation-$eem_exit-base-evidence
    eem_base_log=$TMP/mutation-$eem_exit-base.log
    eem_mutant_state=$TMP/mutation-$eem_exit-mutant-state
    eem_mutant_evidence=$TMP/mutation-$eem_exit-mutant-evidence
    eem_mutant_log=$TMP/mutation-$eem_exit-mutant.log
    eem_mutant=$TMP/mutants/smoke-exit-$eem_exit.sh
    new_case_state "$eem_base_state" running
    new_case_state "$eem_mutant_state" running
    cp -R "$TMP/restart-template-evidence" "$eem_base_evidence"
    cp -R "$TMP/restart-template-evidence" "$eem_mutant_evidence"
    : > "$eem_base_log"; : > "$eem_mutant_log"
    make_instrumented_harness "$eem_mutant" "$eem_exit" 1
    assert_fails run_mutation_harness "$FAST_BASELINE_HARNESS" "$eem_base_state" "$eem_base_log" "$eem_base_evidence" "$eem_case"
    [ -s "$eem_base_log" ] || fail "raw case failed before fake ADB: $eem_case"
    [ "$(sed -n 's/^stage=//p' "$eem_base_evidence/sequence.state")" = restart ] || fail "raw case advanced sequence: $eem_case"
    [ -f "$eem_base_state/raw-result" ] || fail "raw case did not reach its translated script: $eem_case"
    [ "$(cat "$eem_base_state/raw-result")" = "case=$eem_case
kind=$eem_kind
rc=$eem_exit" ] || fail "raw case result marker is wrong: $eem_case"
    grep -Fxq "OP $eem_kind raw-result case=$eem_case kind=$eem_kind rc=$eem_exit" "$eem_base_state/primitive.log" ||
        fail "raw case exact primitive marker is missing: $eem_case"

    eem_module_count=$(awk '/^OP module-install install-module / { count++ } END { print count + 0 }' "$eem_base_state/primitive.log")
    eem_apk_count=$(awk '/^OP apk-install pm-install$/ { count++ } END { print count + 0 }' "$eem_base_state/primitive.log")
    case "$eem_case" in
        e81|e82|e83|e84|e86|e87|e88|e89|e90|e91|e149) eem_expected_module=1; eem_expected_apk=1 ;;
        e137|e141|e142|e143|e144|e145|e146|e147|e148) eem_expected_module=1; eem_expected_apk=0 ;;
        *) eem_expected_module=0; eem_expected_apk=0 ;;
    esac
    [ "$eem_module_count" -eq "$eem_expected_module" ] || fail "raw case module installer count is wrong: $eem_case"
    [ "$eem_apk_count" -eq "$eem_expected_apk" ] || fail "raw case APK installer count is wrong: $eem_case"
    case "$eem_case" in
        e90) grep -q '^OP root-cleanup rm ' "$eem_base_state/primitive.log" || fail "e90 did not reach its chosen rm-failure hook" ;;
        e135) grep -q '^OP module-install hash ' "$eem_base_state/primitive.log" || fail "e135 did not reach its chosen hash-failure hook" ;;
    esac
    if grep -Eq "$eem_pattern" "$eem_base_state/primitive.log" 2>/dev/null; then fail "production exit $eem_exit allowed prohibited primitive continuation"; fi
    case "$eem_case" in
        e120)
            # These remote checks are deliberately redundant with an exact host
            # schema validator. The single mutant must remain safely rejected;
            # the paired double mutant proves the remote predicate has its own
            # primitive-continuation effect when both layers are removed.
            assert_fails run_mutation_harness "$eem_mutant" "$eem_mutant_state" "$eem_mutant_log" "$eem_mutant_evidence" "$eem_case"
            if grep -Eq "$eem_pattern" "$eem_mutant_state/primitive.log" 2>/dev/null; then fail "single defense-in-depth mutant reached prohibited primitive: $eem_case"; fi
            eem_pair_state=$TMP/mutation-$eem_exit-pair-state
            eem_pair_evidence=$TMP/mutation-$eem_exit-pair-evidence
            eem_pair_log=$TMP/mutation-$eem_exit-pair.log
            eem_pair=$TMP/mutants/smoke-exit-$eem_exit-paired.sh
            new_case_state "$eem_pair_state" running
            cp -R "$TMP/restart-template-evidence" "$eem_pair_evidence"
            : > "$eem_pair_log"
            make_instrumented_harness "$eem_pair" "$eem_exit" 1 1
            run_mutation_harness "$eem_pair" "$eem_pair_state" "$eem_pair_log" "$eem_pair_evidence" "$eem_case" >/dev/null 2>&1 || true
            grep -Eq "$eem_pattern" "$eem_pair_state/primitive.log" 2>/dev/null || fail "paired mutation for exit $eem_exit did not reach prohibited primitive"
            ;;
        *)
            run_mutation_harness "$eem_mutant" "$eem_mutant_state" "$eem_mutant_log" "$eem_mutant_evidence" "$eem_case" >/dev/null 2>&1 || true
            grep -Eq "$eem_pattern" "$eem_mutant_state/primitive.log" 2>/dev/null || fail "mutation survivor for exit $eem_exit did not reach prohibited primitive"
            ;;
    esac
}

run_exit_mutant_group() {
    remg_kind=$1
    shift
    for remg_case in "$@"; do
        ( exercise_exit_mutant "$remg_case" "$remg_kind" ) &
        CASE_PIDS="$CASE_PIDS $!"; CASE_COUNT=$((CASE_COUNT + 1))
        [ "$CASE_COUNT" -lt 4 ] || wait_case_batch
    done
    [ "$CASE_COUNT" -eq 0 ] || wait_case_batch
}

exercise_stage_schema() {
    ess_mode=$1
    ess_state=$TMP/stage-schema-$ess_mode-state
    ess_evidence=$TMP/stage-schema-$ess_mode-evidence
    ess_log=$TMP/stage-schema-$ess_mode.log
    new_case_state "$ess_state" running
    cp -R "$TMP/restart-template-evidence" "$ess_evidence"
    : > "$ess_log"
    assert_fails run_update_case "$ess_state" "$ess_log" "$ess_evidence" FAKE_STAGE_SCHEMA="$ess_mode"
    [ "$(sed -n 's/^stage=//p' "$ess_evidence/sequence.state")" = restart ] || fail "stage schema advanced sequence: $ess_mode"
    if grep -E 'magisk --install-module|pm install -r' "$ess_log" >/dev/null 2>&1; then
        fail "malformed stage schema reached an installer: $ess_mode"
    fi
}

exercise_hash_swap() {
    ehs_kind=$1
    ehs_state=$TMP/hash-swap-$ehs_kind-state
    ehs_evidence=$TMP/hash-swap-$ehs_kind-evidence
    ehs_log=$TMP/hash-swap-$ehs_kind.log
    new_case_state "$ehs_state" running
    cp -R "$TMP/restart-template-evidence" "$ehs_evidence"
    : > "$ehs_log"
    assert_fails run_update_case "$ehs_state" "$ehs_log" "$ehs_evidence" FAKE_INSTALL_HASH_SWAP="$ehs_kind"
    [ "$(sed -n 's/^stage=//p' "$ehs_evidence/sequence.state")" = restart ] || fail "install hash swap advanced sequence: $ehs_kind"
}

# Mutation adequacy: each raw hostile state is rejected by production, while a
# temporary copy with the corresponding exit predicate removed reaches a later
# mechanical primitive recorded in the operation trace.
CASE_PIDS=
CASE_COUNT=0
mkdir "$TMP/mutants"
FAST_BASELINE_HARNESS=$TMP/mutants/smoke-fast-baseline.sh
make_instrumented_harness "$FAST_BASELINE_HARNESS" 0 0
RAW_SHELL_CASES='e71 e72 e73 e74 e75'
RAW_ROOT_CLEANUP_CASES='e81 e82 e83 e84 e86 e87 e88 e89 e90 e91'
RAW_COPY_CASES='e101 e102 e103 e104 e105 e106 e107 e108 e109 e110 e111 e112 e113 e114 e115 e116 e117 e118 e119 e120'
RAW_ROOT_CREATE_CASES='e121 e122 e123 e124 e125 e126'
RAW_MODULE_CASES='e131 e132 e133 e134 e135 e136 e137'
RAW_APK_CASES='e141 e142 e143 e144 e145 e146 e147 e148 e149'
RAW_MATRIX_CASES="$RAW_SHELL_CASES $RAW_ROOT_CLEANUP_CASES $RAW_COPY_CASES $RAW_ROOT_CREATE_CASES $RAW_MODULE_CASES $RAW_APK_CASES"
RAW_ENUM_CASES=$(grep '^case "${FAKE_RAW_CASE:-none}" in none|' "$FAKE_ADB" | sed 's/^.* in none|//; s/) ;;.*$//' | tr '|' '\n' | sort)
RAW_MATRIX_SORTED=$(printf '%s\n' $RAW_MATRIX_CASES | sort)
[ "$RAW_ENUM_CASES" = "$RAW_MATRIX_SORTED" ] || fail "FAKE_RAW_CASE enum and executable matrix are not bijective"
[ "$(printf '%s\n' $RAW_MATRIX_CASES | wc -l | tr -d ' ')" -eq 57 ] || fail "FAKE_RAW_CASE matrix does not contain exactly 57 mutants"

if [ -n "$MUTATION_CASES" ]; then
    for SELECTED_CASE in $MUTATION_CASES; do
        case " $RAW_MATRIX_CASES " in *" $SELECTED_CASE "*) ;; *) fail "unknown DEVICE_TEST_MUTATION_CASES entry: $SELECTED_CASE";; esac
        case " $RAW_SHELL_CASES " in *" $SELECTED_CASE "*) SELECTED_KIND=shell-cleanup;;
        *) case " $RAW_ROOT_CLEANUP_CASES " in *" $SELECTED_CASE "*) SELECTED_KIND=root-cleanup;;
           *) case " $RAW_COPY_CASES " in *" $SELECTED_CASE "*) SELECTED_KIND=copy;;
              *) case " $RAW_ROOT_CREATE_CASES " in *" $SELECTED_CASE "*) SELECTED_KIND=root-create;;
                 *) case " $RAW_MODULE_CASES " in *" $SELECTED_CASE "*) SELECTED_KIND=module-install;; *) SELECTED_KIND=apk-install;; esac;;
              esac;;
           esac;;
        esac;;
        esac
        run_exit_mutant_group "$SELECTED_KIND" "$SELECTED_CASE"
    done
else
    run_exit_mutant_group shell-cleanup $RAW_SHELL_CASES
    run_exit_mutant_group root-cleanup $RAW_ROOT_CLEANUP_CASES
    run_exit_mutant_group copy $RAW_COPY_CASES
    run_exit_mutant_group root-create $RAW_ROOT_CREATE_CASES
    run_exit_mutant_group module-install $RAW_MODULE_CASES
    run_exit_mutant_group apk-install $RAW_APK_CASES
fi

if [ -z "$MUTATION_CASES" ]; then
for STAGE_SCHEMA in root_missing root_duplicate root_reordered root_unknown copy_missing copy_duplicate copy_reordered copy_unknown; do
    ( exercise_stage_schema "$STAGE_SCHEMA" ) &
    CASE_PIDS="$CASE_PIDS $!"; CASE_COUNT=$((CASE_COUNT + 1))
    [ "$CASE_COUNT" -lt 4 ] || wait_case_batch
done
[ "$CASE_COUNT" -eq 0 ] || wait_case_batch

for HASH_SWAP in module apk; do
    ( exercise_hash_swap "$HASH_SWAP" ) &
    CASE_PIDS="$CASE_PIDS $!"; CASE_COUNT=$((CASE_COUNT + 1))
done
wait_case_batch
fi

if [ "$MUTATION_ONLY" = 0 ]; then
# Existing evidence accepts only the exact capability schema and rejects drift
# before the first lifecycle mutation.
for CAPABILITY_SCHEMA in missing duplicate reordered unknown; do
    CAP_STATE=$TMP/capability-$CAPABILITY_SCHEMA-state
    CAP_EVIDENCE=$TMP/capability-$CAPABILITY_SCHEMA-evidence
    CAP_LOG=$TMP/capability-$CAPABILITY_SCHEMA.log
    new_case_state "$CAP_STATE" stopped
    cp -R "$TMP/evidence" "$CAP_EVIDENCE"
    : > "$CAP_LOG"
    case "$CAPABILITY_SCHEMA" in
        missing) printf 'schema=1\nipv4=ready\n' > "$CAP_EVIDENCE/capabilities.meta" ;;
        duplicate) printf 'schema=1\nipv4=ready\nipv6=ready\nipv6=ready\n' > "$CAP_EVIDENCE/capabilities.meta" ;;
        reordered) printf 'ipv4=ready\nschema=1\nipv6=ready\n' > "$CAP_EVIDENCE/capabilities.meta" ;;
        unknown) printf 'schema=1\nipv4=ready\nipv6=ready\nunknown=1\n' > "$CAP_EVIDENCE/capabilities.meta" ;;
    esac
    assert_fails env FAKE_STATE_DIR="$CAP_STATE" FAKE_ADB_LOG="$CAP_LOG" ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$CAP_EVIDENCE" --stage stop \
        --allow-mutations --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
    assert_no_device_mutation "$CAP_LOG"
done

DRIFT_STATE=$TMP/capability-drift-state
DRIFT_EVIDENCE=$TMP/capability-drift-evidence
DRIFT_LOG=$TMP/capability-drift.log
new_case_state "$DRIFT_STATE" stopped
cp -R "$TMP/evidence" "$DRIFT_EVIDENCE"
: > "$DRIFT_LOG"
assert_fails env FAKE_STATE_DIR="$DRIFT_STATE" FAKE_ADB_LOG="$DRIFT_LOG" FAKE_IPV6_AVAILABLE=0 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$DRIFT_EVIDENCE" --stage stop \
    --allow-mutations --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN
assert_no_device_mutation "$DRIFT_LOG"
[ "$(sed -n 's/^stage=//p' "$DRIFT_EVIDENCE/sequence.state")" = preflight ] || fail "capability drift advanced sequence"

# Successful dumps are mandatory for IPv4 and for declared-ready IPv6.
for DUMP_FAMILY in v4 v6; do
    DUMP_STATE=$TMP/dump-$DUMP_FAMILY-state
    DUMP_EVIDENCE=$TMP/dump-$DUMP_FAMILY-evidence
    DUMP_LOG=$TMP/dump-$DUMP_FAMILY.log
    new_case_state "$DUMP_STATE" stopped
    : > "$DUMP_LOG"
    assert_fails env FAKE_STATE_DIR="$DUMP_STATE" FAKE_ADB_LOG="$DUMP_LOG" FAKE_DUMP_FAILURE="$DUMP_FAMILY" ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$DUMP_EVIDENCE"
    assert_no_device_mutation "$DUMP_LOG"
done

# A valid rollback record is insufficient when either owned firewall family is dirty.
for DIRTY_FAMILY in v4 v6; do
    DIRTY_STATE=$TMP/dirty-$DIRTY_FAMILY-state
    DIRTY_EVIDENCE=$TMP/dirty-$DIRTY_FAMILY-evidence
    DIRTY_LOG=$TMP/dirty-$DIRTY_FAMILY.log
    new_case_state "$DIRTY_STATE" running
    cp -R "$TMP/update-template-evidence" "$DIRTY_EVIDENCE"
    : > "$DIRTY_LOG"
    assert_fails run_rollback_case "$DIRTY_STATE" "$DIRTY_LOG" "$DIRTY_EVIDENCE" FAKE_FIREWALL_DIRTY="$DIRTY_FAMILY"
    [ "$(sed -n 's/^stage=//p' "$DIRTY_EVIDENCE/sequence.state")" = update ] || fail "dirty firewall advanced sequence: $DIRTY_FAMILY"
done

# Exercise the complete ordered IPv4-only flow through uninstall verification.
IPV4_STATE=$TMP/ipv4-only-state
IPV4_EVIDENCE=$TMP/ipv4-only-evidence
IPV4_LOG=$TMP/ipv4-only.log
new_case_state "$IPV4_STATE" stopped
: > "$IPV4_LOG"
env FAKE_STATE_DIR="$IPV4_STATE" FAKE_ADB_LOG="$IPV4_LOG" FAKE_IPV6_AVAILABLE=0 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$IPV4_EVIDENCE" \
    --module-zip "$MODULE_ARTIFACT" --module-sha256 "$MODULE_HASH" \
    --apk "$APK_ARTIFACT" --apk-sha256 "$APK_HASH" >/dev/null
[ "$(cat "$IPV4_EVIDENCE/capabilities.meta")" = 'schema=1
ipv4=ready
ipv6=not_available' ] || fail "IPv4-only capability record is not exact"
grep -Fxq Z2_IP6TABLES_SAVE=not_available "$IPV4_EVIDENCE/preflight/before-state/ip6tables-save.txt" ||
    fail "IPv4-only preflight lacks canonical IPv6 non-availability evidence"
for IPV4_STAGE in stop start restart; do
    env FAKE_STATE_DIR="$IPV4_STATE" FAKE_ADB_LOG="$IPV4_LOG" FAKE_IPV6_AVAILABLE=0 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
        --serial TEST-SERIAL --evidence-dir "$IPV4_EVIDENCE" --stage "$IPV4_STAGE" \
        --allow-mutations --ack-disposable-device TEST-SERIAL \
        --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
done
run_update_case "$IPV4_STATE" "$IPV4_LOG" "$IPV4_EVIDENCE" FAKE_IPV6_AVAILABLE=0 >/dev/null
env FAKE_STATE_DIR="$IPV4_STATE" FAKE_ADB_LOG="$IPV4_LOG" FAKE_IPV6_AVAILABLE=0 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$IPV4_EVIDENCE" --stage full-rollback \
    --allow-mutations --allow-full-rollback --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
env FAKE_STATE_DIR="$IPV4_STATE" FAKE_ADB_LOG="$IPV4_LOG" FAKE_IPV6_AVAILABLE=0 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$IPV4_EVIDENCE" --stage uninstall \
    --allow-mutations --allow-uninstall --ack-disposable-device TEST-SERIAL \
    --ack-recovery I_HAVE_A_TESTED_RECOVERY_PLAN >/dev/null
env FAKE_STATE_DIR="$IPV4_STATE" FAKE_ADB_LOG="$IPV4_LOG" FAKE_IPV6_AVAILABLE=0 FAKE_UNINSTALLED=1 FAKE_REBOOTED=1 ADB_BIN="$FAKE_ADB" sh "$HARNESS" \
    --serial TEST-SERIAL --evidence-dir "$IPV4_EVIDENCE" --stage uninstall-verify >/dev/null
[ "$(sed -n 's/^stage=//p' "$IPV4_EVIDENCE/sequence.state")" = uninstall-verify ] || fail "IPv4-only ordered flow did not complete"
fi

printf 'PASS: device harness host safety contracts\n'
