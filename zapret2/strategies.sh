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
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multisplit:pos=midsld"
            ;;

        syndata_7_tls_max_ru_multisplit_midsld)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_max \
--lua-desync=multisplit:pos=midsld"
            ;;

        syndata_multidisorder_legacy_midsld)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        # ==================== CENSORLIBER STRATEGIES ====================
        censorliber_google_syndata)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:repeats=6 \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        censorliber_google_syndata_v2)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        censorliber_google_syndata_tcpack)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        censorliber_tls_google_syndata_tcpack_fake)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:repeats=6"
            ;;

        # ==================== ALT STRATEGIES ====================
        alt9)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=fake:blob=tls_google:badsum:repeats=4"
            ;;

        alt11_100_syndata)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=syndata:blob=tls_google \
--lua-desync=fake:blob=tls_google:repeats=6 \
--lua-desync=multisplit:pos=midsld"
            ;;

        # ==================== MULTISPLIT STRATEGIES ====================
        multisplit_split_pos_1)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=multisplit:pos=1"
            ;;

        multisplit_midsld)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=multisplit:pos=midsld"
            ;;

        multidisorder_midsld)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        # ==================== SEQOVL STRATEGIES ====================
        other_seqovl)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=fake:blob=tls_google:badsum:repeats=6 \
--lua-desync=multisplit:pos=midsld"
            ;;

        disorder2_badseq_tls_google)
            echo "$filter \
--payload=tls_client_hello \
--lua-desync=fake:blob=tls_google:repeats=6 \
--lua-desync=multidisorder:pos=1,midsld"
            ;;

        # ==================== HTTP STRATEGIES ====================
        http_aggressive)
            echo "$filter \
--payload=http_req \
--lua-desync=fake:blob=http_fake:badsum \
--lua-desync=multisplit:pos=host+1"
            ;;

        http_simple)
            echo "$filter \
--payload=http_req \
--lua-desync=multisplit:pos=host+1"
            ;;

        # ==================== UDP/QUIC STRATEGIES ====================
        fake_x6_quic)
            echo "$filter \
--payload=quic_initial \
--lua-desync=fake:blob=quic_google:repeats=6"
            ;;

        fake_x11_quic)
            echo "$filter \
--payload=quic_initial \
--lua-desync=fake:blob=quic_google:repeats=11"
            ;;

        fake_x6_stun_discord)
            echo "$filter \
--payload=stun \
--lua-desync=fake:blob=quic1:repeats=6"
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
