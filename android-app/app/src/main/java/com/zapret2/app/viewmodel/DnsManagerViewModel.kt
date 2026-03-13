package com.zapret2.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.HostsIniData
import com.zapret2.app.data.HostsIniParser
import com.zapret2.app.data.RuntimeConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DnsManagerUiState(
    val hostsData: HostsIniData? = null,
    val selectedPresetIndex: Int = 0,
    val selectedDnsServices: Set<String> = emptySet(),
    val selectedDirectServices: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val loadingText: String = "",
    val errorMessage: String? = null
)

@HiltViewModel
class DnsManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsManagerUiState())
    val uiState: StateFlow<DnsManagerUiState> = _uiState.asStateFlow()
    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val hostsReadPath = "/system/etc/hosts"
    private val hostsWritePath = "/data/adb/modules/zapret2/system/etc/hosts"

    init { loadData() }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Loading hosts.ini...", errorMessage = null) }
            val result = withContext(Dispatchers.IO) { HostsIniParser.parse() }
            if (result.data == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = result.error ?: "Unknown error") }
                return@launch
            }

            val saved = withContext(Dispatchers.IO) { RuntimeConfigStore.readDnsManager() }
            val dns = saved["selected_dns"]?.split("|")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val direct = saved["selected_direct"]?.split("|")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            val presetIdx = saved["dns_preset_index"]?.toIntOrNull() ?: 0

            _uiState.update { it.copy(hostsData = result.data, selectedPresetIndex = presetIdx, selectedDnsServices = dns, selectedDirectServices = direct, isLoading = false) }
        }
    }

    fun selectPreset(index: Int) { _uiState.update { it.copy(selectedPresetIndex = index) } }

    fun toggleDnsService(name: String) {
        _uiState.update {
            val new = it.selectedDnsServices.toMutableSet()
            if (name in new) new.remove(name) else new.add(name)
            it.copy(selectedDnsServices = new)
        }
    }

    fun toggleDirectService(name: String) {
        _uiState.update {
            val new = it.selectedDirectServices.toMutableSet()
            if (name in new) new.remove(name) else new.add(name)
            it.copy(selectedDirectServices = new)
        }
    }

    fun selectAllDns(checked: Boolean) {
        val data = _uiState.value.hostsData ?: return
        _uiState.update { it.copy(selectedDnsServices = if (checked) data.dnsServices.map { s -> s.name }.toSet() else emptySet()) }
    }

    fun selectAllDirect(checked: Boolean) {
        val data = _uiState.value.hostsData ?: return
        _uiState.update { it.copy(selectedDirectServices = if (checked) data.directServices.map { s -> s.name }.toSet() else emptySet()) }
    }

    fun applyDns() {
        val data = _uiState.value.hostsData ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Applying DNS...") }
            val success = withContext(Dispatchers.IO) {
                try {
                    val current = Shell.cmd("cat \"$hostsReadPath\" 2>/dev/null").exec().let { if (it.isSuccess) it.out.joinToString("\n") else "" }
                    val block = HostsIniParser.generateHostsBlock(data, _uiState.value.selectedPresetIndex, _uiState.value.selectedDnsServices, _uiState.value.selectedDirectServices)
                    val merged = HostsIniParser.smartMerge(current, block)
                    if (!writeHostsFile(hostsWritePath, merged)) return@withContext false
                    RuntimeConfigStore.upsertDnsManagerValues(mapOf(
                        "dns_preset_index" to _uiState.value.selectedPresetIndex.toString(),
                        "selected_dns" to _uiState.value.selectedDnsServices.joinToString("|"),
                        "selected_direct" to _uiState.value.selectedDirectServices.joinToString("|")
                    ))
                    true
                } catch (_: Exception) { false }
            }
            _uiState.update { it.copy(isLoading = false) }
            _snackbar.emit(if (success) "DNS applied" else "Failed to apply DNS")
        }
    }

    fun resetDns() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingText = "Resetting DNS...") }
            val success = withContext(Dispatchers.IO) {
                try {
                    val current = Shell.cmd("cat \"$hostsReadPath\" 2>/dev/null").exec().let { if (it.isSuccess) it.out.joinToString("\n") else "" }
                    writeHostsFile(hostsWritePath, HostsIniParser.removeZapretBlock(current))
                } catch (_: Exception) { false }
            }
            _uiState.update { it.copy(isLoading = false) }
            _snackbar.emit(if (success) "DNS reset" else "Failed to reset DNS")
        }
    }

    private fun writeHostsFile(path: String, content: String): Boolean {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n') + "\n"
        val parentDir = path.substringBeforeLast('/')
        Shell.cmd("mkdir -p \"$parentDir\"").exec()
        val tmpFile = java.io.File(context.cacheDir, "zapret2_hosts_${System.currentTimeMillis()}")
        try { tmpFile.writeText(normalized, Charsets.UTF_8) } catch (_: Exception) { tmpFile.delete(); return false }
        tmpFile.setReadable(true, false)
        val success = Shell.cmd("cp \"${tmpFile.absolutePath}\" \"$path.tmp\" && mv \"$path.tmp\" \"$path\"").exec().isSuccess
        tmpFile.delete()
        if (!success) { Shell.cmd("rm -f \"$path.tmp\"").exec(); return false }
        if (path != "/system/etc/hosts") {
            Shell.cmd("mount -o rw,remount / 2>/dev/null; cp \"$path\" /system/etc/hosts 2>/dev/null; mount -o ro,remount / 2>/dev/null").exec()
        }
        return true
    }
}
