package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
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

    private lateinit var textCommandFilePath: TextView
    private lateinit var editCommandLine: EditText
    private lateinit var buttonReloadCommand: MaterialButton
    private lateinit var buttonSaveCommand: MaterialButton
    private lateinit var buttonSaveRestartCommand: MaterialButton

    private val moduleDir = "/data/adb/modules/zapret2"
    private val configFile = "$moduleDir/zapret2/config.sh"
    private val userConfigFile = "/data/local/tmp/zapret2-user.conf"
    private val commandFile = "$moduleDir/zapret2/cmdline.txt"
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
                    saveCommandLine(restartAfterSave = true)
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

            if (!isAdded) return@launch

            editCommandLine.setText(commandLine)
            setActionsEnabled(true)
        }
    }

    private fun saveCommandLine(restartAfterSave: Boolean, forceCmdlineMode: Boolean = false) {
        val commandText = normalizeLineEndings(editCommandLine.text?.toString().orEmpty()).trimEnd('\n', '\r')
        if (commandText.isBlank()) {
            Toast.makeText(requireContext(), "Command line is empty", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val (saved, restarted) = withContext(Dispatchers.IO) {
                val writeResult = Shell.cmd(buildSafeWriteCommand(commandFile, commandText)).exec()
                if (!writeResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                if (forceCmdlineMode) {
                    if (!syncCmdlineMode(configFile)) {
                        return@withContext Pair(false, false)
                    }
                    if (!syncCmdlineMode(userConfigFile)) {
                        return@withContext Pair(false, false)
                    }
                }

                if (!restartAfterSave) {
                    return@withContext Pair(true, true)
                }

                val restartResult = Shell.cmd("sh $restartScript").exec()
                Pair(true, restartResult.isSuccess)
            }

            if (!isAdded) return@launch

            when {
                !saved -> {
                    Toast.makeText(requireContext(), "Failed to save command line", Toast.LENGTH_SHORT).show()
                }
                restartAfterSave && restarted -> {
                    val message = if (forceCmdlineMode) {
                        "Command line saved and service restarted in cmdline mode"
                    } else {
                        "Command line saved and service restarted"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                }
                restartAfterSave -> {
                    val message = if (forceCmdlineMode) {
                        "Saved, but restart failed in cmdline mode"
                    } else {
                        "Saved, but service restart failed"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val message = if (forceCmdlineMode) {
                        "Command line saved for cmdline mode"
                    } else {
                        "Command line saved"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }

            setActionsEnabled(true)
        }
    }

    private fun showRestartChoiceDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Command line restart mode")
            .setMessage("Current preset mode is not cmdline. Restart will use current strategy mode. Enable raw cmdline mode and restart?\n\nThis is an advanced option that requires manual command-line settings.")
            .setNegativeButton("Restart with current mode") { _, _ ->
                saveCommandLine(restartAfterSave = true)
            }
            .setPositiveButton("Enable cmdline mode") { _, _ ->
                saveCommandLine(restartAfterSave = true, forceCmdlineMode = true)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private suspend fun isCmdlineModeEnabled(): Boolean {
        val userMode = withContext(Dispatchers.IO) { readConfigValue(userConfigFile, "PRESET_MODE") }
        val fallbackMode = withContext(Dispatchers.IO) { readConfigValue(configFile, "PRESET_MODE") }
        val mode = (userMode ?: fallbackMode ?: "categories").lowercase()
        return mode == "cmdline" || mode == "manual" || mode == "raw"
    }

    private fun readConfigValue(filePath: String, key: String): String? {
        val result = Shell.cmd("cat '$filePath' 2>/dev/null").exec()
        if (!result.isSuccess) return null

        val pattern = Regex("(?i)^\\s*${Regex.escape(key)}\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s#]*))")
        return result.out.joinToString("\n").lineSequence()
            .map { it.trim() }
            .firstOrNull { pattern.matches(it) }
            ?.let { line ->
                pattern.find(line)?.let { match ->
                    (match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value)
                        ?.trim()
                }
            }
    }

    private fun syncCmdlineMode(targetFile: String): Boolean {
        if (!upsertConfigValue(targetFile, "PRESET_MODE", "cmdline")) {
            return false
        }
        if (!upsertConfigValue(targetFile, "CUSTOM_CMDLINE_FILE", commandFile)) {
            return false
        }
        return true
    }

    private fun upsertConfigValue(filePath: String, key: String, value: String): Boolean {
        val escapedPath = filePath.replace("\"", "\\\"")
        val hasValue = Shell.cmd("grep -q '^$key=' \"$escapedPath\" 2>/dev/null && echo 1 || echo 0").exec()
            .out.firstOrNull()?.trim() == "1"

        val result = if (hasValue) {
            val escapedValueForSed = value
                .replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("&", "\\&")
                .replace("\"", "\\\"")
            Shell.cmd("sed -i 's|^$key=.*|$key=\"$escapedValueForSed\"|' \"$escapedPath\"").exec()
        } else {
            val line = "$key=\"$value\"".replace("'", "'\\''")
            Shell.cmd("echo '$line' >> \"$escapedPath\"").exec()
        }

        return result.isSuccess
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
