#!/bin/sh
set -eu

# Rooted-device release smoke harness. The default operation is a read-only
# preflight. Device mutations are possible only after all opt-in gates below
# have been checked and a successful preflight has completed.

umask 077

PROGRAM=${0##*/}
ADB_BIN=${ADB_BIN:-adb}
SERIAL=""
EVIDENCE_DIR=""
EVIDENCE_EXPLICIT=0
STAGE=preflight
DRY_RUN=0
REQUIRE_IPV6=0
ALLOW_MUTATIONS=0
ALLOW_UPDATE=0
ALLOW_FULL_ROLLBACK=0
ALLOW_UNINSTALL=0
ACK_DISPOSABLE=""
ACK_RECOVERY=""
RECOVERY_ACK_TEXT=I_HAVE_A_TESTED_RECOVERY_PLAN
MODULE_ZIP=""
MODULE_SHA256=""
APK_FILE=""
APK_SHA256=""
MUTATION_ARMED=0
IPV6_READY=0
EVIDENCE_IPV6_CAPABILITY=""

MODULE_DIR=/data/adb/modules/zapret2
STATE_DIR=/data/adb/zapret2-state
STATUS_ENTRY=/system/bin/zapret2-status
START_ENTRY=/system/bin/zapret2-start
STOP_ENTRY=/system/bin/zapret2-stop
RESTART_ENTRY=/system/bin/zapret2-restart
ROLLBACK_ENTRY=/system/bin/zapret2-full-rollback
SHELL_STAGE_MODULE=""
SHELL_STAGE_APK=""
ROOT_STAGE_DIR=""
ROOT_STAGE_MODULE=""
ROOT_STAGE_APK=""

say() { printf '%s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }

usage() {
    cat <<EOF
Usage:
  $PROGRAM --serial SERIAL [--evidence-dir DIR] [artifact options]
  $PROGRAM --serial SERIAL --evidence-dir DIR --stage STAGE [mutation gates]

The default stage is read-only preflight. STAGE is one of:
  preflight, stop, start, restart, update, full-rollback,
  uninstall, uninstall-verify

Every mutating stage requires all of:
  --allow-mutations
  --ack-disposable-device SERIAL
  --ack-recovery $RECOVERY_ACK_TEXT

Additional one-stage gates:
  update:        --allow-update plus both exact artifact/hash pairs
  full-rollback: --allow-full-rollback
  uninstall:     --allow-uninstall (arms a manual root-manager uninstall only)

Artifact/hash pairs:
  --module-zip FILE --module-sha256 64_HEX_DIGITS
  --apk FILE        --apk-sha256    64_HEX_DIGITS

Other options:
  --dry-run       perform the complete read-only preflight and print the plan
  --require-ipv6 fail preflight unless ip6tables has every required extension
  -h, --help

Stages use one evidence directory and are enforced in this exact order:
  preflight -> stop -> start -> restart -> update -> full-rollback
            -> uninstall -> uninstall-verify

The uninstall stage never removes a module itself. It records the checkpoint;
the operator must remove Zapret2 in the root-manager UI, reboot, and then run
uninstall-verify. The harness never invokes an internal update guard.
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial) [ "$#" -ge 2 ] || fail "--serial needs a value"; SERIAL=$2; shift 2 ;;
        --evidence-dir) [ "$#" -ge 2 ] || fail "--evidence-dir needs a value"; EVIDENCE_DIR=$2; EVIDENCE_EXPLICIT=1; shift 2 ;;
        --stage) [ "$#" -ge 2 ] || fail "--stage needs a value"; STAGE=$2; shift 2 ;;
        --module-zip) [ "$#" -ge 2 ] || fail "--module-zip needs a value"; MODULE_ZIP=$2; shift 2 ;;
        --module-sha256) [ "$#" -ge 2 ] || fail "--module-sha256 needs a value"; MODULE_SHA256=$2; shift 2 ;;
        --apk) [ "$#" -ge 2 ] || fail "--apk needs a value"; APK_FILE=$2; shift 2 ;;
        --apk-sha256) [ "$#" -ge 2 ] || fail "--apk-sha256 needs a value"; APK_SHA256=$2; shift 2 ;;
        --allow-mutations) ALLOW_MUTATIONS=1; shift ;;
        --allow-update) ALLOW_UPDATE=1; shift ;;
        --allow-full-rollback) ALLOW_FULL_ROLLBACK=1; shift ;;
        --allow-uninstall) ALLOW_UNINSTALL=1; shift ;;
        --ack-disposable-device) [ "$#" -ge 2 ] || fail "--ack-disposable-device needs the exact serial"; ACK_DISPOSABLE=$2; shift 2 ;;
        --ack-recovery) [ "$#" -ge 2 ] || fail "--ack-recovery needs the exact acknowledgement"; ACK_RECOVERY=$2; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --require-ipv6) REQUIRE_IPV6=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

case "$STAGE" in
    preflight|stop|start|restart|update|full-rollback|uninstall|uninstall-verify) ;;
    *) fail "unknown stage: $STAGE" ;;
esac

[ -n "$SERIAL" ] || fail "an explicit --serial is required before any ADB access"
case "$SERIAL" in
    -*|*[!A-Za-z0-9._:-]*) fail "unsafe device serial: $SERIAL" ;;
esac

MUTATION_REQUESTED=0
case "$STAGE" in
    stop|start|restart|update|full-rollback|uninstall) MUTATION_REQUESTED=1 ;;
esac

# All consent checks intentionally happen before require_adb/adb devices. A
# typo or omitted acknowledgement therefore cannot even reach the device.
if [ "$MUTATION_REQUESTED" = 1 ]; then
    [ "$EVIDENCE_EXPLICIT" = 1 ] || fail "mutating stages require an explicit existing --evidence-dir"
    [ "$ALLOW_MUTATIONS" = 1 ] || fail "refusing mutation without --allow-mutations"
    [ "$ACK_DISPOSABLE" = "$SERIAL" ] || fail "--ack-disposable-device must equal the exact --serial"
    [ "$ACK_RECOVERY" = "$RECOVERY_ACK_TEXT" ] || fail "recovery acknowledgement must be exactly $RECOVERY_ACK_TEXT"
fi

case "$STAGE" in
    update)
        [ "$ALLOW_UPDATE" = 1 ] || fail "update requires --allow-update"
        ;;
    full-rollback)
        [ "$ALLOW_FULL_ROLLBACK" = 1 ] || fail "full-rollback requires --allow-full-rollback"
        ;;
    uninstall)
        [ "$ALLOW_UNINSTALL" = 1 ] || fail "uninstall requires --allow-uninstall"
        ;;
esac

normalize_hash() {
    nh_value=$(printf '%s' "$1" | tr 'A-F' 'a-f')
    [ "${#nh_value}" -eq 64 ] || fail "SHA-256 must contain exactly 64 hexadecimal digits"
    case "$nh_value" in *[!0-9a-f]*) fail "SHA-256 contains a non-hexadecimal character" ;; esac
    printf '%s\n' "$nh_value"
}

sha256_file() {
    sf_path=$1
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$sf_path" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$sf_path" | awk '{print tolower($1)}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$sf_path" | awk '{print tolower($NF)}'
    else
        fail "sha256sum, shasum, or openssl is required"
    fi
}

verify_artifact_pair() {
    va_kind=$1 va_path=$2 va_expected=$3
    [ -f "$va_path" ] && [ ! -L "$va_path" ] && [ -s "$va_path" ] || fail "$va_kind artifact must be a non-empty regular, non-symlink file: $va_path"
    va_actual=$(sha256_file "$va_path")
    [ "$va_actual" = "$va_expected" ] || fail "$va_kind SHA-256 mismatch: expected $va_expected, got $va_actual"
}

if [ -n "$MODULE_ZIP" ] || [ -n "$MODULE_SHA256" ] || [ -n "$APK_FILE" ] || [ -n "$APK_SHA256" ]; then
    [ -n "$MODULE_ZIP" ] && [ -n "$MODULE_SHA256" ] && [ -n "$APK_FILE" ] && [ -n "$APK_SHA256" ] ||
        fail "artifact verification requires both module and APK paths and both exact SHA-256 values"
    MODULE_SHA256=$(normalize_hash "$MODULE_SHA256")
    APK_SHA256=$(normalize_hash "$APK_SHA256")
    verify_artifact_pair module "$MODULE_ZIP" "$MODULE_SHA256"
    verify_artifact_pair APK "$APK_FILE" "$APK_SHA256"
elif [ "$STAGE" = update ]; then
    fail "update requires exact module and APK artifacts with SHA-256 values"
fi

require_adb() {
    case "$ADB_BIN" in
        */*|*\\*) [ -x "$ADB_BIN" ] || fail "ADB_BIN is not executable: $ADB_BIN" ;;
        *) command -v "$ADB_BIN" >/dev/null 2>&1 || fail "adb is not available" ;;
    esac
}

adb_host() { "$ADB_BIN" "$@"; }
adb_device() { "$ADB_BIN" -s "$SERIAL" "$@"; }
adb_root() {
    # adb shell concatenates argv on some client/server combinations. Stream
    # the fixed command over stdin so metacharacters remain inside the root
    # shell without creating a script or temporary file on the device.
    printf '%s\n' "$1" | adb_device shell su -c /system/bin/sh
}

authorize_device() {
    ad_listing=$(adb_host devices 2>&1) || fail "adb devices failed: $ad_listing"
    ad_state=$(printf '%s\n' "$ad_listing" | tr -d '\r' | awk -v s="$SERIAL" '$1 == s { print $2 }')
    [ "$ad_state" = device ] || fail "device $SERIAL is not uniquely present and authorized (state: ${ad_state:-missing})"
    ad_get_state=$(adb_device get-state 2>&1 | tr -d '\r') || fail "cannot query device state: $ad_get_state"
    [ "$ad_get_state" = device ] || fail "device $SERIAL is not ready: $ad_get_state"
    ad_reported=$(adb_device get-serialno 2>&1 | tr -d '\r') || fail "cannot confirm device serial: $ad_reported"
    [ "$ad_reported" = "$SERIAL" ] || fail "ADB selected $ad_reported instead of exact serial $SERIAL"
}

init_new_evidence() {
    if [ -z "$EVIDENCE_DIR" ]; then
        ine_stamp=$(date -u '+%Y%m%dT%H%M%SZ')
        EVIDENCE_DIR=$PWD/zapret2-device-evidence-$SERIAL-$ine_stamp
    fi
    [ ! -e "$EVIDENCE_DIR" ] && [ ! -L "$EVIDENCE_DIR" ] || fail "new preflight refuses to overwrite evidence path: $EVIDENCE_DIR"
    mkdir -p "$EVIDENCE_DIR" || fail "cannot create evidence directory: $EVIDENCE_DIR"
    chmod 0700 "$EVIDENCE_DIR" || fail "cannot secure evidence directory"
    EVIDENCE_DIR=$(CDPATH= cd "$EVIDENCE_DIR" && pwd -P) || fail "cannot resolve evidence directory"
    mkdir "$EVIDENCE_DIR/stages" || fail "cannot create evidence stages directory"
    chmod 0700 "$EVIDENCE_DIR/stages"
}

manifest_value() {
    mv_key=$1 mv_file=$2
    [ -f "$mv_file" ] && [ ! -L "$mv_file" ] || fail "unsafe or missing evidence manifest: $mv_file"
    mv_value=$(sed -n "s/^$mv_key=//p" "$mv_file")
    [ "$(grep -c "^$mv_key=" "$mv_file")" -eq 1 ] || fail "malformed evidence manifest key: $mv_key"
    printf '%s\n' "$mv_value"
}

validate_capability_manifest() {
    vcm_file=$1
    [ -f "$vcm_file" ] && [ ! -L "$vcm_file" ] || fail "unsafe or missing capability manifest: $vcm_file"
    vcm_expected='schema
ipv4
ipv6'
    vcm_actual=$(sed 's/=.*//' "$vcm_file")
    [ "$vcm_actual" = "$vcm_expected" ] ||
        fail "capability manifest is truncated, reordered, duplicated, or unknown: $vcm_file"
    [ "$(sed -n 's/^schema=//p' "$vcm_file")" = 1 ] || fail "unsupported capability manifest schema"
    [ "$(sed -n 's/^ipv4=//p' "$vcm_file")" = ready ] || fail "recorded IPv4 capability is not ready"
    vcm_ipv6=$(sed -n 's/^ipv6=//p' "$vcm_file")
    case "$vcm_ipv6" in ready|not_available) ;; *) fail "invalid recorded IPv6 capability: $vcm_ipv6";; esac
}

open_existing_evidence() {
    [ -d "$EVIDENCE_DIR" ] && [ ! -L "$EVIDENCE_DIR" ] || fail "evidence directory is missing or a symlink: $EVIDENCE_DIR"
    EVIDENCE_DIR=$(CDPATH= cd "$EVIDENCE_DIR" && pwd -P) || fail "cannot resolve evidence directory"
    [ -f "$EVIDENCE_DIR/preflight.complete" ] && [ ! -L "$EVIDENCE_DIR/preflight.complete" ] || fail "evidence has no completed preflight"
    oe_serial=$(manifest_value serial "$EVIDENCE_DIR/run.meta")
    [ "$oe_serial" = "$SERIAL" ] || fail "evidence belongs to serial $oe_serial, not $SERIAL"
    validate_capability_manifest "$EVIDENCE_DIR/capabilities.meta"
    EVIDENCE_IPV6_CAPABILITY=$(manifest_value ipv6 "$EVIDENCE_DIR/capabilities.meta")
}

sequence_state() { manifest_value stage "$EVIDENCE_DIR/sequence.state"; }

require_previous_stage() {
    rps_expected=$1
    rps_actual=$(sequence_state)
    [ "$rps_actual" = "$rps_expected" ] || fail "stage $STAGE requires sequence state $rps_expected; found $rps_actual"
}

advance_sequence() {
    as_stage=$1 as_tmp=$EVIDENCE_DIR/.sequence.state.$$
    [ ! -e "$as_tmp" ] && [ ! -L "$as_tmp" ] || fail "unsafe evidence sequence temporary path"
    printf 'stage=%s\n' "$as_stage" > "$as_tmp"
    chmod 0600 "$as_tmp"
    mv -f "$as_tmp" "$EVIDENCE_DIR/sequence.state"
}

record_initial_manifests() {
    rim_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    {
        printf 'schema=1\n'
        printf 'serial=%s\n' "$SERIAL"
        printf 'created_utc=%s\n' "$rim_utc"
        printf 'require_ipv6=%s\n' "$REQUIRE_IPV6"
    } > "$EVIDENCE_DIR/run.meta"
    {
        printf 'module_sha256=%s\n' "$MODULE_SHA256"
        printf 'apk_sha256=%s\n' "$APK_SHA256"
        printf 'module_name=%s\n' "${MODULE_ZIP##*/}"
        printf 'apk_name=%s\n' "${APK_FILE##*/}"
    } > "$EVIDENCE_DIR/artifacts.meta"
    chmod 0600 "$EVIDENCE_DIR/run.meta" "$EVIDENCE_DIR/artifacts.meta"
}

record_capability_manifest() {
    rcm_ipv6=not_available
    [ "$IPV6_READY" = 0 ] || rcm_ipv6=ready
    {
        printf 'schema=1\n'
        printf 'ipv4=ready\n'
        printf 'ipv6=%s\n' "$rcm_ipv6"
    } > "$EVIDENCE_DIR/capabilities.meta"
    chmod 0600 "$EVIDENCE_DIR/capabilities.meta"
    validate_capability_manifest "$EVIDENCE_DIR/capabilities.meta"
    EVIDENCE_IPV6_CAPABILITY=$rcm_ipv6
}

require_capability_consistency() {
    validate_capability_manifest "$EVIDENCE_DIR/capabilities.meta"
    rcc_recorded=$(manifest_value ipv6 "$EVIDENCE_DIR/capabilities.meta")
    [ "$rcc_recorded" = "$EVIDENCE_IPV6_CAPABILITY" ] ||
        fail "IPv6 capability evidence changed during stage execution"
    rcc_current=not_available
    [ "$IPV6_READY" = 0 ] || rcc_current=ready
    [ "$rcc_current" = "$rcc_recorded" ] ||
        fail "IPv6 capability drift: evidence=$rcc_recorded current=$rcc_current"
}

verify_recorded_artifacts() {
    vra_module=$(manifest_value module_sha256 "$EVIDENCE_DIR/artifacts.meta")
    vra_apk=$(manifest_value apk_sha256 "$EVIDENCE_DIR/artifacts.meta")
    [ -n "$vra_module" ] && [ -n "$vra_apk" ] || fail "initial preflight did not bind this evidence bundle to exact release artifacts"
    [ "$MODULE_SHA256" = "$vra_module" ] || fail "module hash differs from the preflight evidence bundle"
    [ "$APK_SHA256" = "$vra_apk" ] || fail "APK hash differs from the preflight evidence bundle"
}

remote_path_state() {
    rps_path=$1 rps_kind=$2
    case "$rps_kind" in d) rps_test=-d ;; f) rps_test=-f ;; *) fail "internal path-kind error" ;; esac
    adb_root "if [ -L $rps_path ]; then echo LINK; elif [ $rps_test $rps_path ]; then stat -c %a:%u:%g $rps_path 2>/dev/null || echo STAT_FAILED; else echo MISSING; fi" 2>&1 | tr -d '\r'
}

check_remote_path() {
    crp_report=$1 crp_path=$2 crp_kind=$3 crp_modes=$4
    crp_state=$(remote_path_state "$crp_path" "$crp_kind") || fail "cannot inspect $crp_path: $crp_state"
    printf 'path\t%s\t%s\n' "$crp_path" "$crp_state" >> "$crp_report"
    crp_mode=${crp_state%%:*}
    crp_rest=${crp_state#*:}
    crp_uid=${crp_rest%%:*}
    crp_gid=${crp_rest#*:}
    [ "$crp_uid" = 0 ] && [ "$crp_gid" = 0 ] || fail "path is not root-owned: $crp_path ($crp_state)"
    case ",$crp_modes," in *",$crp_mode,"*) ;; *) fail "unexpected mode for $crp_path: $crp_state (expected $crp_modes)" ;; esac
}

check_optional_marker() {
    com_report=$1 com_path=$2
    com_state=$(adb_root "if [ -L $com_path ]; then echo LINK; elif [ -e $com_path ]; then stat -c %a:%u:%g $com_path 2>/dev/null || echo STAT_FAILED; else echo ABSENT; fi" 2>&1 | tr -d '\r') ||
        fail "cannot inspect optional marker $com_path"
    printf 'marker\t%s\t%s\n' "$com_path" "$com_state" >> "$com_report"
    case "$com_state" in ABSENT|600:0:0) ;; *) fail "unsafe optional marker $com_path: $com_state" ;; esac
}

probe_help() {
    ph_tool=$1 ph_args=$2 ph_needle=$3 ph_file=$4
    set +e
    ph_output=$(adb_root "$ph_tool $ph_args -h 2>&1")
    ph_rc=$?
    set -e
    printf '%s\n' "$ph_output" >> "$ph_file"
    case "$ph_output" in *"$ph_needle"*) return 0 ;; esac
    printf 'probe failed: %s %s (rc=%s, missing %s)\n' "$ph_tool" "$ph_args" "$ph_rc" "$ph_needle" >> "$ph_file"
    return 1
}

probe_firewall_family() {
    pff_tool=$1 pff_dir=$2 pff_ready=1
    PFF_CONNBYTES=0
    mkdir "$pff_dir" || fail "cannot create capability evidence directory"
    set +e
    adb_root "command -v $pff_tool && $pff_tool --version" > "$pff_dir/version.txt" 2>&1
    pff_rc=$?
    set -e
    [ "$pff_rc" -eq 0 ] || pff_ready=0
    probe_help "$pff_tool" '-j NFQUEUE' 'NFQUEUE' "$pff_dir/nfqueue.txt" || pff_ready=0
    probe_help "$pff_tool" '-j NFQUEUE' '--queue-bypass' "$pff_dir/queue-bypass.txt" || pff_ready=0
    probe_help "$pff_tool" '-m connbytes' '--connbytes' "$pff_dir/connbytes.txt" && PFF_CONNBYTES=1 || :
    probe_help "$pff_tool" '-m multiport' '--dports' "$pff_dir/multiport.txt" || pff_ready=0
    probe_help "$pff_tool" '-m mark' '--mark' "$pff_dir/mark.txt" || pff_ready=0
    [ "$pff_ready" -eq 1 ]
}

validate_status_schema() {
    vss_file=$1
    vss_expected='Z2_STATUS
Z2_OWNED
Z2_PROCESS
Z2_ACTIVE
Z2_PID
Z2_PID_VERIFIED
Z2_PID_STARTTIME
Z2_OWNER_GENERATION
Z2_OWNER_METADATA_VERIFIED
Z2_QNUM
Z2_IPV4
Z2_IPV6
Z2_RULES
Z2_EXPECTED_RULES
Z2_IPV4_RULES
Z2_IPV6_RULES
Z2_RULESET_VERIFIED
Z2_NFQUEUE
Z2_QUEUE_BYPASS
Z2_UPDATE_BLOCKED
Z2_UNINSTALL_TOMBSTONE
Z2_COMPLETE'
    vss_actual=$(sed 's/=.*//' "$vss_file")
    [ "$vss_actual" = "$vss_expected" ] || fail "status machine schema is truncated, reordered, duplicated, or unknown: $vss_file"
    [ "$(sed -n 's/^Z2_COMPLETE=//p' "$vss_file")" = 1 ] || fail "status machine sentinel is incomplete"
    vss_status=$(sed -n 's/^Z2_STATUS=//p' "$vss_file")
    vss_rc=$(cat "$vss_file.rc")
    case "$vss_status:$vss_rc" in ok:0|stopped:1|degraded:2) ;; *) fail "status/exit mismatch: $vss_status/$vss_rc" ;; esac
    for vss_key in Z2_OWNED Z2_PROCESS Z2_ACTIVE Z2_PID_VERIFIED Z2_OWNER_METADATA_VERIFIED Z2_IPV4 Z2_IPV6 Z2_RULESET_VERIFIED Z2_NFQUEUE Z2_QUEUE_BYPASS Z2_UPDATE_BLOCKED Z2_UNINSTALL_TOMBSTONE; do
        vss_value=$(sed -n "s/^$vss_key=//p" "$vss_file")
        case "$vss_value" in 0|1) ;; *) fail "invalid boolean $vss_key=$vss_value" ;; esac
    done
}

capture_status() {
    cs_file=$1
    set +e
    cs_output=$(adb_root "$STATUS_ENTRY --machine" 2>&1)
    cs_rc=$?
    set -e
    printf '%s\n' "$cs_output" | tr -d '\r' > "$cs_file"
    printf '%s\n' "$cs_rc" > "$cs_file.rc"
    validate_status_schema "$cs_file"
}

status_value() { sed -n "s/^$2=//p" "$1"; }

require_running_status() {
    rrs_file=$1
    [ "$(status_value "$rrs_file" Z2_STATUS)" = ok ] || fail "service is not healthy after $STAGE"
    for rrs_key in Z2_PROCESS Z2_ACTIVE Z2_PID_VERIFIED Z2_OWNER_METADATA_VERIFIED Z2_RULESET_VERIFIED Z2_NFQUEUE Z2_QUEUE_BYPASS; do
        [ "$(status_value "$rrs_file" "$rrs_key")" = 1 ] || fail "$rrs_key is not verified after $STAGE"
    done
    [ "$(status_value "$rrs_file" Z2_UPDATE_BLOCKED)" = 0 ] || fail "update serialization remains blocked after $STAGE"
    [ "$(status_value "$rrs_file" Z2_UNINSTALL_TOMBSTONE)" = 0 ] || fail "uninstall tombstone is active after $STAGE"
}

require_stopped_status() {
    rss_file=$1
    [ "$(status_value "$rss_file" Z2_STATUS)" = stopped ] || fail "service did not reach exact stopped status"
    for rss_key in Z2_OWNED Z2_PROCESS Z2_ACTIVE Z2_RULES Z2_EXPECTED_RULES; do
        [ "$(status_value "$rss_file" "$rss_key")" = 0 ] || fail "$rss_key is not clean after stop"
    done
    [ "$(status_value "$rss_file" Z2_RULESET_VERIFIED)" = 1 ] || fail "stopped ruleset is not verified"
}

capture_query() {
    cq_file=$1; shift
    set +e
    "$@" > "$cq_file" 2>&1
    cq_rc=$?
    set -e
    printf '%s\n' "$cq_rc" > "$cq_file.rc"
}

capture_root_query() {
    crq_file=$1 crq_command=$2
    set +e
    adb_root "set +e
{ $crq_command; }
z2_query_rc=\$?
printf '\nZ2_QUERY_RC=%s\nZ2_QUERY_COMPLETE=1\n' \"\$z2_query_rc\"
exit 0" > "$crq_file" 2>&1
    crq_transport_rc=$?
    set -e
    printf '%s\n' "$crq_transport_rc" > "$crq_file.transport.rc"
    if [ "$crq_transport_rc" -ne 0 ]; then
        crq_diagnostic=$(sed -n '1,8p' "$crq_file" | tr '\r\n' '  ')
        fail "root query transport failed (rc=$crq_transport_rc): $crq_file: $crq_diagnostic"
    fi
    [ "$(tail -n 1 "$crq_file")" = Z2_QUERY_COMPLETE=1 ] || fail "root query output is truncated: $crq_file"
    [ "$(tail -n 2 "$crq_file" | sed -n '1p')" = Z2_QUERY_RC=0 ] || fail "root query failed or has an invalid footer: $crq_file"
}

validate_query_schema() {
    vqs_file=$1 vqs_expected=$2
    vqs_actual=$(sed -n '/^Z2_QUERY_RC=/q; s/=.*//p' "$vqs_file")
    [ "$vqs_actual" = "$vqs_expected" ] || fail "query schema is truncated, reordered, duplicated, or unknown: $vqs_file"
}

valid_boot_id() {
    vbi_value=$1
    [ "${#vbi_value}" -eq 36 ] || return 1
    case "$vbi_value" in ????????-????-????-????-????????????) ;; *) return 1;; esac
    vbi_hex=$(printf '%s\n' "$vbi_value" | tr -d '-')
    case "$vbi_hex" in *[!0-9a-f]*) return 1;; esac
}

validate_uninstall_reboot_checkpoint() {
    vurc_file=$1
    [ -f "$vurc_file" ] && [ ! -L "$vurc_file" ] || fail "unsafe or missing uninstall reboot checkpoint: $vurc_file"
    vurc_expected='schema
serial
run_created_utc
boot_before_file
boot_before_size
boot_before_sha256
pre_boot_id
complete'
    vurc_actual=$(sed 's/=.*//' "$vurc_file")
    [ "$vurc_actual" = "$vurc_expected" ] || fail "uninstall reboot checkpoint is truncated, reordered, duplicated, or unknown"
    [ "$(manifest_value schema "$vurc_file")" = 1 ] || fail "unsupported uninstall reboot checkpoint schema"
    [ "$(manifest_value serial "$vurc_file")" = "$SERIAL" ] || fail "uninstall reboot checkpoint belongs to a different serial"
    vurc_run=$(manifest_value run_created_utc "$vurc_file")
    [ "$vurc_run" = "$(manifest_value created_utc "$EVIDENCE_DIR/run.meta")" ] || fail "uninstall reboot checkpoint belongs to a different evidence run"
    vurc_name=$(manifest_value boot_before_file "$vurc_file")
    [ "$vurc_name" = boot-before.txt ] || fail "uninstall reboot checkpoint names an unexpected raw boot evidence file"
    vurc_raw=${vurc_file%/*}/$vurc_name
    [ -f "$vurc_raw" ] && [ ! -L "$vurc_raw" ] || fail "raw uninstall boot evidence is missing or unsafe"
    vurc_size=$(manifest_value boot_before_size "$vurc_file")
    case "$vurc_size" in ''|*[!0-9]*) fail "uninstall reboot checkpoint has a malformed raw evidence size";; esac
    [ "$vurc_size" -gt 0 ] 2>/dev/null || fail "raw uninstall boot evidence is empty"
    [ "$(wc -c < "$vurc_raw" | tr -d ' ')" = "$vurc_size" ] || fail "raw uninstall boot evidence size changed after checkpoint"
    vurc_hash=$(manifest_value boot_before_sha256 "$vurc_file")
    [ "${#vurc_hash}" -eq 64 ] || fail "uninstall reboot checkpoint has a malformed raw evidence hash"
    case "$vurc_hash" in *[!0-9a-f]*) fail "uninstall reboot checkpoint has a malformed raw evidence hash";; esac
    [ "$(sha256_file "$vurc_raw")" = "$vurc_hash" ] || fail "raw uninstall boot evidence hash changed after checkpoint"
    [ "$(tail -n 1 "$vurc_raw")" = Z2_QUERY_COMPLETE=1 ] || fail "raw uninstall boot evidence is truncated"
    [ "$(tail -n 2 "$vurc_raw" | sed -n '1p')" = Z2_QUERY_RC=0 ] || fail "raw uninstall boot evidence query did not succeed"
    validate_query_schema "$vurc_raw" 'Z2_UNINSTALL_BOOT_ID
Z2_UNINSTALL_BOOT_COMPLETE'
    vurc_raw_boot=$(sed -n 's/^Z2_UNINSTALL_BOOT_ID=//p' "$vurc_raw")
    valid_boot_id "$vurc_raw_boot" || fail "raw uninstall boot evidence has a malformed boot identity"
    grep -Fxq Z2_UNINSTALL_BOOT_COMPLETE=1 "$vurc_raw" || fail "raw uninstall boot evidence is incomplete"
    vurc_boot=$(manifest_value pre_boot_id "$vurc_file")
    valid_boot_id "$vurc_boot" || fail "uninstall reboot checkpoint has a malformed boot identity"
    [ "$vurc_boot" = "$vurc_raw_boot" ] || fail "uninstall reboot checkpoint boot identity is not bound to its raw evidence"
    [ "$(manifest_value complete "$vurc_file")" = 1 ] || fail "uninstall reboot checkpoint is incomplete"
}

capture_no_owned_process_audit() {
    cnopa_file=$1
    capture_root_query "$cnopa_file" "
owner_meta=absent
pidfile=absent
owner_pid=
pidfile_pid=
if [ -L $STATE_DIR/owner.meta ]; then exit 31
elif [ -e $STATE_DIR/owner.meta ]; then
    [ -f $STATE_DIR/owner.meta ] || exit 32
    owner_stat=\$(stat -c %h:%a:%u:%g $STATE_DIR/owner.meta 2>/dev/null) || exit 33
    [ \"\$owner_stat\" = 1:600:0:0 ] || exit 34
    owner_pid=\$(awk -F= '\$1 == \"pid\" { print \$2; count++ } END { if (count != 1) exit 1 }' $STATE_DIR/owner.meta) || exit 35
    case \"\$owner_pid\" in ''|*[!0-9]*) exit 36;; esac
    owner_meta=present
fi
if [ -L $STATE_DIR/nfqws2.pid ]; then exit 41
elif [ -e $STATE_DIR/nfqws2.pid ]; then
    [ -f $STATE_DIR/nfqws2.pid ] || exit 42
    pid_stat=\$(stat -c %h:%a:%u:%g $STATE_DIR/nfqws2.pid 2>/dev/null) || exit 43
    [ \"\$pid_stat\" = 1:600:0:0 ] || exit 44
    IFS= read -r pidfile_pid < $STATE_DIR/nfqws2.pid || exit 45
    case \"\$pidfile_pid\" in ''|*[!0-9]*) exit 46;; esac
    pidfile=present
fi
matches=0
for proc in /proc/[0-9]*; do
    [ -d \"\$proc\" ] || continue
    exe=\$(readlink \"\$proc/exe\" 2>/dev/null) || exe=
    cmdline=\$(tr '\000' ' ' < \"\$proc/cmdline\" 2>/dev/null) || cmdline=
    case \"\$exe\" in $MODULE_DIR/zapret2/bin/*/nfqws2*) matches=\$((matches + 1)); continue;; esac
    case \"\$cmdline\" in *$MODULE_DIR/zapret2/bin/*/nfqws2*) matches=\$((matches + 1));; esac
done
printf 'Z2_PROC_OWNER_META=%s\n' \"\$owner_meta\"
printf 'Z2_PROC_PIDFILE=%s\n' \"\$pidfile\"
printf 'Z2_PROC_MATCHES=%s\n' \"\$matches\"
printf 'Z2_PROC_AUDIT_COMPLETE=1\n'
[ \"\$owner_meta\" = absent ] && [ \"\$pidfile\" = absent ] && [ \"\$matches\" = 0 ]"
    validate_query_schema "$cnopa_file" 'Z2_PROC_OWNER_META
Z2_PROC_PIDFILE
Z2_PROC_MATCHES
Z2_PROC_AUDIT_COMPLETE'
    grep -Fxq Z2_PROC_OWNER_META=absent "$cnopa_file" || fail "owner metadata remains: $cnopa_file"
    grep -Fxq Z2_PROC_PIDFILE=absent "$cnopa_file" || fail "PID metadata remains: $cnopa_file"
    grep -Fxq Z2_PROC_MATCHES=0 "$cnopa_file" || fail "module-owned /proc executable/cmdline remains: $cnopa_file"
    grep -Fxq Z2_PROC_AUDIT_COMPLETE=1 "$cnopa_file" || fail "process audit sentinel is missing: $cnopa_file"
}

capture_clean_firewall_audit() {
    ccfa_dir=$1
    capture_root_query "$ccfa_dir/iptables-save.txt" 'command -v iptables-save >/dev/null 2>&1 && iptables-save'
    case "$EVIDENCE_IPV6_CAPABILITY" in
        ready)
            capture_root_query "$ccfa_dir/ip6tables-save.txt" 'command -v ip6tables-save >/dev/null 2>&1 && ip6tables-save'
            ;;
        not_available)
            capture_root_query "$ccfa_dir/ip6tables-save.txt" "printf 'Z2_IP6TABLES_SAVE=not_available\\n'"
            [ "$(sed -n '1p' "$ccfa_dir/ip6tables-save.txt")" = Z2_IP6TABLES_SAVE=not_available ] ||
                fail "canonical IPv6 non-availability evidence is missing"
            ;;
        *) fail "firewall audit has no exact recorded IPv6 capability" ;;
    esac
    for ccfa_file in "$ccfa_dir/iptables-save.txt"; do
        if grep -E '(^|[[:space:]:-])ZAPRET2_(OUT|IN|PROBE)($|[[:space:]])' "$ccfa_file" >/dev/null 2>&1; then
            fail "successful firewall dump still contains an owned chain, anchor, or rule: $ccfa_file"
        fi
    done
    if [ "$EVIDENCE_IPV6_CAPABILITY" = ready ] &&
       grep -E '(^|[[:space:]:-])ZAPRET2_(OUT|IN|PROBE)($|[[:space:]])' "$ccfa_dir/ip6tables-save.txt" >/dev/null 2>&1; then
        fail "successful IPv6 firewall dump still contains an owned chain, anchor, or rule"
    fi
}

capture_snapshot() {
    csp_dir=$1
    mkdir "$csp_dir" || fail "cannot create snapshot directory: $csp_dir"
    chmod 0700 "$csp_dir"
    capture_query "$csp_dir/adb-version.txt" adb_host version
    capture_query "$csp_dir/build.txt" adb_device shell getprop ro.build.fingerprint
    capture_query "$csp_dir/api.txt" adb_device shell getprop ro.build.version.sdk
    capture_query "$csp_dir/abi.txt" adb_device shell getprop ro.product.cpu.abi
    capture_root_query "$csp_dir/root-id.txt" 'id'
    capture_root_query "$csp_dir/kernel.txt" 'uname -a'
    capture_root_query "$csp_dir/selinux.txt" 'getenforce 2>/dev/null || echo unknown'
    capture_root_query "$csp_dir/magisk.txt" 'magisk -v; magisk -V'
    capture_root_query "$csp_dir/module-prop.txt" "cat $MODULE_DIR/module.prop"
    capture_root_query "$csp_dir/install-generation.meta" "cat $MODULE_DIR/zapret2/install-generation.meta"
    capture_root_query "$csp_dir/module-hashes.txt" "sha256sum $MODULE_DIR/module.prop $MODULE_DIR/zapret2/runtime.ini $MODULE_DIR/zapret2/strategy-catalogs/*.txt $MODULE_DIR/zapret2/install-generation.meta 2>/dev/null"
    capture_root_query "$csp_dir/state-listing.txt" "ls -lan $STATE_DIR 2>/dev/null"
    capture_root_query "$csp_dir/module-listing.txt" "ls -lan $MODULE_DIR $MODULE_DIR/zapret2 $MODULE_DIR/system/bin 2>/dev/null"
    capture_root_query "$csp_dir/processes.txt" "ps -A 2>/dev/null | grep nfqws2 || true"
    capture_root_query "$csp_dir/iptables-save.txt" 'command -v iptables-save >/dev/null 2>&1 && iptables-save'
    case "$IPV6_READY" in
        1)
            capture_root_query "$csp_dir/ip6tables-save.txt" 'command -v ip6tables-save >/dev/null 2>&1 && ip6tables-save'
            ;;
        0)
            capture_root_query "$csp_dir/ip6tables-save.txt" "printf 'Z2_IP6TABLES_SAVE=not_available\\n'"
            [ "$(sed -n '1p' "$csp_dir/ip6tables-save.txt")" = Z2_IP6TABLES_SAVE=not_available ] ||
                fail "snapshot lacks canonical IPv6 non-availability evidence"
            ;;
        *) fail "snapshot has no exact current IPv6 capability" ;;
    esac
    capture_root_query "$csp_dir/netfilter-proc.txt" 'for f in /proc/net/ip_tables_targets /proc/net/ip_tables_matches /proc/net/ip6_tables_targets /proc/net/ip6_tables_matches /proc/net/netfilter/nfnetlink_queue /proc/net/netfilter/nf_queue; do echo ===$f; cat $f 2>/dev/null || true; done'
    capture_root_query "$csp_dir/hosts.sha256.txt" 'sha256sum /system/etc/hosts 2>/dev/null || true'
    capture_root_query "$csp_dir/mounts.txt" 'mount 2>/dev/null | grep -E "zapret2|/system" || true'
    capture_status "$csp_dir/status.machine"
    chmod 0600 "$csp_dir"/* 2>/dev/null || true
}

preflight_device() {
    pd_dir=$1 pd_report=$pd_dir/preflight.tsv
    mkdir "$pd_dir" || fail "cannot create preflight evidence directory: $pd_dir"
    chmod 0700 "$pd_dir"
    : > "$pd_report"

    pd_api=$(adb_device shell getprop ro.build.version.sdk 2>&1 | tr -d '\r') || fail "cannot read Android API: $pd_api"
    case "$pd_api" in ''|*[!0-9]*) fail "invalid Android API: $pd_api" ;; esac
    [ "$pd_api" -ge 24 ] || fail "Android API $pd_api is below the supported minimum 24"
    printf 'api\t%s\n' "$pd_api" >> "$pd_report"

    pd_abi=$(adb_device shell getprop ro.product.cpu.abi 2>&1 | tr -d '\r') || fail "cannot read primary ABI: $pd_abi"
    case "$pd_abi" in
        arm64-v8a) pd_binary=$MODULE_DIR/zapret2/bin/arm64-v8a/nfqws2 ;;
        armeabi-v7a|armeabi) pd_binary=$MODULE_DIR/zapret2/bin/armeabi-v7a/nfqws2 ;;
        *) fail "unsupported primary ABI: $pd_abi (release contains ARM/ARM64 binaries only)" ;;
    esac
    printf 'abi\t%s\n' "$pd_abi" >> "$pd_report"

    pd_uid=$(adb_root 'id -u' 2>&1 | tr -d '\r') || fail "root command failed or authorization was denied: $pd_uid"
    [ "$pd_uid" = 0 ] || fail "su did not return uid 0: $pd_uid"
    pd_magisk_code=$(adb_root 'magisk -V' 2>&1 | tr -d '\r') || fail "Magisk CLI is unavailable: $pd_magisk_code"
    case "$pd_magisk_code" in ''|*[!0-9]*) fail "invalid Magisk version code: $pd_magisk_code" ;; esac
    [ "$pd_magisk_code" -ge 20400 ] || fail "Magisk 20.4+ is required; version code is $pd_magisk_code"
    printf 'root_uid\t0\nmagisk_version_code\t%s\n' "$pd_magisk_code" >> "$pd_report"

    check_remote_path "$pd_report" "$MODULE_DIR" d 755
    check_remote_path "$pd_report" "$STATE_DIR" d 700
    check_remote_path "$pd_report" "$MODULE_DIR/module.prop" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/runtime.ini" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/strategy-catalogs/tcp.txt" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/strategy-catalogs/udp.txt" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/strategy-catalogs/voice.txt" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/strategy-catalogs/http80.txt" f 644
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/install-generation.meta" f 600
    check_remote_path "$pd_report" "$MODULE_DIR/system/bin/zapret2-start" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/system/bin/zapret2-stop" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/system/bin/zapret2-restart" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/system/bin/zapret2-status" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/system/bin/zapret2-full-rollback" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/scripts/zapret-start.sh" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/scripts/zapret-stop.sh" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/scripts/zapret-restart.sh" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/scripts/zapret-status.sh" f 755
    check_remote_path "$pd_report" "$MODULE_DIR/zapret2/scripts/zapret-full-rollback.sh" f 755
    check_remote_path "$pd_report" "$pd_binary" f 755
    check_optional_marker "$pd_report" "$MODULE_DIR/disable"
    pd_remove=$(adb_root "if [ -e $MODULE_DIR/remove ] || [ -L $MODULE_DIR/remove ]; then echo PRESENT; else echo ABSENT; fi" 2>&1 | tr -d '\r') || fail "cannot inspect removal marker"
    [ "$pd_remove" = ABSENT ] || fail "root manager removal marker is already present"
    pd_entry=$(adb_root "command -v $STATUS_ENTRY" 2>&1 | tr -d '\r') || fail "packaged status entry is unavailable: $pd_entry"
    [ "$pd_entry" = "$STATUS_ENTRY" ] || fail "unexpected status entry: $pd_entry"

    if ! probe_firewall_family iptables "$pd_dir/iptables-capabilities"; then
        fail "IPv4 iptables lacks NFQUEUE/queue-bypass, multiport, or mark support"
    fi
    IPV4_CONNBYTES_READY="$PFF_CONNBYTES"
    printf 'ipv4_capabilities\tready\nipv4_connbytes\t%s\n' \
        "$([ "$IPV4_CONNBYTES_READY" = 1 ] && printf ready || printf optional-fallback)" >> "$pd_report"
    if probe_firewall_family ip6tables "$pd_dir/ip6tables-capabilities"; then
        IPV6_READY=1
        IPV6_CONNBYTES_READY="$PFF_CONNBYTES"
        printf 'ipv6_capabilities\tready\nipv6_connbytes\t%s\n' \
            "$([ "$IPV6_CONNBYTES_READY" = 1 ] && printf ready || printf optional-fallback)" >> "$pd_report"
    else
        IPV6_READY=0
        printf 'ipv6_capabilities\tunavailable\n' >> "$pd_report"
        [ "$REQUIRE_IPV6" = 0 ] || fail "--require-ipv6 was set but ip6tables capability checks failed"
        warn "IPv6 capability check failed; IPv4 is ready and the module will skip IPv6"
    fi

    capture_status "$pd_dir/status.machine"
    capture_snapshot "$pd_dir/before-state"
    chmod 0600 "$pd_report"
}

validate_rollback_schema() {
    vrs_file=$1
    vrs_expected='Z2_RB_STATUS
Z2_RB_PROCESS_CLEAN
Z2_RB_FIREWALL_CLEAN
Z2_RB_ROLLBACK_ARMED
Z2_RB_HOSTS_PRESERVED
Z2_RB_REBOOT_REQUIRED
Z2_RB_USER_DATA_PRESERVED
Z2_RB_LEGACY_AMBIGUOUS
Z2_RB_DIAGNOSTIC
Z2_RB_COMPLETE'
    vrs_actual=$(sed 's/=.*//' "$vrs_file")
    [ "$vrs_actual" = "$vrs_expected" ] || fail "full-rollback machine schema is truncated, reordered, duplicated, or unknown"
    for vrs_pair in \
        Z2_RB_STATUS=complete Z2_RB_PROCESS_CLEAN=1 Z2_RB_FIREWALL_CLEAN=1 \
        Z2_RB_ROLLBACK_ARMED=1 Z2_RB_HOSTS_PRESERVED=1 Z2_RB_REBOOT_REQUIRED=1 \
        Z2_RB_USER_DATA_PRESERVED=1 Z2_RB_LEGACY_AMBIGUOUS=0 Z2_RB_COMPLETE=1; do
        grep -Fxq "$vrs_pair" "$vrs_file" || fail "full rollback did not satisfy $vrs_pair"
    done
}

mutate_root_to_file() {
    mrf_file=$1 mrf_command=$2
    [ "$MUTATION_ARMED" = 1 ] || fail "internal mutation gate is not armed"
    [ "$DRY_RUN" = 0 ] || fail "internal error: mutation reached during dry-run"
    require_capability_consistency
    set +e
    adb_root "$mrf_command" > "$mrf_file" 2>&1
    mrf_rc=$?
    set -e
    printf '%s\n' "$mrf_rc" > "$mrf_file.rc"
    [ "$mrf_rc" -eq 0 ] || fail "device command failed (rc=$mrf_rc); see $mrf_file"
}

mutate_adb_to_file() {
    maf_file=$1; shift
    [ "$MUTATION_ARMED" = 1 ] || fail "internal mutation gate is not armed"
    [ "$DRY_RUN" = 0 ] || fail "internal error: mutation reached during dry-run"
    require_capability_consistency
    set +e
    adb_device "$@" > "$maf_file" 2>&1
    maf_rc=$?
    set -e
    printf '%s\n' "$maf_rc" > "$maf_file.rc"
    [ "$maf_rc" -eq 0 ] || fail "ADB mutation failed (rc=$maf_rc); see $maf_file"
}

stage_stop() {
    ss_dir=$1
    mutate_root_to_file "$ss_dir/stop-1.txt" "$STOP_ENTRY"
    capture_status "$ss_dir/status-1.machine"; require_stopped_status "$ss_dir/status-1.machine"
    mutate_root_to_file "$ss_dir/stop-2.txt" "$STOP_ENTRY"
    capture_status "$ss_dir/status-2.machine"; require_stopped_status "$ss_dir/status-2.machine"
}

stage_start() {
    ss_dir=$1
    mutate_root_to_file "$ss_dir/start-1.txt" "$START_ENTRY"
    capture_status "$ss_dir/status-1.machine"; require_running_status "$ss_dir/status-1.machine"
    mutate_root_to_file "$ss_dir/start-2.txt" "$START_ENTRY"
    capture_status "$ss_dir/status-2.machine"; require_running_status "$ss_dir/status-2.machine"
}

stage_restart() {
    sr_dir=$1
    mutate_root_to_file "$sr_dir/restart-1.txt" "$RESTART_ENTRY"
    capture_status "$sr_dir/status-1.machine"; require_running_status "$sr_dir/status-1.machine"
    mutate_root_to_file "$sr_dir/restart-2.txt" "$RESTART_ENTRY"
    capture_status "$sr_dir/status-2.machine"; require_running_status "$sr_dir/status-2.machine"
}

new_stage_nonce() {
    if command -v od >/dev/null 2>&1 && [ -r /dev/urandom ]; then
        nsn_value=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
    elif command -v openssl >/dev/null 2>&1; then
        nsn_value=$(openssl rand -hex 16)
    else
        fail "secure randomness is required for the root-private update staging directory"
    fi
    case "$nsn_value" in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
        *) fail "secure random staging nonce is malformed" ;;
    esac
    printf '%s\n' "$nsn_value"
}

cleanup_shell_stage() {
    [ "$MUTATION_ARMED" = 1 ] || return 0
    [ -n "$SHELL_STAGE_MODULE" ] && [ -n "$SHELL_STAGE_APK" ] || return 0
    case "$SHELL_STAGE_MODULE:$SHELL_STAGE_APK" in
        /data/local/tmp/zapret2-device-smoke-[0-9a-f]*.module.zip:/data/local/tmp/zapret2-device-smoke-[0-9a-f]*.control.apk) ;;
        *) warn "refusing cleanup of malformed shell staging paths"; return 1 ;;
    esac
    require_capability_consistency
    adb_root "for z2_shell_file in $SHELL_STAGE_MODULE $SHELL_STAGE_APK; do
    if [ -L \"\$z2_shell_file\" ]; then exit 71
    elif [ -e \"\$z2_shell_file\" ]; then
        [ -f \"\$z2_shell_file\" ] || exit 72
        z2_shell_links=\$(stat -c %h \"\$z2_shell_file\" 2>/dev/null) || exit 73
        [ \"\$z2_shell_links\" = 1 ] || exit 74
        rm -f \"\$z2_shell_file\" || exit 75
    fi
done" >/dev/null 2>&1 || { warn "shell staging cleanup was refused or failed"; return 1; }
    SHELL_STAGE_MODULE=""; SHELL_STAGE_APK=""
}

cleanup_root_stage() {
    [ "$MUTATION_ARMED" = 1 ] || return 0
    [ -n "$ROOT_STAGE_DIR" ] || return 0
    case "$ROOT_STAGE_DIR" in /data/adb/zapret2-device-smoke-[0-9a-f]*) ;; *) warn "refusing cleanup of malformed root staging path"; return 1;; esac
    require_capability_consistency
    adb_root "if [ -L $ROOT_STAGE_DIR ]; then exit 81
elif [ -e $ROOT_STAGE_DIR ]; then
    [ -d $ROOT_STAGE_DIR ] || exit 82
    z2_dir_stat=\$(stat -c %a:%u:%g $ROOT_STAGE_DIR 2>/dev/null) || exit 83
    [ \"\$z2_dir_stat\" = 700:0:0 ] || exit 84
    z2_cleanup_seen=0
    for z2_root_file in $ROOT_STAGE_DIR/* $ROOT_STAGE_DIR/.[!.]* $ROOT_STAGE_DIR/..?*; do
        [ -e \"\$z2_root_file\" ] || [ -L \"\$z2_root_file\" ] || continue
        case \"\$z2_root_file\" in $ROOT_STAGE_APK|$ROOT_STAGE_MODULE) ;; *) exit 86;; esac
        [ -f \"\$z2_root_file\" ] && [ ! -L \"\$z2_root_file\" ] || exit 87
        z2_file_stat=\$(stat -c %h:%a:%u:%g \"\$z2_root_file\" 2>/dev/null) || exit 88
        [ \"\$z2_file_stat\" = 1:600:0:0 ] || exit 89
        z2_cleanup_seen=\$((z2_cleanup_seen + 1))
    done
    [ \"\$z2_cleanup_seen\" -le 2 ] || exit 90
    rm -f $ROOT_STAGE_APK $ROOT_STAGE_MODULE || exit 90
    rmdir $ROOT_STAGE_DIR || exit 91
fi" >/dev/null 2>&1 || { warn "root-private staging cleanup was refused or failed; inspect it manually"; return 1; }
    ROOT_STAGE_DIR=""; ROOT_STAGE_MODULE=""; ROOT_STAGE_APK=""
}

cleanup_update_stages() {
    cleanup_shell_stage || true
    cleanup_root_stage || true
}

validate_stage_copy_schema() {
    vscs_file=$1 vscs_kind=$2 vscs_hash=$3
    vscs_expected=$(printf 'Z2_STAGE_KIND=%s\nZ2_STAGE_SHA256=%s\nZ2_STAGE_SOURCE_SECURE=1\nZ2_STAGE_PRIVATE_SECURE=1\nZ2_STAGE_COPY_COMPLETE=1' "$vscs_kind" "$vscs_hash")
    vscs_actual=$(cat "$vscs_file")
    [ "$vscs_actual" = "$vscs_expected" ] ||
        fail "root-private copy schema is truncated, reordered, duplicated, unknown, or unbound: $vscs_file"
}

validate_root_stage_schema() {
    vrss_file=$1 vrss_path=$2
    vrss_expected=$(printf 'Z2_ROOT_STAGE_PATH=%s\nZ2_ROOT_STAGE_SECURE=1\nZ2_ROOT_STAGE_COMPLETE=1' "$vrss_path")
    vrss_actual=$(cat "$vrss_file")
    [ "$vrss_actual" = "$vrss_expected" ] ||
        fail "root stage schema is truncated, reordered, duplicated, unknown, insecure, or unbound: $vrss_file"
}

copy_to_root_stage() {
    ctrs_log=$1 ctrs_kind=$2 ctrs_source=$3 ctrs_dest=$4 ctrs_expected=$5
    mutate_root_to_file "$ctrs_log" "
[ -d $ROOT_STAGE_DIR ] && [ ! -L $ROOT_STAGE_DIR ] || exit 101
[ \"\$(stat -c %a:%u:%g $ROOT_STAGE_DIR 2>/dev/null)\" = 700:0:0 ] || exit 102
[ -f $ctrs_source ] && [ ! -L $ctrs_source ] || exit 103
z2_src_stat=\$(stat -c %h:%a:%u:%g:%s $ctrs_source 2>/dev/null) || exit 104
z2_src_links=\${z2_src_stat%%:*}; z2_src_rest=\${z2_src_stat#*:}
z2_src_mode=\${z2_src_rest%%:*}; z2_src_rest=\${z2_src_rest#*:}
z2_src_uid=\${z2_src_rest%%:*}; z2_src_rest=\${z2_src_rest#*:}
z2_src_gid=\${z2_src_rest%%:*}; z2_src_size=\${z2_src_rest#*:}
[ \"\$z2_src_links\" = 1 ] || exit 105
case \"\$z2_src_mode\" in 600|640|644) ;; *) exit 106;; esac
case \"\$z2_src_uid\" in 0|2000) ;; *) exit 107;; esac
case \"\$z2_src_gid\" in 0|2000) ;; *) exit 108;; esac
case \"\$z2_src_size\" in ''|0|*[!0-9]*) exit 109;; esac
z2_src_hash=\$(sha256sum $ctrs_source 2>/dev/null) || exit 110
z2_src_hash=\${z2_src_hash%% *}
[ \"\$z2_src_hash\" = $ctrs_expected ] || exit 111
[ ! -e $ctrs_dest ] && [ ! -L $ctrs_dest ] || exit 112
cp $ctrs_source $ctrs_dest || exit 113
chown 0:0 $ctrs_dest || exit 114
chmod 0600 $ctrs_dest || exit 115
[ -f $ctrs_dest ] && [ ! -L $ctrs_dest ] || exit 116
z2_dst_stat=\$(stat -c %h:%a:%u:%g:%s $ctrs_dest 2>/dev/null) || exit 117
[ \"\$z2_dst_stat\" = \"1:600:0:0:\$z2_src_size\" ] || exit 118
z2_dst_hash=\$(sha256sum $ctrs_dest 2>/dev/null) || exit 119
z2_dst_hash=\${z2_dst_hash%% *}
[ \"\$z2_dst_hash\" = $ctrs_expected ] || exit 120
printf 'Z2_STAGE_KIND=$ctrs_kind\n'
printf 'Z2_STAGE_SHA256=%s\n' \"\$z2_dst_hash\"
printf 'Z2_STAGE_SOURCE_SECURE=1\n'
printf 'Z2_STAGE_PRIVATE_SECURE=1\n'
printf 'Z2_STAGE_COPY_COMPLETE=1\n'"
    validate_stage_copy_schema "$ctrs_log" "$ctrs_kind" "$ctrs_expected"
}

remote_sha256() {
    rs_path=$1
    rs_line=$(adb_root "sha256sum $rs_path" 2>&1 | tr -d '\r') || fail "cannot hash remote artifact $rs_path: $rs_line"
    rs_hash=${rs_line%% *}
    normalize_hash "$rs_hash"
}

installed_module_hash() {
    imh_value=$(adb_root "sed -n 's/^archive_sha256=//p' $MODULE_DIR/zapret2/install-generation.meta" 2>&1 | tr -d '\r') ||
        fail "cannot read installed module archive hash: $imh_value"
    normalize_hash "$imh_value"
}

installed_apk_hash() {
    iah_path=$(adb_device shell pm path com.zapret2.app 2>&1 | tr -d '\r' | sed -n 's/^package://p' | sed -n '1p') ||
        fail "cannot locate installed control APK"
    case "$iah_path" in /data/app/*/base.apk) ;; *) fail "unexpected installed APK path: $iah_path" ;; esac
    case "$iah_path" in *[!A-Za-z0-9._/+=~-]*) fail "unsafe installed APK path: $iah_path" ;; esac
    remote_sha256 "$iah_path"
}

stage_update() {
    su_dir=$1
    verify_recorded_artifacts
    su_nonce=$(new_stage_nonce)
    SHELL_STAGE_MODULE=/data/local/tmp/zapret2-device-smoke-$su_nonce.module.zip
    SHELL_STAGE_APK=/data/local/tmp/zapret2-device-smoke-$su_nonce.control.apk
    ROOT_STAGE_DIR=/data/adb/zapret2-device-smoke-$su_nonce
    ROOT_STAGE_MODULE=$ROOT_STAGE_DIR/module.zip
    ROOT_STAGE_APK=$ROOT_STAGE_DIR/control.apk
    trap 'cleanup_update_stages' EXIT
    mutate_adb_to_file "$su_dir/push-module.txt" push "$MODULE_ZIP" "$SHELL_STAGE_MODULE"
    mutate_adb_to_file "$su_dir/push-apk.txt" push "$APK_FILE" "$SHELL_STAGE_APK"
    mutate_root_to_file "$su_dir/create-root-stage.txt" "
[ ! -e $ROOT_STAGE_DIR ] && [ ! -L $ROOT_STAGE_DIR ] || exit 121
mkdir $ROOT_STAGE_DIR || exit 122
chown 0:0 $ROOT_STAGE_DIR || exit 123
chmod 0700 $ROOT_STAGE_DIR || exit 124
[ -d $ROOT_STAGE_DIR ] && [ ! -L $ROOT_STAGE_DIR ] || exit 125
[ \"\$(stat -c %a:%u:%g $ROOT_STAGE_DIR 2>/dev/null)\" = 700:0:0 ] || exit 126
printf 'Z2_ROOT_STAGE_PATH=$ROOT_STAGE_DIR\nZ2_ROOT_STAGE_SECURE=1\nZ2_ROOT_STAGE_COMPLETE=1\n'"
    validate_root_stage_schema "$su_dir/create-root-stage.txt" "$ROOT_STAGE_DIR"
    copy_to_root_stage "$su_dir/copy-module.txt" module "$SHELL_STAGE_MODULE" "$ROOT_STAGE_MODULE" "$MODULE_SHA256"
    copy_to_root_stage "$su_dir/copy-apk.txt" apk "$SHELL_STAGE_APK" "$ROOT_STAGE_APK" "$APK_SHA256"
    cleanup_shell_stage || fail "could not retire shell-accessible staging files after private copy"
    mutate_root_to_file "$su_dir/install-module.txt" "
[ -d $ROOT_STAGE_DIR ] && [ ! -L $ROOT_STAGE_DIR ] || exit 131
[ \"\$(stat -c %a:%u:%g $ROOT_STAGE_DIR 2>/dev/null)\" = 700:0:0 ] || exit 132
[ -f $ROOT_STAGE_MODULE ] && [ ! -L $ROOT_STAGE_MODULE ] || exit 133
[ \"\$(stat -c %h:%a:%u:%g $ROOT_STAGE_MODULE 2>/dev/null)\" = 1:600:0:0 ] || exit 134
z2_install_hash=\$(sha256sum $ROOT_STAGE_MODULE 2>/dev/null) || exit 135
z2_install_hash=\${z2_install_hash%% *}
[ \"\$z2_install_hash\" = $MODULE_SHA256 ] || exit 136
printf 'Z2_INSTALL_PATH=$ROOT_STAGE_MODULE\nZ2_INSTALL_SHA256=%s\n' \"\$z2_install_hash\"
magisk --install-module $ROOT_STAGE_MODULE || exit 137
printf 'Z2_INSTALL_COMPLETE=1\n'"
    grep -Fxq "Z2_INSTALL_PATH=$ROOT_STAGE_MODULE" "$su_dir/install-module.txt" || fail "module installer path was not bound"
    grep -Fxq "Z2_INSTALL_SHA256=$MODULE_SHA256" "$su_dir/install-module.txt" || fail "module installer hash was not bound"
    [ "$(tail -n 1 "$su_dir/install-module.txt")" = Z2_INSTALL_COMPLETE=1 ] || fail "module install output is truncated"
    mutate_root_to_file "$su_dir/install-apk.txt" "
[ -d $ROOT_STAGE_DIR ] && [ ! -L $ROOT_STAGE_DIR ] || exit 141
[ \"\$(stat -c %a:%u:%g $ROOT_STAGE_DIR 2>/dev/null)\" = 700:0:0 ] || exit 142
[ -f $ROOT_STAGE_APK ] && [ ! -L $ROOT_STAGE_APK ] || exit 143
z2_apk_stat=\$(stat -c %h:%a:%u:%g:%s $ROOT_STAGE_APK 2>/dev/null) || exit 144
z2_apk_size=\${z2_apk_stat##*:}
[ \"\$z2_apk_stat\" = \"1:600:0:0:\$z2_apk_size\" ] || exit 145
case \"\$z2_apk_size\" in ''|0|*[!0-9]*) exit 146;; esac
z2_apk_hash=\$(sha256sum $ROOT_STAGE_APK 2>/dev/null) || exit 147
z2_apk_hash=\${z2_apk_hash%% *}
[ \"\$z2_apk_hash\" = $APK_SHA256 ] || exit 148
printf 'Z2_APK_INSTALL_PATH=$ROOT_STAGE_APK\nZ2_APK_INSTALL_SHA256=%s\n' \"\$z2_apk_hash\"
cat $ROOT_STAGE_APK | pm install -r -S \"\$z2_apk_size\" || exit 149
printf 'Z2_APK_INSTALL_COMPLETE=1\n'"
    grep -Fxq "Z2_APK_INSTALL_PATH=$ROOT_STAGE_APK" "$su_dir/install-apk.txt" || fail "APK installer path was not bound"
    grep -Fxq "Z2_APK_INSTALL_SHA256=$APK_SHA256" "$su_dir/install-apk.txt" || fail "APK installer hash was not bound"
    [ "$(tail -n 1 "$su_dir/install-apk.txt")" = Z2_APK_INSTALL_COMPLETE=1 ] || fail "APK install output is truncated"
    cleanup_root_stage || fail "root-private staging cleanup failed"
    trap - EXIT
    [ "$(installed_module_hash)" = "$MODULE_SHA256" ] || fail "installed module generation does not match the exact release ZIP"
    [ "$(installed_apk_hash)" = "$APK_SHA256" ] || fail "installed base APK does not match the exact release APK"
    capture_status "$su_dir/status.machine"
    require_running_status "$su_dir/status.machine"
}

stage_full_rollback() {
    sfr_dir=$1
    mutate_root_to_file "$sfr_dir/full-rollback.machine" "$ROLLBACK_ENTRY --machine"
    validate_rollback_schema "$sfr_dir/full-rollback.machine"
    capture_clean_firewall_audit "$sfr_dir"
    capture_no_owned_process_audit "$sfr_dir/process-audit.txt"
}

stage_uninstall_checkpoint() {
    suc_dir=$1
    suc_boot_query=$suc_dir/boot-before.txt
    capture_root_query "$suc_boot_query" '
z2_boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 151
printf "Z2_UNINSTALL_BOOT_ID=%s\n" "$z2_boot_id"
printf "Z2_UNINSTALL_BOOT_COMPLETE=1\n"'
    validate_query_schema "$suc_boot_query" 'Z2_UNINSTALL_BOOT_ID
Z2_UNINSTALL_BOOT_COMPLETE'
    suc_boot_id=$(sed -n 's/^Z2_UNINSTALL_BOOT_ID=//p' "$suc_boot_query")
    valid_boot_id "$suc_boot_id" || fail "uninstall checkpoint received a malformed boot identity"
    grep -Fxq Z2_UNINSTALL_BOOT_COMPLETE=1 "$suc_boot_query" || fail "uninstall checkpoint boot query is truncated"
    chmod 0600 "$suc_boot_query"
    suc_boot_size=$(wc -c < "$suc_boot_query" | tr -d ' ')
    suc_boot_hash=$(sha256_file "$suc_boot_query")
    suc_run=$(manifest_value created_utc "$EVIDENCE_DIR/run.meta")
    suc_meta_tmp=$suc_dir/.uninstall-reboot.meta.$$
    [ ! -e "$suc_meta_tmp" ] && [ ! -L "$suc_meta_tmp" ] || fail "unsafe uninstall reboot checkpoint temporary path"
    {
        printf 'schema=1\n'
        printf 'serial=%s\n' "$SERIAL"
        printf 'run_created_utc=%s\n' "$suc_run"
        printf 'boot_before_file=boot-before.txt\n'
        printf 'boot_before_size=%s\n' "$suc_boot_size"
        printf 'boot_before_sha256=%s\n' "$suc_boot_hash"
        printf 'pre_boot_id=%s\n' "$suc_boot_id"
        printf 'complete=1\n'
    } > "$suc_meta_tmp"
    chmod 0600 "$suc_meta_tmp"
    mv -f "$suc_meta_tmp" "$suc_dir/uninstall-reboot.meta"
    validate_uninstall_reboot_checkpoint "$suc_dir/uninstall-reboot.meta"
    cat > "$suc_dir/ROOT-MANAGER-UNINSTALL-REQUIRED.txt" <<EOF
Exact device: $SERIAL

1. Open the trusted root-manager UI on this disposable test device.
2. Remove only the Zapret2 module (id: zapret2).
3. Confirm the root manager ran the packaged uninstall hook successfully.
4. Reboot the device.
5. Run this harness with the same serial/evidence directory and:
     --stage uninstall-verify

Do not reinstall in this evidence sequence. Reinstall is a separate case and
must start with a new preflight evidence directory after uninstall verification.
EOF
    chmod 0600 "$suc_dir/ROOT-MANAGER-UNINSTALL-REQUIRED.txt"
    say "Uninstall checkpoint recorded. Remove only Zapret2 in the root-manager UI, reboot, then run uninstall-verify."
}

verify_uninstall() {
    vu_dir=$1
    mkdir "$vu_dir" || fail "cannot create uninstall verification evidence"
    chmod 0700 "$vu_dir"
    vu_checkpoint=$EVIDENCE_DIR/stages/uninstall/uninstall-reboot.meta
    validate_uninstall_reboot_checkpoint "$vu_checkpoint"
    vu_pre_boot=$(manifest_value pre_boot_id "$vu_checkpoint")
    vu_run=$(manifest_value run_created_utc "$vu_checkpoint")
    capture_root_query "$vu_dir/boot-after.txt" '
z2_boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 151
printf "Z2_UNINSTALL_BOOT_ID=%s\n" "$z2_boot_id"
printf "Z2_UNINSTALL_BOOT_COMPLETE=1\n"'
    validate_query_schema "$vu_dir/boot-after.txt" 'Z2_UNINSTALL_BOOT_ID
Z2_UNINSTALL_BOOT_COMPLETE'
    vu_post_boot=$(sed -n 's/^Z2_UNINSTALL_BOOT_ID=//p' "$vu_dir/boot-after.txt")
    valid_boot_id "$vu_post_boot" || fail "uninstall verification received a malformed boot identity"
    grep -Fxq Z2_UNINSTALL_BOOT_COMPLETE=1 "$vu_dir/boot-after.txt" || fail "uninstall verification boot query is truncated"
    [ "$vu_post_boot" != "$vu_pre_boot" ] || fail "uninstall verification requires a proven reboot after the uninstall checkpoint"
    {
        printf 'schema=1\nserial=%s\nrun_created_utc=%s\n' "$SERIAL" "$vu_run"
        printf 'pre_boot_id=%s\npost_boot_id=%s\ncomplete=1\n' "$vu_pre_boot" "$vu_post_boot"
    } > "$vu_dir/boot-transition.meta"
    chmod 0600 "$vu_dir/boot-transition.meta"
    capture_root_query "$vu_dir/root-audit.txt" '
z2_uid=$(id -u) || exit 151
printf "Z2_UNINSTALL_ROOT_UID=%s\n" "$z2_uid"
printf "Z2_UNINSTALL_ROOT_COMPLETE=1\n"
[ "$z2_uid" = 0 ]'
    validate_query_schema "$vu_dir/root-audit.txt" 'Z2_UNINSTALL_ROOT_UID
Z2_UNINSTALL_ROOT_COMPLETE'
    grep -Fxq Z2_UNINSTALL_ROOT_UID=0 "$vu_dir/root-audit.txt" || fail "uninstall verification does not have uid 0"
    grep -Fxq Z2_UNINSTALL_ROOT_COMPLETE=1 "$vu_dir/root-audit.txt" || fail "root audit is truncated"

    capture_root_query "$vu_dir/path-audit.txt" "
z2_live=absent; z2_update=absent; z2_wrappers=absent
{ [ -e $MODULE_DIR ] || [ -L $MODULE_DIR ]; } && z2_live=present
{ [ -e /data/adb/modules_update/zapret2 ] || [ -L /data/adb/modules_update/zapret2 ]; } && z2_update=present
for z2_wrapper in $START_ENTRY $STOP_ENTRY $RESTART_ENTRY $STATUS_ENTRY $ROLLBACK_ENTRY; do
    if [ -e \"\$z2_wrapper\" ] || [ -L \"\$z2_wrapper\" ]; then z2_wrappers=present; fi
done
printf 'Z2_UNINSTALL_LIVE_MODULE=%s\n' \"\$z2_live\"
printf 'Z2_UNINSTALL_UPDATE_MODULE=%s\n' \"\$z2_update\"
printf 'Z2_UNINSTALL_WRAPPERS=%s\n' \"\$z2_wrappers\"
printf 'Z2_UNINSTALL_PATHS_COMPLETE=1\n'
[ \"\$z2_live\" = absent ] && [ \"\$z2_update\" = absent ] && [ \"\$z2_wrappers\" = absent ]"
    validate_query_schema "$vu_dir/path-audit.txt" 'Z2_UNINSTALL_LIVE_MODULE
Z2_UNINSTALL_UPDATE_MODULE
Z2_UNINSTALL_WRAPPERS
Z2_UNINSTALL_PATHS_COMPLETE'
    for vu_pair in Z2_UNINSTALL_LIVE_MODULE=absent Z2_UNINSTALL_UPDATE_MODULE=absent Z2_UNINSTALL_WRAPPERS=absent Z2_UNINSTALL_PATHS_COMPLETE=1; do
        grep -Fxq "$vu_pair" "$vu_dir/path-audit.txt" || fail "post-uninstall path audit failed: $vu_pair"
    done

    capture_clean_firewall_audit "$vu_dir"
    capture_no_owned_process_audit "$vu_dir/process-audit.txt"

    capture_root_query "$vu_dir/state-audit.txt" "
[ -d $STATE_DIR ] && [ ! -L $STATE_DIR ] || exit 161
z2_state_stat=\$(stat -c %a:%u:%g $STATE_DIR 2>/dev/null) || exit 162
[ \"\$z2_state_stat\" = 700:0:0 ] || exit 163
z2_state_entries=\$(ls -A $STATE_DIR 2>/dev/null) || exit 164
[ \"\$z2_state_entries\" = uninstall.tombstone ] || exit 165
[ -f $STATE_DIR/uninstall.tombstone ] && [ ! -L $STATE_DIR/uninstall.tombstone ] || exit 166
z2_tombstone_stat=\$(stat -c %h:%a:%u:%g $STATE_DIR/uninstall.tombstone 2>/dev/null) || exit 167
[ \"\$z2_tombstone_stat\" = 1:600:0:0 ] || exit 168
z2_tombstone_keys=\$(sed 's/=.*//' $STATE_DIR/uninstall.tombstone 2>/dev/null) || exit 169
[ \"\$z2_tombstone_keys\" = 'version
pid
starttime
token
module_dir' ] || exit 170
z2_tombstone_version=\$(sed -n '1s/^version=//p' $STATE_DIR/uninstall.tombstone) || exit 171
z2_tombstone_pid=\$(sed -n '2s/^pid=//p' $STATE_DIR/uninstall.tombstone) || exit 172
z2_tombstone_start=\$(sed -n '3s/^starttime=//p' $STATE_DIR/uninstall.tombstone) || exit 173
z2_tombstone_token=\$(sed -n '4s/^token=//p' $STATE_DIR/uninstall.tombstone) || exit 174
z2_tombstone_module=\$(sed -n '5s/^module_dir=//p' $STATE_DIR/uninstall.tombstone) || exit 175
[ \"\$z2_tombstone_version\" = 1 ] || exit 176
case \"\$z2_tombstone_pid\" in ''|*[!0-9]*) exit 177;; esac
[ \"\$z2_tombstone_pid\" -gt 0 ] 2>/dev/null || exit 178
case \"\$z2_tombstone_start\" in ''|*[!0-9]*) exit 179;; esac
case \"\$z2_tombstone_token\" in ''|*[!A-Za-z0-9._-]*) exit 180;; esac
[ \"\$z2_tombstone_module\" = /data/adb/modules/zapret2 ] || exit 181
if [ -e /proc/\$z2_tombstone_pid ] || [ -L /proc/\$z2_tombstone_pid ]; then
    [ -r /proc/\$z2_tombstone_pid/stat ] || exit 182
    z2_tombstone_live_start=\$(awk '{ print \$22 }' /proc/\$z2_tombstone_pid/stat 2>/dev/null) || exit 183
    case \"\$z2_tombstone_live_start\" in ''|*[!0-9]*) exit 184;; esac
    [ \"\$z2_tombstone_live_start\" != \"\$z2_tombstone_start\" ] || exit 185
else
    [ ! -e /proc/\$z2_tombstone_pid/stat ] && [ ! -L /proc/\$z2_tombstone_pid/stat ] || exit 186
fi
printf 'Z2_UNINSTALL_STATE_DIR=700:0:0\n'
printf 'Z2_UNINSTALL_STATE_ENTRIES=uninstall.tombstone\n'
printf 'Z2_UNINSTALL_TOMBSTONE=1:600:0:0\n'
printf 'Z2_UNINSTALL_TOMBSTONE_VERSION=%s\n' \"\$z2_tombstone_version\"
printf 'Z2_UNINSTALL_TOMBSTONE_PID=%s\n' \"\$z2_tombstone_pid\"
printf 'Z2_UNINSTALL_TOMBSTONE_STARTTIME=%s\n' \"\$z2_tombstone_start\"
printf 'Z2_UNINSTALL_TOMBSTONE_TOKEN=%s\n' \"\$z2_tombstone_token\"
printf 'Z2_UNINSTALL_TOMBSTONE_MODULE_DIR=%s\n' \"\$z2_tombstone_module\"
printf 'Z2_UNINSTALL_TOMBSTONE_OWNER=dead\n'
printf 'Z2_UNINSTALL_STATE_COMPLETE=1\n'"
    validate_query_schema "$vu_dir/state-audit.txt" 'Z2_UNINSTALL_STATE_DIR
Z2_UNINSTALL_STATE_ENTRIES
Z2_UNINSTALL_TOMBSTONE
Z2_UNINSTALL_TOMBSTONE_VERSION
Z2_UNINSTALL_TOMBSTONE_PID
Z2_UNINSTALL_TOMBSTONE_STARTTIME
Z2_UNINSTALL_TOMBSTONE_TOKEN
Z2_UNINSTALL_TOMBSTONE_MODULE_DIR
Z2_UNINSTALL_TOMBSTONE_OWNER
Z2_UNINSTALL_STATE_COMPLETE'
    for vu_pair in Z2_UNINSTALL_STATE_DIR=700:0:0 Z2_UNINSTALL_STATE_ENTRIES=uninstall.tombstone Z2_UNINSTALL_TOMBSTONE=1:600:0:0 Z2_UNINSTALL_TOMBSTONE_VERSION=1 Z2_UNINSTALL_TOMBSTONE_MODULE_DIR=/data/adb/modules/zapret2 Z2_UNINSTALL_TOMBSTONE_OWNER=dead Z2_UNINSTALL_STATE_COMPLETE=1; do
        grep -Fxq "$vu_pair" "$vu_dir/state-audit.txt" || fail "post-uninstall state audit failed: $vu_pair"
    done
    vu_tombstone_pid=$(sed -n 's/^Z2_UNINSTALL_TOMBSTONE_PID=//p' "$vu_dir/state-audit.txt")
    case "$vu_tombstone_pid" in ''|*[!0-9]*) fail "post-uninstall tombstone pid is malformed";; esac
    [ "$vu_tombstone_pid" -gt 0 ] 2>/dev/null || fail "post-uninstall tombstone pid is not positive"
    vu_tombstone_start=$(sed -n 's/^Z2_UNINSTALL_TOMBSTONE_STARTTIME=//p' "$vu_dir/state-audit.txt")
    case "$vu_tombstone_start" in ''|*[!0-9]*) fail "post-uninstall tombstone starttime is malformed";; esac
    vu_tombstone_token=$(sed -n 's/^Z2_UNINSTALL_TOMBSTONE_TOKEN=//p' "$vu_dir/state-audit.txt")
    case "$vu_tombstone_token" in ''|*[!A-Za-z0-9._-]*) fail "post-uninstall tombstone token is malformed";; esac
    capture_root_query "$vu_dir/state-listing.txt" "ls -lan $STATE_DIR"
}

require_adb
authorize_device

if [ "$STAGE" = preflight ]; then
    init_new_evidence
    record_initial_manifests
    preflight_device "$EVIDENCE_DIR/preflight"
    record_capability_manifest
    printf 'complete=1\n' > "$EVIDENCE_DIR/preflight.complete"
    chmod 0600 "$EVIDENCE_DIR/preflight.complete"
    advance_sequence preflight
    say "Read-only preflight complete. Private before-state evidence: $EVIDENCE_DIR"
    exit 0
fi

open_existing_evidence

case "$STAGE" in
    stop) require_previous_stage preflight ;;
    start) require_previous_stage stop ;;
    restart) require_previous_stage start ;;
    update) require_previous_stage restart; verify_recorded_artifacts ;;
    full-rollback) require_previous_stage update ;;
    uninstall) require_previous_stage full-rollback ;;
    uninstall-verify) require_previous_stage uninstall ;;
esac

if [ "$STAGE" = uninstall-verify ]; then
    authorize_device
    verify_uninstall "$EVIDENCE_DIR/stages/uninstall-verify"
    advance_sequence uninstall-verify
    say "Uninstall verification complete. Reinstall must use a new evidence directory."
    exit 0
fi

if [ "$DRY_RUN" = 1 ]; then
    pd_dry_stamp=$(date -u '+%Y%m%dT%H%M%SZ')
    pd_stage_dir=$EVIDENCE_DIR/stages/dry-run-$STAGE-$pd_dry_stamp-$$
else
    pd_stage_dir=$EVIDENCE_DIR/stages/$STAGE
fi
[ ! -e "$pd_stage_dir" ] && [ ! -L "$pd_stage_dir" ] || fail "stage evidence already exists: $pd_stage_dir"
mkdir "$pd_stage_dir"; chmod 0700 "$pd_stage_dir"
preflight_device "$pd_stage_dir/read-only-preflight"
require_capability_consistency

if [ "$DRY_RUN" = 1 ]; then
    say "DRY RUN: preflight passed; stage '$STAGE' would be next. No device mutation was executed and sequence state was not advanced."
    exit 0
fi

# This is the only point at which the mutation wrappers can become active.
MUTATION_ARMED=1
case "$STAGE" in
    stop) stage_stop "$pd_stage_dir" ;;
    start) stage_start "$pd_stage_dir" ;;
    restart) stage_restart "$pd_stage_dir" ;;
    update) stage_update "$pd_stage_dir" ;;
    full-rollback) stage_full_rollback "$pd_stage_dir" ;;
    uninstall) stage_uninstall_checkpoint "$pd_stage_dir" ;;
esac

capture_snapshot "$pd_stage_dir/after-state"
advance_sequence "$STAGE"
say "Stage '$STAGE' complete. Evidence: $pd_stage_dir"
