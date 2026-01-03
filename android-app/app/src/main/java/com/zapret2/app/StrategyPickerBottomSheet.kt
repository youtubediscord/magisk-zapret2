package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StrategyPickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CATEGORY_KEY = "category_key"
        private const val ARG_CATEGORY_NAME = "category_name"
        private const val ARG_PROTOCOL = "protocol"
        private const val ARG_ICON_RES = "icon_res"
        private const val ARG_CURRENT_INDEX = "current_index"
        private const val ARG_STRATEGY_TYPE = "strategy_type"

        const val TYPE_TCP = "tcp"
        const val TYPE_UDP = "udp"
        const val TYPE_VOICE = "voice"
        const val TYPE_DEBUG = "debug"
        const val TYPE_PKT_COUNT = "pkt_count"

        fun newInstance(
            categoryKey: String,
            categoryName: String,
            protocol: String,
            iconRes: Int,
            currentIndex: Int,
            strategyType: String
        ): StrategyPickerBottomSheet {
            return StrategyPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY_KEY, categoryKey)
                    putString(ARG_CATEGORY_NAME, categoryName)
                    putString(ARG_PROTOCOL, protocol)
                    putInt(ARG_ICON_RES, iconRes)
                    putInt(ARG_CURRENT_INDEX, currentIndex)
                    putString(ARG_STRATEGY_TYPE, strategyType)
                }
            }
        }
    }

    private var onStrategySelected: ((Int) -> Unit)? = null

    fun setOnStrategySelectedListener(listener: (Int) -> Unit) {
        onStrategySelected = listener
    }

    override fun getTheme(): Int = R.style.Theme_Zapret2_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_strategy_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val categoryKey = arguments?.getString(ARG_CATEGORY_KEY) ?: return
        val categoryName = arguments?.getString(ARG_CATEGORY_NAME) ?: return
        val protocol = arguments?.getString(ARG_PROTOCOL) ?: ""
        val iconRes = arguments?.getInt(ARG_ICON_RES) ?: R.drawable.ic_settings
        val currentIndex = arguments?.getInt(ARG_CURRENT_INDEX) ?: 0
        val strategyType = arguments?.getString(ARG_STRATEGY_TYPE) ?: TYPE_TCP

        // Set title and icon
        view.findViewById<TextView>(R.id.sheetTitle).text = categoryName
        view.findViewById<TextView>(R.id.sheetProtocol).text = protocol
        view.findViewById<ImageView>(R.id.sheetIcon).setImageResource(iconRes)

        // Get strategies based on type
        val strategies = when (strategyType) {
            TYPE_TCP -> getTcpStrategies()
            TYPE_UDP -> getUdpStrategies()
            TYPE_VOICE -> getVoiceStrategies()
            TYPE_DEBUG -> getDebugModes()
            TYPE_PKT_COUNT -> getPktCountOptions()
            else -> getTcpStrategies()
        }

        // Setup RecyclerView
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerStrategies)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = StrategyAdapter(strategies, currentIndex) { selectedIndex ->
            onStrategySelected?.invoke(selectedIndex)
            dismiss()
        }
    }

    private fun getTcpStrategies(): List<StrategyItem> = listOf(
        StrategyItem("Отключено", "Не применять стратегию"),
        // Syndata strategies
        StrategyItem("syndata_2_tls_7", "Syndata 2 tls 7"),
        StrategyItem("syndata_3_tls_google", "Syndata 3 tls google"),
        StrategyItem("syndata_7_n3", "Syndata 7 n3"),
        StrategyItem("syndata_multisplit_tls_google_700", "Syndata multisplit tls google 700"),
        StrategyItem("syndata_multidisorder_tls_google_700", "Syndata multidisorder tls google 700"),
        StrategyItem("syndata_multisplit_tls_google_1000", "Syndata multisplit tls google 1000"),
        StrategyItem("syndata_multidisorder_tls_google_1000", "Syndata multidisorder tls google 1000"),
        StrategyItem("syndata_7_tls_google_multisplit_midsld", "Syndata 7 tls google multisplit midsld"),
        StrategyItem("syndata_7_tls_max_ru_multisplit_midsld", "Syndata 7 tls max ru multisplit midsld"),
        StrategyItem("syndata_multidisorder_legacy_midsld", "Syndata multidisorder legacy midsld"),
        // Alt strategies
        StrategyItem("alt9", "Alt9"),
        StrategyItem("alt11_100_syndata", "Alt11 100 syndata"),
        StrategyItem("syndata_general_simple_fake_165_2", "Syndata general simple fake 165 2"),
        // Censorliber strategies
        StrategyItem("censorliber_google_syndata", "Censorliber google syndata"),
        StrategyItem("censorliber_google_syndata_v2", "Censorliber google syndata v2"),
        StrategyItem("censorliber_google_syndata_tcpack", "Censorliber google syndata tcpack"),
        StrategyItem("censorliber_tls_google_syndata_tcpack_fake", "Censorliber tls google syndata tcpack fake"),
        StrategyItem("censorliber_tls_google_syndata_tcpack_fake_seqovl_1", "Censorliber tls google syndata tcpack fake seqovl 1"),
        StrategyItem("censorliber_max_ru_syndata_tcpack_fake_seqovl_1", "Censorliber max ru syndata tcpack fake seqovl 1"),
        StrategyItem("general_alt11_191_syndata", "General alt11 191 syndata"),
        // Multidisorder legacy strategies
        StrategyItem("multidisorder_legacy_midsld", "Multidisorder legacy midsld"),
        StrategyItem("multidisorder_legacy_seqovl_211", "Multidisorder legacy seqovl 211"),
        StrategyItem("multidisorder_legacy_seqovl_652", "Multidisorder legacy seqovl 652"),
        StrategyItem("multidisorder_legacy_badseq", "Multidisorder legacy badseq"),
        StrategyItem("multidisorder_legacy_md5sig", "Multidisorder legacy md5sig"),
        StrategyItem("multidisorder_legacy_syndata", "Multidisorder legacy syndata"),
        StrategyItem("multidisorder_legacy_autottl", "Multidisorder legacy autottl"),
        StrategyItem("multidisorder_legacy_sniext", "Multidisorder legacy sniext"),
        // Other seqovl strategies
        StrategyItem("other_seqovl", "Other seqovl"),
        StrategyItem("general_alt11_191", "General alt11 191"),
        StrategyItem("general_simplefake_alt2_191", "General simplefake alt2 191"),
        StrategyItem("general_simplefake_alt2_191_v2", "General simplefake alt2 191 v2"),
        StrategyItem("other_seqovl_v2", "Other seqovl v2"),
        // Seqovl pattern strategies
        StrategyItem("seqovl_211_google", "Seqovl 211 google"),
        StrategyItem("seqovl_211_tls4", "Seqovl 211 tls4"),
        StrategyItem("seqovl_286_tls5", "Seqovl 286 tls5"),
        StrategyItem("seqovl_286_google", "Seqovl 286 google"),
        StrategyItem("seqovl_308_tls5", "Seqovl 308 tls5"),
        StrategyItem("seqovl_652_tls5", "Seqovl 652 tls5"),
        StrategyItem("seqovl_681_tls5", "Seqovl 681 tls5"),
        StrategyItem("seqovl_50_tls12", "Seqovl 50 tls12"),
        StrategyItem("seqovl_150_tls5", "Seqovl 150 tls5"),
        StrategyItem("seqovl_350_google", "Seqovl 350 google"),
        StrategyItem("seqovl_500_tls7", "Seqovl 500 tls7"),
        StrategyItem("seqovl_211_tls9", "Seqovl 211 tls9"),
        StrategyItem("multisplit_sniext_midsld_18", "Multisplit sniext midsld 18"),
        // Dis strategies
        StrategyItem("dis4_dup", "Dis4 dup"),
        StrategyItem("dis4_midsld", "Dis4 midsld"),
        StrategyItem("multisplit_fake_tls_badseq", "Multisplit fake tls badseq"),
        StrategyItem("multisplit_fake_tls_badseq_lite", "Multisplit fake tls badseq lite"),
        StrategyItem("multisplit_fake_tls_badseq_autottl2", "Multisplit fake tls badseq autottl2"),
        StrategyItem("multisplit_fake_tls_md5sig", "Multisplit fake tls md5sig"),
        StrategyItem("multisplit_fake_tls_md5sig_lite", "Multisplit fake tls md5sig lite"),
        // Dronatar strategies
        StrategyItem("dronatar_4_2", "Dronatar 4 2"),
        StrategyItem("dronatar_4_2_tlsmod", "Dronatar 4 2 tlsmod"),
        // Multidisorder strategies
        StrategyItem("multidisorder_badseq_pos", "Multidisorder badseq pos"),
        StrategyItem("multidisorder_md5sig_pos", "Multidisorder md5sig pos"),
        StrategyItem("multidisorder_ipset_syndata", "Multidisorder ipset syndata"),
        StrategyItem("original_bolvan_v2_badsum", "Original bolvan v2 badsum"),
        StrategyItem("original_bolvan_v2_badsum_max", "Original bolvan v2 badsum max"),
        StrategyItem("multisplit_286_pattern", "Multisplit 286 pattern"),
        StrategyItem("multidisorder_super_split_md5sig", "Multidisorder super split md5sig"),
        StrategyItem("multidisorder_super_split_badseq", "Multidisorder super split badseq"),
        StrategyItem("multidisorder_w3", "Multidisorder w3"),
        StrategyItem("multidisorder_pos_100", "Multidisorder pos 100"),
        // Dis 1-14 strategies
        StrategyItem("dis14", "Dis14"),
        StrategyItem("multisplit_3", "Multisplit 3"),
        StrategyItem("fake_badseq_rnd", "Fake badseq rnd"),
        StrategyItem("fakedsplit_badseq_4", "Fakedsplit badseq 4"),
        StrategyItem("fake_autottl_faketls", "Fake autottl faketls"),
        StrategyItem("fake_datanoack_fake_tls", "Fake datanoack fake tls"),
        StrategyItem("dis1", "Dis1"),
        StrategyItem("dis2", "Dis2"),
        StrategyItem("dis3", "Dis3"),
        StrategyItem("dis5", "Dis5"),
        StrategyItem("dis5_sberbank", "Dis5 sberbank"),
        StrategyItem("dis6", "Dis6"),
        StrategyItem("dis7", "Dis7"),
        StrategyItem("dis8", "Dis8"),
        StrategyItem("dis9", "Dis9"),
        StrategyItem("dis10", "Dis10"),
        StrategyItem("dis11", "Dis11"),
        StrategyItem("split_pos_badseq", "Split pos badseq"),
        StrategyItem("dis12", "Dis12"),
        StrategyItem("dis13", "Dis13"),
        // Alt strategies
        StrategyItem("alt1_161", "Alt1 161"),
        StrategyItem("general_alt183", "General alt183"),
        StrategyItem("general_alt185", "General alt185"),
        StrategyItem("general_alt2183", "General alt2183"),
        StrategyItem("general_alt3183_2", "General alt3183 2"),
        StrategyItem("general_alt4_182", "General alt4 182"),
        StrategyItem("general_bf_2", "General bf 2"),
        StrategyItem("general_alt5_182", "General alt5 182"),
        StrategyItem("general_alt6_182", "General alt6 182"),
        StrategyItem("general_alt6_184", "General alt6 184"),
        StrategyItem("general_alt7_184", "General alt7 184"),
        StrategyItem("general_alt8_185", "General alt8 185"),
        StrategyItem("fake_badseq_2", "Fake badseq 2"),
        StrategyItem("general_alt8_185_2", "General alt8 185 2"),
        StrategyItem("general_alt8_185_3", "General alt8 185 3"),
        StrategyItem("fake_autottl_repeats_6_badseq", "Fake autottl repeats 6 badseq"),
        StrategyItem("general_simplefake_185", "General simplefake 185"),
        StrategyItem("altmgts2_161_2", "Altmgts2 161 2"),
        StrategyItem("general_simple_fake_165_2", "General simple fake 165 2"),
        StrategyItem("altmgts2_161_3", "Altmgts2 161 3"),
        StrategyItem("general_fake_tls_auto_alt_184", "General fake tls auto alt 184"),
        StrategyItem("general_fake_tls_auto_alt_185", "General fake tls auto alt 185"),
        StrategyItem("general_fake_tls_auto_alt3_184", "General fake tls auto alt3 184"),
        StrategyItem("general_fake_tls_auto_alt2_184", "General fake tls auto alt2 184"),
        StrategyItem("general_fake_tls_auto_184", "General fake tls auto 184"),
        StrategyItem("dronatar_4_3", "Dronatar 4 3"),
        // Launcher zapret strategies
        StrategyItem("launcher_zapret_2_9_1_v1", "Launcher zapret 2 9 1 v1"),
        StrategyItem("launcher_zapret_2_9_1_v1_tlsmod", "Launcher zapret 2 9 1 v1 tlsmod"),
        StrategyItem("fake_md5sig_fake_tls", "Fake md5sig fake tls"),
        StrategyItem("launcher_zapret_2_9_1_v3", "Launcher zapret 2 9 1 v3"),
        StrategyItem("launcher_zapret_2_9_1_v4", "Launcher zapret 2 9 1 v4"),
        StrategyItem("multidisorder_seqovl_midsld", "Multidisorder seqovl midsld"),
        StrategyItem("other_seqovl_2", "Other seqovl 2"),
        StrategyItem("multisplit_226_pattern_18", "Multisplit 226 pattern 18"),
        StrategyItem("multisplit_226_pattern_google_Com", "Multisplit 226 pattern google Com"),
        StrategyItem("multisplit_308_pattern", "Multisplit 308 pattern"),
        StrategyItem("multisplit_split_pos_1", "Multisplit split pos 1"),
        StrategyItem("datanoack", "Datanoack"),
        StrategyItem("multisplit_datanoack", "Multisplit datanoack"),
        StrategyItem("multisplit_datanoack_split_pos_1", "Multisplit datanoack split pos 1"),
        StrategyItem("other_seqovl_fakedsplit_ttl2", "Other seqovl fakedsplit ttl2"),
        StrategyItem("other_seqovl_fakedsplit_ttl2_tlsmod", "Other seqovl fakedsplit ttl2 tlsmod"),
        StrategyItem("fakeddisorder_datanoack_1", "Fakeddisorder datanoack 1"),
        StrategyItem("other_multidisorder", "Other multidisorder"),
        StrategyItem("fake_fakedsplit_autottl_2", "Fake fakedsplit autottl 2"),
        StrategyItem("multisplit_seqovl_2_midsld", "Multisplit seqovl 2 midsld"),
        StrategyItem("multisplit_17", "Multisplit 17"),
        StrategyItem("other5", "Other5"),
        StrategyItem("multisplit_1_midsld", "Multisplit 1 midsld"),
        StrategyItem("fake_multidisorder_1_split_pos_1", "Fake multidisorder 1 split pos 1"),
        StrategyItem("multisplit_seqovl_midsld", "Multisplit seqovl midsld"),
        StrategyItem("bolvan_md5sig", "Bolvan md5sig"),
        StrategyItem("bolvan_md5sig_2", "Bolvan md5sig 2"),
        StrategyItem("bolvan_fake_tls", "Bolvan fake tls"),
        StrategyItem("fake_multisplit_seqovl_md5sig", "Fake multisplit seqovl md5sig"),
        StrategyItem("multisplit_1", "Multisplit 1"),
        StrategyItem("multisplit_2_plus", "Multisplit 2 plus"),
        StrategyItem("multisplit_1_plus_2", "Multisplit 1 plus 2"),
        StrategyItem("multisplit_2", "Multisplit 2"),
        StrategyItem("multidisorder_fake_tls_1", "Multidisorder fake tls 1"),
        StrategyItem("multidisorder_fake_tls_1_tlsmod", "Multidisorder fake tls 1 tlsmod"),
        StrategyItem("multidisorder_fake_tls_2", "Multidisorder fake tls 2"),
        StrategyItem("multidisorder_fake_tls_2_tlsmod", "Multidisorder fake tls 2 tlsmod"),
        StrategyItem("syndata", "Syndata"),
        StrategyItem("multisplit_md5sig", "Multisplit md5sig"),
        StrategyItem("fake_multidisorder_seqovl_fake_tls", "Fake multidisorder seqovl fake tls"),
        StrategyItem("syndata_md5sig_2", "Syndata md5sig 2"),
        StrategyItem("multisplit_seqovl_pos", "Multisplit seqovl pos"),
        StrategyItem("multisplit_seqovl_pos_2", "Multisplit seqovl pos 2"),
        StrategyItem("multidisorder_repeats_md5sig", "Multidisorder repeats md5sig"),
        StrategyItem("multidisorder_repeats_md5sig_2", "Multidisorder repeats md5sig 2"),
        StrategyItem("general_alt3183", "General alt3183"),
        StrategyItem("general_alt4_161", "General alt4 161"),
        StrategyItem("general_alt6_181", "General alt6 181"),
        StrategyItem("ankddev10", "Ankddev10"),
        // Split2 strategies
        StrategyItem("split2_seqovl_vk", "Split2 seqovl vk"),
        StrategyItem("split2_seqovl_google", "Split2 seqovl google"),
        StrategyItem("split2_split", "Split2 split"),
        StrategyItem("split2_seqovl_652", "Split2 seqovl 652"),
        StrategyItem("split2_split_google", "Split2 split google"),
        StrategyItem("split2_split_2", "Split2 split 2"),
        StrategyItem("fake_split2", "Fake split2"),
        StrategyItem("split_seqovl", "Split seqovl"),
        StrategyItem("split_pos_badseq_10", "Split pos badseq 10"),
        StrategyItem("split_pos_3", "Split pos 3"),
        // Multidisorder midsld strategies
        StrategyItem("multidisorder_midsld", "Multidisorder midsld"),
        StrategyItem("multidisorder_midsld_syndata", "Multidisorder midsld syndata"),
        // Googlevideo strategies
        StrategyItem("googlevideo_fakedsplit", "Googlevideo fakedsplit"),
        StrategyItem("googlevideo_split", "Googlevideo split"),
        StrategyItem("googlevideo_multidisorder", "Googlevideo multidisorder"),
        StrategyItem("googlevideo_multisplit_pattern", "Googlevideo multisplit pattern"),
        StrategyItem("googlevideo_fakeddisorder", "Googlevideo fakeddisorder"),
        StrategyItem("googlevideo_fakedsplit_simple", "Googlevideo fakedsplit simple"),
        StrategyItem("googlevideo_split_aggressive", "Googlevideo split aggressive"),
        StrategyItem("googlevideo_multidisorder_midsld", "Googlevideo multidisorder midsld"),
        StrategyItem("googlevideo_fake_multisplit", "Googlevideo fake multisplit"),
        StrategyItem("fake_fakedsplit_md5sig_80_port", "Fake fakedsplit md5sig 80 port"),
        StrategyItem("fake_multisplit_datanoack_wssize_midsld", "Fake multisplit datanoack wssize midsld"),
        // Syndata additional strategies
        StrategyItem("syndata_1", "Syndata 1"),
        StrategyItem("syndata_4_badseq", "Syndata 4 badseq"),
        StrategyItem("syndata_tls_google_5_repeats", "Syndata tls google 5 repeats"),
        StrategyItem("syndata_tls_google_5_repeats_multidisorder", "Syndata tls google 5 repeats multidisorder"),
        StrategyItem("syndata_syn_packet_n3", "Syndata syn packet n3"),
        // TLS aggressive strategies
        StrategyItem("tls_aggressive", "Tls aggressive"),
        StrategyItem("tls_aggressive_max", "Tls aggressive max"),
        StrategyItem("tls_aggressive_md5", "Tls aggressive md5"),
        StrategyItem("tls_multisplit_sni", "Tls multisplit sni"),
        StrategyItem("tls_multisplit_sni_652", "Tls multisplit sni 652"),
        StrategyItem("tls_fake_flood", "Tls fake flood"),
        StrategyItem("tls_fake_flood_md5", "Tls fake flood md5"),
        StrategyItem("tls_fake_flood_max", "Tls fake flood max"),
        StrategyItem("tls_combo_aggressive", "Tls combo aggressive"),
        StrategyItem("tls_fake_simple", "Tls fake simple"),
        StrategyItem("tls_fake_only", "Tls fake only"),
        StrategyItem("tls_fake_only_ttl2", "Tls fake only ttl2"),
        StrategyItem("tls_fake_only_badseq", "Tls fake only badseq"),
        StrategyItem("tls_fake_only_md5", "Tls fake only md5"),
        StrategyItem("tls_pass", "Tls pass"),
        StrategyItem("tls_fake_simple_ttl2", "Tls fake simple ttl2"),
        StrategyItem("tls_fake_simple_10", "Tls fake simple 10"),
        StrategyItem("tls_split_gentle", "Tls split gentle"),
        StrategyItem("tls_split_gentle_host", "Tls split gentle host"),
        StrategyItem("tls_split_gentle_sld", "Tls split gentle sld"),
        StrategyItem("tls_fake_split", "Tls fake split"),
        StrategyItem("tls_fake_split_host", "Tls fake split host"),
        StrategyItem("tls_disorder_gentle", "Tls disorder gentle"),
        StrategyItem("tls_disorder_gentle_sld", "Tls disorder gentle sld"),
        StrategyItem("tls_fake_disorder_gentle", "Tls fake disorder gentle"),
        StrategyItem("tls_fake_disorder_gentle_6", "Tls fake disorder gentle 6"),
        StrategyItem("censorliber_google", "Censorliber google"),
        // Discord strategies
        StrategyItem("discord_window_collapse", "Discord window collapse"),
        StrategyItem("discord_router_alert", "Discord router alert"),
        StrategyItem("discord_ecn_exploit", "Discord ecn exploit"),
        StrategyItem("discord_timestamp_travel", "Discord timestamp travel"),
        StrategyItem("discord_urgent_sni", "Discord urgent sni"),
        StrategyItem("discord_ultimate_combo", "Discord ultimate combo")
    )

    private fun getUdpStrategies(): List<StrategyItem> = listOf(
        StrategyItem("Отключено", "Не применять стратегию"),
        // Dronator and basic fake strategies
        StrategyItem("dronator_43", "Dronator 43"),
        StrategyItem("fake_blob_quic_google_repeats_10_autottl", "Fake blob quic google repeats 10 autottl"),
        StrategyItem("fake_2_n2", "Fake 2 n2"),
        StrategyItem("fake_2_n2_google", "Fake 2 n2 google"),
        StrategyItem("fake_2_n2_test", "Fake 2 n2 test"),
        StrategyItem("fake_4_google", "Fake 4 google"),
        StrategyItem("fake_4_quic1", "Fake 4 quic1"),
        // Ipset strategies
        StrategyItem("ipset_fake_6_d3", "Ipset fake 6 d3"),
        StrategyItem("general_bf_32", "General bf 32"),
        StrategyItem("ipset_fake_12_n2_apex", "Ipset fake 12 n2 apex"),
        StrategyItem("ipset_fake_12_n3", "Ipset fake 12 n3"),
        StrategyItem("ipset_fake_10_n2", "Ipset fake 10 n2"),
        StrategyItem("ipset_fake_14_n3", "Ipset fake 14 n3"),
        StrategyItem("ipset_fake_tamper_11", "Ipset fake tamper 11"),
        StrategyItem("ipset_fake_quic6_ttl7", "Ipset fake quic6 ttl7"),
        // Additional fake strategies
        StrategyItem("fake_2_n2_test_2", "Fake 2 n2 test 2"),
        StrategyItem("fake_11_simple", "Fake 11 simple"),
        StrategyItem("fake_15_ttl0_md5sig", "Fake 15 ttl0 md5sig"),
        StrategyItem("fake_15_ttl0_badsum", "Fake 15 ttl0 badsum"),
        StrategyItem("fake_6_google", "Fake 6 google"),
        // IP fragmentation strategies
        StrategyItem("fake_ipfrag2_quic5", "Fake ipfrag2 quic5"),
        StrategyItem("fake_ipfrag2_quic3", "Fake ipfrag2 quic3"),
        StrategyItem("fake_ipfrag2_quic7", "Fake ipfrag2 quic7"),
        // UDP length strategies
        StrategyItem("fake_udplen_2_quic3", "Fake udplen 2 quic3"),
        StrategyItem("fake_udplen_4_quic3", "Fake udplen 4 quic3"),
        StrategyItem("fake_udplen_4_quic4", "Fake udplen 4 quic4"),
        StrategyItem("fake_udplen_8_pattern1", "Fake udplen 8 pattern1"),
        StrategyItem("fake_udplen_8_pattern1_autottl", "Fake udplen 8 pattern1 autottl"),
        StrategyItem("fake_udplen_8_pattern2", "Fake udplen 8 pattern2"),
        StrategyItem("fake_udplen_8_pattern3", "Fake udplen 8 pattern3"),
        StrategyItem("fake_udplen_25", "Fake udplen 25"),
        StrategyItem("fake_udplen_25_10", "Fake udplen 25 10"),
        // Tamper strategies
        StrategyItem("fake_tamper_11", "Fake tamper 11"),
        StrategyItem("fake_tamper_11_autottl", "Fake tamper 11 autottl"),
        // More fake strategies
        StrategyItem("fake_6_n15_unknown", "Fake 6 n15 unknown"),
        StrategyItem("fake_11_quic_bin", "Fake 11 quic bin"),
        StrategyItem("fake_2_n2_quic", "Fake 2 n2 quic"),
        StrategyItem("fake_repeat_2_quic", "Fake repeat 2 quic"),
        StrategyItem("fake_4_google_quic", "Fake 4 google quic"),
        StrategyItem("fake_6_google_quic", "Fake 6 google quic"),
        StrategyItem("fake_6_1", "Fake 6 1"),
        StrategyItem("fake_6_vk_com", "Fake 6 vk com"),
        StrategyItem("fake_8_google", "Fake 8 google"),
        StrategyItem("fake_10_pattern", "Fake 10 pattern"),
        // UDP length modification strategies
        StrategyItem("fake_udplen", "Fake udplen"),
        StrategyItem("fake_updlen_7_quic_cutoff", "Fake updlen 7 quic cutoff"),
        StrategyItem("fake_updlen_7_quic_google", "Fake updlen 7 quic google"),
        StrategyItem("fake_updlen_10_pattern", "Fake updlen 10 pattern"),
        StrategyItem("fake_udplen_6_pattern", "Fake udplen 6 pattern"),
        StrategyItem("fake_udplen_11", "Fake udplen 11")
    )

    private fun getVoiceStrategies(): List<StrategyItem> = listOf(
        StrategyItem("Disabled", "No voice bypass"),
        StrategyItem("Strategy 1 - fake STUN x6", "6 STUN fake packets"),
        StrategyItem("Strategy 2 - fake STUN x4", "4 STUN fake packets"),
        StrategyItem("Strategy 3 - fake+udplen", "STUN + length mod")
    )

    private fun getDebugModes(): List<StrategyItem> = listOf(
        StrategyItem("None", "Logging disabled"),
        StrategyItem("Android (logcat)", "Output to logcat"),
        StrategyItem("File", "Write to file"),
        StrategyItem("Syslog", "System logger")
    )

    private fun getPktCountOptions(): List<StrategyItem> = listOf(
        StrategyItem("1", "Minimal"),
        StrategyItem("3", "Light"),
        StrategyItem("5", "Default"),
        StrategyItem("10", "Extended"),
        StrategyItem("15", "Heavy"),
        StrategyItem("20", "Maximum")
    )

    data class StrategyItem(val name: String, val description: String = "")

    inner class StrategyAdapter(
        private val items: List<StrategyItem>,
        private val selectedIndex: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<StrategyAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val radioButton: RadioButton = view.findViewById(R.id.radioStrategy)
            val nameText: TextView = view.findViewById(R.id.textStrategyName)
            val descText: TextView = view.findViewById(R.id.textStrategyDescription)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_strategy_option, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameText.text = item.name
            holder.radioButton.isChecked = position == selectedIndex

            if (item.description.isNotEmpty()) {
                holder.descText.visibility = View.VISIBLE
                holder.descText.text = item.description
            } else {
                holder.descText.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount() = items.size
    }
}
