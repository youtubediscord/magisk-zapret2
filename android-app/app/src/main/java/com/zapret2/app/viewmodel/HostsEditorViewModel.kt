package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostsEditorUiState(val content: String = "", val isLoading: Boolean = false, val actionsEnabled: Boolean = true)

@HiltViewModel
class HostsEditorViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HostsEditorUiState())
    val uiState: StateFlow<HostsEditorUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()
    private val hostsFile = "/system/etc/hosts"

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
            val saved = withContext(Dispatchers.IO) { Shell.cmd(buildSafeWriteCommand(hostsFile, content)).exec().isSuccess }
            _uiState.update { it.copy(isLoading = false, actionsEnabled = true) }
            _snackbar.emit(if (saved) "Hosts file saved" else "Failed to save hosts file")
        }
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var d = "__ZAPRET_HOSTS_EOF__"; while (content.contains(d)) d += "_X"
        return "cat <<'$d' > \"$path\"\n$content\n$d"
    }
}
