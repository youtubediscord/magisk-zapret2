package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.StrategyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CategoryUiModel(
    val key: String,
    val title: String,
    val subtitle: String,
    val type: String,           // "tcp", "udp", "voice"
    val iconRes: Int,
    val iconTint: Long,         // Color as ARGB long
    val strategyName: String,
    val strategyDisplayName: String,
    val filterMode: String,
    val canSwitchFilter: Boolean
)

data class StrategiesUiState(
    val categories: List<CategoryUiModel> = emptyList(),
    val pktCount: String = "5",
    val debugMode: String = "none",
    val isLoading: Boolean = false,
    val loadingText: String = ""
)

@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val moduleDir = "/data/adb/modules/zapret2"
    private val restartScript = "$moduleDir/zapret2/scripts/zapret-restart.sh"

    // Mutable maps for tracking user selections
    private val selections = mutableMapOf<String, String>()
    private val filterModes = mutableMapOf<String, String>()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            val categories = StrategyRepository.readCategories()
            val runtimeCore = withContext(Dispatchers.IO) { RuntimeConfigStore.readCore() }

            selections.clear()
            filterModes.clear()

            val uiModels = categories.map { (key, config) ->
                val strategyName = config.strategyName.ifEmpty { "disabled" }
                selections[key] = strategyName
                filterModes[key] = config.filterMode

                CategoryUiModel(
                    key = key,
                    title = formatCategoryTitle(key, config.protocol),
                    subtitle = "${protocolLabel(config.protocol)} - ${filterTarget(config)}",
                    type = pickerType(config.protocol),
                    iconRes = 0, // Will be resolved in UI layer
                    iconTint = 0L,
                    strategyName = strategyName,
                    strategyDisplayName = formatStrategyDisplay(strategyName),
                    filterMode = config.filterMode,
                    canSwitchFilter = config.canSwitchFilterMode
                )
            }

            val pktValue = runtimeCore["pkt_out"] ?: runtimeCore["pkt_count"] ?: "5"
            val logMode = runtimeCore["log_mode"] ?: "none"
            selections["pkt_count"] = pktValue
            selections["debug"] = logMode

            _uiState.update { it.copy(
                categories = uiModels,
                pktCount = pktValue,
                debugMode = logMode
            )}
        }
    }

    fun selectStrategy(categoryKey: String, strategyId: String, newFilterMode: String? = null) {
        selections[categoryKey] = strategyId
        newFilterMode?.let { filterModes[categoryKey] = it }

        _uiState.update { state ->
            state.copy(categories = state.categories.map { cat ->
                if (cat.key == categoryKey) {
                    cat.copy(
                        strategyName = strategyId,
                        strategyDisplayName = formatStrategyDisplay(strategyId),
                        filterMode = newFilterMode ?: cat.filterMode
                    )
                } else cat
            })
        }
        saveConfigAndRestart()
    }

    fun setPktCount(value: String) {
        selections["pkt_count"] = value
        _uiState.update { it.copy(pktCount = value) }
        saveConfigAndRestart()
    }

    fun setDebugMode(value: String) {
        selections["debug"] = value
        _uiState.update { it.copy(debugMode = value) }
        saveConfigAndRestart()
    }

    private fun saveConfigAndRestart() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Restarting service...") }

            val categoryKeys = _uiState.value.categories.map { it.key }
            val categoryUpdates = categoryKeys.associateWith { selections[it] ?: "disabled" }
            val filterModeUpdates = categoryKeys.mapNotNull { key ->
                filterModes[key]?.let { key to it }
            }.toMap()

            val allSuccess = StrategyRepository.updateAllCategoryStrategies(
                categoryUpdates,
                filterModeUpdates.ifEmpty { null }
            )

            val pktCount = selections["pkt_count"] ?: "5"
            val debugMode = selections["debug"] ?: "none"

            val (configSuccess, restartSuccess) = withContext(Dispatchers.IO) {
                val updated = RuntimeConfigStore.updateCoreSettings(
                    RuntimeConfigStore.CoreSettingsUpdate(
                        presetMode = "categories",
                        logMode = debugMode,
                        pktOut = pktCount.toIntOrNull()
                    ),
                    removeKeys = setOf("pkt_count")
                )
                if (!updated) return@withContext Pair(false, false)
                val restart = Shell.cmd("sh $restartScript").exec()
                Pair(true, restart.isSuccess)
            }

            _uiState.update { it.copy(isLoading = false) }

            if (allSuccess && configSuccess && restartSuccess) {
                _snackbar.emit("Applied successfully")
                serviceEventBus.notifyServiceRestarted()
            } else if (allSuccess && configSuccess) {
                _snackbar.emit("Saved, restart failed")
            } else {
                _snackbar.emit("Save failed")
            }
        }
    }

    private fun formatCategoryTitle(key: String, protocol: String): String {
        val protocolToken = when (protocol.lowercase()) { "udp" -> "udp"; "stun" -> "stun"; else -> "tcp" }
        val tokens = key.split("_").toMutableList()
        if (tokens.size > 1 && tokens.last().lowercase() == protocolToken) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ") { token ->
            when (token.lowercase()) {
                "youtube" -> "YouTube"; "googlevideo" -> "GoogleVideo"; "whatsapp" -> "WhatsApp"
                "github" -> "GitHub"; "anydesk" -> "AnyDesk"; "cloudflare" -> "Cloudflare"
                "warp" -> "WARP"; "claude" -> "Claude"; "chatgpt" -> "ChatGPT"
                "tcp" -> "TCP"; "udp" -> "UDP"; "stun" -> "STUN"
                else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    private fun protocolLabel(protocol: String) = when (protocol.lowercase()) { "udp" -> "UDP"; "stun" -> "STUN"; else -> "TCP" }
    private fun pickerType(protocol: String) = when (protocol.lowercase()) { "udp" -> "udp"; "stun" -> "voice"; else -> "tcp" }

    private fun filterTarget(config: StrategyRepository.CategoryConfig): String = when (config.filterMode.lowercase()) {
        "ipset" -> config.ipsetFile.ifEmpty { "ipset" }
        "hostlist" -> config.hostlistFile.ifEmpty { "hostlist" }
        "hostlist-domains" -> config.hostlistDomains.ifEmpty { "hostlist-domains" }
        else -> "none"
    }

    private fun formatStrategyDisplay(name: String): String {
        if (name == "disabled") return "Disabled"
        val display = name.split("_").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
        return if (display.length > 25) display.take(22) + "..." else display
    }
}
