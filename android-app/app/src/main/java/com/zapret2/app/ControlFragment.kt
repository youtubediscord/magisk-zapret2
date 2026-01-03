package com.zapret2.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*

class ControlFragment : Fragment() {

    companion object {
        private const val PREFS_NAME = "zapret2_prefs"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        // PKT limits for packet interception settings
        private const val PKT_MIN = 1
        private const val PKT_MAX = 100
        private const val PKT_OUT_DEFAULT = 20
        private const val PKT_IN_DEFAULT = 10
    }

    // UI Elements
    private lateinit var viewStatusIndicator: View
    private lateinit var textStatusTitle: TextView
    private lateinit var textStatusValue: TextView
    private lateinit var textUptime: TextView
    private lateinit var buttonToggle: MaterialButton
    private lateinit var textToggleHint: TextView
    private lateinit var rowAutostart: LinearLayout
    private lateinit var rowWifiOnly: LinearLayout
    private lateinit var switchAutostart: MaterialSwitch
    private lateinit var switchWifiOnly: MaterialSwitch
    private lateinit var textAutostartValue: TextView
    private lateinit var textWifiOnlyValue: TextView
    private lateinit var textAppVersion: TextView
    private lateinit var textModuleVersion: TextView
    private lateinit var textRootStatus: TextView
    private lateinit var textNfqueueStatus: TextView
    private lateinit var buttonUpdateModule: MaterialButton

    // Process Statistics UI Elements
    private lateinit var textProcessStatsHeader: TextView
    private lateinit var layoutProcessStats: LinearLayout
    private lateinit var textMemoryUsage: TextView
    private lateinit var textCpuUsage: TextView
    private lateinit var textProcessUptime: TextView
    private lateinit var textThreadCount: TextView
    private lateinit var textProcessPid: TextView

    // Network Stats UI Elements
    private lateinit var iconNetworkType: ImageView
    private lateinit var textNetworkType: TextView
    private lateinit var rowWifiName: LinearLayout
    private lateinit var textWifiName: TextView
    private lateinit var textIptablesStatus: TextView
    private lateinit var textNfqueueRulesCount: TextView

    // PKT Settings UI Elements
    private lateinit var textPktOutValue: TextView
    private lateinit var textPktInValue: TextView
    private lateinit var buttonPktOutMinus: MaterialButton
    private lateinit var buttonPktOutPlus: MaterialButton
    private lateinit var buttonPktInMinus: MaterialButton
    private lateinit var buttonPktInPlus: MaterialButton

    // PKT current values
    private var pktOutValue: Int = PKT_OUT_DEFAULT
    private var pktInValue: Int = PKT_IN_DEFAULT

    // Network stats manager
    private lateinit var networkStatsManager: NetworkStatsManager

    // Status tracking
    private var isRunning = false
    private var statusPollingJob: Job? = null
    private var serviceStartTime: Long = 0L  // Unix timestamp when service started
    private var currentPid: String? = null   // Current nfqws2 process ID
    private var lastCpuTime: Long = 0L       // For CPU usage calculation
    private var lastCpuCheckTime: Long = 0L  // Timestamp of last CPU check

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val CONFIG = "$MODDIR/zapret2/config.sh"
    private val SCRIPTS = "$MODDIR/zapret2/scripts"
    private val PIDFILE = "/data/local/tmp/nfqws2.pid"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        checkRootAndModule()

        // Auto-check for updates on first launch (with 2-second delay)
        scheduleAutoUpdateCheck()
    }

    /**
     * Schedules automatic update check if auto-check is enabled
     * and enough time has passed since the last check.
     */
    private fun scheduleAutoUpdateCheck() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_UPDATES, true) // Enabled by default
        val lastCheckTime = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        val currentTime = System.currentTimeMillis()

        // Check if auto-check is enabled and enough time has passed
        if (autoCheckEnabled && (currentTime - lastCheckTime) > UPDATE_CHECK_INTERVAL_MS) {
            viewLifecycleOwner.lifecycleScope.launch {
                // Wait 2 seconds before checking
                delay(2000)

                // Ensure fragment is still attached
                if (!isAdded) return@launch

                // Perform silent update check
                performSilentUpdateCheck()

                // Save last check time
                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, currentTime).apply()
            }
        }
    }

    /**
     * Performs a silent update check without showing loading state on the button.
     * Only shows dialog if update is available.
     */
    private suspend fun performSilentUpdateCheck() {
        val context = context ?: return
        val updateManager = UpdateManager(context)
        val result = updateManager.checkForUpdates()

        if (!isAdded) return

        when (result) {
            is UpdateManager.UpdateResult.Available -> {
                // Show update dialog
                val dialog = UpdateDialogFragment.newInstance(
                    release = result.release,
                    currentVersion = updateManager.getCurrentVersion()
                )
                dialog.show(parentFragmentManager, "update_dialog")
            }
            is UpdateManager.UpdateResult.UpToDate -> {
                // Silent - do nothing
            }
            is UpdateManager.UpdateResult.Error -> {
                // Silent - do nothing on error for auto-check
            }
        }
    }

    private fun initViews(view: View) {
        viewStatusIndicator = view.findViewById(R.id.viewStatusIndicator)
        textStatusTitle = view.findViewById(R.id.textStatusTitle)
        textStatusValue = view.findViewById(R.id.textStatusValue)
        textUptime = view.findViewById(R.id.textUptime)
        buttonToggle = view.findViewById(R.id.buttonToggle)
        textToggleHint = view.findViewById(R.id.textToggleHint)

        // Settings rows
        rowAutostart = view.findViewById(R.id.rowAutostart)
        rowWifiOnly = view.findViewById(R.id.rowWifiOnly)
        switchAutostart = view.findViewById(R.id.switchAutostart)
        switchWifiOnly = view.findViewById(R.id.switchWifiOnly)
        textAutostartValue = view.findViewById(R.id.textAutostartValue)
        textWifiOnlyValue = view.findViewById(R.id.textWifiOnlyValue)

        textAppVersion = view.findViewById(R.id.textAppVersion)
        textModuleVersion = view.findViewById(R.id.textModuleVersion)
        textRootStatus = view.findViewById(R.id.textRootStatus)
        textNfqueueStatus = view.findViewById(R.id.textNfqueueStatus)
        buttonUpdateModule = view.findViewById(R.id.buttonUpdateModule)

        // Process Statistics views
        textProcessStatsHeader = view.findViewById(R.id.textProcessStatsHeader)
        layoutProcessStats = view.findViewById(R.id.layoutProcessStats)
        textMemoryUsage = view.findViewById(R.id.textMemoryUsage)
        textCpuUsage = view.findViewById(R.id.textCpuUsage)
        textProcessUptime = view.findViewById(R.id.textProcessUptime)
        textThreadCount = view.findViewById(R.id.textThreadCount)
        textProcessPid = view.findViewById(R.id.textProcessPid)

        // Set APK version from BuildConfig (static, doesn't need refresh)
        textAppVersion.text = "v${BuildConfig.VERSION_NAME}"

        // Network Stats views
        iconNetworkType = view.findViewById(R.id.iconNetworkType)
        textNetworkType = view.findViewById(R.id.textNetworkType)
        rowWifiName = view.findViewById(R.id.rowWifiName)
        textWifiName = view.findViewById(R.id.textWifiName)
        textIptablesStatus = view.findViewById(R.id.textIptablesStatus)
        textNfqueueRulesCount = view.findViewById(R.id.textNfqueueRulesCount)

        // PKT Settings views
        textPktOutValue = view.findViewById(R.id.textPktOutValue)
        textPktInValue = view.findViewById(R.id.textPktInValue)
        buttonPktOutMinus = view.findViewById(R.id.buttonPktOutMinus)
        buttonPktOutPlus = view.findViewById(R.id.buttonPktOutPlus)
        buttonPktInMinus = view.findViewById(R.id.buttonPktInMinus)
        buttonPktInPlus = view.findViewById(R.id.buttonPktInPlus)

        // Initialize network stats manager
        networkStatsManager = NetworkStatsManager(requireContext())
    }

    private fun setupListeners() {
        buttonToggle.setOnClickListener {
            if (isRunning) {
                stopService()
            } else {
                startService()
            }
        }

        // Row click toggles the switch
        rowAutostart.setOnClickListener {
            switchAutostart.toggle()
        }

        rowWifiOnly.setOnClickListener {
            switchWifiOnly.toggle()
        }

        switchAutostart.setOnCheckedChangeListener { _, isChecked ->
            textAutostartValue.text = if (isChecked) "On" else "Off"
            saveAutostart(isChecked)
        }

        switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            textWifiOnlyValue.text = if (isChecked) "On" else "Off"
            saveWifiOnly(isChecked)
        }

        buttonUpdateModule.setOnClickListener {
            checkForUpdates()
        }

        // PKT_OUT controls
        buttonPktOutMinus.setOnClickListener {
            if (pktOutValue > PKT_MIN) {
                pktOutValue--
                textPktOutValue.text = pktOutValue.toString()
                savePktOut(pktOutValue)
            }
        }

        buttonPktOutPlus.setOnClickListener {
            if (pktOutValue < PKT_MAX) {
                pktOutValue++
                textPktOutValue.text = pktOutValue.toString()
                savePktOut(pktOutValue)
            }
        }

        // PKT_IN controls
        buttonPktInMinus.setOnClickListener {
            if (pktInValue > PKT_MIN) {
                pktInValue--
                textPktInValue.text = pktInValue.toString()
                savePktIn(pktInValue)
            }
        }

        buttonPktInPlus.setOnClickListener {
            if (pktInValue < PKT_MAX) {
                pktInValue++
                textPktInValue.text = pktInValue.toString()
                savePktIn(pktInValue)
            }
        }
    }

    private fun checkRootAndModule() {
        viewLifecycleOwner.lifecycleScope.launch {
            textStatusValue.text = "Checking..."
            textRootStatus.text = "Checking..."
            textNfqueueStatus.text = "Checking..."

            // Check root access
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    Shell.getShell().isRoot
                } catch (e: Exception) {
                    false
                }
            }

            if (!hasRoot) {
                textStatusValue.text = "Root not available!"
                textRootStatus.text = "Not granted"
                textRootStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                setStatus(Status.ERROR)
                disableControls()
                return@launch
            }

            textRootStatus.text = "Granted"
            textRootStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))

            // Check module exists
            val moduleExists = withContext(Dispatchers.IO) {
                Shell.cmd("[ -d $MODDIR ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"
            }

            if (!moduleExists) {
                textStatusValue.text = "Module not installed!"
                setStatus(Status.ERROR)
                disableControls()
                return@launch
            }

            // Check for nfqws2 binary
            val binaryExists = withContext(Dispatchers.IO) {
                Shell.cmd("[ -f $MODDIR/zapret2/nfqws2 ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"
            }

            if (!binaryExists) {
                textStatusValue.text = "nfqws2 not found!"
                setStatus(Status.ERROR)
                return@launch
            }

            // Check NFQUEUE support - multiple methods
            val nfqueueSupported = withContext(Dispatchers.IO) {
                // Method 1: Check /proc/net/netfilter/nf_queue (traditional)
                val method1 = Shell.cmd("[ -f /proc/net/netfilter/nf_queue ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                // Method 2: Check /proc/net/netfilter/nfnetlink_queue
                val method2 = Shell.cmd("[ -f /proc/net/netfilter/nfnetlink_queue ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                // Method 3: Check if xt_NFQUEUE module exists or is built-in
                val method3 = Shell.cmd("grep -q NFQUEUE /proc/net/ip_tables_targets 2>/dev/null && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                // Method 4: Check kernel config (if available)
                val method4 = Shell.cmd("zcat /proc/config.gz 2>/dev/null | grep -q CONFIG_NETFILTER_NETLINK_QUEUE=y && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                method1 || method2 || method3 || method4
            }

            if (nfqueueSupported) {
                textNfqueueStatus.text = "Supported"
                textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
            } else {
                // Even if detection fails, NFQUEUE might still work - show warning instead of error
                textNfqueueStatus.text = "Unknown (may work)"
                textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }

            // Load module version
            loadModuleVersion()

            // Load config
            loadConfig()

            // Check initial status
            checkStatus()
        }
    }

    private fun loadModuleVersion() {
        viewLifecycleOwner.lifecycleScope.launch {
            val version = withContext(Dispatchers.IO) {
                Shell.cmd("grep 'version=' $MODDIR/module.prop | cut -d= -f2").exec()
                    .out.firstOrNull() ?: "Unknown"
            }
            textModuleVersion.text = "v$version"
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CONFIG 2>/dev/null").exec().out.joinToString("\n")
            }

            // Parse AUTOSTART
            val autostartValue = parseConfigValue(config, "AUTOSTART")
            val isAutostartEnabled = autostartValue == "1"
            switchAutostart.isChecked = isAutostartEnabled
            textAutostartValue.text = if (isAutostartEnabled) "On" else "Off"

            // Parse WIFI_ONLY (if exists)
            val wifiOnlyValue = parseConfigValue(config, "WIFI_ONLY")
            val isWifiOnlyEnabled = wifiOnlyValue == "1"
            switchWifiOnly.isChecked = isWifiOnlyEnabled
            textWifiOnlyValue.text = if (isWifiOnlyEnabled) "On" else "Off"

            // Parse PKT_OUT (if exists)
            val pktOutStr = parseConfigValue(config, "PKT_OUT")
            pktOutValue = pktOutStr?.toIntOrNull() ?: PKT_OUT_DEFAULT
            pktOutValue = pktOutValue.coerceIn(PKT_MIN, PKT_MAX)
            textPktOutValue.text = pktOutValue.toString()

            // Parse PKT_IN (if exists)
            val pktInStr = parseConfigValue(config, "PKT_IN")
            pktInValue = pktInStr?.toIntOrNull() ?: PKT_IN_DEFAULT
            pktInValue = pktInValue.coerceIn(PKT_MIN, PKT_MAX)
            textPktInValue.text = pktInValue.toString()
        }
    }

    private fun parseConfigValue(config: String, key: String): String? {
        val regex = Regex("""$key=["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }

    /**
     * Data class to hold process statistics
     */
    private data class ProcessStats(
        val running: Boolean,
        val pid: String?,
        val startTime: Long,
        val memoryRssKb: Long,
        val cpuPercent: Double,
        val threads: Int,
        val uptimeFormatted: String
    )

    private fun checkStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Re-check root status dynamically
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    Shell.getShell().isRoot
                } catch (e: Exception) {
                    false
                }
            }

            // Update root status UI dynamically
            if (!hasRoot) {
                textRootStatus.text = "Not granted"
                textRootStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                setStatus(Status.ERROR)
                textStatusValue.text = "Root lost!"
                textStatusValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
                textUptime.visibility = View.GONE
                hideProcessStats()
                disableControls()
                return@launch
            } else {
                textRootStatus.text = "Granted"
                textRootStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
                enableControls()
            }

            // Re-check NFQUEUE status dynamically
            val nfqueueSupported = withContext(Dispatchers.IO) {
                checkNfqueueSupport()
            }
            updateNfqueueStatusUI(nfqueueSupported)

            val stats = withContext(Dispatchers.IO) {
                // Check if nfqws2 is running
                val pgrepResult = Shell.cmd("pgrep -f nfqws2").exec()
                val running = pgrepResult.isSuccess && pgrepResult.out.isNotEmpty()

                if (!running) {
                    return@withContext ProcessStats(
                        running = false,
                        pid = null,
                        startTime = 0L,
                        memoryRssKb = 0L,
                        cpuPercent = 0.0,
                        threads = 0,
                        uptimeFormatted = ""
                    )
                }

                val pid = pgrepResult.out.firstOrNull()?.trim() ?: return@withContext ProcessStats(
                    running = false, pid = null, startTime = 0L, memoryRssKb = 0L,
                    cpuPercent = 0.0, threads = 0, uptimeFormatted = ""
                )

                var startTime = 0L
                var memoryRssKb = 0L
                var threads = 0
                var cpuPercent = 0.0
                var processUptimeSeconds = 0.0

                // Read /proc/PID/status for memory and threads
                val statusResult = Shell.cmd("cat /proc/$pid/status 2>/dev/null").exec()
                if (statusResult.isSuccess) {
                    for (line in statusResult.out) {
                        when {
                            line.startsWith("VmRSS:") -> {
                                // Format: "VmRSS:    1234 kB"
                                memoryRssKb = line.split(Regex("\\s+"))
                                    .getOrNull(1)?.toLongOrNull() ?: 0L
                            }
                            line.startsWith("Threads:") -> {
                                threads = line.split(Regex("\\s+"))
                                    .getOrNull(1)?.toIntOrNull() ?: 0
                            }
                        }
                    }
                }

                // Read /proc/PID/stat for CPU time and start time
                val statResult = Shell.cmd("cat /proc/$pid/stat 2>/dev/null").exec()
                if (statResult.isSuccess && statResult.out.isNotEmpty()) {
                    try {
                        // Parse stat file - fields are space-separated
                        // But comm (field 2) can contain spaces and is in parentheses
                        val statLine = statResult.out.first()
                        val commEnd = statLine.lastIndexOf(')')
                        val fieldsAfterComm = statLine.substring(commEnd + 2).split(" ")

                        // Field indices after comm (0-based from fieldsAfterComm):
                        // 0=state, 11=utime, 12=stime, 19=starttime (relative to original stat)
                        // In original /proc/PID/stat: field 14=utime, 15=stime, 22=starttime (1-based)

                        val utime = fieldsAfterComm.getOrNull(11)?.toLongOrNull() ?: 0L
                        val stime = fieldsAfterComm.getOrNull(12)?.toLongOrNull() ?: 0L
                        val startTicks = fieldsAfterComm.getOrNull(19)?.toLongOrNull() ?: 0L

                        val totalCpuTime = utime + stime

                        // Get system uptime
                        val uptimeResult = Shell.cmd("cat /proc/uptime").exec()
                        if (uptimeResult.isSuccess && uptimeResult.out.isNotEmpty()) {
                            val systemUptimeSeconds = uptimeResult.out.first()
                                .split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0

                            // Clock ticks per second (usually 100 on Android)
                            val ticksPerSec = 100L
                            processUptimeSeconds = systemUptimeSeconds - (startTicks.toDouble() / ticksPerSec)

                            // Calculate absolute start time
                            startTime = System.currentTimeMillis() - (processUptimeSeconds * 1000).toLong()

                            // Calculate CPU percentage
                            val currentTime = System.currentTimeMillis()
                            if (lastCpuTime > 0 && lastCpuCheckTime > 0 && currentPid == pid) {
                                val cpuTimeDelta = totalCpuTime - lastCpuTime
                                val realTimeDelta = (currentTime - lastCpuCheckTime) / 1000.0
                                if (realTimeDelta > 0) {
                                    // CPU time is in ticks, convert to seconds
                                    val cpuSeconds = cpuTimeDelta.toDouble() / ticksPerSec
                                    cpuPercent = (cpuSeconds / realTimeDelta) * 100.0
                                    // Clamp to 0-100%
                                    cpuPercent = cpuPercent.coerceIn(0.0, 100.0)
                                }
                            }

                            // Store for next calculation
                            lastCpuTime = totalCpuTime
                            lastCpuCheckTime = currentTime
                        }
                    } catch (e: Exception) {
                        // Parsing failed, use defaults
                    }
                }

                ProcessStats(
                    running = true,
                    pid = pid,
                    startTime = startTime,
                    memoryRssKb = memoryRssKb,
                    cpuPercent = cpuPercent,
                    threads = threads,
                    uptimeFormatted = formatUptimeHMS((processUptimeSeconds * 1000).toLong())
                )
            }

            val wasRunning = isRunning
            isRunning = stats.running
            currentPid = stats.pid

            // Update start time only when service transitions to running
            // or when we get a valid start time and don't have one yet
            if (isRunning && stats.startTime > 0L) {
                if (serviceStartTime == 0L || !wasRunning) {
                    serviceStartTime = stats.startTime
                }
            } else if (!isRunning) {
                serviceStartTime = 0L
                lastCpuTime = 0L
                lastCpuCheckTime = 0L
            }

            updateUI()
            updateProcessStats(stats)
        }
    }

    private fun updateUI() {
        if (isRunning) {
            setStatus(Status.RUNNING)
            textStatusValue.text = "Running"
            textStatusValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
            buttonToggle.text = "STOP"
            buttonToggle.setIconResource(R.drawable.ic_stop)
            buttonToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            textToggleHint.text = "Tap to stop DPI bypass"

            // Update uptime display
            if (serviceStartTime > 0L) {
                val uptimeMillis = System.currentTimeMillis() - serviceStartTime
                textUptime.text = formatUptime(uptimeMillis)
                textUptime.visibility = View.VISIBLE
            } else {
                textUptime.text = ""
                textUptime.visibility = View.GONE
            }
        } else {
            setStatus(Status.STOPPED)
            textStatusValue.text = "Stopped"
            textStatusValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            buttonToggle.text = "START"
            buttonToggle.setIconResource(R.drawable.ic_play)
            buttonToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
            textToggleHint.text = "Tap to start DPI bypass"

            // Hide uptime when stopped
            textUptime.text = ""
            textUptime.visibility = View.GONE
        }
    }

    /**
     * Formats uptime in milliseconds to human-readable string.
     * Examples: "5s", "2m 30s", "1h 15m", "2d 5h"
     */
    private fun formatUptime(millis: Long): String {
        if (millis < 0) return ""

        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Formats uptime in HH:MM:SS format for process statistics.
     */
    private fun formatUptimeHMS(millis: Long): String {
        if (millis < 0) return "--:--:--"

        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 99) {
            // Show days if more than 99 hours
            val days = hours / 24
            val remainingHours = hours % 24
            "${days}d ${remainingHours}h ${minutes}m"
        } else {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }
    }

    /**
     * Updates the process statistics UI section.
     */
    private fun updateProcessStats(stats: ProcessStats) {
        if (stats.running) {
            // Show statistics section
            textProcessStatsHeader.visibility = View.VISIBLE
            layoutProcessStats.visibility = View.VISIBLE

            // Memory (convert KB to MB with 1 decimal)
            val memoryMb = stats.memoryRssKb / 1024.0
            textMemoryUsage.text = String.format("%.1f MB", memoryMb)

            // CPU percentage
            textCpuUsage.text = String.format("%.1f%%", stats.cpuPercent)

            // Uptime in HH:MM:SS format
            textProcessUptime.text = stats.uptimeFormatted.ifEmpty { "--:--:--" }

            // Thread count
            textThreadCount.text = if (stats.threads > 0) stats.threads.toString() else "--"

            // Process ID
            textProcessPid.text = stats.pid ?: "--"
        } else {
            // Hide statistics section when not running
            textProcessStatsHeader.visibility = View.GONE
            layoutProcessStats.visibility = View.GONE

            // Reset values
            textMemoryUsage.text = "-- MB"
            textCpuUsage.text = "--%"
            textProcessUptime.text = "--:--:--"
            textThreadCount.text = "--"
            textProcessPid.text = "--"
        }
    }

    private fun startService() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonToggle.isEnabled = false
            textStatusValue.text = "Starting..."
            textStatusValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            textUptime.visibility = View.GONE

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-start.sh 2>&1").exec()
            }

            delay(1500)

            // Record approximate start time (will be refined by checkStatus)
            if (result.isSuccess) {
                serviceStartTime = System.currentTimeMillis()
            }

            checkStatus()
            buttonToggle.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Service started", Toast.LENGTH_SHORT).show()
            } else {
                serviceStartTime = 0L
                Toast.makeText(requireContext(), "Start failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopService() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonToggle.isEnabled = false
            textStatusValue.text = "Stopping..."
            textStatusValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-stop.sh 2>&1").exec()
            }

            // Reset start time immediately
            serviceStartTime = 0L

            delay(500)
            checkStatus()
            buttonToggle.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Service stopped", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Stop failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveAutostart(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val value = if (enabled) "1" else "0"
            withContext(Dispatchers.IO) {
                Shell.cmd("sed -i 's/^AUTOSTART=.*/AUTOSTART=$value/' $CONFIG").exec()
            }
            Toast.makeText(
                requireContext(),
                if (enabled) "Autostart enabled" else "Autostart disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveWifiOnly(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val value = if (enabled) "1" else "0"
            withContext(Dispatchers.IO) {
                // Check if WIFI_ONLY exists in config, if not add it
                val hasWifiOnly = Shell.cmd("grep -q 'WIFI_ONLY=' $CONFIG && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                if (hasWifiOnly) {
                    Shell.cmd("sed -i 's/^WIFI_ONLY=.*/WIFI_ONLY=$value/' $CONFIG").exec()
                } else {
                    Shell.cmd("echo 'WIFI_ONLY=$value' >> $CONFIG").exec()
                }
            }
            Toast.makeText(
                requireContext(),
                if (enabled) "WiFi-only mode enabled" else "WiFi-only mode disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun savePktOut(value: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Check if PKT_OUT exists in config, if not add it
                val hasPktOut = Shell.cmd("grep -q 'PKT_OUT=' $CONFIG && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                if (hasPktOut) {
                    Shell.cmd("sed -i 's/^PKT_OUT=.*/PKT_OUT=$value/' $CONFIG").exec()
                } else {
                    Shell.cmd("echo 'PKT_OUT=$value' >> $CONFIG").exec()
                }
            }
        }
    }

    private fun savePktIn(value: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Check if PKT_IN exists in config, if not add it
                val hasPktIn = Shell.cmd("grep -q 'PKT_IN=' $CONFIG && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"

                if (hasPktIn) {
                    Shell.cmd("sed -i 's/^PKT_IN=.*/PKT_IN=$value/' $CONFIG").exec()
                } else {
                    Shell.cmd("echo 'PKT_IN=$value' >> $CONFIG").exec()
                }
            }
        }
    }

    private enum class Status { RUNNING, STOPPED, ERROR }

    private fun setStatus(status: Status) {
        val colorRes = when (status) {
            Status.RUNNING -> R.color.status_running
            Status.STOPPED -> R.color.status_stopped
            Status.ERROR -> R.color.status_error
        }
        viewStatusIndicator.background?.setTint(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun disableControls() {
        buttonToggle.isEnabled = false
        switchAutostart.isEnabled = false
        switchWifiOnly.isEnabled = false
        rowAutostart.isClickable = false
        rowWifiOnly.isClickable = false
    }

    /**
     * Enable controls after root is confirmed.
     */
    private fun enableControls() {
        buttonToggle.isEnabled = true
        switchAutostart.isEnabled = true
        switchWifiOnly.isEnabled = true
        rowAutostart.isClickable = true
        rowWifiOnly.isClickable = true
    }

    /**
     * Check NFQUEUE support using 4 different methods.
     * Returns true if any method indicates support.
     * Must be called from IO dispatcher.
     */
    private fun checkNfqueueSupport(): Boolean {
        // Method 1: Check /proc/net/netfilter/nf_queue (traditional)
        val method1 = Shell.cmd("[ -f /proc/net/netfilter/nf_queue ] && echo 1 || echo 0").exec()
            .out.firstOrNull() == "1"

        // Method 2: Check /proc/net/netfilter/nfnetlink_queue
        val method2 = Shell.cmd("[ -f /proc/net/netfilter/nfnetlink_queue ] && echo 1 || echo 0").exec()
            .out.firstOrNull() == "1"

        // Method 3: Check if xt_NFQUEUE module exists or is built-in
        val method3 = Shell.cmd("grep -q NFQUEUE /proc/net/ip_tables_targets 2>/dev/null && echo 1 || echo 0").exec()
            .out.firstOrNull() == "1"

        // Method 4: Check kernel config (if available)
        val method4 = Shell.cmd("zcat /proc/config.gz 2>/dev/null | grep -q CONFIG_NETFILTER_NETLINK_QUEUE=y && echo 1 || echo 0").exec()
            .out.firstOrNull() == "1"

        return method1 || method2 || method3 || method4
    }

    /**
     * Update NFQUEUE status in UI.
     * Must be called from Main thread.
     */
    private fun updateNfqueueStatusUI(supported: Boolean) {
        if (supported) {
            textNfqueueStatus.text = "Supported"
            textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
        } else {
            // Even if detection fails, NFQUEUE might still work - show warning instead of error
            textNfqueueStatus.text = "Unknown (may work)"
            textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
    }

    /**
     * Hide process statistics section.
     */
    private fun hideProcessStats() {
        textProcessStatsHeader.visibility = View.GONE
        layoutProcessStats.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Start status polling every 3 seconds
        startStatusPolling()
        // Register network change listener
        registerNetworkListener()
        // Initial network stats update
        updateNetworkStats()
    }

    override fun onPause() {
        super.onPause()
        // Stop status polling when fragment is not visible
        stopStatusPolling()
        // Unregister network change listener
        unregisterNetworkListener()
    }

    private fun startStatusPolling() {
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                checkStatus()
                delay(3000)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private fun checkForUpdates() {
        val ctx = context ?: return
        buttonUpdateModule.isEnabled = false
        buttonUpdateModule.text = "Checking..."

        viewLifecycleOwner.lifecycleScope.launch {
            val updateManager = UpdateManager(ctx)
            val result = updateManager.checkForUpdates()

            if (!isAdded) return@launch

            buttonUpdateModule.isEnabled = true
            buttonUpdateModule.text = "Check for Updates"

            when (result) {
                is UpdateManager.UpdateResult.Available -> {
                    val dialog = UpdateDialogFragment.newInstance(
                        release = result.release,
                        currentVersion = updateManager.getCurrentVersion()
                    )
                    dialog.show(parentFragmentManager, "update_dialog")
                }
                is UpdateManager.UpdateResult.UpToDate -> {
                    Toast.makeText(
                        ctx,
                        "Latest version installed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is UpdateManager.UpdateResult.Error -> {
                    Toast.makeText(
                        ctx,
                        "Check failed: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ==================== Network Stats Functions ====================

    /**
     * Registers a listener for network connectivity changes
     */
    private fun registerNetworkListener() {
        networkStatsManager.registerNetworkChangeListener(object : NetworkStatsManager.NetworkChangeListener {
            override fun onNetworkChanged(stats: NetworkStatsManager.NetworkStats) {
                // Update network stats on network change
                viewLifecycleOwner.lifecycleScope.launch {
                    updateNetworkStats()
                }
            }
        })
    }

    /**
     * Unregisters the network change listener
     */
    private fun unregisterNetworkListener() {
        networkStatsManager.unregisterNetworkChangeListener()
    }

    /**
     * Updates all network statistics in the UI
     */
    private fun updateNetworkStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                networkStatsManager.getNetworkStats()
            }

            if (!isAdded) return@launch

            // Update network type
            val networkTypeString = networkStatsManager.getNetworkTypeString(stats.networkType)
            textNetworkType.text = networkTypeString

            // Update icon based on network type
            val iconRes = networkStatsManager.getNetworkTypeIcon(stats.networkType)
            iconNetworkType.setImageResource(iconRes)

            // Set color based on connection status
            val colorRes = when (stats.networkType) {
                NetworkStatsManager.NetworkType.NONE -> R.color.status_error
                NetworkStatsManager.NetworkType.VPN -> R.color.accent_light_blue
                else -> R.color.status_running
            }
            textNetworkType.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

            // Update WiFi name (show/hide row based on connection type)
            if (stats.networkType == NetworkStatsManager.NetworkType.WIFI && stats.wifiSsid != null) {
                rowWifiName.visibility = View.VISIBLE
                textWifiName.text = stats.wifiSsid
            } else {
                rowWifiName.visibility = View.GONE
            }

            // Update iptables status
            if (stats.iptablesActive) {
                textIptablesStatus.text = "Active"
                textIptablesStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
            } else {
                textIptablesStatus.text = "Inactive"
                textIptablesStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }

            // Update NFQUEUE rules count
            textNfqueueRulesCount.text = stats.nfqueueRulesCount.toString()
            val rulesColorRes = if (stats.nfqueueRulesCount > 0) R.color.status_running else R.color.text_secondary
            textNfqueueRulesCount.setTextColor(ContextCompat.getColor(requireContext(), rulesColorRes))
        }
    }

}
