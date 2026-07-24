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
Z2_FW_FAILURE_CLASS=""
Z2_FW_ERROR_DETAIL=""
Z2_FW_FALLBACK_DETAIL=""
Z2_FW_LAST_RESTORE_EXIT=0
Z2_FW_LAST_RESTORE_DETAIL=""
Z2_FW_LAST_FAILURE_CLASS=""
Z2_FW_RESTORE_WAIT_IPTABLES=unknown
Z2_FW_RESTORE_WAIT_IP6TABLES=unknown

# iptables-restore gained native xtables-lock waiting later than the oldest
# Android release supported by the module. Prefer the backend's own lock wait
# when advertised. Older/vendor backends receive the same bounded wait only
# after they explicitly report the xtables lock as busy.
Z2_FW_LOCK_WAIT_SECONDS=5
Z2_FW_DIAGNOSTIC_MAX_BYTES=384

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

z2_fw_restore_supports_wait() {
    local restore="$1" cached
    case "$restore" in
        iptables-restore) cached="$Z2_FW_RESTORE_WAIT_IPTABLES" ;;
        ip6tables-restore) cached="$Z2_FW_RESTORE_WAIT_IP6TABLES" ;;
        *) return 1 ;;
    esac
    if [ "$cached" = unknown ]; then
        if "$restore" --help 2>&1 | grep -Fq -- '--wait'; then
            cached=1
        else
            cached=0
        fi
        case "$restore" in
            iptables-restore) Z2_FW_RESTORE_WAIT_IPTABLES="$cached" ;;
            ip6tables-restore) Z2_FW_RESTORE_WAIT_IP6TABLES="$cached" ;;
        esac
    fi
    [ "$cached" = 1 ]
}

z2_fw_reset_restore_wait_capabilities() {
    Z2_FW_RESTORE_WAIT_IPTABLES=unknown
    Z2_FW_RESTORE_WAIT_IP6TABLES=unknown
}

z2_fw_normalize_diagnostic() {
    local LC_ALL=C
    printf '%s' "$1" | tr '[:cntrl:]' ' ' | cut -b "1-$Z2_FW_DIAGNOSTIC_MAX_BYTES"
}

z2_fw_read_restore_diagnostic() {
    local path="$1" detail
    detail="$(tail -c "$Z2_FW_DIAGNOSTIC_MAX_BYTES" "$path" 2>/dev/null)" || detail=""
    z2_fw_normalize_diagnostic "$detail"
}

z2_fw_diagnostic_is_lock_busy() {
    case "$1" in
        *xtables*lock*|*XTABLES*lock*|*Another\ app*holding*lock*|\
        *another\ app*holding*lock*|*lock*temporarily\ unavailable*|\
        *lock*busy*) return 0 ;;
        *) return 1 ;;
    esac
}

z2_fw_lock_retry_pause() {
    sleep 1
}

z2_fw_run_restore() {
    local restore="$1" tool="$2" phase="$3" batch="$4"
    local capture wait_supported=0 attempts=0 rc=1 cleanup_rc=0 detail
    capture="$STATE_DIR/firewall-restore.${tool}.$$.error"
    Z2_FW_LAST_RESTORE_EXIT=0
    Z2_FW_LAST_RESTORE_DETAIL=""
    Z2_FW_LAST_FAILURE_CLASS=""
    state_path_is_managed_file "$capture" || {
        Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_LAST_RESTORE_DETAIL="unsafe firewall diagnostic path"
        return 1
    }
    if [ -e "$capture" ] || [ -L "$capture" ]; then
        Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_LAST_RESTORE_DETAIL="firewall diagnostic path already exists"
        return 1
    fi
    umask 077
    if ! : > "$capture" || ! chmod 0600 "$capture" 2>/dev/null; then
        rm -f "$capture" 2>/dev/null
        Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_LAST_RESTORE_DETAIL="cannot create private firewall diagnostic capture"
        return 1
    fi
    z2_fw_restore_supports_wait "$restore" && wait_supported=1

    while :; do
        : > "$capture" || {
            rc=1
            Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
            Z2_FW_LAST_RESTORE_DETAIL="cannot reset firewall diagnostic capture"
            break
        }
        if [ "$wait_supported" = 1 ]; then
            if [ "$phase" = test ]; then
                "$restore" --wait "$Z2_FW_LOCK_WAIT_SECONDS" --test --noflush \
                    < "$batch" >/dev/null 2>"$capture"
            else
                "$restore" --wait "$Z2_FW_LOCK_WAIT_SECONDS" --noflush \
                    < "$batch" >/dev/null 2>"$capture"
            fi
        elif [ "$phase" = test ]; then
            "$restore" --test --noflush < "$batch" >/dev/null 2>"$capture"
        else
            "$restore" --noflush < "$batch" >/dev/null 2>"$capture"
        fi
        rc=$?
        detail="$(z2_fw_read_restore_diagnostic "$capture")"
        [ "$rc" -ne 0 ] || break
        if [ "$wait_supported" = 0 ] && [ "$rc" -eq 4 ] 2>/dev/null &&
           z2_fw_diagnostic_is_lock_busy "$detail" &&
           [ "$attempts" -lt "$Z2_FW_LOCK_WAIT_SECONDS" ] 2>/dev/null; then
            attempts=$((attempts + 1))
            if ! z2_fw_lock_retry_pause; then
                Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
                Z2_FW_LAST_RESTORE_DETAIL="xtables lock wait could not be scheduled"
                rc=1
                break
            fi
            continue
        fi
        break
    done

    Z2_FW_LAST_RESTORE_EXIT="$rc"
    if [ -z "$Z2_FW_LAST_RESTORE_DETAIL" ]; then
        Z2_FW_LAST_RESTORE_DETAIL="$(z2_fw_read_restore_diagnostic "$capture")"
    fi
    rm -f "$capture" 2>/dev/null || cleanup_rc=1
    if [ "$cleanup_rc" -ne 0 ]; then
        Z2_FW_LAST_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_LAST_RESTORE_DETAIL="cannot remove private firewall diagnostic capture"
        return 1
    fi
    [ "$rc" -eq 0 ] 2>/dev/null && return 0
    if [ -z "$Z2_FW_LAST_FAILURE_CLASS" ]; then
        if z2_fw_diagnostic_is_lock_busy "$Z2_FW_LAST_RESTORE_DETAIL"; then
            Z2_FW_LAST_FAILURE_CLASS=LOCK_TIMEOUT
        elif [ "$phase" = test ]; then
            Z2_FW_LAST_FAILURE_CLASS=RULESET_REJECTED
        else
            Z2_FW_LAST_FAILURE_CLASS=PUBLICATION_FAILED
        fi
    fi
    return "$rc"
}

z2_fw_set_restore_failure() {
    local restore="$1" phase="$2" connbytes="$3" detail
    detail="${Z2_FW_LAST_RESTORE_DETAIL:-no backend diagnostic}"
    Z2_FW_FAILURE_CLASS="${Z2_FW_LAST_FAILURE_CLASS:-PUBLICATION_FAILED}"
    Z2_FW_ERROR_DETAIL="$restore $phase failed (connbytes=$connbytes, exit=$Z2_FW_LAST_RESTORE_EXIT): $detail"
    Z2_FW_ERROR_DETAIL="$(z2_fw_normalize_diagnostic "$Z2_FW_ERROR_DETAIL")"
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
    local tool="$1" connbytes="$2" restore batch
    Z2_FW_FAILURE_CLASS=""
    Z2_FW_ERROR_DETAIL=""
    restore="$(z2_fw_restore_command "$tool")" || return 2
    command -v "$restore" >/dev/null 2>&1 || return 3
    batch="$STATE_DIR/firewall-batch.${tool}.$$"
    state_path_is_managed_file "$batch" || {
        Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="unsafe firewall batch path"
        return 1
    }
    if [ -e "$batch" ] || [ -L "$batch" ]; then
        Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="firewall batch path already exists"
        return 1
    fi
    umask 077
    z2_fw_write_batch "$batch" "$connbytes" || {
        rm -f "$batch" 2>/dev/null
        Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="cannot create firewall batch"
        return 1
    }
    chmod 0600 "$batch" 2>/dev/null || {
        rm -f "$batch" 2>/dev/null
        Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="cannot secure firewall batch"
        return 1
    }
    if z2_fw_run_restore "$restore" "$tool" test "$batch"; then
        :
    else
        z2_fw_set_restore_failure "$restore" test "$connbytes"
        rm -f "$batch" 2>/dev/null || {
            Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
            Z2_FW_ERROR_DETAIL="cannot remove rejected firewall batch"
            return 1
        }
        [ "$Z2_FW_FAILURE_CLASS" = RULESET_REJECTED ] && return 4
        return 1
    fi
    if z2_fw_run_restore "$restore" "$tool" commit "$batch"; then
        :
    else
        z2_fw_set_restore_failure "$restore" commit "$connbytes"
        rm -f "$batch" 2>/dev/null || {
            Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
            Z2_FW_ERROR_DETAIL="cannot remove failed firewall batch"
            return 1
        }
        return 1
    fi
    rm -f "$batch" 2>/dev/null || {
        Z2_FW_FAILURE_CLASS=STATE_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="cannot remove committed firewall batch"
        return 1
    }
    Z2_FW_BACKEND=restore
    return 0
}

z2_fw_expected_rule_count() {
    local connbytes="$1" per_direction=0
    [ -z "$PORTS_TCP" ] || per_direction=$((per_direction + 1))
    [ -z "$PORTS_UDP" ] || per_direction=$((per_direction + 1))
    printf '%s\n' $((per_direction * (1 + connbytes)))
}

z2_fw_verify_family() {
    local tool="$1" connbytes="$2" listing
    local out_tcp out_udp in_tcp in_udp
    listing="$("$tool" -t mangle -S 2>/dev/null)" || return 1
    out_tcp="$(z2_fw_emit_batch_rule "$Z2_FW_OUT_CHAIN" tcp out "$PORTS_TCP" "$TCP_PKT_OUT" original "$connbytes")" || return 1
    out_udp="$(z2_fw_emit_batch_rule "$Z2_FW_OUT_CHAIN" udp out "$PORTS_UDP" "$UDP_PKT_OUT" original "$connbytes")" || return 1
    in_tcp=""
    in_udp=""
    if [ "$connbytes" = 1 ]; then
        in_tcp="$(z2_fw_emit_batch_rule "$Z2_FW_IN_CHAIN" tcp in "$PORTS_TCP" "$TCP_PKT_IN" reply 1)" || return 1
        in_udp="$(z2_fw_emit_batch_rule "$Z2_FW_IN_CHAIN" udp in "$PORTS_UDP" "$UDP_PKT_IN" reply 1)" || return 1
    fi
    printf '%s\n' "$listing" | awk \
        -v out="$Z2_FW_OUT_CHAIN" -v inchain="$Z2_FW_IN_CHAIN" \
        -v connbytes="$connbytes" \
        -v out_tcp="$out_tcp" -v out_udp="$out_udp" \
        -v in_tcp="$in_tcp" -v in_udp="$in_udp" '
        function required_seen(expected, seen) {
            return expected == "" ? seen == 0 : seen == 1
        }
        $1 == "-N" && $2 == out { out_chain++ }
        $1 == "-N" && $2 == inchain { in_chain++ }
        $1 == "-A" && $2 == out {
            out_rules++
            if ($0 == out_tcp && out_tcp != "") out_tcp_seen++
            else if ($0 == out_udp && out_udp != "") out_udp_seen++
            else bad=1
        }
        $1 == "-A" && $2 == inchain {
            in_rules++
            if ($0 == in_tcp && in_tcp != "") in_tcp_seen++
            else if ($0 == in_udp && in_udp != "") in_udp_seen++
            else bad=1
        }
        $1 == "-A" {
            for (i=3; i<=NF; i++) {
                if ($i != "-j" && $i != "--jump" &&
                    $i != "-g" && $i != "--goto") continue
                target=$(i+1)
                if (target == out) {
                    if ($0 == "-A OUTPUT -j " out) out_anchor++
                    else bad=1
                } else if (target == inchain) {
                    if ($0 == "-A INPUT -j " inchain) in_anchor++
                    else bad=1
                }
            }
        }
        END {
            expected_out=(out_tcp != "") + (out_udp != "")
            expected_in=(in_tcp != "") + (in_udp != "")
            ok = !bad && out_chain == 1 && out_anchor == 1 &&
                out_rules == expected_out &&
                required_seen(out_tcp, out_tcp_seen) &&
                required_seen(out_udp, out_udp_seen)
            if (connbytes == 1) {
                ok = ok && in_chain == 1 && in_anchor == 1 &&
                    in_rules == expected_in &&
                    required_seen(in_tcp, in_tcp_seen) &&
                    required_seen(in_udp, in_udp_seen)
            } else {
                ok = ok && in_chain == 0 && in_anchor == 0 && in_rules == 0
            }
            exit ok ? 0 : 1
        }' || return 1
    Z2_FW_CONNBYTES="$connbytes"
    Z2_FW_RULES="$(z2_fw_expected_rule_count "$connbytes")" || return 1
    Z2_FW_CHAINS=$((1 + connbytes))
    Z2_FW_ANCHORS=$((1 + connbytes))
    return 0
}

z2_fw_reconcile_family() {
    local tool="$1" cleanup_mode="${2:-owned}" apply_rc candidate_detail
    case "$cleanup_mode" in owned|precleaned) ;; *) return 2 ;; esac
    Z2_FW_BACKEND=""; Z2_FW_CONNBYTES=0
    Z2_FW_RULES=0; Z2_FW_CHAINS=0; Z2_FW_ANCHORS=0
    Z2_FW_FAILURE_CLASS=""; Z2_FW_ERROR_DETAIL=""; Z2_FW_FALLBACK_DETAIL=""
    z2_fw_tool_available "$tool" || {
        Z2_FW_FAILURE_CLASS=BACKEND_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="$tool mangle backend is unavailable"
        return 2
    }
    z2_fw_restore_available "$tool" || {
        Z2_FW_FAILURE_CLASS=BACKEND_UNAVAILABLE
        Z2_FW_ERROR_DETAIL="$tool restore backend is unavailable"
        return 3
    }
    if [ "$cleanup_mode" = owned ]; then
        z2_fw_cleanup_family "$tool" || {
            Z2_FW_FAILURE_CLASS=CLEANUP_FAILED
            Z2_FW_ERROR_DETAIL="cannot clean the $tool stable firewall namespace before publication"
            return 1
        }
    fi
    if z2_fw_apply_restore "$tool" 1; then
        apply_rc=0
    else
        apply_rc=$?
    fi
    if [ "$apply_rc" = 0 ]; then
        Z2_FW_CONNBYTES=1
        Z2_FW_RULES="$(z2_fw_expected_rule_count 1)" || return 1
        Z2_FW_CHAINS=2
        Z2_FW_ANCHORS=2
        Z2_FW_FAILURE_CLASS=""; Z2_FW_ERROR_DETAIL=""
        return 0
    fi
    candidate_detail="$Z2_FW_ERROR_DETAIL"
    z2_fw_cleanup_family "$tool" >/dev/null 2>&1 || {
        Z2_FW_FAILURE_CLASS=CLEANUP_FAILED
        Z2_FW_ERROR_DETAIL="cannot clean the $tool namespace after failed publication"
        return 1
    }
    # Only candidate rejection is a capability signal. A failed COMMIT or a
    # failed postcondition is a publication error and must not silently alter
    # the intended topology.
    [ "$apply_rc" = 4 ] || return 1
    if z2_fw_apply_restore "$tool" 0; then
        Z2_FW_CONNBYTES=0
        Z2_FW_RULES="$(z2_fw_expected_rule_count 0)" || return 1
        Z2_FW_CHAINS=1
        Z2_FW_ANCHORS=1
        Z2_FW_FALLBACK_DETAIL="$candidate_detail"
        Z2_FW_FAILURE_CLASS=""; Z2_FW_ERROR_DETAIL=""
        return 0
    fi
    if [ -z "$Z2_FW_ERROR_DETAIL" ]; then
        Z2_FW_FAILURE_CLASS=POSTCONDITION_FAILED
        Z2_FW_ERROR_DETAIL="$tool post-publication verification failed (connbytes=0)"
    fi
    z2_fw_cleanup_family "$tool" >/dev/null 2>&1 || {
        Z2_FW_FAILURE_CLASS=CLEANUP_FAILED
        Z2_FW_ERROR_DETAIL="cannot clean the $tool namespace after fallback failure"
    }
    return 1
}
