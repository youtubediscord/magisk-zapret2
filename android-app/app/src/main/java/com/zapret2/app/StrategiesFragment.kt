package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
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

    // Current selections (strategy IDs/names)
    private val selections = mutableMapOf<String, String>()

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CONFIG = "$MODDIR/zapret2/config.sh"
    private val START_SCRIPT = "$MODDIR/zapret2/scripts/zapret-start.sh"
    private val STOP_SCRIPT = "$MODDIR/zapret2/scripts/zapret-stop.sh"

    // Category key to categories.txt mapping
    private val categoryKeyMap = mapOf(
        "youtube_tcp" to "youtube",
        "youtube_quic" to "youtube_udp",
        "twitch" to "twitch_tcp",
        "discord_tcp" to "discord",
        "discord_quic" to "udp_discord",
        "voice" to "discord_voice_udp",
        "telegram" to "telegram_tcp",
        "whatsapp" to "whatsapp_tcp",
        "facebook" to "facebook_tcp",
        "instagram" to "instagram_tcp",
        "twitter" to "twitter_tcp",
        "github" to "github_tcp",
        "steam" to "steam_tcp",
        "soundcloud" to "soundcloud_tcp",
        "rutracker" to "rutracker_tcp",
        "other" to "other"
    )

    // Is category TCP or UDP
    private val isTcpCategory = mapOf(
        "youtube_tcp" to true,
        "youtube_quic" to false,
        "twitch" to true,
        "discord_tcp" to true,
        "discord_quic" to false,
        "voice" to false,
        "telegram" to true,
        "whatsapp" to true,
        "facebook" to true,
        "instagram" to true,
        "twitter" to true,
        "github" to true,
        "steam" to true,
        "soundcloud" to true,
        "rutracker" to true,
        "other" to true
    )

    private val voiceStrategies = arrayOf(
        "Disabled",
        "Strategy 1 - fake STUN x6",
        "Strategy 2 - fake STUN x4",
        "Strategy 3 - fake+udplen"
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
                selections["youtube_tcp"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowYoutubeQuic.setOnClickListener {
            showStrategyPicker("youtube_quic", "YouTube QUIC", "UDP 443", R.drawable.ic_video,
                selections["youtube_quic"] ?: "disabled", StrategyPickerBottomSheet.TYPE_UDP)
        }
        rowTwitch.setOnClickListener {
            showStrategyPicker("twitch", "Twitch", "TCP 443", R.drawable.ic_video,
                selections["twitch"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Messaging
        rowDiscordTcp.setOnClickListener {
            showStrategyPicker("discord_tcp", "Discord", "TCP 443", R.drawable.ic_message,
                selections["discord_tcp"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowDiscordQuic.setOnClickListener {
            showStrategyPicker("discord_quic", "Discord QUIC", "UDP 443", R.drawable.ic_message,
                selections["discord_quic"] ?: "disabled", StrategyPickerBottomSheet.TYPE_UDP)
        }
        rowVoice.setOnClickListener {
            showStrategyPicker("voice", "Voice/STUN", "UDP Voice", R.drawable.ic_message,
                selections["voice"] ?: "disabled", StrategyPickerBottomSheet.TYPE_VOICE)
        }
        rowTelegram.setOnClickListener {
            showStrategyPicker("telegram", "Telegram", "TCP 443", R.drawable.ic_message,
                selections["telegram"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowWhatsapp.setOnClickListener {
            showStrategyPicker("whatsapp", "WhatsApp", "TCP 443", R.drawable.ic_message,
                selections["whatsapp"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Social Media
        rowFacebook.setOnClickListener {
            showStrategyPicker("facebook", "Facebook", "TCP 443", R.drawable.ic_social,
                selections["facebook"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowInstagram.setOnClickListener {
            showStrategyPicker("instagram", "Instagram", "TCP 443", R.drawable.ic_social,
                selections["instagram"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowTwitter.setOnClickListener {
            showStrategyPicker("twitter", "Twitter/X", "TCP 443", R.drawable.ic_social,
                selections["twitter"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Other Services
        rowGithub.setOnClickListener {
            showStrategyPicker("github", "GitHub", "TCP 443", R.drawable.ic_apps,
                selections["github"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowSteam.setOnClickListener {
            showStrategyPicker("steam", "Steam", "TCP 443", R.drawable.ic_apps,
                selections["steam"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowSoundcloud.setOnClickListener {
            showStrategyPicker("soundcloud", "SoundCloud", "TCP 443", R.drawable.ic_apps,
                selections["soundcloud"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowRutracker.setOnClickListener {
            showStrategyPicker("rutracker", "Rutracker", "TCP 443", R.drawable.ic_apps,
                selections["rutracker"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }
        rowOther.setOnClickListener {
            showStrategyPicker("other", "Other/Hostlist", "TCP 443", R.drawable.ic_apps,
                selections["other"] ?: "disabled", StrategyPickerBottomSheet.TYPE_TCP)
        }

        // Advanced
        rowPktCount.setOnClickListener {
            showStrategyPicker("pkt_count", "PKT_COUNT", "Packets to modify", R.drawable.ic_settings,
                selections["pkt_count"] ?: "5", StrategyPickerBottomSheet.TYPE_PKT_COUNT)
        }
        rowDebug.setOnClickListener {
            showStrategyPicker("debug", "Debug Mode", "Log destination", R.drawable.ic_settings,
                selections["debug"] ?: "none", StrategyPickerBottomSheet.TYPE_DEBUG)
        }
    }

    private fun showStrategyPicker(
        key: String,
        name: String,
        protocol: String,
        iconRes: Int,
        currentStrategyName: String,
        type: String
    ) {
        val bottomSheet = StrategyPickerBottomSheet.newInstance(
            key, name, protocol, iconRes, currentStrategyName, type
        )
        bottomSheet.setOnStrategySelectedListener { selectedName ->
            selections[key] = selectedName
            updateValueText(key, selectedName, type)
            saveConfigAndRestart()
        }
        bottomSheet.show(parentFragmentManager, "strategy_picker")
    }

    private fun updateValueText(key: String, strategyName: String, type: String) {
        // For TCP/UDP, format the strategy name for display
        if (type == StrategyPickerBottomSheet.TYPE_TCP || type == StrategyPickerBottomSheet.TYPE_UDP) {
            val displayName = if (strategyName == "disabled") {
                "Disabled"
            } else {
                // Format strategy name for display (replace underscores with spaces, title case)
                strategyName.split("_")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
            }
            updateTextView(key, displayName)
        } else {
            // For voice, debug, and pkt_count - strategyName is already the display value or ID
            val displayName = when (type) {
                StrategyPickerBottomSheet.TYPE_VOICE -> {
                    when (strategyName) {
                        "disabled" -> "Disabled"
                        "voice_fake_stun_6" -> "Strategy 1 - fake STUN x6"
                        "voice_fake_stun_4" -> "Strategy 2 - fake STUN x4"
                        "voice_fake_udplen" -> "Strategy 3 - fake+udplen"
                        else -> "Disabled"
                    }
                }
                StrategyPickerBottomSheet.TYPE_DEBUG -> {
                    when (strategyName) {
                        "none" -> "None"
                        "android" -> "Android (logcat)"
                        "file" -> "File"
                        "syslog" -> "Syslog"
                        else -> "None"
                    }
                }
                StrategyPickerBottomSheet.TYPE_PKT_COUNT -> strategyName  // "1", "3", "5", etc.
                else -> "Disabled"
            }
            updateTextView(key, displayName)
        }
    }

    private fun updateTextView(key: String, displayName: String) {
        // Shorten display for rows if needed
        val shortName = when {
            displayName == "Disabled" -> "Disabled"
            displayName == "None" -> "None"
            displayName.startsWith("Strategy") -> displayName.substringBefore(" -").trim()
            displayName.length > 25 -> displayName.take(22) + "..."
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
            // Load from categories.txt using StrategyRepository
            val categories = StrategyRepository.readCategories()

            // Preload TCP and UDP strategies to convert indices to names
            val tcpStrategies = StrategyRepository.getTcpStrategies()
            val udpStrategies = StrategyRepository.getUdpStrategies()

            // Voice strategies mapping (index to id)
            val voiceStrategyIds = listOf("disabled", "voice_fake_stun_6", "voice_fake_stun_4", "voice_fake_udplen")

            // Load each category's strategy
            categoryKeyMap.forEach { (uiKey, categoryKey) ->
                val config = categories[categoryKey]
                if (config != null) {
                    val strategyIndex = config.strategyIndex
                    val isTcp = isTcpCategory[uiKey] == true

                    // Voice has special handling - uses its own strategy list
                    val strategyName = if (uiKey == "voice") {
                        if (config.enabled != 1 || strategyIndex == 0) {
                            "disabled"
                        } else {
                            voiceStrategyIds.getOrNull(strategyIndex) ?: "disabled"
                        }
                    } else {
                        // TCP/UDP categories use their respective strategy lists
                        val strategies = if (isTcp) tcpStrategies else udpStrategies
                        if (config.enabled != 1 || strategyIndex == 0) {
                            "disabled"
                        } else {
                            strategies.getOrNull(strategyIndex)?.id ?: "disabled"
                        }
                    }
                    selections[uiKey] = strategyName

                    val type = when {
                        uiKey == "voice" -> StrategyPickerBottomSheet.TYPE_VOICE
                        isTcp -> StrategyPickerBottomSheet.TYPE_TCP
                        else -> StrategyPickerBottomSheet.TYPE_UDP
                    }
                    updateValueText(uiKey, strategyName, type)
                }
            }

            // Load PKT_COUNT and LOG_MODE from config.sh (they're not in categories.txt)
            val config = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
            }

            parseConfigValue(config, "PKT_COUNT")?.let { value ->
                // Store the value directly (e.g., "5", "10")
                if (value in pktCountOptions) {
                    selections["pkt_count"] = value
                    updateValueText("pkt_count", value, StrategyPickerBottomSheet.TYPE_PKT_COUNT)
                }
            }

            parseConfigValue(config, "LOG_MODE")?.let { value ->
                // Store the value directly (e.g., "none", "android", "file", "syslog")
                if (value in debugModeValues) {
                    selections["debug"] = value
                    updateValueText("debug", value, StrategyPickerBottomSheet.TYPE_DEBUG)
                }
            }
        }
    }

    private fun parseConfigValue(config: String, key: String): String? {
        val regex = Regex("""$key=["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }

    private fun saveConfigAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            var allSuccess = true

            // Preload TCP and UDP strategies to convert names to indices
            val tcpStrategies = StrategyRepository.getTcpStrategies()
            val udpStrategies = StrategyRepository.getUdpStrategies()

            // Voice strategies mapping (id to index)
            val voiceStrategyIds = listOf("disabled", "voice_fake_stun_6", "voice_fake_stun_4", "voice_fake_udplen")

            // Save each category to categories.txt
            categoryKeyMap.forEach { (uiKey, categoryKey) ->
                val strategyName = selections[uiKey] ?: "disabled"

                // Convert strategy name to index
                val strategyIndex = if (uiKey == "voice") {
                    // Voice has special handling
                    if (strategyName == "disabled") {
                        0
                    } else {
                        voiceStrategyIds.indexOf(strategyName).takeIf { it >= 0 } ?: 0
                    }
                } else {
                    // TCP/UDP categories use their respective strategy lists
                    val isTcp = isTcpCategory[uiKey] == true
                    val strategies = if (isTcp) tcpStrategies else udpStrategies
                    if (strategyName == "disabled") {
                        0
                    } else {
                        strategies.indexOfFirst { it.id == strategyName }.takeIf { it >= 0 } ?: 0
                    }
                }

                val success = StrategyRepository.updateCategoryStrategy(categoryKey, strategyIndex)
                if (!success) allSuccess = false
            }

            // Save PKT_COUNT and LOG_MODE to config.sh
            val pktCount = selections["pkt_count"] ?: "5"
            val debugMode = selections["debug"] ?: "none"

            val (configSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                // Read current config
                val currentConfig = Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")

                // Update PKT_COUNT and LOG_MODE in config
                var newConfig = currentConfig
                    .replace(Regex("""PKT_COUNT=["']?\d+["']?"""), "PKT_COUNT=\"$pktCount\"")
                    .replace(Regex("""LOG_MODE=["']?\w+["']?"""), "LOG_MODE=\"$debugMode\"")

                // If values don't exist, add them
                if (!newConfig.contains("PKT_COUNT=")) {
                    newConfig += "\nPKT_COUNT=\"$pktCount\""
                }
                if (!newConfig.contains("LOG_MODE=")) {
                    newConfig += "\nLOG_MODE=\"$debugMode\""
                }

                val escaped = newConfig.replace("'", "'\\''")
                val saveResult = Shell.cmd("echo '$escaped' > $CONFIG").exec()

                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                Shell.cmd("$STOP_SCRIPT").exec()
                val startResult = Shell.cmd("$START_SCRIPT").exec()
                Pair(true, startResult.isSuccess)
            }

            if (allSuccess && configSuccess && restartSuccess) {
                Toast.makeText(requireContext(), "Applied successfully", Toast.LENGTH_SHORT).show()
                // Notify LogsFragment to refresh cmdline and logs
                setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
            } else if (allSuccess && configSuccess) {
                Toast.makeText(requireContext(), "Saved, restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
