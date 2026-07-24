package com.zapret2.app.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.ProtectedTextRead
import com.zapret2.app.data.RuntimeLogRepository
import com.zapret2.app.data.RuntimeLogSelection
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class LogTab { COMMAND, LOGS, WARNINGS }

internal fun LogTab.runtimeSelectionOrNull(): RuntimeLogSelection? = when (this) {
    LogTab.COMMAND -> null
    LogTab.LOGS -> RuntimeLogSelection.MAIN
    LogTab.WARNINGS -> RuntimeLogSelection.WARNINGS
}

enum class LogsLoadState { IDLE, LOADING, READY, ERROR }

private enum class LogClearOutcome { CLEARED, FAILED, BLOCKED }

data class LogsUiState(
    val currentTab: LogTab = LogTab.COMMAND,
    val commandLoadState: LogsLoadState = LogsLoadState.IDLE,
    val outputLoadState: LogsLoadState = LogsLoadState.IDLE,
    val cmdline: String = "",
    val rawCmdline: String = "",
    val logs: String = "",
    val filterText: String = "",
    val autoScroll: Boolean = true,
    val isClearing: Boolean = false,
    val message: UiText? = null,
)

internal sealed interface LogSharePreparation {
    data class Ready(val text: String) : LogSharePreparation
    data object Empty : LogSharePreparation
    data object Rejected : LogSharePreparation
}

internal fun prepareLogShare(
    state: LogsUiState,
    screenStarted: Boolean,
    requestedTab: LogTab,
    requestedText: String,
): LogSharePreparation {
    if (!screenStarted || requestedTab == LogTab.COMMAND || state.currentTab != requestedTab ||
        state.outputLoadState != LogsLoadState.READY || state.isClearing ||
        requestedText != state.logs
    ) return LogSharePreparation.Rejected

    val shareText = redactedBoundedLogShareText(requestedText)
    return if (shareText.isBlank()) {
        LogSharePreparation.Empty
    } else {
        LogSharePreparation.Ready(shareText)
    }
}

private sealed interface CommandFetchResult {
    data class Content(val raw: String, val formatted: String) : CommandFetchResult
    data object Empty : CommandFetchResult
    data object Failed : CommandFetchResult
}

private sealed interface LogFetchResult {
    data class Content(val value: String) : LogFetchResult
    data object Failed : LogFetchResult
}

@HiltViewModel
class LogsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val serviceEventBus: ServiceEventBus,
    private val logRepository: RuntimeLogRepository = RuntimeLogRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private var commandLoadJob: Job? = null
    private var outputLoadJob: Job? = null
    private var screenStarted = false
    private val clearInProgress = AtomicBoolean(false)
    private var commandLoadGeneration = 0L
    private var outputLoadGeneration = 0L
    private var loadedOutputTab: LogTab? = null
    init {
        viewModelScope.launch {
            serviceEventBus.serviceRestarted.collect {
                if (screenStarted) {
                    loadCurrentTab(force = true)
                } else {
                    loadedOutputTab = null
                    _uiState.update {
                        it.copy(
                            commandLoadState = LogsLoadState.IDLE,
                            outputLoadState = LogsLoadState.IDLE,
                        )
                    }
                }
            }
        }
    }

    fun onScreenStarted() {
        if (screenStarted) return
        screenStarted = true
        loadCurrentTab(force = false)
    }

    fun onScreenStopped() {
        screenStarted = false
        commandLoadGeneration++
        outputLoadGeneration++
        commandLoadJob?.cancel()
        commandLoadJob = null
        outputLoadJob?.cancel()
        outputLoadJob = null
        _uiState.update {
            it.copy(
                commandLoadState = if (it.commandLoadState == LogsLoadState.LOADING) {
                    LogsLoadState.IDLE
                } else {
                    it.commandLoadState
                },
                outputLoadState = if (it.outputLoadState == LogsLoadState.LOADING) {
                    LogsLoadState.IDLE
                } else {
                    it.outputLoadState
                },
            )
        }
    }

    fun selectTab(tab: LogTab) {
        if (clearInProgress.get()) return
        if (tab == LogTab.COMMAND) {
            outputLoadGeneration++
            outputLoadJob?.cancel()
            outputLoadJob = null
            _uiState.update {
                if (it.outputLoadState == LogsLoadState.LOADING) {
                    it.copy(outputLoadState = LogsLoadState.IDLE)
                } else {
                    it
                }
            }
        } else {
            commandLoadGeneration++
            commandLoadJob?.cancel()
            commandLoadJob = null
            _uiState.update {
                if (it.commandLoadState == LogsLoadState.LOADING) {
                    it.copy(commandLoadState = LogsLoadState.IDLE)
                } else {
                    it
                }
            }
        }
        _uiState.update { it.copy(currentTab = tab) }
        loadCurrentTab(force = false)
    }

    fun setFilter(text: String) {
        _uiState.update { it.copy(filterText = text.take(MAX_LOG_FILTER_CHARS)) }
    }

    fun toggleAutoScroll() {
        _uiState.update { it.copy(autoScroll = !it.autoScroll) }
    }

    fun refresh() {
        if (!screenStarted || clearInProgress.get()) return
        loadCurrentTab(force = true)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun reportShareEmpty() {
        _uiState.update { it.copy(message = UiText.Resource(R.string.logs_share_empty)) }
    }

    fun reportShareFailed() {
        _uiState.update { it.copy(message = UiText.Resource(R.string.logs_share_failed)) }
    }

    internal fun prepareShare(tab: LogTab, text: String): LogSharePreparation =
        prepareLogShare(_uiState.value, screenStarted, tab, text)

    fun clearLogs(expectedTab: LogTab, confirmed: Boolean = false) {
        if (!confirmed || !screenStarted) return
        if (_uiState.value.currentTab != expectedTab) return
        val selection = expectedTab.runtimeSelectionOrNull() ?: return
        if (!clearInProgress.compareAndSet(false, true)) return
        if (_uiState.value.currentTab != expectedTab) {
            clearInProgress.set(false)
            return
        }
        val tab = expectedTab
        _uiState.update { it.copy(isClearing = true) }
        viewModelScope.launch {
            try {
                val outcome = try {
                    val cleared = ModuleMutationCoordinator.withNonCancellableMutation {
                        withContext(Dispatchers.IO) {
                            logRepository.clear(selection)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (cleared) LogClearOutcome.CLEARED else LogClearOutcome.FAILED
                } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                    LogClearOutcome.BLOCKED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    LogClearOutcome.FAILED
                }
                if (outcome == LogClearOutcome.CLEARED) outputLoadGeneration++
                val resultMessage = UiText.resource(
                    when (outcome) {
                        LogClearOutcome.CLEARED -> R.string.logs_cleared
                        LogClearOutcome.FAILED -> R.string.logs_clear_failed
                        LogClearOutcome.BLOCKED -> R.string.logs_clear_blocked
                    },
                )
                if (tab != _uiState.value.currentTab) {
                    _uiState.update { it.copy(message = resultMessage) }
                    if (screenStarted && _uiState.value.currentTab != LogTab.COMMAND) {
                        loadCurrentTab(force = true)
                    }
                    return@launch
                }
                _uiState.update {
                    if (outcome == LogClearOutcome.CLEARED) {
                        it.copy(
                            logs = "",
                            outputLoadState = LogsLoadState.READY,
                            message = resultMessage,
                        )
                    } else {
                        it.copy(message = resultMessage)
                    }
                }
            } finally {
                _uiState.update { it.copy(isClearing = false) }
                clearInProgress.set(false)
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        if (!screenStarted) return
        val state = _uiState.value
        val isCurrentTrustedPayload = when (state.currentTab) {
            LogTab.COMMAND -> state.commandLoadState == LogsLoadState.READY && text == state.rawCmdline
            LogTab.LOGS,
            LogTab.WARNINGS,
            -> state.outputLoadState == LogsLoadState.READY && text == state.logs
        }
        if (!isCurrentTrustedPayload || text.isBlank() || state.isClearing) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            _uiState.update {
                it.copy(message = UiText.resource(R.string.logs_copied_to_clipboard, label))
            }
        } catch (_: Exception) {
            _uiState.update { it.copy(message = UiText.resource(R.string.logs_copy_failed)) }
        }
    }

    private fun loadCmdline() {
        if (!screenStarted) return
        val generation = ++commandLoadGeneration
        commandLoadJob?.cancel()
        commandLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(commandLoadState = LogsLoadState.LOADING) }
            val result = fetchCmdlineSafely()
            if (generation != commandLoadGeneration) return@launch
            when (result) {
                is CommandFetchResult.Content -> _uiState.update {
                    it.copy(
                        rawCmdline = result.raw,
                        cmdline = result.formatted,
                        commandLoadState = LogsLoadState.READY,
                    )
                }

                CommandFetchResult.Empty -> _uiState.update {
                    it.copy(rawCmdline = "", cmdline = "", commandLoadState = LogsLoadState.READY)
                }

                CommandFetchResult.Failed -> _uiState.update {
                    it.copy(rawCmdline = "", cmdline = "", commandLoadState = LogsLoadState.ERROR)
                }
            }
        }
    }

    private fun loadLogs(showLoading: Boolean = false) {
        if (!screenStarted) return
        val tab = _uiState.value.currentTab
        val generation = ++outputLoadGeneration
        outputLoadJob?.cancel()
        outputLoadJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(outputLoadState = LogsLoadState.LOADING) }
            }
            val result = fetchLogsSafely(tab)
            if (generation == outputLoadGeneration && tab == _uiState.value.currentTab) {
                applyLogResult(tab, result)
            }
        }
    }

    private fun loadCurrentTab(force: Boolean) {
        if (!screenStarted) return
        val state = _uiState.value
        when (state.currentTab) {
            LogTab.COMMAND -> {
                if (force || state.commandLoadState in setOf(LogsLoadState.IDLE, LogsLoadState.ERROR)) {
                    loadCmdline()
                }
            }
            LogTab.LOGS,
            LogTab.WARNINGS,
            -> {
                if (force || loadedOutputTab != state.currentTab ||
                    state.outputLoadState in setOf(LogsLoadState.IDLE, LogsLoadState.ERROR)
                ) {
                    loadLogs(showLoading = true)
                }
            }
        }
    }

    private fun applyLogResult(tab: LogTab, result: LogFetchResult) {
        loadedOutputTab = tab.takeIf { result is LogFetchResult.Content }
        _uiState.update {
            when (result) {
                is LogFetchResult.Content -> it.copy(
                    logs = result.value,
                    outputLoadState = LogsLoadState.READY,
                )

                LogFetchResult.Failed -> it.copy(
                    logs = "",
                    outputLoadState = LogsLoadState.ERROR,
                )
            }
        }
    }

    private fun fetchCmdline(): CommandFetchResult = when (val result = logRepository.readCommandLine()) {
        is ProtectedTextRead.Content -> {
            val raw = result.value.lineSequence().filter { it.isNotBlank() }.joinToString(" ").trim()
            if (raw.isBlank()) CommandFetchResult.Empty else CommandFetchResult.Content(raw, formatCmdline(raw))
        }
        ProtectedTextRead.Absent -> CommandFetchResult.Empty
        ProtectedTextRead.Failed -> CommandFetchResult.Failed
    }

    private suspend fun fetchCmdlineSafely(): CommandFetchResult = try {
        withContext(Dispatchers.IO) { fetchCmdline() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CommandFetchResult.Failed
    }

    private fun fetchLogs(tab: LogTab): LogFetchResult {
        val selection = tab.runtimeSelectionOrNull() ?: return LogFetchResult.Content("")
        return when (val result = logRepository.readLogs(selection)) {
            is ProtectedTextRead.Content -> {
                val lines = result.value.lineSequence().filter {
                    it.isNotBlank() && it.trim().length > 2 && !it.trim().all { character ->
                        character == it.trim()[0]
                    }
                }.toList()
                LogFetchResult.Content(
                    if (tab == LogTab.WARNINGS) {
                        lines.filter(::isWarningOrError).joinToString("\n")
                    } else {
                        lines.joinToString("\n")
                    },
                )
            }
            ProtectedTextRead.Absent -> LogFetchResult.Content("")
            ProtectedTextRead.Failed -> LogFetchResult.Failed
        }
    }

    private suspend fun fetchLogsSafely(tab: LogTab): LogFetchResult = try {
        withContext(Dispatchers.IO) { fetchLogs(tab) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LogFetchResult.Failed
    }

    private fun isWarningOrError(line: String): Boolean {
        val normalized = line.lowercase()
        return normalized.contains("error") || normalized.contains("warn") ||
            normalized.contains("fatal") || normalized.contains("failed") ||
            normalized.contains("permission denied") || normalized.contains("not found")
    }

    private fun formatCmdline(cmdline: String): String {
        if (cmdline.isBlank()) return ""
        val parts = cmdline.split(" --")
        if (parts.isEmpty()) return cmdline
        val executable = parts[0].trim()
        val arguments = parts.drop(1).filter { it.isNotBlank() }.map { "  --${it.trim()}" }
        return if (arguments.isEmpty()) executable else executable + "\n" + arguments.joinToString("\n")
    }

    override fun onCleared() {
        commandLoadJob?.cancel()
        outputLoadJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_LOG_FILTER_CHARS = 256
    }
}
