package com.zapret2.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsFragment : Fragment() {

    // Main tab enum (Command, Logs, Errors)
    private enum class MainTab { COMMAND, LOGS, WARNINGS }

    // Log source enum (for logs tab)
    private enum class LogSource { LOGCAT, OUTPUT, ERRORS }

    // UI Elements (nullable for safe access)
    private var tabLayoutLogs: TabLayout? = null
    private var cmdlineContainer: View? = null
    private var logsContainer: View? = null
    private var iconCopyCmdline: ImageView? = null
    private var textCmdline: TextView? = null
    private var editLogFilter: TextInputEditText? = null
    private var checkAutoScroll: MaterialCheckBox? = null
    private var scrollLogs: ScrollView? = null
    private var textLogs: TextView? = null
    private var buttonRefresh: MaterialButton? = null
    private var buttonCopyLogs: MaterialButton? = null
    private var buttonClearLogs: MaterialButton? = null

    // State
    private var currentMainTab = MainTab.COMMAND
    private var currentLogSource = LogSource.LOGCAT
    private var currentLogs = ""
    private var rawCmdline = ""  // Raw cmdline for clipboard (single line)
    private var pollingJob: Job? = null

    // File paths
    companion object {
        private const val LOG_FILE = "/data/local/tmp/zapret2.log"
        private const val ERROR_FILE = "/data/local/tmp/nfqws2-error.log"
        private const val CMDLINE_FILE = "/data/local/tmp/nfqws2-cmdline.txt"
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_LINES = 500
        const val SERVICE_RESTARTED_KEY = "service_restarted"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupTabListener()
        setupCmdlineSection()

        // Listen for service restart events from StrategiesFragment
        parentFragmentManager.setFragmentResultListener(SERVICE_RESTARTED_KEY, viewLifecycleOwner) { _, _ ->
            refreshAll()
        }
        setupFilterInput()
        setupButtons()

        // Default to Command tab (position 0)
        showCommandTab()
        loadCmdline()
    }

    private fun initViews(view: View) {
        tabLayoutLogs = view.findViewById(R.id.tabLayoutLogs)
        cmdlineContainer = view.findViewById(R.id.cmdlineContainer)
        logsContainer = view.findViewById(R.id.logsContainer)
        iconCopyCmdline = view.findViewById(R.id.iconCopyCmdline)
        textCmdline = view.findViewById(R.id.textCmdline)
        editLogFilter = view.findViewById(R.id.editLogFilter)
        checkAutoScroll = view.findViewById(R.id.checkAutoScroll)
        scrollLogs = view.findViewById(R.id.scrollLogs)
        textLogs = view.findViewById(R.id.textLogs)
        buttonRefresh = view.findViewById(R.id.buttonRefresh)
        buttonCopyLogs = view.findViewById(R.id.buttonCopyLogs)
        buttonClearLogs = view.findViewById(R.id.buttonClearLogs)
    }

    private fun setupTabListener() {
        tabLayoutLogs?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        // Command tab selected
                        currentMainTab = MainTab.COMMAND
                        showCommandTab()
                        loadCmdline()
                    }
                    1 -> {
                        // Logs tab selected
                        currentMainTab = MainTab.LOGS
                        currentLogSource = LogSource.OUTPUT
                        showLogsTab()
                        // Clear current logs to prevent showing stale data
                        currentLogs = ""
                        textLogs?.text = "Loading..."
                        loadLogs()
                        startPolling()
                    }
                    2 -> {
                        // Errors tab selected
                        currentMainTab = MainTab.WARNINGS
                        currentLogSource = LogSource.ERRORS
                        showLogsTab()
                        // Clear current logs to prevent showing stale data
                        currentLogs = ""
                        textLogs?.text = "Loading warnings/errors..."
                        loadLogs()
                        startPolling()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // Stop polling when leaving Logs tab
                if (tab?.position == 1 || tab?.position == 2) {
                    pollingJob?.cancel()
                    pollingJob = null
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> loadCmdline()
                    1, 2 -> loadLogs()
                }
            }
        })
    }

    /**
     * Show Command tab content, hide Logs tab content
     */
    private fun showCommandTab() {
        cmdlineContainer?.visibility = View.VISIBLE
        logsContainer?.visibility = View.GONE
    }

    /**
     * Show Logs tab content, hide Command tab content
     */
    private fun showLogsTab() {
        cmdlineContainer?.visibility = View.GONE
        logsContainer?.visibility = View.VISIBLE
    }

    private fun setupCmdlineSection() {
        iconCopyCmdline?.setOnClickListener {
            // Copy the raw (single-line) cmdline for pasting into terminal
            if (rawCmdline.isNotBlank()) {
                copyToClipboard(rawCmdline, "Command line")
            }
        }
    }

    private fun setupFilterInput() {
        editLogFilter?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                displayLogs(currentLogs)
            }
        })
    }

    private fun setupButtons() {
        buttonRefresh?.setOnClickListener {
            when (currentMainTab) {
                MainTab.COMMAND -> loadCmdline()
                MainTab.LOGS, MainTab.WARNINGS -> {
                    loadCmdline()
                    loadLogs()
                }
            }
        }

        buttonCopyLogs?.setOnClickListener {
            when (currentMainTab) {
                MainTab.COMMAND -> {
                    if (rawCmdline.isNotBlank()) {
                        copyToClipboard(rawCmdline, "Command line")
                    } else {
                        view?.let { Snackbar.make(it, "No command to copy", Snackbar.LENGTH_SHORT).show() }
                    }
                }
                MainTab.LOGS, MainTab.WARNINGS -> {
                    if (currentLogs.isNotBlank()) {
                        val label = if (currentMainTab == MainTab.WARNINGS) "Warnings" else "Logs"
                        copyToClipboard(currentLogs, label)
                    } else {
                        view?.let { Snackbar.make(it, "No logs to copy", Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
        }

        buttonClearLogs?.setOnClickListener {
            clearLogs()
        }
    }

    private fun loadCmdline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (raw, formatted) = withContext(Dispatchers.IO) {
                fetchCmdline()
            }
            if (isAdded && view != null) {
                rawCmdline = raw
                textCmdline?.text = formatted.ifBlank { "No command line available" }
            }
        }
    }

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded || view == null) return@launch
            buttonRefresh?.isEnabled = false

            val logs = withContext(Dispatchers.IO) {
                fetchLogs()
            }

            if (!isAdded || view == null) return@launch
            currentLogs = logs
            displayLogs(logs)
            buttonRefresh?.isEnabled = true
        }
    }

    private fun fetchLogs(): String {
        return try {
            val command = when (currentLogSource) {
                LogSource.LOGCAT -> {
                    // Get logcat entries tagged with Zapret2 or nfqws2
                    "logcat -d -s Zapret2:* nfqws2:* *:S | tail -n $MAX_LINES"
                }
                LogSource.OUTPUT -> {
                    "tail -n $MAX_LINES $LOG_FILE"
                }
                LogSource.ERRORS -> {
                    "{ if [ -f \"$LOG_FILE\" ]; then tail -n $MAX_LINES \"$LOG_FILE\"; fi; " +
                        "if [ -f \"$ERROR_FILE\" ]; then tail -n $MAX_LINES \"$ERROR_FILE\"; fi; }"
                }
            }

            val result = Shell.cmd(command).exec()
            val output = result.out

            if (result.isSuccess && !output.isNullOrEmpty()) {
                when (currentLogSource) {
                    LogSource.ERRORS -> output
                        .filter { line ->
                            line.isNotBlank() &&
                            isWarningOrErrorLine(line) &&
                            !isGarbageLine(line)
                        }
                        .joinToString("\n")
                    else -> output
                        .filter { line ->
                            // Filter out garbage lines
                            line.isNotBlank() &&
                            !isGarbageLine(line)
                        }
                        .joinToString("\n")
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    /**
     * Filter out garbage/malformed lines from logs
     */
    private fun isGarbageLine(line: String): Boolean {
        val trimmed = line.trim()

        // Skip very short lines that are likely garbage
        if (trimmed.length <= 2) return true

        // Skip lines that are just repeated characters
        if (trimmed.all { it == trimmed[0] }) return true

        // Skip lines that look like broken command fragments
        if (trimmed == "-t" || trimmed == "-n" || trimmed == "--") return true

        return false
    }

    private fun isWarningOrErrorLine(line: String): Boolean {
        val lower = line.lowercase()

        return lower.contains("error") ||
            lower.contains("warn") ||
            lower.contains("fatal") ||
            lower.contains("failed") ||
            lower.contains("permission denied") ||
            lower.contains("not found")
    }

    private fun displayLogs(logs: String) {
        // Safety check - ensure fragment is attached and views are available
        if (!isAdded || view == null) return

        val filterText = editLogFilter?.text?.toString()?.trim() ?: ""

        val displayText = if (filterText.isNotEmpty()) {
            logs.lines()
                .filter { it.contains(filterText, ignoreCase = true) }
                .joinToString("\n")
        } else {
            logs
        }

        val finalText = if (displayText.isBlank()) {
            getEmptyStateMessage()
        } else {
            displayText
        }

        textLogs?.text = finalText

        // Auto-scroll to bottom if enabled (check view is attached)
        if (checkAutoScroll?.isChecked == true && displayText.isNotBlank()) {
            scrollLogs?.post {
                if (isAdded && view != null) {
                    scrollLogs?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun getEmptyStateMessage(): String {
        return when (currentLogSource) {
            LogSource.LOGCAT -> "No logcat entries found.\n\nStart the Zapret2 service to see logs here."
            LogSource.OUTPUT -> "No output logs found.\n\nOutput will appear here when the service runs."
            LogSource.ERRORS -> "No warning/error entries found.\n\nThis is good - no recent issues were detected."
        }
    }

    private fun clearLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded || view == null) return@launch
            buttonClearLogs?.isEnabled = false

            val success = withContext(Dispatchers.IO) {
                try {
                    val command = when (currentLogSource) {
                        LogSource.LOGCAT -> "logcat -c"
                        LogSource.OUTPUT -> "> $LOG_FILE"
                        LogSource.ERRORS -> "{ > \"$ERROR_FILE\"; > \"$LOG_FILE\"; }"
                    }
                    Shell.cmd(command).exec().isSuccess
                } catch (e: Exception) {
                    false
                }
            }

            if (!isAdded || view == null) return@launch

            if (success) {
                view?.let { Snackbar.make(it, "Logs cleared", Snackbar.LENGTH_SHORT).show() }
                currentLogs = ""
                displayLogs("")
            } else {
                view?.let { Snackbar.make(it, "Failed to clear logs", Snackbar.LENGTH_SHORT).show() }
            }

            buttonClearLogs?.isEnabled = true
        }
    }

    private fun startPolling() {
        // Only poll when on Logs tab and fragment is attached
        if (currentMainTab != MainTab.LOGS && currentMainTab != MainTab.WARNINGS) return
        if (!isAdded || view == null) return

        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive && (currentMainTab == MainTab.LOGS || currentMainTab == MainTab.WARNINGS)) {
                    delay(POLL_INTERVAL_MS)
                    if (isActive && (currentMainTab == MainTab.LOGS || currentMainTab == MainTab.WARNINGS)) {
                        // Fetch logs only (cmdline is on a different tab now)
                        val newLogs = withContext(Dispatchers.IO) { fetchLogs() }

                        // Only update UI if still active and on Logs tab
                        if (isActive && isAdded && view != null &&
                            (currentMainTab == MainTab.LOGS || currentMainTab == MainTab.WARNINGS)
                        ) {
                            // Only update if logs changed
                            if (newLogs != currentLogs) {
                                currentLogs = newLogs
                                displayLogs(newLogs)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch cmdline from file (runs on IO dispatcher)
     * @return Pair of (raw cmdline for clipboard, formatted cmdline for display)
     */
    private fun fetchCmdline(): Pair<String, String> {
        return try {
            val result = Shell.cmd("cat $CMDLINE_FILE").exec()
            val output = result.out
            if (result.isSuccess && !output.isNullOrEmpty()) {
                val raw = output
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()
                val formatted = formatCmdline(raw)
                Pair(raw, formatted)
            } else {
                Pair("", "")
            }
        } catch (e: Exception) {
            Pair("", "")
        }
    }

    /**
     * Format command line for nice display.
     * Splits by " --" and displays each argument on a new line with indentation.
     *
     * Example output:
     * /data/adb/modules/zapret2/zapret2/nfqws2
     *   --qnum=200
     *   --fwmark=0x40000000
     *   --filter-tcp=80,443
     *   ...
     */
    private fun formatCmdline(cmdline: String): String {
        if (cmdline.isBlank()) return ""

        // Split by " --" to get individual arguments
        val parts = cmdline.split(" --")

        if (parts.isEmpty()) return cmdline

        // First part is the executable path
        val executable = parts[0].trim()

        // Remaining parts are arguments (need to add "--" prefix back)
        val arguments = parts.drop(1)
            .filter { it.isNotBlank() }
            .map { "  --${it.trim()}" }

        // Combine: executable on first line, then each argument indented on new lines
        return if (arguments.isEmpty()) {
            executable
        } else {
            executable + "\n" + arguments.joinToString("\n")
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val ctx = context ?: return
        try {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            view?.let { Snackbar.make(it, "$label copied to clipboard", Snackbar.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            view?.let { Snackbar.make(it, "Failed to copy", Snackbar.LENGTH_SHORT).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh content based on current tab
        when (currentMainTab) {
            MainTab.COMMAND -> loadCmdline()
            MainTab.LOGS, MainTab.WARNINGS -> {
                loadLogs()
                startPolling()
            }
        }
    }

    override fun onDestroyView() {
        pollingJob?.cancel()
        pollingJob = null

        // Null out view references to prevent stale access after view destruction
        tabLayoutLogs = null
        cmdlineContainer = null
        logsContainer = null
        iconCopyCmdline = null
        textCmdline = null
        editLogFilter = null
        checkAutoScroll = null
        scrollLogs = null
        textLogs = null
        buttonRefresh = null
        buttonCopyLogs = null
        buttonClearLogs = null
        super.onDestroyView()
    }

    /**
     * Refresh both cmdline and logs after service restart
     */
    private fun refreshAll() {
        loadCmdline()
        loadLogs()
    }
}
