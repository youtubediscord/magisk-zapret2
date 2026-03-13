package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.google.android.material.snackbar.Snackbar
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
                selections["debug"] ?: "none",
                StrategyPickerBottomSheet.TYPE_DEBUG
            )
        }
    }

    private fun buildCategoryRows(categories: Map<String, StrategyRepository.CategoryConfig>) {
        categoriesContainer.removeAllViews()
        categoryOrder.clear()
        categoryValueViews.clear()
        categoryMeta.clear()

        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

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
        val ctx = context ?: return Pair(R.drawable.ic_apps, 0)
        val key = categoryKey.lowercase()
        return when {
            key.contains("youtube") -> Pair(R.drawable.ic_video, ContextCompat.getColor(ctx, R.color.youtube_red))
            key.contains("googlevideo") -> Pair(R.drawable.ic_video, ContextCompat.getColor(ctx, R.color.googlevideo_red))
            key.contains("twitch") -> Pair(R.drawable.ic_video, ContextCompat.getColor(ctx, R.color.twitch_purple))
            key.contains("discord") -> Pair(R.drawable.ic_message, ContextCompat.getColor(ctx, R.color.discord_blue))
            key.contains("telegram") -> Pair(R.drawable.ic_message, ContextCompat.getColor(ctx, R.color.telegram_blue))
            key.contains("whatsapp") -> Pair(R.drawable.ic_message, ContextCompat.getColor(ctx, R.color.whatsapp_green))
            key.contains("voice") || type == StrategyPickerBottomSheet.TYPE_VOICE -> Pair(
                R.drawable.ic_message,
                ContextCompat.getColor(ctx, R.color.voice_purple)
            )

            key.contains("facebook") -> Pair(R.drawable.ic_social, ContextCompat.getColor(ctx, R.color.facebook_blue))
            key.contains("instagram") -> Pair(R.drawable.ic_social, ContextCompat.getColor(ctx, R.color.instagram_pink))
            key.contains("twitter") -> Pair(R.drawable.ic_social, ContextCompat.getColor(ctx, R.color.twitter_blue))
            key.contains("github") -> Pair(R.drawable.ic_apps, ContextCompat.getColor(ctx, R.color.github_white))
            key.contains("soundcloud") -> Pair(R.drawable.ic_apps, ContextCompat.getColor(ctx, R.color.soundcloud_orange))
            key.contains("steam") -> Pair(R.drawable.ic_apps, ContextCompat.getColor(ctx, R.color.steam_blue))
            type == StrategyPickerBottomSheet.TYPE_UDP -> Pair(R.drawable.ic_message, ContextCompat.getColor(ctx, R.color.udp_blue))
            else -> Pair(R.drawable.ic_apps, ContextCompat.getColor(ctx, R.color.status_success))
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
            if (!isAdded) return@setOnStrategyAndFilterSelectedListener
            selections[key] = selectedName
            newFilterMode?.let { filterModes[key] = it }
            updateValueText(key, selectedName, type)
            saveConfigAndRestart()
        }

        bottomSheet.setOnStrategySelectedListener { selectedName ->
            if (!isAdded) return@setOnStrategySelectedListener
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

            categoriesContainer.alpha = 0f
            categoriesContainer.animate().alpha(1f).setDuration(200).start()

            val runtimeCore = withContext(Dispatchers.IO) {
                RuntimeConfigStore.readCore()
            }

            if (!isAdded || view == null) return@launch

            val pktValue = runtimeCore["pkt_out"]
                ?: runtimeCore["pkt_count"]
                ?: "5"
            if (pktValue in pktCountOptions) {
                selections["pkt_count"] = pktValue
                updateValueText("pkt_count", pktValue, StrategyPickerBottomSheet.TYPE_PKT_COUNT)
            }

            val logModeValue = runtimeCore["log_mode"] ?: "none"
            if (logModeValue in debugModeValues) {
                selections["debug"] = logModeValue
                updateValueText("debug", logModeValue, StrategyPickerBottomSheet.TYPE_DEBUG)
            }
        }
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
            val debugMode = selections["debug"] ?: "none"

            val (configSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                val runtimeUpdated = RuntimeConfigStore.updateCoreSettings(
                    update = RuntimeConfigStore.CoreSettingsUpdate(
                        presetMode = "categories",
                        logMode = debugMode,
                        pktOut = pktCount.toIntOrNull()
                    ),
                    removeKeys = setOf("pkt_count")
                )
                if (!runtimeUpdated) {
                    return@withContext Pair(false, false)
                }

                val restartResult = Shell.cmd("sh $RESTART_SCRIPT").exec()
                Pair(true, restartResult.isSuccess)
            }

            if (!isAdded || view == null) return@launch

            hideLoading()

            if (allSuccess && configSuccess && restartSuccess) {
                view?.let { Snackbar.make(it, "Applied successfully", Snackbar.LENGTH_SHORT).show() }
                setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
            } else if (allSuccess && configSuccess) {
                view?.let { Snackbar.make(it, "Saved, restart failed", Snackbar.LENGTH_SHORT).show() }
            } else {
                view?.let { Snackbar.make(it, "Save failed", Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
