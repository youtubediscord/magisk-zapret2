package com.zapret2.app.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.zapret2.app.data.NetworkStatsManager
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ControlUiState(
    val isRunning: Boolean = false,
    val statusText: String = "Checking...",
    val uptime: String = "",
    val autostart: Boolean = true,
    val wifiOnly: Boolean = false,
    val moduleVersion: String = "",
    val networkType: String = "Checking...",
    val wifiSsid: String? = null,
    val iptablesActive: Boolean = false,
    val nfqueueRulesCount: Int = 0,
    val processStats: ProcessStats = ProcessStats(),
    val isToggling: Boolean = false,
    val showQuicBanner: Boolean = false,
    val iptablesDetail: NetworkStatsManager.IptablesDetail = NetworkStatsManager.IptablesDetail(),
    val pktOut: Int = 20,
    val pktIn: Int = 10,
    val hasRootAccess: Boolean = true,
    val isModuleInstalled: Boolean = true,
    val nfqueueSupported: Boolean = true
)

data class ProcessStats(
    val pid: String = "",
    val memory: String = "",
    val cpu: String = "",
    val threads: String = "",
    val uptime: String = ""
)

sealed class ControlEvent {
    data class ShowSnackbar(val message: String) : ControlEvent()
    data class ShowUpdateDialog(val release: UpdateManager.Release) : ControlEvent()
    data class ShowErrorDialog(val title: String, val details: String) : ControlEvent()
}

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val networkStatsManager: NetworkStatsManager,
    private val updateManager: UpdateManager,
    private val prefs: SharedPreferences,
    private val serviceEventBus: ServiceEventBus
) : ViewModel() {

    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<ControlEvent> = _events.asSharedFlow()

    private var pollingJob: Job? = null

    private val moduleDir = "/data/adb/modules/zapret2"
    private val startScript = "$moduleDir/zapret2/scripts/zapret-start.sh"
    private val stopScript = "$moduleDir/zapret2/scripts/zapret-stop.sh"
    private val restartScript = "$moduleDir/zapret2/scripts/zapret-restart.sh"

    init {
        loadInitialState()
        startPolling()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            // Check root, module, NFQUEUE in parallel
            val (hasRoot, isModuleInstalled, nfqueueSupported) = withContext(Dispatchers.IO) {
                val root = Shell.cmd("id").exec().isSuccess
                val module = Shell.cmd("[ -d \"$moduleDir\" ] && echo 1").exec().out.firstOrNull() == "1"
                val nfqueue = Shell.cmd("[ -f /proc/net/netfilter/nf_queue ] && echo 1 || cat /proc/net/ip_tables_targets 2>/dev/null | grep -q NFQUEUE && echo 1").exec().out.firstOrNull() == "1"
                Triple(root, module, nfqueue)
            }

            val coreValues = withContext(Dispatchers.IO) { RuntimeConfigStore.readCore() }
            val moduleVersion = withContext(Dispatchers.IO) {
                val result = Shell.cmd("grep 'version=' $moduleDir/module.prop 2>/dev/null").exec()
                result.out.firstOrNull()?.substringAfter("version=")?.trim() ?: ""
            }

            _uiState.update { it.copy(
                hasRootAccess = hasRoot,
                isModuleInstalled = isModuleInstalled,
                nfqueueSupported = nfqueueSupported,
                moduleVersion = moduleVersion,
                autostart = coreValues["autostart"] != "0",
                wifiOnly = coreValues["wifi_only"] == "1",
                pktOut = coreValues["pkt_out"]?.toIntOrNull() ?: 20,
                pktIn = coreValues["pkt_in"]?.toIntOrNull() ?: 10,
                showQuicBanner = !prefs.getBoolean("quic_banner_dismissed", false)
            )}

            checkStatus()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                checkStatus()
                delay(if (_uiState.value.isRunning) 3000L else 10000L)
            }
        }
    }

    private suspend fun checkStatus() {
        withContext(Dispatchers.IO) {
            val isRunning = Shell.cmd("pgrep -f nfqws2 >/dev/null 2>&1").exec().isSuccess
            val netStats = networkStatsManager.getNetworkStats()

            val processStats = if (isRunning) {
                val pidResult = Shell.cmd("pgrep -f nfqws2 | head -1").exec()
                val pid = pidResult.out.firstOrNull()?.trim() ?: ""
                if (pid.isNotEmpty()) {
                    val memResult = Shell.cmd("grep VmRSS /proc/$pid/status 2>/dev/null | awk '{print \$2}'").exec()
                    val mem = memResult.out.firstOrNull()?.trim()?.let { "${it} KB" } ?: ""
                    val threadsResult = Shell.cmd("grep Threads /proc/$pid/status 2>/dev/null | awk '{print \$2}'").exec()
                    val threads = threadsResult.out.firstOrNull()?.trim() ?: ""
                    val uptimeResult = Shell.cmd("ps -o etime= -p $pid 2>/dev/null").exec()
                    val uptime = uptimeResult.out.firstOrNull()?.trim() ?: ""
                    ProcessStats(pid = pid, memory = mem, threads = threads, uptime = uptime)
                } else ProcessStats()
            } else ProcessStats()

            _uiState.update { it.copy(
                isRunning = isRunning,
                statusText = if (isRunning) "Running" else "Stopped",
                uptime = processStats.uptime,
                networkType = networkStatsManager.getNetworkTypeString(netStats.networkType),
                wifiSsid = netStats.wifiSsid,
                iptablesActive = netStats.iptablesActive,
                nfqueueRulesCount = netStats.nfqueueRulesCount,
                iptablesDetail = netStats.iptablesDetail,
                processStats = processStats
            )}
        }
    }

    fun toggleService() {
        if (_uiState.value.isToggling) return
        viewModelScope.launch {
            _uiState.update { it.copy(isToggling = true) }
            val wasRunning = _uiState.value.isRunning

            val result = withContext(Dispatchers.IO) {
                val script = if (wasRunning) stopScript else startScript
                Shell.cmd("sh $script").exec()
            }

            delay(1000) // Brief delay for process state to settle
            checkStatus()
            _uiState.update { it.copy(isToggling = false) }

            if (result.isSuccess) {
                val action = if (wasRunning) "stopped" else "started"
                _events.emit(ControlEvent.ShowSnackbar("Service $action"))
                if (!wasRunning) serviceEventBus.notifyServiceRestarted()
            } else {
                val action = if (wasRunning) "stop" else "start"
                val diagnosticLines = result.out
                    .filter { it.startsWith("DIAGNOSTIC:") }
                    .map { it.removePrefix("DIAGNOSTIC:").trim() }

                if (diagnosticLines.isNotEmpty()) {
                    _events.emit(ControlEvent.ShowErrorDialog(
                        title = "Failed to $action service",
                        details = diagnosticLines.joinToString("\n")
                    ))
                } else {
                    // Try reading error log as fallback
                    val errorLog = withContext(Dispatchers.IO) {
                        val logResult = Shell.cmd("cat /data/local/tmp/nfqws2-error.log 2>/dev/null").exec()
                        if (logResult.isSuccess && logResult.out.isNotEmpty()) {
                            logResult.out.joinToString("\n")
                        } else null
                    }

                    if (errorLog != null) {
                        _events.emit(ControlEvent.ShowErrorDialog(
                            title = "Failed to $action service",
                            details = errorLog
                        ))
                    } else {
                        _events.emit(ControlEvent.ShowErrorDialog(
                            title = "Failed to $action service",
                            details = "No diagnostic information available.\nCheck logs for details."
                        ))
                    }
                }
            }
        }
    }

    fun setAutostart(enabled: Boolean) {
        viewModelScope.launch {
            val success = RuntimeConfigStore.upsertCoreValue("autostart", if (enabled) "1" else "0")
            if (success) {
                _uiState.update { it.copy(autostart = enabled) }
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            val success = RuntimeConfigStore.upsertCoreValue("wifi_only", if (enabled) "1" else "0")
            if (success) {
                _uiState.update { it.copy(wifiOnly = enabled) }
            }
        }
    }

    fun adjustPktOut(value: Int) {
        val clamped = value.coerceIn(1, 100)
        viewModelScope.launch {
            val success = RuntimeConfigStore.upsertCoreValue("pkt_out", clamped.toString())
            if (success) {
                _uiState.update { it.copy(pktOut = clamped) }
                restartService()
            }
        }
    }

    fun adjustPktIn(value: Int) {
        val clamped = value.coerceIn(1, 100)
        viewModelScope.launch {
            val success = RuntimeConfigStore.upsertCoreValue("pkt_in", clamped.toString())
            if (success) {
                _uiState.update { it.copy(pktIn = clamped) }
                restartService()
            }
        }
    }

    fun dismissQuicBanner() {
        prefs.edit().putBoolean("quic_banner_dismissed", true).apply()
        _uiState.update { it.copy(showQuicBanner = false) }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            when (val result = updateManager.checkForUpdates()) {
                is UpdateManager.UpdateResult.Available -> {
                    _events.emit(ControlEvent.ShowUpdateDialog(result.release))
                }
                is UpdateManager.UpdateResult.UpToDate -> {
                    _events.emit(ControlEvent.ShowSnackbar("App is up to date"))
                }
                is UpdateManager.UpdateResult.Error -> {
                    _events.emit(ControlEvent.ShowSnackbar("Update check failed: ${result.message}"))
                }
            }
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        }
    }

    fun downloadAndInstallApk(url: String) {
        viewModelScope.launch {
            when (val result = updateManager.downloadFile(url, "zapret2-update.apk") { }) {
                is UpdateManager.DownloadResult.Success -> updateManager.installApk(result.file)
                is UpdateManager.DownloadResult.Error -> _events.emit(ControlEvent.ShowSnackbar("Download failed: ${result.message}"))
            }
        }
    }

    fun downloadAndInstallModule(url: String) {
        viewModelScope.launch {
            when (val result = updateManager.downloadFile(url, "zapret2-module.zip") { }) {
                is UpdateManager.DownloadResult.Success -> {
                    val (success, needsReboot) = updateManager.installModule(result.file)
                    val msg = when {
                        success && needsReboot -> "Module installed. Reboot required."
                        success -> "Module updated successfully"
                        else -> "Module installation failed"
                    }
                    _events.emit(ControlEvent.ShowSnackbar(msg))
                    if (success && !needsReboot) {
                        delay(1000)
                        checkStatus()
                    }
                }
                is UpdateManager.DownloadResult.Error -> _events.emit(ControlEvent.ShowSnackbar("Download failed: ${result.message}"))
            }
        }
    }

    private suspend fun restartService() {
        if (!_uiState.value.isRunning) return
        withContext(Dispatchers.IO) {
            Shell.cmd("sh $restartScript").exec()
        }
        serviceEventBus.notifyServiceRestarted()
        delay(1000)
        checkStatus()
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
