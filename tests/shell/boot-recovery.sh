#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
TMP=${Z2_TEST_TMP:?}
CASE="$TMP/boot-recovery"
MOCK="$CASE/bin"
MUTATION_LOG="$CASE/firewall-mutations.log"

fail() { echo "FAIL: boot-recovery: $*" >&2; exit 1; }

mkdir -p "$CASE" "$MOCK"
: > "$MUTATION_LOG"

# The production CI has normal Android/Linux coreutils.  The local Codex
# Windows runtime intentionally ships a reduced POSIX layer, so provide narrow
# deterministic fallbacks only when a command is absent; they model metadata
# for this isolated root-owned fixture and never enter package artifacts.
if ! command -v chmod >/dev/null 2>&1; then
    cat > "$MOCK/chmod" <<'EOF'
#!/bin/sh
exit 0
EOF
fi
if ! command -v stat >/dev/null 2>&1; then
    cat > "$MOCK/stat" <<'EOF'
#!/bin/sh
[ "${1:-}" = -c ] || exit 1
format=${2:-}; path=${3:-}
case "$format" in
    %u) printf '0\n' ;;
    %a) if [ -d "$path" ]; then printf '700\n'; else printf '600\n'; fi ;;
    %h) printf '1\n' ;;
    *) exit 1 ;;
esac
EOF
fi
if ! command -v sha256sum >/dev/null 2>&1; then
    cat > "$MOCK/sha256sum" <<'EOF'
#!/bin/sh
[ "$#" -gt 0 ] || cat >/dev/null
printf 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  -\n'
EOF
fi
if ! command -v od >/dev/null 2>&1; then
    cat > "$MOCK/od" <<'EOF'
#!/bin/sh
[ "$#" -gt 3 ] || cat >/dev/null
printf 'aa\n'
EOF
fi
if ! command -v sync >/dev/null 2>&1; then
    cat > "$MOCK/sync" <<'EOF'
#!/bin/sh
exit 0
EOF
fi
if ! command -v sleep >/dev/null 2>&1; then
    cat > "$MOCK/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
fi
if ! command -v ln >/dev/null 2>&1; then
    cat > "$MOCK/ln" <<'EOF'
#!/bin/sh
cp "$1" "$2"
EOF
fi
PATH="$MOCK:$PATH"
export PATH

cat > "$MOCK/getprop" <<'EOF'
#!/bin/sh
[ "${1:-}" = sys.boot_completed ] && printf '1\n'
EOF
cat > "$MOCK/ip" <<'EOF'
#!/bin/sh
[ "$*" = 'route show default' ] && printf 'default via 192.0.2.1 dev test0\n'
EOF
cat > "$MOCK/iptables" <<'EOF'
#!/bin/sh
case " $* " in
    *' -t mangle -L OUTPUT -n '*) exit 0 ;;
    *' -t mangle -C OUTPUT -j ZAPRET2_OUT '*|*' -t mangle -C INPUT -j ZAPRET2_IN '*)
        exit 1
        ;;
    *' -t mangle -S ZAPRET2_OUT '*|*' -t mangle -S ZAPRET2_IN '*) exit 1 ;;
    ' -t mangle -S ') exit 0 ;;
    *' -A '*|*' -I '*|*' -D '*|*' -N '*|*' -E '*|*' -F '*|*' -X '*)
        printf '%s\n' "$*" >> "$Z2_BOOT_MUTATION_LOG"
        exit 99
        ;;
    *) exit 0 ;;
esac
EOF
cp "$MOCK/iptables" "$MOCK/ip6tables"
chmod 0755 "$MOCK/getprop" "$MOCK/ip" "$MOCK/iptables" "$MOCK/ip6tables"

if [ -r /proc/sys/kernel/random/boot_id ]; then
    actual_boot=$(cat /proc/sys/kernel/random/boot_id)
    Z2_TEST_BOOT_OVERRIDE=0
else
    actual_boot=22222222-2222-2222-2222-222222222222
    Z2_TEST_BOOT_OVERRIDE=1
fi
case "$actual_boot" in
    11111111-1111-1111-1111-111111111111) stale_boot=22222222-2222-2222-2222-222222222222 ;;
    *) stale_boot=11111111-1111-1111-1111-111111111111 ;;
esac

prepare_case() {
    mode="$1"
    module="$CASE/$mode/module"
    state="$CASE/$mode/state"
    mkdir -p "$module/zapret2/scripts" "$state"
    chmod 0700 "$state"
    cp "$ROOT/service.sh" "$module/service.sh"
    cp "$ROOT/zapret2/scripts/common.sh" "$ROOT/zapret2/scripts/firewall-reconciler.sh" \
        "$ROOT/zapret2/scripts/zapret-start.sh" "$module/zapret2/scripts/"
    if [ "$Z2_TEST_BOOT_OVERRIDE" = 1 ]; then
        cat >> "$module/zapret2/scripts/common.sh" <<EOF
read_current_boot_id() { CURRENT_BOOT_ID=$actual_boot; }
EOF
    fi
    cp "$ROOT/zapret2/runtime.ini" "$module/zapret2/runtime.ini"
    printf '%s\n' '#!/bin/sh' 'printf started >> "${Z2_BOOT_MUTATION_LOG}.daemon"' > "$module/zapret2/nfqws2"
    chmod 0755 "$module/service.sh" "$module/zapret2/scripts/"*.sh "$module/zapret2/nfqws2"
    sed 's/^autostart=.*/autostart=0/' "$module/zapret2/runtime.ini" > "$module/zapret2/runtime.ini.tmp"
    mv "$module/zapret2/runtime.ini.tmp" "$module/zapret2/runtime.ini"
    cat > "$module/zapret2/install-generation.meta" <<EOF
version=1
module_dir=$module
generation=boot-recovery-install
archive_sha256=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
EOF
    chmod 0600 "$module/zapret2/install-generation.meta"
    if [ "$mode" = disabled ]; then : > "$module/disable"; chmod 0600 "$module/disable"; fi

    (
        STATE_DIR="$state"
        MODDIR="$module"
        ZAPRET_DIR="$module/zapret2"
        SCRIPT_DIR="$module/zapret2/scripts"
        export STATE_DIR MODDIR ZAPRET_DIR SCRIPT_DIR
        . "$module/zapret2/scripts/common.sh"
        read_current_boot_id() { CURRENT_BOOT_ID="$stale_boot"; }
        QNUM=200; PORTS_TCP=80:65535; PORTS_UDP=443:65535; TCP_PKT_OUT=20; TCP_PKT_IN=10; UDP_PKT_OUT=20; UDP_PKT_IN=10; PKT_OUT=20; PKT_IN=10; DESYNC_MARK=0x40000000
        prepare_new_firewall_identity || exit 30
        IPV4_CONNBYTES=1; IPV4_MULTIPORT=1; IPV4_MARK=1
        IPV6_CONNBYTES=1; IPV6_MULTIPORT=1; IPV6_MARK=1
        prepare_owner_generation_spec 1 0 || exit 31
        write_owner_state 999999 1 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 200 boot-recovery-owner active || exit 32
        write_numeric_pidfile 999999 || exit 33
        read_owner_state || exit 34
        printf '%s\n' 'status=ok' 'qnum=200' > "$STATUS_SNAPSHOT"
        chmod 0600 "$STATUS_SNAPSHOT"
    ) || fail "$mode fixture owner publication failed"
}

run_case() {
    mode="$1"
    module="$CASE/$mode/module"
    state="$CASE/$mode/state"
    prepare_case "$mode"
    Z2_BOOT_MUTATION_LOG="$MUTATION_LOG" STATE_DIR="$state" PATH="$MOCK:$PATH" \
        sh "$module/service.sh" > "$CASE/$mode/service.out" 2>&1 ||
        fail "$mode boot service rejected clean cross-boot recovery"
    [ ! -e "$state/owner.meta" ] && [ ! -L "$state/owner.meta" ] || fail "$mode retained stale owner"
    [ ! -e "$state/nfqws2.pid" ] && [ ! -L "$state/nfqws2.pid" ] || fail "$mode retained stale pidfile"
    [ ! -e "$state/status.snapshot" ] && [ ! -L "$state/status.snapshot" ] || fail "$mode retained stale status"
    [ ! -e "$state/firewall-teardown.wal" ] && [ ! -L "$state/firewall-teardown.wal" ] ||
        fail "$mode created an obsolete firewall WAL"
    [ ! -e "$state/lifecycle.lock" ] && [ ! -L "$state/lifecycle.lock" ] || fail "$mode leaked lifecycle lock"
    [ ! -e "$MUTATION_LOG.daemon" ] || fail "$mode started nfqws2"
    [ ! -s "$MUTATION_LOG" ] || fail "$mode attempted a firewall mutation"
}

run_case disabled
run_case autostart-off

echo "Disabled/autostart-off boot recovery shell tests passed"
