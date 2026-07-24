#!/system/bin/sh
# Shared lifecycle, configuration and ownership helpers for zapret2.

ZAPRET_DIR="${ZAPRET_DIR:-$(dirname "$SCRIPT_DIR")}"
MODDIR="${MODDIR:-$(dirname "$ZAPRET_DIR")}"

umask 077

# Stable adapter-owned error protocol shared by module scripts and the Android
# app. The app validates only these bounds and displays all identity fields
# opaquely, so adding a future domain, stage or code does not require an APK.
Z2_ERROR_SCHEMA_VERSION=1
Z2_ERROR_DETAIL_MAX_BYTES=512

z2_error_token_is_valid() {
    [ -n "$1" ] && [ "${#1}" -le 64 ] || return 1
    case "$1" in *[!A-Z0-9_]*) return 1 ;; esac
}

z2_error_detail_normalize() {
    printf '%s' "$1" | tr '\r\n\t' '   ' | cut -b "1-$Z2_ERROR_DETAIL_MAX_BYTES"
}

z2_error_detail_is_valid() {
    local normalized
    normalized="$(z2_error_detail_normalize "$1")"
    [ "$1" = "$normalized" ]
}

z2_error_fields_are_valid() {
    local status="$1" domain="$2" stage="$3" code="$4" detail="$5"
    case "$status" in OK|ERROR) ;; *) return 1 ;; esac
    z2_error_token_is_valid "$domain" && z2_error_token_is_valid "$stage" &&
        z2_error_token_is_valid "$code" && z2_error_detail_is_valid "$detail" || return 1
    if [ "$status" = OK ]; then
        [ "$domain" = NONE ] && [ "$stage" = NONE ] && [ "$code" = NONE ] &&
            [ -z "$detail" ]
    else
        [ "$domain" != NONE ] && [ "$stage" != NONE ] && [ "$code" != NONE ] &&
            [ -n "$detail" ]
    fi
}

z2_error_set() {
    local detail
    detail="$(z2_error_detail_normalize "$4")"
    z2_error_fields_are_valid ERROR "$1" "$3" "$2" "$detail" || return 1
    Z2_ERROR_STATUS=ERROR
    Z2_ERROR_DOMAIN="$1"
    Z2_ERROR_CODE="$2"
    Z2_ERROR_STAGE="$3"
    Z2_ERROR_DETAIL="$detail"
}

z2_error_clear() {
    Z2_ERROR_STATUS=OK
    Z2_ERROR_DOMAIN=NONE
    Z2_ERROR_STAGE=NONE
    Z2_ERROR_CODE=NONE
    Z2_ERROR_DETAIL=""
}

z2_error_emit_machine() {
    z2_error_fields_are_valid "${Z2_ERROR_STATUS:-}" "${Z2_ERROR_DOMAIN:-}" \
        "${Z2_ERROR_STAGE:-}" "${Z2_ERROR_CODE:-}" "${Z2_ERROR_DETAIL:-}" || return 1
    printf 'Z2_ERROR_SCHEMA=%s\n' "$Z2_ERROR_SCHEMA_VERSION"
    printf 'Z2_ERROR_STATUS=%s\n' "$Z2_ERROR_STATUS"
    printf 'Z2_ERROR_DOMAIN=%s\n' "$Z2_ERROR_DOMAIN"
    printf 'Z2_ERROR_STAGE=%s\n' "$Z2_ERROR_STAGE"
    printf 'Z2_ERROR_CODE=%s\n' "$Z2_ERROR_CODE"
    printf 'Z2_ERROR_DETAIL=%s\n' "$Z2_ERROR_DETAIL"
}

z2_error_clear

# All live privileged state is kept below one fixed root-only directory.  The
# old /data/local/tmp names are migration inputs only and are never normal
# lifecycle write/delete targets.
STATE_DIR="${STATE_DIR:-/data/adb/zapret2-state}"
PIDFILE="$STATE_DIR/nfqws2.pid"
OWNER_STATE="$STATE_DIR/owner.meta"
LOGFILE="$STATE_DIR/nfqws2.log"
LOGFILE_PREVIOUS="$STATE_DIR/nfqws2.log.1"
LOG_MAX_BYTES=1048576
CMDLINE_FILE="$STATE_DIR/nfqws2.cmdline"
COMPILED_ARGV_FILE="$STATE_DIR/nfqws2.argv"
STARTUP_LOG="$STATE_DIR/nfqws2.startup.log"
ERROR_LOG="$STATE_DIR/nfqws2.error"
DEBUG_LOG="$STATE_DIR/nfqws2-debug.log"
RUNTIME_OWNER_MARKER="$STATE_DIR/runtime.owner"
STATUS_SNAPSHOT="$STATE_DIR/status.snapshot"
RUNTIME_METADATA_MAX_BYTES=262144
# Legacy owner schemas duplicated a command of up to 256 KiB as hex. Keep a
# read-only compatibility envelope for those records, while every current
# record stores only a fixed-size SHA-256 identity and has a much tighter cap.
OWNER_STATE_MAX_BYTES=1048576
OWNER_STATE_CURRENT_MAX_BYTES=65536

RUNTIME_CONFIG="$ZAPRET_DIR/runtime.ini"

NFQWS2="$ZAPRET_DIR/nfqws2"
LISTS_DIR="$ZAPRET_DIR/lists"
PRESETS_DIR="$ZAPRET_DIR/presets"
STRATEGY_CATALOGS_DIR="$ZAPRET_DIR/strategy-catalogs"

ZAPRET2_OUT="ZAPRET2_OUT"
ZAPRET2_IN="ZAPRET2_IN"
ZAPRET2_PROBE="ZAPRET2_PROBE"
IPTABLES_STATUS="$STATUS_SNAPSHOT"
LEGACY_IPTABLES_STATUS="$ZAPRET_DIR/iptables-status"
LIFECYCLE_LOCK="$STATE_DIR/lifecycle.lock"
LIFECYCLE_LOCK_OWNER="$LIFECYCLE_LOCK/owner"
LIFECYCLE_LOCK_REAPER="$STATE_DIR/lifecycle.lock.reaper"
LIFECYCLE_LOCK_REAPER_RECOVERY="$STATE_DIR/lifecycle.lock.reaper.recovery"
LIFECYCLE_LOCK_REAPER_RECOVERY_QUARANTINE="$STATE_DIR/lifecycle.lock.reaper.recovery.quarantine"
LIFECYCLE_LOCK_QUARANTINE="$STATE_DIR/lifecycle.lock.quarantine"
LIFECYCLE_LOCK_WAIT_SECONDS="${LIFECYCLE_LOCK_WAIT_SECONDS:-60}"
UNINSTALL_TOMBSTONE="$STATE_DIR/uninstall.tombstone"
UNINSTALL_TOMBSTONE_VERSION=1
PURGE_REQUEST="$STATE_DIR/purge.request"
FULL_ROLLBACK_TRANSACTION="$STATE_DIR/full-rollback.transaction"
FULL_ROLLBACK_META="$STATE_DIR/full-rollback.meta"
FULL_ROLLBACK_HOSTS_BACKUP="$STATE_DIR/hosts.rollback.backup"
FULL_ROLLBACK_VERSION=1
INSTALL_GENERATION_META="$ZAPRET_DIR/install-generation.meta"
INSTALL_GENERATION_VERSION=1
LEGACY_MIGRATION_MARKER="$STATE_DIR/legacy-direct-rules.migrated"
OWNER_STATE_VERSION=7
OWNER_STATE_V3_FIELD_SEQUENCE="version|pid|starttime|argv_hex|qnum|exe|generation|phase"
OWNER_STATE_V4_FIELD_SEQUENCE="version|pid|starttime|argv_hex|qnum|exe|generation|phase|install_generation|install_archive_sha256|ports_tcp|ports_udp|stun_ports|pkt_out|pkt_in|desync_mark|ipv4_active|ipv6_active|ipv4_connbytes|ipv4_multiport|ipv4_mark|ipv6_connbytes|ipv6_multiport|ipv6_mark|ipv4_rules|ipv6_rules|ipv4_spec|ipv6_spec|firewall_fingerprint"
OWNER_STATE_V5_FIELD_SEQUENCE="version|pid|starttime|argv_hex|qnum|exe|generation|boot_id|phase|install_generation|install_archive_sha256|ports_tcp|ports_udp|stun_ports|pkt_out|pkt_in|desync_mark|ipv4_active|ipv6_active|ipv4_connbytes|ipv4_multiport|ipv4_mark|ipv6_connbytes|ipv6_multiport|ipv6_mark|ipv4_rules|ipv6_rules|ipv4_spec|ipv6_spec|firewall_fingerprint"
OWNER_STATE_V6_FIELD_SEQUENCE="version|pid|starttime|argv_hex|qnum|exe|generation|boot_id|phase|install_generation|install_archive_sha256|firewall_tag|out_chain|in_chain|ports_tcp|ports_udp|stun_ports|pkt_out|pkt_in|desync_mark|ipv4_active|ipv6_active|ipv4_connbytes|ipv4_multiport|ipv4_mark|ipv6_connbytes|ipv6_multiport|ipv6_mark|ipv4_rules|ipv6_rules|ipv4_spec|ipv6_spec|firewall_fingerprint"
OWNER_STATE_V7_FIELD_SEQUENCE="version|pid|starttime|argv_sha256|qnum|exe|generation|boot_id|phase|install_generation|install_archive_sha256|firewall_tag|out_chain|in_chain|ports_tcp|ports_udp|stun_ports|pkt_out|pkt_in|desync_mark|ipv4_active|ipv6_active|ipv4_connbytes|ipv4_multiport|ipv4_mark|ipv6_connbytes|ipv6_multiport|ipv6_mark|ipv4_rules|ipv6_rules|ipv4_spec|ipv6_spec|firewall_fingerprint"
TRACK_JOURNAL_VERSION=2
TEARDOWN_JOURNAL="$STATE_DIR/firewall-teardown.wal"
TEARDOWN_JOURNAL_VERSION=2

export STATE_DIR PIDFILE OWNER_STATE LOGFILE LOGFILE_PREVIOUS CMDLINE_FILE COMPILED_ARGV_FILE
export STARTUP_LOG ERROR_LOG DEBUG_LOG RUNTIME_OWNER_MARKER STATUS_SNAPSHOT
export LIFECYCLE_LOCK LIFECYCLE_LOCK_OWNER LIFECYCLE_LOCK_REAPER
export LIFECYCLE_LOCK_REAPER_RECOVERY LIFECYCLE_LOCK_REAPER_RECOVERY_QUARANTINE
export LIFECYCLE_LOCK_QUARANTINE UNINSTALL_TOMBSTONE
export PURGE_REQUEST
export FULL_ROLLBACK_TRANSACTION FULL_ROLLBACK_META FULL_ROLLBACK_HOSTS_BACKUP
export INSTALL_GENERATION_META LEGACY_MIGRATION_MARKER
export TEARDOWN_JOURNAL

CORE_CONFIG_SOURCE="defaults"
CORE_CONFIG_SOURCE_PATH="built-in defaults"
RUNTIME_CONFIG_STATUS="unknown"
RUNTIME_CONFIG_REASON=""
RUNTIME_CONFIG_ERROR=""
RUNTIME_CORE_REPAIR_MODE="defaults"
RUNTIME_CORE_REQUIRED_KEYS="schema_version config_format runtime_source autostart wifi_only debug qnum desync_mark pkt_out pkt_in active_preset nfqws_uid log_mode"

is_decimal() {
    case "$1" in
        ""|*[!0-9]*) return 1 ;;
        *) return 0 ;;
    esac
}

is_canonical_positive_decimal() {
    case "$1" in ""|0*|*[!0-9]*) return 1 ;; *) return 0 ;; esac
}

is_canonical_nonnegative_i64() {
    local value="$1" digits first rest
    case "$value" in 0) return 0 ;; ""|0*|*[!0-9]*) return 1 ;; esac
    digits=${#value}
    [ "$digits" -lt 19 ] 2>/dev/null && return 0
    [ "$digits" -eq 19 ] 2>/dev/null || return 1
    first=${value%"${value#?}"}
    rest=${value#?}
    [ "$first" -lt 9 ] 2>/dev/null && return 0
    [ "$first" -eq 9 ] 2>/dev/null && [ "$rest" -le 223372036854775807 ] 2>/dev/null
}

is_canonical_nfqws_id() {
    local value="$1" digits
    case "$value" in 0) return 0 ;; ""|0*|*[!0-9]*) return 1 ;; esac
    digits=${#value}
    [ "$digits" -lt 10 ] 2>/dev/null && return 0
    [ "$digits" -eq 10 ] 2>/dev/null && [ "$value" -le 2147483647 ] 2>/dev/null
}

path_uid_is_root() {
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

state_dir_is_secure() {
    local mode listing
    [ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || return 1
    path_uid_is_root "$STATE_DIR" || return 1
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$STATE_DIR" 2>/dev/null)" || return 1
        [ "$mode" = 700 ]
        return
    fi
    listing="$(ls -ldn "$STATE_DIR" 2>/dev/null)" || return 1
    set -- $listing
    case "${1:-}" in drwx------*) return 0 ;; *) return 1 ;; esac
}

ensure_state_dir() {
    umask 077
    if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then
        [ -d "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ] || return 1
        path_uid_is_root "$STATE_DIR" || return 1
    else
        mkdir "$STATE_DIR" 2>/dev/null || return 1
    fi
    chmod 0700 "$STATE_DIR" 2>/dev/null || return 1
    state_dir_is_secure
}

state_path_is_managed_file() {
    local suffix
    case "$1" in
        "$STATE_DIR"/*)
            suffix="${1#"$STATE_DIR"/}"
            [ -n "$suffix" ] || return 1
            case "$suffix" in */*) return 1 ;; esac
            return 0
            ;;
        *) return 1 ;;
    esac
}

state_file_is_secure() {
    state_dir_is_secure || return 1
    state_path_is_managed_file "$1" || return 1
    [ -f "$1" ] && [ ! -L "$1" ] || return 1
    path_uid_is_root "$1"
}

state_file_target_is_safe() {
    state_dir_is_secure || return 1
    state_path_is_managed_file "$1" || return 1
    [ ! -e "$1" ] && [ ! -L "$1" ] && return 0
    state_file_is_secure "$1"
}

if ! is_decimal "$LIFECYCLE_LOCK_WAIT_SECONDS" || [ "$LIFECYCLE_LOCK_WAIT_SECONDS" -lt 1 ] 2>/dev/null; then
    LIFECYCLE_LOCK_WAIT_SECONDS=60
fi

is_safe_token() {
    case "$1" in
        ""|*[!A-Za-z0-9._-]*) return 1 ;;
        *) return 0 ;;
    esac
}

is_lower_sha256() {
    [ "${#1}" -eq 64 ] 2>/dev/null || return 1
    case "$1" in *[!0-9a-f]*) return 1 ;; *) return 0 ;; esac
}

path_mode_is_0600() {
    local path="$1" mode listing
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
        [ "$mode" = 600 ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    set -- $listing
    case "${1:-}" in -rw-------*) return 0 ;; *) return 1 ;; esac
}

path_nlink_is_one() {
    local path="$1" links listing
    if command -v stat >/dev/null 2>&1; then
        links="$(stat -c '%h' "$path" 2>/dev/null)" || return 1
        [ "$links" = 1 ]
        return
    fi
    listing="$(ls -ldn "$path" 2>/dev/null)" || return 1
    set -- $listing
    [ "$#" -ge 2 ] && [ "$2" = 1 ]
}

INSTALL_META_GENERATION=""
INSTALL_META_ARCHIVE_SHA256=""
read_install_generation_meta() {
    local path="${1:-$INSTALL_GENERATION_META}" key value version="" module="" generation="" archive="" seen="" size
    INSTALL_META_GENERATION=""; INSTALL_META_ARCHIVE_SHA256=""
    [ -f "$path" ] && [ ! -L "$path" ] && path_uid_is_root "$path" &&
        path_mode_is_0600 "$path" && path_nlink_is_one "$path" || return 1
    size="$(wc -c < "$path" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null && [ "$size" -le 1024 ] 2>/dev/null || return 1
    while :; do
        key=""; value=""
        IFS='=' read -r key value || [ -n "$key$value" ] || break
        case "$key" in
            version) case "$seen" in *v*) return 1;; esac; version="$value"; seen="${seen}v" ;;
            module_dir) case "$seen" in *m*) return 1;; esac; module="$value"; seen="${seen}m" ;;
            generation) case "$seen" in *g*) return 1;; esac; generation="$value"; seen="${seen}g" ;;
            archive_sha256) case "$seen" in *a*) return 1;; esac; archive="$value"; seen="${seen}a" ;;
            *) return 1 ;;
        esac
    done < "$path"
    [ "${#seen}" -eq 4 ] 2>/dev/null && [ "$version" = "$INSTALL_GENERATION_VERSION" ] && [ "$module" = "$MODDIR" ] || return 1
    is_safe_token "$generation" && [ "${#generation}" -le 128 ] 2>/dev/null || return 1
    is_lower_sha256 "$archive" || return 1
    INSTALL_META_GENERATION="$generation"; INSTALL_META_ARCHIVE_SHA256="$archive"
}

RECOVERY_ARTIFACT_DIAGNOSTIC=""
RECOVERY_ARTIFACT_CLASS="clean"
RECOVERY_ARTIFACT_FIRST=""

CURRENT_BOOT_ID=""
STALE_TRACK_FILES=""
STALE_TRACK_DIAGNOSTIC=""
STALE_OWNER_PUBLICATION_RETIRED=0
BOOT_RECOVERY_DIAGNOSTIC=""

is_valid_boot_id() {
    local value="$1"
    [ "${#value}" -eq 36 ] 2>/dev/null || return 1
    case "$value" in
        ????????-????-????-????-????????????) ;;
        *) return 1 ;;
    esac
    case "$value" in *[!0-9a-f-]*) return 1;; esac
}

read_current_boot_id() {
    local value
    IFS= read -r value < /proc/sys/kernel/random/boot_id 2>/dev/null || return 1
    is_valid_boot_id "$value" || return 1
    CURRENT_BOOT_ID="$value"
}

validate_track_journal_identity() {
    local path="$1" base expected_mode expected_tool expected_pid identity old_ifs
    state_file_is_secure "$path" && path_mode_is_0600 "$path" && path_nlink_is_one "$path" || return 1
    [ "$(wc -c < "$path" 2>/dev/null)" -le 131072 ] 2>/dev/null || return 1
    base="${path##*/}"
    case "$base" in
        build-track.ipv4.*) expected_mode=build; expected_tool=iptables; expected_pid="${base#build-track.ipv4.}" ;;
        build-track.ipv6.*) expected_mode=build; expected_tool=ip6tables; expected_pid="${base#build-track.ipv6.}" ;;
        probe-track.ipv4.*) expected_mode=probe; expected_tool=iptables; expected_pid="${base#probe-track.ipv4.}" ;;
        probe-track.ipv6.*) expected_mode=probe; expected_tool=ip6tables; expected_pid="${base#probe-track.ipv6.}" ;;
        *) return 1 ;;
    esac
    is_decimal "$expected_pid" && [ "$expected_pid" -gt 0 ] 2>/dev/null || return 1
    identity="$(awk -F '|' -v mode="$expected_mode" -v tool="$expected_tool" -v module="$MODDIR" \
        -v pid="$expected_pid" -v probe="$ZAPRET2_PROBE" '
        function derive(chain, tag) {
            if (length(chain) != 14 || (substr(chain,1,4) != "Z2O_" && substr(chain,1,4) != "Z2I_")) return 0
            tag=substr(chain,5)
            if (length(tag) != 10 || tag !~ /^[A-Za-z0-9]+$/) return 0
            firewall_tag=tag
            outchain="Z2O_" tag
            inchain="Z2I_" tag
            ruleprefix="Z2R_" tag "_"
            identity_ready=1
            return 1
        }
        function ruleside(chain, suffix) {
            if (!identity_ready) return ""
            if (index(chain,ruleprefix)!=1) return ""
            suffix=substr(chain,length(ruleprefix)+1)
            if (suffix ~ /^O[1-9][0-9]*$/) return "O"
            if (suffix ~ /^I[1-9][0-9]*$/) return "I"
            return ""
        }
        NR == 1 { if ($0 != "version=2") exit 1; next }
        NR == 2 { if ($0 != "mode=" mode) exit 1; next }
        NR == 3 { if ($0 != "tool=" tool) exit 1; next }
        NR == 4 { if ($0 != "module_dir=" module) exit 1; next }
        NR == 5 { split($0,a,"="); if (a[1] != "creator_pid" || a[2] != pid || a[2] !~ /^[1-9][0-9]*$/) exit 1; creator=a[2]; next }
        NR == 6 { split($0,a,"="); if (a[1] != "creator_starttime" || a[2] !~ /^[0-9]+$/) exit 1; start=a[2]; next }
        NR == 7 { split($0,a,"="); if (a[1] != "boot_id" || a[2] == "") exit 1; boot=a[2]; next }
        NR > 7 {
            if ($1 != "record" || $2 !~ /^[1-9][0-9]*$/ || $2 != expected + 1) exit 1
            expected=$2
            if ($3 !~ /^(pending|applied|consuming|consumed)$/) exit 1
            if (mode == "build" && $4 == "chain") {
                if (!identity_ready && !derive($5)) exit 1
                if (NF != 5 || ($5 != outchain && $5 != inchain && ruleside($5)=="")) exit 1
            } else if (mode == "build" && $4 == "anchor") {
                if (!identity_ready) exit 1
                if (NF != 6 || !(($5 == "OUTPUT" && $6 == outchain) || ($5 == "INPUT" && $6 == inchain) ||
                    ($5 == outchain && ruleside($6)=="O") || ($5 == inchain && ruleside($6)=="I"))) exit 1
            } else if (mode == "build" && $4 == "rule") {
                if (!identity_ready) exit 1
                if (NF != 15 || ruleside($5)=="" || $6 !~ /^(tcp|udp)$/ ||
                    $8 !~ /^[0-9]+(:[0-9]+)?(,[0-9]+(:[0-9]+)?)*$/ ||
                    $9 !~ /^[0-9]+$/ || $11 !~ /^[0-9]+$/ ||
                    $12 !~ /^(0x)?[0-9A-Fa-f]+$/ || $13 !~ /^(0|1)$/ || $14 !~ /^(0|1)$/ || $15 !~ /^(0|1)$/) exit 1
                if (ruleside($5)=="O" && ($7 != "out" || $10 != "original")) exit 1
                if (ruleside($5)=="I" && ($7 != "in" || $10 != "reply")) exit 1
            } else if (mode == "probe" && $4 == "chain") {
                if (NF != 5 || $5 != probe) exit 1
            } else if (mode == "probe" && $4 == "probe_rule") {
                if (NF != 8 || $5 !~ /^(queue_bypass|queue|connbytes|multiport|mark)$/ ||
                    $6 !~ /^[0-9]+$/ || $7 !~ /^[0-9]+$/ || $8 !~ /^(0x)?[0-9A-Fa-f]+$/) exit 1
            } else exit 1
            for (i=5; i<=NF; i++) if ($i == "" || $i !~ /^[A-Za-z0-9_,:.-]+$/) exit 1
        }
        END {
            if (NR < 7) exit 1
            if (mode == "build") {
                if (expected > 0 && !identity_ready) exit 1
                if (!identity_ready) firewall_tag=outchain=inchain="none"
            } else {
                firewall_tag="probe"; outchain=inchain=probe
            }
            print creator "|" start "|" boot "|" firewall_tag "|" outchain "|" inchain
        }
    ' "$path" 2>/dev/null)" || return 1
    old_ifs="$IFS"; IFS='|'; set -- $identity; IFS="$old_ifs"
    [ "$#" -eq 6 ] || return 1
    TRACK_CREATOR_PID="$1"; TRACK_CREATOR_START="$2"; TRACK_BOOT_ID="$3"
    TRACK_FIREWALL_TAG="$4"; TRACK_OUT_CHAIN="$5"; TRACK_IN_CHAIN="$6"
    TRACK_JOURNAL_MODE="$expected_mode"
    TRACK_JOURNAL_TOOL="$expected_tool"
    is_valid_boot_id "$TRACK_BOOT_ID"
}

track_journal_is_terminal() {
    local path="$1"
    # validate_track_journal_identity() has already authenticated the complete
    # grammar. A journal with no records, or only durable consumed records, no
    # longer protects an uncommitted firewall mutation. In particular, it is
    # safe to retire without querying a family whose iptables frontend is
    # present but unusable on this kernel.
    awk -F '|' '
        $1 == "record" && $3 != "consumed" { terminal=0; exit }
        BEGIN { terminal=1 }
        END { exit !terminal }
    ' "$path" 2>/dev/null
}

track_creator_liveness() {
    local actual
    TRACK_CREATOR_LIVENESS=unknown
    [ -n "$CURRENT_BOOT_ID" ] || read_current_boot_id || return 1
    if [ "$TRACK_BOOT_ID" != "$CURRENT_BOOT_ID" ]; then
        TRACK_CREATOR_LIVENESS=stale
        return 0
    fi
    if actual="$(proc_starttime "$TRACK_CREATOR_PID" 2>/dev/null)"; then
        if [ "$actual" = "$TRACK_CREATOR_START" ]; then
            TRACK_CREATOR_LIVENESS=live
        else
            # The numeric PID was reused; the journal's exact creator identity
            # is dead even though another process now owns that PID.
            TRACK_CREATOR_LIVENESS=stale
        fi
        return 0
    fi
    if [ ! -e "/proc/$TRACK_CREATOR_PID" ] && [ ! -L "/proc/$TRACK_CREATOR_PID" ]; then
        TRACK_CREATOR_LIVENESS=stale
    fi
    return 0
}

same_boot_track_creator_is_stably_stale() {
    local path="$1" pid="$TRACK_CREATOR_PID" start="$TRACK_CREATOR_START"
    local boot="$TRACK_BOOT_ID" mode="$TRACK_JOURNAL_MODE" tool="$TRACK_JOURNAL_TOOL"
    local tag="$TRACK_FIREWALL_TAG" out="$TRACK_OUT_CHAIN" in="$TRACK_IN_CHAIN"
    track_creator_liveness || return 1
    [ "$TRACK_CREATOR_LIVENESS" = stale ] || return 1
    sleep 1
    validate_track_journal_identity "$path" || return 1
    [ "$TRACK_CREATOR_PID" = "$pid" ] && [ "$TRACK_CREATOR_START" = "$start" ] &&
        [ "$TRACK_BOOT_ID" = "$boot" ] && [ "$TRACK_JOURNAL_MODE" = "$mode" ] &&
        [ "$TRACK_JOURNAL_TOOL" = "$tool" ] && [ "$TRACK_FIREWALL_TAG" = "$tag" ] &&
        [ "$TRACK_OUT_CHAIN" = "$out" ] && [ "$TRACK_IN_CHAIN" = "$in" ] || return 1
    track_creator_liveness || return 1
    [ "$TRACK_CREATOR_LIVENESS" = stale ]
}

track_generation_absent() {
    local listing
    [ "$TRACK_JOURNAL_MODE" = build ] && [ "$TRACK_FIREWALL_TAG" != none ] || return 1
    listing="$("$TRACK_JOURNAL_TOOL" -t mangle -S 2>/dev/null)" || return 1
    printf '%s\n' "$listing" | awk \
        -v out="$TRACK_OUT_CHAIN" -v inchain="$TRACK_IN_CHAIN" \
        -v prefix="Z2R_${TRACK_FIREWALL_TAG}_" '
        $1 == "-N" && ($2 == out || $2 == inchain || index($2,prefix)==1) { found=1 }
        $1 == "-A" {
            for (i=3; i<=NF; i++) {
                if (($i=="-j" || $i=="--jump" || $i=="-g" || $i=="--goto") &&
                    ($(i+1)==out || $(i+1)==inchain || index($(i+1),prefix)==1)) found=1
            }
        }
        END { exit found ? 1 : 0 }
    '
}

authenticated_published_owner_generation_healthy() {
    read_verified_pidfile || return 1
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
    case "$OWNER_STATE_PHASE" in launched|active) ;; *) return 1 ;; esac
    [ "$OWNER_STATE_IPV4_ACTIVE" = 1 ] || return 1
    owner_family_generation_healthy iptables ipv4 || return 1
    if command -v ip6tables >/dev/null 2>&1; then
        owner_family_generation_healthy ip6tables ipv6 || return 1
    else
        [ "$OWNER_STATE_IPV6_ACTIVE" = 0 ] || return 1
    fi
    return 0
}

stale_track_file_is_known() {
    local wanted="$1" item
    for item in $STALE_TRACK_FILES; do [ "$item" = "$wanted" ] && return 0; done
    return 1
}

stale_track_clean_ownership_proof() {
    local scope="${1:-generation}" tool family_state canonical_nfqws effective_nfqws candidate checked=""
    case "$scope" in generation|namespace) ;; *) return 1 ;; esac
    effective_nfqws="${AUDIT_NFQWS2_OVERRIDE:-$NFQWS2}"
    scan_exact_owned_nfqws_for_path "$effective_nfqws" >/dev/null 2>&1 || return 1
    [ -z "$OWNED_SCAN_PIDS" ] || return 1
    canonical_nfqws="$MODDIR/zapret2/nfqws2"
    checked="|$effective_nfqws|"
    for candidate in "$canonical_nfqws" "$NFQWS2"; do
        case "$checked" in *"|$candidate|"*) continue;; esac
        scan_exact_owned_nfqws_for_path "$candidate" >/dev/null 2>&1 || return 1
        [ -z "$OWNED_SCAN_PIDS" ] || return 1
        checked="${checked}${candidate}|"
    done
    for tool in $STALE_TRACK_REQUIRED_TOOLS; do command -v "$tool" >/dev/null 2>&1 || return 1; done
    for tool in iptables ip6tables; do
        command -v "$tool" >/dev/null 2>&1 || continue
        if [ "$scope" = namespace ]; then
            zapret2_namespace_present "$tool" >/dev/null 2>&1
        else
            owned_family_present "$tool" >/dev/null 2>&1
        fi
        family_state=$?
        case "$family_state" in 1) ;; *) return 1;; esac
    done
    return 0
}

INSTALLER_TRACKS_RETIRED=0

# Build/probe journals are private write-ahead logs of one serialized runtime
# lifecycle operation. They are not part of the installed configuration and
# must never become an ABI gate between an old live module and a newly staged
# Magisk release. Once customize.sh owns the exact lifecycle lock, no runtime
# operation can still own or append one of these files. The installer may
# therefore retire the bounded canonical files without parsing versioned WAL
# contents or requiring the old release's partially mutated firewall namespace
# to be empty. Runtime start/stop audits remain strict and keep using the WAL
# grammar for recovery classification.
retire_installer_ephemeral_track_journals() {
    local path base suffix found=0 retired="" restore_noglob=0
    INSTALLER_TRACKS_RETIRED=0
    caller_holds_exact_lifecycle_lock || return 1
    state_dir_is_secure || return 1

    case "$-" in *f*) restore_noglob=1; set +f;; esac
    set -- "$STATE_DIR"/build-track.* "$STATE_DIR"/probe-track.*
    [ "$restore_noglob" = 1 ] && set -f
    for path in "$@"; do
        { [ -e "$path" ] || [ -L "$path" ]; } || continue
        found=1
        base="${path##*/}"
        case "$base" in
            build-track.ipv4.*) suffix="${base#build-track.ipv4.}" ;;
            build-track.ipv6.*) suffix="${base#build-track.ipv6.}" ;;
            probe-track.ipv4.*) suffix="${base#probe-track.ipv4.}" ;;
            probe-track.ipv6.*) suffix="${base#probe-track.ipv6.}" ;;
            *) return 1 ;;
        esac
        is_decimal "$suffix" && [ "$suffix" -gt 0 ] 2>/dev/null || return 1
        state_file_is_secure "$path" && path_mode_is_0600 "$path" &&
            path_nlink_is_one "$path" || return 1
        [ "$(wc -c < "$path" 2>/dev/null)" -le 131072 ] 2>/dev/null || return 1
        retired="${retired}${retired:+ }$path"
    done
    [ "$found" = 1 ] || return 0

    for path in $retired; do rm -f "$path" 2>/dev/null || return 1; done
    sync >/dev/null 2>&1 || return 1
    for path in $retired; do
        [ ! -e "$path" ] && [ ! -L "$path" ] || return 1
    done
    INSTALLER_TRACKS_RETIRED=1
    return 0
}

caller_holds_exact_lifecycle_lock() {
    case "$LOCK_HELD" in 1|inherited) ;; *) return 1;; esac
    read_lock_owner && lock_owner_alive || return 1
    [ "$LOCK_FILE_PID" = "$LOCK_OWNER_PID" ] && [ "$LOCK_FILE_START" = "$LOCK_OWNER_START" ] &&
        [ "$LOCK_FILE_TOKEN" = "$LOCK_OWNER_TOKEN" ]
}

recover_stale_owner_publication() {
    local current_boot pidfile_pid legacy=0
    STALE_OWNER_PUBLICATION_RETIRED=0
    { [ -e "$OWNER_STATE" ] || [ -L "$OWNER_STATE" ] || [ -e "$PIDFILE" ] || [ -L "$PIDFILE" ]; } || return 0
    # The owner record is the authenticated commit marker.  A bare pidfile can
    # never prove that a PID belongs to this installation.
    [ -e "$OWNER_STATE" ] && [ ! -L "$OWNER_STATE" ] && read_owner_state || {
        STALE_TRACK_DIAGNOSTIC="unauthenticated owner publication remains"
        return 1
    }
    case "$OWNER_STATE_SCHEMA_VERSION" in
        3|4|5|6) legacy=1 ;;
        "$OWNER_STATE_VERSION") ;;
        *) STALE_TRACK_DIAGNOSTIC="owner publication schema is unsupported"; return 1 ;;
    esac
    if [ -e "$PIDFILE" ] || [ -L "$PIDFILE" ]; then
        [ ! -L "$PIDFILE" ] && state_file_is_secure "$PIDFILE" || {
            STALE_TRACK_DIAGNOSTIC="unsafe pidfile accompanies owner publication"
            return 1
        }
        IFS= read -r pidfile_pid < "$PIDFILE" 2>/dev/null || return 1
        is_decimal "$pidfile_pid" && [ "$pidfile_pid" = "$OWNER_STATE_PID" ] || {
            STALE_TRACK_DIAGNOSTIC="pidfile and owner publication disagree"
            return 1
        }
    fi
    read_current_boot_id || { STALE_TRACK_DIAGNOSTIC="current boot identity is unavailable"; return 1; }
    current_boot="$CURRENT_BOOT_ID"
    if [ "$legacy" = 0 ] && [ "$OWNER_STATE_BOOT_ID" = "$current_boot" ]; then
        verify_nfqws_pid "$OWNER_STATE_PID" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" && return 0
        STALE_TRACK_DIAGNOSTIC="same-boot owner/PID ambiguity remains"
        return 1
    fi
    if { [ "$OWNER_STATE_SCHEMA_VERSION" = 5 ] || [ "$OWNER_STATE_SCHEMA_VERSION" = 6 ]; } &&
       [ "$OWNER_STATE_BOOT_ID" = "$current_boot" ]; then
        STALE_TRACK_DIAGNOSTIC="same-boot legacy owner requires an explicit restart migration"
        return 1
    fi
    STALE_TRACK_REQUIRED_TOOLS=iptables
    [ "$OWNER_STATE_SCHEMA_VERSION" != 3 ] && [ "$OWNER_STATE_IPV6_ACTIVE" = 1 ] &&
        STALE_TRACK_REQUIRED_TOOLS="$STALE_TRACK_REQUIRED_TOOLS ip6tables"
    stale_track_clean_ownership_proof generation || {
        if [ "$legacy" = 1 ]; then
            STALE_TRACK_DIAGNOSTIC="legacy owner recovery lacks a clean process/firewall snapshot"
        else
            STALE_TRACK_DIAGNOSTIC="cross-boot owner recovery lacks a clean process/firewall snapshot"
        fi
        return 1
    }
    # Audits before lock acquisition may classify this state, but only the
    # exact lifecycle-lock owner may retire durable metadata.
    caller_holds_exact_lifecycle_lock || return 0
    if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
        [ "$legacy" != 1 ] && validate_teardown_operation_journal || {
                STALE_TRACK_DIAGNOSTIC="teardown journal does not authenticate to the stale owner"
                return 1
            }
    fi
    if [ "${BOOT_STALE_RUNTIME_RECOVERY:-0}" = 1 ] &&
       { [ -e "$STATUS_SNAPSHOT" ] || [ -L "$STATUS_SNAPSHOT" ]; }; then
        state_file_is_secure "$STATUS_SNAPSHOT" &&
            path_mode_is_0600 "$STATUS_SNAPSHOT" && path_nlink_is_one "$STATUS_SNAPSHOT" || {
                STALE_TRACK_DIAGNOSTIC="stale status snapshot is unsafe"
                return 1
            }
    fi
    if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
        rm -f "$TEARDOWN_JOURNAL" || return 1
        sync || return 1
    fi
    if [ -e "$PIDFILE" ]; then rm -f "$PIDFILE" || return 1; sync || return 1; fi
    if [ "${BOOT_STALE_RUNTIME_RECOVERY:-0}" = 1 ] && [ -e "$STATUS_SNAPSHOT" ]; then
        rm -f "$STATUS_SNAPSHOT" || return 1
        sync || return 1
    fi
    rm -f "$OWNER_STATE" || return 1
    sync || return 1
    STALE_OWNER_PUBLICATION_RETIRED=1
    return 0
}

classify_stale_track_journals() {
    local path found=0 restore_noglob=0 needs_clean_proof=0 retired_files
    local owner_healthy owner_tag
    STALE_TRACK_FILES=""; STALE_TRACK_REQUIRED_TOOLS=""; STALE_TRACK_DIAGNOSTIC=""; CURRENT_BOOT_ID=""
    case "$-" in *f*) restore_noglob=1; set +f;; esac
    set -- "$STATE_DIR"/build-track.* "$STATE_DIR"/probe-track.*
    [ "$restore_noglob" = 1 ] && set -f
    for path in "$@"; do
        { [ -e "$path" ] || [ -L "$path" ]; } || continue
        found=1
        if [ -z "$CURRENT_BOOT_ID" ]; then
            read_current_boot_id || { STALE_TRACK_DIAGNOSTIC="current boot identity is unavailable"; return 1; }
        fi
        validate_track_journal_identity "$path" || { STALE_TRACK_DIAGNOSTIC="unsafe or unauthenticated track journal: $path"; return 1; }
        if [ "$TRACK_BOOT_ID" = "$CURRENT_BOOT_ID" ]; then
            track_creator_liveness || { STALE_TRACK_DIAGNOSTIC="track creator identity is unavailable: $path"; return 1; }
            case "$TRACK_CREATOR_LIVENESS" in
                live) STALE_TRACK_DIAGNOSTIC="track creator is still active: $path"; return 1 ;;
                stale)
                    same_boot_track_creator_is_stably_stale "$path" || {
                        STALE_TRACK_DIAGNOSTIC="same-boot track creator did not remain stably stale: $path"
                        return 1
                    }
                    ;;
                *) STALE_TRACK_DIAGNOSTIC="same-boot track creator identity is ambiguous: $path"; return 1 ;;
            esac
        fi
        STALE_TRACK_FILES="${STALE_TRACK_FILES}${STALE_TRACK_FILES:+ }$path"
        if ! track_journal_is_terminal "$path"; then
            owner_healthy=0
            owner_tag=""
            if [ "$TRACK_JOURNAL_MODE" = build ] &&
               authenticated_published_owner_generation_healthy; then
                owner_healthy=1
                owner_tag="$OWNER_STATE_FIREWALL_TAG"
            fi
            # owner.meta becomes authoritative after exact process and complete
            # topology verification. A dead start process may therefore leave
            # an unfinished WAL for either the committed generation itself or
            # an older failed generation that is now proven absent. Neither
            # case may permanently fence update/install.
            if [ "$owner_healthy" = 1 ] &&
               { [ "$TRACK_FIREWALL_TAG" = "$owner_tag" ] || track_generation_absent; }; then
                :
            else
                needs_clean_proof=1
                case " $STALE_TRACK_REQUIRED_TOOLS " in
                    *" $TRACK_JOURNAL_TOOL "*) ;;
                    *) STALE_TRACK_REQUIRED_TOOLS="${STALE_TRACK_REQUIRED_TOOLS}${STALE_TRACK_REQUIRED_TOOLS:+ }$TRACK_JOURNAL_TOOL" ;;
                esac
            fi
        fi
    done
    [ "$found" = 1 ] || return 0
    if [ "$needs_clean_proof" = 1 ]; then
        stale_track_clean_ownership_proof namespace || {
            STALE_TRACK_DIAGNOSTIC="unfinished stale track cannot be retired while owned process/firewall state exists"
            return 1
        }
    fi
    if caller_holds_exact_lifecycle_lock; then
        retired_files="$STALE_TRACK_FILES"
        for path in $STALE_TRACK_FILES; do
            rm -f "$path" 2>/dev/null || return 1
            sync >/dev/null 2>&1 || return 1
        done
        for path in $retired_files; do
            [ ! -e "$path" ] && [ ! -L "$path" ] || return 1
        done
        STALE_TRACK_FILES=""
    fi
    return 0
}

enumerate_recovery_artifacts() {
    local artifact restore_noglob=0 rc=0
    case "$-" in *f*) restore_noglob=1; set +f;; esac
    for artifact in \
        "$UNINSTALL_TOMBSTONE" "$UNINSTALL_TOMBSTONE".tmp "$UNINSTALL_TOMBSTONE".tmp.* "$STATE_DIR"/.uninstall.tombstone.* \
        "$LIFECYCLE_LOCK_REAPER" "$LIFECYCLE_LOCK_REAPER".* \
        "$LIFECYCLE_LOCK_REAPER_RECOVERY" "$LIFECYCLE_LOCK_REAPER_RECOVERY".* \
        "$LIFECYCLE_LOCK_QUARANTINE" "$LIFECYCLE_LOCK_QUARANTINE".* \
        "$STATE_DIR"/lifecycle.lock.candidate.* "$STATE_DIR"/.lifecycle.lock.* \
        "$STATE_DIR"/build-track.* "$STATE_DIR"/probe-track.* \
        "$FULL_ROLLBACK_TRANSACTION" "$FULL_ROLLBACK_TRANSACTION".tmp "$FULL_ROLLBACK_TRANSACTION".tmp.* \
        "$STATE_DIR"/.full-rollback.transaction.* \
        "$FULL_ROLLBACK_META" "$FULL_ROLLBACK_META".tmp "$FULL_ROLLBACK_META".tmp.* \
        "$STATE_DIR"/.full-rollback.meta.* \
        "$FULL_ROLLBACK_HOSTS_BACKUP" "$FULL_ROLLBACK_HOSTS_BACKUP".tmp "$FULL_ROLLBACK_HOSTS_BACKUP".tmp.* \
        "$STATE_DIR"/.hosts.rollback.backup.*; do
        if [ -e "$artifact" ] || [ -L "$artifact" ]; then printf '%s\n' "$artifact" || { rc=1; break; }; fi
    done
    [ "$restore_noglob" = 1 ] && set -f
    return "$rc"
}

audit_recovery_artifacts() {
    local scope="$1" AUDIT_NFQWS2_OVERRIDE="${2:-}" artifact
    local rollback_seen=0 unsafe_seen=0 rollback_meta=0 rollback_tx=0 rollback_extra=0
    RECOVERY_ARTIFACT_DIAGNOSTIC=""
    RECOVERY_ARTIFACT_CLASS=clean
    RECOVERY_ARTIFACT_FIRST=""
    case "$scope" in
        lifecycle|full-rollback|install|uninstall) ;;
        *) RECOVERY_ARTIFACT_DIAGNOSTIC="unknown recovery audit scope"; return 1 ;;
    esac
    if ! recover_stale_owner_publication; then
        RECOVERY_ARTIFACT_CLASS=unsafe
        RECOVERY_ARTIFACT_DIAGNOSTIC="$STALE_TRACK_DIAGNOSTIC"
        return 1
    fi
    if ! classify_stale_track_journals; then
        RECOVERY_ARTIFACT_CLASS=unsafe
        RECOVERY_ARTIFACT_DIAGNOSTIC="$STALE_TRACK_DIAGNOSTIC"
        return 1
    fi
    for artifact in $(enumerate_recovery_artifacts); do
        stale_track_file_is_known "$artifact" && continue
        [ "$scope" = lifecycle ] && [ "$artifact" = "$UNINSTALL_TOMBSTONE" ] && continue
        [ "$scope" = uninstall ] && [ "$artifact" = "$UNINSTALL_TOMBSTONE" ] && continue
        if [ "$scope" = install ] && [ "$artifact" = "$UNINSTALL_TOMBSTONE" ]; then
            if state_file_is_secure "$UNINSTALL_TOMBSTONE" &&
               read_uninstall_tombstone &&
               [ "$UNINSTALL_FILE_MODULE" = "$MODDIR" ] &&
               ! uninstall_tombstone_owner_alive; then
                continue
            fi
            [ -n "$RECOVERY_ARTIFACT_FIRST" ] || RECOVERY_ARTIFACT_FIRST="$artifact"
            unsafe_seen=1
            continue
        fi
        [ -n "$RECOVERY_ARTIFACT_FIRST" ] || RECOVERY_ARTIFACT_FIRST="$artifact"
        if [ -L "$artifact" ] || ! path_uid_is_root "$artifact"; then
            unsafe_seen=1
            continue
        fi
        case "$artifact" in
            "$FULL_ROLLBACK_META")
                [ -f "$artifact" ] || unsafe_seen=1
                rollback_seen=1
                rollback_meta=1
                ;;
            "$FULL_ROLLBACK_TRANSACTION")
                [ -f "$artifact" ] || unsafe_seen=1
                rollback_seen=1
                rollback_tx=1
                ;;
            "$FULL_ROLLBACK_HOSTS_BACKUP")
                [ -f "$artifact" ] || unsafe_seen=1
                rollback_seen=1
                ;;
            "$FULL_ROLLBACK_HOSTS_BACKUP".tmp.*)
                rollback_seen=1
                if [ "$scope" = full-rollback ] && read_transaction >/dev/null 2>&1 &&
                   case "${artifact##*/}" in
                       "${FULL_ROLLBACK_HOSTS_BACKUP##*/}.tmp.${RB_TOKEN:-}."*) true ;;
                       *) false ;;
                   esac &&
                   state_file_is_secure "$artifact" && path_mode_is_0600 "$artifact"; then
                    :
                else
                    rollback_extra=1
                fi
                ;;
            "$FULL_ROLLBACK_META"*|"$FULL_ROLLBACK_TRANSACTION"*|"$FULL_ROLLBACK_HOSTS_BACKUP"*|"$STATE_DIR"/.full-rollback.*|"$STATE_DIR"/.hosts.rollback.*)
                rollback_seen=1
                rollback_extra=1
                ;;
            *) unsafe_seen=1 ;;
        esac
    done
    if [ "$unsafe_seen" = 1 ]; then
        RECOVERY_ARTIFACT_CLASS=unsafe
    elif [ "$rollback_seen" = 1 ]; then
        if [ "$rollback_meta" = 1 ] && [ "$rollback_tx" = 0 ] && [ "$rollback_extra" = 0 ]; then
            RECOVERY_ARTIFACT_CLASS=rollback-complete
        else
            RECOVERY_ARTIFACT_CLASS=rollback-partial
        fi
    else
        RECOVERY_ARTIFACT_CLASS=clean
    fi
    if [ "$RECOVERY_ARTIFACT_CLASS" = rollback-partial ] &&
       [ "$scope" = full-rollback ] && [ "$rollback_extra" = 0 ]; then
        return 0
    fi
    case "$RECOVERY_ARTIFACT_CLASS:$scope" in
        clean:*) return 0 ;;
        rollback-complete:lifecycle)
            [ -e "$UNINSTALL_TOMBSTONE" ] && [ ! -L "$UNINSTALL_TOMBSTONE" ] &&
                state_file_is_secure "$UNINSTALL_TOMBSTONE" &&
                uninstall_tombstone_allows_stop || {
                RECOVERY_ARTIFACT_DIAGNOSTIC="completed rollback lifecycle access requires the exact live uninstall owner"
                return 1
            }
            return 0
            ;;
        rollback-complete:full-rollback|rollback-complete:install|rollback-complete:uninstall)
            return 0
            ;;
        *)
            RECOVERY_ARTIFACT_DIAGNOSTIC="$RECOVERY_ARTIFACT_CLASS recovery state requires its exact owner: ${RECOVERY_ARTIFACT_FIRST:-unknown}"
            return 1
            ;;
    esac
}


# Boot may need to retire authenticated state from the previous kernel boot
# even when the module is disabled or runtime autostart is off.  This path is
# deliberately cleanup-only: it never creates the state directory, starts the
# daemon, or installs firewall rules.  Same-boot live/unknown evidence retains
# the fail-closed behavior of the ordinary lifecycle audit.
recover_boot_stale_runtime_state() {
    local rc=0 diagnostic=""
    BOOT_RECOVERY_DIAGNOSTIC=""
    if [ ! -e "$STATE_DIR" ] && [ ! -L "$STATE_DIR" ]; then
        return 0
    fi
    state_dir_is_secure || {
        BOOT_RECOVERY_DIAGNOSTIC="state directory is unsafe"
        return 1
    }
    [ "${LOCK_HELD:-0}" = 0 ] || {
        BOOT_RECOVERY_DIAGNOSTIC="unexpected inherited lifecycle lock"
        return 1
    }
    acquire_lifecycle_lock || {
        BOOT_RECOVERY_DIAGNOSTIC="lifecycle lock is busy or unsafe"
        return 1
    }
    BOOT_STALE_RUNTIME_RECOVERY=1
    if ! audit_recovery_artifacts lifecycle; then
        rc=1
        diagnostic="${RECOVERY_ARTIFACT_DIAGNOSTIC:-unsafe recovery state}"
    fi
    BOOT_STALE_RUNTIME_RECOVERY=0
    if ! release_lifecycle_lock; then
        rc=1
        [ -n "$diagnostic" ] || diagnostic="lifecycle lock release failed"
    fi
    if [ "$rc" -ne 0 ]; then
        BOOT_RECOVERY_DIAGNOSTIC="$diagnostic"
        return 1
    fi
    return 0
}

canonical_mark() {
    local value
    MARK_CANONICAL=""
    value="$(printf '0x%x' "$1" 2>/dev/null)" || return 1
    # Netfilter marks are unsigned 32-bit values. printf also accepts wider and
    # negative shell integers, so reject every canonical result above 8 hex
    # digits instead of letting Android and the runtime disagree later.
    case "$value" in
        0x[0-9a-f]|0x[0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]|0x[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])
            MARK_CANONICAL="$value"
            ;;
        *) return 1 ;;
    esac
}

prepare_private_runtime_file() {
    local path="$1"
    ensure_state_dir || return 1
    state_file_target_is_safe "$path" || return 1
    umask 077
    : > "$path" || return 1
    chmod 0600 "$path" 2>/dev/null || return 1
    state_file_is_secure "$path"
}

write_private_runtime_line() {
    local path="$1" value="$2" tmp="$1.tmp.$$" size
    ensure_state_dir || return 1
    state_file_target_is_safe "$path" || return 1
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    size="${#value}"
    [ "$size" -le "$RUNTIME_METADATA_MAX_BYTES" ] 2>/dev/null || return 1
    umask 077
    printf '%s\n' "$value" > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$path" || { rm -f "$tmp"; return 1; }
}

write_runtime_owner_marker() {
    local tmp="$RUNTIME_OWNER_MARKER.tmp.$$"
    ensure_state_dir || return 1
    state_file_target_is_safe "$RUNTIME_OWNER_MARKER" || return 1
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    {
        echo "version=1"
        echo "module_dir=$MODDIR"
        echo "nfqws=$NFQWS2"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$RUNTIME_OWNER_MARKER" || { rm -f "$tmp"; return 1; }
}

read_runtime_owner_marker() {
    local key value version="" module="" nfqws=""
    state_file_is_secure "$RUNTIME_OWNER_MARKER" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) version="$value" ;;
            module_dir) module="$value" ;;
            nfqws) nfqws="$value" ;;
        esac
    done < "$RUNTIME_OWNER_MARKER"
    [ "$version" = 1 ] && [ "$module" = "$MODDIR" ] && [ "$nfqws" = "$NFQWS2" ]
}

new_lifecycle_token() {
    local token=""
    if [ -r /proc/sys/kernel/random/uuid ]; then
        IFS= read -r token < /proc/sys/kernel/random/uuid 2>/dev/null || token=""
    fi
    if ! is_safe_token "$token"; then
        token="z2-$(date +%s 2>/dev/null)-$$-$(proc_starttime "$$" 2>/dev/null || echo 0)"
    fi
    printf '%s\n' "$token"
}

normalize_qnum() {
    local raw="$1" normalized
    QNUM_NORMALIZED=""
    is_decimal "$raw" || return 1
    normalized="$(printf '%s' "$raw" | sed 's/^0*//')"
    [ -n "$normalized" ] || normalized=0
    [ "${#normalized}" -le 5 ] || return 1
    [ "$normalized" -ge 1 ] 2>/dev/null || return 1
    [ "$normalized" -le 65535 ] 2>/dev/null || return 1
    QNUM_NORMALIZED="$normalized"
    return 0
}

runtime_config_exists() {
    local size
    [ -f "$RUNTIME_CONFIG" ] && [ ! -L "$RUNTIME_CONFIG" ] && [ -r "$RUNTIME_CONFIG" ] &&
        path_uid_is_root "$RUNTIME_CONFIG" && path_nlink_is_one "$RUNTIME_CONFIG" &&
        runtime_config_mode_is_safe "$RUNTIME_CONFIG" || return 1
    size="$(wc -c < "$RUNTIME_CONFIG" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null &&
        [ "$size" -le "$RUNTIME_METADATA_MAX_BYTES" ] 2>/dev/null
}

runtime_config_mode_is_safe() {
    local path="$1" mode listing
    if command -v stat >/dev/null 2>&1; then
        mode="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
        case "$mode" in 600|644) return 0;; *) return 1;; esac
    fi
    listing="$(ls -ln "$path" 2>/dev/null)" || return 1
    set -- $listing
    case "${1:-}" in -rw-------*|-rw-r--r--*) return 0;; *) return 1;; esac
}

runtime_config_state_reason() {
    if [ -L "$RUNTIME_CONFIG" ]; then echo "unsafe-symlink"
    elif [ -e "$RUNTIME_CONFIG" ]; then echo "unreadable-or-unsafe"
    else echo "missing"
    fi
}

ensure_runtime_core_config() {
    RUNTIME_CONFIG_STATUS="unknown"
    RUNTIME_CONFIG_REASON=""
    RUNTIME_CONFIG_ERROR=""
    RUNTIME_CORE_REPAIR_MODE="defaults"
    if runtime_config_exists; then
        set_core_config_defaults
        if apply_runtime_core_overrides; then
            RUNTIME_CONFIG_STATUS="loaded"
            return 0
        fi
        RUNTIME_CONFIG_REASON="invalid-or-partial"
        set_core_config_defaults
    else
        RUNTIME_CONFIG_REASON="$(runtime_config_state_reason)"
        # Never replace an existing symlink, directory, device, or unreadable
        # file. Only a missing path or a readable regular runtime can heal.
        if [ -e "$RUNTIME_CONFIG" ] || [ -L "$RUNTIME_CONFIG" ]; then
            RUNTIME_CONFIG_STATUS="unavailable"
            [ -n "$RUNTIME_CONFIG_ERROR" ] || RUNTIME_CONFIG_ERROR="unsafe runtime.ini target"
            return 1
        fi
        set_core_config_defaults
    fi
    if regenerate_runtime_core_config; then
        RUNTIME_CONFIG_STATUS="regenerated"
        set_core_config_defaults
        apply_runtime_core_overrides || {
            RUNTIME_CONFIG_STATUS="unavailable"
            [ -n "$RUNTIME_CONFIG_ERROR" ] || RUNTIME_CONFIG_ERROR="regenerated runtime.ini failed validation"
            return 1
        }
        return 0
    fi
    [ -n "$RUNTIME_CONFIG_ERROR" ] || RUNTIME_CONFIG_ERROR="runtime.ini regeneration failed"
    RUNTIME_CONFIG_STATUS="unavailable"
    return 1
}

regenerate_runtime_core_config() {
    local runtime_tool="$SCRIPT_DIR/runtime-config.sh"
    [ -f "$runtime_tool" ] && [ ! -L "$runtime_tool" ] || return 1
    if runtime_config_exists; then
        sh "$runtime_tool" --repair "$RUNTIME_CONFIG" >/dev/null 2>&1 || return 1
    else
        sh "$runtime_tool" "$RUNTIME_CONFIG" >/dev/null 2>&1 || return 1
    fi
    runtime_config_exists
}

runtime_config_status_message() {
    case "$RUNTIME_CONFIG_STATUS" in
        loaded) echo "runtime.ini is present and authoritative: $RUNTIME_CONFIG" ;;
        regenerated) echo "runtime.ini was regenerated because it was $RUNTIME_CONFIG_REASON: $RUNTIME_CONFIG" ;;
        unavailable) echo "runtime.ini is unavailable ($RUNTIME_CONFIG_REASON): ${RUNTIME_CONFIG_ERROR:-validation failed}" ;;
        *) echo "Runtime config status: $RUNTIME_CONFIG_STATUS" ;;
    esac
}

core_config_source_message() { echo "Core config source: $CORE_CONFIG_SOURCE_PATH"; }

# Configuration parsing runs on the boot path and may inspect thousands of
# catalog lines.  Keep trimming in the current shell: spawning sed for every
# scalar makes validation take minutes on process-heavy Android devices.
trim_config_value_in_place() {
    CONFIG_VALUE_TRIMMED="$1"
    CONFIG_VALUE_TRIMMED="${CONFIG_VALUE_TRIMMED#"${CONFIG_VALUE_TRIMMED%%[![:space:]]*}"}"
    CONFIG_VALUE_TRIMMED="${CONFIG_VALUE_TRIMMED%"${CONFIG_VALUE_TRIMMED##*[![:space:]]}"}"
}

trim_config_value() {
    trim_config_value_in_place "$1"
    printf '%s' "$CONFIG_VALUE_TRIMMED"
}

# Decode one INI/bootstrap scalar without eval, command substitution or escape
# expansion. Matching outer quotes are removed; unmatched quotes are rejected.
decode_config_value() {
    local value first last
    CONFIG_VALUE_DECODED=""
    case "$1" in *'
'*) return 1 ;; esac
    trim_config_value_in_place "$1"
    value="$CONFIG_VALUE_TRIMMED"
    [ -n "$value" ] || { CONFIG_VALUE_DECODED=""; return 0; }
    first="${value%"${value#?}"}"
    last="${value#"${value%?}"}"
    case "$first" in
        \"|\')
            [ "${#value}" -ge 2 ] || return 1
            [ "$last" = "$first" ] || return 1
            value="${value#?}"
            value="${value%?}"
            ;;
        *) case "$last" in \"|\') return 1 ;; esac ;;
    esac
    # The character class covers embedded CR/LF and every other control byte
    # without a command substitution for each parsed scalar.
    case "$value" in *[[:cntrl:]]*) return 1 ;; esac
    CONFIG_VALUE_DECODED="$value"
}

apply_core_config_key() {
    local key="$1" value="$2"
    case "$key" in
        autostart|AUTOSTART) case "$value" in 0|1) AUTOSTART="$value" ;; *) return 1;; esac ;;
        wifi_only|WIFI_ONLY) case "$value" in 0|1) WIFI_ONLY="$value" ;; *) return 1;; esac ;;
        debug|DEBUG) case "$value" in 0|1) DEBUG="$value" ;; *) return 1;; esac ;;
        qnum|QNUM) normalize_qnum "$value" || return 1; QNUM="$QNUM_NORMALIZED" ;;
        desync_mark|DESYNC_MARK) canonical_mark "$value" || return 1; DESYNC_MARK="$MARK_CANONICAL" ;;
        pkt_out|PKT_OUT)
            is_canonical_positive_decimal "$value" && [ "${#value}" -le 9 ] 2>/dev/null || return 1
            PKT_OUT="$value"
            ;;
        pkt_in|PKT_IN)
            is_canonical_positive_decimal "$value" && [ "${#value}" -le 9 ] 2>/dev/null || return 1
            PKT_IN="$value"
            ;;
        active_preset|ACTIVE_PRESET)
            is_safe_runtime_file_name "$value" || return 1
            case "$value" in _*|*.txt) ;; *) return 1 ;; esac
            case "$value" in _*) return 1 ;; esac
            ACTIVE_PRESET="$value"
            ;;
        nfqws_uid|NFQWS_UID)
            case "$value" in
                *:*)
                    case "${value#*:}" in *:*) return 1;; esac
                    is_canonical_nfqws_id "${value%%:*}" &&
                        is_canonical_nfqws_id "${value#*:}" || return 1
                    NFQWS_UID="$value"
                    ;;
                *) return 1 ;;
            esac
            ;;
        log_mode|LOG_MODE) case "$value" in android|file|syslog|none) LOG_MODE="$value" ;; *) return 1;; esac ;;
        *) return 1 ;;
    esac
}

is_safe_file_name_byte_length() {
    local value="$1"
    local LC_ALL=C
    [ "${#value}" -le 255 ] 2>/dev/null
}

is_safe_runtime_file_name() {
    local value="$1"
    [ -n "$value" ] && is_safe_file_name_byte_length "$value" || return 1
    trim_config_value_in_place "$value"
    [ "$value" = "$CONFIG_VALUE_TRIMMED" ] || return 1
    [ "$value" != . ] && [ "$value" != .. ] || return 1
    case "$value" in */*|*\\*|*\"*|*\'*) return 1;; esac
    case "$value" in *[[:cntrl:]]*) return 1 ;; esac
    return 0
}

set_core_config_defaults() {
    RUNTIME_SOURCE="builtin-defaults"
    AUTOSTART=1
    WIFI_ONLY=0
    DEBUG=0
    QNUM=200
    DESYNC_MARK=0x40000000
    PKT_OUT=20
    PKT_IN=10
    ACTIVE_PRESET="Default v1 (game filter).txt"
    NFQWS_UID="0:0"
    LOG_MODE="none"
}

# WIFI_ONLY=1 was accepted by older releases even though the current firewall
# contract has no verified interface selector. Migrate it to the safe mode.
normalize_unsupported_wifi_only() {
    WIFI_ONLY_LEGACY_NORMALIZED=0
    case "${WIFI_ONLY:-}" in
        0) return 0 ;;
        1)
            WIFI_ONLY=0
            WIFI_ONLY_LEGACY_NORMALIZED=1
            return 0
            ;;
        *) return 1 ;;
    esac
}

apply_runtime_core_overrides() {
    runtime_config_exists || return 1
    local current_section="" line="" cr key value core_sections=0 seen_keys="|" required missing=""
    RUNTIME_CORE_REPAIR_MODE="defaults"
    cr="$(printf '\r')"
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%"$cr"}"
        trim_config_value_in_place "$line"
        line="$CONFIG_VALUE_TRIMMED"
        case "$line" in
            ""|"#"*|";"*) continue ;;
            "["*"]")
                current_section="${line#[}"
                current_section="${current_section%]}"
                if [ "$current_section" = core ]; then
                    core_sections=$((core_sections + 1))
                    [ "$core_sections" -eq 1 ] || {
                        RUNTIME_CONFIG_ERROR="runtime.ini contains duplicate [core] sections"
                        return 1
                    }
                fi
                continue
                ;;
        esac
        [ "$current_section" = core ] || continue
        case "$line" in
            *=*)
                trim_config_value_in_place "${line%%=*}"
                key="$CONFIG_VALUE_TRIMMED"
                case "$key" in ""|*[!a-z0-9_-]*)
                    RUNTIME_CONFIG_ERROR="invalid runtime.ini [core] key: $key"
                    return 1
                    ;;
                esac
                value="${line#*=}"
                decode_config_value "$value" || {
                    RUNTIME_CONFIG_ERROR="invalid quoted value for [core] $key"
                    return 1
                }
                value="$CONFIG_VALUE_DECODED"
                ;;
            *)
                RUNTIME_CONFIG_ERROR="malformed runtime.ini [core] line"
                return 1
                ;;
        esac
        case "$seen_keys" in *"|$key|"*)
            RUNTIME_CONFIG_ERROR="duplicate runtime.ini [core] key: $key"
            return 1
            ;;
        esac
        seen_keys="${seen_keys}${key}|"
        case "$key" in
            schema_version)
                [ "$value" = 1 ] || { RUNTIME_CONFIG_ERROR="unsupported runtime.ini schema_version"; return 1; }
                ;;
            config_format)
                [ "$value" = runtime-v1 ] || { RUNTIME_CONFIG_ERROR="unsupported runtime.ini config_format"; return 1; }
                ;;
            runtime_source)
                case "$value" in ""|*[!A-Za-z0-9._-]*) RUNTIME_CONFIG_ERROR="invalid runtime.ini runtime_source"; return 1;; esac
                RUNTIME_SOURCE="$value"
                ;;
            autostart|wifi_only|debug|qnum|desync_mark|pkt_out|pkt_in|active_preset|nfqws_uid|log_mode)
                apply_core_config_key "$key" "$value" || {
                    if [ "$key" = qnum ]; then
                        RUNTIME_CONFIG_ERROR="qnum=$value, expected 1..65535"
                    else
                        RUNTIME_CONFIG_ERROR="$key=$value is invalid"
                    fi
                    return 1
                }
                ;;
            *)
                RUNTIME_CONFIG_ERROR="unsupported runtime.ini [core] key: $key"
                return 1
                ;;
        esac
    done < "$RUNTIME_CONFIG"

    [ "$core_sections" -eq 1 ] || {
        RUNTIME_CONFIG_ERROR="runtime.ini has no [core] section"
        return 1
    }
    for required in $RUNTIME_CORE_REQUIRED_KEYS; do
        case "$seen_keys" in *"|$required|"*) ;; *) missing="${missing}${missing:+,}$required" ;; esac
    done
    if [ -n "$missing" ]; then
        RUNTIME_CONFIG_ERROR="runtime.ini [core] is partial; missing: $missing"
        return 1
    fi

    if ! normalize_qnum "$QNUM"; then
        RUNTIME_CONFIG_ERROR="invalid [core] qnum '$QNUM' (expected decimal 1..65535)"
        return 1
    fi
    QNUM="$QNUM_NORMALIZED"
    return 0
}

runtime_config_error_code() {
    case "$1" in
        "unsupported runtime.ini schema_version") RUNTIME_CONFIG_ERROR_CODE=UNSUPPORTED_SCHEMA ;;
        "unsupported runtime.ini config_format") RUNTIME_CONFIG_ERROR_CODE=UNSUPPORTED_FORMAT ;;
        "runtime.ini contains duplicate [core] sections") RUNTIME_CONFIG_ERROR_CODE=DUPLICATE_CORE ;;
        "invalid runtime.ini [core] key:"*) RUNTIME_CONFIG_ERROR_CODE=INVALID_CORE_KEY ;;
        "invalid quoted value for [core]"*) RUNTIME_CONFIG_ERROR_CODE=INVALID_QUOTED_VALUE ;;
        "malformed runtime.ini [core] line") RUNTIME_CONFIG_ERROR_CODE=MALFORMED_CORE_LINE ;;
        "duplicate runtime.ini [core] key:"*) RUNTIME_CONFIG_ERROR_CODE=DUPLICATE_CORE_KEY ;;
        "invalid runtime.ini runtime_source") RUNTIME_CONFIG_ERROR_CODE=INVALID_RUNTIME_SOURCE ;;
        "unsupported runtime.ini [core] key:"*) RUNTIME_CONFIG_ERROR_CODE=UNKNOWN_CORE_KEY ;;
        "runtime.ini has no [core] section") RUNTIME_CONFIG_ERROR_CODE=MISSING_CORE ;;
        "runtime.ini [core] is partial;"*) RUNTIME_CONFIG_ERROR_CODE=INCOMPLETE_CORE ;;
        "invalid [core] qnum"*|"invalid [core] value for qnum"|qnum=*", expected 1..65535")
            RUNTIME_CONFIG_ERROR_CODE=INVALID_QNUM
            ;;
        "invalid [core] value for"*|*" is invalid")
            RUNTIME_CONFIG_ERROR_CODE=INVALID_CORE_VALUE
            ;;
        "runtime.ini is required for read-only status")
            RUNTIME_CONFIG_ERROR_CODE=RUNTIME_MISSING
            ;;
        *) RUNTIME_CONFIG_ERROR_CODE=CONFIG_INVALID ;;
    esac
}

load_effective_core_config() {
    set_core_config_defaults
    CORE_CONFIG_SOURCE="defaults"
    CORE_CONFIG_SOURCE_PATH="built-in defaults"
    if ensure_runtime_core_config; then
        CORE_CONFIG_SOURCE="runtime.ini"
        CORE_CONFIG_SOURCE_PATH="$RUNTIME_CONFIG"
        normalize_unsupported_wifi_only || return 1
        return 0
    fi
    return 1
}

# Status and diagnostics never create or migrate configuration and never read
# bootstrap inputs. A missing, partial, or invalid runtime is reported as such.
load_effective_core_config_readonly() {
    set_core_config_defaults
    CORE_CONFIG_SOURCE="defaults"
    CORE_CONFIG_SOURCE_PATH="built-in defaults"
    RUNTIME_CONFIG_ERROR=""
    if runtime_config_exists; then
        RUNTIME_CONFIG_REASON=""
        CORE_CONFIG_SOURCE="runtime.ini"
        CORE_CONFIG_SOURCE_PATH="$RUNTIME_CONFIG"
        if ! apply_runtime_core_overrides; then
            RUNTIME_CONFIG_STATUS="unavailable"
            RUNTIME_CONFIG_REASON="invalid-or-partial"
            return 1
        fi
        RUNTIME_CONFIG_STATUS="loaded"
        normalize_unsupported_wifi_only || return 1
        return 0
    fi

    RUNTIME_CONFIG_STATUS="unavailable"
    RUNTIME_CONFIG_REASON="$(runtime_config_state_reason)"
    RUNTIME_CONFIG_ERROR="runtime.ini is required for read-only status"
    return 1
}

proc_starttime() {
    local pid="$1" stat tail
    is_decimal "$pid" || return 1
    [ "$pid" -gt 0 ] 2>/dev/null || return 1
    [ -r "/proc/$pid/stat" ] || return 1
    IFS= read -r stat < "/proc/$pid/stat" || return 1
    tail="${stat##*) }"
    set -- $tail
    [ "$#" -ge 20 ] || return 1
    shift 19
    printf '%s\n' "$1"
}

LOCK_HELD=0
LOCK_OWNER_PID=""
LOCK_OWNER_START=""
LOCK_OWNER_TOKEN=""
LIFECYCLE_ACQUIRE_CANDIDATE=""
LIFECYCLE_ACQUIRE_TOKEN=""

read_lock_owner() {
    LOCK_FILE_PID=""; LOCK_FILE_START=""; LOCK_FILE_TOKEN=""
    LOCK_FILE_KIND=""; LOCK_FILE_BOOT=""; LOCK_FILE_MODULE=""
    state_dir_is_secure || return 1
    [ -d "$LIFECYCLE_LOCK" ] && [ ! -L "$LIFECYCLE_LOCK" ] || return 1
    [ -f "$LIFECYCLE_LOCK_OWNER" ] && [ ! -L "$LIFECYCLE_LOCK_OWNER" ] || return 1
    path_uid_is_root "$LIFECYCLE_LOCK_OWNER" && path_mode_is_0600 "$LIFECYCLE_LOCK_OWNER" &&
        path_nlink_is_one "$LIFECYCLE_LOCK_OWNER" || return 1
    local key value sequence="" version="" kind="" boot="" module=""
    while IFS='=' read -r key value; do
        sequence="${sequence}${sequence:+|}$key"
        case "$key" in
            pid) LOCK_FILE_PID="$value" ;;
            starttime) LOCK_FILE_START="$value" ;;
            token) LOCK_FILE_TOKEN="$value" ;;
            version) version="$value" ;;
            kind) kind="$value" ;;
            boot_id) boot="$value" ;;
            module_dir) module="$value" ;;
            *) return 1 ;;
        esac
    done < "$LIFECYCLE_LOCK_OWNER"
    is_decimal "$LOCK_FILE_PID" && [ "$LOCK_FILE_PID" -gt 0 ] 2>/dev/null &&
        is_decimal "$LOCK_FILE_START" && [ "$LOCK_FILE_START" -gt 0 ] 2>/dev/null &&
        is_safe_token "$LOCK_FILE_TOKEN" || return 1
    case "$sequence" in
        pid\|starttime\|token)
            [ -z "$version$kind$boot$module" ] || return 1
            LOCK_FILE_KIND=shell
            ;;
        version\|kind\|pid\|starttime\|boot_id\|token\|module_dir)
            [ "$version" = 1 ] && [ "$kind" = android-mutation ] &&
                is_valid_boot_id "$boot" && [ "$module" = "$MODDIR" ] || return 1
            LOCK_FILE_KIND=android-mutation
            LOCK_FILE_BOOT="$boot"
            LOCK_FILE_MODULE="$module"
            ;;
        *) return 1 ;;
    esac
    return 0
}

lock_owner_alive() {
    local actual
    read_lock_owner || return 1
    if [ "$LOCK_FILE_KIND" = android-mutation ]; then
        # Boot identity is part of the Android lease.  A proven mismatch is
        # stale even if the numeric PID was reused; an unavailable boot query
        # is unknown and therefore blocks cleanup rather than weakening it.
        read_current_boot_id || return 0
        [ "$LOCK_FILE_BOOT" = "$CURRENT_BOOT_ID" ] || return 1
    fi
    actual="$(proc_starttime "$LOCK_FILE_PID")" || return 1
    [ "$actual" = "$LOCK_FILE_START" ]
}

# Read-only, constant-cost lifecycle classification. Unlike lock_owner_alive,
# this preserves the distinction between a live owner, a proven stale owner,
# and ownership that cannot be authenticated safely.
classify_lifecycle_lock() {
    local actual
    LIFECYCLE_OBSERVED_STATE=idle
    LIFECYCLE_OBSERVED_KIND=none

    if [ ! -e "$LIFECYCLE_LOCK" ] && [ ! -L "$LIFECYCLE_LOCK" ]; then
        if [ -e "$STATE_DIR" ] || [ -L "$STATE_DIR" ]; then
            state_dir_is_secure || {
                LIFECYCLE_OBSERVED_STATE=ambiguous
                LIFECYCLE_OBSERVED_KIND=unknown
            }
        fi
        return 0
    fi
    read_lock_owner || {
        LIFECYCLE_OBSERVED_STATE=ambiguous
        LIFECYCLE_OBSERVED_KIND=unknown
        return 0
    }
    LIFECYCLE_OBSERVED_KIND="$LOCK_FILE_KIND"
    if [ "$LOCK_FILE_KIND" = android-mutation ]; then
        read_current_boot_id || {
            LIFECYCLE_OBSERVED_STATE=ambiguous
            return 0
        }
        if [ "$LOCK_FILE_BOOT" != "$CURRENT_BOOT_ID" ]; then
            LIFECYCLE_OBSERVED_STATE=stale
            return 0
        fi
    fi
    actual="$(proc_starttime "$LOCK_FILE_PID" 2>/dev/null)" || {
        LIFECYCLE_OBSERVED_STATE=stale
        return 0
    }
    if [ "$actual" = "$LOCK_FILE_START" ]; then
        LIFECYCLE_OBSERVED_STATE=active
    else
        LIFECYCLE_OBSERVED_STATE=stale
    fi
    return 0
}

read_lifecycle_gate() {
    local key value
    GATE_FILE_PID=""; GATE_FILE_START=""; GATE_FILE_TOKEN=""
    state_file_is_secure "$LIFECYCLE_LOCK_REAPER" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            pid) GATE_FILE_PID="$value" ;;
            starttime) GATE_FILE_START="$value" ;;
            token) GATE_FILE_TOKEN="$value" ;;
        esac
    done < "$LIFECYCLE_LOCK_REAPER"
    is_decimal "$GATE_FILE_PID" && is_decimal "$GATE_FILE_START" && is_safe_token "$GATE_FILE_TOKEN"
}

lifecycle_gate_alive() {
    local actual
    read_lifecycle_gate || return 1
    actual="$(proc_starttime "$GATE_FILE_PID")" || return 1
    [ "$actual" = "$GATE_FILE_START" ]
}

release_lifecycle_gate() {
    local token="$1"
    read_lifecycle_gate || return 1
    [ "$GATE_FILE_PID" = "$$" ] && [ "$GATE_FILE_TOKEN" = "$token" ] || return 1
    rm -f "$LIFECYCLE_LOCK_REAPER" 2>/dev/null
}

read_lifecycle_recovery_gate() {
    local key value
    RECOVERY_FILE_PID=""; RECOVERY_FILE_START=""; RECOVERY_FILE_TOKEN=""
    state_file_is_secure "$LIFECYCLE_LOCK_REAPER_RECOVERY" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            pid) RECOVERY_FILE_PID="$value" ;;
            starttime) RECOVERY_FILE_START="$value" ;;
            token) RECOVERY_FILE_TOKEN="$value" ;;
        esac
    done < "$LIFECYCLE_LOCK_REAPER_RECOVERY"
    is_decimal "$RECOVERY_FILE_PID" && is_decimal "$RECOVERY_FILE_START" && is_safe_token "$RECOVERY_FILE_TOKEN"
}

lifecycle_recovery_gate_alive() {
    local actual
    read_lifecycle_recovery_gate || return 1
    actual="$(proc_starttime "$RECOVERY_FILE_PID")" || return 1
    [ "$actual" = "$RECOVERY_FILE_START" ]
}

release_lifecycle_recovery_gate() {
    local token="$1"
    read_lifecycle_recovery_gate || return 1
    [ "$RECOVERY_FILE_PID" = "$$" ] && [ "$RECOVERY_FILE_TOKEN" = "$token" ] || return 1
    rm -f "$LIFECYCLE_LOCK_REAPER_RECOVERY" 2>/dev/null
}

claim_lifecycle_recovery_gate() {
    local self_start="$1" token="$2" tmp="$LIFECYCLE_LOCK_REAPER_RECOVERY.tmp.$$.$token"
    local stale_pid stale_start stale_token quarantine
    umask 077
    printf 'pid=%s\nstarttime=%s\ntoken=%s\n' "$$" "$self_start" "$token" > "$tmp" || return 1
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    if ln "$tmp" "$LIFECYCLE_LOCK_REAPER_RECOVERY" 2>/dev/null; then
        rm -f "$tmp"
        return 0
    fi
    rm -f "$tmp"
    read_lifecycle_recovery_gate || return 1
    lifecycle_recovery_gate_alive && return 1
    stale_pid="$RECOVERY_FILE_PID"; stale_start="$RECOVERY_FILE_START"; stale_token="$RECOVERY_FILE_TOKEN"
    sleep 1
    if ! read_lifecycle_recovery_gate || lifecycle_recovery_gate_alive ||
       [ "$RECOVERY_FILE_PID" != "$stale_pid" ] || [ "$RECOVERY_FILE_START" != "$stale_start" ] ||
       [ "$RECOVERY_FILE_TOKEN" != "$stale_token" ]; then
        return 1
    fi
    quarantine="$LIFECYCLE_LOCK_REAPER_RECOVERY_QUARANTINE.$$.$token"
    [ ! -e "$quarantine" ] || return 1
    mv "$LIFECYCLE_LOCK_REAPER_RECOVERY" "$quarantine" 2>/dev/null || return 1
    rm -f "$quarantine" 2>/dev/null || return 1
    return 1
}

claim_lifecycle_gate() {
    local self_start="$1" token="$2" tmp="$LIFECYCLE_LOCK_REAPER.tmp.$$.$token"
    local stale_pid stale_start stale_token
    while :; do
        if [ ! -e "$LIFECYCLE_LOCK_REAPER_RECOVERY" ]; then
            umask 077
            printf 'pid=%s\nstarttime=%s\ntoken=%s\n' "$$" "$self_start" "$token" > "$tmp" || return 1
            chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
            if ln "$tmp" "$LIFECYCLE_LOCK_REAPER" 2>/dev/null; then
                rm -f "$tmp"
                if [ -e "$LIFECYCLE_LOCK_REAPER_RECOVERY" ]; then
                    release_lifecycle_gate "$token" >/dev/null 2>&1 || true
                    sleep 1
                    continue
                fi
                return 0
            fi
            rm -f "$tmp"
        fi
        lifecycle_gate_alive && return 1
        if claim_lifecycle_recovery_gate "$self_start" "$token"; then
            if read_lifecycle_gate; then
                stale_pid="$GATE_FILE_PID"; stale_start="$GATE_FILE_START"; stale_token="$GATE_FILE_TOKEN"
                sleep 1
                if read_lifecycle_gate && ! lifecycle_gate_alive &&
                   [ "$GATE_FILE_PID" = "$stale_pid" ] && [ "$GATE_FILE_START" = "$stale_start" ] &&
                   [ "$GATE_FILE_TOKEN" = "$stale_token" ]; then
                    rm -f "$LIFECYCLE_LOCK_REAPER" 2>/dev/null
                fi
            else
                # Atomic hard-link publication cannot expose a partial gate.
                # A stable malformed regular gate is therefore abandoned.
                sleep 1
                if [ -f "$LIFECYCLE_LOCK_REAPER" ] && [ ! -L "$LIFECYCLE_LOCK_REAPER" ] &&
                   ! read_lifecycle_gate; then
                    rm -f "$LIFECYCLE_LOCK_REAPER" 2>/dev/null
                fi
            fi
            release_lifecycle_recovery_gate "$token" >/dev/null 2>&1 || true
            continue
        fi
        return 1
    done
}

acquire_lifecycle_lock() {
    local attempts=0 self_start token owner_pid owner_start quarantine candidate
    local stale_kind stale_pid stale_start stale_token stale_boot stale_module
    ensure_state_dir || return 1
    self_start="$(proc_starttime "$$")" || return 1
    token="${ZAPRET2_LIFECYCLE_TOKEN:-}"
    owner_pid="${ZAPRET2_LIFECYCLE_OWNER_PID:-}"
    owner_start="${ZAPRET2_LIFECYCLE_OWNER_START:-}"

    # A child launched by the lock holder can safely inherit the lock.  It may
    # never release it: the original holder remains responsible for cleanup.
    if is_safe_token "$token" && is_decimal "$owner_pid" && is_decimal "$owner_start" &&
       read_lock_owner && [ "$LOCK_FILE_KIND" = shell ] && lock_owner_alive &&
       [ "$LOCK_FILE_TOKEN" = "$token" ] &&
       [ "$LOCK_FILE_PID" = "$owner_pid" ] &&
       [ "$LOCK_FILE_START" = "$owner_start" ]; then
        LOCK_HELD=inherited
        LOCK_OWNER_PID="$owner_pid"
        LOCK_OWNER_START="$owner_start"
        LOCK_OWNER_TOKEN="$token"
        return 0
    fi

    token="$(new_lifecycle_token)" || return 1
    is_safe_token "$token" || return 1
    candidate="$LIFECYCLE_LOCK.candidate.$$.$token"
    LIFECYCLE_ACQUIRE_TOKEN="$token"
    LIFECYCLE_ACQUIRE_CANDIDATE="$candidate"
    [ ! -e "$candidate" ] || return 1
    mkdir "$candidate" 2>/dev/null || return 1
    umask 077
    if ! printf 'pid=%s\nstarttime=%s\ntoken=%s\n' "$$" "$self_start" "$token" > "$candidate/owner" ||
       ! chmod 0600 "$candidate/owner" 2>/dev/null; then
        rm -rf "$candidate" 2>/dev/null
        LIFECYCLE_ACQUIRE_CANDIDATE=""; LIFECYCLE_ACQUIRE_TOKEN=""
        return 1
    fi
    while [ "$attempts" -lt "$LIFECYCLE_LOCK_WAIT_SECONDS" ]; do
        if ! claim_lifecycle_gate "$self_start" "$token"; then
            attempts=$((attempts + 1)); sleep 1; continue
        fi
        if [ ! -e "$LIFECYCLE_LOCK" ] && [ ! -L "$LIFECYCLE_LOCK" ]; then
            if mv "$candidate" "$LIFECYCLE_LOCK" 2>/dev/null; then
                release_lifecycle_gate "$token" >/dev/null 2>&1 || true
                candidate=""
                LIFECYCLE_ACQUIRE_CANDIDATE=""; LIFECYCLE_ACQUIRE_TOKEN=""
                LOCK_HELD=1
                LOCK_OWNER_PID="$$"
                LOCK_OWNER_START="$self_start"
                LOCK_OWNER_TOKEN="$token"
                export ZAPRET2_LIFECYCLE_TOKEN="$token"
                export ZAPRET2_LIFECYCLE_OWNER_PID="$$"
                export ZAPRET2_LIFECYCLE_OWNER_START="$self_start"
                return 0
            fi
            release_lifecycle_gate "$token" >/dev/null 2>&1 || true
        elif ! read_lock_owner; then
            # Only an exact recognized owner schema may ever be reaped.  A
            # malformed, foreign, or future record remains a hard fail-closed
            # barrier for manual inspection.
            release_lifecycle_gate "$token" >/dev/null 2>&1 || true
        elif lock_owner_alive; then
            release_lifecycle_gate "$token" >/dev/null 2>&1 || true
        else
            # The gate excludes publishers and other reapers.  A second stable
            # exact stale-owner observation makes quarantine safe.
            stale_kind="$LOCK_FILE_KIND"; stale_pid="$LOCK_FILE_PID"; stale_start="$LOCK_FILE_START"
            stale_token="$LOCK_FILE_TOKEN"; stale_boot="$LOCK_FILE_BOOT"; stale_module="$LOCK_FILE_MODULE"
            sleep 1
            if read_lock_owner && ! lock_owner_alive &&
               [ "$LOCK_FILE_KIND" = "$stale_kind" ] && [ "$LOCK_FILE_PID" = "$stale_pid" ] &&
               [ "$LOCK_FILE_START" = "$stale_start" ] && [ "$LOCK_FILE_TOKEN" = "$stale_token" ] &&
               [ "$LOCK_FILE_BOOT" = "$stale_boot" ] && [ "$LOCK_FILE_MODULE" = "$stale_module" ]; then
                quarantine="$LIFECYCLE_LOCK_QUARANTINE.$$.$token"
                if [ ! -e "$quarantine" ] && mv "$LIFECYCLE_LOCK" "$quarantine" 2>/dev/null; then
                    release_lifecycle_gate "$token" >/dev/null 2>&1 || true
                    rm -rf "$quarantine" 2>/dev/null || true
                    attempts=$((attempts + 1))
                    continue
                fi
            fi
            release_lifecycle_gate "$token" >/dev/null 2>&1 || true
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    [ -z "$candidate" ] || rm -rf "$candidate" 2>/dev/null
    LIFECYCLE_ACQUIRE_CANDIDATE=""; LIFECYCLE_ACQUIRE_TOKEN=""
    return 1
}

# A caller with an early signal trap can use this while acquire_lifecycle_lock
# is waiting. Only the exact candidate/gates published by this PID and token
# are retired; an inherited or foreign lifecycle owner is never released.
abort_lifecycle_lock_acquire() {
    local candidate="$LIFECYCLE_ACQUIRE_CANDIDATE" token="$LIFECYCLE_ACQUIRE_TOKEN" owner
    if [ "$LOCK_HELD" = 1 ]; then
        release_lifecycle_lock >/dev/null 2>&1 || return 1
        LIFECYCLE_ACQUIRE_CANDIDATE=""; LIFECYCLE_ACQUIRE_TOKEN=""
        return 0
    fi
    if is_safe_token "$token"; then
        release_lifecycle_gate "$token" >/dev/null 2>&1 || true
        release_lifecycle_recovery_gate "$token" >/dev/null 2>&1 || true
    fi
    case "$candidate" in "$LIFECYCLE_LOCK.candidate.$$.$token") ;; *) return 1 ;; esac
    if [ -d "$candidate" ] && [ ! -L "$candidate" ]; then
        owner="$candidate/owner"
        if [ -f "$owner" ] && [ ! -L "$owner" ] && path_uid_is_root "$owner"; then
            rm -f "$owner" 2>/dev/null || return 1
        fi
        rmdir "$candidate" 2>/dev/null || return 1
    elif [ -e "$candidate" ] || [ -L "$candidate" ]; then
        return 1
    fi
    LIFECYCLE_ACQUIRE_CANDIDATE=""; LIFECYCLE_ACQUIRE_TOKEN=""
    return 0
}

release_lifecycle_lock() {
    local self_start token quarantine
    [ "$LOCK_HELD" = 1 ] || { LOCK_HELD=0; return 0; }
    self_start="$(proc_starttime "$$")" || return 1
    token="$LOCK_OWNER_TOKEN"
    claim_lifecycle_gate "$self_start" "$token" || return 1
    if read_lock_owner &&
       [ "$LOCK_FILE_PID" = "$LOCK_OWNER_PID" ] &&
       [ "$LOCK_FILE_START" = "$LOCK_OWNER_START" ] &&
       [ "$LOCK_FILE_TOKEN" = "$LOCK_OWNER_TOKEN" ]; then
        quarantine="$LIFECYCLE_LOCK_QUARANTINE.release.$$.$token"
        if [ ! -e "$quarantine" ] && mv "$LIFECYCLE_LOCK" "$quarantine" 2>/dev/null; then
            release_lifecycle_gate "$token" >/dev/null 2>&1 || true
            rm -rf "$quarantine" 2>/dev/null || true
            LOCK_HELD=0
            return 0
        fi
    fi
    release_lifecycle_gate "$token" >/dev/null 2>&1 || true
    # Preserve ownership state on failure so the caller's EXIT trap can retry
    # exact cleanup. Forgetting a still-published owner turns a recoverable
    # release error into a persistent lifecycle barrier.
    return 1
}

module_removal_pending() {
    [ -e "$MODDIR/remove" ] || [ -L "$MODDIR/remove" ]
}

read_uninstall_tombstone() {
    local key value version="" seen_version=0 seen_pid=0 seen_start=0
    local seen_token=0 seen_module=0
    UNINSTALL_FILE_PID=""; UNINSTALL_FILE_START=""
    UNINSTALL_FILE_TOKEN=""; UNINSTALL_FILE_MODULE=""
    state_file_is_secure "$UNINSTALL_TOMBSTONE" && [ -r "$UNINSTALL_TOMBSTONE" ] || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version)
                [ "$seen_version" = 0 ] || return 1
                version="$value"; seen_version=1
                ;;
            pid)
                [ "$seen_pid" = 0 ] || return 1
                UNINSTALL_FILE_PID="$value"; seen_pid=1
                ;;
            starttime)
                [ "$seen_start" = 0 ] || return 1
                UNINSTALL_FILE_START="$value"; seen_start=1
                ;;
            token)
                [ "$seen_token" = 0 ] || return 1
                UNINSTALL_FILE_TOKEN="$value"; seen_token=1
                ;;
            module_dir)
                [ "$seen_module" = 0 ] || return 1
                UNINSTALL_FILE_MODULE="$value"; seen_module=1
                ;;
            *) return 1 ;;
        esac
    done < "$UNINSTALL_TOMBSTONE"
    [ "$seen_version:$seen_pid:$seen_start:$seen_token:$seen_module" = 1:1:1:1:1 ] || return 1
    [ "$version" = "$UNINSTALL_TOMBSTONE_VERSION" ] || return 1
    is_decimal "$UNINSTALL_FILE_PID" && [ "$UNINSTALL_FILE_PID" -gt 0 ] 2>/dev/null || return 1
    is_decimal "$UNINSTALL_FILE_START" || return 1
    is_safe_token "$UNINSTALL_FILE_TOKEN" || return 1
    [ "$UNINSTALL_FILE_MODULE" = "$MODDIR" ]
}

uninstall_tombstone_owner_alive() {
    local actual
    actual="$(proc_starttime "$UNINSTALL_FILE_PID")" || return 1
    [ "$actual" = "$UNINSTALL_FILE_START" ]
}

uninstall_environment_authorized() {
    is_safe_token "${ZAPRET2_UNINSTALL_TOKEN:-}" &&
        [ "${ZAPRET2_UNINSTALL_TOKEN:-}" = "$UNINSTALL_FILE_TOKEN" ] &&
        is_decimal "${ZAPRET2_UNINSTALL_OWNER_PID:-}" &&
        [ "${ZAPRET2_UNINSTALL_OWNER_PID:-}" = "$UNINSTALL_FILE_PID" ] &&
        is_decimal "${ZAPRET2_UNINSTALL_OWNER_START:-}" &&
        [ "${ZAPRET2_UNINSTALL_OWNER_START:-}" = "$UNINSTALL_FILE_START" ] &&
        read_lock_owner && lock_owner_alive &&
        [ "$LOCK_FILE_PID" = "$UNINSTALL_FILE_PID" ] &&
        [ "$LOCK_FILE_START" = "$UNINSTALL_FILE_START" ]
}

uninstall_tombstone_allows_start() {
    UNINSTALL_TOMBSTONE_ERROR=""; UNINSTALL_TOMBSTONE_DIAGNOSTIC=""
    if module_removal_pending; then
        UNINSTALL_TOMBSTONE_ERROR="Magisk module removal marker is present: $MODDIR/remove"
        return 1
    fi
    { [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; } || return 0
    UNINSTALL_TOMBSTONE_ERROR="uninstall tombstone blocks start/restart: $UNINSTALL_TOMBSTONE"
    return 1
}

uninstall_tombstone_allows_stop() {
    UNINSTALL_TOMBSTONE_ERROR=""; UNINSTALL_TOMBSTONE_DIAGNOSTIC=""
    { [ -e "$UNINSTALL_TOMBSTONE" ] || [ -L "$UNINSTALL_TOMBSTONE" ]; } || return 0
    read_uninstall_tombstone || {
        UNINSTALL_TOMBSTONE_ERROR="uninstall tombstone is malformed or unsafe"
        return 1
    }
    uninstall_tombstone_owner_alive || {
        UNINSTALL_TOMBSTONE_ERROR="uninstall tombstone owner is not alive"
        return 1
    }
    uninstall_environment_authorized || {
        UNINSTALL_TOMBSTONE_ERROR="stop caller lacks exact live uninstall ownership"
        return 1
    }
    UNINSTALL_TOMBSTONE_DIAGNOSTIC="stop authorized by exact live uninstall owner"
    return 0
}

pid_cmdline_has_arg() {
    local pid="$1" wanted="$2"
    [ -r "/proc/$pid/cmdline" ] || return 1
    tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -Fqx -- "$wanted"
}

proc_cmdline_sha256() {
    local pid="$1" value
    is_decimal "$pid" || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    value="$(sha256sum "/proc/$pid/cmdline" 2>/dev/null)" || return 1
    value="${value%% *}"
    is_lower_sha256 "$value" || return 1
    printf '%s\n' "$value"
}

proc_argv0() {
    local pid="$1" value
    is_decimal "$pid" || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    value="$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | sed -n '1p')" || return 1
    [ -n "$value" ] || return 1
    printf '%s\n' "$value"
}

# Fast, fork-free prefilter for the recovery scan. Shell variables cannot retain
# NUL separators while reading cmdline, so an exact argv0 is
# guaranteed to retain this prefix. Prefix collisions are harmless: the
# candidate still goes through verify_nfqws_pid's full argv0/start/exe proof.
proc_cmdline_may_match_nfqws() {
    local pid="$1" runtime_nfqws2="${AUDIT_NFQWS2_OVERRIDE:-$NFQWS2}" cmdline=""
    is_decimal "$pid" || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    IFS= read -r cmdline < "/proc/$pid/cmdline" 2>/dev/null || [ -n "$cmdline" ] || return 1
    case "$cmdline" in "$runtime_nfqws2"*) return 0 ;; *) return 1 ;; esac
}

OWNER_STATE_PID=""
OWNER_STATE_START=""
OWNER_STATE_ARGV_HEX=""
OWNER_STATE_ARGV_SHA256=""
OWNER_STATE_QNUM=""
OWNER_STATE_EXE=""
OWNER_STATE_GENERATION=""
OWNER_STATE_PHASE=""
OWNER_STATE_SCHEMA_VERSION=""
OWNER_STATE_LEGACY=0
OWNER_STATE_INSTALL_GENERATION=""
OWNER_STATE_INSTALL_ARCHIVE_SHA256=""
OWNER_STATE_PORTS_TCP=""; OWNER_STATE_PORTS_UDP=""; OWNER_STATE_STUN_PORTS=""
OWNER_STATE_PKT_OUT=""; OWNER_STATE_PKT_IN=""; OWNER_STATE_DESYNC_MARK=""
OWNER_STATE_IPV4_ACTIVE=0; OWNER_STATE_IPV6_ACTIVE=0
OWNER_STATE_IPV4_CONNBYTES=0; OWNER_STATE_IPV4_MULTIPORT=0; OWNER_STATE_IPV4_MARK=0
OWNER_STATE_IPV6_CONNBYTES=0; OWNER_STATE_IPV6_MULTIPORT=0; OWNER_STATE_IPV6_MARK=0
OWNER_STATE_IPV4_RULES=0; OWNER_STATE_IPV6_RULES=0
OWNER_STATE_IPV4_SPEC=""; OWNER_STATE_IPV6_SPEC=""; OWNER_STATE_FIREWALL_FINGERPRINT=""
OWNER_WRITE_READY=0

normalize_owner_port_list() {
    local list="$1" item first last old_ifs result="" normalized
    OWNER_PORT_LIST_NORMALIZED=""
    [ -n "$list" ] || return 1
    case "$list" in *[!0-9,:]*|,*|*,|*,,*) return 1;; esac
    old_ifs="$IFS"; IFS=,; set -- $list; IFS="$old_ifs"; [ "$#" -gt 0 ] || return 1
    for item in "$@"; do
        case "$item" in
            *:*) first="${item%%:*}"; last="${item#*:}"; case "$last" in *:*) return 1;; esac
                is_decimal "$first" && is_decimal "$last" || return 1
                first="$(printf '%s' "$first" | sed 's/^0*//')"; last="$(printf '%s' "$last" | sed 's/^0*//')"; [ -n "$first" ] || first=0; [ -n "$last" ] || last=0
                [ "$first" -le 65535 ] 2>/dev/null && [ "$last" -le 65535 ] 2>/dev/null && [ "$first" -le "$last" ] 2>/dev/null || return 1
                normalized="$first:$last" ;;
            *) is_decimal "$item" || return 1; normalized="$(printf '%s' "$item" | sed 's/^0*//')"; [ -n "$normalized" ] || normalized=0
                [ "$normalized" -le 65535 ] 2>/dev/null || return 1 ;;
        esac
        result="${result}${result:+,}$normalized"
    done
    OWNER_PORT_LIST_NORMALIZED="$result"
}

normalize_owner_optional_port_list() {
    OWNER_PORT_LIST_NORMALIZED=""
    [ -z "$1" ] && return 0
    normalize_owner_port_list "$1"
}

owner_port_rule_count() {
    local old_ifs count
    [ -n "$1" ] || { printf '0\n'; return 0; }
    old_ifs="$IFS"; IFS=,; set -- $1; IFS="$old_ifs"; count=$#
    [ "$count" -gt 0 ] || return 1; printf '%s\n' "$count"
}

is_safe_firewall_identity() {
    local tag="$1" out="$2" inchain="$3"
    case "$tag" in ""|*[!A-Za-z0-9]*) return 1;; esac
    [ "${#tag}" -eq 10 ] 2>/dev/null || return 1
    [ "$out" = "Z2O_$tag" ] && [ "$inchain" = "Z2I_$tag" ] && [ "${#out}" -le 28 ] 2>/dev/null
}

prepare_new_firewall_identity() {
    local token clean
    token="$(new_lifecycle_token)" || return 1
    clean="$(printf '%s' "$token" | tr -cd 'A-Za-z0-9' | cut -c1-10)"
    [ "${#clean}" -eq 10 ] 2>/dev/null || return 1
    FIREWALL_TAG="$clean"; ZAPRET2_OUT="Z2O_$clean"; ZAPRET2_IN="Z2I_$clean"; PENDING_OWNER_GENERATION="$token"
    is_safe_firewall_identity "$FIREWALL_TAG" "$ZAPRET2_OUT" "$ZAPRET2_IN"
}

# Every NFQUEUE payload lives in its own generation-bound chain.  The parent
# chain contains only the unique jump, giving teardown a stable kernel object
# identity instead of relying on a mutable rule number or payload equality.
owner_rule_chain() {
    local parent="$1" ordinal="$2" side
    is_decimal "$ordinal" && [ "$ordinal" -gt 0 ] 2>/dev/null || return 1
    if [ "$parent" = "$ZAPRET2_OUT" ]; then side=O
    elif [ "$parent" = "$ZAPRET2_IN" ]; then side=I
    else return 1
    fi
    OWNER_RULE_CHAIN="Z2R_${FIREWALL_TAG}_${side}${ordinal}"
    [ "${#OWNER_RULE_CHAIN}" -le 28 ] 2>/dev/null || return 1
}

owner_build_family_spec() {
    printf 'family:%s;active:%s;tag:%s;outchain:%s;inchain:%s;qnum:%s;tcp:%s;udp:%s;stun:%s;out:%s;in:%s;mark:%s;connbytes:%s;multiport:%s;markcap:%s;rules:%s\n' \
        "$1" "$2" "$OWNER_WRITE_FIREWALL_TAG" "$OWNER_WRITE_OUT_CHAIN" "$OWNER_WRITE_IN_CHAIN" \
        "$OWNER_WRITE_QNUM" "$OWNER_WRITE_PORTS_TCP" "$OWNER_WRITE_PORTS_UDP" "$OWNER_WRITE_STUN_PORTS" \
        "$OWNER_WRITE_PKT_OUT" "$OWNER_WRITE_PKT_IN" "$OWNER_WRITE_DESYNC_MARK" "$3" "$4" "$5" "$6"
}

owner_spec_fingerprint() {
    local value
    command -v sha256sum >/dev/null 2>&1 || return 1
    value="$(printf '%s\n%s\n' "$1" "$2" | sha256sum 2>/dev/null | awk '{print $1}')" || return 1
    is_lower_sha256 "$value" || return 1; printf '%s\n' "$value"
}

prepare_owner_generation_spec() {
    local ipv4_active="${1:-1}" ipv6_active="${2:-0}" tcp_count udp_count per_direction
    read_install_generation_meta || return 1
    is_safe_firewall_identity "${FIREWALL_TAG:-}" "${ZAPRET2_OUT:-}" "${ZAPRET2_IN:-}" || prepare_new_firewall_identity || return 1
    OWNER_WRITE_FIREWALL_TAG="$FIREWALL_TAG"; OWNER_WRITE_OUT_CHAIN="$ZAPRET2_OUT"; OWNER_WRITE_IN_CHAIN="$ZAPRET2_IN"
    normalize_qnum "${QNUM:-}" || return 1; OWNER_WRITE_QNUM="$QNUM_NORMALIZED"
    normalize_owner_optional_port_list "${PORTS_TCP:-}" || return 1; OWNER_WRITE_PORTS_TCP="$OWNER_PORT_LIST_NORMALIZED"
    normalize_owner_optional_port_list "${PORTS_UDP:-}" || return 1; OWNER_WRITE_PORTS_UDP="$OWNER_PORT_LIST_NORMALIZED"
    [ -n "$OWNER_WRITE_PORTS_TCP$OWNER_WRITE_PORTS_UDP" ] || return 1
    # Voice ports are already folded into the compiled UDP union.
    OWNER_WRITE_STUN_PORTS=0
    is_decimal "${PKT_OUT:-}" && [ "$PKT_OUT" -gt 0 ] || return 1; OWNER_WRITE_PKT_OUT="$PKT_OUT"
    is_decimal "${PKT_IN:-}" && [ "$PKT_IN" -gt 0 ] || return 1; OWNER_WRITE_PKT_IN="$PKT_IN"
    canonical_mark "${DESYNC_MARK:-}" || return 1; OWNER_WRITE_DESYNC_MARK="$MARK_CANONICAL"
    case "$ipv4_active:$ipv6_active" in 1:0|1:1) ;; *) return 1;; esac
    OWNER_WRITE_IPV4_ACTIVE="$ipv4_active"; OWNER_WRITE_IPV6_ACTIVE="$ipv6_active"
    OWNER_WRITE_IPV4_CONNBYTES="${IPV4_CONNBYTES:-1}"; OWNER_WRITE_IPV4_MULTIPORT="${IPV4_MULTIPORT:-1}"; OWNER_WRITE_IPV4_MARK="${IPV4_MARK:-1}"
    OWNER_WRITE_IPV6_CONNBYTES="${IPV6_CONNBYTES:-1}"; OWNER_WRITE_IPV6_MULTIPORT="${IPV6_MULTIPORT:-1}"; OWNER_WRITE_IPV6_MARK="${IPV6_MARK:-1}"
    case "$OWNER_WRITE_IPV4_CONNBYTES:$OWNER_WRITE_IPV4_MULTIPORT:$OWNER_WRITE_IPV4_MARK:$OWNER_WRITE_IPV6_CONNBYTES:$OWNER_WRITE_IPV6_MULTIPORT:$OWNER_WRITE_IPV6_MARK" in *[!01:]*) return 1;; esac
    tcp_count="$(owner_port_rule_count "$OWNER_WRITE_PORTS_TCP")" || return 1
    udp_count="$(owner_port_rule_count "$OWNER_WRITE_PORTS_UDP")" || return 1
    if [ "$OWNER_WRITE_IPV4_MULTIPORT" = 1 ]; then
        per_direction=0; [ -z "$OWNER_WRITE_PORTS_TCP" ] || per_direction=$((per_direction + 1)); [ -z "$OWNER_WRITE_PORTS_UDP" ] || per_direction=$((per_direction + 1))
    else per_direction=$((tcp_count + udp_count)); fi
    OWNER_WRITE_IPV4_RULES=$((per_direction * (1 + OWNER_WRITE_IPV4_CONNBYTES) * ipv4_active))
    if [ "$OWNER_WRITE_IPV6_MULTIPORT" = 1 ]; then
        per_direction=0; [ -z "$OWNER_WRITE_PORTS_TCP" ] || per_direction=$((per_direction + 1)); [ -z "$OWNER_WRITE_PORTS_UDP" ] || per_direction=$((per_direction + 1))
    else per_direction=$((tcp_count + udp_count)); fi
    OWNER_WRITE_IPV6_RULES=$((per_direction * (1 + OWNER_WRITE_IPV6_CONNBYTES) * ipv6_active))
    OWNER_WRITE_IPV4_SPEC="$(owner_build_family_spec ipv4 "$ipv4_active" "$OWNER_WRITE_IPV4_CONNBYTES" "$OWNER_WRITE_IPV4_MULTIPORT" "$OWNER_WRITE_IPV4_MARK" "$OWNER_WRITE_IPV4_RULES")"
    OWNER_WRITE_IPV6_SPEC="$(owner_build_family_spec ipv6 "$ipv6_active" "$OWNER_WRITE_IPV6_CONNBYTES" "$OWNER_WRITE_IPV6_MULTIPORT" "$OWNER_WRITE_IPV6_MARK" "$OWNER_WRITE_IPV6_RULES")"
    OWNER_WRITE_FIREWALL_FINGERPRINT="$(owner_spec_fingerprint "$OWNER_WRITE_IPV4_SPEC" "$OWNER_WRITE_IPV6_SPEC")" || return 1
    OWNER_WRITE_INSTALL_GENERATION="$INSTALL_META_GENERATION"; OWNER_WRITE_INSTALL_ARCHIVE_SHA256="$INSTALL_META_ARCHIVE_SHA256"; OWNER_WRITE_SOURCE_GENERATION=""; OWNER_WRITE_READY=1
}

owner_load_generation_fields() {
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
    OWNER_WRITE_QNUM="$OWNER_STATE_QNUM"; OWNER_WRITE_PORTS_TCP="$OWNER_STATE_PORTS_TCP"; OWNER_WRITE_PORTS_UDP="$OWNER_STATE_PORTS_UDP"; OWNER_WRITE_STUN_PORTS="$OWNER_STATE_STUN_PORTS"
    OWNER_WRITE_FIREWALL_TAG="$OWNER_STATE_FIREWALL_TAG"; OWNER_WRITE_OUT_CHAIN="$OWNER_STATE_OUT_CHAIN"; OWNER_WRITE_IN_CHAIN="$OWNER_STATE_IN_CHAIN"
    FIREWALL_TAG="$OWNER_STATE_FIREWALL_TAG"; ZAPRET2_OUT="$OWNER_STATE_OUT_CHAIN"; ZAPRET2_IN="$OWNER_STATE_IN_CHAIN"
    OWNER_WRITE_PKT_OUT="$OWNER_STATE_PKT_OUT"; OWNER_WRITE_PKT_IN="$OWNER_STATE_PKT_IN"; OWNER_WRITE_DESYNC_MARK="$OWNER_STATE_DESYNC_MARK"
    OWNER_WRITE_IPV4_ACTIVE="$OWNER_STATE_IPV4_ACTIVE"; OWNER_WRITE_IPV6_ACTIVE="$OWNER_STATE_IPV6_ACTIVE"
    OWNER_WRITE_IPV4_CONNBYTES="$OWNER_STATE_IPV4_CONNBYTES"; OWNER_WRITE_IPV4_MULTIPORT="$OWNER_STATE_IPV4_MULTIPORT"; OWNER_WRITE_IPV4_MARK="$OWNER_STATE_IPV4_MARK"
    OWNER_WRITE_IPV6_CONNBYTES="$OWNER_STATE_IPV6_CONNBYTES"; OWNER_WRITE_IPV6_MULTIPORT="$OWNER_STATE_IPV6_MULTIPORT"; OWNER_WRITE_IPV6_MARK="$OWNER_STATE_IPV6_MARK"
    OWNER_WRITE_IPV4_RULES="$OWNER_STATE_IPV4_RULES"; OWNER_WRITE_IPV6_RULES="$OWNER_STATE_IPV6_RULES"; OWNER_WRITE_IPV4_SPEC="$OWNER_STATE_IPV4_SPEC"; OWNER_WRITE_IPV6_SPEC="$OWNER_STATE_IPV6_SPEC"
    OWNER_WRITE_FIREWALL_FINGERPRINT="$OWNER_STATE_FIREWALL_FINGERPRINT"; OWNER_WRITE_INSTALL_GENERATION="$OWNER_STATE_INSTALL_GENERATION"; OWNER_WRITE_INSTALL_ARCHIVE_SHA256="$OWNER_STATE_INSTALL_ARCHIVE_SHA256"; OWNER_WRITE_SOURCE_GENERATION="$OWNER_STATE_GENERATION"; OWNER_WRITE_READY=1
}

owner_state_is_current_boot() {
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
    is_valid_boot_id "$OWNER_STATE_BOOT_ID" || return 1
    read_current_boot_id || return 1
    [ "$OWNER_STATE_BOOT_ID" = "$CURRENT_BOOT_ID" ]
}

owner_loaded_generation_for_write() {
    owner_state_is_current_boot || return 1
    owner_load_generation_fields
}

read_owner_state() {
    local expected_nfqws2="${AUDIT_NFQWS2_OVERRIDE:-$NFQWS2}"
    OWNER_STATE_PID=""; OWNER_STATE_START=""; OWNER_STATE_ARGV_HEX=""; OWNER_STATE_ARGV_SHA256=""
    OWNER_STATE_QNUM=""; OWNER_STATE_EXE=""; OWNER_STATE_GENERATION=""; OWNER_STATE_BOOT_ID=""; OWNER_STATE_PHASE=""; OWNER_STATE_SCHEMA_VERSION=""; OWNER_STATE_LEGACY=0
    OWNER_STATE_INSTALL_GENERATION=""; OWNER_STATE_INSTALL_ARCHIVE_SHA256=""
    OWNER_STATE_PORTS_TCP=""; OWNER_STATE_PORTS_UDP=""; OWNER_STATE_STUN_PORTS=""; OWNER_STATE_PKT_OUT=""; OWNER_STATE_PKT_IN=""; OWNER_STATE_DESYNC_MARK=""
    OWNER_STATE_IPV4_ACTIVE=""; OWNER_STATE_IPV6_ACTIVE=""; OWNER_STATE_IPV4_CONNBYTES=""; OWNER_STATE_IPV4_MULTIPORT=""; OWNER_STATE_IPV4_MARK=""
    OWNER_STATE_IPV6_CONNBYTES=""; OWNER_STATE_IPV6_MULTIPORT=""; OWNER_STATE_IPV6_MARK=""; OWNER_STATE_IPV4_RULES=""; OWNER_STATE_IPV6_RULES=""
    OWNER_STATE_IPV4_SPEC=""; OWNER_STATE_IPV6_SPEC=""; OWNER_STATE_FIREWALL_FINGERPRINT=""
    OWNER_STATE_FIREWALL_TAG=""; OWNER_STATE_OUT_CHAIN=""; OWNER_STATE_IN_CHAIN=""
    state_file_is_secure "$OWNER_STATE" && [ -r "$OWNER_STATE" ] || return 1
    local key value version="" tcp_count udp_count stun_count expected seen_keys="|" field_sequence="" size
    size="$(wc -c < "$OWNER_STATE" 2>/dev/null)" || return 1
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null &&
        [ "$size" -le "$OWNER_STATE_MAX_BYTES" ] 2>/dev/null || return 1
    while IFS='=' read -r key value; do
        case "$seen_keys" in *"|$key|"*) return 1;; esac
        seen_keys="${seen_keys}${key}|"
        field_sequence="${field_sequence}${field_sequence:+|}$key"
        case "$key" in
            version) version="$value" ;;
            pid) OWNER_STATE_PID="$value" ;;
            starttime) OWNER_STATE_START="$value" ;;
            argv_hex) OWNER_STATE_ARGV_HEX="$value" ;;
            argv_sha256) OWNER_STATE_ARGV_SHA256="$value" ;;
            qnum) OWNER_STATE_QNUM="$value" ;;
            exe) OWNER_STATE_EXE="$value" ;;
            generation) OWNER_STATE_GENERATION="$value" ;;
            boot_id) OWNER_STATE_BOOT_ID="$value" ;;
            phase) OWNER_STATE_PHASE="$value" ;;
            install_generation) OWNER_STATE_INSTALL_GENERATION="$value" ;;
            install_archive_sha256) OWNER_STATE_INSTALL_ARCHIVE_SHA256="$value" ;;
            firewall_tag) OWNER_STATE_FIREWALL_TAG="$value" ;;
            out_chain) OWNER_STATE_OUT_CHAIN="$value" ;;
            in_chain) OWNER_STATE_IN_CHAIN="$value" ;;
            ports_tcp) OWNER_STATE_PORTS_TCP="$value" ;;
            ports_udp) OWNER_STATE_PORTS_UDP="$value" ;;
            stun_ports) OWNER_STATE_STUN_PORTS="$value" ;;
            pkt_out) OWNER_STATE_PKT_OUT="$value" ;;
            pkt_in) OWNER_STATE_PKT_IN="$value" ;;
            desync_mark) OWNER_STATE_DESYNC_MARK="$value" ;;
            ipv4_active) OWNER_STATE_IPV4_ACTIVE="$value" ;;
            ipv6_active) OWNER_STATE_IPV6_ACTIVE="$value" ;;
            ipv4_connbytes) OWNER_STATE_IPV4_CONNBYTES="$value" ;;
            ipv4_multiport) OWNER_STATE_IPV4_MULTIPORT="$value" ;;
            ipv4_mark) OWNER_STATE_IPV4_MARK="$value" ;;
            ipv6_connbytes) OWNER_STATE_IPV6_CONNBYTES="$value" ;;
            ipv6_multiport) OWNER_STATE_IPV6_MULTIPORT="$value" ;;
            ipv6_mark) OWNER_STATE_IPV6_MARK="$value" ;;
            ipv4_rules) OWNER_STATE_IPV4_RULES="$value" ;;
            ipv6_rules) OWNER_STATE_IPV6_RULES="$value" ;;
            ipv4_spec) OWNER_STATE_IPV4_SPEC="$value" ;;
            ipv6_spec) OWNER_STATE_IPV6_SPEC="$value" ;;
            firewall_fingerprint) OWNER_STATE_FIREWALL_FINGERPRINT="$value" ;;
            *) return 1 ;;
        esac
    done < "$OWNER_STATE"
    case "$version" in
        3) [ "$field_sequence" = "$OWNER_STATE_V3_FIELD_SEQUENCE" ] || return 1; OWNER_STATE_SCHEMA_VERSION=3; OWNER_STATE_LEGACY=1 ;;
        4) [ "$field_sequence" = "$OWNER_STATE_V4_FIELD_SEQUENCE" ] || return 1; OWNER_STATE_SCHEMA_VERSION=4; OWNER_STATE_LEGACY=1 ;;
        5) [ "$field_sequence" = "$OWNER_STATE_V5_FIELD_SEQUENCE" ] || return 1; OWNER_STATE_SCHEMA_VERSION=5; OWNER_STATE_LEGACY=1 ;;
        6) [ "$field_sequence" = "$OWNER_STATE_V6_FIELD_SEQUENCE" ] || return 1; OWNER_STATE_SCHEMA_VERSION=6; OWNER_STATE_LEGACY=1 ;;
        "$OWNER_STATE_VERSION") [ "$field_sequence" = "$OWNER_STATE_V7_FIELD_SEQUENCE" ] || return 1; OWNER_STATE_SCHEMA_VERSION="$OWNER_STATE_VERSION" ;;
        *) return 1 ;;
    esac
    if [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ]; then
        [ "$size" -le "$OWNER_STATE_CURRENT_MAX_BYTES" ] 2>/dev/null || return 1
    fi
    is_canonical_positive_decimal "$OWNER_STATE_PID" &&
        is_canonical_nonnegative_i64 "$OWNER_STATE_START" || return 1
    normalize_qnum "$OWNER_STATE_QNUM" || return 1
    OWNER_STATE_QNUM="$QNUM_NORMALIZED"
    [ "$OWNER_STATE_EXE" = "$expected_nfqws2" ] || return 1
    if [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ]; then
        [ -z "$OWNER_STATE_ARGV_HEX" ] && is_lower_sha256 "$OWNER_STATE_ARGV_SHA256" || return 1
    else
        [ -z "$OWNER_STATE_ARGV_SHA256" ] && [ -n "$OWNER_STATE_ARGV_HEX" ] || return 1
        case "$OWNER_STATE_ARGV_HEX" in *[!0-9A-Fa-f]*) return 1 ;; esac
    fi
    is_safe_token "$OWNER_STATE_GENERATION" || return 1
    case "$OWNER_STATE_SCHEMA_VERSION" in
        5|6|"$OWNER_STATE_VERSION") is_valid_boot_id "$OWNER_STATE_BOOT_ID" || return 1 ;;
        *) [ -z "$OWNER_STATE_BOOT_ID" ] || return 1 ;;
    esac
    case "$OWNER_STATE_PHASE" in launched|active|stopping|error) ;; *) return 1 ;; esac
    [ "$OWNER_STATE_SCHEMA_VERSION" = 3 ] && return 0
    is_safe_token "$OWNER_STATE_INSTALL_GENERATION" && [ "${#OWNER_STATE_INSTALL_GENERATION}" -le 128 ] 2>/dev/null || return 1
    is_lower_sha256 "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" || return 1
    case "$OWNER_STATE_SCHEMA_VERSION" in
        6|"$OWNER_STATE_VERSION")
            is_safe_firewall_identity "$OWNER_STATE_FIREWALL_TAG" "$OWNER_STATE_OUT_CHAIN" "$OWNER_STATE_IN_CHAIN" || return 1
            OWNER_WRITE_FIREWALL_TAG="$OWNER_STATE_FIREWALL_TAG"; OWNER_WRITE_OUT_CHAIN="$OWNER_STATE_OUT_CHAIN"; OWNER_WRITE_IN_CHAIN="$OWNER_STATE_IN_CHAIN"
            ;;
        *)
            [ -z "$OWNER_STATE_FIREWALL_TAG$OWNER_STATE_OUT_CHAIN$OWNER_STATE_IN_CHAIN" ] || return 1
            OWNER_WRITE_FIREWALL_TAG=legacy; OWNER_WRITE_OUT_CHAIN=ZAPRET2_OUT; OWNER_WRITE_IN_CHAIN=ZAPRET2_IN
            ;;
    esac
    normalize_owner_optional_port_list "$OWNER_STATE_PORTS_TCP" || return 1; [ "$OWNER_PORT_LIST_NORMALIZED" = "$OWNER_STATE_PORTS_TCP" ] || return 1
    normalize_owner_optional_port_list "$OWNER_STATE_PORTS_UDP" || return 1; [ "$OWNER_PORT_LIST_NORMALIZED" = "$OWNER_STATE_PORTS_UDP" ] || return 1
    [ -n "$OWNER_STATE_PORTS_TCP$OWNER_STATE_PORTS_UDP" ] || return 1
    [ "$OWNER_STATE_STUN_PORTS" = 0 ] || return 1
    is_canonical_positive_decimal "$OWNER_STATE_PKT_OUT" && [ "${#OWNER_STATE_PKT_OUT}" -le 9 ] 2>/dev/null || return 1
    is_canonical_positive_decimal "$OWNER_STATE_PKT_IN" && [ "${#OWNER_STATE_PKT_IN}" -le 9 ] 2>/dev/null || return 1
    canonical_mark "$OWNER_STATE_DESYNC_MARK" || return 1; [ "$MARK_CANONICAL" = "$OWNER_STATE_DESYNC_MARK" ] || return 1
    case "$OWNER_STATE_IPV4_ACTIVE:$OWNER_STATE_IPV6_ACTIVE:$OWNER_STATE_IPV4_CONNBYTES:$OWNER_STATE_IPV4_MULTIPORT:$OWNER_STATE_IPV4_MARK:$OWNER_STATE_IPV6_CONNBYTES:$OWNER_STATE_IPV6_MULTIPORT:$OWNER_STATE_IPV6_MARK" in *[!01:]*) return 1;; esac
    [ "$OWNER_STATE_IPV4_ACTIVE" = 1 ] || return 1
    is_canonical_nonnegative_i64 "$OWNER_STATE_IPV4_RULES" &&
        is_canonical_nonnegative_i64 "$OWNER_STATE_IPV6_RULES" || return 1
    tcp_count="$(owner_port_rule_count "$OWNER_STATE_PORTS_TCP")" || return 1
    udp_count="$(owner_port_rule_count "$OWNER_STATE_PORTS_UDP")" || return 1
    if [ "$OWNER_STATE_IPV4_MULTIPORT" = 1 ]; then
        expected=0; [ -z "$OWNER_STATE_PORTS_TCP" ] || expected=$((expected + 1)); [ -z "$OWNER_STATE_PORTS_UDP" ] || expected=$((expected + 1))
    else expected=$((tcp_count + udp_count)); fi
    expected=$((expected * (1 + OWNER_STATE_IPV4_CONNBYTES)))
    [ "$OWNER_STATE_IPV4_RULES" = $((expected * OWNER_STATE_IPV4_ACTIVE)) ] || return 1
    if [ "$OWNER_STATE_IPV6_MULTIPORT" = 1 ]; then
        expected=0; [ -z "$OWNER_STATE_PORTS_TCP" ] || expected=$((expected + 1)); [ -z "$OWNER_STATE_PORTS_UDP" ] || expected=$((expected + 1))
    else expected=$((tcp_count + udp_count)); fi
    expected=$((expected * (1 + OWNER_STATE_IPV6_CONNBYTES)))
    [ "$OWNER_STATE_IPV6_RULES" = $((expected * OWNER_STATE_IPV6_ACTIVE)) ] || return 1
    [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 0
    # A cold lifecycle process has no prior OWNER_WRITE_* generation.  Build
    # the authenticated v7 specs solely from the just-validated owner fields;
    # otherwise a valid record is accidentally accepted only in the writer's
    # original shell where these globals happen to remain populated.
    OWNER_WRITE_QNUM="$OWNER_STATE_QNUM"
    OWNER_WRITE_PORTS_TCP="$OWNER_STATE_PORTS_TCP"; OWNER_WRITE_PORTS_UDP="$OWNER_STATE_PORTS_UDP"; OWNER_WRITE_STUN_PORTS="$OWNER_STATE_STUN_PORTS"
    OWNER_WRITE_PKT_OUT="$OWNER_STATE_PKT_OUT"; OWNER_WRITE_PKT_IN="$OWNER_STATE_PKT_IN"; OWNER_WRITE_DESYNC_MARK="$OWNER_STATE_DESYNC_MARK"
    [ "$(owner_build_family_spec ipv4 "$OWNER_STATE_IPV4_ACTIVE" "$OWNER_STATE_IPV4_CONNBYTES" "$OWNER_STATE_IPV4_MULTIPORT" "$OWNER_STATE_IPV4_MARK" "$OWNER_STATE_IPV4_RULES")" = "$OWNER_STATE_IPV4_SPEC" ] || return 1
    [ "$(owner_build_family_spec ipv6 "$OWNER_STATE_IPV6_ACTIVE" "$OWNER_STATE_IPV6_CONNBYTES" "$OWNER_STATE_IPV6_MULTIPORT" "$OWNER_STATE_IPV6_MARK" "$OWNER_STATE_IPV6_RULES")" = "$OWNER_STATE_IPV6_SPEC" ] || return 1
    [ "$(owner_spec_fingerprint "$OWNER_STATE_IPV4_SPEC" "$OWNER_STATE_IPV6_SPEC")" = "$OWNER_STATE_FIREWALL_FINGERPRINT" ] || return 1
    FIREWALL_TAG="$OWNER_STATE_FIREWALL_TAG"; ZAPRET2_OUT="$OWNER_STATE_OUT_CHAIN"; ZAPRET2_IN="$OWNER_STATE_IN_CHAIN"
    return 0
}

write_numeric_pidfile() {
    local pid="$1"
    is_decimal "$pid" && [ "$pid" -gt 0 ] 2>/dev/null || return 1
    write_private_runtime_line "$PIDFILE" "$pid"
}

write_owner_state() {
    local pid="$1" start="$2" argv_sha256="$3" qnum="$4" generation="$5" phase="$6"
    local tmp="$OWNER_STATE.tmp.$$" boot_id size
    read_current_boot_id || return 1
    boot_id="$CURRENT_BOOT_ID"
    is_canonical_positive_decimal "$pid" && is_canonical_nonnegative_i64 "$start" || return 1
    normalize_qnum "$qnum" || return 1
    qnum="$QNUM_NORMALIZED"
    is_lower_sha256 "$argv_sha256" || return 1
    is_safe_token "$generation" || return 1
    case "$phase" in launched|active|stopping|error) ;; *) return 1 ;; esac
    if [ "${OWNER_WRITE_READY:-0}" != 1 ] || { [ -n "${OWNER_WRITE_SOURCE_GENERATION:-}" ] && [ "$OWNER_WRITE_SOURCE_GENERATION" != "$generation" ]; }; then
        prepare_owner_generation_spec 1 "${IPV6_BUILT:-${IPV6_ACTIVE:-0}}" || return 1
    fi
    [ "$qnum" = "$OWNER_WRITE_QNUM" ] || return 1
    ensure_state_dir || return 1
    state_file_target_is_safe "$OWNER_STATE" || return 1
    state_path_is_managed_file "$tmp" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    {
        printf 'version=%s\n' "$OWNER_STATE_VERSION"
        printf 'pid=%s\nstarttime=%s\nargv_sha256=%s\n' "$pid" "$start" "$argv_sha256"
        printf 'qnum=%s\nexe=%s\ngeneration=%s\nboot_id=%s\nphase=%s\n' "$qnum" "$NFQWS2" "$generation" "$boot_id" "$phase"
        printf 'install_generation=%s\ninstall_archive_sha256=%s\n' "$OWNER_WRITE_INSTALL_GENERATION" "$OWNER_WRITE_INSTALL_ARCHIVE_SHA256"
        printf 'firewall_tag=%s\nout_chain=%s\nin_chain=%s\n' "$OWNER_WRITE_FIREWALL_TAG" "$OWNER_WRITE_OUT_CHAIN" "$OWNER_WRITE_IN_CHAIN"
        printf 'ports_tcp=%s\nports_udp=%s\nstun_ports=%s\npkt_out=%s\npkt_in=%s\ndesync_mark=%s\n' "$OWNER_WRITE_PORTS_TCP" "$OWNER_WRITE_PORTS_UDP" "$OWNER_WRITE_STUN_PORTS" "$OWNER_WRITE_PKT_OUT" "$OWNER_WRITE_PKT_IN" "$OWNER_WRITE_DESYNC_MARK"
        printf 'ipv4_active=%s\nipv6_active=%s\nipv4_connbytes=%s\nipv4_multiport=%s\nipv4_mark=%s\n' "$OWNER_WRITE_IPV4_ACTIVE" "$OWNER_WRITE_IPV6_ACTIVE" "$OWNER_WRITE_IPV4_CONNBYTES" "$OWNER_WRITE_IPV4_MULTIPORT" "$OWNER_WRITE_IPV4_MARK"
        printf 'ipv6_connbytes=%s\nipv6_multiport=%s\nipv6_mark=%s\nipv4_rules=%s\nipv6_rules=%s\n' "$OWNER_WRITE_IPV6_CONNBYTES" "$OWNER_WRITE_IPV6_MULTIPORT" "$OWNER_WRITE_IPV6_MARK" "$OWNER_WRITE_IPV4_RULES" "$OWNER_WRITE_IPV6_RULES"
        printf 'ipv4_spec=%s\nipv6_spec=%s\nfirewall_fingerprint=%s\n' "$OWNER_WRITE_IPV4_SPEC" "$OWNER_WRITE_IPV6_SPEC" "$OWNER_WRITE_FIREWALL_FINGERPRINT"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    size="$(wc -c < "$tmp" 2>/dev/null)" || { rm -f "$tmp"; return 1; }
    is_decimal "$size" && [ "$size" -gt 0 ] 2>/dev/null &&
        [ "$size" -le "$OWNER_STATE_CURRENT_MAX_BYTES" ] 2>/dev/null || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$OWNER_STATE" || { rm -f "$tmp"; return 1; }
    OWNER_WRITE_READY=0; OWNER_WRITE_SOURCE_GENERATION=""
}

publish_nfqws_owner() {
    local pid="$1" start="$2" qnum="$3" phase="$4" argv_sha256 generation
    is_decimal "$pid" && is_decimal "$start" || return 1
    argv_sha256="$(proc_cmdline_sha256 "$pid")" || return 1
    verify_nfqws_pid "$pid" "$start" "$argv_sha256" "$qnum" || return 1
    generation="${PENDING_OWNER_GENERATION:-}"
    [ -n "$generation" ] || generation="$(new_lifecycle_token)" || return 1
    # The authenticated, boot-bound owner is the publication commit marker.
    # Publish it first so a power loss can leave at worst an owner-only state,
    # which process preflight can verify exactly.  A bare numeric pidfile is
    # intentionally never produced.
    write_owner_state "$pid" "$start" "$argv_sha256" "$qnum" "$generation" "$phase" || return 1
    sync || return 1
    write_numeric_pidfile "$pid" || return 1
    sync || return 1
    PUBLISHED_PID="$pid"
    PUBLISHED_START="$start"
    PUBLISHED_GENERATION="$generation"
    return 0
}

set_owner_phase() {
    local phase="$1"
    read_owner_state && owner_state_is_current_boot || return 1
    [ "$OWNER_STATE_PHASE" = "$phase" ] && return 0
    verify_nfqws_pid "$OWNER_STATE_PID" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" || return 1
    write_owner_state "$OWNER_STATE_PID" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" "$OWNER_STATE_GENERATION" "$phase"
}

republish_owner_ipv6_inactive() {
    read_owner_state || return 1
    owner_state_is_current_boot || return 1
    verify_nfqws_pid "$OWNER_STATE_PID" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" || return 1
    owner_loaded_generation_for_write || return 1
    OWNER_WRITE_IPV6_ACTIVE=0; OWNER_WRITE_IPV6_RULES=0
    OWNER_WRITE_IPV6_SPEC="$(owner_build_family_spec ipv6 0 "$OWNER_WRITE_IPV6_CONNBYTES" "$OWNER_WRITE_IPV6_MULTIPORT" "$OWNER_WRITE_IPV6_MARK" 0)" || return 1
    OWNER_WRITE_FIREWALL_FINGERPRINT="$(owner_spec_fingerprint "$OWNER_WRITE_IPV4_SPEC" "$OWNER_WRITE_IPV6_SPEC")" || return 1
    write_owner_state "$OWNER_STATE_PID" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" "$OWNER_STATE_GENERATION" "$OWNER_STATE_PHASE" || return 1
    read_owner_state && [ "$OWNER_STATE_IPV6_ACTIVE" = 0 ] && [ "$OWNER_STATE_IPV6_RULES" = 0 ]
}

retire_owner_metadata() {
    scan_exact_owned_nfqws >/dev/null 2>&1 || return 1
    [ -z "$OWNED_SCAN_PIDS" ] || return 1
    # Rejected/corrupt PID or owner metadata is repair evidence and must not be
    # silently removed.  Verified publication cleanup happens in
    # stop_pidfile_process().
    [ ! -e "$PIDFILE" ] && [ ! -L "$PIDFILE" ] || return 1
    [ ! -e "$OWNER_STATE" ] && [ ! -L "$OWNER_STATE" ] || return 1
    return 0
}

VERIFIED_STARTTIME=""
verify_nfqws_pid() {
    local pid="$1" expected_start="${2:-}" expected_argv_sha256="${3:-}" expected_qnum="${4:-}"
    local before after cmd_exe binary_exe actual_argv_sha256 argv0 runtime_nfqws2
    runtime_nfqws2="${AUDIT_NFQWS2_OVERRIDE:-$NFQWS2}"
    VERIFIED_STARTTIME=""
    is_decimal "$pid" || return 1
    [ "$pid" -gt 0 ] 2>/dev/null || return 1
    before="$(proc_starttime "$pid")" || return 1
    [ -z "$expected_start" ] || [ "$before" = "$expected_start" ] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    argv0="$(proc_argv0 "$pid")" || return 1
    [ "$argv0" = "$runtime_nfqws2" ] || return 1
    if [ -n "$expected_qnum" ]; then
        normalize_qnum "$expected_qnum" || return 1
        pid_cmdline_has_arg "$pid" "--qnum=$QNUM_NORMALIZED" || return 1
    fi
    if [ -n "$expected_argv_sha256" ]; then
        is_lower_sha256 "$expected_argv_sha256" || return 1
        actual_argv_sha256="$(proc_cmdline_sha256 "$pid")" || return 1
        [ "$actual_argv_sha256" = "$expected_argv_sha256" ] || return 1
    fi

    cmd_exe="$(readlink -f "/proc/$pid/exe" 2>/dev/null)"
    binary_exe="$(readlink -f "$runtime_nfqws2" 2>/dev/null)"
    # Exact argv0 is mandatory on every platform.  /proc/PID/exe strengthens
    # that identity when readlink is available, but its absence on some
    # Android kernels must not weaken or disable the exact argv0 check above.
    if [ -n "$cmd_exe" ] && [ -n "$binary_exe" ]; then
        [ "$cmd_exe" = "$binary_exe" ] || return 1
    fi
    after="$(proc_starttime "$pid")" || return 1
    [ "$before" = "$after" ] || return 1
    VERIFIED_STARTTIME="$after"
    return 0
}

read_verified_pidfile() {
    VERIFIED_PID=""
    VERIFIED_PID_START=""
    VERIFIED_PID_ARGV_SHA256=""
    VERIFIED_PID_QNUM=""
    state_file_is_secure "$PIDFILE" && [ -r "$PIDFILE" ] || return 1
    local candidate
    candidate="$(cat "$PIDFILE" 2>/dev/null)"
    is_decimal "$candidate" || return 1
    if [ -e "$OWNER_STATE" ]; then
        read_owner_state && owner_state_is_current_boot || return 1
        [ "$OWNER_STATE_PID" = "$candidate" ] || return 1
        verify_nfqws_pid "$candidate" "$OWNER_STATE_START" "$OWNER_STATE_ARGV_SHA256" "$OWNER_STATE_QNUM" || return 1
        VERIFIED_PID_ARGV_SHA256="$OWNER_STATE_ARGV_SHA256"
        VERIFIED_PID_QNUM="$OWNER_STATE_QNUM"
    else
        # A bare numeric PID cannot authenticate a current lifecycle owner.
        return 1
    fi
    VERIFIED_PID="$candidate"
    VERIFIED_PID_START="$VERIFIED_STARTTIME"
    return 0
}

read_live_pidfile() {
    LIVE_PIDFILE_PID=""
    state_file_is_secure "$PIDFILE" && [ -r "$PIDFILE" ] || return 1
    local candidate
    candidate="$(cat "$PIDFILE" 2>/dev/null)"
    is_decimal "$candidate" || return 1
    [ "$candidate" -gt 0 ] 2>/dev/null || return 1
    kill -0 "$candidate" 2>/dev/null || return 1
    proc_starttime "$candidate" >/dev/null 2>&1 || return 1
    LIVE_PIDFILE_PID="$candidate"
    return 0
}

stop_verified_nfqws_pid() {
    local pid="$1" start="$2" expected_argv_sha256="${3:-}" expected_qnum="${4:-}" n=0
    verify_nfqws_pid "$pid" "$start" "$expected_argv_sha256" "$expected_qnum" || return 2
    kill -TERM "$pid" 2>/dev/null || return 1
    while [ "$n" -lt 5 ]; do
        sleep 1
        verify_nfqws_pid "$pid" "$start" "$expected_argv_sha256" "$expected_qnum" || return 0
        n=$((n + 1))
    done
    verify_nfqws_pid "$pid" "$start" "$expected_argv_sha256" "$expected_qnum" || return 0
    kill -KILL "$pid" 2>/dev/null || return 1
    n=0
    while [ "$n" -lt 3 ]; do
        sleep 1
        verify_nfqws_pid "$pid" "$start" "$expected_argv_sha256" "$expected_qnum" || return 0
        n=$((n + 1))
    done
    return 1
}

PROCESS_CLEANUP_PREFLIGHT_PROVEN=0
PROCESS_PREFLIGHT_PID=""
PROCESS_PREFLIGHT_START=""
PROCESS_PREFLIGHT_ARGV_SHA256=""
PROCESS_PREFLIGHT_QNUM=""
PROCESS_PREFLIGHT_GENERATION=""
PROCESS_PREFLIGHT_PHASE=""
PROCESS_PREFLIGHT_PIDFILE_PRESENT=0
PROCESS_PREFLIGHT_OWNER_PRESENT=0
PROCESS_PREFLIGHT_LIVE=0

process_snapshot_owner_matches() {
    [ "$PROCESS_PREFLIGHT_OWNER_PRESENT" = 1 ] || {
        [ ! -e "$OWNER_STATE" ] && [ ! -L "$OWNER_STATE" ]
        return
    }
    read_owner_state && owner_state_is_current_boot || return 1
    [ "$OWNER_STATE_PID" = "$PROCESS_PREFLIGHT_PID" ] &&
        [ "$OWNER_STATE_START" = "$PROCESS_PREFLIGHT_START" ] &&
        [ "$OWNER_STATE_ARGV_SHA256" = "$PROCESS_PREFLIGHT_ARGV_SHA256" ] &&
        [ "$OWNER_STATE_QNUM" = "$PROCESS_PREFLIGHT_QNUM" ] &&
        [ "$OWNER_STATE_GENERATION" = "$PROCESS_PREFLIGHT_GENERATION" ] &&
        [ "$OWNER_STATE_PHASE" = "$PROCESS_PREFLIGHT_PHASE" ]
}

process_snapshot_pidfile_matches() {
    local candidate
    [ "$PROCESS_PREFLIGHT_PIDFILE_PRESENT" = 1 ] || {
        [ ! -e "$PIDFILE" ] && [ ! -L "$PIDFILE" ]
        return
    }
    state_file_is_secure "$PIDFILE" && [ -r "$PIDFILE" ] || return 1
    candidate="$(cat "$PIDFILE" 2>/dev/null)" || return 1
    [ "$candidate" = "$PROCESS_PREFLIGHT_PID" ]
}

stop_pidfile_process() {
    local rc=0
    [ "$PROCESS_CLEANUP_PREFLIGHT_PROVEN" = 1 ] || preflight_owned_process_cleanup || return 1
    process_snapshot_pidfile_matches && process_snapshot_owner_matches || return 1
    if [ "$PROCESS_PREFLIGHT_LIVE" = 1 ]; then
        verify_nfqws_pid "$PROCESS_PREFLIGHT_PID" "$PROCESS_PREFLIGHT_START" \
            "$PROCESS_PREFLIGHT_ARGV_SHA256" "$PROCESS_PREFLIGHT_QNUM" || return 1
        stop_verified_nfqws_pid "$PROCESS_PREFLIGHT_PID" "$PROCESS_PREFLIGHT_START" \
            "$PROCESS_PREFLIGHT_ARGV_SHA256" "$PROCESS_PREFLIGHT_QNUM" || rc=1
    fi
    # Never kill a process that appeared after the read-only proof. A new exact
    # process makes teardown incomplete and leaves its evidence intact.
    scan_exact_owned_nfqws >/dev/null 2>&1 || rc=1
    [ -z "$OWNED_SCAN_PIDS" ] || rc=1
    if [ "$rc" -eq 0 ]; then
        process_snapshot_pidfile_matches && process_snapshot_owner_matches || return 1
        if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then teardown_operation_commit_proven || return 1; fi
        if [ "$PROCESS_PREFLIGHT_PIDFILE_PRESENT" = 1 ]; then rm -f "$PIDFILE" 2>/dev/null || rc=1; fi
        if [ "$PROCESS_PREFLIGHT_OWNER_PRESENT" = 1 ]; then rm -f "$OWNER_STATE" 2>/dev/null || rc=1; fi
        if [ "$rc" -eq 0 ] && [ "${TEARDOWN_COMMIT_PROVEN:-0}" = 1 ]; then rm -f "$TEARDOWN_JOURNAL" 2>/dev/null || rc=1; sync || rc=1; fi
    fi
    return "$rc"
}

scan_exact_owned_nfqws() {
    local procdir pid start restore_noglob=0
    OWNED_SCAN_PIDS=""
    case "$-" in *f*) restore_noglob=1; set +f ;; esac
    for procdir in /proc/[0-9]*; do
        [ -d "$procdir" ] || continue
        pid="${procdir#/proc/}"
        # The previous implementation performed multiple /proc reads plus
        # tr/sed forks for every Android process. Filter with one shell-builtin
        # read and reserve the strict identity proof for plausible candidates.
        proc_cmdline_may_match_nfqws "$pid" || continue
        start="$(proc_starttime "$pid")" || continue
        if verify_nfqws_pid "$pid" "$start" "" ""; then
            if [ -n "$OWNED_SCAN_PIDS" ]; then OWNED_SCAN_PIDS="$OWNED_SCAN_PIDS $pid"
            else OWNED_SCAN_PIDS="$pid"; fi
        fi
    done
    [ "$restore_noglob" = 1 ] && set -f
    printf '%s\n' "$OWNED_SCAN_PIDS"
}

scan_exact_owned_nfqws_for_path() {
    local AUDIT_NFQWS2_OVERRIDE="$1"
    [ -n "$AUDIT_NFQWS2_OVERRIDE" ] || return 1
    scan_exact_owned_nfqws
}

stop_all_exact_owned_nfqws() {
    local pid start rc=0
    scan_exact_owned_nfqws >/dev/null 2>&1 || return 1
    for pid in $OWNED_SCAN_PIDS; do
        start="$(proc_starttime "$pid")" || continue
        stop_verified_nfqws_pid "$pid" "$start" "" "" || rc=1
    done
    scan_exact_owned_nfqws >/dev/null 2>&1 || return 1
    [ -z "$OWNED_SCAN_PIDS" ] || rc=1
    return "$rc"
}

stop_all_exact_owned_nfqws_for_path() {
    local AUDIT_NFQWS2_OVERRIDE="$1"
    [ -n "$AUDIT_NFQWS2_OVERRIDE" ] || return 1
    stop_all_exact_owned_nfqws
}

# Refuse teardown when exact process publication cannot account for every
# module-binary process.  In particular, a rejected PID/owner file must never
# fall through to the broad exact-path scan and kill a listener.
PROCESS_CLEANUP_PREFLIGHT_ERROR=""
preflight_owned_process_cleanup() {
    local count=0 pid pidfile_present=0 owner_present=0 argv_sha256 start
    PROCESS_CLEANUP_PREFLIGHT_ERROR=""
    PROCESS_CLEANUP_PREFLIGHT_PROVEN=0
    PROCESS_PREFLIGHT_PID=""; PROCESS_PREFLIGHT_START=""; PROCESS_PREFLIGHT_ARGV_SHA256=""
    PROCESS_PREFLIGHT_QNUM=""; PROCESS_PREFLIGHT_GENERATION=""; PROCESS_PREFLIGHT_PHASE=""
    PROCESS_PREFLIGHT_PIDFILE_PRESENT=0; PROCESS_PREFLIGHT_OWNER_PRESENT=0
    PROCESS_PREFLIGHT_LIVE=0
    { [ -e "$PIDFILE" ] || [ -L "$PIDFILE" ]; } && pidfile_present=1
    { [ -e "$OWNER_STATE" ] || [ -L "$OWNER_STATE" ]; } && owner_present=1
    scan_exact_owned_nfqws >/dev/null 2>&1 || {
        PROCESS_CLEANUP_PREFLIGHT_ERROR="exact module process scan is unavailable"
        return 1
    }
    for pid in $OWNED_SCAN_PIDS; do count=$((count + 1)); done
    [ "$count" -le 1 ] || {
        PROCESS_CLEANUP_PREFLIGHT_ERROR="multiple exact module processes are ambiguous"
        return 1
    }
    if [ "$count" = 0 ] && [ "$pidfile_present" = 1 ]; then
        PROCESS_CLEANUP_PREFLIGHT_ERROR="PID publication exists but cannot be matched to an exact live module process"
        return 1
    fi
    if [ "$owner_present" = 1 ]; then
        read_owner_state || {
            PROCESS_CLEANUP_PREFLIGHT_ERROR="owner metadata is malformed or unsafe"
            return 1
        }
        owner_state_is_current_boot || {
            PROCESS_CLEANUP_PREFLIGHT_ERROR="owner metadata is legacy or not bound to the current boot"
            return 1
        }
        PROCESS_PREFLIGHT_OWNER_PRESENT=1
        PROCESS_PREFLIGHT_GENERATION="$OWNER_STATE_GENERATION"
        PROCESS_PREFLIGHT_PHASE="$OWNER_STATE_PHASE"
    fi
    if [ "$count" = 1 ]; then
        [ "$owner_present" = 1 ] || {
            PROCESS_CLEANUP_PREFLIGHT_ERROR="live module process lacks an authenticated current owner"
            return 1
        }
        PROCESS_PREFLIGHT_LIVE=1
        pid="$OWNED_SCAN_PIDS"
        start="$(proc_starttime "$pid")" || return 1
        argv_sha256="$(proc_cmdline_sha256 "$pid")" || return 1
        if [ "$pidfile_present" = 1 ]; then
            read_verified_pidfile || {
                PROCESS_CLEANUP_PREFLIGHT_ERROR="live PID publication is corrupt or unverified"
                return 1
            }
            [ "$VERIFIED_PID" = "$pid" ] || {
                PROCESS_CLEANUP_PREFLIGHT_ERROR="PID publication names a different live process"
                return 1
            }
            PROCESS_PREFLIGHT_PIDFILE_PRESENT=1
            PROCESS_PREFLIGHT_QNUM="$VERIFIED_PID_QNUM"
        elif [ "$owner_present" = 1 ]; then
            [ "$OWNER_STATE_PID" = "$pid" ] && [ "$OWNER_STATE_START" = "$start" ] &&
                [ "$OWNER_STATE_ARGV_SHA256" = "$argv_sha256" ] &&
                verify_nfqws_pid "$pid" "$start" "$argv_sha256" "$OWNER_STATE_QNUM" || {
                    PROCESS_CLEANUP_PREFLIGHT_ERROR="owner metadata does not match the exact module process"
                    return 1
                }
            PROCESS_PREFLIGHT_QNUM="$OWNER_STATE_QNUM"
        fi
        PROCESS_PREFLIGHT_PID="$pid"
        PROCESS_PREFLIGHT_START="$start"
        PROCESS_PREFLIGHT_ARGV_SHA256="$argv_sha256"
    elif [ "$owner_present" = 1 ]; then
        PROCESS_CLEANUP_PREFLIGHT_ERROR="current-boot owner publication has no exact live module process"
        return 1
    fi
    PROCESS_CLEANUP_PREFLIGHT_PROVEN=1
    return 0
}

exact_anchor_exists() {
    local tool="$1" builtin="$2" target="$3" listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 2
    printf '%s\n' "$listing" | grep -Fqx -- "-A $builtin -j $target"
}

exact_anchor_count() {
    local tool="$1" builtin="$2" target="$3" count
    local listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    count="$(printf '%s\n' "$listing" | grep -Fxc -- "-A $builtin -j $target" || true)"
    is_decimal "$count" || count=0
    printf '%s\n' "$count"
}

owned_chain_reference_count() {
    local tool="$1" target="$2" listing count
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    count="$(printf '%s\n' "$listing" | awk -v target="$target" '
        $1 == "-A" {
            for (i = 3; i <= NF; i++) {
                if (($i == "-j" || $i == "--jump" || $i == "-g" || $i == "--goto") &&
                    $(i + 1) == target) count++
            }
        }
        END { print count + 0 }
    ')" || return 1
    is_decimal "$count" || return 1
    printf '%s\n' "$count"
}

owned_chain_exists() {
    local listing
    listing="$("$1" -t mangle -S 2>/dev/null)" || return 2
    printf '%s\n' "$listing" | grep -Fqx -- "-N $2"
}

delete_exact_anchor() {
    local tool="$1" builtin="$2" target="$3"
    exact_anchor_exists "$tool" "$builtin" "$target"
    case $? in 0) ;; 1) return 0;; *) return 1;; esac
    "$tool" -t mangle -D "$builtin" -j "$target" 2>/dev/null || return 1
    exact_anchor_exists "$tool" "$builtin" "$target"
    case $? in 1) return 0;; *) return 1;; esac
}

destroy_owned_chain() {
    local tool="$1" chain="$2"
    owned_chain_exists "$tool" "$chain"
    case $? in 0) ;; 1) return 0;; *) return 1;; esac
    "$tool" -t mangle -X "$chain" 2>/dev/null || return 1
    owned_chain_exists "$tool" "$chain"
    case $? in 1) return 0;; *) return 1;; esac
}

FIREWALL_CLEANUP_PREFLIGHT_ERROR=""
owner_rule_once() {
    local tool="$1" op="$2" chain="$3" proto="$4" direction="$5" ports="$6" packet_count="$7" cb_dir="$8"
    local qnum="$9" mark="${10}" connbytes="${11}" multiport="${12}" markcap="${13}"
    set -- -t mangle "$op" "$chain" -p "$proto"
    if [ "$multiport" = 1 ]; then
        if [ "$direction" = out ]; then set -- "$@" -m multiport --dports "$ports"; else set -- "$@" -m multiport --sports "$ports"; fi
    else
        if [ "$direction" = out ]; then set -- "$@" --dport "$ports"; else set -- "$@" --sport "$ports"; fi
    fi
    [ "$connbytes" != 1 ] || set -- "$@" -m connbytes --connbytes "1:$packet_count" --connbytes-dir "$cb_dir" --connbytes-mode packets
    [ "$markcap" != 1 ] || set -- "$@" -m mark ! --mark "$mark/$mark"
    set -- "$@" -j NFQUEUE --queue-num "$qnum" --queue-bypass
    "$tool" "$@" >/dev/null 2>&1
}

owner_rule_set() {
    local tool="$1" op="$2" chain="$3" proto="$4" direction="$5" ports="$6" packet_count="$7" cb_dir="$8"
    local qnum="$9" mark="${10}" connbytes="${11}" multiport="${12}" markcap="${13}" old_ifs item rc=0
    [ -n "$ports" ] || return 0
    if [ "$multiport" = 1 ]; then owner_rule_once "$tool" "$op" "$chain" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" "$multiport" "$markcap"; return; fi
    old_ifs="$IFS"; IFS=,; set -- $ports; IFS="$old_ifs"
    for item in "$@"; do owner_rule_once "$tool" "$op" "$chain" "$proto" "$direction" "$item" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" 0 "$markcap" || rc=1; done
    return "$rc"
}

owner_payload_rule_once() {
    local tool="$1" parent="$2" ordinal="$3" proto="$4" direction="$5" ports="$6" packet_count="$7" cb_dir="$8"
    local qnum="$9" mark="${10}" connbytes="${11}" multiport="${12}" markcap="${13}" refs rules jumps
    owner_rule_chain "$parent" "$ordinal" || return 1
    owned_chain_exists "$tool" "$OWNER_RULE_CHAIN" || return 1
    jumps="$(exact_anchor_count "$tool" "$parent" "$OWNER_RULE_CHAIN")" || return 1
    refs="$(owned_chain_reference_count "$tool" "$OWNER_RULE_CHAIN")" || return 1
    rules="$(chain_owned_rule_count "$tool" "$OWNER_RULE_CHAIN")" || return 1
    [ "$jumps" = 1 ] && [ "$refs" = 1 ] && [ "$rules" = 1 ] || return 1
    owner_rule_once "$tool" -C "$OWNER_RULE_CHAIN" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" "$multiport" "$markcap"
}

owner_payload_rule_set() {
    local tool="$1" parent="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    local qnum="$8" mark="$9" connbytes="${10}" multiport="${11}" markcap="${12}" item old_ifs ordinal first
    [ -n "$ports" ] || return 0
    if [ "$parent" = "$ZAPRET2_OUT" ]; then OWNER_PAYLOAD_OUT_ORDINAL=$((OWNER_PAYLOAD_OUT_ORDINAL + 1)); ordinal="$OWNER_PAYLOAD_OUT_ORDINAL"
    else OWNER_PAYLOAD_IN_ORDINAL=$((OWNER_PAYLOAD_IN_ORDINAL + 1)); ordinal="$OWNER_PAYLOAD_IN_ORDINAL"; fi
    if [ "$multiport" = 1 ]; then
        owner_payload_rule_once "$tool" "$parent" "$ordinal" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" 1 "$markcap"
        return
    fi
    old_ifs="$IFS"; IFS=,; set -- $ports; IFS="$old_ifs"
    first=1
    for item in "$@"; do
        if [ "$first" = 0 ]; then
            if [ "$parent" = "$ZAPRET2_OUT" ]; then OWNER_PAYLOAD_OUT_ORDINAL=$((OWNER_PAYLOAD_OUT_ORDINAL + 1)); ordinal="$OWNER_PAYLOAD_OUT_ORDINAL"
            else OWNER_PAYLOAD_IN_ORDINAL=$((OWNER_PAYLOAD_IN_ORDINAL + 1)); ordinal="$OWNER_PAYLOAD_IN_ORDINAL"; fi
        fi
        first=0
        owner_payload_rule_once "$tool" "$parent" "$ordinal" "$proto" "$direction" "$item" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" 0 "$markcap" || return 1
    done
}

owner_family_generation_healthy() {
    local tool="$1" family="$2" active connbytes multiport markcap expected out_count in_count out_anchor in_anchor out_refs in_refs subchains
    if [ "$family" = ipv4 ]; then active="$OWNER_STATE_IPV4_ACTIVE"; connbytes="$OWNER_STATE_IPV4_CONNBYTES"; multiport="$OWNER_STATE_IPV4_MULTIPORT"; markcap="$OWNER_STATE_IPV4_MARK"; expected="$OWNER_STATE_IPV4_RULES"
    else active="$OWNER_STATE_IPV6_ACTIVE"; connbytes="$OWNER_STATE_IPV6_CONNBYTES"; multiport="$OWNER_STATE_IPV6_MULTIPORT"; markcap="$OWNER_STATE_IPV6_MARK"; expected="$OWNER_STATE_IPV6_RULES"; fi
    if [ "$active" = 0 ]; then owned_family_absent "$tool"; return; fi
    "$tool" -t mangle -S >/dev/null 2>&1 || {
        FIREWALL_CLEANUP_PREFLIGHT_ERROR="$tool mangle ruleset is unavailable"
        return 1
    }
    owned_chain_exists "$tool" "$ZAPRET2_OUT" || return 1
    owned_chain_exists "$tool" "$ZAPRET2_PROBE" && return 1
    out_anchor="$(exact_anchor_count "$tool" OUTPUT "$ZAPRET2_OUT")" || return 1
    in_anchor="$(exact_anchor_count "$tool" INPUT "$ZAPRET2_IN")" || return 1
    out_refs="$(owned_chain_reference_count "$tool" "$ZAPRET2_OUT")" || return 1
    in_refs="$(owned_chain_reference_count "$tool" "$ZAPRET2_IN")" || return 1
    [ "$out_anchor" = 1 ] && [ "$out_refs" = 1 ] || return 1
    if [ "$connbytes" = 1 ]; then
        owned_chain_exists "$tool" "$ZAPRET2_IN" || return 1
        [ "$in_anchor" = 1 ] && [ "$in_refs" = 1 ] || return 1
    else
        if owned_chain_exists "$tool" "$ZAPRET2_IN"; then return 1
        else case $? in 1) ;; *) return 1;; esac; fi
        [ "$in_anchor" = 0 ] && [ "$in_refs" = 0 ] || return 1
    fi
    out_count="$(chain_owned_rule_count "$tool" "$ZAPRET2_OUT")" || return 1
    if [ "$connbytes" = 1 ]; then in_count="$(chain_owned_rule_count "$tool" "$ZAPRET2_IN")" || return 1
    else in_count=0; fi
    [ $((out_count + in_count)) = "$expected" ] || return 1
    subchains="$("$tool" -t mangle -S 2>/dev/null | awk -v p="Z2R_${FIREWALL_TAG}_" '$1=="-N"&&index($2,p)==1{n++} END{print n+0}')" || return 1
    [ "$subchains" = "$expected" ] || return 1
    OWNER_PAYLOAD_OUT_ORDINAL=0; OWNER_PAYLOAD_IN_ORDINAL=0
    owner_payload_rule_set "$tool" "$ZAPRET2_OUT" tcp out "$OWNER_STATE_PORTS_TCP" "$OWNER_STATE_PKT_OUT" original "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || return 1
    owner_payload_rule_set "$tool" "$ZAPRET2_OUT" udp out "$OWNER_STATE_PORTS_UDP" "$OWNER_STATE_PKT_OUT" original "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || return 1
    if [ "$connbytes" = 1 ]; then
        owner_payload_rule_set "$tool" "$ZAPRET2_IN" tcp in "$OWNER_STATE_PORTS_TCP" "$OWNER_STATE_PKT_IN" reply "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || return 1
        owner_payload_rule_set "$tool" "$ZAPRET2_IN" udp in "$OWNER_STATE_PORTS_UDP" "$OWNER_STATE_PKT_IN" reply "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || return 1
    fi
    return 0
}

audit_owned_firewall_for_cleanup() {
    local ipv4_state ipv6_state=1
    FIREWALL_CLEANUP_PREFLIGHT_ERROR=""
    if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
        caller_holds_exact_lifecycle_lock && read_runtime_owner_marker && read_owner_state &&
            owner_state_is_current_boot && read_install_generation_meta &&
            [ "$OWNER_STATE_INSTALL_GENERATION" = "$INSTALL_META_GENERATION" ] &&
            [ "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] &&
            validate_teardown_operation_journal && return 0
        FIREWALL_CLEANUP_PREFLIGHT_ERROR="partial teardown WAL is corrupt, foreign, or not owned by the exact lifecycle generation"
        return 1
    fi
    command -v iptables >/dev/null 2>&1 || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="iptables is unavailable"; return 1; }
    owned_family_present iptables 2>/dev/null; ipv4_state=$?
    [ "$ipv4_state" -ne 2 ] || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="IPv4 mangle ruleset is unavailable"; return 1; }
    if command -v ip6tables >/dev/null 2>&1; then
        owned_family_present ip6tables 2>/dev/null; ipv6_state=$?
        [ "$ipv6_state" -ne 2 ] || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="IPv6 mangle ruleset is unavailable"; return 1; }
    fi
    if [ "$ipv4_state" = 1 ] && [ "$ipv6_state" = 1 ]; then
        return 0
    fi
    read_runtime_owner_marker || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="exact runtime ownership marker is unavailable"; return 1; }
    read_owner_state || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="owner metadata is malformed or unsafe"; return 1; }
    owner_state_is_current_boot || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="owner metadata is legacy or not bound to the current boot"; return 1; }
    read_install_generation_meta && [ "$OWNER_STATE_INSTALL_GENERATION" = "$INSTALL_META_GENERATION" ] &&
        [ "$OWNER_STATE_INSTALL_ARCHIVE_SHA256" = "$INSTALL_META_ARCHIVE_SHA256" ] || {
            FIREWALL_CLEANUP_PREFLIGHT_ERROR="owner firewall generation does not bind to the installed archive"
            return 1
        }
    owner_family_generation_healthy iptables ipv4 || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="IPv4 persisted generation does not exactly match live rules"; return 1; }
    if [ "$OWNER_STATE_IPV6_ACTIVE" = 1 ] && command -v ip6tables >/dev/null 2>&1; then
        owner_family_generation_healthy ip6tables ipv6 || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="IPv6 persisted generation does not exactly match live rules"; return 1; }
    elif [ "$OWNER_STATE_IPV6_ACTIVE" = 1 ]; then
        FIREWALL_CLEANUP_PREFLIGHT_ERROR="ip6tables is unavailable for an active owned family"
        return 1
    elif command -v ip6tables >/dev/null 2>&1 && ! owned_family_absent ip6tables; then
        FIREWALL_CLEANUP_PREFLIGHT_ERROR="unexpected IPv6 owned-family artifacts"
        return 1
    fi
    return 0
}

read_teardown_journal() {
    local key value version="" module="" generation="" fingerprint="" phase="" boot_id="" seen=""
    state_file_is_secure "$TEARDOWN_JOURNAL" && path_mode_is_0600 "$TEARDOWN_JOURNAL" || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version) case "$seen" in *v*) return 1;; esac; version="$value"; seen="${seen}v";;
            module_dir) case "$seen" in *m*) return 1;; esac; module="$value"; seen="${seen}m";;
            generation) case "$seen" in *g*) return 1;; esac; generation="$value"; seen="${seen}g";;
            fingerprint) case "$seen" in *f*) return 1;; esac; fingerprint="$value"; seen="${seen}f";;
            boot_id) case "$seen" in *b*) return 1;; esac; boot_id="$value"; seen="${seen}b";;
            phase) case "$seen" in *p*) return 1;; esac; phase="$value"; seen="${seen}p";;
            *) return 1;;
        esac
    done < "$TEARDOWN_JOURNAL"
    [ "$seen" = vmgfbp ] && [ "$version" = "$TEARDOWN_JOURNAL_VERSION" ] && [ "$module" = "$MODDIR" ] || return 1
    is_safe_token "$generation" && is_lower_sha256 "$fingerprint" && is_valid_boot_id "$boot_id" || return 1
    case "$phase" in pending|applied|consuming-ipv4|consumed-ipv4|consuming-ipv6|consumed-ipv6|consumed) ;; *) return 1;; esac
    TEARDOWN_GENERATION="$generation"; TEARDOWN_FINGERPRINT="$fingerprint"; TEARDOWN_BOOT_ID="$boot_id"; TEARDOWN_PHASE="$phase"
}

teardown_phase_rank() {
    case "$1" in pending) printf '1\n';; applied) printf '2\n';; consuming-ipv4) printf '3\n';;
        consumed-ipv4) printf '4\n';; consuming-ipv6) printf '5\n';; consumed-ipv6) printf '6\n';;
        consumed) printf '7\n';; *) return 1;; esac
}

write_teardown_journal() {
    local phase="$1" tmp="$TEARDOWN_JOURNAL.tmp.$$" boot_id have_rank want_rank
    case "$phase" in pending|applied|consuming-ipv4|consumed-ipv4|consuming-ipv6|consumed-ipv6|consumed) ;; *) return 1;; esac
    owner_state_is_current_boot || return 1
    want_rank="$(teardown_phase_rank "$phase")" || return 1
    if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
        read_teardown_journal || return 1
        [ "$TEARDOWN_GENERATION" = "$OWNER_STATE_GENERATION" ] && [ "$TEARDOWN_FINGERPRINT" = "$OWNER_STATE_FIREWALL_FINGERPRINT" ] &&
            [ "$TEARDOWN_BOOT_ID" = "$OWNER_STATE_BOOT_ID" ] || return 1
        have_rank="$(teardown_phase_rank "$TEARDOWN_PHASE")" || return 1
        [ "$have_rank" -le "$want_rank" ] 2>/dev/null || return 1
        [ "$have_rank" -lt "$want_rank" ] 2>/dev/null || return 0
    fi
    read_current_boot_id || return 1
    boot_id="$CURRENT_BOOT_ID"
    state_file_target_is_safe "$TEARDOWN_JOURNAL" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    printf 'version=%s\nmodule_dir=%s\ngeneration=%s\nfingerprint=%s\nboot_id=%s\nphase=%s\n' \
        "$TEARDOWN_JOURNAL_VERSION" "$MODDIR" "$OWNER_STATE_GENERATION" "$OWNER_STATE_FIREWALL_FINGERPRINT" "$boot_id" "$phase" > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$TEARDOWN_JOURNAL" || { rm -f "$tmp"; return 1; }
    sync || return 1
    TEARDOWN_PHASE="$phase"; TEARDOWN_BOOT_ID="$boot_id"
}

prepare_teardown_journal() {
    owner_state_is_current_boot || return 1
    if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
        read_teardown_journal || return 1
        [ "$TEARDOWN_GENERATION" = "$OWNER_STATE_GENERATION" ] &&
            [ "$TEARDOWN_FINGERPRINT" = "$OWNER_STATE_FIREWALL_FINGERPRINT" ] &&
            [ "$TEARDOWN_BOOT_ID" = "$OWNER_STATE_BOOT_ID" ] || return 1
        case "$TEARDOWN_PHASE" in
            consuming-ipv4) owned_family_absent iptables || return 1; write_teardown_journal consumed-ipv4 || return 1;;
            consuming-ipv6) command -v ip6tables >/dev/null 2>&1 && owned_family_absent ip6tables || return 1; write_teardown_journal consumed-ipv6 || return 1;;
        esac
        return 0
    fi
    write_teardown_journal pending || return 1
    write_teardown_journal applied
}

retire_teardown_journal() {
    write_teardown_journal consumed || return 1
    owned_family_absent iptables || return 1
    if command -v ip6tables >/dev/null 2>&1; then owned_family_absent ip6tables || return 1; fi
    rm -f "$TEARDOWN_JOURNAL" || return 1
    sync
}

teardown_append_rule_records() {
    local path="$1" family="$2" parent="$3" proto="$4" direction="$5" ports="$6" packet_count="$7" cb_dir="$8"
    local qnum="$9" mark="${10}" connbytes="${11}" multiport="${12}" markcap="${13}" item old_ifs ordinal first
    [ -n "$ports" ] || return 0
    old_ifs="$IFS"; if [ "$multiport" = 1 ]; then set -- "$ports"; else IFS=,; set -- $ports; IFS="$old_ifs"; fi
    first=1
    for item in "$@"; do
        if [ "$parent" = "$ZAPRET2_OUT" ]; then TEARDOWN_OUT_ORDINAL=$((TEARDOWN_OUT_ORDINAL + 1)); ordinal="$TEARDOWN_OUT_ORDINAL"
        else TEARDOWN_IN_ORDINAL=$((TEARDOWN_IN_ORDINAL + 1)); ordinal="$TEARDOWN_IN_ORDINAL"; fi
        owner_rule_chain "$parent" "$ordinal" || return 1; chain="$OWNER_RULE_CHAIN"
        TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1))
        printf 'record|%s|pending|%s|anchor|%s|%s|Z2M%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$parent" "$chain" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$path"
        TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1))
        TEARDOWN_LINE_HEX="$(teardown_snapshot_rule_hex "$TEARDOWN_FAMILY_SNAPSHOT" "$chain" 1)" || return 1
        printf 'record|%s|pending|%s|rule|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|1|%s|Z2M%s_%s_%s\n' \
            "$TEARDOWN_RECORD_ID" "$family" "$chain" "$proto" "$direction" "$item" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" "$multiport" "$markcap" "$TEARDOWN_LINE_HEX" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$path"
        TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1))
        printf 'record|%s|pending|%s|chain|%s|Z2X%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$chain" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$path"
        first=0
    done
}

teardown_snapshot_rule_hex() {
    local snapshot="$1" chain="$2" ordinal="$3" line
    line="$(printf '%s\n' "$snapshot" | awk -v chain="$chain" -v ordinal="$ordinal" '$1=="-A"&&$2==chain { if(++n==ordinal) { print; found=1; exit } } END { if(!found) exit 1 }')" || return 1
    printf '%s' "$line" | od -An -v -tx1 | tr -d '[:space:]'
}

owner_family_snapshot_structurally_healthy() {
    local family="$1" listing="$2" active connbytes expected result
    if [ "$family" = ipv4 ]; then active="$OWNER_STATE_IPV4_ACTIVE"; connbytes="$OWNER_STATE_IPV4_CONNBYTES"; expected="$OWNER_STATE_IPV4_RULES"
    else active="$OWNER_STATE_IPV6_ACTIVE"; connbytes="$OWNER_STATE_IPV6_CONNBYTES"; expected="$OWNER_STATE_IPV6_RULES"; fi
    result="$(printf '%s\n' "$listing" | awk -v active="$active" -v connbytes="$connbytes" -v expected="$expected" -v out="$ZAPRET2_OUT" -v inchain="$ZAPRET2_IN" -v probe="$ZAPRET2_PROBE" -v prefix="Z2R_${FIREWALL_TAG}_" '
        $1=="-N" { if($2==out) no++; else if($2==inchain) ni++; else if($2==probe) np++; else if(index($2,prefix)==1){ ns++; declared[$2]++ } }
        $1=="-A" {
            if($2==out || $2==inchain) {
                if(NF!=4 || $3!="-j" || index($4,prefix)!=1) bad=1
                else { parentrules++; jumps[$4]++; if($2==out && index($4,prefix "O")!=1) bad=1; if($2==inchain && index($4,prefix "I")!=1) bad=1 }
            } else if(index($2,prefix)==1) payload[$2]++
            for(i=3;i<=NF;i++) if(($i=="-j"||$i=="--jump"||$i=="-g"||$i=="--goto")) {
                if($(i+1)==out) refsout++; if($(i+1)==inchain) refsin++; if($(i+1)==probe) refsprobe++
                if(index($(i+1),prefix)==1) subrefs[$(i+1)]++
            }
            if($2=="OUTPUT" && NF==4 && $3=="-j" && $4==out) ao++
            if($2=="INPUT" && NF==4 && $3=="-j" && $4==inchain) ai++
        }
        END {
            for(c in declared) if(declared[c]!=1 || jumps[c]!=1 || subrefs[c]!=1 || payload[c]!=1) bad=1
            for(c in jumps) if(declared[c]!=1) bad=1
            if(active==0) ok=(no+ni+np+ns+refsout+refsin+refsprobe==0)
            else if(connbytes==1) ok=(!bad&&no==1&&ni==1&&np==0&&ao==1&&ai==1&&refsout==1&&refsin==1&&refsprobe==0&&ns==expected&&parentrules==expected)
            else ok=(!bad&&no==1&&ni==0&&np==0&&ao==1&&ai==0&&refsout==1&&refsin==0&&refsprobe==0&&ns==expected&&parentrules==expected)
            print ok?"ok":"bad"
        }
    ')" || return 1
    [ "$result" = ok ]
}

create_teardown_operation_journal() {
    local tmp="$TEARDOWN_JOURNAL.tmp.$$" family active connbytes multiport markcap tool boot_id token
    owner_state_is_current_boot || return 1
    read_current_boot_id || return 1; boot_id="$CURRENT_BOOT_ID"
    state_file_target_is_safe "$TEARDOWN_JOURNAL" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    token="$(new_lifecycle_token)" || return 1
    TEARDOWN_TOKEN_SHORT="$(printf '%s' "$token" | tr -cd 'A-Za-z0-9' | cut -c1-8)"
    [ "${#TEARDOWN_TOKEN_SHORT}" -ge 6 ] 2>/dev/null || return 1
    TEARDOWN_RECORD_ID=0
    umask 077
    printf 'version=%s\nmodule_dir=%s\ngeneration=%s\nfingerprint=%s\nboot_id=%s\ntoken=%s\nmode=records\n' \
        "$TEARDOWN_JOURNAL_VERSION" "$MODDIR" "$OWNER_STATE_GENERATION" "$OWNER_STATE_FIREWALL_FINGERPRINT" "$boot_id" "$token" > "$tmp" || return 1
    for family in ipv4 ipv6; do
        if [ "$family" = ipv4 ]; then
            active="$OWNER_STATE_IPV4_ACTIVE"; connbytes="$OWNER_STATE_IPV4_CONNBYTES"; multiport="$OWNER_STATE_IPV4_MULTIPORT"; markcap="$OWNER_STATE_IPV4_MARK"
        else
            active="$OWNER_STATE_IPV6_ACTIVE"; connbytes="$OWNER_STATE_IPV6_CONNBYTES"; multiport="$OWNER_STATE_IPV6_MULTIPORT"; markcap="$OWNER_STATE_IPV6_MARK"
        fi
        [ "$active" = 1 ] || continue
        if [ "$family" = ipv4 ]; then TEARDOWN_FAMILY_SNAPSHOT="$(iptables -t mangle -S 2>/dev/null)" || { rm -f "$tmp"; return 1; }
        else TEARDOWN_FAMILY_SNAPSHOT="$(ip6tables -t mangle -S 2>/dev/null)" || { rm -f "$tmp"; return 1; }; fi
        owner_family_snapshot_structurally_healthy "$family" "$TEARDOWN_FAMILY_SNAPSHOT" || { rm -f "$tmp"; return 1; }
        TEARDOWN_OUT_ORDINAL=0; TEARDOWN_IN_ORDINAL=0
        TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1)); printf 'record|%s|pending|%s|anchor|OUTPUT|%s|Z2M%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$ZAPRET2_OUT" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$tmp"
        teardown_append_rule_records "$tmp" "$family" "$ZAPRET2_OUT" tcp out "$OWNER_STATE_PORTS_TCP" "$OWNER_STATE_PKT_OUT" original "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || { rm -f "$tmp"; return 1; }
        teardown_append_rule_records "$tmp" "$family" "$ZAPRET2_OUT" udp out "$OWNER_STATE_PORTS_UDP" "$OWNER_STATE_PKT_OUT" original "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || { rm -f "$tmp"; return 1; }
        if [ "$connbytes" = 1 ]; then
            TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1)); printf 'record|%s|pending|%s|anchor|INPUT|%s|Z2M%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$ZAPRET2_IN" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$tmp"
            teardown_append_rule_records "$tmp" "$family" "$ZAPRET2_IN" tcp in "$OWNER_STATE_PORTS_TCP" "$OWNER_STATE_PKT_IN" reply "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || { rm -f "$tmp"; return 1; }
            teardown_append_rule_records "$tmp" "$family" "$ZAPRET2_IN" udp in "$OWNER_STATE_PORTS_UDP" "$OWNER_STATE_PKT_IN" reply "$OWNER_STATE_QNUM" "$OWNER_STATE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || { rm -f "$tmp"; return 1; }
        fi
        TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1)); printf 'record|%s|pending|%s|chain|%s|Z2X%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$ZAPRET2_OUT" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$tmp"
        if [ "$connbytes" = 1 ]; then
            TEARDOWN_RECORD_ID=$((TEARDOWN_RECORD_ID + 1)); printf 'record|%s|pending|%s|chain|%s|Z2X%s_%s_%s\n' "$TEARDOWN_RECORD_ID" "$family" "$ZAPRET2_IN" "${family#ipv}" "$TEARDOWN_RECORD_ID" "$TEARDOWN_TOKEN_SHORT" >> "$tmp"
        fi
    done
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$TEARDOWN_JOURNAL" || { rm -f "$tmp"; return 1; }
    sync
}

validate_teardown_operation_journal() {
    local identity
    state_file_is_secure "$TEARDOWN_JOURNAL" && path_mode_is_0600 "$TEARDOWN_JOURNAL" || return 1
    identity="$(awk -F '|' -v version="$TEARDOWN_JOURNAL_VERSION" -v module="$MODDIR" \
        -v a4="$OWNER_STATE_IPV4_ACTIVE" -v a6="$OWNER_STATE_IPV6_ACTIVE" \
        -v c4="$OWNER_STATE_IPV4_CONNBYTES" -v m4="$OWNER_STATE_IPV4_MULTIPORT" -v k4="$OWNER_STATE_IPV4_MARK" \
        -v c6="$OWNER_STATE_IPV6_CONNBYTES" -v m6="$OWNER_STATE_IPV6_MULTIPORT" -v k6="$OWNER_STATE_IPV6_MARK" \
        -v tcp="$OWNER_STATE_PORTS_TCP" -v udp="$OWNER_STATE_PORTS_UDP" \
        -v po="$OWNER_STATE_PKT_OUT" -v pi="$OWNER_STATE_PKT_IN" -v q="$OWNER_STATE_QNUM" -v mark="$OWNER_STATE_DESYNC_MARK" \
        -v outchain="$OWNER_STATE_OUT_CHAIN" -v inchain="$OWNER_STATE_IN_CHAIN" -v tag="$OWNER_STATE_FIREWALL_TAG" '
        function add(f,s) { expected[++expected_n]=f "|" s }
        function one(f,chain,proto,dir,port,pkt,cb,conn,multi,markcap, key,side,rulechain) {
            key=f SUBSEP chain
            ord[key]++; side=(chain==outchain?"O":"I"); rulechain="Z2R_" tag "_" side ord[key]
            add(f,"anchor|" chain "|" rulechain)
            add(f,"rule|" rulechain "|" proto "|" dir "|" port "|" pkt "|" cb "|" q "|" mark "|" conn "|" multi "|" markcap "|1")
            add(f,"chain|" rulechain)
        }
        function rules(f,chain,proto,dir,ports,pkt,cb,conn,multi,markcap, n,p,i) {
            if (ports=="") return
            if (multi==1) { one(f,chain,proto,dir,ports,pkt,cb,conn,multi,markcap); return }
            n=split(ports,p,","); for(i=1;i<=n;i++) one(f,chain,proto,dir,p[i],pkt,cb,conn,0,markcap)
        }
        function family(f,active,conn,multi,markcap) {
            if(active!=1) return
            add(f,"anchor|OUTPUT|" outchain)
            rules(f,outchain,"tcp","out",tcp,po,"original",conn,multi,markcap)
            rules(f,outchain,"udp","out",udp,po,"original",conn,multi,markcap)
            if(conn==1) {
                add(f,"anchor|INPUT|" inchain)
                rules(f,inchain,"tcp","in",tcp,pi,"reply",conn,multi,markcap)
                rules(f,inchain,"udp","in",udp,pi,"reply",conn,multi,markcap)
            }
            add(f,"chain|" outchain); if(conn==1) add(f,"chain|" inchain)
        }
        BEGIN { family("ipv4",a4,c4,m4,k4); family("ipv6",a6,c6,m6,k6) }
        NR==1 { if ($0 != "version=" version) exit 1; next }
        NR==2 { if ($0 != "module_dir=" module) exit 1; next }
        NR==3 { split($0,a,"="); if (a[1]!="generation" || a[2]=="") exit 1; generation=a[2]; next }
        NR==4 { split($0,a,"="); if (a[1]!="fingerprint" || length(a[2])!=64 || a[2] !~ /^[0-9a-f]+$/) exit 1; fingerprint=a[2]; next }
        NR==5 { split($0,a,"="); if (a[1]!="boot_id" || a[2]=="") exit 1; boot=a[2]; next }
        NR==6 { split($0,a,"="); if (a[1]!="token" || a[2]=="" || a[2] !~ /^[A-Za-z0-9._:-]+$/) exit 1; token=a[2]; short=token; gsub(/[^A-Za-z0-9]/,"",short); short=substr(short,1,8); if(length(short)<6) exit 1; next }
        NR==7 { if ($0 != "mode=records") exit 1; next }
        NR>7 {
            if ($1!="record" || $2 != ++id || $3 !~ /^(pending|applied|consuming|target-consumed|consumed)$/ || $4 !~ /^ipv[46]$/) exit 1
            digit=substr($4,4,1)
            if ($5=="anchor") { if (NF!=8 || $6 !~ /^[A-Za-z0-9_]+$/ || $7 !~ /^[A-Za-z0-9_]+$/ || $8 != "Z2M" digit "_" id "_" short || seen_name[$8]++) exit 1; actual=$4 "|anchor|" $6 "|" $7 }
            else if ($5=="chain") { if (NF!=7 || $6 !~ /^[A-Za-z0-9_]+$/ || $7 != "Z2X" digit "_" id "_" short || seen_name[$7]++) exit 1; actual=$4 "|chain|" $6 }
            else if ($5=="rule") {
                # The port field is authenticated below by exact equality with
                # the already normalized owner metadata.  Do not duplicate its
                # grammar here: doing so previously rejected valid ranges such
                # as 80:65535 after this writer had durably emitted them.
                if (NF!=19 || index($6,"Z2R_" tag "_")!=1 || $7 !~ /^(tcp|udp)$/ || $8 !~ /^(out|in)$/ || $10 !~ /^[0-9]+$/ || $11 !~ /^(original|reply)$/ || $12 !~ /^[0-9]+$/ || $13 !~ /^(0x)?[0-9A-Fa-f]+$/ || $14 !~ /^(0|1)$/ || $15 !~ /^(0|1)$/ || $16 !~ /^(0|1)$/ || $17 != 1 || $18 !~ /^[0-9a-f]+$/ || $19 != "Z2M" digit "_" id "_" short || seen_name[$19]++) exit 1
                actual=$4 "|rule|" $6 "|" $7 "|" $8 "|" $9 "|" $10 "|" $11 "|" $12 "|" $13 "|" $14 "|" $15 "|" $16 "|" $17
            }
            else exit 1
            if (id>expected_n || actual!=expected[id]) { if (ENVIRON["Z2_WAL_DEBUG"]=="1") print "manifest mismatch " id ": " actual " != " expected[id] > "/dev/stderr"; exit 1 }
        }
        END { if (NR<7 || id!=expected_n || expected_n==0) exit 1; print generation "|" fingerprint "|" boot }
    ' "$TEARDOWN_JOURNAL" 2>/dev/null)" || return 1
    [ "$identity" = "$OWNER_STATE_GENERATION|$OWNER_STATE_FIREWALL_FINGERPRINT|$OWNER_STATE_BOOT_ID" ]
}

set_teardown_record_state() {
    local id="$1" from="$2" to="$3" tmp="$TEARDOWN_JOURNAL.tmp.$$"
    awk -F '|' -v OFS='|' -v id="$id" -v from="$from" -v to="$to" '
        $1=="record" && $2==id { if ($3!=from) exit 2; $3=to; changed=1 }
        { print }
        END { if (!changed) exit 3 }
    ' "$TEARDOWN_JOURNAL" > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$TEARDOWN_JOURNAL" || { rm -f "$tmp"; return 1; }
    sync
}

teardown_marker_positions() {
    local tool="$1" chain="$2" marker="$3" listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 2
    TEARDOWN_MARKER_POSITIONS="$(printf '%s\n' "$listing" | awk -v chain="$chain" -v marker="$marker" '
        $1=="-A" && $2==chain { n++; if (NF==4 && $3=="-j" && $4==marker) printf "%s%s", found++?" ":"", n }
    ')" || return 2
}

locate_teardown_rule_block() {
    local tool="$1" family="$2" chain="$3" id="$4" listing lines line hex actual="" expected
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    lines="$(printf '%s\n' "$listing" | awk -v chain="$chain" '$1=="-A"&&$2==chain { print }')" || return 1
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        hex="$(printf '%s' "$line" | od -An -v -tx1 | tr -d '[:space:]')" || return 1
        actual="${actual}${actual:+
}$hex"
    done <<EOF
$lines
EOF
    expected="$(awk -F '|' -v family="$family" -v chain="$chain" -v id="$id" '$1=="record"&&$4==family&&$5=="rule"&&$6==chain&&$2>=id&&$3!="consumed" { print $18 }' "$TEARDOWN_JOURNAL")" || return 1
    [ -n "$expected" ] || return 1
    printf '%s\n' "$actual" | awk -v expected="$expected" '
        BEGIN { ne=split(expected,e,"\n") }
        { a[++na]=$0 }
        END {
            for(i=1;i<=na-ne+1;i++) { ok=1; for(j=1;j<=ne;j++) if(a[i+j-1]!=e[j]) { ok=0; break } if(ok){ found++; pos=i } }
            if(found!=1) exit 1; print pos
        }
    '
}

ensure_teardown_marker_chain() {
    local tool="$1" marker="$2" listing count rules
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    if ! printf '%s\n' "$listing" | grep -Fqx -- "-N $marker"; then
        "$tool" -t mangle -N "$marker" >/dev/null 2>&1 || return 1
        "$tool" -t mangle -A "$marker" -j RETURN >/dev/null 2>&1 || return 1
        return 0
    fi
    rules="$(printf '%s\n' "$listing" | grep -F -- "-A $marker " || true)"
    if [ -z "$rules" ]; then "$tool" -t mangle -A "$marker" -j RETURN >/dev/null 2>&1
    else [ "$rules" = "-A $marker -j RETURN" ]; fi
}

prepare_teardown_marker() {
    local tool="$1" kind="$2" chain="$3" target="$4" marker="$5" family="$6" id="$7" expected_hex="$8" positions count endpos targetpos listing
    ensure_teardown_marker_chain "$tool" "$marker" || return 1
    teardown_marker_positions "$tool" "$chain" "$marker" || return 1
    positions="$TEARDOWN_MARKER_POSITIONS"; set -- $positions; count=$#
    [ "$count" -le 2 ] || return 1
    if [ "$count" = 0 ]; then
        if [ "$kind" = anchor ]; then
            listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
            targetpos="$(printf '%s\n' "$listing" | awk -v chain="$chain" -v target="$target" '$1=="-A"&&$2==chain { n++; if (NF==4&&$3=="-j"&&$4==target) { if(found) exit 2; found=1; pos=n } } END { if(!found) exit 1; print pos }')" || return 1
        else
            targetpos="$(locate_teardown_rule_block "$tool" "$family" "$chain" "$id")" || return 1
        fi
        "$tool" -t mangle -I "$chain" $((targetpos + 1)) -j "$marker" >/dev/null 2>&1 || return 1
        "$tool" -t mangle -I "$chain" "$targetpos" -j "$marker" >/dev/null 2>&1 || return 1
    elif [ "$count" = 1 ]; then
        endpos="$1"; [ "$endpos" -gt 1 ] 2>/dev/null || return 1
        "$tool" -t mangle -I "$chain" $((endpos - 1)) -j "$marker" >/dev/null 2>&1 || return 1
    fi
    teardown_marker_positions "$tool" "$chain" "$marker" || return 1
    set -- $TEARDOWN_MARKER_POSITIONS
    [ "$#" = 2 ] && [ "$2" -eq $(( $1 + 2 )) ] 2>/dev/null || return 1
    teardown_bracket_matches "$tool" "$chain" "$marker" "$expected_hex"
}

teardown_bracket_matches() {
    local tool="$1" chain="$2" marker="$3" expected_hex="$4" listing middle middle_hex
    teardown_marker_positions "$tool" "$chain" "$marker" || return 1
    set -- $TEARDOWN_MARKER_POSITIONS
    [ "$#" = 2 ] && [ "$2" -eq $(( $1 + 2 )) ] 2>/dev/null || return 1
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    middle="$(printf '%s\n' "$listing" | awk -v chain="$chain" -v pos="$(( $1 + 1 ))" '$1=="-A"&&$2==chain { if(++n==pos){ print; found=1; exit } } END { if(!found) exit 1 }')" || return 1
    middle_hex="$(printf '%s' "$middle" | od -An -v -tx1 | tr -d '[:space:]')" || return 1
    [ "$middle_hex" = "$expected_hex" ]
}

consume_bracketed_teardown_target() {
    local tool="$1" chain="$2" marker="$3" expected_hex="$4"
    teardown_marker_positions "$tool" "$chain" "$marker" || return 1
    set -- $TEARDOWN_MARKER_POSITIONS
    [ "$#" = 2 ] || return 1
    if [ "$2" -eq $(( $1 + 2 )) ] 2>/dev/null; then
        teardown_bracket_matches "$tool" "$chain" "$marker" "$expected_hex" || return 1
        TEARDOWN_TARGET_PRESENT=1
    elif [ "$2" -ne $(( $1 + 1 )) ] 2>/dev/null; then
        return 1
    else
        TEARDOWN_TARGET_PRESENT=0
    fi
}

cleanup_teardown_marker() {
    local tool="$1" chain="$2" marker="$3" rc listing
    teardown_marker_positions "$tool" "$chain" "$marker" || return 1
    set -- $TEARDOWN_MARKER_POSITIONS
    [ "$#" -le 2 ] || return 1
    while [ "$#" -gt 0 ]; do "$tool" -t mangle -D "$chain" -j "$marker" >/dev/null 2>&1 || return 1; shift; done
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    if printf '%s\n' "$listing" | grep -Fqx -- "-N $marker"; then
        "$tool" -t mangle -D "$marker" -j RETURN >/dev/null 2>&1 || true
        "$tool" -t mangle -X "$marker" >/dev/null 2>&1 || return 1
    fi
}

run_teardown_operation_journal() {
    local line old_ifs id state family kind tool rc chain marker target proto direction ports packet_count cb_dir qnum mark connbytes multiport markcap tomb listing ordinal line_hex expected_hex
    owner_state_is_current_boot || return 1
    validate_teardown_operation_journal || return 1
    while :; do
        line="$(awk -F '|' '$1=="record" && $3!="consumed" { print; exit }' "$TEARDOWN_JOURNAL")" || return 1
        [ -n "$line" ] || return 0
        old_ifs="$IFS"; IFS='|'; set -- $line; IFS="$old_ifs"
        id="$2"; state="$3"; family="$4"; kind="$5"; shift 5
        if [ "$family" = ipv4 ]; then tool=iptables; else tool=ip6tables; fi
        command -v "$tool" >/dev/null 2>&1 || return 1
        case "$state" in
            pending) set_teardown_record_state "$id" pending applied || return 1; continue;;
            applied)
                if [ "$kind" = chain ]; then
                    chain="$1"; tomb="$2"
                    "$tool" -t mangle -S >/dev/null 2>&1 || return 1
                    owned_chain_exists "$tool" "$chain" || return 1
                    owned_chain_exists "$tool" "$tomb"; case $? in 1) ;; *) return 1;; esac
                    set_teardown_record_state "$id" applied consuming || return 1
                    "$tool" -t mangle -E "$chain" "$tomb" >/dev/null 2>&1 || return 1
                else
                    if [ "$kind" = anchor ]; then
                        chain="$1"; target="$2"; marker="$3"; expected_hex="$(printf '%s' "-A $chain -j $target" | od -An -v -tx1 | tr -d '[:space:]')" || return 1
                    else
                        chain="$1"; proto="$2"; direction="$3"; ports="$4"; packet_count="$5"; cb_dir="$6"; qnum="$7"; mark="$8"; connbytes="$9"; multiport="${10}"; markcap="${11}"; ordinal="${12}"; line_hex="${13}"; marker="${14}"
                        owner_rule_once "$tool" -C "$chain" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" "$qnum" "$mark" "$connbytes" "$multiport" "$markcap" || return 1
                        target=""; expected_hex="$line_hex"
                    fi
                    prepare_teardown_marker "$tool" "$kind" "$chain" "$target" "$marker" "$family" "$id" "$expected_hex" || return 1
                    set_teardown_record_state "$id" applied consuming || return 1
                fi
                continue
                ;;
            consuming)
                if [ "$kind" = chain ]; then
                    chain="$1"; tomb="$2"; listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
                    if printf '%s\n' "$listing" | grep -Fqx -- "-N $tomb"; then :
                    elif printf '%s\n' "$listing" | grep -Fqx -- "-N $chain"; then "$tool" -t mangle -E "$chain" "$tomb" >/dev/null 2>&1 || return 1
                    else return 1; fi
                    set_teardown_record_state "$id" consuming target-consumed || return 1
                else
                    if [ "$kind" = anchor ]; then
                        chain="$1"; target="$2"; marker="$3"; expected_hex="$(printf '%s' "-A $chain -j $target" | od -An -v -tx1 | tr -d '[:space:]')" || return 1
                    else chain="$1"; expected_hex="${13}"; marker="${14}"; fi
                    consume_bracketed_teardown_target "$tool" "$chain" "$marker" "$expected_hex" || return 1
                    if [ "$TEARDOWN_TARGET_PRESENT" = 1 ]; then
                        if [ "$kind" = anchor ]; then "$tool" -t mangle -D "$chain" -j "$target" >/dev/null 2>&1 || return 1
                        else owner_rule_once "$tool" -D "$chain" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" || return 1; fi
                    fi
                    set_teardown_record_state "$id" consuming target-consumed || return 1
                fi
                ;;
            target-consumed)
                if [ "$kind" = chain ]; then
                    tomb="$2"; listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
                    if printf '%s\n' "$listing" | grep -Fqx -- "-N $tomb"; then "$tool" -t mangle -X "$tomb" >/dev/null 2>&1 || return 1; fi
                else
                    if [ "$kind" = anchor ]; then chain="$1"; marker="$3"; else chain="$1"; marker="${14}"; fi
                    cleanup_teardown_marker "$tool" "$chain" "$marker" || return 1
                fi
                set_teardown_record_state "$id" target-consumed consumed || return 1
                ;;
        esac
    done
}

retire_teardown_operation_journal() {
    validate_teardown_operation_journal || return 1
    awk -F '|' '$1=="record" && $3!="consumed" { bad=1 } END { exit bad ? 1 : 0 }' "$TEARDOWN_JOURNAL" || return 1
    TEARDOWN_COMMIT_PROVEN=0
    teardown_operation_commit_proven
}

teardown_operation_commit_proven() {
    [ "${TEARDOWN_COMMIT_PROVEN:-0}" = 1 ] && return 0
    owner_state_is_current_boot || return 1
    validate_teardown_operation_journal || return 1
    awk -F '|' '$1=="record" && $3!="consumed" { bad=1 } END { exit bad ? 1 : 0 }' "$TEARDOWN_JOURNAL" || return 1
    command -v iptables >/dev/null 2>&1 && iptables -t mangle -S >/dev/null 2>&1 || return 1
    if [ "$OWNER_STATE_IPV6_ACTIVE" = 1 ]; then command -v ip6tables >/dev/null 2>&1 && ip6tables -t mangle -S >/dev/null 2>&1 || return 1; fi
    TEARDOWN_COMMIT_PROVEN=1
}

cleanup_owned_family() {
    local tool="$1" family="${2:-}" rc=0 active connbytes multiport markcap
    if [ -z "$family" ]; then case "$tool" in iptables) family=ipv4;; *) family=ipv6;; esac; fi
    if [ -z "${OWNER_WRITE_QNUM:-}" ]; then
        if [ "$family" = ipv4 ]; then prepare_owner_generation_spec 1 0 || return 1
        else prepare_owner_generation_spec 1 1 || return 1; fi
    fi
    if [ "$family" = ipv4 ]; then active="$OWNER_WRITE_IPV4_ACTIVE"; connbytes="$OWNER_WRITE_IPV4_CONNBYTES"; multiport="$OWNER_WRITE_IPV4_MULTIPORT"; markcap="$OWNER_WRITE_IPV4_MARK"
    else active="$OWNER_WRITE_IPV6_ACTIVE"; connbytes="$OWNER_WRITE_IPV6_CONNBYTES"; multiport="$OWNER_WRITE_IPV6_MULTIPORT"; markcap="$OWNER_WRITE_IPV6_MARK"; fi
    [ "$active" = 1 ] || { owned_family_absent "$tool"; return; }
    owned_family_present "$tool"
    case $? in 0) ;; 1) return 0;; *) return 1;; esac
    "$tool" -t mangle -D OUTPUT -j "$ZAPRET2_OUT" >/dev/null 2>&1 || true
    "$tool" -t mangle -D INPUT -j "$ZAPRET2_IN" >/dev/null 2>&1 || true
    owner_rule_set "$tool" -D "$ZAPRET2_OUT" tcp out "$OWNER_WRITE_PORTS_TCP" "$OWNER_WRITE_PKT_OUT" original "$OWNER_WRITE_QNUM" "$OWNER_WRITE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || true
    owner_rule_set "$tool" -D "$ZAPRET2_OUT" udp out "$OWNER_WRITE_PORTS_UDP" "$OWNER_WRITE_PKT_OUT" original "$OWNER_WRITE_QNUM" "$OWNER_WRITE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || true
    owner_rule_set "$tool" -D "$ZAPRET2_IN" tcp in "$OWNER_WRITE_PORTS_TCP" "$OWNER_WRITE_PKT_IN" reply "$OWNER_WRITE_QNUM" "$OWNER_WRITE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || true
    owner_rule_set "$tool" -D "$ZAPRET2_IN" udp in "$OWNER_WRITE_PORTS_UDP" "$OWNER_WRITE_PKT_IN" reply "$OWNER_WRITE_QNUM" "$OWNER_WRITE_DESYNC_MARK" "$connbytes" "$multiport" "$markcap" || true
    if owned_chain_exists "$tool" "$ZAPRET2_OUT"; then "$tool" -t mangle -X "$ZAPRET2_OUT" >/dev/null 2>&1 || rc=1; fi
    if owned_chain_exists "$tool" "$ZAPRET2_IN"; then "$tool" -t mangle -X "$ZAPRET2_IN" >/dev/null 2>&1 || rc=1; fi
    owned_family_absent "$tool" || rc=1
    return "$rc"
}

cleanup_owned_firewall() {
    local rc=0 ipv4_state ipv6_state=1 committed=0
    read_iptables_status >/dev/null 2>&1 || true
    command -v iptables >/dev/null 2>&1 || return 1
    owned_family_present iptables 2>/dev/null; ipv4_state=$?
    [ "$ipv4_state" -ne 2 ] || return 1
    if command -v ip6tables >/dev/null 2>&1; then owned_family_present ip6tables 2>/dev/null; ipv6_state=$?; [ "$ipv6_state" -ne 2 ] || return 1; fi
    if [ "$ipv4_state" = 1 ] && [ "$ipv6_state" = 1 ]; then
        if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
            read_owner_state || return 1
            [ "$OWNER_STATE_SCHEMA_VERSION" = "$OWNER_STATE_VERSION" ] || return 1
            validate_teardown_operation_journal || return 1
            run_teardown_operation_journal || return 1
            retire_teardown_operation_journal || return 1
        fi
        return 0
    fi
    if read_owner_state; then
        owner_state_is_current_boot || { FIREWALL_CLEANUP_PREFLIGHT_ERROR="owner metadata is legacy or not bound to the current boot"; return 1; }
        owner_loaded_generation_for_write || return 1
        committed=1
    elif [ "${FIREWALL_MUTATED:-0}" = 1 ]; then
        prepare_owner_generation_spec "${IPV4_BUILT:-1}" "${IPV6_BUILT:-0}" || return 1
    else
        owned_family_absent iptables 2>/dev/null || return 1
        if command -v ip6tables >/dev/null 2>&1; then owned_family_absent ip6tables 2>/dev/null || return 1; fi
        return 0
    fi
    if [ "$committed" = 1 ]; then
        if [ -e "$TEARDOWN_JOURNAL" ] || [ -L "$TEARDOWN_JOURNAL" ]; then
            validate_teardown_operation_journal || return 1
        else
            create_teardown_operation_journal || return 1
        fi
        run_teardown_operation_journal || return 1
        retire_teardown_operation_journal
        return
    fi
    [ "$committed" != 1 ] || prepare_teardown_journal || return 1
    if command -v iptables >/dev/null 2>&1; then
        case "${TEARDOWN_PHASE:-}" in consumed-ipv4|consuming-ipv6|consumed-ipv6|consumed) :;;
            *) [ "$committed" != 1 ] || write_teardown_journal consuming-ipv4 || return 1
               cleanup_owned_family iptables ipv4 || rc=1
               [ "$rc" != 0 ] || { [ "$committed" != 1 ] || write_teardown_journal consumed-ipv4 || return 1; };;
        esac
    else
        rc=1
    fi
    if command -v ip6tables >/dev/null 2>&1; then
        case "${TEARDOWN_PHASE:-}" in consumed-ipv6|consumed) :;;
            *) [ "$rc" = 0 ] || return 1
               [ "$committed" != 1 ] || write_teardown_journal consuming-ipv6 || return 1
               cleanup_owned_family ip6tables ipv6 || rc=1
               [ "$rc" != 0 ] || { [ "$committed" != 1 ] || write_teardown_journal consumed-ipv6 || return 1; };;
        esac
    elif [ "$OWNER_WRITE_IPV6_ACTIVE" = 1 ]; then
        rc=1
    fi
    [ "$rc" != 0 ] || { [ "$committed" != 1 ] || retire_teardown_journal || return 1; }
    return "$rc"
}

chain_owned_rule_count() {
    local tool="$1" chain="$2" count listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    count="$(printf '%s\n' "$listing" | grep -c "^-A $chain " || true)"
    is_decimal "$count" || count=0
    printf '%s\n' "$count"
}

owned_family_present() {
    local tool="$1" listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 2
    printf '%s\n' "$listing" | awk -v out="$ZAPRET2_OUT" -v inchain="$ZAPRET2_IN" -v probe="$ZAPRET2_PROBE" -v prefix="Z2R_${FIREWALL_TAG}_" '
        $1 == "-N" && ($2 == out || $2 == inchain || $2 == probe || index($2,prefix)==1) { found=1 }
        $1 == "-A" { for (i=3;i<=NF;i++) if (($i=="-j" || $i=="--jump" || $i=="-g" || $i=="--goto") && ($(i+1)==out || $(i+1)==inchain || $(i+1)==probe || index($(i+1),prefix)==1)) found=1 }
        END { exit found ? 0 : 1 }
    '
}

# Read-only namespace discovery for a failed generation that never reached
# owner.meta publication. Dynamic chain names are strict module-owned kernel
# object identities; detecting them is safe even when teardown still requires
# a stronger journal/owner proof.
zapret2_namespace_present() {
    local tool="$1" listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 2
    printf '%s\n' "$listing" | awk '
        function owned(name, tag, side, ordinal) {
            if (name == "ZAPRET2_OUT" || name == "ZAPRET2_IN" || name == "ZAPRET2_PROBE") return 1
            if ((index(name,"Z2O_")==1 || index(name,"Z2I_")==1) && length(name)==14) {
                tag=substr(name,5,10)
                return tag !~ /[^A-Za-z0-9]/
            }
            if (index(name,"Z2R_")==1 && length(name)>=17) {
                tag=substr(name,5,10); side=substr(name,16,1); ordinal=substr(name,17)
                return substr(name,15,1)=="_" && tag !~ /[^A-Za-z0-9]/ &&
                    (side=="O" || side=="I") && ordinal ~ /^[1-9][0-9]*$/
            }
            return 0
        }
        $1 == "-N" && owned($2) { found=1 }
        $1 == "-A" {
            if (owned($2)) found=1
            for (i=3;i<=NF;i++) if (($i=="-j" || $i=="--jump" || $i=="-g" || $i=="--goto") && owned($(i+1))) found=1
        }
        END { exit found ? 0 : 1 }
    '
}

zapret2_delete_simple_jump_all() {
    local tool="$1" source="$2" target="$3" count=0
    while "$tool" -t mangle -C "$source" -j "$target" >/dev/null 2>&1; do
        [ "$count" -lt 4096 ] 2>/dev/null || return 1
        "$tool" -t mangle -D "$source" -j "$target" >/dev/null 2>&1 || return 1
        count=$((count + 1))
    done
    return 0
}

# The Magisk removal marker is a durable global start fence. Once that marker
# has been authenticated, uninstall may remove every strictly named Zapret2
# generation even when its interrupted build journal is unavailable. No broad
# table flush or rule-number deletion is used: only exact module-created jumps
# and the reserved chain namespace are touched.
purge_zapret2_namespace() {
    local tool="$1" listing chains chain rest tag suffix parent pass
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    chains="$(printf '%s\n' "$listing" | awk '
        function owned(name, tag, side, ordinal) {
            if (name == "ZAPRET2_OUT" || name == "ZAPRET2_IN" || name == "ZAPRET2_PROBE") return 1
            if ((index(name,"Z2O_")==1 || index(name,"Z2I_")==1) && length(name)==14) {
                tag=substr(name,5,10)
                return tag !~ /[^A-Za-z0-9]/
            }
            if (index(name,"Z2R_")==1 && length(name)>=17) {
                tag=substr(name,5,10); side=substr(name,16,1); ordinal=substr(name,17)
                return substr(name,15,1)=="_" && tag !~ /[^A-Za-z0-9]/ &&
                    (side=="O" || side=="I") && ordinal ~ /^[1-9][0-9]*$/
            }
            return 0
        }
        $1 == "-N" && owned($2) { print $2 }
    ')" || return 1

    for chain in $chains; do
        case "$chain" in
            Z2R_*)
                rest="${chain#Z2R_}"; tag="${rest%%_*}"; suffix="${rest#*_}"
                case "$suffix" in O[1-9]* ) parent="Z2O_$tag" ;; I[1-9]* ) parent="Z2I_$tag" ;; *) return 1 ;; esac
                zapret2_delete_simple_jump_all "$tool" "$parent" "$chain" || return 1
                ;;
            Z2O_*) zapret2_delete_simple_jump_all "$tool" OUTPUT "$chain" || return 1 ;;
            Z2I_*) zapret2_delete_simple_jump_all "$tool" INPUT "$chain" || return 1 ;;
            ZAPRET2_OUT) zapret2_delete_simple_jump_all "$tool" OUTPUT "$chain" || return 1 ;;
            ZAPRET2_IN) zapret2_delete_simple_jump_all "$tool" INPUT "$chain" || return 1 ;;
            ZAPRET2_PROBE) ;;
            *) return 1 ;;
        esac
    done

    for chain in $chains; do
        "$tool" -t mangle -S "$chain" >/dev/null 2>&1 || continue
        "$tool" -t mangle -F "$chain" >/dev/null 2>&1 || return 1
    done
    for pass in 1 2; do
        for chain in $chains; do
            case "$pass:$chain" in
                1:Z2R_*|1:ZAPRET2_PROBE|2:Z2O_*|2:Z2I_*|2:ZAPRET2_OUT|2:ZAPRET2_IN) ;;
                *) continue ;;
            esac
            "$tool" -t mangle -S "$chain" >/dev/null 2>&1 || continue
            "$tool" -t mangle -X "$chain" >/dev/null 2>&1 || return 1
        done
    done
    zapret2_namespace_present "$tool"
    case $? in 1) return 0 ;; *) return 1 ;; esac
}

owned_family_absent() {
    owned_family_present "$1"
    case $? in 1) return 0;; *) return 1;; esac
}

# Legacy direct-rule migration is a separate transaction from the current
# owned-chain lifecycle.  Callers install their signal traps before invoking
# it; these globals let those traps reconstruct the exact proven fingerprint
# if phase-two deletion is interrupted.
LEGACY_ROLLBACK_ARMED=0
LEGACY_ROLLBACK_IN_PROGRESS=0
LEGACY_ROLLBACK_FAILED=0
LEGACY_SNAPSHOT_ARTIFACTS_OWNED=0
LEGACY_MARKER_PUBLISH_ATTEMPTED=0
LEGACY_MIGRATION_VERIFIED=0

legacy_direct_rule_one() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    set -- -t mangle -C "$chain" -p "$proto"
    if [ "$LEGACY_MULTIPORT" = 1 ]; then
        if [ "$direction" = out ]; then set -- "$@" -m multiport --dports "$ports"
        else set -- "$@" -m multiport --sports "$ports"; fi
    else
        if [ "$direction" = out ]; then set -- "$@" --dport "$ports"
        else set -- "$@" --sport "$ports"; fi
    fi
    if [ "$LEGACY_CONNBYTES" = 1 ]; then
        set -- "$@" -m connbytes --connbytes "1:$packet_count" --connbytes-dir "$cb_dir" --connbytes-mode packets
    fi
    if [ "$direction" = out ] && [ "$LEGACY_MARK" = 1 ]; then
        set -- "$@" -m mark ! --mark "$DESYNC_MARK/$DESYNC_MARK"
    fi
    set -- "$@" -j NFQUEUE --queue-num "$LEGACY_QNUM"
    [ "$LEGACY_BYPASS" = 1 ] && set -- "$@" --queue-bypass
    if "$tool" "$@" >/dev/null 2>&1; then
        LEGACY_FOUND=$((LEGACY_FOUND + 1))
    fi
}

# Build the delete form separately while preserving the exact pre-change
# fingerprint; BusyBox sh intentionally gets only positional primitives here.
legacy_delete_rule_one() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    set -- -t mangle -D "$chain" -p "$proto"
    if [ "$LEGACY_MULTIPORT" = 1 ]; then
        if [ "$direction" = out ]; then set -- "$@" -m multiport --dports "$ports"
        else set -- "$@" -m multiport --sports "$ports"; fi
    else
        if [ "$direction" = out ]; then set -- "$@" --dport "$ports"
        else set -- "$@" --sport "$ports"; fi
    fi
    if [ "$LEGACY_CONNBYTES" = 1 ]; then set -- "$@" -m connbytes --connbytes "1:$packet_count" --connbytes-dir "$cb_dir" --connbytes-mode packets; fi
    if [ "$direction" = out ] && [ "$LEGACY_MARK" = 1 ]; then set -- "$@" -m mark ! --mark "$DESYNC_MARK/$DESYNC_MARK"; fi
    set -- "$@" -j NFQUEUE --queue-num "$LEGACY_QNUM"
    [ "$LEGACY_BYPASS" = 1 ] && set -- "$@" --queue-bypass
    "$tool" "$@" >/dev/null 2>&1
}

legacy_insert_rule_one() {
    local tool="$1" chain="$2" position="$3" proto="$4" direction="$5" ports="$6" packet_count="$7" cb_dir="$8"
    set -- -t mangle -I "$chain" "$position" -p "$proto"
    if [ "$LEGACY_MULTIPORT" = 1 ]; then
        if [ "$direction" = out ]; then set -- "$@" -m multiport --dports "$ports"
        else set -- "$@" -m multiport --sports "$ports"; fi
    else
        if [ "$direction" = out ]; then set -- "$@" --dport "$ports"
        else set -- "$@" --sport "$ports"; fi
    fi
    if [ "$LEGACY_CONNBYTES" = 1 ]; then set -- "$@" -m connbytes --connbytes "1:$packet_count" --connbytes-dir "$cb_dir" --connbytes-mode packets; fi
    if [ "$direction" = out ] && [ "$LEGACY_MARK" = 1 ]; then set -- "$@" -m mark ! --mark "$DESYNC_MARK/$DESYNC_MARK"; fi
    set -- "$@" -j NFQUEUE --queue-num "$LEGACY_QNUM"
    [ "$LEGACY_BYPASS" = 1 ] && set -- "$@" --queue-bypass
    "$tool" "$@" >/dev/null 2>&1
}

legacy_visit_port_set() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    local old_ifs item
    if [ "$LEGACY_MULTIPORT" = 1 ]; then
        legacy_visit_rule "$tool" "$chain" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir"
        return
    fi
    old_ifs="$IFS"; IFS=,; set -- $ports; IFS="$old_ifs"
    for item in "$@"; do legacy_visit_rule "$tool" "$chain" "$proto" "$direction" "$item" "$packet_count" "$cb_dir"; done
}

legacy_visit_rule() {
    local before
    before="$LEGACY_FOUND"
    legacy_direct_rule_one "$@"
    if [ "$LEGACY_ACTION" = delete ] && [ "$LEGACY_FOUND" -gt "$before" ]; then
        legacy_delete_rule_one "$@" || LEGACY_DELETE_FAILED=1
    elif [ "$LEGACY_ACTION" = snapshot ]; then
        if [ "$LEGACY_FOUND" -gt "$before" ]; then
            legacy_snapshot_rule_one "$@" || LEGACY_SNAPSHOT_FAILED=1
        else
            LEGACY_SNAPSHOT_FAILED=1
        fi
    fi
}

legacy_visit_family() {
    local tool="$1" stun_ports="3478,5349,19302"
    [ "$LEGACY_MULTIPORT" = 1 ] || stun_ports=3478
    legacy_visit_port_set "$tool" OUTPUT tcp out "$PORTS_TCP" "$PKT_OUT" original
    legacy_visit_port_set "$tool" OUTPUT udp out "$PORTS_UDP" "$PKT_OUT" original
    legacy_visit_port_set "$tool" OUTPUT udp out "$stun_ports" "$PKT_OUT" original
    legacy_visit_port_set "$tool" INPUT tcp in "$PORTS_TCP" "$PKT_IN" reply
    legacy_visit_port_set "$tool" INPUT udp in "$PORTS_UDP" "$PKT_IN" reply
    legacy_visit_port_set "$tool" INPUT udp in "$stun_ports" "$PKT_IN" reply
}

legacy_expected_family_count() {
    local tcp_count udp_count
    if [ "$LEGACY_MULTIPORT" = 1 ]; then printf '6\n'; return; fi
    tcp_count="$(printf '%s\n' "$PORTS_TCP" | tr ',' '\n' | wc -l | tr -d '[:space:]')"
    udp_count="$(printf '%s\n' "$PORTS_UDP" | tr ',' '\n' | wc -l | tr -d '[:space:]')"
    is_decimal "$tcp_count" || tcp_count=0
    is_decimal "$udp_count" || udp_count=0
    printf '%s\n' $((2 * tcp_count + 2 * udp_count + 2))
}

legacy_direct_qnum_count() {
    local tool="$1" count listing
    command -v "$tool" >/dev/null 2>&1 || return 1
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    count="$(printf '%s\n' "$listing" | awk -v qnum="$LEGACY_QNUM" '
        $1 == "-A" && ($2 == "OUTPUT" || $2 == "INPUT") {
            nfqueue = 0
            queue = 0
            for (i = 3; i <= NF; i++) {
                if (($i == "-j" || $i == "--jump") && $(i + 1) == "NFQUEUE") nfqueue = 1
                if ($i == "--queue-num" && $(i + 1) == qnum) queue = 1
            }
            if (nfqueue && queue) count++
        }
        END { print count + 0 }
    ')" || return 1
    is_decimal "$count" || return 1
    printf '%s\n' "$count"
}

legacy_prove_family() {
    local tool="$1" active="$2" expected direct
    command -v "$tool" >/dev/null 2>&1 || {
        LEGACY_MIGRATION_ERROR="cannot prove active/inactive legacy family because $tool is unavailable"
        return 1
    }
    LEGACY_ACTION=count; LEGACY_FOUND=0
    legacy_visit_family "$tool"
    direct="$(legacy_direct_qnum_count "$tool")" || {
        LEGACY_MIGRATION_ERROR="cannot count direct legacy queue references with $tool"
        return 1
    }
    expected=0
    [ "$active" = 0 ] || expected=6
    [ "$LEGACY_FOUND" = "$expected" ] && [ "$direct" = "$expected" ] || {
        LEGACY_MIGRATION_ERROR="$tool legacy family proof mismatch: active=$active, matched fingerprints=$LEGACY_FOUND, direct queue references=$direct, expected=$expected; no direct rules were changed"
        return 1
    }
    if [ "$tool" = iptables ]; then
        LEGACY_IPV4_FOUND="$LEGACY_FOUND"; LEGACY_IPV4_DIRECT="$direct"
    else
        LEGACY_IPV6_FOUND="$LEGACY_FOUND"; LEGACY_IPV6_DIRECT="$direct"
    fi
    return 0
}

legacy_snapshot_paths_init() {
    local prefix="$STATE_DIR/legacy-rollback.$$"
    LEGACY_SNAPSHOT_RULES_FILE="$prefix.rules"
    LEGACY_SNAPSHOT_SORTED_FILE="$prefix.sorted"
    LEGACY_SNAPSHOT_CURRENT_FILE="$prefix.current"
    LEGACY_SNAPSHOT_V4_OUTPUT_FILE="$prefix.v4.output"
    LEGACY_SNAPSHOT_V4_INPUT_FILE="$prefix.v4.input"
    LEGACY_SNAPSHOT_V6_OUTPUT_FILE="$prefix.v6.output"
    LEGACY_SNAPSHOT_V6_INPUT_FILE="$prefix.v6.input"
}

legacy_snapshot_file_for() {
    case "$1:$2" in
        iptables:OUTPUT) LEGACY_CHAIN_SNAPSHOT_FILE="$LEGACY_SNAPSHOT_V4_OUTPUT_FILE" ;;
        iptables:INPUT) LEGACY_CHAIN_SNAPSHOT_FILE="$LEGACY_SNAPSHOT_V4_INPUT_FILE" ;;
        ip6tables:OUTPUT) LEGACY_CHAIN_SNAPSHOT_FILE="$LEGACY_SNAPSHOT_V6_OUTPUT_FILE" ;;
        ip6tables:INPUT) LEGACY_CHAIN_SNAPSHOT_FILE="$LEGACY_SNAPSHOT_V6_INPUT_FILE" ;;
        *) return 1 ;;
    esac
}

legacy_cleanup_snapshot_artifacts() {
    local path rc=0
    [ "$LEGACY_SNAPSHOT_ARTIFACTS_OWNED" = 1 ] || return 0
    for path in \
        "$LEGACY_SNAPSHOT_RULES_FILE" "$LEGACY_SNAPSHOT_SORTED_FILE" \
        "$LEGACY_SNAPSHOT_CURRENT_FILE" "$LEGACY_SNAPSHOT_V4_OUTPUT_FILE" \
        "$LEGACY_SNAPSHOT_V4_INPUT_FILE" "$LEGACY_SNAPSHOT_V6_OUTPUT_FILE" \
        "$LEGACY_SNAPSHOT_V6_INPUT_FILE"; do
        state_path_is_managed_file "$path" || { rc=1; continue; }
        if [ -e "$path" ] || [ -L "$path" ]; then rm -f "$path" 2>/dev/null || rc=1; fi
        [ ! -e "$path" ] && [ ! -L "$path" ] || rc=1
    done
    [ "$rc" -ne 0 ] || LEGACY_SNAPSHOT_ARTIFACTS_OWNED=0
    return "$rc"
}

legacy_prepare_snapshot_artifacts() {
    local path tool chain active
    ensure_state_dir || return 1
    legacy_snapshot_paths_init
    for path in \
        "$LEGACY_SNAPSHOT_RULES_FILE" "$LEGACY_SNAPSHOT_SORTED_FILE" \
        "$LEGACY_SNAPSHOT_CURRENT_FILE" "$LEGACY_SNAPSHOT_V4_OUTPUT_FILE" \
        "$LEGACY_SNAPSHOT_V4_INPUT_FILE" "$LEGACY_SNAPSHOT_V6_OUTPUT_FILE" \
        "$LEGACY_SNAPSHOT_V6_INPUT_FILE"; do
        state_path_is_managed_file "$path" || return 1
        [ ! -e "$path" ] && [ ! -L "$path" ] || return 1
    done
    LEGACY_SNAPSHOT_ARTIFACTS_OWNED=1
    umask 077
    : > "$LEGACY_SNAPSHOT_RULES_FILE" || return 1
    chmod 0600 "$LEGACY_SNAPSHOT_RULES_FILE" 2>/dev/null || return 1
    for tool in iptables ip6tables; do
        if [ "$tool" = iptables ]; then active="$LEGACY_SNAPSHOT_IPV4_ACTIVE"
        else active="$LEGACY_SNAPSHOT_IPV6_ACTIVE"; fi
        [ "$active" = 1 ] || continue
        command -v "$tool" >/dev/null 2>&1 || return 1
        for chain in OUTPUT INPUT; do
            legacy_snapshot_file_for "$tool" "$chain" || return 1
            "$tool" -t mangle -S "$chain" > "$LEGACY_CHAIN_SNAPSHOT_FILE" 2>/dev/null || return 1
            chmod 0600 "$LEGACY_CHAIN_SNAPSHOT_FILE" 2>/dev/null || return 1
        done
    done
    return 0
}

legacy_snapshot_rule_one() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5" packet_count="$6" cb_dir="$7"
    local port_option want_conn want_mark want_bypass position
    legacy_snapshot_file_for "$tool" "$chain" || return 1
    [ -f "$LEGACY_CHAIN_SNAPSHOT_FILE" ] && [ ! -L "$LEGACY_CHAIN_SNAPSHOT_FILE" ] || return 1
    if [ "$LEGACY_MULTIPORT" = 1 ]; then
        if [ "$direction" = out ]; then port_option=--dports
        else port_option=--sports; fi
    else
        if [ "$direction" = out ]; then port_option=--dport
        else port_option=--sport; fi
    fi
    want_conn="$LEGACY_CONNBYTES"
    want_mark=0
    [ "$direction" != out ] || [ "$LEGACY_MARK" != 1 ] || want_mark=1
    want_bypass="$LEGACY_BYPASS"
    position="$(awk -v chain="$chain" -v proto="$proto" \
        -v port_option="$port_option" -v ports="$ports" \
        -v packet_count="$packet_count" -v cb_dir="$cb_dir" \
        -v qnum="$LEGACY_QNUM" -v want_conn="$want_conn" \
        -v want_mark="$want_mark" -v want_bypass="$want_bypass" '
        $1 == "-A" && $2 == chain {
            ordinal++
            got_proto = got_port = got_jump = got_queue = 0
            got_conn = got_conn_dir = got_conn_mode = 0
            got_mark = got_bypass = 0
            for (i = 3; i <= NF; i++) {
                if (($i == "-p" || $i == "--protocol") && $(i + 1) == proto) got_proto = 1
                if ($i == port_option && $(i + 1) == ports) got_port = 1
                if ($i == "--connbytes" && $(i + 1) == "1:" packet_count) got_conn = 1
                if ($i == "--connbytes-dir" && $(i + 1) == cb_dir) got_conn_dir = 1
                if ($i == "--connbytes-mode" && $(i + 1) == "packets") got_conn_mode = 1
                if ($i == "--mark") got_mark = 1
                if (($i == "-j" || $i == "--jump") && $(i + 1) == "NFQUEUE") got_jump = 1
                if ($i == "--queue-num" && $(i + 1) == qnum) got_queue = 1
                if ($i == "--queue-bypass") got_bypass = 1
            }
            conn_ok = want_conn ? (got_conn && got_conn_dir && got_conn_mode) : (!got_conn && !got_conn_dir && !got_conn_mode)
            if (got_proto && got_port && got_jump && got_queue && conn_ok &&
                got_mark == want_mark && got_bypass == want_bypass) {
                matches++
                matched_position = ordinal
            }
        }
        END {
            if (matches == 1) print matched_position
            else exit 1
        }
    ' "$LEGACY_CHAIN_SNAPSHOT_FILE")" || return 1
    is_decimal "$position" && [ "$position" -ge 1 ] 2>/dev/null || return 1
    printf '%s|%s|%s|%s|%s|%s|%s|%s\n' \
        "$tool" "$chain" "$position" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" \
        >> "$LEGACY_SNAPSHOT_RULES_FILE" || return 1
    LEGACY_SNAPSHOT_RECORDS=$((LEGACY_SNAPSHOT_RECORDS + 1))
    return 0
}

legacy_snapshot_records_are_exact() {
    local expected="$1"
    awk -F '|' -v expected="$expected" '
        NF != 8 { bad = 1; exit }
        {
            key = $1 "|" $2 "|" $3
            if (seen[key]++) { bad = 1; exit }
            count++
        }
        END { if (bad || count != expected) exit 1 }
    ' "$LEGACY_SNAPSHOT_RULES_FILE"
}

legacy_snapshot_chains_equal() {
    local tool chain active rc=0
    for tool in iptables ip6tables; do
        if [ "$tool" = iptables ]; then active="$LEGACY_SNAPSHOT_IPV4_ACTIVE"
        else active="$LEGACY_SNAPSHOT_IPV6_ACTIVE"; fi
        [ "$active" = 1 ] || continue
        for chain in OUTPUT INPUT; do
            legacy_snapshot_file_for "$tool" "$chain" || return 1
            "$tool" -t mangle -S "$chain" > "$LEGACY_SNAPSHOT_CURRENT_FILE" 2>/dev/null || return 1
            cmp -s "$LEGACY_CHAIN_SNAPSHOT_FILE" "$LEGACY_SNAPSHOT_CURRENT_FILE" || rc=1
        done
    done
    rm -f "$LEGACY_SNAPSHOT_CURRENT_FILE" 2>/dev/null || rc=1
    return "$rc"
}

legacy_restore_snapshot_records() {
    local tool chain position proto direction ports packet_count cb_dir
    local count=0 rc=0
    command -v sort >/dev/null 2>&1 || return 1
    sort -t '|' -k1,1 -k2,2 -k3,3n "$LEGACY_SNAPSHOT_RULES_FILE" \
        > "$LEGACY_SNAPSHOT_SORTED_FILE" 2>/dev/null || return 1
    chmod 0600 "$LEGACY_SNAPSHOT_SORTED_FILE" 2>/dev/null || return 1
    while IFS='|' read -r tool chain position proto direction ports packet_count cb_dir; do
        case "$tool:$chain:$direction:$cb_dir" in
            iptables:OUTPUT:out:original|iptables:INPUT:in:reply|ip6tables:OUTPUT:out:original|ip6tables:INPUT:in:reply) ;;
            *) rc=1; continue ;;
        esac
        case "$proto" in tcp|udp) ;; *) rc=1; continue ;; esac
        case "$ports" in ""|*[!0-9,:]*) rc=1; continue ;; esac
        is_decimal "$position" && [ "$position" -ge 1 ] 2>/dev/null || { rc=1; continue; }
        is_decimal "$packet_count" && [ "$packet_count" -ge 1 ] 2>/dev/null || { rc=1; continue; }
        count=$((count + 1))
        LEGACY_FOUND=0
        legacy_direct_rule_one "$tool" "$chain" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir"
        if [ "$LEGACY_FOUND" = 0 ]; then
            legacy_insert_rule_one "$tool" "$chain" "$position" "$proto" "$direction" "$ports" "$packet_count" "$cb_dir" || rc=1
        elif [ "$LEGACY_FOUND" != 1 ]; then
            rc=1
        fi
    done < "$LEGACY_SNAPSHOT_SORTED_FILE"
    [ "$count" = "$LEGACY_SNAPSHOT_RECORDS" ] || rc=1
    [ "$rc" -ne 0 ] || legacy_snapshot_chains_equal || rc=1
    return "$rc"
}

read_legacy_migration_marker() {
    local key value version="" qnum="" seen_version=0 seen_qnum=0
    LEGACY_MARKER_QNUM=""
    state_file_is_secure "$LEGACY_MIGRATION_MARKER" &&
        [ -r "$LEGACY_MIGRATION_MARKER" ] || return 1
    while IFS='=' read -r key value; do
        case "$key" in
            version)
                [ "$seen_version" = 0 ] || return 1
                version="$value"; seen_version=1
                ;;
            qnum)
                [ "$seen_qnum" = 0 ] || return 1
                qnum="$value"; seen_qnum=1
                ;;
            *) return 1 ;;
        esac
    done < "$LEGACY_MIGRATION_MARKER"
    [ "$seen_version:$seen_qnum" = 1:1 ] && [ "$version" = 1 ] || return 1
    normalize_qnum "$qnum" || return 1
    LEGACY_MARKER_QNUM="$QNUM_NORMALIZED"
    return 0
}

legacy_arm_rollback() {
    local tool active expected
    [ "$LEGACY_ROLLBACK_ARMED" = 0 ] || return 1
    LEGACY_SNAPSHOT_QNUM="$LEGACY_QNUM"
    LEGACY_SNAPSHOT_CONNBYTES="$LEGACY_CONNBYTES"
    LEGACY_SNAPSHOT_MULTIPORT="$LEGACY_MULTIPORT"
    LEGACY_SNAPSHOT_MARK="$LEGACY_MARK"
    LEGACY_SNAPSHOT_BYPASS="$LEGACY_BYPASS"
    LEGACY_SNAPSHOT_IPV4_ACTIVE="$STATUS_FILE_IPV4_ACTIVE"
    LEGACY_SNAPSHOT_IPV6_ACTIVE="$STATUS_FILE_IPV6_ACTIVE"
    LEGACY_SNAPSHOT_PORTS_TCP="$PORTS_TCP"
    LEGACY_SNAPSHOT_PORTS_UDP="$PORTS_UDP"
    LEGACY_SNAPSHOT_PKT_OUT="$PKT_OUT"
    LEGACY_SNAPSHOT_PKT_IN="$PKT_IN"
    LEGACY_SNAPSHOT_DESYNC_MARK="$DESYNC_MARK"
    LEGACY_CONTROLLED_TEARDOWN_PREVIOUS="${CONTROLLED_TEARDOWN_STARTED:-0}"
    case "$LEGACY_CONTROLLED_TEARDOWN_PREVIOUS" in 0|1) ;; *) return 1 ;; esac
    LEGACY_SNAPSHOT_RECORDS=0
    LEGACY_SNAPSHOT_FAILED=0
    LEGACY_ROLLBACK_FAILED=0
    if ! legacy_prepare_snapshot_artifacts; then
        legacy_cleanup_snapshot_artifacts >/dev/null 2>&1 || true
        return 1
    fi
    LEGACY_ACTION=snapshot
    LEGACY_FOUND=0
    for tool in iptables ip6tables; do
        if [ "$tool" = iptables ]; then active="$LEGACY_SNAPSHOT_IPV4_ACTIVE"
        else active="$LEGACY_SNAPSHOT_IPV6_ACTIVE"; fi
        [ "$active" = 1 ] || continue
        legacy_visit_family "$tool"
    done
    expected=$((6 * (LEGACY_SNAPSHOT_IPV4_ACTIVE + LEGACY_SNAPSHOT_IPV6_ACTIVE)))
    if [ "$LEGACY_SNAPSHOT_FAILED" != 0 ] ||
       [ "$LEGACY_SNAPSHOT_RECORDS" != "$expected" ] ||
       ! legacy_snapshot_records_are_exact "$expected" ||
       ! legacy_snapshot_chains_equal; then
        legacy_cleanup_snapshot_artifacts >/dev/null 2>&1 || true
        return 1
    fi
    LEGACY_MARKER_PUBLISH_ATTEMPTED=0
    # Publish the journal arm and fail-closed teardown guard as one shell
    # assignment command so a pending signal cannot observe a half-armed state.
    LEGACY_ROLLBACK_ARMED=1 CONTROLLED_TEARDOWN_STARTED=1
    return 0
}

legacy_restore_snapshot_rules() {
    local save_qnum="$LEGACY_QNUM" save_connbytes="$LEGACY_CONNBYTES"
    local save_multiport="$LEGACY_MULTIPORT" save_mark="$LEGACY_MARK"
    local save_bypass="$LEGACY_BYPASS" save_ipv4="$STATUS_FILE_IPV4_ACTIVE"
    local save_ipv6="$STATUS_FILE_IPV6_ACTIVE" save_tcp="$PORTS_TCP"
    local save_udp="$PORTS_UDP" save_out="$PKT_OUT" save_in="$PKT_IN"
    local save_desync="$DESYNC_MARK" rc

    LEGACY_QNUM="$LEGACY_SNAPSHOT_QNUM"
    LEGACY_CONNBYTES="$LEGACY_SNAPSHOT_CONNBYTES"
    LEGACY_MULTIPORT="$LEGACY_SNAPSHOT_MULTIPORT"
    LEGACY_MARK="$LEGACY_SNAPSHOT_MARK"
    LEGACY_BYPASS="$LEGACY_SNAPSHOT_BYPASS"
    STATUS_FILE_IPV4_ACTIVE="$LEGACY_SNAPSHOT_IPV4_ACTIVE"
    STATUS_FILE_IPV6_ACTIVE="$LEGACY_SNAPSHOT_IPV6_ACTIVE"
    PORTS_TCP="$LEGACY_SNAPSHOT_PORTS_TCP"
    PORTS_UDP="$LEGACY_SNAPSHOT_PORTS_UDP"
    PKT_OUT="$LEGACY_SNAPSHOT_PKT_OUT"
    PKT_IN="$LEGACY_SNAPSHOT_PKT_IN"
    DESYNC_MARK="$LEGACY_SNAPSHOT_DESYNC_MARK"
    legacy_restore_snapshot_records
    rc=$?

    LEGACY_QNUM="$save_qnum"
    LEGACY_CONNBYTES="$save_connbytes"
    LEGACY_MULTIPORT="$save_multiport"
    LEGACY_MARK="$save_mark"
    LEGACY_BYPASS="$save_bypass"
    STATUS_FILE_IPV4_ACTIVE="$save_ipv4"
    STATUS_FILE_IPV6_ACTIVE="$save_ipv6"
    PORTS_TCP="$save_tcp"
    PORTS_UDP="$save_udp"
    PKT_OUT="$save_out"
    PKT_IN="$save_in"
    DESYNC_MARK="$save_desync"
    return "$rc"
}

legacy_remove_transaction_marker() {
    local rc=0 tmp="$LEGACY_MIGRATION_MARKER.tmp.$$"
    [ "$LEGACY_MARKER_PUBLISH_ATTEMPTED" = 1 ] || return 0
    if [ -e "$LEGACY_MIGRATION_MARKER" ] || [ -L "$LEGACY_MIGRATION_MARKER" ]; then
        if read_legacy_migration_marker &&
           [ "$LEGACY_MARKER_QNUM" = "$LEGACY_SNAPSHOT_QNUM" ]; then
            rm -f "$LEGACY_MIGRATION_MARKER" 2>/dev/null || rc=1
        else
            rc=1
        fi
    fi
    if [ -e "$tmp" ] || [ -L "$tmp" ]; then
        rm -f "$tmp" 2>/dev/null || rc=1
    fi
    return "$rc"
}

rollback_legacy_migration() {
    local rc=0
    if [ "$LEGACY_ROLLBACK_ARMED" != 1 ]; then
        [ "$LEGACY_ROLLBACK_FAILED" = 0 ] || return 1
        legacy_cleanup_snapshot_artifacts
        return $?
    fi
    [ "$LEGACY_ROLLBACK_IN_PROGRESS" = 0 ] || return 1
    LEGACY_ROLLBACK_IN_PROGRESS=1
    legacy_restore_snapshot_rules || rc=1
    legacy_remove_transaction_marker || rc=1
    legacy_cleanup_snapshot_artifacts || rc=1
    LEGACY_ROLLBACK_ARMED=0
    if [ "$rc" -eq 0 ]; then
        LEGACY_ROLLBACK_FAILED=0
        LEGACY_MARKER_PUBLISH_ATTEMPTED=0
        CONTROLLED_TEARDOWN_STARTED="$LEGACY_CONTROLLED_TEARDOWN_PREVIOUS"
    else
        LEGACY_ROLLBACK_FAILED=1
    fi
    LEGACY_ROLLBACK_IN_PROGRESS=0
    return "$rc"
}

commit_legacy_migration() {
    legacy_cleanup_snapshot_artifacts || return 1
    LEGACY_ROLLBACK_ARMED=0 LEGACY_ROLLBACK_FAILED=0 \
        LEGACY_MARKER_PUBLISH_ATTEMPTED=0 \
        CONTROLLED_TEARDOWN_STARTED="$LEGACY_CONTROLLED_TEARDOWN_PREVIOUS"
    return 0
}

write_legacy_migration_marker() {
    local tmp="$LEGACY_MIGRATION_MARKER.tmp.$$"
    ensure_state_dir || return 1
    state_file_target_is_safe "$LEGACY_MIGRATION_MARKER" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    printf 'version=1\nqnum=%s\n' "$LEGACY_QNUM" > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$LEGACY_MIGRATION_MARKER" || { rm -f "$tmp"; return 1; }
}

legacy_migrate_firewall() {
    local proof=0 tool expected_family expected_total active direct
    LEGACY_MIGRATION_ERROR=""
    LEGACY_MIGRATION_VERIFIED=0
    if [ -e "$LEGACY_MIGRATION_MARKER" ] || [ -L "$LEGACY_MIGRATION_MARKER" ]; then
        read_legacy_migration_marker || {
            LEGACY_MIGRATION_ERROR="legacy migration marker is unsafe or corrupt: $LEGACY_MIGRATION_MARKER"
            return 1
        }
        LEGACY_QNUM="$LEGACY_MARKER_QNUM"
        for tool in iptables ip6tables; do
            command -v "$tool" >/dev/null 2>&1 || continue
            direct="$(legacy_direct_qnum_count "$tool")" || {
                LEGACY_MIGRATION_ERROR="cannot audit $tool against the verified legacy migration marker"
                return 1
            }
            [ "$direct" = 0 ] || {
                LEGACY_MIGRATION_ERROR="$tool has $direct direct queue references that conflict with the verified legacy migration marker; daemon retained"
                return 1
            }
        done
        LEGACY_MIGRATION_VERIFIED=1
        return 0
    fi
    read_iptables_status "$LEGACY_IPTABLES_STATUS" >/dev/null 2>&1 || true
    case "$STATUS_FILE_STATUS" in ok|partial) proof=1 ;; esac
    case "$STATUS_FILE_RULES_TOTAL" in 6|12) ;; *) proof=0 ;; esac
    [ "$STATUS_FILE_NFQUEUE_SUPPORTED" = 1 ] || proof=0
    LEGACY_QNUM="$STATUS_FILE_QNUM"
    if normalize_qnum "$LEGACY_QNUM"; then LEGACY_QNUM="$QNUM_NORMALIZED"; else proof=0; LEGACY_QNUM=200; fi
    LEGACY_CONNBYTES="$STATUS_FILE_CONNBYTES_SUPPORTED"
    LEGACY_MULTIPORT="$STATUS_FILE_MULTIPORT_SUPPORTED"
    LEGACY_MARK="$STATUS_FILE_MARK_SUPPORTED"
    LEGACY_BYPASS="$STATUS_FILE_QUEUE_BYPASS_SUPPORTED"
    case "$LEGACY_CONNBYTES:$LEGACY_MULTIPORT:$LEGACY_MARK:$LEGACY_BYPASS" in
        [01]:[01]:[01]:[01]) ;;
        *) proof=0 ;;
    esac
    if [ "$proof" != 1 ]; then
        LEGACY_QNUM=200
        for tool in iptables ip6tables; do
            direct="$(legacy_direct_qnum_count "$tool")" || {
                LEGACY_MIGRATION_ERROR="cannot prove absence of direct legacy queue rules because $tool is unavailable or unreadable"
                return 1
            }
            [ "$direct" = 0 ] || {
                LEGACY_MIGRATION_ERROR="direct built-in NFQUEUE rules on historical queue 200 lack a provable zapret2 legacy status; leave them intact and remove only exact old zapret2 fingerprints manually, then rerun start"
                return 1
            }
        done
        write_legacy_migration_marker || { LEGACY_MIGRATION_ERROR="cannot write verified legacy migration marker"; return 1; }
        LEGACY_MIGRATION_VERIFIED=1
        return 0
    fi

    expected_family="$(legacy_expected_family_count)"
    [ "$expected_family" = 6 ] || {
        LEGACY_MIGRATION_ERROR="legacy status does not describe the historical six-rule family fingerprint; no direct rules were changed"
        return 1
    }
    case "$STATUS_FILE_IPV4_ACTIVE:$STATUS_FILE_IPV6_ACTIVE" in
        [01]:[01]) ;;
        *)
            LEGACY_MIGRATION_ERROR="legacy status has invalid per-family activity flags; no direct rules were changed"
            return 1
            ;;
    esac
    [ "$STATUS_FILE_IPV4_ACTIVE" = 1 ] || {
        LEGACY_MIGRATION_ERROR="legacy status does not prove the mandatory IPv4 family active; no direct rules were changed"
        return 1
    }
    expected_total=$((6 * (STATUS_FILE_IPV4_ACTIVE + STATUS_FILE_IPV6_ACTIVE)))
    [ "$STATUS_FILE_RULES_TOTAL" = "$expected_total" ] || {
        LEGACY_MIGRATION_ERROR="legacy status total is inconsistent with per-family activity; no direct rules were changed"
        return 1
    }

    # Phase one is deliberately read-only.  Each active family must contain
    # exactly six distinct expected fingerprints and exactly six total direct
    # references to the historical queue; each inactive family must contain
    # zero of both.  No family is changed until both proofs and the aggregate
    # status total agree.
    LEGACY_IPV4_FOUND=0; LEGACY_IPV4_DIRECT=0
    LEGACY_IPV6_FOUND=0; LEGACY_IPV6_DIRECT=0
    for tool in iptables ip6tables; do
        if [ "$tool" = iptables ]; then active="$STATUS_FILE_IPV4_ACTIVE"
        else active="$STATUS_FILE_IPV6_ACTIVE"; fi
        legacy_prove_family "$tool" "$active" || return 1
    done
    [ $((LEGACY_IPV4_FOUND + LEGACY_IPV6_FOUND)) = "$STATUS_FILE_RULES_TOTAL" ] &&
        [ $((LEGACY_IPV4_DIRECT + LEGACY_IPV6_DIRECT)) = "$STATUS_FILE_RULES_TOTAL" ] || {
        LEGACY_MIGRATION_ERROR="legacy per-family proof total is inconsistent; no direct rules were changed"
        return 1
    }

    # Phase two deletes only after every family passed phase one.  If any
    # exact delete or post-delete audit fails, reconstruct the complete proven
    # prestate so IPv4/IPv6 cannot be left in a mixed migration state.
    legacy_arm_rollback || {
        LEGACY_MIGRATION_ERROR="cannot arm exact legacy-rule rollback; no direct rules were changed"
        return 1
    }
    LEGACY_ACTION=delete; LEGACY_FOUND=0; LEGACY_DELETE_FAILED=0
    for tool in iptables ip6tables; do
        if [ "$tool" = iptables ]; then active="$STATUS_FILE_IPV4_ACTIVE"
        else active="$STATUS_FILE_IPV6_ACTIVE"; fi
        [ "$active" = 1 ] || continue
        legacy_visit_family "$tool"
    done
    [ "$LEGACY_DELETE_FAILED" = 0 ] || {
        trap '' HUP INT TERM
        if rollback_legacy_migration; then
            LEGACY_MIGRATION_ERROR="one or more exact legacy rules could not be deleted; the exact ordered prestate snapshot was restored"
        else
            LEGACY_MIGRATION_ERROR="one or more exact legacy rules could not be deleted and exact ordered restoration could not be proven; daemon must remain running and both families require inspection"
        fi
        return 1
    }
    if [ "$(legacy_direct_qnum_count iptables 2>/dev/null)" != 0 ] ||
       [ "$(legacy_direct_qnum_count ip6tables 2>/dev/null)" != 0 ]; then
        trap '' HUP INT TERM
        if rollback_legacy_migration; then
            LEGACY_MIGRATION_ERROR="legacy post-delete proof failed; the exact ordered prestate snapshot was restored"
        else
            LEGACY_MIGRATION_ERROR="legacy post-delete proof and exact ordered restoration could not be proven; daemon must remain running and both families require inspection"
        fi
        return 1
    fi
    LEGACY_MARKER_PUBLISH_ATTEMPTED=1
    if ! write_legacy_migration_marker; then
        trap '' HUP INT TERM
        if rollback_legacy_migration; then
            LEGACY_MIGRATION_ERROR="legacy marker publication failed; the exact ordered prestate snapshot was restored"
        else
            LEGACY_MIGRATION_ERROR="legacy marker publication and exact ordered restoration could not be proven; daemon must remain running and both families require inspection"
        fi
        return 1
    fi
    if ! commit_legacy_migration; then
        trap '' HUP INT TERM
        if rollback_legacy_migration; then
            LEGACY_MIGRATION_ERROR="legacy snapshot cleanup failed before commit; the exact ordered prestate was restored"
        else
            LEGACY_MIGRATION_ERROR="legacy snapshot cleanup failed and exact ordered restoration could not be proven; daemon must remain running"
        fi
        return 1
    fi
    LEGACY_MIGRATION_VERIFIED=1
    return 0
}

status_safe_value() { printf '%s' "$1" | tr '\r\n' '  '; }

LOG_READY="${LOG_READY:-0}"

prepare_lifecycle_log() {
    local log_size
    ensure_state_dir || return 1
    umask 077
    # Refuse symlinks and special files.  Removing a hostile path is not
    # necessary for logging and leaves less room for a replacement race.
    state_file_target_is_safe "$LOGFILE" || return 1
    state_file_target_is_safe "$LOGFILE_PREVIOUS" || return 1
    if [ -f "$LOGFILE" ]; then
        log_size="$(wc -c < "$LOGFILE" 2>/dev/null)" || return 1
        is_decimal "$log_size" || return 1
        if [ "$log_size" -ge "$LOG_MAX_BYTES" ] 2>/dev/null; then
            rm -f "$LOGFILE_PREVIOUS" 2>/dev/null || return 1
            mv -f "$LOGFILE" "$LOGFILE_PREVIOUS" 2>/dev/null || return 1
            chmod 0600 "$LOGFILE_PREVIOUS" 2>/dev/null || return 1
        fi
    fi
    : >> "$LOGFILE" || return 1
    chmod 0600 "$LOGFILE" 2>/dev/null || return 1
    state_file_is_secure "$LOGFILE" || return 1
    LOG_READY=1
    return 0
}

append_lifecycle_log() {
    [ "$LOG_READY" = 1 ] || return 0
    printf '%s\n' "$1" >> "$LOGFILE" 2>/dev/null
}

STATUS_FILE_STATUS=""
STATUS_FILE_QNUM=""
STATUS_FILE_RULES_TOTAL=0
STATUS_FILE_NFQUEUE_SUPPORTED=0
STATUS_FILE_QUEUE_BYPASS_SUPPORTED=0
STATUS_FILE_CONNBYTES_SUPPORTED=0
STATUS_FILE_MULTIPORT_SUPPORTED=0
STATUS_FILE_MARK_SUPPORTED=0
STATUS_FILE_IPV4_ACTIVE=0
STATUS_FILE_IPV6_ACTIVE=0
STATUS_FILE_IPV4_RULES=0
STATUS_FILE_IPV6_RULES=0
STATUS_FILE_CHAINS=0
STATUS_FILE_ANCHORS=0
STATUS_FILE_RULESET_VERIFIED=0
STATUS_FILE_OWNER_METADATA_VERIFIED=0
STATUS_FILE_RULES_EXPECTED=0
STATUS_FILE_OWN_PID=""
STATUS_FILE_OWN_PID_STARTTIME=""
STATUS_FILE_OWNER_GENERATION=""
STATUS_FILE_DIAGNOSTICS=""
STATUS_FILE_ERROR_SCHEMA=0
STATUS_FILE_ERROR_STATUS=OK
STATUS_FILE_ERROR_DOMAIN=NONE
STATUS_FILE_ERROR_CODE=NONE
STATUS_FILE_ERROR_STAGE=NONE
STATUS_FILE_ERROR_DETAIL=""

read_iptables_status() {
    local path="${1:-$IPTABLES_STATUS}"
    STATUS_FILE_STATUS=""; STATUS_FILE_QNUM=""; STATUS_FILE_RULES_TOTAL=0
    STATUS_FILE_NFQUEUE_SUPPORTED=0; STATUS_FILE_QUEUE_BYPASS_SUPPORTED=0
    STATUS_FILE_CONNBYTES_SUPPORTED=0; STATUS_FILE_MULTIPORT_SUPPORTED=0
    STATUS_FILE_MARK_SUPPORTED=0; STATUS_FILE_IPV4_ACTIVE=0; STATUS_FILE_IPV6_ACTIVE=0
    STATUS_FILE_IPV4_RULES=0; STATUS_FILE_IPV6_RULES=0
    STATUS_FILE_CHAINS=0; STATUS_FILE_ANCHORS=0; STATUS_FILE_RULESET_VERIFIED=0
    STATUS_FILE_OWNER_METADATA_VERIFIED=0; STATUS_FILE_RULES_EXPECTED=0; STATUS_FILE_DIAGNOSTICS=""
    STATUS_FILE_OWN_PID=""; STATUS_FILE_OWN_PID_STARTTIME=""; STATUS_FILE_OWNER_GENERATION=""
    STATUS_FILE_ERROR_SCHEMA=0; STATUS_FILE_ERROR_STATUS=OK
    STATUS_FILE_ERROR_DOMAIN=NONE; STATUS_FILE_ERROR_CODE=NONE
    STATUS_FILE_ERROR_STAGE=NONE; STATUS_FILE_ERROR_DETAIL=""
    if [ "$path" = "$IPTABLES_STATUS" ]; then
        state_file_is_secure "$path" && [ -r "$path" ] || return 1
    else
        [ "$path" = "$LEGACY_IPTABLES_STATUS" ] || return 1
        [ -f "$path" ] && [ ! -L "$path" ] && [ -r "$path" ] || return 1
        path_uid_is_root "$path" || return 1
    fi
    local key value
    while IFS='=' read -r key value; do
        case "$key" in
            status) STATUS_FILE_STATUS="$value" ;;
            qnum) STATUS_FILE_QNUM="$value" ;;
            rules_total|total) STATUS_FILE_RULES_TOTAL="$value" ;;
            nfqueue_supported) STATUS_FILE_NFQUEUE_SUPPORTED="$value" ;;
            queue_bypass_supported) STATUS_FILE_QUEUE_BYPASS_SUPPORTED="$value" ;;
            connbytes_supported) STATUS_FILE_CONNBYTES_SUPPORTED="$value" ;;
            multiport_supported) STATUS_FILE_MULTIPORT_SUPPORTED="$value" ;;
            mark_supported) STATUS_FILE_MARK_SUPPORTED="$value" ;;
            ipv4_active) STATUS_FILE_IPV4_ACTIVE="$value" ;;
            ipv6_active) STATUS_FILE_IPV6_ACTIVE="$value" ;;
            ipv4_rules) STATUS_FILE_IPV4_RULES="$value" ;;
            ipv6_rules) STATUS_FILE_IPV6_RULES="$value" ;;
            chains) STATUS_FILE_CHAINS="$value" ;;
            anchors) STATUS_FILE_ANCHORS="$value" ;;
            ruleset_verified) STATUS_FILE_RULESET_VERIFIED="$value" ;;
            owner_metadata_verified) STATUS_FILE_OWNER_METADATA_VERIFIED="$value" ;;
            rules_expected) STATUS_FILE_RULES_EXPECTED="$value" ;;
            own_pid) STATUS_FILE_OWN_PID="$value" ;;
            own_pid_starttime) STATUS_FILE_OWN_PID_STARTTIME="$value" ;;
            owner_generation) STATUS_FILE_OWNER_GENERATION="$value" ;;
            diagnostics) STATUS_FILE_DIAGNOSTICS="$value" ;;
            error_schema) STATUS_FILE_ERROR_SCHEMA="$value" ;;
            error_status) STATUS_FILE_ERROR_STATUS="$value" ;;
            error_domain) STATUS_FILE_ERROR_DOMAIN="$value" ;;
            error_code) STATUS_FILE_ERROR_CODE="$value" ;;
            error_stage) STATUS_FILE_ERROR_STAGE="$value" ;;
            error_detail) STATUS_FILE_ERROR_DETAIL="$value" ;;
        esac
    done < "$path"
    normalize_qnum "$STATUS_FILE_QNUM" && STATUS_FILE_QNUM="$QNUM_NORMALIZED" || STATUS_FILE_QNUM=""
    for value in "$STATUS_FILE_RULES_TOTAL" "$STATUS_FILE_IPV4_RULES" \
        "$STATUS_FILE_IPV6_RULES" "$STATUS_FILE_RULES_EXPECTED" \
        "$STATUS_FILE_CHAINS" "$STATUS_FILE_ANCHORS"; do
        is_decimal "$value" || return 1
    done
    for value in "$STATUS_FILE_NFQUEUE_SUPPORTED" "$STATUS_FILE_QUEUE_BYPASS_SUPPORTED" \
        "$STATUS_FILE_CONNBYTES_SUPPORTED" "$STATUS_FILE_MULTIPORT_SUPPORTED" \
        "$STATUS_FILE_MARK_SUPPORTED" "$STATUS_FILE_IPV4_ACTIVE" "$STATUS_FILE_IPV6_ACTIVE" \
        "$STATUS_FILE_RULESET_VERIFIED" "$STATUS_FILE_OWNER_METADATA_VERIFIED"; do
        case "$value" in 0|1) ;; *) return 1 ;; esac
    done
    if [ -n "$STATUS_FILE_OWN_PID" ] || [ -n "$STATUS_FILE_OWN_PID_STARTTIME" ] ||
       [ -n "$STATUS_FILE_OWNER_GENERATION" ]; then
        is_decimal "$STATUS_FILE_OWN_PID" &&
            is_decimal "$STATUS_FILE_OWN_PID_STARTTIME" &&
            is_safe_token "$STATUS_FILE_OWNER_GENERATION" || return 1
    fi
    if [ "$STATUS_FILE_ERROR_SCHEMA" = "$Z2_ERROR_SCHEMA_VERSION" ] &&
       z2_error_fields_are_valid "$STATUS_FILE_ERROR_STATUS" "$STATUS_FILE_ERROR_DOMAIN" \
           "$STATUS_FILE_ERROR_STAGE" "$STATUS_FILE_ERROR_CODE" "$STATUS_FILE_ERROR_DETAIL"; then
        :
    elif [ "$STATUS_FILE_ERROR_SCHEMA" = 0 ]; then
        STATUS_FILE_ERROR_STATUS=OK
        STATUS_FILE_ERROR_DOMAIN=NONE; STATUS_FILE_ERROR_CODE=NONE
        STATUS_FILE_ERROR_STAGE=NONE; STATUS_FILE_ERROR_DETAIL=""
    else
        return 1
    fi
    return 0
}

restore_status_facts() {
    read_iptables_status >/dev/null 2>&1 || true
    [ -n "${STATUS_QNUM:-}" ] || STATUS_QNUM="${QNUM:-$STATUS_FILE_QNUM}"
    [ -n "${STATUS_QNUM:-}" ] || { read_owner_state >/dev/null 2>&1 && STATUS_QNUM="$OWNER_STATE_QNUM"; }
    STATUS_NFQUEUE_SUPPORTED="${STATUS_NFQUEUE_SUPPORTED:-$STATUS_FILE_NFQUEUE_SUPPORTED}"
    STATUS_QUEUE_BYPASS_SUPPORTED="${STATUS_QUEUE_BYPASS_SUPPORTED:-$STATUS_FILE_QUEUE_BYPASS_SUPPORTED}"
    STATUS_CONNBYTES_SUPPORTED="${STATUS_CONNBYTES_SUPPORTED:-$STATUS_FILE_CONNBYTES_SUPPORTED}"
    STATUS_MULTIPORT_SUPPORTED="${STATUS_MULTIPORT_SUPPORTED:-$STATUS_FILE_MULTIPORT_SUPPORTED}"
    STATUS_MARK_SUPPORTED="${STATUS_MARK_SUPPORTED:-$STATUS_FILE_MARK_SUPPORTED}"
}

write_iptables_status() {
    local state="$1" tmp="$IPTABLES_STATUS.tmp.$$" errors diagnostics
    local error_status="${STATUS_ERROR_STATUS:-OK}"
    local error_domain="${STATUS_ERROR_DOMAIN:-NONE}" error_code="${STATUS_ERROR_CODE:-NONE}"
    local error_stage="${STATUS_ERROR_STAGE:-NONE}" error_detail
    errors="$(status_safe_value "${STATUS_ERRORS:-}")"
    diagnostics="$(status_safe_value "${STATUS_DIAGNOSTICS:-}")"
    error_detail="$(z2_error_detail_normalize "${STATUS_ERROR_DETAIL:-}")"
    z2_error_fields_are_valid "$error_status" "$error_domain" "$error_stage" "$error_code" \
        "$error_detail" ||
        return 1
    ensure_state_dir || return 1
    state_file_target_is_safe "$IPTABLES_STATUS" || return 1
    [ ! -e "$tmp" ] && [ ! -L "$tmp" ] || return 1
    umask 077
    {
        echo "status=$state"
        echo "timestamp=$(date '+%Y-%m-%d %H:%M:%S')"
        echo "rules_ok=${STATUS_RULES_OK:-0}"
        echo "rules_fail=${STATUS_RULES_FAIL:-0}"
        echo "rules_total=${STATUS_RULES_TOTAL:-0}"
        echo "ok=${STATUS_RULES_OK:-0}"
        echo "fail=${STATUS_RULES_FAIL:-0}"
        echo "total=${STATUS_RULES_TOTAL:-0}"
        echo "errors=$errors"
        echo "own_pid=${STATUS_OWN_PID:-}"
        echo "own_pid_starttime=${STATUS_OWN_PID_STARTTIME:-}"
        echo "owner_generation=${STATUS_OWNER_GENERATION:-}"
        echo "pid_verified=${STATUS_PID_VERIFIED:-0}"
        echo "owner_metadata_verified=${STATUS_OWNER_METADATA_VERIFIED:-0}"
        echo "ruleset_verified=${STATUS_RULESET_VERIFIED:-0}"
        echo "rules_expected=${STATUS_RULES_EXPECTED:-0}"
        echo "qnum=${STATUS_QNUM:-${QNUM:-}}"
        echo "ipv4_active=${STATUS_IPV4_ACTIVE:-0}"
        echo "ipv6_active=${STATUS_IPV6_ACTIVE:-0}"
        echo "ipv4_rules=${STATUS_IPV4_RULES:-0}"
        echo "ipv6_rules=${STATUS_IPV6_RULES:-0}"
        echo "chains=${STATUS_CHAINS:-0}"
        echo "anchors=${STATUS_ANCHORS:-0}"
        echo "nfqueue_supported=${STATUS_NFQUEUE_SUPPORTED:-0}"
        echo "queue_bypass_supported=${STATUS_QUEUE_BYPASS_SUPPORTED:-0}"
        echo "connbytes_supported=${STATUS_CONNBYTES_SUPPORTED:-0}"
        echo "multiport_supported=${STATUS_MULTIPORT_SUPPORTED:-0}"
        echo "mark_supported=${STATUS_MARK_SUPPORTED:-0}"
        echo "fallback_mode=${STATUS_FALLBACK_MODE:-0}"
        printf 'error_schema=%s\n' "$Z2_ERROR_SCHEMA_VERSION"
        printf 'error_status=%s\n' "$error_status"
        printf 'error_domain=%s\n' "$error_domain"
        printf 'error_code=%s\n' "$error_code"
        printf 'error_stage=%s\n' "$error_stage"
        printf 'error_detail=%s\n' "$error_detail"
        echo "diagnostics=$diagnostics"
    } > "$tmp" || { rm -f "$tmp"; return 1; }
    chmod 0600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
    mv -f "$tmp" "$IPTABLES_STATUS" || { rm -f "$tmp"; return 1; }
}
