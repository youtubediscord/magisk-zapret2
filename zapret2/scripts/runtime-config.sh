#!/system/bin/sh
# Create or repair the v1 preset runtime from built-in defaults. This is not
# migration: no bootstrap input is read. Repair preserves non-core section
# content so all callers share one recovery contract.

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)"
ZAPRET_DIR="$(dirname "$SCRIPT_DIR")"

runtime_canonical_sha256() {
    awk '
        {
            sub(/\r$/, "")
            lines[NR]=$0
        }
        END {
            last=NR
            while (last > 0 && lines[last] == "") last--
            for (line_number=1; line_number <= last; line_number++) {
                print lines[line_number]
            }
        }
    ' "$1" | sha256sum
}

mode=init
candidate_path=""
expected_digest=""
case "${1:-}" in
    --inspect-machine) mode=inspect-machine; shift ;;
    --commit-candidate)
        mode=commit-candidate
        shift
        candidate_path="${1:-}"
        [ "$#" -gt 0 ] && shift
        expected_digest="${1:-}"
        [ "$#" -gt 0 ] && shift
        ;;
    --repair) mode=repair; shift ;;
esac
output_path="${1:-$ZAPRET_DIR/runtime.ini}"
output_dir="$(dirname "$output_path")"

[ "$#" -le 1 ] || {
    echo "ERROR: usage: runtime-config.sh [--inspect-machine|--repair] [runtime.ini]" >&2
    echo "       runtime-config.sh --commit-candidate CANDIDATE EXPECTED_SHA256 [runtime.ini]" >&2
    exit 2
}
[ -d "$output_dir" ] && [ ! -L "$output_dir" ] || {
    echo "ERROR: runtime directory is missing or unsafe" >&2; exit 1;
}
case "$output_path" in "$output_dir/runtime.ini") ;; *) echo "ERROR: unsafe runtime path" >&2; exit 1 ;; esac

if [ "$mode" = commit-candidate ]; then
    . "$SCRIPT_DIR/common.sh"
    commit_error() {
        z2_error_set CONFIG "$1" RUNTIME_COMMIT "$2" ||
            z2_error_set CONFIG RUNTIME_COMMIT_FAILED RUNTIME_COMMIT \
                "runtime.ini candidate commit failed"
        z2_error_emit_machine
        exit 1
    }
    case "$candidate_path" in
        "$output_path.candidate."*) ;;
        *) commit_error UNSAFE_RUNTIME_CANDIDATE "runtime candidate path is unsafe" ;;
    esac
    candidate_suffix="${candidate_path#"$output_path.candidate."}"
    candidate_pid="${candidate_suffix%%.*}"
    candidate_nonce="${candidate_suffix#*.}"
    [ "$candidate_nonce" != "$candidate_suffix" ] &&
        [ "${candidate_nonce#*.}" = "$candidate_nonce" ] &&
        is_decimal "$candidate_pid" && is_decimal "$candidate_nonce" ||
        commit_error UNSAFE_RUNTIME_CANDIDATE "runtime candidate identity is unsafe"
    for stale_candidate in "$output_path.candidate."*; do
        [ -e "$stale_candidate" ] || [ -L "$stale_candidate" ] || continue
        [ "$stale_candidate" != "$candidate_path" ] || continue
        stale_suffix="${stale_candidate#"$output_path.candidate."}"
        stale_pid="${stale_suffix%%.*}"
        stale_nonce="${stale_suffix#*.}"
        [ "$stale_nonce" != "$stale_suffix" ] &&
            [ "${stale_nonce#*.}" = "$stale_nonce" ] &&
            is_decimal "$stale_pid" && is_decimal "$stale_nonce" &&
            [ -f "$stale_candidate" ] && [ ! -L "$stale_candidate" ] &&
            path_uid_is_root "$stale_candidate" &&
            path_nlink_is_one "$stale_candidate" ||
            commit_error UNSAFE_RUNTIME_CANDIDATE \
                "stale runtime candidate is unsafe"
        rm -f "$stale_candidate" ||
            commit_error RUNTIME_COMMIT_FAILED \
                "stale runtime candidate cleanup failed"
    done
    trap 'rm -f "$candidate_path" 2>/dev/null' EXIT HUP INT TERM
    [ "${#expected_digest}" -eq 64 ] &&
        case "$expected_digest" in *[!0-9a-f]*) false ;; *) true ;; esac ||
        commit_error INVALID_RUNTIME_IDENTITY "expected runtime identity is invalid"
    [ -f "$candidate_path" ] && [ ! -L "$candidate_path" ] && [ -r "$candidate_path" ] ||
        commit_error UNSAFE_RUNTIME_CANDIDATE \
            "runtime candidate is not a readable regular file"
    candidate_meta="$(stat -c '%u:%a:%h:%s' "$candidate_path" 2>/dev/null)" ||
        commit_error RUNTIME_METADATA_UNAVAILABLE "runtime candidate metadata is unavailable"
    old_ifs=$IFS
    IFS=:
    set -- $candidate_meta
    IFS=$old_ifs
    [ "$#" -eq 4 ] && [ "$1" = 0 ] && [ "$3" = 1 ] ||
        commit_error UNSAFE_RUNTIME_CANDIDATE \
            "runtime candidate ownership is unsafe"
    case "$2" in 600|644) ;; *)
        commit_error UNSAFE_RUNTIME_CANDIDATE "runtime candidate mode is unsafe"
        ;;
    esac
    case "$4" in ''|*[!0-9]*)
        commit_error UNSAFE_RUNTIME_CANDIDATE "runtime candidate size is invalid"
        ;;
    esac
    [ "$4" -gt 0 ] 2>/dev/null && [ "$4" -le 262144 ] 2>/dev/null ||
        commit_error UNSAFE_RUNTIME_CANDIDATE \
            "runtime candidate size is outside the allowed envelope"
    RUNTIME_CONFIG="$candidate_path"
    set_core_config_defaults
    RUNTIME_CONFIG_ERROR=""
    apply_runtime_core_overrides ||
        commit_error CONFIG_INVALID "${RUNTIME_CONFIG_ERROR:-runtime candidate is invalid}"
    [ -f "$output_path" ] && [ ! -L "$output_path" ] &&
        path_uid_is_root "$output_path" && path_nlink_is_one "$output_path" ||
        commit_error UNSAFE_RUNTIME_FILE "runtime.ini target is unsafe"
    target_meta_before="$(stat -c '%d:%i:%u:%a:%h:%s' "$output_path" 2>/dev/null)" ||
        commit_error RUNTIME_METADATA_UNAVAILABLE "runtime.ini metadata is unavailable"
    case "$target_meta_before" in *":0:600:1:"*|*":0:644:1:"*) ;; *)
        commit_error UNSAFE_RUNTIME_FILE "runtime.ini target metadata is unsafe"
        ;;
    esac
    target_digest_before="$(runtime_canonical_sha256 "$output_path" 2>/dev/null)"
    target_digest_before="${target_digest_before%% *}"
    [ "$target_digest_before" = "$expected_digest" ] ||
        commit_error RUNTIME_SOURCE_CHANGED \
            "runtime.ini changed since the edit began"
    chmod 0644 "$candidate_path" 2>/dev/null ||
        commit_error RUNTIME_COMMIT_FAILED "runtime candidate chmod failed"
    target_meta_after="$(stat -c '%d:%i:%u:%a:%h:%s' "$output_path" 2>/dev/null)"
    target_digest_after="$(runtime_canonical_sha256 "$output_path" 2>/dev/null)"
    target_digest_after="${target_digest_after%% *}"
    [ "$target_meta_after" = "$target_meta_before" ] &&
        [ "$target_digest_after" = "$expected_digest" ] ||
        commit_error RUNTIME_SOURCE_CHANGED \
            "runtime.ini changed during candidate validation"
    mv -f "$candidate_path" "$output_path" ||
        commit_error RUNTIME_COMMIT_FAILED "runtime candidate publication failed"
    sync || commit_error RUNTIME_COMMIT_FAILED "runtime candidate sync failed"
    trap - EXIT HUP INT TERM
    z2_error_clear
    z2_error_emit_machine
    exit 0
fi

if [ "$mode" = inspect-machine ]; then
    # Reuse the exact lifecycle parser. This entry point is read-only. The
    # envelope fields are opaque to the APK; only their shape and bounds are
    # stable.
    . "$SCRIPT_DIR/common.sh"
    RUNTIME_CONFIG="$output_path"
    if ! runtime_config_exists; then
        if [ ! -e "$RUNTIME_CONFIG" ] && [ ! -L "$RUNTIME_CONFIG" ]; then
            z2_error_set CONFIG RUNTIME_MISSING RUNTIME_OPEN \
                "runtime.ini is missing"
        else
            z2_error_set CONFIG UNSAFE_RUNTIME_FILE RUNTIME_OPEN \
                "runtime.ini is not a safe root-owned regular file"
        fi
        z2_error_emit_machine
        exit 0
    fi
    runtime_meta_before="$(stat -c '%d:%i:%u:%a:%h:%s' "$RUNTIME_CONFIG" 2>/dev/null)" || {
        z2_error_set CONFIG RUNTIME_METADATA_UNAVAILABLE RUNTIME_OPEN \
            "runtime.ini metadata is unavailable"
        z2_error_emit_machine
        exit 0
    }
    runtime_digest_before="$(runtime_canonical_sha256 "$RUNTIME_CONFIG" 2>/dev/null)" || {
        z2_error_set CONFIG RUNTIME_READ_FAILED RUNTIME_OPEN \
            "runtime.ini cannot be hashed"
        z2_error_emit_machine
        exit 0
    }
    runtime_digest_before="${runtime_digest_before%% *}"
    set_core_config_defaults
    RUNTIME_CONFIG_ERROR=""
    if ! apply_runtime_core_overrides; then
        runtime_config_error_code "$RUNTIME_CONFIG_ERROR"
        z2_error_set CONFIG "$RUNTIME_CONFIG_ERROR_CODE" RUNTIME_PARSE \
            "$RUNTIME_CONFIG_ERROR" ||
            z2_error_set CONFIG CONFIG_INVALID RUNTIME_PARSE \
                "runtime.ini validation failed"
        z2_error_emit_machine
        exit 0
    fi
    runtime_meta_after="$(stat -c '%d:%i:%u:%a:%h:%s' "$RUNTIME_CONFIG" 2>/dev/null)"
    runtime_digest_after="$(runtime_canonical_sha256 "$RUNTIME_CONFIG" 2>/dev/null)"
    runtime_digest_after="${runtime_digest_after%% *}"
    if [ -z "$runtime_meta_after" ] || [ "$runtime_meta_after" != "$runtime_meta_before" ] ||
       [ "$runtime_digest_after" != "$runtime_digest_before" ]; then
        z2_error_set CONFIG RUNTIME_CHANGED RUNTIME_READ \
            "runtime.ini changed while it was being inspected"
        z2_error_emit_machine
        exit 0
    fi
    z2_error_clear
    z2_error_emit_machine
    printf 'Z2_RUNTIME_SHA256\t%s\n' "$runtime_digest_after"
    printf 'Z2_RUNTIME_CORE\tschema_version\t1\n'
    printf 'Z2_RUNTIME_CORE\tconfig_format\truntime-v1\n'
    printf 'Z2_RUNTIME_CORE\truntime_source\t%s\n' "$RUNTIME_SOURCE"
    printf 'Z2_RUNTIME_CORE\tautostart\t%s\n' "$AUTOSTART"
    printf 'Z2_RUNTIME_CORE\twifi_only\t%s\n' "$WIFI_ONLY"
    printf 'Z2_RUNTIME_CORE\tdebug\t%s\n' "$DEBUG"
    printf 'Z2_RUNTIME_CORE\tqnum\t%s\n' "$QNUM"
    printf 'Z2_RUNTIME_CORE\tdesync_mark\t%s\n' "$DESYNC_MARK"
    printf 'Z2_RUNTIME_CORE\tpkt_out\t%s\n' "$PKT_OUT"
    printf 'Z2_RUNTIME_CORE\tpkt_in\t%s\n' "$PKT_IN"
    printf 'Z2_RUNTIME_CORE\tactive_preset\t%s\n' "$ACTIVE_PRESET"
    printf 'Z2_RUNTIME_CORE\tnfqws_uid\t%s\n' "$NFQWS_UID"
    printf 'Z2_RUNTIME_CORE\tlog_mode\t%s\n' "$LOG_MODE"
    exit 0
fi

if [ -e "$output_path" ] || [ -L "$output_path" ]; then
    [ "$mode" = repair ] && [ -f "$output_path" ] && [ ! -L "$output_path" ] &&
        [ -r "$output_path" ] || {
        echo "ERROR: unsafe runtime target" >&2; exit 1;
    }
    runtime_meta="$(stat -c '%u:%a:%h:%s' "$output_path" 2>/dev/null)" || {
        echo "ERROR: runtime metadata is unavailable" >&2; exit 1;
    }
    old_ifs=$IFS
    IFS=:
    set -- $runtime_meta
    IFS=$old_ifs
    [ "$#" -eq 4 ] && [ "$1" = 0 ] && [ "$3" = 1 ] || {
        echo "ERROR: unsafe runtime ownership" >&2; exit 1;
    }
    case "$2" in 600|644) ;; *) echo "ERROR: unsafe runtime mode" >&2; exit 1 ;; esac
    case "$4" in ''|*[!0-9]*) echo "ERROR: invalid runtime size" >&2; exit 1 ;; esac
    [ "$4" -gt 0 ] 2>/dev/null && [ "$4" -le 262144 ] 2>/dev/null || {
        echo "ERROR: runtime size is outside the repair envelope" >&2; exit 1;
    }
fi

tmp="$output_path.tmp.$$"
preserved="$output_path.sections.$$"
[ ! -e "$tmp" ] && [ ! -L "$tmp" ] && [ ! -e "$preserved" ] && [ ! -L "$preserved" ] || exit 1
trap 'rm -f "$tmp" "$preserved" 2>/dev/null' EXIT HUP INT TERM
umask 077

if [ "$mode" = repair ] && [ -f "$output_path" ]; then
    awk '
        BEGIN { in_core=0; keep=0 }
        /^[[:space:]]*\[[^]]+\][[:space:]]*$/ {
            section=$0
            gsub(/^[[:space:]]*\[/, "", section)
            gsub(/\][[:space:]]*$/, "", section)
            normalized=tolower(section)
            if (normalized == "core") { in_core=1; keep=0; next }
            in_core=0
            keep=1
        }
        keep && !in_core { print }
    ' "$output_path" > "$preserved" || exit 1
else
    : > "$preserved" || exit 1
fi

{
    echo '# Zapret2 preset runtime'
    echo '[core]'
    echo 'schema_version=1'
    echo 'config_format=runtime-v1'
    if [ "$mode" = repair ]; then
        echo 'runtime_source=self-healed-runtime'
    else
        echo 'runtime_source=builtin-defaults'
    fi
    echo 'autostart=1'
    echo 'wifi_only=0'
    echo 'debug=0'
    echo 'qnum=200'
    echo 'desync_mark=0x40000000'
    echo 'pkt_out=20'
    echo 'pkt_in=10'
    echo 'active_preset=Default v1 (game filter).txt'
    echo 'nfqws_uid=0:0'
    echo 'log_mode=none'
    if [ -s "$preserved" ]; then
        echo
        cat "$preserved"
    fi
} > "$tmp" || exit 1
rm -f "$preserved" || exit 1
chmod 0644 "$tmp" 2>/dev/null || exit 1
mv -f "$tmp" "$output_path" || exit 1
tmp=
preserved=
trap - EXIT HUP INT TERM
if [ "$mode" = repair ]; then
    echo "Repaired runtime config: $output_path"
else
    echo "Initialized runtime config: $output_path"
fi
