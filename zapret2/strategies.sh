#!/system/bin/sh
##########################################################################################
# Zapret2 Strategy Definitions
# Each function returns nfqws2 options for specific strategy
##########################################################################################

# Get strategy options by ID
get_strategy_options() {
    strategy_id="$1"
    filter="$2"  # e.g., "--filter-tcp=443 --filter-l7=tls"

    case "$strategy_id" in
        # ==================== SYNDATA STRATEGIES ====================
        syndata_7_tls_google_multisplit_midsld)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
            ;;

        syndata_7_tls_max_ru_multisplit_midsld)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_max \
--lua-desync=multisplit:pos=midsld"
            ;;

        syndata_multidisorder_legacy_midsld)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder_legacy:pos=1,midsld"
            ;;

        # ==================== CENSORLIBER STRATEGIES ====================
        censorliber_google_syndata)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:ip_ttl=-1,3-20:ip6_autottl=-1,3-20 \
--lua-desync=multidisorder:pos=1,midsld:seqovl=680:seqovl_pattern=tls_google:ip_ttl=-1,3-20:ip6_ttl=-1,3-20"
            ;;

        censorliber_google_syndata_v2)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder:pos=1,midsld:seqovl=680:seqovl_pattern=tls_google:tls_mod=rnd,rndsni,dupsid:ip_ttl=-1,3-20:ip6_ttl=-1,3-20"
            ;;

        censorliber_google_syndata_tcpack)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder:pos=1,midsld:seqovl=680:seqovl_pattern=tls_google:tls_mod=rnd,rndsni,dupsid:tcp_ack=-66000:ip_ttl=-1,3-20:ip6_ttl=-1,3-20"
            ;;

        censorliber_tls_google_syndata_tcpack_fake)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:ip_autottl=-1,3-20:ip6_autottl=-1,3-20:repeats=6:tcp_ack=-66000"
            ;;

        # ==================== ALT STRATEGIES ====================
        alt9)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=hostfakesplit:host=ozon.ru:tcp_ts=-1000:tcp_md5:repeats=4"
            ;;

        alt11_100_syndata)
            echo "$filter \
--out-range=-d4 \
--payload=tls_client_hello \
--lua-desync=send:repeats=2 \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:repeats=6:tcp_ack=-66000 \
--lua-desync=fake:blob=tls_google:ip_autottl=-1,3-20:ip6_autottl=-1,3-20:repeats=6:tcp_ack=-66000 \
--lua-desync=multisplit:pos=2:seqovl=652:seqovl_pattern=tls_google"
            ;;

        # ==================== MULTISPLIT STRATEGIES ====================
        multisplit_split_pos_1)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=multisplit:pos=1"
            ;;

        multisplit_midsld)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=multisplit:pos=midsld"
            ;;

        multidisorder_midsld)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        # ==================== SEQOVL STRATEGIES ====================
        other_seqovl)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=fake:blob=tls_google:tcp_md5:repeats=6 \
--lua-desync=multisplit:pos=midsld:seqovl=700:seqovl_pattern=tls_google"
            ;;

        disorder2_badseq_tls_google)
            echo "$filter \
--out-range=-d10 \
--payload=tls_client_hello \
--lua-desync=fake:blob=tls_google:tcp_seq=-10000:repeats=6 \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        # ==================== HTTP STRATEGIES ====================
        http_aggressive)
            echo "$filter \
--out-range=-d10 \
--payload=http_req \
--lua-desync=fake:blob=fake_default_http:tcp_md5 \
--lua-desync=multisplit:pos=method+2"
            ;;

        http_simple)
            echo "$filter \
--out-range=-d10 \
--payload=http_req \
--lua-desync=multisplit:pos=host+1"
            ;;

        # ==================== UDP/QUIC STRATEGIES ====================
        fake_x6_quic)
            echo "$filter \
--payload=quic_initial \
--lua-desync=fake:blob=fake_default_quic:repeats=6"
            ;;

        fake_x11_quic)
            echo "$filter \
--payload=quic_initial \
--lua-desync=fake:blob=fake_default_quic:repeats=11"
            ;;

        fake_x6_stun_discord)
            echo "$filter \
--payload=stun \
--lua-desync=fake:blob=fake_stun:repeats=6"
            ;;

        # ==================== DISABLED ====================
        none|disabled|"")
            echo ""
            ;;

        *)
            # Unknown strategy - return empty
            echo ""
            ;;
    esac
}

# Get HTTP filter for category
get_http_filter() {
    category="$1"

    case "$category" in
        youtube)
            echo "--filter-tcp=80 --filter-l7=http --hostlist=$LISTS_DIR/youtube.txt"
            ;;
        discord)
            echo "--filter-tcp=80 --filter-l7=http --hostlist=$LISTS_DIR/discord.txt"
            ;;
        telegram)
            echo "--filter-tcp=80 --filter-l7=http --hostlist=$LISTS_DIR/telegram.txt"
            ;;
        other)
            echo "--filter-tcp=80 --filter-l7=http --hostlist=$LISTS_DIR/other.txt"
            ;;
        *)
            echo "--filter-tcp=80 --filter-l7=http"
            ;;
    esac
}

# Get TLS filter for category
get_tls_filter() {
    category="$1"

    case "$category" in
        youtube)
            echo "--filter-tcp=443 --filter-l7=tls --hostlist=$LISTS_DIR/youtube.txt"
            ;;
        discord)
            echo "--filter-tcp=443 --filter-l7=tls --hostlist=$LISTS_DIR/discord.txt"
            ;;
        telegram)
            echo "--filter-tcp=443 --filter-l7=tls --hostlist=$LISTS_DIR/telegram.txt"
            ;;
        other)
            echo "--filter-tcp=443 --filter-l7=tls --hostlist=$LISTS_DIR/other.txt"
            ;;
        *)
            echo "--filter-tcp=443 --filter-l7=tls"
            ;;
    esac
}

# Get UDP filter for category
get_udp_filter() {
    category="$1"

    case "$category" in
        youtube)
            echo "--filter-udp=443 --filter-l7=quic"
            ;;
        discord)
            echo "--filter-udp=443 --filter-l7=quic"
            ;;
        discord_voice)
            echo "--filter-udp=19294-50100"
            ;;
        *)
            echo "--filter-udp=443 --filter-l7=quic"
            ;;
    esac
}
