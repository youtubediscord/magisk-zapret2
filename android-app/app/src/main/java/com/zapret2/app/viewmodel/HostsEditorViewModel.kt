package com.zapret2.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostsEditorUiState(val content: String = "", val isLoading: Boolean = false, val actionsEnabled: Boolean = true)

@HiltViewModel
class HostsEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostsEditorUiState())
    val uiState: StateFlow<HostsEditorUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()
    private val hostsFile = "/system/etc/hosts"
    private val hostsWritePath = "/data/adb/modules/zapret2/system/etc/hosts"

    init { loadHosts() }

    fun loadHosts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, actionsEnabled = false) }
            val content = withContext(Dispatchers.IO) {
                val r = Shell.cmd("cat \"$hostsFile\" 2>/dev/null").exec()
                if (r.isSuccess) r.out.joinToString("\n") else null
            }
            if (content != null) _uiState.update { it.copy(content = content, isLoading = false, actionsEnabled = true) }
            else { _snackbar.emit("Failed to read hosts file"); _uiState.update { it.copy(isLoading = false, actionsEnabled = true) } }
        }
    }

    fun updateContent(text: String) { _uiState.update { it.copy(content = text) } }

    fun saveHosts() {
        viewModelScope.launch {
            val content = _uiState.value.content.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n') + "\n"
            if (content.isBlank()) { _snackbar.emit("Hosts file is empty"); return@launch }
            _uiState.update { it.copy(isLoading = true, actionsEnabled = false) }
            val saved = withContext(Dispatchers.IO) {
                val parentDir = hostsWritePath.substringBeforeLast('/')
                Shell.cmd("mkdir -p \"$parentDir\"").exec()
                val tmpFile = java.io.File(context.cacheDir, "zapret2_hosts_${System.currentTimeMillis()}")
                try { tmpFile.writeText(content, Charsets.UTF_8) } catch (_: Exception) { tmpFile.delete(); return@withContext false }
                tmpFile.setReadable(true, false)
                val success = Shell.cmd("cp \"${tmpFile.absolutePath}\" \"$hostsWritePath.tmp\" && mv \"$hostsWritePath.tmp\" \"$hostsWritePath\"").exec().isSuccess
                tmpFile.delete()
                if (!success) { Shell.cmd("rm -f \"$hostsWritePath.tmp\"").exec(); return@withContext false }
                // Try to apply immediately
                Shell.cmd("mount -o rw,remount / 2>/dev/null; cp \"$hostsWritePath\" /system/etc/hosts 2>/dev/null; mount -o ro,remount / 2>/dev/null").exec()
                true
            }
            _uiState.update { it.copy(isLoading = false, actionsEnabled = true) }
            _snackbar.emit(if (saved) "Hosts file saved" else "Failed to save hosts file")
        }
    }
}
