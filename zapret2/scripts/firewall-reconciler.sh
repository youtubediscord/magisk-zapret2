#!/system/bin/sh
# Idempotent boot-local firewall reconciler.
#
# The stable ZAPRET2_OUT/ZAPRET2_IN namespace is exclusively owned by this
# module. A complete ruleset is derived from the compiled preset on every
# start. There is deliberately no firewall WAL: iptables-restore validates the
# complete candidate and publishes it at COMMIT. Any interruption is recovered
# by repeating z2_fw_cleanup_family under the lifecycle lock.

Z2_FW_OUT_CHAIN="${Z2_FW_OUT_CHAIN:-ZAPRET2_OUT}"
Z2_FW_IN_CHAIN="${Z2_FW_IN_CHAIN:-ZAPRET2_IN}"
Z2_FW_BACKEND=""
Z2_FW_CONNBYTES=0
Z2_FW_RULES=0
Z2_FW_CHAINS=0
Z2_FW_ANCHORS=0

z2_fw_restore_command() {
    case "$1" in
        iptables) printf '%s\n' iptables-restore ;;
        ip6tables) printf '%s\n' ip6tables-restore ;;
        *) return 1 ;;
    esac
}

z2_fw_restore_available() {
    local restore
    restore="$(z2_fw_restore_command "$1")" || return 1
    command -v "$restore" >/dev/null 2>&1
}

z2_fw_tool_available() {
    command -v "$1" >/dev/null 2>&1 &&
        "$1" -t mangle -L OUTPUT -n >/dev/null 2>&1
}

z2_fw_cleanup_is_unambiguous() {
    local tool="$1" listing
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    printf '%s\n' "$listing" |
        awk -v out="$Z2_FW_OUT_CHAIN" -v inchain="$Z2_FW_IN_CHAIN" '
            $1 == "-A" {
                for (i = 3; i <= NF; i++) {
                    if ($i != "-j" && $i != "--jump" &&
                        $i != "-g" && $i != "--goto") continue
                    target = $(i + 1)
                    if (target == out &&
                        !($2 == "OUTPUT" && NF == 4 && $3 == "-j")) bad = 1
                    if (target == inchain &&
                        !($2 == "INPUT" && NF == 4 && $3 == "-j")) bad = 1
                }
            }
            END { exit bad ? 1 : 0 }
        '
}

z2_fw_delete_anchors() {
    local tool="$1" builtin="$2" chain="$3" count=0
    while "$tool" -t mangle -C "$builtin" -j "$chain" >/dev/null 2>&1; do
        [ "$count" -lt 8 ] || return 1
        "$tool" -t mangle -D "$builtin" -j "$chain" >/dev/null 2>&1 || return 1
        count=$((count + 1))
    done
    return 0
}

z2_fw_drop_chain() {
    local tool="$1" chain="$2"
    "$tool" -t mangle -S "$chain" >/dev/null 2>&1 || return 0
    "$tool" -t mangle -F "$chain" >/dev/null 2>&1 || return 1
    "$tool" -t mangle -X "$chain" >/dev/null 2>&1 || return 1
    ! "$tool" -t mangle -S "$chain" >/dev/null 2>&1
}

z2_fw_cleanup_family() {
    local tool="$1" rc=0
    z2_fw_tool_available "$tool" || return 2
    z2_fw_cleanup_is_unambiguous "$tool" || return 1
    z2_fw_delete_anchors "$tool" OUTPUT "$Z2_FW_OUT_CHAIN" || rc=1
    z2_fw_delete_anchors "$tool" INPUT "$Z2_FW_IN_CHAIN" || rc=1
    z2_fw_drop_chain "$tool" "$Z2_FW_IN_CHAIN" || rc=1
    z2_fw_drop_chain "$tool" "$Z2_FW_OUT_CHAIN" || rc=1
    return "$rc"
}

z2_fw_family_absent() {
    local tool="$1"
    z2_fw_tool_available "$tool" || return 2
    ! "$tool" -t mangle -C OUTPUT -j "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
        ! "$tool" -t mangle -C INPUT -j "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 &&
        ! "$tool" -t mangle -S "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 &&
        ! "$tool" -t mangle -S "$Z2_FW_IN_CHAIN" >/dev/null 2>&1
}

z2_fw_emit_batch_rule() {
    local chain="$1" proto="$2" direction="$3" ports="$4"
    local packet_count="$5" cb_dir="$6" connbytes="$7"
    [ -n "$ports" ] || return 0
    printf '%s' "-A $chain -p $proto -m multiport"
    if [ "$direction" = out ]; then
        printf '%s' " --dports $ports"
    else
        printf '%s' " --sports $ports"
    fi
    if [ "$connbytes" = 1 ]; then
        printf '%s' " -m connbytes --connbytes 1:$packet_count --connbytes-dir $cb_dir --connbytes-mode packets"
    fi
    printf '%s\n' " -m mark ! --mark $DESYNC_MARK/$DESYNC_MARK -j NFQUEUE --queue-num $QNUM --queue-bypass"
}

z2_fw_write_batch() {
    local path="$1" connbytes="$2"
    {
        printf '%s\n' '*mangle'
        printf ':%s - [0:0]\n' "$Z2_FW_OUT_CHAIN"
        [ "$connbytes" != 1 ] || printf ':%s - [0:0]\n' "$Z2_FW_IN_CHAIN"
        z2_fw_emit_batch_rule "$Z2_FW_OUT_CHAIN" tcp out "$PORTS_TCP" "$TCP_PKT_OUT" original "$connbytes"
        z2_fw_emit_batch_rule "$Z2_FW_OUT_CHAIN" udp out "$PORTS_UDP" "$UDP_PKT_OUT" original "$connbytes"
        if [ "$connbytes" = 1 ]; then
            z2_fw_emit_batch_rule "$Z2_FW_IN_CHAIN" tcp in "$PORTS_TCP" "$TCP_PKT_IN" reply 1
            z2_fw_emit_batch_rule "$Z2_FW_IN_CHAIN" udp in "$PORTS_UDP" "$UDP_PKT_IN" reply 1
        fi
        printf '%s\n' "-A OUTPUT -j $Z2_FW_OUT_CHAIN"
        [ "$connbytes" != 1 ] || printf '%s\n' "-A INPUT -j $Z2_FW_IN_CHAIN"
        printf '%s\n' COMMIT
    } > "$path"
}

z2_fw_apply_restore() {
    local tool="$1" connbytes="$2" restore batch rc
    restore="$(z2_fw_restore_command "$tool")" || return 2
    command -v "$restore" >/dev/null 2>&1 || return 3
    batch="$STATE_DIR/firewall-batch.${tool}.$$"
    state_path_is_managed_file "$batch" || return 1
    [ ! -e "$batch" ] && [ ! -L "$batch" ] || return 1
    umask 077
    z2_fw_write_batch "$batch" "$connbytes" || { rm -f "$batch"; return 1; }
    chmod 0600 "$batch" 2>/dev/null || { rm -f "$batch"; return 1; }
    "$restore" --test --noflush < "$batch" >/dev/null 2>&1 || {
        rm -f "$batch"
        return 4
    }
    "$restore" --wait 5 --noflush < "$batch" >/dev/null 2>&1
    rc=$?
    rm -f "$batch" 2>/dev/null || rc=1
    [ "$rc" -eq 0 ] || return 1
    Z2_FW_BACKEND=restore
    return 0
}

z2_fw_expected_rule_count() {
    local connbytes="$1" per_direction=0
    [ -z "$PORTS_TCP" ] || per_direction=$((per_direction + 1))
    [ -z "$PORTS_UDP" ] || per_direction=$((per_direction + 1))
    printf '%s\n' $((per_direction * (1 + connbytes)))
}

z2_fw_verify_rule() {
    local tool="$1" chain="$2" proto="$3" direction="$4" ports="$5"
    local packet_count="$6" cb_dir="$7" connbytes="$8"
    [ -n "$ports" ] || return 0
    set -- "$tool" -t mangle -C "$chain" -p "$proto" -m multiport
    if [ "$direction" = out ]; then
        set -- "$@" --dports "$ports"
    else
        set -- "$@" --sports "$ports"
    fi
    if [ "$connbytes" = 1 ]; then
        set -- "$@" -m connbytes --connbytes "1:$packet_count" \
            --connbytes-dir "$cb_dir" --connbytes-mode packets
    fi
    set -- "$@" -m mark ! --mark "$DESYNC_MARK/$DESYNC_MARK" \
        -j NFQUEUE --queue-num "$QNUM" --queue-bypass
    "$@" >/dev/null 2>&1
}

z2_fw_chain_rule_count() {
    local tool="$1" chain="$2"
    "$tool" -t mangle -S "$chain" 2>/dev/null |
        awk -v chain="$chain" '$1 == "-A" && $2 == chain { count++ } END { print count + 0 }'
}

z2_fw_verify_family() {
    local tool="$1" connbytes="$2" expected actual
    "$tool" -t mangle -C OUTPUT -j "$Z2_FW_OUT_CHAIN" >/dev/null 2>&1 || return 1
    z2_fw_verify_rule "$tool" "$Z2_FW_OUT_CHAIN" tcp out "$PORTS_TCP" "$TCP_PKT_OUT" original "$connbytes" ||
        return 1
    z2_fw_verify_rule "$tool" "$Z2_FW_OUT_CHAIN" udp out "$PORTS_UDP" "$UDP_PKT_OUT" original "$connbytes" ||
        return 1
    expected=0
    [ -z "$PORTS_TCP" ] || expected=$((expected + 1))
    [ -z "$PORTS_UDP" ] || expected=$((expected + 1))
    actual="$(z2_fw_chain_rule_count "$tool" "$Z2_FW_OUT_CHAIN")" || return 1
    [ "$actual" = "$expected" ] || return 1
    if [ "$connbytes" = 1 ]; then
        "$tool" -t mangle -C INPUT -j "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 || return 1
        z2_fw_verify_rule "$tool" "$Z2_FW_IN_CHAIN" tcp in "$PORTS_TCP" "$TCP_PKT_IN" reply 1 ||
            return 1
        z2_fw_verify_rule "$tool" "$Z2_FW_IN_CHAIN" udp in "$PORTS_UDP" "$UDP_PKT_IN" reply 1 ||
            return 1
        actual="$(z2_fw_chain_rule_count "$tool" "$Z2_FW_IN_CHAIN")" || return 1
        [ "$actual" = "$expected" ] || return 1
    else
        ! "$tool" -t mangle -C INPUT -j "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 || return 1
        ! "$tool" -t mangle -S "$Z2_FW_IN_CHAIN" >/dev/null 2>&1 || return 1
    fi
    Z2_FW_CONNBYTES="$connbytes"
    Z2_FW_RULES="$(z2_fw_expected_rule_count "$connbytes")" || return 1
    Z2_FW_CHAINS=$((1 + connbytes))
    Z2_FW_ANCHORS=$((1 + connbytes))
    return 0
}

z2_fw_reconcile_family() {
    local tool="$1" apply_rc
    Z2_FW_BACKEND=""; Z2_FW_CONNBYTES=0
    Z2_FW_RULES=0; Z2_FW_CHAINS=0; Z2_FW_ANCHORS=0
    z2_fw_tool_available "$tool" || return 2
    z2_fw_restore_available "$tool" || return 3
    z2_fw_cleanup_family "$tool" || return 1
    if z2_fw_apply_restore "$tool" 1; then
        apply_rc=0
    else
        apply_rc=$?
    fi
    if [ "$apply_rc" = 0 ] && z2_fw_verify_family "$tool" 1; then
        return 0
    fi
    z2_fw_cleanup_family "$tool" >/dev/null 2>&1 || return 1
    # Only candidate rejection is a capability signal. A failed COMMIT or a
    # failed postcondition is a publication error and must not silently alter
    # the intended topology.
    [ "$apply_rc" = 4 ] || return 1
    if z2_fw_apply_restore "$tool" 0 &&
       z2_fw_verify_family "$tool" 0; then
        return 0
    fi
    z2_fw_cleanup_family "$tool" >/dev/null 2>&1
    return 1
}
