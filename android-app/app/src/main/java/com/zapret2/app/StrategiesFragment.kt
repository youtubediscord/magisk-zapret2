package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrategiesFragment : Fragment() {

    // Row views
    private lateinit var rowYoutubeTcp: LinearLayout
    private lateinit var rowYoutubeQuic: LinearLayout
    private lateinit var rowTwitch: LinearLayout
    private lateinit var rowDiscordTcp: LinearLayout
    private lateinit var rowDiscordQuic: LinearLayout
    private lateinit var rowVoice: LinearLayout
    private lateinit var rowTelegram: LinearLayout
    private lateinit var rowWhatsapp: LinearLayout
    private lateinit var rowFacebook: LinearLayout
    private lateinit var rowInstagram: LinearLayout
    private lateinit var rowTwitter: LinearLayout
    private lateinit var rowGithub: LinearLayout
    private lateinit var rowSteam: LinearLayout
    private lateinit var rowSoundcloud: LinearLayout
    private lateinit var rowRutracker: LinearLayout
    private lateinit var rowOther: LinearLayout
    private lateinit var rowPktCount: LinearLayout
    private lateinit var rowDebug: LinearLayout

    // Value TextViews
    private lateinit var textYoutubeTcpValue: TextView
    private lateinit var textYoutubeQuicValue: TextView
    private lateinit var textTwitchValue: TextView
    private lateinit var textDiscordTcpValue: TextView
    private lateinit var textDiscordQuicValue: TextView
    private lateinit var textVoiceValue: TextView
    private lateinit var textTelegramValue: TextView
    private lateinit var textWhatsappValue: TextView
    private lateinit var textFacebookValue: TextView
    private lateinit var textInstagramValue: TextView
    private lateinit var textTwitterValue: TextView
    private lateinit var textGithubValue: TextView
    private lateinit var textSteamValue: TextView
    private lateinit var textSoundcloudValue: TextView
    private lateinit var textRutrackerValue: TextView
    private lateinit var textOtherValue: TextView
    private lateinit var textPktCountValue: TextView
    private lateinit var textDebugValue: TextView

    // Current selection indices
    private val selections = mutableMapOf<String, Int>()

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CONFIG = "$MODDIR/zapret2/config.sh"
    private val START_SCRIPT = "$MODDIR/zapret2/scripts/zapret-start.sh"
    private val STOP_SCRIPT = "$MODDIR/zapret2/scripts/zapret-stop.sh"

    // Strategy arrays
    private val tcpStrategies = arrayOf(
        "Disabled",
        "Strategy 1 - syndata+multisplit",
        "Strategy 2 - censorliber v2",
        "Strategy 3 - multidisorder",
        "Strategy 4 - multisplit",
        "Strategy 5 - fake+split",
        "Strategy 6 - fake autottl",
        "Strategy 7 - fake+multisplit",
        "Strategy 8 - fake autottl+split",
        "Strategy 9 - fake md5sig",
        "Strategy 10 - syndata+fake",
        "Strategy 11 - TLS aggressive",
        "Strategy 12 - syndata only"
    )

    private val tcpStrategyValues = arrayOf(
        "none",
        "syndata_7_tls_google_multisplit_midsld",
        "censorliber_google_syndata_v2",
        "multidisorder_midsld",
        "multisplit_1_midsld",
        "tls_fake_split",
        "fake_autottl_repeats_6_badseq",
        "dis5",
        "dis7",
        "bolvan_md5sig",
        "general_alt11_191_syndata",
        "tls_aggressive",
        "syndata"
    )

    private val udpStrategies = arrayOf(
        "Disabled",
        "Strategy 1 - fake QUIC x6",
        "Strategy 2 - fake QUIC x4",
        "Strategy 3 - fake QUIC x11",
        "Strategy 4 - fake+udplen",
        "Strategy 5 - fake+ipfrag",
        "Strategy 6 - fake autottl x12"
    )

    private val udpStrategyValues = arrayOf(
        "none",
        "fake_6_google_quic",
        "fake_4_google_quic",
        "fake_11_quic_bin",
        "fake_udplen_25_10",
        "fake_ipfrag2_quic5",
        "ipset_fake_12_n3"
    )

    private val voiceStrategies = arrayOf(
        "Disabled",
        "Strategy 1 - fake STUN x6",
        "Strategy 2 - fake STUN x4",
        "Strategy 3 - fake+udplen"
    )

    private val voiceStrategyValues = arrayOf(
        "none",
        "fake_6_stun",
        "fake_4_stun",
        "fake_udplen_stun"
    )

    private val debugModes = arrayOf("None", "Android (logcat)", "File", "Syslog")
    private val debugModeValues = arrayOf("none", "android", "file", "syslog")

    private val pktCountOptions = arrayOf("1", "3", "5", "10", "15", "20")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_strategies, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        loadConfig()
    }

    private fun initViews(view: View) {
        // Rows
        rowYoutubeTcp = view.findViewById(R.id.rowYoutubeTcp)
        rowYoutubeQuic = view.findViewById(R.id.rowYoutubeQuic)
        rowTwitch = view.findViewById(R.id.rowTwitch)
        rowDiscordTcp = view.findViewById(R.id.rowDiscordTcp)
        rowDiscordQuic = view.findViewById(R.id.rowDiscordQuic)
        rowVoice = view.findViewById(R.id.rowVoice)
        rowTelegram = view.findViewById(R.id.rowTelegram)
        rowWhatsapp = view.findViewById(R.id.rowWhatsapp)
        rowFacebook = view.findViewById(R.id.rowFacebook)
        rowInstagram = view.findViewById(R.id.rowInstagram)
        rowTwitter = view.findViewById(R.id.rowTwitter)
        rowGithub = view.findViewById(R.id.rowGithub)
        rowSteam = view.findViewById(R.id.rowSteam)
        rowSoundcloud = view.findViewById(R.id.rowSoundcloud)
        rowRutracker = view.findViewById(R.id.rowRutracker)
        rowOther = view.findViewById(R.id.rowOther)
        rowPktCount = view.findViewById(R.id.rowPktCount)
        rowDebug = view.findViewById(R.id.rowDebug)

        // Value texts
        textYoutubeTcpValue = view.findViewById(R.id.textYoutubeTcpValue)
        textYoutubeQuicValue = view.findViewById(R.id.textYoutubeQuicValue)
        textTwitchValue = view.findViewById(R.id.textTwitchValue)
        textDiscordTcpValue = view.findViewById(R.id.textDiscordTcpValue)
        textDiscordQuicValue = view.findViewById(R.id.textDiscordQuicValue)
        textVoiceValue = view.findViewById(R.id.textVoiceValue)
        textTelegramValue = view.findViewById(R.id.textTelegramValue)
        textWhatsappValue = view.findViewById(R.id.textWhatsappValue)
        textFacebookValue = view.findViewById(R.id.textFacebookValue)
        textInstagramValue = view.findViewById(R.id.textInstagramValue)
        textTwitterValue = view.findViewById(R.id.textTwitterValue)
        textGithubValue = view.findViewById(R.id.textGithubValue)
        textSteamValue = view.findViewById(R.id.textSteamValue)
        textSoundcloudValue = view.findViewById(R.id.textSoundcloudValue)
        textRutrackerValue = view.findViewById(R.id.textRutrackerValue)
        textOtherValue = view.findViewById(R.id.textOtherValue)
        textPktCountValue = view.findViewById(R.id.textPktCountValue)
        textDebugValue = view.findViewById(R.id.textDebugValue)
    }

    private fun setupClickListeners() {
        // Video Services
        rowYoutubeTcp.setOnClickListener {
            showStrategyPicker("youtube_tcp", "YouTube TCP", "TCP 443", R.drawable.ic_video,
                selections["youtube_tcp"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowYoutubeQuic.setOnClickListener {
            showStrategyPicker("youtube_quic", "YouTube QUIC", "UDP 443", R.drawable.ic_video,
                selections["youtube_quic"] ?: 0, StrategyPickerBottomSheet.TYPE_UDP)
        }
        rowTwitch.setOnClickListener {
            showStrategyPicker("twitch", "Twitch", "TCP 443", R.drawable.ic_video,
                selections["twitch"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Messaging
        rowDiscordTcp.setOnClickListener {
            showStrategyPicker("discord_tcp", "Discord", "TCP 443", R.drawable.ic_message,
                selections["discord_tcp"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowDiscordQuic.setOnClickListener {
            showStrategyPicker("discord_quic", "Discord QUIC", "UDP 443", R.drawable.ic_message,
                selections["discord_quic"] ?: 0, StrategyPickerBottomSheet.TYPE_UDP)
        }
        rowVoice.setOnClickListener {
            showStrategyPicker("voice", "Voice/STUN", "UDP Voice", R.drawable.ic_message,
                selections["voice"] ?: 0, StrategyPickerBottomSheet.TYPE_VOICE)
        }
        rowTelegram.setOnClickListener {
            showStrategyPicker("telegram", "Telegram", "TCP 443", R.drawable.ic_message,
                selections["telegram"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowWhatsapp.setOnClickListener {
            showStrategyPicker("whatsapp", "WhatsApp", "TCP 443", R.drawable.ic_message,
                selections["whatsapp"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Social Media
        rowFacebook.setOnClickListener {
            showStrategyPicker("facebook", "Facebook", "TCP 443", R.drawable.ic_social,
                selections["facebook"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowInstagram.setOnClickListener {
            showStrategyPicker("instagram", "Instagram", "TCP 443", R.drawable.ic_social,
                selections["instagram"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowTwitter.setOnClickListener {
            showStrategyPicker("twitter", "Twitter/X", "TCP 443", R.drawable.ic_social,
                selections["twitter"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Other Services
        rowGithub.setOnClickListener {
            showStrategyPicker("github", "GitHub", "TCP 443", R.drawable.ic_apps,
                selections["github"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowSteam.setOnClickListener {
            showStrategyPicker("steam", "Steam", "TCP 443", R.drawable.ic_apps,
                selections["steam"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowSoundcloud.setOnClickListener {
            showStrategyPicker("soundcloud", "SoundCloud", "TCP 443", R.drawable.ic_apps,
                selections["soundcloud"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowRutracker.setOnClickListener {
            showStrategyPicker("rutracker", "Rutracker", "TCP 443", R.drawable.ic_apps,
                selections["rutracker"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowOther.setOnClickListener {
            showStrategyPicker("other", "Other/Hostlist", "TCP 443", R.drawable.ic_apps,
                selections["other"] ?: 0, StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Advanced
        rowPktCount.setOnClickListener {
            showStrategyPicker("pkt_count", "PKT_COUNT", "Packets to modify", R.drawable.ic_settings,
                selections["pkt_count"] ?: 2, StrategyPickerBottomSheet.TYPE_PKT_COUNT)
        }
        rowDebug.setOnClickListener {
            showStrategyPicker("debug", "Debug Mode", "Log destination", R.drawable.ic_settings,
                selections["debug"] ?: 0, StrategyPickerBottomSheet.TYPE_DEBUG)
        }
    }

    private fun showStrategyPicker(
        key: String,
        name: String,
        protocol: String,
        iconRes: Int,
        currentIndex: Int,
        type: String
    ) {
        val bottomSheet = StrategyPickerBottomSheet.newInstance(
            key, name, protocol, iconRes, currentIndex, type
        )
        bottomSheet.setOnStrategySelectedListener { selectedIndex ->
            selections[key] = selectedIndex
            updateValueText(key, selectedIndex, type)
            saveConfigAndRestart()
        }
        bottomSheet.show(parentFragmentManager, "strategy_picker")
    }

    private fun updateValueText(key: String, index: Int, type: String) {
        val displayName = when (type) {
            StrategyPickerBottomSheet.TYPE_TCP -> tcpStrategies.getOrNull(index) ?: "Disabled"
            StrategyPickerBottomSheet.TYPE_UDP -> udpStrategies.getOrNull(index) ?: "Disabled"
            StrategyPickerBottomSheet.TYPE_VOICE -> voiceStrategies.getOrNull(index) ?: "Disabled"
            StrategyPickerBottomSheet.TYPE_DEBUG -> debugModes.getOrNull(index) ?: "None"
            StrategyPickerBottomSheet.TYPE_PKT_COUNT -> pktCountOptions.getOrNull(index) ?: "5"
            else -> "Disabled"
        }

        // Shorten display for rows
        val shortName = when {
            displayName == "Disabled" -> "Disabled"
            displayName == "None" -> "None"
            displayName.startsWith("Strategy") -> displayName.substringBefore(" -").trim()
            else -> displayName
        }

        when (key) {
            "youtube_tcp" -> textYoutubeTcpValue.text = shortName
            "youtube_quic" -> textYoutubeQuicValue.text = shortName
            "twitch" -> textTwitchValue.text = shortName
            "discord_tcp" -> textDiscordTcpValue.text = shortName
            "discord_quic" -> textDiscordQuicValue.text = shortName
            "voice" -> textVoiceValue.text = shortName
            "telegram" -> textTelegramValue.text = shortName
            "whatsapp" -> textWhatsappValue.text = shortName
            "facebook" -> textFacebookValue.text = shortName
            "instagram" -> textInstagramValue.text = shortName
            "twitter" -> textTwitterValue.text = shortName
            "github" -> textGithubValue.text = shortName
            "steam" -> textSteamValue.text = shortName
            "soundcloud" -> textSoundcloudValue.text = shortName
            "rutracker" -> textRutrackerValue.text = shortName
            "other" -> textOtherValue.text = shortName
            "pkt_count" -> textPktCountValue.text = shortName
            "debug" -> textDebugValue.text = shortName
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
            }

            // Parse and set values
            parseAndSetValue(config, "STRATEGY_YOUTUBE", "youtube_tcp", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_YOUTUBE_UDP", "youtube_quic", udpStrategyValues)
            parseAndSetValue(config, "STRATEGY_TWITCH_TCP", "twitch", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_DISCORD", "discord_tcp", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_DISCORD_QUIC", "discord_quic", udpStrategyValues)
            parseAndSetValue(config, "STRATEGY_DISCORD_VOICE_UDP", "voice", voiceStrategyValues)
            parseAndSetValue(config, "STRATEGY_TELEGRAM_TCP", "telegram", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_WHATSAPP_TCP", "whatsapp", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_FACEBOOK_TCP", "facebook", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_INSTAGRAM_TCP", "instagram", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_TWITTER_TCP", "twitter", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_GITHUB_TCP", "github", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_STEAM_TCP", "steam", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_SOUNDCLOUD_TCP", "soundcloud", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_RUTRACKER_TCP", "rutracker", tcpStrategyValues)
            parseAndSetValue(config, "STRATEGY_OTHER", "other", tcpStrategyValues)
            parseAndSetValue(config, "LOG_MODE", "debug", debugModeValues)

            // PKT_COUNT
            parseConfigValue(config, "PKT_COUNT")?.let { value ->
                val idx = pktCountOptions.indexOf(value)
                if (idx >= 0) {
                    selections["pkt_count"] = idx
                    updateValueText("pkt_count", idx, StrategyPickerBottomSheet.TYPE_PKT_COUNT)
                }
            }
        }
    }

    private fun parseAndSetValue(config: String, configKey: String, selectionKey: String, values: Array<String>) {
        parseConfigValue(config, configKey)?.let { value ->
            val idx = values.indexOf(value)
            if (idx >= 0) {
                selections[selectionKey] = idx
                val type = when (values) {
                    tcpStrategyValues -> StrategyPickerBottomSheet.TYPE_TCP
                    udpStrategyValues -> StrategyPickerBottomSheet.TYPE_UDP
                    voiceStrategyValues -> StrategyPickerBottomSheet.TYPE_VOICE
                    debugModeValues -> StrategyPickerBottomSheet.TYPE_DEBUG
                    else -> StrategyPickerBottomSheet.TYPE_TCP
                }
                updateValueText(selectionKey, idx, type)
            }
        }
    }

    private fun parseConfigValue(config: String, key: String): String? {
        val regex = Regex("""$key=["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }

    private fun getStrategyValue(key: String, type: String): String {
        val index = selections[key] ?: 0
        return when (type) {
            StrategyPickerBottomSheet.TYPE_TCP -> tcpStrategyValues.getOrNull(index) ?: "none"
            StrategyPickerBottomSheet.TYPE_UDP -> udpStrategyValues.getOrNull(index) ?: "none"
            StrategyPickerBottomSheet.TYPE_VOICE -> voiceStrategyValues.getOrNull(index) ?: "none"
            StrategyPickerBottomSheet.TYPE_DEBUG -> debugModeValues.getOrNull(index) ?: "none"
            StrategyPickerBottomSheet.TYPE_PKT_COUNT -> pktCountOptions.getOrNull(index) ?: "5"
            else -> "none"
        }
    }

    private fun saveConfigAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ytTcp = getStrategyValue("youtube_tcp", StrategyPickerBottomSheet.TYPE_TCP)
            val ytQuic = getStrategyValue("youtube_quic", StrategyPickerBottomSheet.TYPE_UDP)
            val twitch = getStrategyValue("twitch", StrategyPickerBottomSheet.TYPE_TCP)
            val dcTcp = getStrategyValue("discord_tcp", StrategyPickerBottomSheet.TYPE_TCP)
            val dcQuic = getStrategyValue("discord_quic", StrategyPickerBottomSheet.TYPE_UDP)
            val voice = getStrategyValue("voice", StrategyPickerBottomSheet.TYPE_VOICE)
            val telegram = getStrategyValue("telegram", StrategyPickerBottomSheet.TYPE_TCP)
            val whatsapp = getStrategyValue("whatsapp", StrategyPickerBottomSheet.TYPE_TCP)
            val facebook = getStrategyValue("facebook", StrategyPickerBottomSheet.TYPE_TCP)
            val instagram = getStrategyValue("instagram", StrategyPickerBottomSheet.TYPE_TCP)
            val twitter = getStrategyValue("twitter", StrategyPickerBottomSheet.TYPE_TCP)
            val github = getStrategyValue("github", StrategyPickerBottomSheet.TYPE_TCP)
            val steam = getStrategyValue("steam", StrategyPickerBottomSheet.TYPE_TCP)
            val soundcloud = getStrategyValue("soundcloud", StrategyPickerBottomSheet.TYPE_TCP)
            val rutracker = getStrategyValue("rutracker", StrategyPickerBottomSheet.TYPE_TCP)
            val other = getStrategyValue("other", StrategyPickerBottomSheet.TYPE_TCP)
            val pktCount = getStrategyValue("pkt_count", StrategyPickerBottomSheet.TYPE_PKT_COUNT)
            val debugMode = getStrategyValue("debug", StrategyPickerBottomSheet.TYPE_DEBUG)

            val config = """
#!/system/bin/sh
# Zapret2 Configuration - Generated by Zapret2 App
AUTOSTART=1
QNUM=200
DESYNC_MARK=0x40000000
PORTS_TCP="80,443"
PORTS_UDP="443"
PKT_OUT=20
PKT_IN=10
LOG_MODE="$debugMode"
USE_CATEGORIES=1
PKT_COUNT="$pktCount"

# Video Services
STRATEGY_YOUTUBE="$ytTcp"
STRATEGY_YOUTUBE_UDP="$ytQuic"
STRATEGY_TWITCH_TCP="$twitch"

# Messaging
STRATEGY_DISCORD="$dcTcp"
STRATEGY_DISCORD_QUIC="$dcQuic"
STRATEGY_DISCORD_VOICE_UDP="$voice"
STRATEGY_TELEGRAM_TCP="$telegram"
STRATEGY_WHATSAPP_TCP="$whatsapp"

# Social Media
STRATEGY_FACEBOOK_TCP="$facebook"
STRATEGY_INSTAGRAM_TCP="$instagram"
STRATEGY_TWITTER_TCP="$twitter"

# Other Services
STRATEGY_GITHUB_TCP="$github"
STRATEGY_STEAM_TCP="$steam"
STRATEGY_SOUNDCLOUD_TCP="$soundcloud"
STRATEGY_RUTRACKER_TCP="$rutracker"
STRATEGY_OTHER="$other"
            """.trimIndent()

            val (saveSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                val escaped = config.replace("'", "'\\''")
                val saveResult = Shell.cmd("echo '$escaped' > $CONFIG").exec()

                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                Shell.cmd("$STOP_SCRIPT").exec()
                val startResult = Shell.cmd("$START_SCRIPT").exec()
                Pair(true, startResult.isSuccess)
            }

            if (saveSuccess && restartSuccess) {
                Toast.makeText(requireContext(), "Applied successfully", Toast.LENGTH_SHORT).show()
            } else if (saveSuccess) {
                Toast.makeText(requireContext(), "Saved, restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
