package com.zapret2.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*

class ControlFragment : Fragment() {

    companion object {
        private const val PREFS_NAME = "zapret2_prefs"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_QUIC_BANNER_DISMISSED = "quic_banner_dismissed"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        // PKT limits for packet interception settings
        private const val PKT_MIN = 1
        private const val PKT_MAX = 100
        private const val PKT_OUT_DEFAULT = 20
        private const val PKT_IN_DEFAULT = 10

        // NFQUEUE support cache keys
        private const val KEY_NFQUEUE_SUPPORT = "nfqueue_support"
        private const val KEY_NFQUEUE_CHECKED = "nfqueue_checked"
    }

    // UI Elements (nullable to avoid crashes after view destruction)
    private var viewStatusIndicator: View? = null
    private var textStatusTitle: TextView? = null
    private var textStatusValue: TextView? = null
    private var textUptime: TextView? = null
    private var buttonToggle: MaterialButton? = null
    private var textToggleHint: TextView? = null
    private var rowAutostart: LinearLayout? = null
    private var rowWifiOnly: LinearLayout? = null
    private var switchAutostart: MaterialSwitch? = null
    private var switchWifiOnly: MaterialSwitch? = null
    private var textAutostartValue: TextView? = null
    private var textWifiOnlyValue: TextView? = null
    private var textAppVersion: TextView? = null
    private var textModuleVersion: TextView? = null
    private var textRootStatus: TextView? = null
    private var textNfqueueStatus: TextView? = null
    private var buttonUpdateModule: MaterialButton? = null

    // Process Statistics UI Elements
    private var textProcessStatsHeader: TextView? = null
    private var layoutProcessStats: View? = null
    private var textMemoryUsage: TextView? = null
    private var textCpuUsage: TextView? = null
    private var textProcessUptime: TextView? = null
    private var textThreadCount: TextView? = null
    private var textProcessPid: TextView? = null

    // Network Stats UI Elements
    private var iconNetworkType: ImageView? = null
    private var textNetworkType: TextView? = null
    private var rowWifiName: LinearLayout? = null
    private var textWifiName: TextView? = null
    private var textIptablesStatus: TextView? = null
    private var textNfqueueRulesCount: TextView? = null

    // PKT Settings UI Elements
    private var textPktOutValue: TextView? = null
    private var textPktInValue: TextView? = null
    private var buttonPktOutMinus: MaterialButton? = null
    private var buttonPktOutPlus: MaterialButton? = null
    private var buttonPktInMinus: MaterialButton? = null
    private var buttonPktInPlus: MaterialButton? = null

    // PKT current values
    private var pktOutValue: Int = PKT_OUT_DEFAULT
    private var pktInValue: Int = PKT_IN_DEFAULT
    private var isLoadingCoreConfig = false

    // QUIC Warning Banner
    private var bannerQuicWarning: LinearLayout? = null
    private var buttonDismissQuicBanner: MaterialButton? = null

    // Network stats manager
    private var networkStatsManager: NetworkStatsManager? = null

    // Pulse animation for status indicator
    private var pulseAnimator: ObjectAnimator? = null

    // Status tracking
    private var isRunning = false
    private var statusPollingJob: Job? = null
    private var serviceStartTime: Long = 0L  // Unix timestamp when service started
    private var currentPid: String? = null   // Current nfqws2 process ID
    private var lastCpuTime: Long = 0L       // For CPU usage calculation
    private var lastCpuCheckTime: Long = 0L  // Timestamp of last CPU check

    // Smart polling variables for battery optimization
    private var lastServiceStatus: ServiceStatus? = null
    private var stableCount = 0
    private var currentPollInterval = 3000L

    // Service status enum for smart polling comparison
    private enum class ServiceStatus { RUNNING, STOPPED, ERROR }

    // Paths
    private val MODDIR = "/data/adb/modules/zapret2"
    private val SCRIPTS = "$MODDIR/zapret2/scripts"

    private data class CoreSettings(
        val autostart: Boolean = true,
        val wifiOnly: Boolean = false,
        val pktOut: Int = 20,
        val pktIn: Int = 10
    )

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

    override fun onDestroyView() {
        stopPulseAnimation()
        statusPollingJob?.cancel()
        statusPollingJob = null
        // Null out ALL view references to prevent leaks and crashes
        viewStatusIndicator = null
        textStatusTitle = null
        textStatusValue = null
        textUptime = null
        buttonToggle = null
        textToggleHint = null
        rowAutostart = null
        rowWifiOnly = null
        switchAutostart = null
        switchWifiOnly = null
        textAutostartValue = null
        textWifiOnlyValue = null
        textAppVersion = null
        textModuleVersion = null
        textRootStatus = null
        textNfqueueStatus = null
        buttonUpdateModule = null
        textProcessStatsHeader = null
        layoutProcessStats = null
        textMemoryUsage = null
        textCpuUsage = null
        textProcessUptime = null
        textThreadCount = null
        textProcessPid = null
        iconNetworkType = null
        textNetworkType = null
        rowWifiName = null
        textWifiName = null
        textIptablesStatus = null
        textNfqueueRulesCount = null
        textPktOutValue = null
        textPktInValue = null
        buttonPktOutMinus = null
        buttonPktOutPlus = null
        buttonPktInMinus = null
        buttonPktInPlus = null
        bannerQuicWarning = null
        buttonDismissQuicBanner = null
        networkStatsManager?.unregisterNetworkChangeListener()
        networkStatsManager = null
        super.onDestroyView()
    }

    /**
     * Schedules automatic update check if auto-check is enabled
     * and enough time has passed since the last check.
     */
    private fun scheduleAutoUpdateCheck() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        textAppVersion?.text = "v${BuildConfig.VERSION_NAME}"

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
        networkStatsManager = NetworkStatsManager(view.context)

        // QUIC Warning Banner
        bannerQuicWarning = view.findViewById(R.id.bannerQuicWarning)
        buttonDismissQuicBanner = view.findViewById(R.id.buttonDismissQuicBanner)

        // Show QUIC banner if not dismissed
        setupQuicWarningBanner()
    }

    private fun setupListeners() {
        buttonToggle?.setOnClickListener {
            if (isRunning) {
                stopService()
            } else {
                startService()
            }
        }

        // Row click toggles the switch
        rowAutostart?.setOnClickListener {
            switchAutostart?.toggle()
        }

        rowWifiOnly?.setOnClickListener {
            switchWifiOnly?.toggle()
        }

        switchAutostart?.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingCoreConfig) return@setOnCheckedChangeListener
            textAutostartValue?.text = if (isChecked) "On" else "Off"
            saveAutostart(isChecked)
        }

        switchWifiOnly?.setOnCheckedChangeListener { _, isChecked ->
            if (isLoadingCoreConfig) return@setOnCheckedChangeListener
            textWifiOnlyValue?.text = if (isChecked) "On" else "Off"
            saveWifiOnly(isChecked)
        }

        buttonUpdateModule?.setOnClickListener {
            checkForUpdates()
        }

        // PKT_OUT controls
        buttonPktOutMinus?.setOnClickListener {
            if (pktOutValue > PKT_MIN) {
                pktOutValue--
                textPktOutValue?.text = pktOutValue.toString()
                savePktOut(pktOutValue)
            }
        }

        buttonPktOutPlus?.setOnClickListener {
            if (pktOutValue < PKT_MAX) {
                pktOutValue++
                textPktOutValue?.text = pktOutValue.toString()
                savePktOut(pktOutValue)
            }
        }

        // PKT_IN controls
        buttonPktInMinus?.setOnClickListener {
            if (pktInValue > PKT_MIN) {
                pktInValue--
                textPktInValue?.text = pktInValue.toString()
                savePktIn(pktInValue)
            }
        }

        buttonPktInPlus?.setOnClickListener {
            if (pktInValue < PKT_MAX) {
                pktInValue++
                textPktInValue?.text = pktInValue.toString()
                savePktIn(pktInValue)
            }
        }
    }

    private fun checkRootAndModule() {
        viewLifecycleOwner.lifecycleScope.launch {
            textStatusValue?.text = "Checking..."
            textRootStatus?.text = "Checking..."
            textNfqueueStatus?.text = "Checking..."

            // Check root access
            val hasRoot = withContext(Dispatchers.IO) {
                try {
                    Shell.getShell().isRoot
                } catch (e: Exception) {
                    false
                }
            }

            if (!isAdded || view == null) return@launch
            val ctx = context ?: return@launch

            if (!hasRoot) {
                textStatusValue?.text = "Root not available!"
                textRootStatus?.text = "Not granted"
                textRootStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                setStatus(Status.ERROR)
                disableControls()
                return@launch
            }

            textRootStatus?.text = "Granted"
            textRootStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))

            // Check module exists
            val moduleExists = withContext(Dispatchers.IO) {
                Shell.cmd("[ -d $MODDIR ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"
            }

            if (!isAdded || view == null) return@launch

            if (!moduleExists) {
                textStatusValue?.text = "Module not installed!"
                setStatus(Status.ERROR)
                disableControls()
                return@launch
            }

            // Check for nfqws2 binary
            val binaryExists = withContext(Dispatchers.IO) {
                Shell.cmd("[ -f $MODDIR/zapret2/nfqws2 ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"
            }

            if (!isAdded || view == null) return@launch

            if (!binaryExists) {
                textStatusValue?.text = "nfqws2 not found!"
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

            if (!isAdded || view == null) return@launch

            if (nfqueueSupported) {
                textNfqueueStatus?.text = "Supported"
                textNfqueueStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))
            } else {
                // Even if detection fails, NFQUEUE might still work - show warning instead of error
                textNfqueueStatus?.text = "Unknown (may work)"
                textNfqueueStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
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
            if (!isAdded || view == null) return@launch
            // version already contains "v" prefix from module.prop
            textModuleVersion?.text = version
        }
    }

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val coreSettings = readCoreSettings()

            if (!isAdded || view == null) return@launch

            isLoadingCoreConfig = true
            switchAutostart?.isChecked = coreSettings.autostart
            switchWifiOnly?.isChecked = coreSettings.wifiOnly
            isLoadingCoreConfig = false

            textAutostartValue?.text = if (coreSettings.autostart) "On" else "Off"
            textWifiOnlyValue?.text = if (coreSettings.wifiOnly) "On" else "Off"

            pktOutValue = coreSettings.pktOut.coerceIn(PKT_MIN, PKT_MAX)
            pktInValue = coreSettings.pktIn.coerceIn(PKT_MIN, PKT_MAX)
            textPktOutValue?.text = pktOutValue.toString()
            textPktInValue?.text = pktInValue.toString()
        }
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

    /**
     * Simple wrapper that calls checkStatusAndGetState().
     * Used by startService() and stopService() for immediate status refresh.
     */
    private fun checkStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            checkStatusAndGetState()
        }
    }

    private fun updateUI() {
        val ctx = context ?: return

        if (isRunning) {
            setStatus(Status.RUNNING)
            textStatusValue?.text = "Running"
            textStatusValue?.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))
            buttonToggle?.text = "STOP"
            buttonToggle?.setIconResource(R.drawable.ic_stop)
            buttonToggle?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.status_error))
            textToggleHint?.text = "Tap to stop DPI bypass"
            startPulseAnimation(viewStatusIndicator)

            // Update uptime display
            if (serviceStartTime > 0L) {
                val uptimeMillis = System.currentTimeMillis() - serviceStartTime
                textUptime?.text = formatUptime(uptimeMillis)
                textUptime?.visibility = View.VISIBLE
            } else {
                textUptime?.text = ""
                textUptime?.visibility = View.GONE
            }
        } else {
            setStatus(Status.STOPPED)
            textStatusValue?.text = "Stopped"
            textStatusValue?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            buttonToggle?.text = "START"
            buttonToggle?.setIconResource(R.drawable.ic_play)
            buttonToggle?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_blue))
            textToggleHint?.text = "Tap to start DPI bypass"
            stopPulseAnimation()

            // Hide uptime when stopped
            textUptime?.text = ""
            textUptime?.visibility = View.GONE
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
            textProcessStatsHeader?.visibility = View.VISIBLE
            layoutProcessStats?.visibility = View.VISIBLE

            // Memory (convert KB to MB with 1 decimal)
            val memoryMb = stats.memoryRssKb / 1024.0
            textMemoryUsage?.text = String.format("%.1f MB", memoryMb)

            // CPU percentage
            textCpuUsage?.text = String.format("%.1f%%", stats.cpuPercent)

            // Uptime in HH:MM:SS format
            textProcessUptime?.text = stats.uptimeFormatted.ifEmpty { "--:--:--" }

            // Thread count
            textThreadCount?.text = if (stats.threads > 0) stats.threads.toString() else "--"

            // Process ID
            textProcessPid?.text = stats.pid ?: "--"
        } else {
            // Hide statistics section when not running
            textProcessStatsHeader?.visibility = View.GONE
            layoutProcessStats?.visibility = View.GONE

            // Reset values
            textMemoryUsage?.text = "-- MB"
            textCpuUsage?.text = "--%"
            textProcessUptime?.text = "--:--:--"
            textThreadCount?.text = "--"
            textProcessPid?.text = "--"
        }
    }

    private fun startService() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch

            buttonToggle?.isEnabled = false
            textStatusValue?.text = "Starting..."
            textStatusValue?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textUptime?.visibility = View.GONE

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-start.sh 2>&1").exec()
            }

            if (!isAdded || view == null) return@launch

            // Record approximate start time (will be refined by checkStatus)
            if (result.isSuccess) {
                serviceStartTime = System.currentTimeMillis()
            }

            checkStatus()
            buttonToggle?.isEnabled = true

            if (!isAdded) return@launch

            if (result.isSuccess) {
                view?.let { Snackbar.make(it, "Service started", Snackbar.LENGTH_SHORT).show() }
                // Notify LogsFragment to refresh
                setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
            } else {
                serviceStartTime = 0L
                view?.let { Snackbar.make(it, "Start failed", Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    private fun stopService() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch

            buttonToggle?.isEnabled = false
            textStatusValue?.text = "Stopping..."
            textStatusValue?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-stop.sh 2>&1").exec()
            }

            if (!isAdded || view == null) return@launch

            // Reset start time immediately
            serviceStartTime = 0L

            checkStatus()
            buttonToggle?.isEnabled = true

            if (!isAdded) return@launch

            if (result.isSuccess) {
                view?.let { Snackbar.make(it, "Service stopped", Snackbar.LENGTH_SHORT).show() }
                // Notify LogsFragment to refresh
                setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
            } else {
                view?.let { Snackbar.make(it, "Stop failed", Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    private fun saveAutostart(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val value = if (enabled) "1" else "0"
            persistCoreValue("autostart", value)
            if (!isAdded) return@launch
            view?.let {
                Snackbar.make(
                    it,
                    if (enabled) "Autostart enabled" else "Autostart disabled",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun saveWifiOnly(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val value = if (enabled) "1" else "0"
            persistCoreValue("wifi_only", value)
            if (!isAdded) return@launch
            view?.let {
                Snackbar.make(
                    it,
                    if (enabled) "WiFi-only mode enabled" else "WiFi-only mode disabled",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun savePktOut(value: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            persistCoreValue("pkt_out", value.toString())
        }
    }

    private fun savePktIn(value: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            persistCoreValue("pkt_in", value.toString())
        }
    }

    private suspend fun readCoreSettings(): CoreSettings = withContext(Dispatchers.IO) {
        val runtimeCoreValues = RuntimeConfigStore.readCore()

        fun runtimeValue(key: String): String? = runtimeCoreValues[key]?.takeIf { it.isNotEmpty() }

        CoreSettings(
            autostart = (runtimeValue("autostart") ?: "1") == "1",
            wifiOnly = (runtimeValue("wifi_only") ?: "0") == "1",
            pktOut = (runtimeValue("pkt_out") ?: PKT_OUT_DEFAULT.toString()).toIntOrNull()
                ?: PKT_OUT_DEFAULT,
            pktIn = (runtimeValue("pkt_in") ?: PKT_IN_DEFAULT.toString()).toIntOrNull()
                ?: PKT_IN_DEFAULT
        )
    }

    private suspend fun persistCoreValue(runtimeKey: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            RuntimeConfigStore.upsertCoreValue(runtimeKey, value)
        }

    private fun startPulseAnimation(view: View?) {
        stopPulseAnimation()
        view ?: return
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f, 1.0f)
        ).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private enum class Status { RUNNING, STOPPED, ERROR }

    private fun setStatus(status: Status) {
        val ctx = context ?: return
        val colorRes = when (status) {
            Status.RUNNING -> R.color.status_running
            Status.STOPPED -> R.color.status_stopped
            Status.ERROR -> R.color.status_error
        }
        viewStatusIndicator?.background?.setTint(ContextCompat.getColor(ctx, colorRes))
    }

    private fun disableControls() {
        buttonToggle?.isEnabled = false
        switchAutostart?.isEnabled = false
        switchWifiOnly?.isEnabled = false
        rowAutostart?.isClickable = false
        rowWifiOnly?.isClickable = false
    }

    /**
     * Enable controls after root is confirmed.
     */
    private fun enableControls() {
        buttonToggle?.isEnabled = true
        switchAutostart?.isEnabled = true
        switchWifiOnly?.isEnabled = true
        rowAutostart?.isClickable = true
        rowWifiOnly?.isClickable = true
    }

    /**
     * Check NFQUEUE support using 4 different methods with SharedPreferences caching.
     * The result is cached because NFQUEUE support never changes without a device reboot.
     * Returns true if any method indicates support.
     * Must be called from IO dispatcher.
     */
    private fun checkNfqueueSupport(): Boolean {
        val ctx = context ?: return true  // Default to true if no context (don't block user)
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if we already have a cached result
        if (prefs.getBoolean(KEY_NFQUEUE_CHECKED, false)) {
            return prefs.getBoolean(KEY_NFQUEUE_SUPPORT, true)  // Default true if not found
        }

        // First check - run all 4 shell commands
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

        val supported = method1 || method2 || method3 || method4

        // Cache the result - this won't change until device reboot
        prefs.edit()
            .putBoolean(KEY_NFQUEUE_SUPPORT, supported)
            .putBoolean(KEY_NFQUEUE_CHECKED, true)
            .apply()

        return supported
    }

    /**
     * Update NFQUEUE status in UI.
     * Must be called from Main thread.
     */
    private fun updateNfqueueStatusUI(supported: Boolean) {
        val ctx = context ?: return
        if (supported) {
            textNfqueueStatus?.text = "Supported"
            textNfqueueStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))
        } else {
            // Even if detection fails, NFQUEUE might still work - show warning instead of error
            textNfqueueStatus?.text = "Unknown (may work)"
            textNfqueueStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
    }

    /**
     * Hide process statistics section.
     */
    private fun hideProcessStats() {
        textProcessStatsHeader?.visibility = View.GONE
        layoutProcessStats?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (view == null) return
        // Start status polling every 3 seconds
        startStatusPolling()
        // Register network change listener (only if manager is initialized)
        networkStatsManager?.let { registerNetworkListener() }
        // Initial network stats update
        networkStatsManager?.let { updateNetworkStats() }
    }

    override fun onPause() {
        super.onPause()
        // Stop status polling when fragment is not visible
        stopStatusPolling()
        // Unregister network change listener
        networkStatsManager?.let { unregisterNetworkListener() }
    }

    private fun startStatusPolling() {
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val newStatus = checkStatusAndGetState()

                // Adaptive polling interval based on status stability
                if (newStatus == lastServiceStatus) {
                    stableCount++
                    currentPollInterval = when {
                        stableCount > 20 -> 30000L  // 30 sec after 20 identical polls
                        stableCount > 10 -> 15000L  // 15 sec after 10 identical polls
                        stableCount > 5 -> 10000L   // 10 sec after 5 identical polls
                        else -> 3000L               // 3 sec default
                    }
                } else {
                    stableCount = 0
                    currentPollInterval = 3000L
                }
                lastServiceStatus = newStatus

                delay(currentPollInterval)
            }
        }
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
        // Reset smart polling state when stopping
        stableCount = 0
        currentPollInterval = 3000L
    }

    /**
     * Checks status and returns the ServiceStatus for smart polling comparison.
     * This wraps checkStatus() and determines the current state.
     */
    private suspend fun checkStatusAndGetState(): ServiceStatus {
        // Re-check root status dynamically
        val hasRoot = withContext(Dispatchers.IO) {
            try {
                Shell.getShell().isRoot
            } catch (e: Exception) {
                false
            }
        }

        if (!isAdded || view == null) return ServiceStatus.ERROR
        val ctx = context ?: return ServiceStatus.ERROR

        // Update root status UI dynamically
        if (!hasRoot) {
            textRootStatus?.text = "Not granted"
            textRootStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
            setStatus(Status.ERROR)
            textStatusValue?.text = "Root lost!"
            textStatusValue?.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
            textUptime?.visibility = View.GONE
            hideProcessStats()
            disableControls()
            return ServiceStatus.ERROR
        } else {
            textRootStatus?.text = "Granted"
            textRootStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))
            enableControls()
        }

        // Re-check NFQUEUE status dynamically (now cached)
        val nfqueueSupported = withContext(Dispatchers.IO) {
            checkNfqueueSupport()
        }
        if (!isAdded || view == null) return ServiceStatus.ERROR
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
                    val statLine = statResult.out.first()
                    val commEnd = statLine.lastIndexOf(')')
                    val fieldsAfterComm = statLine.substring(commEnd + 2).split(" ")

                    val utime = fieldsAfterComm.getOrNull(11)?.toLongOrNull() ?: 0L
                    val stime = fieldsAfterComm.getOrNull(12)?.toLongOrNull() ?: 0L
                    val startTicks = fieldsAfterComm.getOrNull(19)?.toLongOrNull() ?: 0L

                    val totalCpuTime = utime + stime

                    val uptimeResult = Shell.cmd("cat /proc/uptime").exec()
                    if (uptimeResult.isSuccess && uptimeResult.out.isNotEmpty()) {
                        val systemUptimeSeconds = uptimeResult.out.first()
                            .split(" ").firstOrNull()?.toDoubleOrNull() ?: 0.0

                        val ticksPerSec = 100L
                        processUptimeSeconds = systemUptimeSeconds - (startTicks.toDouble() / ticksPerSec)

                        startTime = System.currentTimeMillis() - (processUptimeSeconds * 1000).toLong()

                        val currentTime = System.currentTimeMillis()
                        if (lastCpuTime > 0 && lastCpuCheckTime > 0 && currentPid == pid) {
                            val cpuTimeDelta = totalCpuTime - lastCpuTime
                            val realTimeDelta = (currentTime - lastCpuCheckTime) / 1000.0
                            if (realTimeDelta > 0) {
                                val cpuSeconds = cpuTimeDelta.toDouble() / ticksPerSec
                                cpuPercent = (cpuSeconds / realTimeDelta) * 100.0
                                cpuPercent = cpuPercent.coerceIn(0.0, 100.0)
                            }
                        }

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

        if (!isAdded || view == null) return ServiceStatus.ERROR

        val wasRunning = isRunning
        isRunning = stats.running
        currentPid = stats.pid

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

        return if (isRunning) ServiceStatus.RUNNING else ServiceStatus.STOPPED
    }

    private fun checkForUpdates() {
        val ctx = context ?: return
        buttonUpdateModule?.isEnabled = false
        buttonUpdateModule?.text = "Checking..."

        viewLifecycleOwner.lifecycleScope.launch {
            val updateManager = UpdateManager(ctx)
            val result = updateManager.checkForUpdates()

            if (!isAdded || view == null) return@launch

            buttonUpdateModule?.isEnabled = true
            buttonUpdateModule?.text = "Check for Updates"

            when (result) {
                is UpdateManager.UpdateResult.Available -> {
                    val dialog = UpdateDialogFragment.newInstance(
                        release = result.release,
                        currentVersion = updateManager.getCurrentVersion()
                    )
                    dialog.show(parentFragmentManager, "update_dialog")
                }
                is UpdateManager.UpdateResult.UpToDate -> {
                    view?.let {
                        Snackbar.make(it, "Latest version installed", Snackbar.LENGTH_SHORT).show()
                    }
                }
                is UpdateManager.UpdateResult.Error -> {
                    view?.let {
                        Snackbar.make(it, "Check failed: ${result.message}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ==================== Network Stats Functions ====================

    /**
     * Registers a listener for network connectivity changes
     */
    private fun registerNetworkListener() {
        networkStatsManager?.registerNetworkChangeListener(object : NetworkStatsManager.NetworkChangeListener {
            override fun onNetworkChanged(stats: NetworkStatsManager.NetworkStats) {
                // Update network stats on network change
                try {
                    viewLifecycleOwner.lifecycleScope.launch {
                        updateNetworkStats()
                    }
                } catch (_: IllegalStateException) {
                    // Fragment view already destroyed
                }
            }
        })
    }

    /**
     * Unregisters the network change listener
     */
    private fun unregisterNetworkListener() {
        networkStatsManager?.unregisterNetworkChangeListener()
    }

    /**
     * Updates all network statistics in the UI
     */
    private fun updateNetworkStats() {
        val manager = networkStatsManager ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                manager.getNetworkStats()
            }

            val ctx = context ?: return@launch
            if (!isAdded || view == null) return@launch

            // Update network type
            val networkTypeString = manager.getNetworkTypeString(stats.networkType)
            textNetworkType?.text = networkTypeString

            // Update icon based on network type
            val iconRes = manager.getNetworkTypeIcon(stats.networkType)
            iconNetworkType?.setImageResource(iconRes)

            // Set color based on connection status
            val colorRes = when (stats.networkType) {
                NetworkStatsManager.NetworkType.NONE -> R.color.status_error
                NetworkStatsManager.NetworkType.VPN -> R.color.accent_light_blue
                else -> R.color.status_running
            }
            textNetworkType?.setTextColor(ContextCompat.getColor(ctx, colorRes))

            // Update WiFi name (show/hide row based on connection type)
            if (stats.networkType == NetworkStatsManager.NetworkType.WIFI && stats.wifiSsid != null) {
                rowWifiName?.visibility = View.VISIBLE
                textWifiName?.text = stats.wifiSsid
            } else {
                rowWifiName?.visibility = View.GONE
            }

            // Update iptables status with error codes
            val detail = stats.iptablesDetail
            val errorColor = ContextCompat.getColor(ctx, R.color.status_error)
            val warnColor = ContextCompat.getColor(ctx, R.color.status_warning)
            val okColor = ContextCompat.getColor(ctx, R.color.status_running)

            if (stats.iptablesActive) {
                if (detail.rulesFail > 0) {
                    // ERR_IPT_PARTIAL: some rules failed
                    textIptablesStatus?.text = "ERR_IPT_PARTIAL (${detail.rulesOk}/${detail.rulesTotal})"
                    textIptablesStatus?.setTextColor(warnColor)
                } else if (detail.rulesTotal > 0) {
                    textIptablesStatus?.text = "Active (${detail.rulesOk}/${detail.rulesTotal})"
                    textIptablesStatus?.setTextColor(okColor)
                } else {
                    textIptablesStatus?.text = "Active"
                    textIptablesStatus?.setTextColor(okColor)
                }
            } else {
                // iptables inactive = always an error when service should be running
                if (detail.rulesFail > 0 && detail.rulesOk == 0) {
                    // ERR_IPT_ALLFAIL: all rules failed to apply
                    textIptablesStatus?.text = "ERR_IPT_ALLFAIL (0/${detail.rulesTotal})"
                    textIptablesStatus?.setTextColor(errorColor)
                } else if (isRunning) {
                    // ERR_IPT_LOST: process running but rules gone (flushed by system/other module)
                    textIptablesStatus?.text = "ERR_IPT_LOST"
                    textIptablesStatus?.setTextColor(errorColor)
                } else if (detail.status == "unknown" && detail.rulesTotal == 0) {
                    // ERR_IPT_NOFILE: no status file = script never wrote iptables status
                    textIptablesStatus?.text = "Inactive"
                    textIptablesStatus?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                } else {
                    // ERR_IPT_FAIL: generic failure
                    textIptablesStatus?.text = "ERR_IPT_FAIL"
                    textIptablesStatus?.setTextColor(errorColor)
                }
            }

            // Make iptables status clickable to show diagnostic dialog
            textIptablesStatus?.setOnClickListener {
                showIptablesDiagnosticDialog(detail)
            }

            // Update NFQUEUE rules count with error detail
            if (stats.nfqueueRulesCount > 0) {
                textNfqueueRulesCount?.text = stats.nfqueueRulesCount.toString()
                textNfqueueRulesCount?.setTextColor(okColor)
            } else if (isRunning) {
                // Process running but no NFQUEUE rules = error
                val errMsg = if (detail.errors.isNotEmpty()) detail.errors.first() else "no rules"
                textNfqueueRulesCount?.text = "ERR: 0 — $errMsg"
                textNfqueueRulesCount?.setTextColor(errorColor)
            } else if (detail.errors.isNotEmpty()) {
                textNfqueueRulesCount?.text = "0 — ${detail.errors.first()}"
                textNfqueueRulesCount?.setTextColor(errorColor)
            } else {
                textNfqueueRulesCount?.text = "0"
                textNfqueueRulesCount?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            }
        }
    }

    private fun showIptablesDiagnosticDialog(detail: NetworkStatsManager.IptablesDetail) {
        val ctx = context ?: return

        val message = buildString {
            append("Rules: ${detail.rulesOk} ok / ${detail.rulesFail} failed / ${detail.rulesTotal} total\n\n")

            // Kernel capabilities table
            append("=== Kernel modules ===\n")
            append("NFQUEUE: ${if (detail.nfqueueSupported) "OK" else "NOT SUPPORTED"}\n")
            append("queue-bypass: ${if (detail.queueBypassSupported) "OK" else "NOT SUPPORTED"}\n")
            append("connbytes: ${if (detail.connbytesSupported) "OK" else "NOT SUPPORTED"}\n")
            append("multiport: ${if (detail.multiportSupported) "OK" else "NOT SUPPORTED"}\n")
            append("mark: ${if (detail.markSupported) "OK" else "NOT SUPPORTED"}\n")

            // Fallback mode
            if (detail.fallbackMode) {
                append("\nMode: FALLBACK\n")
                append("Some kernel modules are missing. Rules were applied with reduced functionality. DPI bypass should still work.\n")
            }

            // Diagnostics from shell
            if (detail.diagnostics.isNotBlank()) {
                append("\nDetails: ${detail.diagnostics}\n")
            }

            // Errors list
            if (detail.errors.isNotEmpty()) {
                append("\n=== Failed rules ===\n")
                detail.errors.forEach { error ->
                    append("  - $error\n")
                }
            }

            // Help text based on situation
            append("\n=== What to do ===\n")
            if (!detail.nfqueueSupported) {
                append("Your kernel does not support NFQUEUE. DPI bypass CANNOT work.\n\n")
                append("This requires kernel option CONFIG_NETFILTER_NETLINK_QUEUE which is built into the kernel at compile time. It cannot be enabled without flashing a custom kernel that has this option.\n\n")
                append("Contact your ROM/kernel developer and ask them to enable NFQUEUE support.")
            } else if (detail.rulesOk > 0 && detail.rulesFail == 0) {
                append("All rules applied successfully. Everything is working correctly.")
                if (detail.fallbackMode) {
                    append("\n\nFallback mode: slightly higher CPU usage is normal. This is not a problem.")
                }
            } else if (detail.rulesOk > 0) {
                append("Some rules failed (usually IPv6). DPI bypass should work for most traffic since IPv4 rules are active.\n\n")
                append("IPv6 failures are normal on many devices and do not affect functionality.")
            } else {
                append("All rules failed. Possible causes:\n")
                append("- Another module or app conflicts with iptables rules\n")
                append("- Kernel is missing required netfilter modules\n")
                append("- SELinux is blocking iptables modifications\n\n")
                append("Try:\n")
                append("1. Reboot the device\n")
                append("2. Disable other VPN/firewall apps (AdGuard, AFWall+, etc.)\n")
                append("3. Check if your kernel supports netfilter (custom kernels may strip it)")
            }
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("iptables Diagnostics")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ==================== QUIC Warning Banner ====================

    /**
     * Sets up the QUIC warning banner visibility based on SharedPreferences.
     * Shows the banner if user hasn't dismissed it yet.
     */
    private fun setupQuicWarningBanner() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean(KEY_QUIC_BANNER_DISMISSED, false)

        if (!isDismissed) {
            bannerQuicWarning?.visibility = View.VISIBLE
        } else {
            bannerQuicWarning?.visibility = View.GONE
        }

        // Set up dismiss button click listener
        buttonDismissQuicBanner?.setOnClickListener {
            dismissQuicWarningBanner()
        }
    }

    /**
     * Dismisses the QUIC warning banner and saves the preference.
     */
    private fun dismissQuicWarningBanner() {
        val ctx = context ?: return

        // Hide banner with animation
        bannerQuicWarning?.animate()
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                bannerQuicWarning?.visibility = View.GONE
                bannerQuicWarning?.alpha = 1f
            }
            ?.start()

        // Save dismissed state to SharedPreferences
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_QUIC_BANNER_DISMISSED, true).apply()
    }

}
