package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigEditorFragment : Fragment() {

    private data class CmdlineSourceState(
        val mode: String,
        val cmdlineFile: String
    )

    private lateinit var textCommandFilePath: TextView
    private lateinit var editCommandLine: EditText
    private lateinit var buttonReloadCommand: MaterialButton
    private lateinit var buttonSaveCommand: MaterialButton
    private lateinit var buttonSaveRestartCommand: MaterialButton

    private val moduleDir = "/data/adb/modules/zapret2"
    private val commandFile = "$moduleDir/zapret2/cmdline.txt"
    private val commandFileName = "cmdline.txt"
    private val runtimeCmdlineFile = "/data/local/tmp/nfqws2-cmdline.txt"
    private val restartScript = "$moduleDir/zapret2/scripts/zapret-restart.sh"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_config_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupButtons()
        loadCommandLine()
    }

    private fun initViews(view: View) {
        textCommandFilePath = view.findViewById(R.id.textCommandFilePath)
        editCommandLine = view.findViewById(R.id.editCommandLine)
        buttonReloadCommand = view.findViewById(R.id.buttonReloadCommand)
        buttonSaveCommand = view.findViewById(R.id.buttonSaveCommand)
        buttonSaveRestartCommand = view.findViewById(R.id.buttonSaveRestartCommand)

        textCommandFilePath.text = commandFile
    }

    private fun setupButtons() {
        buttonReloadCommand.setOnClickListener {
            loadCommandLine()
        }

        buttonSaveCommand.setOnClickListener {
            saveCommandLine(restartAfterSave = false)
        }

        buttonSaveRestartCommand.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (isCmdlineModeEnabled()) {
                    saveCommandLine(restartAfterSave = true, syncCmdlineSource = true)
                } else {
                    showRestartChoiceDialog()
                }
            }
        }
    }

    private fun loadCommandLine() {
        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val commandLine = withContext(Dispatchers.IO) {
                val manualResult = Shell.cmd("cat \"$commandFile\" 2>/dev/null").exec()
                if (manualResult.isSuccess && manualResult.out.isNotEmpty()) {
                    formatCommandForEditor(manualResult.out.joinToString("\n"))
                } else {
                    val runtimeResult = Shell.cmd("cat \"$runtimeCmdlineFile\" 2>/dev/null").exec()
                    if (runtimeResult.isSuccess && runtimeResult.out.isNotEmpty()) {
                        formatCommandForEditor(runtimeResult.out.joinToString("\n"))
                    } else {
                        ""
                    }
                }
            }

            if (!isAdded || view == null) return@launch

            editCommandLine.setText(commandLine)
            setActionsEnabled(true)
        }
    }

    private fun saveCommandLine(
        restartAfterSave: Boolean,
        forceCmdlineMode: Boolean = false,
        syncCmdlineSource: Boolean = false
    ) {
        val commandText = normalizeLineEndings(editCommandLine.text?.toString().orEmpty()).trimEnd('\n', '\r')
        if (commandText.isBlank()) {
            view?.let { Snackbar.make(it, "Command line is empty", Snackbar.LENGTH_SHORT).show() }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val (saved, restarted) = withContext(Dispatchers.IO) {
                val writeResult = Shell.cmd(buildSafeWriteCommand(commandFile, commandText)).exec()
                if (!writeResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                if (forceCmdlineMode || syncCmdlineSource) {
                    if (!syncCmdlineMode()) {
                        return@withContext Pair(false, false)
                    }
                }

                if (!restartAfterSave) {
                    return@withContext Pair(true, true)
                }

                val restartResult = Shell.cmd("sh $restartScript").exec()
                Pair(true, restartResult.isSuccess)
            }

            if (!isAdded || view == null) return@launch

            when {
                !saved -> {
                    view?.let { Snackbar.make(it, "Failed to save command line", Snackbar.LENGTH_SHORT).show() }
                }
                restartAfterSave && restarted -> {
                    val message = if (forceCmdlineMode) {
                        "Command line saved and service restarted in cmdline mode"
                    } else {
                        "Command line saved and service restarted"
                    }
                    view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                }
                restartAfterSave -> {
                    val message = if (forceCmdlineMode) {
                        "Saved, but restart failed in cmdline mode"
                    } else {
                        "Saved, but service restart failed"
                    }
                    view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
                }
                else -> {
                    val message = if (forceCmdlineMode) {
                        "Command line saved for cmdline mode"
                    } else {
                        "Command line saved"
                    }
                    view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
                }
            }

            setActionsEnabled(true)
        }
    }

    private fun showRestartChoiceDialog() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Command line restart mode")
            .setMessage("Current preset mode is not cmdline. Restart will use current strategy mode. Enable raw cmdline mode and restart?\n\nThis is an advanced option that requires manual command-line settings.")
            .setNegativeButton("Restart with current mode") { _, _ ->
                saveCommandLine(restartAfterSave = true, syncCmdlineSource = false)
            }
            .setPositiveButton("Enable cmdline mode") { _, _ ->
                saveCommandLine(
                    restartAfterSave = true,
                    forceCmdlineMode = true,
                    syncCmdlineSource = true
                )
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private suspend fun isCmdlineModeEnabled(): Boolean {
        val mode = loadCmdlineSourceState().mode.lowercase()
        return mode == "cmdline" || mode == "manual" || mode == "raw"
    }

    private suspend fun loadCmdlineSourceState(): CmdlineSourceState {
        val runtimeCore = RuntimeConfigStore.readCore()

        val mode = (runtimeCore["preset_mode"]
            ?: "categories")
            .orEmpty()
            .ifEmpty { "categories" }

        val cmdlineFile = (runtimeCore["custom_cmdline_file"]
            ?: commandFileName)
            .orEmpty()
            .ifEmpty { commandFileName }

        return CmdlineSourceState(mode = mode, cmdlineFile = cmdlineFile)
    }

    private suspend fun syncCmdlineMode(): Boolean {
        return RuntimeConfigStore.setActiveModeValues(
            RuntimeConfigStore.CoreSettingsUpdate(
                presetMode = "cmdline",
                customCmdlineFile = commandFileName
            )
        )
    }

    private fun stripBinaryPrefix(cmdline: String): String {
        val trimmed = normalizeLineEndings(cmdline).trimStart()
        if (trimmed.isEmpty()) return ""

        val firstWhitespaceIndex = trimmed.indexOfFirst { it.isWhitespace() }
        val firstToken = if (firstWhitespaceIndex < 0) trimmed else trimmed.substring(0, firstWhitespaceIndex)
        val commandRest = if (firstWhitespaceIndex < 0) "" else trimmed.substring(firstWhitespaceIndex).trimStart()

        return if (firstToken == "nfqws2" || firstToken.endsWith("/nfqws2")) {
            commandRest
        } else {
            trimmed
        }
    }

    private fun normalizeLineEndings(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun formatCommandForEditor(rawCommandLine: String): String {
        val stripped = stripBinaryPrefix(rawCommandLine)
        if (stripped.isBlank()) return ""

        val normalized = normalizeLineEndings(stripped).trim()
        return if (normalized.contains('\n')) {
            normalized.replace(Regex("\\n[ \t]+"), "\n").trim()
        } else {
            normalized.replace(" --", "\n--")
        }
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var delimiter = "__ZAPRET_CMDLINE_EOF__"
        while (content.contains(delimiter)) {
            delimiter += "_X"
        }

        return buildString {
            append("cat <<'")
            append(delimiter)
            append("' > \"")
            append(path)
            append("\"\n")
            append(content)
            append("\n")
            append(delimiter)
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        editCommandLine.isEnabled = enabled
        buttonReloadCommand.isEnabled = enabled
        buttonSaveCommand.isEnabled = enabled
        buttonSaveRestartCommand.isEnabled = enabled
    }
}
