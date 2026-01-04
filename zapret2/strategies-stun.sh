#!/system/bin/sh
##########################################################################################
# Zapret2 STUN Strategy Definitions
# STUN/Voice strategies for Discord, Telegram, WhatsApp voice calls
#
# Usage:
#   get_stun_strategy_options "strategy_name" "$filter"
#   list_stun_strategies
##########################################################################################

LISTS_DIR="$ZAPRET_DIR/lists"

# List all available STUN strategies
list_stun_strategies() {
    echo "disabled fake_x6_stun_discord fakeknown_x6_stun_discord fake_x6_30_out_stun_discord fakeknown_x6_30_out_stun_discord fake_x6_2plus_stun_discord fakeknown_x6_2plus_stun_discord fakeknown_x3_stun_discord fake_x3_stun_discord fakeknown_x6_pad_stun_discord fake_x6_pad_stun_discord fakeknown_x6_10_out_stun_discord fake_x6_10_out_stun_discord fakeknown_x6_20_out_stun_discord fake_x6_20_out_stun_discord fakeknown_x3_2plus_stun_discord fake_x3_2plus_stun_discord fakeknown_x3_30_out_stun_discord fake_x3_30_out_stun_discord fakeknown_x3_pad_stun_discord fake_x3_pad_stun_discord fakeknown_x6_50_out_stun_discord fake_x6_50_out_stun_discord fakeknown_x3_10_out_stun_discord fake_x3_10_out_stun_discord fakeknown_x3_20_out_stun_discord fake_x3_20_out_stun_discord fakeknown_x10_stun_discord fake_x10_stun_discord fakeknown_x3_50_out_stun_discord fake_x3_50_out_stun_discord fakeknown_x6_100_out_stun_discord fake_x6_100_out_stun_discord fakeknown_x3_100_out_stun_discord fake_x3_100_out_stun_discord fake_x10_30_out_stun_discord fakeknown_x10_30_out_stun_discord fake_x10_2plus_stun_discord fakeknown_x10_2plus_stun_discord fake_x10_pad_stun_discord fakeknown_x10_pad_stun_discord fake_x10_10_out_stun_discord fakeknown_x10_10_out_stun_discord fake_x10_20_out_stun_discord fakeknown_x10_20_out_stun_discord fake_x10_50_out_stun_discord fakeknown_x10_50_out_stun_discord fake_x10_100_out_stun_discord fakeknown_x10_100_out_stun_discord"
}

# Get STUN strategy options by name
# Arguments: $1 = strategy_name, $2 = filter (optional)
get_stun_strategy_options() {
    local strategy_name="$1"
    local filter="$2"

    case "$strategy_name" in
        disabled)
            echo ""
            ;;

        # ==================== STUN STRATEGIES ====================

        fake_veryfast_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00000000000000000000000000000000:repeats=2"
            ;;

        fake_x6_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x6_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x6_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x6_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fakeknown_x3_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x6_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=6+udplen:delta=2"
            ;;

        fake_x6_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=6+udplen:delta=2"
            ;;

        fakeknown_x6_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x6_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x3_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x3_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x3_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=3+udplen:delta=2"
            ;;

        fake_x3_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=3+udplen:delta=2"
            ;;

        fakeknown_x6_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x3_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x3_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x10_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x3_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fakeknown_x6_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fakeknown:blob=stun:repeats=6"
            ;;

        fake_x6_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fake:blob=0x00:repeats=6"
            ;;

        fakeknown_x3_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fakeknown:blob=stun:repeats=3"
            ;;

        fake_x3_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fake:blob=0x00:repeats=3"
            ;;

        fake_x10_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_30_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d30 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_2plus_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=2+ --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=10+udplen:delta=2"
            ;;

        fakeknown_x10_pad_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=10+udplen:delta=2"
            ;;

        fake_x10_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_10_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d10 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_20_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d20 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_50_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d50 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        fake_x10_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fake:blob=0x00:repeats=10"
            ;;

        fakeknown_x10_100_out_stun_discord)
            echo "$filter --payload=stun,discord_ip_discovery --out-range=-d100 --lua-desync=fakeknown:blob=stun:repeats=10"
            ;;

        # ==================== END STUN STRATEGIES ====================

        *)
            echo ""
            ;;
    esac
}
