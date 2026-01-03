package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrategiesFragment : Fragment() {

    // Strategy spinners - Video
    private lateinit var spinnerYoutubeTcp: Spinner
    private lateinit var spinnerYoutubeQuic: Spinner
    private lateinit var spinnerTwitch: Spinner

    // Strategy spinners - Messaging
    private lateinit var spinnerDiscordTcp: Spinner
    private lateinit var spinnerDiscordQuic: Spinner
    private lateinit var spinnerVoice: Spinner
    private lateinit var spinnerTelegram: Spinner
    private lateinit var spinnerWhatsapp: Spinner

    // Strategy spinners - Social
    private lateinit var spinnerFacebook: Spinner
    private lateinit var spinnerInstagram: Spinner
    private lateinit var spinnerTwitter: Spinner

    // Strategy spinners - Other
    private lateinit var spinnerGithub: Spinner
    private lateinit var spinnerSteam: Spinner
    private lateinit var spinnerSoundcloud: Spinner
    private lateinit var spinnerRutracker: Spinner
    private lateinit var spinnerOther: Spinner

    // Advanced settings
    private lateinit var editPktCount: TextInputEditText
    private lateinit var spinnerDebug: Spinner
    private lateinit var buttonSave: MaterialButton

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CONFIG = "$MODDIR/zapret2/config.sh"
    private val START_SCRIPT = "$MODDIR/zapret2/scripts/zapret-start.sh"
    private val STOP_SCRIPT = "$MODDIR/zapret2/scripts/zapret-stop.sh"

    // Flag to prevent auto-save during initial load
    private var isInitialized = false

    // Strategy options arrays
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

    private val debugModes = arrayOf(
        "None",
        "Android (logcat)",
        "File",
        "Syslog"
    )

    private val debugModeValues = arrayOf(
        "none",
        "android",
        "file",
        "syslog"
    )

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
        setupSpinners()
        loadConfig()
        setupListeners()
    }

    private fun initViews(view: View) {
        // Video
        spinnerYoutubeTcp = view.findViewById(R.id.spinnerYoutubeTcp)
        spinnerYoutubeQuic = view.findViewById(R.id.spinnerYoutubeQuic)
        spinnerTwitch = view.findViewById(R.id.spinnerTwitch)

        // Messaging
        spinnerDiscordTcp = view.findViewById(R.id.spinnerDiscordTcp)
        spinnerDiscordQuic = view.findViewById(R.id.spinnerDiscordQuic)
        spinnerVoice = view.findViewById(R.id.spinnerVoice)
        spinnerTelegram = view.findViewById(R.id.spinnerTelegram)
        spinnerWhatsapp = view.findViewById(R.id.spinnerWhatsapp)

        // Social
        spinnerFacebook = view.findViewById(R.id.spinnerFacebook)
        spinnerInstagram = view.findViewById(R.id.spinnerInstagram)
        spinnerTwitter = view.findViewById(R.id.spinnerTwitter)

        // Other
        spinnerGithub = view.findViewById(R.id.spinnerGithub)
        spinnerSteam = view.findViewById(R.id.spinnerSteam)
        spinnerSoundcloud = view.findViewById(R.id.spinnerSoundcloud)
        spinnerRutracker = view.findViewById(R.id.spinnerRutracker)
        spinnerOther = view.findViewById(R.id.spinnerOther)

        // Advanced
        editPktCount = view.findViewById(R.id.editPktCount)
        spinnerDebug = view.findViewById(R.id.spinnerDebug)
        buttonSave = view.findViewById(R.id.buttonSave)
    }

    private fun setupSpinners() {
        val context = requireContext()

        // TCP strategy adapter
        val tcpAdapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies)
        tcpAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        // UDP strategy adapter
        val udpAdapter = ArrayAdapter(context, R.layout.spinner_item, udpStrategies)
        udpAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        // Voice strategy adapter
        val voiceAdapter = ArrayAdapter(context, R.layout.spinner_item, voiceStrategies)
        voiceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        // Debug mode adapter
        val debugAdapter = ArrayAdapter(context, R.layout.spinner_item, debugModes)
        debugAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)

        // Apply TCP adapter
        spinnerYoutubeTcp.adapter = tcpAdapter
        spinnerTwitch.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerDiscordTcp.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerTelegram.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerWhatsapp.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerFacebook.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerInstagram.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerTwitter.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerGithub.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerSteam.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerSoundcloud.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerRutracker.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        spinnerOther.adapter = ArrayAdapter(context, R.layout.spinner_item, tcpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        // Apply UDP adapter
        spinnerYoutubeQuic.adapter = udpAdapter
        spinnerDiscordQuic.adapter = ArrayAdapter(context, R.layout.spinner_item, udpStrategies).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        // Apply Voice adapter
        spinnerVoice.adapter = voiceAdapter

        // Apply Debug adapter
        spinnerDebug.adapter = debugAdapter
    }

    private fun setupListeners() {
        buttonSave.setOnClickListener {
            saveConfigAndRestart()
        }

        // Setup auto-save on all spinners
        setupSpinnerAutoSave(spinnerYoutubeTcp)
        setupSpinnerAutoSave(spinnerYoutubeQuic)
        setupSpinnerAutoSave(spinnerTwitch)
        setupSpinnerAutoSave(spinnerDiscordTcp)
        setupSpinnerAutoSave(spinnerDiscordQuic)
        setupSpinnerAutoSave(spinnerVoice)
        setupSpinnerAutoSave(spinnerTelegram)
        setupSpinnerAutoSave(spinnerWhatsapp)
        setupSpinnerAutoSave(spinnerFacebook)
        setupSpinnerAutoSave(spinnerInstagram)
        setupSpinnerAutoSave(spinnerTwitter)
        setupSpinnerAutoSave(spinnerGithub)
        setupSpinnerAutoSave(spinnerSteam)
        setupSpinnerAutoSave(spinnerSoundcloud)
        setupSpinnerAutoSave(spinnerRutracker)
        setupSpinnerAutoSave(spinnerOther)
        setupSpinnerAutoSave(spinnerDebug)
    }

    private fun setupSpinnerAutoSave(spinner: Spinner) {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isInitialized) {
                    saveConfigAndRestart()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
            }

            // Video
            parseConfigValue(config, "STRATEGY_YOUTUBE")?.let {
                setSpinnerByValue(spinnerYoutubeTcp, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_YOUTUBE_UDP")?.let {
                setSpinnerByValue(spinnerYoutubeQuic, it, udpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_TWITCH_TCP")?.let {
                setSpinnerByValue(spinnerTwitch, it, tcpStrategyValues)
            }

            // Messaging
            parseConfigValue(config, "STRATEGY_DISCORD")?.let {
                setSpinnerByValue(spinnerDiscordTcp, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_DISCORD_QUIC")?.let {
                setSpinnerByValue(spinnerDiscordQuic, it, udpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_DISCORD_VOICE_UDP")?.let {
                setSpinnerByValue(spinnerVoice, it, voiceStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_TELEGRAM_TCP")?.let {
                setSpinnerByValue(spinnerTelegram, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_WHATSAPP_TCP")?.let {
                setSpinnerByValue(spinnerWhatsapp, it, tcpStrategyValues)
            }

            // Social
            parseConfigValue(config, "STRATEGY_FACEBOOK_TCP")?.let {
                setSpinnerByValue(spinnerFacebook, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_INSTAGRAM_TCP")?.let {
                setSpinnerByValue(spinnerInstagram, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_TWITTER_TCP")?.let {
                setSpinnerByValue(spinnerTwitter, it, tcpStrategyValues)
            }

            // Other
            parseConfigValue(config, "STRATEGY_GITHUB_TCP")?.let {
                setSpinnerByValue(spinnerGithub, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_STEAM_TCP")?.let {
                setSpinnerByValue(spinnerSteam, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_SOUNDCLOUD_TCP")?.let {
                setSpinnerByValue(spinnerSoundcloud, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_RUTRACKER_TCP")?.let {
                setSpinnerByValue(spinnerRutracker, it, tcpStrategyValues)
            }
            parseConfigValue(config, "STRATEGY_OTHER")?.let {
                setSpinnerByValue(spinnerOther, it, tcpStrategyValues)
            }

            // Advanced
            parseConfigValue(config, "PKT_COUNT")?.let {
                editPktCount.setText(it)
            }
            parseConfigValue(config, "LOG_MODE")?.let {
                setSpinnerByValue(spinnerDebug, it, debugModeValues)
            }

            // Mark as initialized to enable auto-save
            isInitialized = true
        }
    }

    private fun parseConfigValue(config: String, key: String): String? {
        val regex = Regex("""$key=["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }

    private fun setSpinnerByValue(spinner: Spinner, value: String, values: Array<String>) {
        val idx = values.indexOf(value)
        if (idx >= 0) {
            spinner.setSelection(idx)
        }
    }

    private fun getSpinnerValue(spinner: Spinner, values: Array<String>): String {
        val position = spinner.selectedItemPosition
        return if (position >= 0 && position < values.size) {
            values[position]
        } else {
            values[0]
        }
    }

    private fun saveConfigAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonSave.isEnabled = false
            buttonSave.text = "Applying..."

            val ytTcp = getSpinnerValue(spinnerYoutubeTcp, tcpStrategyValues)
            val ytQuic = getSpinnerValue(spinnerYoutubeQuic, udpStrategyValues)
            val twitch = getSpinnerValue(spinnerTwitch, tcpStrategyValues)
            val dcTcp = getSpinnerValue(spinnerDiscordTcp, tcpStrategyValues)
            val dcQuic = getSpinnerValue(spinnerDiscordQuic, udpStrategyValues)
            val voice = getSpinnerValue(spinnerVoice, voiceStrategyValues)
            val telegram = getSpinnerValue(spinnerTelegram, tcpStrategyValues)
            val whatsapp = getSpinnerValue(spinnerWhatsapp, tcpStrategyValues)
            val facebook = getSpinnerValue(spinnerFacebook, tcpStrategyValues)
            val instagram = getSpinnerValue(spinnerInstagram, tcpStrategyValues)
            val twitter = getSpinnerValue(spinnerTwitter, tcpStrategyValues)
            val github = getSpinnerValue(spinnerGithub, tcpStrategyValues)
            val steam = getSpinnerValue(spinnerSteam, tcpStrategyValues)
            val soundcloud = getSpinnerValue(spinnerSoundcloud, tcpStrategyValues)
            val rutracker = getSpinnerValue(spinnerRutracker, tcpStrategyValues)
            val other = getSpinnerValue(spinnerOther, tcpStrategyValues)
            val pktCount = editPktCount.text?.toString() ?: "5"
            val debugMode = getSpinnerValue(spinnerDebug, debugModeValues)

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
                // Save config
                val escaped = config.replace("'", "'\\''")
                val saveResult = Shell.cmd("echo '$escaped' > $CONFIG").exec()

                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                // Restart service
                Shell.cmd("$STOP_SCRIPT").exec()
                val startResult = Shell.cmd("$START_SCRIPT").exec()
                Pair(true, startResult.isSuccess)
            }

            buttonSave.isEnabled = true
            buttonSave.text = "SAVE CONFIGURATION"

            if (saveSuccess && restartSuccess) {
                // Build summary of applied strategies
                val appliedStrategies = buildAppliedSummary(ytTcp, dcTcp, telegram)
                Toast.makeText(requireContext(), "Applied: $appliedStrategies", Toast.LENGTH_SHORT).show()
            } else if (saveSuccess) {
                Toast.makeText(requireContext(), "Saved, but restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildAppliedSummary(youtube: String, discord: String, telegram: String): String {
        val parts = mutableListOf<String>()

        if (youtube != "none") {
            val idx = tcpStrategyValues.indexOf(youtube)
            if (idx > 0) parts.add("YT=${idx}")
        }
        if (discord != "none") {
            val idx = tcpStrategyValues.indexOf(discord)
            if (idx > 0) parts.add("DC=${idx}")
        }
        if (telegram != "none") {
            val idx = tcpStrategyValues.indexOf(telegram)
            if (idx > 0) parts.add("TG=${idx}")
        }

        return if (parts.isEmpty()) "All disabled" else parts.joinToString(", ")
    }
}
