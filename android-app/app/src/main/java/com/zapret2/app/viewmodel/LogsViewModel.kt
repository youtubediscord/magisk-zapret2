package com.zapret2.app.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.ServiceEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LogTab { COMMAND, LOGS, WARNINGS }

data class LogsUiState(
    val currentTab: LogTab = LogTab.COMMAND,
    val cmdline: String = "",
    val rawCmdline: String = "",
    val logs: String = "",
    val filterText: String = "",
    val autoScroll: Boolean = true
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private var pollingJob: Job? = null
    private val logFile = "/data/local/tmp/zapret2.log"
    private val errorFile = "/data/local/tmp/nfqws2-error.log"
    private val cmdlineFile = "/data/local/tmp/nfqws2-cmdline.txt"

    init {
        loadCmdline()
        viewModelScope.launch {
            serviceEventBus.serviceRestarted.collect { loadCmdline(); loadLogs() }
        }
    }

    fun selectTab(tab: LogTab) {
        _uiState.update { it.copy(currentTab = tab) }
        when (tab) {
            LogTab.COMMAND -> { stopPolling(); loadCmdline() }
            LogTab.LOGS, LogTab.WARNINGS -> { _uiState.update { it.copy(logs = "Loading...") }; loadLogs(); startPolling() }
        }
    }

    fun setFilter(text: String) { _uiState.update { it.copy(filterText = text) } }
    fun toggleAutoScroll() { _uiState.update { it.copy(autoScroll = !it.autoScroll) } }

    fun refresh() { loadCmdline(); loadLogs() }

    fun clearLogs() {
        viewModelScope.launch {
            val tab = _uiState.value.currentTab
            val success = withContext(Dispatchers.IO) {
                val cmd = when (tab) {
                    LogTab.COMMAND -> return@withContext true
                    LogTab.LOGS -> "> $logFile"
                    LogTab.WARNINGS -> "{ > \"$errorFile\"; > \"$logFile\"; }"
                }
                Shell.cmd(cmd).exec().isSuccess
            }
            if (success) { _uiState.update { it.copy(logs = "") }; _snackbar.emit("Logs cleared") }
            else _snackbar.emit("Failed to clear logs")
        }
    }

    fun copyToClipboard(label: String, text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            viewModelScope.launch { _snackbar.emit("$label copied to clipboard") }
        } catch (_: Exception) {
            viewModelScope.launch { _snackbar.emit("Failed to copy") }
        }
    }

    private fun loadCmdline() {
        viewModelScope.launch {
            val (raw, formatted) = withContext(Dispatchers.IO) { fetchCmdline() }
            _uiState.update { it.copy(rawCmdline = raw, cmdline = formatted.ifBlank { "No command line available" }) }
        }
    }

    private fun loadLogs() {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) { fetchLogs() }
            _uiState.update { it.copy(logs = logs) }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(3000)
                val logs = withContext(Dispatchers.IO) { fetchLogs() }
                if (logs != _uiState.value.logs) _uiState.update { it.copy(logs = logs) }
            }
        }
    }

    private fun stopPolling() { pollingJob?.cancel(); pollingJob = null }

    private fun fetchCmdline(): Pair<String, String> {
        return try {
            val result = Shell.cmd("cat $cmdlineFile").exec()
            if (result.isSuccess && !result.out.isNullOrEmpty()) {
                val raw = result.out.filter { it.isNotBlank() }.joinToString(" ").trim()
                Pair(raw, formatCmdline(raw))
            } else Pair("", "")
        } catch (_: Exception) { Pair("", "") }
    }

    private fun fetchLogs(): String {
        val tab = _uiState.value.currentTab
        val command = when (tab) {
            LogTab.COMMAND -> return ""
            LogTab.LOGS -> "tail -n 500 $logFile"
            LogTab.WARNINGS -> "{ if [ -f \"$logFile\" ]; then tail -n 500 \"$logFile\"; fi; if [ -f \"$errorFile\" ]; then tail -n 500 \"$errorFile\"; fi; }"
        }
        return try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess && !result.out.isNullOrEmpty()) {
                val lines = result.out.filter { it.isNotBlank() && it.trim().length > 2 && !it.trim().all { c -> c == it.trim()[0] } }
                if (tab == LogTab.WARNINGS) lines.filter { isWarningOrError(it) }.joinToString("\n")
                else lines.joinToString("\n")
            } else ""
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun isWarningOrError(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("error") || l.contains("warn") || l.contains("fatal") || l.contains("failed") || l.contains("permission denied") || l.contains("not found")
    }

    private fun formatCmdline(cmdline: String): String {
        if (cmdline.isBlank()) return ""
        val parts = cmdline.split(" --")
        if (parts.isEmpty()) return cmdline
        val exe = parts[0].trim()
        val args = parts.drop(1).filter { it.isNotBlank() }.map { "  --${it.trim()}" }
        return if (args.isEmpty()) exe else exe + "\n" + args.joinToString("\n")
    }

    override fun onCleared() { stopPolling(); super.onCleared() }
}
