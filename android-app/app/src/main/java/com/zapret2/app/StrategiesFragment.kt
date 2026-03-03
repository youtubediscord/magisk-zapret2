package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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

    private data class CategoryUiMeta(
        val title: String,
        val subtitle: String,
        val type: String,
        val iconRes: Int,
        val iconTint: Int
    )

    // Dynamic category list container
    private lateinit var categoriesContainer: LinearLayout

    // Advanced rows
    private lateinit var rowPktCount: LinearLayout
    private lateinit var rowDebug: LinearLayout

    // Loading overlay
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView

    // Advanced value TextViews
    private lateinit var textPktCountValue: TextView
    private lateinit var textDebugValue: TextView

    // Current selections (strategy IDs/names)
    private val selections = mutableMapOf<String, String>()

    // Current filter modes for each category
    private val filterModes = mutableMapOf<String, String>()

    // Categories that support filter mode switching (have both hostlist and ipset)
    private val canSwitchFilterMode = mutableMapOf<String, Boolean>()

    // Dynamic category UI state
    private val categoryOrder = mutableListOf<String>()
    private val categoryValueViews = mutableMapOf<String, TextView>()
    private val categoryMeta = mutableMapOf<String, CategoryUiMeta>()

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CONFIG = "$MODDIR/zapret2/config.sh"
    private val USER_CONFIG = "/data/local/tmp/zapret2-user.conf"
    private val RESTART_SCRIPT = "$MODDIR/zapret2/scripts/zapret-restart.sh"

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
        categoriesContainer = view.findViewById(R.id.categoriesContainer)

        rowPktCount = view.findViewById(R.id.rowPktCount)
        rowDebug = view.findViewById(R.id.rowDebug)

        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingText = view.findViewById(R.id.loadingText)

        textPktCountValue = view.findViewById(R.id.textPktCountValue)
        textDebugValue = view.findViewById(R.id.textDebugValue)
    }

    private fun setupClickListeners() {
        rowPktCount.setOnClickListener {
            showStrategyPicker(
                "pkt_count",
                "PKT_COUNT",
                "Packets to modify",
                R.drawable.ic_settings,
                selections["pkt_count"] ?: "5",
                StrategyPickerBottomSheet.TYPE_PKT_COUNT
            )
        }

        rowDebug.setOnClickListener {
            showStrategyPicker(
                "debug",
                "Debug Mode",
                "Log destination",
                R.drawable.ic_settings,
                selections["debug"] ?: "android",
                StrategyPickerBottomSheet.TYPE_DEBUG
            )
        }
    }

    private fun buildCategoryRows(categories: Map<String, StrategyRepository.CategoryConfig>) {
        categoriesContainer.removeAllViews()
        categoryOrder.clear()
        categoryValueViews.clear()
        categoryMeta.clear()

        val inflater = LayoutInflater.from(requireContext())

        categories.forEach { (categoryKey, config) ->
            val meta = createCategoryMeta(categoryKey, config)
            categoryMeta[categoryKey] = meta
            categoryOrder.add(categoryKey)

            val rowView = inflater.inflate(R.layout.item_strategy_category_row, categoriesContainer, false)
            val row = rowView.findViewById<LinearLayout>(R.id.rowCategory)
            val icon = rowView.findViewById<ImageView>(R.id.iconCategory)
            val title = rowView.findViewById<TextView>(R.id.textCategoryName)
            val subtitle = rowView.findViewById<TextView>(R.id.textCategorySubtitle)
            val value = rowView.findViewById<TextView>(R.id.textCategoryValue)

            icon.setImageResource(meta.iconRes)
            icon.setColorFilter(meta.iconTint)
            title.text = meta.title
            subtitle.text = meta.subtitle

            row.setOnClickListener {
                showStrategyPicker(
                    key = categoryKey,
                    name = meta.title,
                    protocol = meta.subtitle,
                    iconRes = meta.iconRes,
                    currentStrategyName = selections[categoryKey] ?: "disabled",
                    type = meta.type
                )
            }

            categoriesContainer.addView(rowView)
            categoryValueViews[categoryKey] = value
        }
    }

    private fun createCategoryMeta(
        categoryKey: String,
        config: StrategyRepository.CategoryConfig
    ): CategoryUiMeta {
        val type = pickerTypeFromProtocol(config.protocol)
        val protocol = protocolLabel(config.protocol)

        val filterTarget = when (config.filterMode.lowercase()) {
            "ipset" -> config.ipsetFile.ifEmpty { "ipset" }
            "hostlist" -> config.hostlistFile.ifEmpty { "hostlist" }
            "hostlist-domains" -> config.hostlistDomains.ifEmpty { "hostlist-domains" }
            else -> "none"
        }

        val subtitle = "$protocol - $filterTarget"

        val iconInfo = resolveCategoryVisual(categoryKey, type)

        return CategoryUiMeta(
            title = formatCategoryTitle(categoryKey, config.protocol),
            subtitle = subtitle,
            type = type,
            iconRes = iconInfo.first,
            iconTint = iconInfo.second
        )
    }

    private fun resolveCategoryVisual(categoryKey: String, type: String): Pair<Int, Int> {
        val key = categoryKey.lowercase()
        return when {
            key.contains("youtube") -> Pair(R.drawable.ic_video, 0xFFFF0000.toInt())
            key.contains("googlevideo") -> Pair(R.drawable.ic_video, 0xFFE53935.toInt())
            key.contains("twitch") -> Pair(R.drawable.ic_video, 0xFF9146FF.toInt())
            key.contains("discord") -> Pair(R.drawable.ic_message, 0xFF5865F2.toInt())
            key.contains("telegram") -> Pair(R.drawable.ic_message, 0xFF0088CC.toInt())
            key.contains("whatsapp") -> Pair(R.drawable.ic_message, 0xFF25D366.toInt())
            key.contains("voice") || type == StrategyPickerBottomSheet.TYPE_VOICE -> Pair(
                R.drawable.ic_message,
                0xFF9B59B6.toInt()
            )

            key.contains("facebook") -> Pair(R.drawable.ic_social, 0xFF1877F2.toInt())
            key.contains("instagram") -> Pair(R.drawable.ic_social, 0xFFE4405F.toInt())
            key.contains("twitter") -> Pair(R.drawable.ic_social, 0xFF1DA1F2.toInt())
            key.contains("github") -> Pair(R.drawable.ic_apps, 0xFFFFFFFF.toInt())
            key.contains("soundcloud") -> Pair(R.drawable.ic_apps, 0xFFFF5500.toInt())
            key.contains("steam") -> Pair(R.drawable.ic_apps, 0xFF66C0F4.toInt())
            type == StrategyPickerBottomSheet.TYPE_UDP -> Pair(R.drawable.ic_message, 0xFF03A9F4.toInt())
            else -> Pair(R.drawable.ic_apps, 0xFF4CAF50.toInt())
        }
    }

    private fun pickerTypeFromProtocol(protocol: String): String {
        return when (protocol.lowercase()) {
            "udp" -> StrategyPickerBottomSheet.TYPE_UDP
            "stun" -> StrategyPickerBottomSheet.TYPE_VOICE
            else -> StrategyPickerBottomSheet.TYPE_TCP
        }
    }

    private fun protocolLabel(protocol: String): String {
        return when (protocol.lowercase()) {
            "udp" -> "UDP"
            "stun" -> "STUN"
            else -> "TCP"
        }
    }

    private fun formatCategoryTitle(categoryKey: String, protocol: String): String {
        val protocolToken = when (protocol.lowercase()) {
            "udp" -> "udp"
            "stun" -> "stun"
            else -> "tcp"
        }

        val tokens = categoryKey.split("_").toMutableList()
        if (tokens.size > 1 && tokens.last().lowercase() == protocolToken) {
            tokens.removeAt(tokens.lastIndex)
        }

        return tokens
            .joinToString(" ") { token ->
                when (token.lowercase()) {
                    "youtube" -> "YouTube"
                    "googlevideo" -> "GoogleVideo"
                    "whatsapp" -> "WhatsApp"
                    "github" -> "GitHub"
                    "anydesk" -> "AnyDesk"
                    "cloudflare" -> "Cloudflare"
                    "warp" -> "WARP"
                    "claude" -> "Claude"
                    "chatgpt" -> "ChatGPT"
                    "tcp" -> "TCP"
                    "udp" -> "UDP"
                    "stun" -> "STUN"
                    "http" -> "HTTP"
                    "https" -> "HTTPS"
                    "ipset" -> "IPSet"
                    "ovh" -> "OVH"
                    else -> token.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                }
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
        val canSwitch = canSwitchFilterMode[key] ?: false
        val currentFilterMode = filterModes[key] ?: "none"

        val bottomSheet = StrategyPickerBottomSheet.newInstance(
            key,
            name,
            protocol,
            iconRes,
            currentStrategyName,
            type,
            canSwitch,
            currentFilterMode
        )

        bottomSheet.setOnStrategyAndFilterSelectedListener { selectedName, newFilterMode ->
            selections[key] = selectedName
            newFilterMode?.let { filterModes[key] = it }
            updateValueText(key, selectedName, type)
            saveConfigAndRestart()
        }

        bottomSheet.setOnStrategySelectedListener { selectedName ->
            if (type == StrategyPickerBottomSheet.TYPE_DEBUG ||
                type == StrategyPickerBottomSheet.TYPE_PKT_COUNT
            ) {
                selections[key] = selectedName
                updateValueText(key, selectedName, type)
                saveConfigAndRestart()
            }
        }

        bottomSheet.show(parentFragmentManager, "strategy_picker")
    }

    private fun updateValueText(key: String, strategyName: String, type: String) {
        if (type == StrategyPickerBottomSheet.TYPE_TCP ||
            type == StrategyPickerBottomSheet.TYPE_UDP ||
            type == StrategyPickerBottomSheet.TYPE_VOICE
        ) {
            val displayName = if (strategyName == "disabled") {
                "Disabled"
            } else {
                strategyName.split("_")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase() else it.toString()
                        }
                    }
            }
            updateTextView(key, displayName)
            return
        }

        val displayName = when (type) {
            StrategyPickerBottomSheet.TYPE_DEBUG -> {
                when (strategyName) {
                    "none" -> "None"
                    "android" -> "Android (logcat)"
                    "file" -> "File"
                    "syslog" -> "Syslog"
                    else -> "None"
                }
            }

            StrategyPickerBottomSheet.TYPE_PKT_COUNT -> strategyName
            else -> "Disabled"
        }

        updateTextView(key, displayName)
    }

    private fun updateTextView(key: String, displayName: String) {
        val shortName = when {
            displayName == "Disabled" -> "Disabled"
            displayName == "None" -> "None"
            displayName.startsWith("Strategy") -> displayName.substringBefore(" -").trim()
            displayName.length > 25 -> displayName.take(22) + "..."
            else -> displayName
        }

        categoryValueViews[key]?.let {
            it.text = shortName
            return
        }

        when (key) {
            "pkt_count" -> textPktCountValue.text = shortName
            "debug" -> textDebugValue.text = shortName
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val categories = StrategyRepository.readCategories()

            selections.clear()
            filterModes.clear()
            canSwitchFilterMode.clear()

            categories.forEach { (categoryKey, config) ->
                val strategyName = config.strategyName.ifEmpty { "disabled" }
                selections[categoryKey] = strategyName
                filterModes[categoryKey] = config.filterMode
                canSwitchFilterMode[categoryKey] = config.canSwitchFilterMode
            }

            buildCategoryRows(categories)

            categoryOrder.forEach { categoryKey ->
                val strategyName = selections[categoryKey] ?: "disabled"
                val type = categoryMeta[categoryKey]?.type ?: StrategyPickerBottomSheet.TYPE_TCP
                updateValueText(categoryKey, strategyName, type)
            }

            val (moduleConfig, userConfig) = withContext(Dispatchers.IO) {
                val modConfig = Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
                val usrConfig = Shell.cmd("cat $USER_CONFIG 2>/dev/null").exec().out.joinToString("\n")
                Pair(modConfig, usrConfig)
            }

            val pktValue = parseConfigValue(userConfig, "PKT_OUT")
                ?: parseConfigValue(userConfig, "PKT_COUNT")
                ?: parseConfigValue(moduleConfig, "PKT_OUT")
                ?: parseConfigValue(moduleConfig, "PKT_COUNT")
            pktValue?.let { value ->
                if (value in pktCountOptions) {
                    selections["pkt_count"] = value
                    updateValueText("pkt_count", value, StrategyPickerBottomSheet.TYPE_PKT_COUNT)
                }
            }

            val logModeValue = parseConfigValue(userConfig, "LOG_MODE")
                ?: parseConfigValue(moduleConfig, "LOG_MODE")
            logModeValue?.let { value ->
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

    private fun showLoading(text: String = "Restarting service...") {
        loadingText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private fun saveConfigAndRestart() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Restarting service...")

            val categoryUpdates = categoryOrder.associateWith { categoryKey ->
                selections[categoryKey] ?: "disabled"
            }

            val filterModeUpdates = categoryOrder.mapNotNull { categoryKey ->
                filterModes[categoryKey]?.let { categoryKey to it }
            }.toMap()

            val allSuccess = StrategyRepository.updateAllCategoryStrategies(
                categoryUpdates,
                filterModeUpdates.ifEmpty { null }
            )

            val pktCount = selections["pkt_count"] ?: "5"
            val debugMode = selections["debug"] ?: "android"

            val (configSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                val configReadResult = Shell.cmd("cat $CONFIG 2>/dev/null").exec()
                val currentConfig = configReadResult.out.joinToString("\n")
                if (!configReadResult.isSuccess || currentConfig.isBlank()) {
                    return@withContext Pair(false, false)
                }

                var newConfig = currentConfig
                    .replace(Regex("""PKT_OUT=["']?\d+["']?"""), "PKT_OUT=\"$pktCount\"")
                    .replace(Regex("""PKT_COUNT=["']?\d+["']?"""), "PKT_COUNT=\"$pktCount\"")
                    .replace(Regex("""LOG_MODE=["']?\w+["']?"""), "LOG_MODE=\"$debugMode\"")
                    .replace(Regex("""PRESET_MODE=["']?[^"'\n]+["']?"""), "PRESET_MODE=\"categories\"")

                if (!newConfig.contains("PKT_OUT=")) {
                    newConfig += "\nPKT_OUT=\"$pktCount\""
                }
                if (!newConfig.contains("PKT_COUNT=")) {
                    newConfig += "\nPKT_COUNT=\"$pktCount\""
                }
                if (!newConfig.contains("LOG_MODE=")) {
                    newConfig += "\nLOG_MODE=\"$debugMode\""
                }
                if (!newConfig.contains("PRESET_MODE=")) {
                    newConfig += "\nPRESET_MODE=\"categories\""
                }

                val escaped = newConfig.replace("'", "'\\''")
                val saveResult = Shell.cmd("echo '$escaped' > $CONFIG").exec()
                if (!saveResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val hasPktOut = Shell.cmd("grep -q '^PKT_OUT=' $USER_CONFIG 2>/dev/null && echo 1 || echo 0").exec()
                    .out.firstOrNull()?.trim() == "1"
                val savePktOutResult = if (hasPktOut) {
                    Shell.cmd("sed -i 's/^PKT_OUT=.*/PKT_OUT=$pktCount/' $USER_CONFIG").exec()
                } else {
                    Shell.cmd("echo 'PKT_OUT=$pktCount' >> $USER_CONFIG").exec()
                }
                if (!savePktOutResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val hasLogMode = Shell.cmd("grep -q '^LOG_MODE=' $USER_CONFIG 2>/dev/null && echo 1 || echo 0").exec()
                    .out.firstOrNull()?.trim() == "1"
                val saveLogModeResult = if (hasLogMode) {
                    Shell.cmd("sed -i 's/^LOG_MODE=.*/LOG_MODE=$debugMode/' $USER_CONFIG").exec()
                } else {
                    Shell.cmd("echo 'LOG_MODE=$debugMode' >> $USER_CONFIG").exec()
                }
                if (!saveLogModeResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val hasPresetMode = Shell.cmd("grep -q '^PRESET_MODE=' $USER_CONFIG 2>/dev/null && echo 1 || echo 0").exec()
                    .out.firstOrNull()?.trim() == "1"
                val savePresetModeResult = if (hasPresetMode) {
                    Shell.cmd("sed -i 's/^PRESET_MODE=.*/PRESET_MODE=categories/' $USER_CONFIG").exec()
                } else {
                    Shell.cmd("echo 'PRESET_MODE=categories' >> $USER_CONFIG").exec()
                }
                if (!savePresetModeResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                val restartResult = Shell.cmd("sh $RESTART_SCRIPT").exec()
                Pair(true, restartResult.isSuccess)
            }

            hideLoading()

            if (allSuccess && configSuccess && restartSuccess) {
                Toast.makeText(requireContext(), "Applied successfully", Toast.LENGTH_SHORT).show()
                setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
            } else if (allSuccess && configSuccess) {
                Toast.makeText(requireContext(), "Saved, restart failed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
