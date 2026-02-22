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
            saveCommandLine(restartAfterSave = true)
        }
    }

    private fun loadCommandLine() {
        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val commandLine = withContext(Dispatchers.IO) {
                val manualResult = Shell.cmd("cat \"$commandFile\" 2>/dev/null").exec()
                if (manualResult.isSuccess && manualResult.out.isNotEmpty()) {
                    manualResult.out.joinToString("\n").trimEnd()
                } else {
                    val runtimeResult = Shell.cmd("cat \"$runtimeCmdlineFile\" 2>/dev/null").exec()
                    if (runtimeResult.isSuccess && runtimeResult.out.isNotEmpty()) {
                        stripBinaryPrefix(runtimeResult.out.joinToString(" ").trim())
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

    private fun saveCommandLine(restartAfterSave: Boolean) {
        val commandText = editCommandLine.text?.toString().orEmpty().replace("\r\n", "\n").trim()
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

                if (!syncCmdlineMode(configFile)) {
                    return@withContext Pair(false, false)
                }
                if (!syncCmdlineMode(userConfigFile)) {
                    return@withContext Pair(false, false)
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
                    Toast.makeText(requireContext(), "Saved and restarted", Toast.LENGTH_SHORT).show()
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                }
                restartAfterSave -> {
                    Toast.makeText(requireContext(), "Saved, restart failed", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(requireContext(), "Command line saved", Toast.LENGTH_SHORT).show()
                }
            }

            setActionsEnabled(true)
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
        val trimmed = cmdline.trim()
        if (trimmed.isEmpty()) return ""

        val firstToken = trimmed.substringBefore(' ')
        return if (firstToken == "nfqws2" || firstToken.endsWith("/nfqws2")) {
            trimmed.removePrefix(firstToken).trimStart()
        } else {
            trimmed
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
