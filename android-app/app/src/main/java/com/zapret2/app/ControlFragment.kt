package com.zapret2.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*

class ControlFragment : Fragment() {

    companion object {
        private const val PREFS_NAME = "zapret2_prefs"
        private const val KEY_AUTO_CHECK_UPDATES = "auto_check_updates"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    // UI Elements
    private lateinit var viewStatusIndicator: View
    private lateinit var textStatusTitle: TextView
    private lateinit var textStatusValue: TextView
    private lateinit var textUptime: TextView
    private lateinit var buttonToggle: MaterialButton
    private lateinit var textToggleHint: TextView
    private lateinit var checkAutostart: MaterialCheckBox
    private lateinit var checkWifiOnly: MaterialCheckBox
    private lateinit var textModuleVersion: TextView
    private lateinit var textRootStatus: TextView
    private lateinit var textNfqueueStatus: TextView
    private lateinit var buttonUpdateModule: MaterialButton

    // Status tracking
    private var isRunning = false
    private var statusPollingJob: Job? = null

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
        checkAutostart = view.findViewById(R.id.checkAutostart)
        checkWifiOnly = view.findViewById(R.id.checkWifiOnly)
        textModuleVersion = view.findViewById(R.id.textModuleVersion)
        textRootStatus = view.findViewById(R.id.textRootStatus)
        textNfqueueStatus = view.findViewById(R.id.textNfqueueStatus)
        buttonUpdateModule = view.findViewById(R.id.buttonUpdateModule)
    }

    private fun setupListeners() {
        buttonToggle.setOnClickListener {
            if (isRunning) {
                stopService()
            } else {
                startService()
            }
        }

        checkAutostart.setOnCheckedChangeListener { _, isChecked ->
            saveAutostart(isChecked)
        }

        checkWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            saveWifiOnly(isChecked)
        }

        buttonUpdateModule.setOnClickListener {
            checkForUpdates()
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

            // Check NFQUEUE support
            val nfqueueSupported = withContext(Dispatchers.IO) {
                Shell.cmd("[ -f /proc/net/netfilter/nf_queue ] && echo 1 || echo 0").exec()
                    .out.firstOrNull() == "1"
            }

            if (nfqueueSupported) {
                textNfqueueStatus.text = "Supported"
                textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_running))
            } else {
                textNfqueueStatus.text = "Not supported"
                textNfqueueStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
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
            checkAutostart.isChecked = autostartValue == "1"

            // Parse WIFI_ONLY (if exists)
            val wifiOnlyValue = parseConfigValue(config, "WIFI_ONLY")
            checkWifiOnly.isChecked = wifiOnlyValue == "1"
        }
    }

    private fun parseConfigValue(config: String, key: String): String? {
        val regex = Regex("""$key=["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }

    private fun checkStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val statusResult = withContext(Dispatchers.IO) {
                // First try using zapret-status.sh if it exists
                val statusScript = Shell.cmd("[ -f $SCRIPTS/zapret-status.sh ] && sh $SCRIPTS/zapret-status.sh 2>/dev/null || echo 'no_script'").exec()
                val scriptOutput = statusScript.out.firstOrNull() ?: ""

                if (scriptOutput != "no_script" && scriptOutput.isNotEmpty()) {
                    scriptOutput
                } else {
                    // Fallback to PID check
                    val result = Shell.cmd(
                        "if [ -f $PIDFILE ]; then " +
                        "PID=\$(cat $PIDFILE 2>/dev/null); " +
                        "if [ -n \"\$PID\" ] && [ -d /proc/\$PID ]; then echo 'running'; else echo 'stopped'; fi; " +
                        "else echo 'stopped'; fi"
                    ).exec()
                    result.out.firstOrNull() ?: "stopped"
                }
            }

            isRunning = statusResult.contains("running", ignoreCase = true)
            updateUI()
        }
    }

    private fun updateUI() {
        if (isRunning) {
            setStatus(Status.RUNNING)
            textStatusValue.text = "Running"
            buttonToggle.text = "STOP"
            buttonToggle.setIconResource(R.drawable.ic_stop)
            buttonToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            textToggleHint.text = "Tap to stop DPI bypass"
        } else {
            setStatus(Status.STOPPED)
            textStatusValue.text = "Stopped"
            buttonToggle.text = "START"
            buttonToggle.setIconResource(R.drawable.ic_play)
            buttonToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_blue))
            textToggleHint.text = "Tap to start DPI bypass"
        }
    }

    private fun startService() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonToggle.isEnabled = false
            textStatusValue.text = "Starting..."

            val result = withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-start.sh 2>&1").exec()
            }

            delay(1500)
            checkStatus()
            buttonToggle.isEnabled = true

            if (result.isSuccess) {
                Toast.makeText(requireContext(), "Service started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Start failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopService() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonToggle.isEnabled = false
            textStatusValue.text = "Stopping..."

            withContext(Dispatchers.IO) {
                Shell.cmd("sh $SCRIPTS/zapret-stop.sh 2>&1").exec()
            }

            delay(500)
            checkStatus()
            buttonToggle.isEnabled = true
            Toast.makeText(requireContext(), "Service stopped", Toast.LENGTH_SHORT).show()
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
        checkAutostart.isEnabled = false
        checkWifiOnly.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        // Start status polling every 3 seconds
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        // Stop status polling when fragment is not visible
        stopStatusPolling()
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
        buttonUpdateModule.text = "Проверка..."

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
                        "Установлена последняя версия",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is UpdateManager.UpdateResult.Error -> {
                    Toast.makeText(
                        ctx,
                        "Ошибка проверки: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
