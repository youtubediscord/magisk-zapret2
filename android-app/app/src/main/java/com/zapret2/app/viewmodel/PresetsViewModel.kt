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

data class PresetEntry(val fileName: String, val displayName: String)

data class PresetsUiState(
    val activeMode: String = "categories",
    val activePresetFile: String = "",
    val activeCmdlineFile: String = "cmdline.txt",
    val presets: List<PresetEntry> = emptyList(),
    val isLoading: Boolean = false,
    val loadingText: String = "",
    val editingPreset: Pair<String, String>? = null // fileName to content
)

@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(PresetsUiState())
    val uiState: StateFlow<PresetsUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val modDir = "/data/adb/modules/zapret2"
    private val presetsDir = "$modDir/zapret2/presets"
    private val restartScript = "$modDir/zapret2/scripts/zapret-restart.sh"

    init { loadPresets() }

    fun loadPresets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Loading presets...") }
            val core = withContext(Dispatchers.IO) { RuntimeConfigStore.readCore() }
            val mode = (core["preset_mode"] ?: "categories").ifEmpty { "categories" }.lowercase()
            val presetFile = (core["preset_file"] ?: "").trim()
            val cmdlineFile = (core["custom_cmdline_file"] ?: "cmdline.txt").trim().ifEmpty { "cmdline.txt" }

            val entries = withContext(Dispatchers.IO) {
                val result = Shell.cmd("ls -1 \"$presetsDir\" 2>/dev/null").exec()
                if (!result.isSuccess) emptyList()
                else result.out.map { it.trim() }.filter { it.isNotEmpty() && it.endsWith(".txt", true) && !it.startsWith("_") }
                    .sortedBy { it.lowercase() }.map { PresetEntry(it, it.removeSuffix(".txt")) }
            }

            _uiState.update { it.copy(activeMode = mode, activePresetFile = presetFile, activeCmdlineFile = cmdlineFile, presets = entries, isLoading = false) }
        }
    }

    fun applyPreset(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Applying $fileName...") }
            val (saved, restarted) = withContext(Dispatchers.IO) {
                val s = RuntimeConfigStore.setActiveModeValues(RuntimeConfigStore.CoreSettingsUpdate(presetMode = "file", presetFile = fileName))
                if (!s) return@withContext Pair(false, false)
                Pair(true, Shell.cmd("sh $restartScript").exec().isSuccess)
            }
            _uiState.update { it.copy(isLoading = false) }
            when {
                saved && restarted -> { _snackbar.emit("$fileName applied"); serviceEventBus.notifyServiceRestarted(); loadPresets() }
                saved -> { _snackbar.emit("Preset selected, restart failed"); loadPresets() }
                else -> _snackbar.emit("Failed to apply preset")
            }
        }
    }

    fun switchToCategoriesMode() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Switching to categories mode...") }
            val (saved, restarted) = withContext(Dispatchers.IO) {
                val s = RuntimeConfigStore.setActiveModeValues(RuntimeConfigStore.CoreSettingsUpdate(presetMode = "categories"))
                if (!s) return@withContext Pair(false, false)
                Pair(true, Shell.cmd("sh $restartScript").exec().isSuccess)
            }
            _uiState.update { it.copy(isLoading = false) }
            when {
                saved && restarted -> { _snackbar.emit("Categories mode enabled"); serviceEventBus.notifyServiceRestarted(); loadPresets() }
                saved -> { _snackbar.emit("Mode switched, restart failed"); loadPresets() }
                else -> _snackbar.emit("Failed to switch mode")
            }
        }
    }

    fun openPresetEditor(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Loading $fileName...") }
            val content = withContext(Dispatchers.IO) {
                val result = Shell.cmd("cat \"$presetsDir/$fileName\" 2>/dev/null").exec()
                if (result.isSuccess) result.out.joinToString("\n") else null
            }
            _uiState.update { it.copy(isLoading = false) }
            if (content != null) _uiState.update { it.copy(editingPreset = fileName to content) }
            else _snackbar.emit("Failed to read preset")
        }
    }

    fun closePresetEditor() { _uiState.update { it.copy(editingPreset = null) } }

    fun savePreset(fileName: String, content: String, applyAfterSave: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(editingPreset = null, isLoading = true, loadingText = if (applyAfterSave) "Saving and applying..." else "Saving preset...") }
            val (saved, restarted) = withContext(Dispatchers.IO) {
                val cmd = buildSafeWriteCommand("$presetsDir/$fileName", content.replace("\r\n", "\n"))
                if (!Shell.cmd(cmd).exec().isSuccess) return@withContext Pair(false, false)
                if (!applyAfterSave) return@withContext Pair(true, true)
                val s = RuntimeConfigStore.setActiveModeValues(RuntimeConfigStore.CoreSettingsUpdate(presetMode = "file", presetFile = fileName))
                if (!s) return@withContext Pair(false, false)
                Pair(true, Shell.cmd("sh $restartScript").exec().isSuccess)
            }
            _uiState.update { it.copy(isLoading = false) }
            when {
                !saved -> _snackbar.emit("Failed to save preset")
                applyAfterSave && restarted -> { _snackbar.emit("Preset saved and applied"); serviceEventBus.notifyServiceRestarted() }
                applyAfterSave -> _snackbar.emit("Preset saved, restart failed")
                else -> _snackbar.emit("Preset saved")
            }
            loadPresets()
        }
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var delimiter = "__ZAPRET_PRESET_EOF__"
        while (content.contains(delimiter)) delimiter += "_X"
        return "cat <<'$delimiter' > \"$path\"\n$content\n$delimiter"
    }
}
