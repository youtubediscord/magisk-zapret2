package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.ServiceEventBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConfigEditorUiState(
    val commandText: String = "",
    val isLoading: Boolean = false,
    val actionsEnabled: Boolean = true,
    val showModeDialog: Boolean = false
)

@HiltViewModel
class ConfigEditorViewModel @Inject constructor(
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfigEditorUiState())
    val uiState: StateFlow<ConfigEditorUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val moduleDir = "/data/adb/modules/zapret2"
    private val commandFile = "$moduleDir/zapret2/cmdline.txt"
    private val runtimeCmdlineFile = "/data/local/tmp/nfqws2-cmdline.txt"
    private val restartScript = "$moduleDir/zapret2/scripts/zapret-restart.sh"

    init { loadCommandLine() }

    fun loadCommandLine() {
        viewModelScope.launch {
            _uiState.update { it.copy(actionsEnabled = false) }
            val text = withContext(Dispatchers.IO) {
                val manual = Shell.cmd("cat \"$commandFile\" 2>/dev/null").exec()
                if (manual.isSuccess && manual.out.isNotEmpty()) formatForEditor(manual.out.joinToString("\n"))
                else {
                    val runtime = Shell.cmd("cat \"$runtimeCmdlineFile\" 2>/dev/null").exec()
                    if (runtime.isSuccess && runtime.out.isNotEmpty()) formatForEditor(runtime.out.joinToString("\n"))
                    else ""
                }
            }
            _uiState.update { it.copy(commandText = text, actionsEnabled = true) }
        }
    }

    fun updateCommandText(text: String) { _uiState.update { it.copy(commandText = text) } }

    fun saveCommandLine(restart: Boolean = false, forceCmdline: Boolean = false) {
        val text = _uiState.value.commandText.replace("\r\n", "\n").trimEnd('\n', '\r')
        if (text.isBlank()) { viewModelScope.launch { _snackbar.emit("Command line is empty") }; return }

        viewModelScope.launch {
            _uiState.update { it.copy(actionsEnabled = false) }
            val (saved, restarted) = withContext(Dispatchers.IO) {
                if (!Shell.cmd(buildSafeWriteCommand(commandFile, text)).exec().isSuccess) return@withContext Pair(false, false)
                if (forceCmdline) {
                    RuntimeConfigStore.setActiveModeValues(RuntimeConfigStore.CoreSettingsUpdate(presetMode = "cmdline", customCmdlineFile = "cmdline.txt"))
                }
                if (!restart) return@withContext Pair(true, true)
                Pair(true, Shell.cmd("sh $restartScript").exec().isSuccess)
            }
            _uiState.update { it.copy(actionsEnabled = true) }
            when {
                !saved -> _snackbar.emit("Failed to save command line")
                restart && restarted -> { _snackbar.emit("Saved and restarted"); serviceEventBus.notifyServiceRestarted() }
                restart -> _snackbar.emit("Saved, restart failed")
                else -> _snackbar.emit("Command line saved")
            }
        }
    }

    fun showModeDialog() { _uiState.update { it.copy(showModeDialog = true) } }
    fun dismissModeDialog() { _uiState.update { it.copy(showModeDialog = false) } }

    suspend fun isCmdlineMode(): Boolean = withContext(Dispatchers.IO) {
        val mode = (RuntimeConfigStore.readCoreValue("preset_mode") ?: "categories").lowercase()
        mode in listOf("cmdline", "manual", "raw")
    }

    private fun formatForEditor(raw: String): String {
        val stripped = stripBinaryPrefix(raw.replace("\r\n", "\n").replace("\r", "\n"))
        if (stripped.isBlank()) return ""
        return if (stripped.contains('\n')) stripped.replace(Regex("\\n[ \t]+"), "\n").trim()
        else stripped.replace(" --", "\n--")
    }

    private fun stripBinaryPrefix(cmdline: String): String {
        val trimmed = cmdline.trimStart()
        if (trimmed.isEmpty()) return ""
        val first = trimmed.substringBefore(' ', trimmed)
        return if (first == "nfqws2" || first.endsWith("/nfqws2")) trimmed.substringAfter(' ', "").trimStart() else trimmed
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var d = "__ZAPRET_CMDLINE_EOF__"; while (content.contains(d)) d += "_X"
        return "cat <<'$d' > \"$path\"\n$content\n$d"
    }
}
