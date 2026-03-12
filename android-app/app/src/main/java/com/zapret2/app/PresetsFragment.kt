package com.zapret2.app

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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

class PresetsFragment : Fragment() {

    private data class PresetEntry(
        val fileName: String,
        val displayName: String
    )

    private data class ActiveSelection(
        val mode: String,
        val presetFile: String,
        val cmdlineFile: String
    )

    private lateinit var textActiveModeValue: TextView
    private lateinit var textActivePresetValue: TextView
    private lateinit var textPresetsPath: TextView
    private lateinit var buttonReloadPresets: MaterialButton
    private lateinit var buttonUseCategories: MaterialButton
    private lateinit var presetListContainer: LinearLayout
    private lateinit var textNoPresets: TextView

    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText: TextView

    private val modDir = "/data/adb/modules/zapret2"
    private val configFile = "$modDir/zapret2/config.sh"
    private val presetsDir = "$modDir/zapret2/presets"
    private val restartScript = "$modDir/zapret2/scripts/zapret-restart.sh"

    private var activeMode: String = "categories"
    private var activePresetFile: String = ""
    private var activeCmdlineFile: String = "cmdline.txt"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_presets, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupListeners()
        refreshAll()
    }

    private fun initViews(view: View) {
        textActiveModeValue = view.findViewById(R.id.textActiveModeValue)
        textActivePresetValue = view.findViewById(R.id.textActivePresetValue)
        textPresetsPath = view.findViewById(R.id.textPresetsPath)
        buttonReloadPresets = view.findViewById(R.id.buttonReloadPresets)
        buttonUseCategories = view.findViewById(R.id.buttonUseCategories)
        presetListContainer = view.findViewById(R.id.presetListContainer)
        textNoPresets = view.findViewById(R.id.textNoPresets)

        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingText = view.findViewById(R.id.loadingText)
    }

    private fun setupListeners() {
        buttonReloadPresets.setOnClickListener {
            refreshAll()
        }

        buttonUseCategories.setOnClickListener {
            switchToCategoriesMode()
        }
    }

    private fun refreshAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Loading presets...")

            val activeSelection = loadActiveSelection()

            activeMode = activeSelection.mode
            activePresetFile = activeSelection.presetFile
            activeCmdlineFile = activeSelection.cmdlineFile

            updateActiveInfo()
            val presets = loadPresetEntries()
            renderPresetList(presets)

            hideLoading()
        }
    }

    private suspend fun loadPresetEntries(): List<PresetEntry> {
        return withContext(Dispatchers.IO) {
            val result = Shell.cmd("ls -1 \"$presetsDir\" 2>/dev/null").exec()
            if (!result.isSuccess) return@withContext emptyList()

            result.out
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { it.endsWith(".txt", ignoreCase = true) }
                .filterNot { it.startsWith("_") }
                .sortedBy { it.lowercase() }
                .map { fileName ->
                    PresetEntry(
                        fileName = fileName,
                        displayName = fileName.removeSuffix(".txt")
                    )
                }
        }
    }

    private fun updateActiveInfo() {
        textActiveModeValue.text = when (activeMode) {
            "file", "preset", "txt" -> "Preset file mode"
            "cmdline", "manual", "raw" -> "Cmdline mode"
            else -> "Categories mode"
        }

        textActivePresetValue.text = when (activeMode) {
            "file", "preset", "txt" -> if (activePresetFile.isNotBlank()) {
                activePresetFile
            } else {
                "(not set)"
            }
            "cmdline", "manual", "raw" -> if (activeCmdlineFile.isNotBlank()) {
                activeCmdlineFile
            } else {
                "cmdline.txt"
            }
            else -> "categories.ini + strategies-*.ini"
        }

        textPresetsPath.text = presetsDir
    }

    private fun renderPresetList(entries: List<PresetEntry>) {
        presetListContainer.removeAllViews()

        if (entries.isEmpty()) {
            textNoPresets.visibility = View.VISIBLE
            return
        }

        textNoPresets.visibility = View.GONE

        entries.forEach { entry ->
            val row = layoutInflater.inflate(
                R.layout.item_preset_file,
                presetListContainer,
                false
            )

            val textPresetTitle = row.findViewById<TextView>(R.id.textPresetTitle)
            val textPresetMeta = row.findViewById<TextView>(R.id.textPresetMeta)
            val textPresetActive = row.findViewById<TextView>(R.id.textPresetActive)
            val buttonApplyPreset = row.findViewById<MaterialButton>(R.id.buttonApplyPreset)
            val buttonEditPreset = row.findViewById<MaterialButton>(R.id.buttonEditPreset)

            val isActive = isActivePresetFile(entry.fileName)

            textPresetTitle.text = entry.displayName
            textPresetMeta.text = entry.fileName
            textPresetActive.visibility = if (isActive) View.VISIBLE else View.GONE

            buttonApplyPreset.text = if (isActive) "Applied" else "Apply"
            buttonApplyPreset.isEnabled = !isActive

            row.setOnClickListener {
                if (!isActive) applyPresetFile(entry.fileName)
            }
            buttonApplyPreset.setOnClickListener {
                applyPresetFile(entry.fileName)
            }
            buttonEditPreset.setOnClickListener {
                openPresetEditor(entry.fileName)
            }

            presetListContainer.addView(row)
        }
    }

    private fun isActivePresetFile(fileName: String): Boolean {
        return (activeMode == "file" || activeMode == "preset" || activeMode == "txt") &&
            activePresetFile == fileName
    }

    private fun applyPresetFile(fileName: String) {
        if (loadingOverlay.visibility == View.VISIBLE) return

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Applying $fileName...")
            setActionsEnabled(false)

            val (stateSaved, restartSuccess) = withContext(Dispatchers.IO) {
                if (!persistActiveSelection(mode = "file", presetFile = fileName)) {
                    return@withContext Pair(false, false)
                }

                val restartResult = Shell.cmd("sh $restartScript").exec()
                Pair(true, restartResult.isSuccess)
            }

            hideLoading()
            setActionsEnabled(true)

            if (!isAdded) return@launch

            when {
                stateSaved && restartSuccess -> {
                    context?.let {
                        Toast.makeText(it, "$fileName applied", Toast.LENGTH_SHORT).show()
                    }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                    refreshAll()
                }
                stateSaved -> {
                    context?.let {
                        Toast.makeText(it, "Preset selected, restart failed", Toast.LENGTH_SHORT).show()
                    }
                    refreshAll()
                }
                else -> {
                    context?.let {
                        Toast.makeText(it, "Failed to apply preset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun switchToCategoriesMode() {
        if (loadingOverlay.visibility == View.VISIBLE) return

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Switching to categories mode...")
            setActionsEnabled(false)

            val (stateSaved, restartSuccess) = withContext(Dispatchers.IO) {
                if (!persistActiveSelection(mode = "categories")) {
                    return@withContext Pair(false, false)
                }

                val restartResult = Shell.cmd("sh $restartScript").exec()
                Pair(true, restartResult.isSuccess)
            }

            hideLoading()
            setActionsEnabled(true)

            if (!isAdded) return@launch

            when {
                stateSaved && restartSuccess -> {
                    context?.let {
                        Toast.makeText(it, "Categories mode enabled", Toast.LENGTH_SHORT).show()
                    }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                    refreshAll()
                }
                stateSaved -> {
                    context?.let {
                        Toast.makeText(it, "Mode switched, restart failed", Toast.LENGTH_SHORT).show()
                    }
                    refreshAll()
                }
                else -> {
                    context?.let {
                        Toast.makeText(it, "Failed to switch mode", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun openPresetEditor(fileName: String) {
        if (loadingOverlay.visibility == View.VISIBLE) return

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Loading $fileName...")

            val readResult = withContext(Dispatchers.IO) {
                val escapedPath = "$presetsDir/$fileName".replace("\"", "\\\"")
                val result = Shell.cmd("cat \"$escapedPath\" 2>/dev/null").exec()
                Pair(result.isSuccess, result.out.joinToString("\n"))
            }

            hideLoading()

            if (!isAdded) return@launch

            if (!readResult.first) {
                context?.let {
                    Toast.makeText(it, "Failed to read preset", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            showEditorDialog(fileName, readResult.second)
        }
    }

    private fun showEditorDialog(fileName: String, initialText: String) {
        val editor = EditText(requireContext()).apply {
            setText(initialText)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF808080.toInt())
            setBackgroundColor(0xFF2D2D2D.toInt())
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 12
            maxLines = 28
            setPadding(24, 24, 24, 24)
        }

        val container = FrameLayout(requireContext()).apply {
            setPadding(32, 8, 32, 0)
            addView(editor, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit preset")
            .setMessage(fileName)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                savePresetText(fileName, editor.text?.toString().orEmpty(), applyAfterSave = false)
            }
            .setNeutralButton("Save + Apply") { _, _ ->
                savePresetText(fileName, editor.text?.toString().orEmpty(), applyAfterSave = true)
            }
            .show()
    }

    private fun savePresetText(fileName: String, content: String, applyAfterSave: Boolean) {
        if (loadingOverlay.visibility == View.VISIBLE) return

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(if (applyAfterSave) "Saving and applying..." else "Saving preset...")
            setActionsEnabled(false)

            val (saved, restartSuccess) = withContext(Dispatchers.IO) {
                val escapedPath = "$presetsDir/$fileName".replace("\"", "\\\"")
                val normalized = content.replace("\r\n", "\n")
                val escaped = normalized.replace("'", "'\\''")

                val writeResult = Shell.cmd("echo '$escaped' > \"$escapedPath\"").exec()
                if (!writeResult.isSuccess) {
                    return@withContext Pair(false, false)
                }

                if (!applyAfterSave) {
                    return@withContext Pair(true, true)
                }

                if (!persistActiveSelection(mode = "file", presetFile = fileName)) {
                    return@withContext Pair(false, false)
                }

                val restartResult = Shell.cmd("sh $restartScript").exec()
                Pair(true, restartResult.isSuccess)
            }

            hideLoading()
            setActionsEnabled(true)

            if (!isAdded) return@launch

            if (!saved) {
                context?.let {
                    Toast.makeText(it, "Failed to save preset", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            if (applyAfterSave) {
                if (restartSuccess) {
                    context?.let {
                        Toast.makeText(it, "Preset saved and applied", Toast.LENGTH_SHORT).show()
                    }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                } else {
                    context?.let {
                        Toast.makeText(it, "Preset saved, restart failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                context?.let {
                    Toast.makeText(it, "Preset saved", Toast.LENGTH_SHORT).show()
                }
            }

            refreshAll()
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        buttonReloadPresets.isEnabled = enabled
        buttonUseCategories.isEnabled = enabled
    }

    private fun showLoading(text: String) {
        loadingText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private suspend fun loadActiveSelection(): ActiveSelection {
        val coreReadResult = RuntimeConfigStore.readCoreResult()
        val runtimeCore = coreReadResult.values
        val configText = if (!coreReadResult.usesRuntimeConfig) {
            withContext(Dispatchers.IO) { readFileText(configFile) }
        } else {
            null
        }

        val mode = (runtimeCore["preset_mode"]
            ?: parseConfigValue(configText, "PRESET_MODE")
            ?: "categories")
            .ifEmpty { "categories" }
            .lowercase()

        val presetFile = (runtimeCore["preset_file"]
            ?: parseConfigValue(configText, "PRESET_FILE")
            ?: "")
            .trim()

        val cmdlineFile = (runtimeCore["custom_cmdline_file"]
            ?: parseConfigValue(configText, "CUSTOM_CMDLINE_FILE")
            ?: "cmdline.txt")
            .trim()

        return ActiveSelection(
            mode = mode,
            presetFile = presetFile,
            cmdlineFile = cmdlineFile.ifEmpty { "cmdline.txt" }
        )
    }

    private suspend fun persistActiveSelection(mode: String, presetFile: String? = null): Boolean {
        return RuntimeConfigStore.setActiveModeValues(
            RuntimeConfigStore.CoreSettingsUpdate(
                presetMode = mode,
                presetFile = presetFile
            )
        )
    }

    private fun readFileText(filePath: String): String? {
        val result = Shell.cmd("cat '$filePath' 2>/dev/null").exec()
        if (!result.isSuccess) {
            return null
        }

        return result.out.joinToString("\n")
    }

    private fun parseConfigValue(config: String?, key: String): String? {
        if (config.isNullOrBlank()) {
            return null
        }

        val regex = Regex("""(?m)^$key\s*=\s*["']?([^"'\n]*)["']?""")
        return regex.find(config)?.groupValues?.get(1)?.trim()
    }
}
