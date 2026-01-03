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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
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

    // Log tab enum
    private enum class LogTab { LOGCAT, OUTPUT, ERRORS }

    // UI Elements
    private lateinit var tabLayoutLogs: TabLayout
    private lateinit var layoutCmdlineHeader: LinearLayout
    private lateinit var layoutCmdlineContent: LinearLayout
    private lateinit var iconCmdlineExpand: ImageView
    private lateinit var iconCopyCmdline: ImageView
    private lateinit var textCmdline: TextView
    private lateinit var editLogFilter: TextInputEditText
    private lateinit var checkAutoScroll: MaterialCheckBox
    private lateinit var scrollLogs: ScrollView
    private lateinit var textLogs: TextView
    private lateinit var buttonRefresh: MaterialButton
    private lateinit var buttonCopyLogs: MaterialButton
    private lateinit var buttonClearLogs: MaterialButton

    // State
    private var currentTab = LogTab.LOGCAT
    private var isCmdlineExpanded = false
    private var currentLogs = ""
    private var pollingJob: Job? = null

    // File paths
    companion object {
        private const val LOG_FILE = "/data/local/tmp/zapret2.log"
        private const val ERROR_FILE = "/data/local/tmp/zapret2_error.log"
        private const val CMDLINE_FILE = "/data/local/tmp/nfqws2-cmdline.txt"
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_LINES = 500
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
        setupFilterInput()
        setupButtons()
        loadCmdline()
        loadLogs()
        startPolling()
    }

    private fun initViews(view: View) {
        tabLayoutLogs = view.findViewById(R.id.tabLayoutLogs)
        layoutCmdlineHeader = view.findViewById(R.id.layoutCmdlineHeader)
        layoutCmdlineContent = view.findViewById(R.id.layoutCmdlineContent)
        iconCmdlineExpand = view.findViewById(R.id.iconCmdlineExpand)
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
        tabLayoutLogs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = when (tab?.position) {
                    0 -> LogTab.LOGCAT
                    1 -> LogTab.OUTPUT
                    2 -> LogTab.ERRORS
                    else -> LogTab.LOGCAT
                }
                loadLogs()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                loadLogs()
            }
        })
    }

    private fun setupCmdlineSection() {
        layoutCmdlineHeader.setOnClickListener {
            toggleCmdlineExpand()
        }

        iconCopyCmdline.setOnClickListener {
            val cmdline = textCmdline.text.toString()
            if (cmdline.isNotBlank() && cmdline != "No command line available") {
                copyToClipboard(cmdline, "Command line")
            }
        }
    }

    private fun setupFilterInput() {
        editLogFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                displayLogs(currentLogs)
            }
        })
    }

    private fun setupButtons() {
        buttonRefresh.setOnClickListener {
            loadCmdline()
            loadLogs()
        }

        buttonCopyLogs.setOnClickListener {
            if (currentLogs.isNotBlank()) {
                copyToClipboard(currentLogs, "Logs")
            } else {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
            }
        }

        buttonClearLogs.setOnClickListener {
            clearLogs()
        }
    }

    private fun toggleCmdlineExpand() {
        isCmdlineExpanded = !isCmdlineExpanded
        layoutCmdlineContent.visibility = if (isCmdlineExpanded) View.VISIBLE else View.GONE
        iconCmdlineExpand.animate()
            .rotation(if (isCmdlineExpanded) 180f else 0f)
            .setDuration(200)
            .start()
    }

    private fun loadCmdline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cmdline = withContext(Dispatchers.IO) {
                try {
                    val result = Shell.cmd("cat $CMDLINE_FILE").exec()
                    if (result.isSuccess && result.out.isNotEmpty()) {
                        // Join all lines and clean up the output
                        result.out
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .trim()
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    ""
                }
            }

            textCmdline.text = cmdline.ifBlank { "No command line available" }
        }
    }

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonRefresh.isEnabled = false

            val logs = withContext(Dispatchers.IO) {
                fetchLogs()
            }

            currentLogs = logs
            displayLogs(logs)
            buttonRefresh.isEnabled = true
        }
    }

    private fun fetchLogs(): String {
        return try {
            val command = when (currentTab) {
                LogTab.LOGCAT -> {
                    // Get logcat entries tagged with Zapret2 or nfqws2
                    "logcat -d -s Zapret2:* nfqws2:* *:S | tail -n $MAX_LINES"
                }
                LogTab.OUTPUT -> {
                    "tail -n $MAX_LINES $LOG_FILE"
                }
                LogTab.ERRORS -> {
                    "tail -n $MAX_LINES $ERROR_FILE"
                }
            }

            val result = Shell.cmd(command).exec()

            if (result.isSuccess && result.out.isNotEmpty()) {
                result.out
                    .filter { line ->
                        // Filter out garbage lines
                        line.isNotBlank() &&
                        !isGarbageLine(line)
                    }
                    .joinToString("\n")
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

    private fun displayLogs(logs: String) {
        val filterText = editLogFilter.text?.toString()?.trim() ?: ""

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

        textLogs.text = finalText

        // Auto-scroll to bottom if enabled
        if (checkAutoScroll.isChecked && displayText.isNotBlank()) {
            scrollLogs.post {
                scrollLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun getEmptyStateMessage(): String {
        return when (currentTab) {
            LogTab.LOGCAT -> "No logcat entries found.\n\nStart the Zapret2 service to see logs here."
            LogTab.OUTPUT -> "No output logs found.\n\nOutput will appear here when the service runs."
            LogTab.ERRORS -> "No error logs found.\n\nThis is good - no errors have occurred."
        }
    }

    private fun clearLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            buttonClearLogs.isEnabled = false

            val success = withContext(Dispatchers.IO) {
                try {
                    val command = when (currentTab) {
                        LogTab.LOGCAT -> "logcat -c"
                        LogTab.OUTPUT -> "> $LOG_FILE"
                        LogTab.ERRORS -> "> $ERROR_FILE"
                    }
                    Shell.cmd(command).exec().isSuccess
                } catch (e: Exception) {
                    false
                }
            }

            if (success) {
                Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
                currentLogs = ""
                displayLogs("")
            } else {
                Toast.makeText(requireContext(), "Failed to clear logs", Toast.LENGTH_SHORT).show()
            }

            buttonClearLogs.isEnabled = true
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    if (isActive) {
                        val logs = withContext(Dispatchers.IO) {
                            fetchLogs()
                        }
                        // Only update if logs changed
                        if (logs != currentLogs) {
                            currentLogs = logs
                            displayLogs(logs)
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to copy", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadCmdline()
        loadLogs()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollingJob?.cancel()
        pollingJob = null
    }
}
