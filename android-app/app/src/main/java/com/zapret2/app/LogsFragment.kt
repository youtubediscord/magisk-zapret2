package com.zapret2.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogsFragment : Fragment() {

    // UI Elements
    private lateinit var tabLayoutLogs: TabLayout
    private lateinit var cardCmdline: View
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

    // Paths
    private val LOGFILE = "/data/local/tmp/zapret2.log"
    private val ERRORFILE = "/data/local/tmp/zapret2_error.log"
    private val CMDLINE_FILE = "/data/local/tmp/nfqws2.cmdline"

    private enum class LogTab {
        LOGCAT, OUTPUT, ERRORS
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
        setupListeners()
        loadCmdline()
        loadLogs()
    }

    private fun initViews(view: View) {
        tabLayoutLogs = view.findViewById(R.id.tabLayoutLogs)
        cardCmdline = view.findViewById(R.id.cardCmdline)
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

    private fun setupListeners() {
        // Tab selection listener
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

        // Cmdline expand/collapse
        layoutCmdlineHeader.setOnClickListener {
            toggleCmdlineExpand()
        }

        // Copy cmdline
        iconCopyCmdline.setOnClickListener {
            copyToClipboard(textCmdline.text.toString(), "Command line")
        }

        // Refresh button
        buttonRefresh.setOnClickListener {
            loadLogs()
        }

        // Copy logs button
        buttonCopyLogs.setOnClickListener {
            copyToClipboard(currentLogs, "Logs")
        }

        // Clear logs button
        buttonClearLogs.setOnClickListener {
            clearLogs()
        }

        // Filter text change
        editLogFilter.setOnEditorActionListener { _, _, _ ->
            applyFilter()
            true
        }
    }

    private fun toggleCmdlineExpand() {
        isCmdlineExpanded = !isCmdlineExpanded
        if (isCmdlineExpanded) {
            layoutCmdlineContent.visibility = View.VISIBLE
            iconCmdlineExpand.rotation = 180f
        } else {
            layoutCmdlineContent.visibility = View.GONE
            iconCmdlineExpand.rotation = 0f
        }
    }

    private fun loadCmdline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cmdline = withContext(Dispatchers.IO) {
                Shell.cmd("cat $CMDLINE_FILE 2>/dev/null || echo 'No command line available'")
                    .exec().out.joinToString(" ")
            }
            textCmdline.text = cmdline.ifBlank { "No command line available" }
        }
    }

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            textLogs.text = "Loading..."
            buttonRefresh.isEnabled = false

            val logs = withContext(Dispatchers.IO) {
                when (currentTab) {
                    LogTab.LOGCAT -> {
                        Shell.cmd("logcat -d -s Zapret2 2>/dev/null | tail -500")
                            .exec().out.joinToString("\n")
                    }
                    LogTab.OUTPUT -> {
                        Shell.cmd("tail -500 $LOGFILE 2>/dev/null || echo 'No output logs'")
                            .exec().out.joinToString("\n")
                    }
                    LogTab.ERRORS -> {
                        Shell.cmd("tail -500 $ERRORFILE 2>/dev/null || echo 'No error logs'")
                            .exec().out.joinToString("\n")
                    }
                }
            }

            currentLogs = logs
            displayLogs(logs)
            buttonRefresh.isEnabled = true

            // Auto-scroll to bottom if enabled
            if (checkAutoScroll.isChecked) {
                scrollLogs.post {
                    scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
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

        textLogs.text = if (displayText.isBlank()) {
            when (currentTab) {
                LogTab.LOGCAT -> "No logcat entries found.\n\nStart the service to see logs here."
                LogTab.OUTPUT -> "No output logs found.\n\nStart the service to generate logs."
                LogTab.ERRORS -> "No error logs found.\n\nThis is good - no errors occurred."
            }
        } else {
            displayText
        }
    }

    private fun applyFilter() {
        displayLogs(currentLogs)
    }

    private fun clearLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val cmd = when (currentTab) {
                    LogTab.LOGCAT -> "logcat -c"
                    LogTab.OUTPUT -> "echo '' > $LOGFILE"
                    LogTab.ERRORS -> "echo '' > $ERRORFILE"
                }
                Shell.cmd(cmd).exec().isSuccess
            }

            if (success) {
                Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
                loadLogs()
            } else {
                Toast.makeText(requireContext(), "Failed to clear logs", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        loadCmdline()
        loadLogs()
    }
}
