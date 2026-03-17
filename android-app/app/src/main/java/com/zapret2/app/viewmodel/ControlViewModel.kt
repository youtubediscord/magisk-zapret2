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
    val nfqueueSupported: Boolean = true,
    val isUpdating: Boolean = false,
    val updateProgress: Float = 0f,
    val updateStatus: String = ""
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
                        // Tier 2.5: check stderr from the script execution
                        val stderrLines = result.err
                        if (stderrLines.isNotEmpty()) {
                            _events.emit(ControlEvent.ShowErrorDialog(
                                title = "Failed to $action service",
                                details = stderrLines.joinToString("\n")
                            ))
                        } else {
                            // Tier 2.75: read last 20 lines of main log file
                            val mainLog = withContext(Dispatchers.IO) {
                                val logResult = Shell.cmd("tail -n 20 /data/local/tmp/zapret2.log 2>/dev/null").exec()
                                if (logResult.isSuccess && logResult.out.isNotEmpty()) {
                                    logResult.out.joinToString("\n")
                                } else null
                            }

                            if (mainLog != null) {
                                _events.emit(ControlEvent.ShowErrorDialog(
                                    title = "Failed to $action service",
                                    details = mainLog
                                ))
                            } else {
                                // Tier 3: generic fallback
                                _events.emit(ControlEvent.ShowErrorDialog(
                                    title = "Failed to $action service",
                                    details = "No diagnostic information available.\nCheck logs for details."
                                ))
                            }
                        }
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

    fun updateAll(apkUrl: String?, moduleUrl: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, updateProgress = 0f, updateStatus = "Подготовка...") }

            val result = withContext(Dispatchers.IO) {
                updateManager.updateAll(apkUrl, moduleUrl) { progress, status ->
                    // StateFlow.update is thread-safe, no need for Dispatchers.Main
                    _uiState.update { it.copy(updateProgress = progress, updateStatus = status) }
                }
            }

            _uiState.update { it.copy(isUpdating = false, updateProgress = 0f, updateStatus = "") }

            result.onSuccess { needsReboot ->
                if (needsReboot) {
                    _events.emit(ControlEvent.ShowSnackbar("Обновление установлено. Требуется перезагрузка."))
                } else {
                    _events.emit(ControlEvent.ShowSnackbar("Обновление завершено"))
                    delay(1000)
                    checkStatus()
                }
            }.onFailure { error ->
                _events.emit(ControlEvent.ShowErrorDialog(
                    title = "Ошибка обновления",
                    details = error.message ?: "Неизвестная ошибка"
                ))
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
