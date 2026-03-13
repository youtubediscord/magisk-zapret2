package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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

    private var textActiveModeValue: TextView? = null
    private var textActivePresetValue: TextView? = null
    private var textPresetsPath: TextView? = null
    private var buttonReloadPresets: MaterialButton? = null
    private var buttonUseCategories: MaterialButton? = null
    private var presetListContainer: LinearLayout? = null
    private var textNoPresets: TextView? = null

    private var loadingOverlay: FrameLayout? = null
    private var loadingText: TextView? = null

    private val modDir = "/data/adb/modules/zapret2"
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

    override fun onDestroyView() {
        textActiveModeValue = null
        textActivePresetValue = null
        textPresetsPath = null
        buttonReloadPresets = null
        buttonUseCategories = null
        presetListContainer = null
        textNoPresets = null
        loadingOverlay = null
        loadingText = null
        super.onDestroyView()
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
        buttonReloadPresets?.setOnClickListener {
            refreshAll()
        }

        buttonUseCategories?.setOnClickListener {
            switchToCategoriesMode()
        }
    }

    private fun refreshAll() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Loading presets...")

            val activeSelection = loadActiveSelection()
            if (!isAdded || view == null) return@launch

            activeMode = activeSelection.mode
            activePresetFile = activeSelection.presetFile
            activeCmdlineFile = activeSelection.cmdlineFile

            updateActiveInfo()
            val presets = loadPresetEntries()
            if (!isAdded || view == null) return@launch
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
        textActiveModeValue?.text = when (activeMode) {
            "file", "preset", "txt" -> "Preset file mode"
            "cmdline", "manual", "raw" -> "Cmdline mode"
            else -> "Categories mode"
        }

        textActivePresetValue?.text = when (activeMode) {
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

        textPresetsPath?.text = presetsDir
    }

    private fun renderPresetList(entries: List<PresetEntry>) {
        presetListContainer?.removeAllViews()

        if (entries.isEmpty()) {
            textNoPresets?.visibility = View.VISIBLE
            return
        }

        textNoPresets?.visibility = View.GONE

        val container = presetListContainer ?: return
        val ctx = context ?: return
        val inflater = LayoutInflater.from(ctx)

        entries.forEach { entry ->
            val row = inflater.inflate(
                R.layout.item_preset_file,
                container,
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

            container.addView(row)
        }
    }

    private fun isActivePresetFile(fileName: String): Boolean {
        return (activeMode == "file" || activeMode == "preset" || activeMode == "txt") &&
            activePresetFile == fileName
    }

    private fun applyPresetFile(fileName: String) {
        if (loadingOverlay?.visibility == View.VISIBLE) return

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
                    view?.let { Snackbar.make(it, "$fileName applied", Snackbar.LENGTH_SHORT).show() }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                    refreshAll()
                }
                stateSaved -> {
                    view?.let { Snackbar.make(it, "Preset selected, restart failed", Snackbar.LENGTH_SHORT).show() }
                    refreshAll()
                }
                else -> {
                    view?.let { Snackbar.make(it, "Failed to apply preset", Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun switchToCategoriesMode() {
        if (loadingOverlay?.visibility == View.VISIBLE) return

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
                    view?.let { Snackbar.make(it, "Categories mode enabled", Snackbar.LENGTH_SHORT).show() }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                    refreshAll()
                }
                stateSaved -> {
                    view?.let { Snackbar.make(it, "Mode switched, restart failed", Snackbar.LENGTH_SHORT).show() }
                    refreshAll()
                }
                else -> {
                    view?.let { Snackbar.make(it, "Failed to switch mode", Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun openPresetEditor(fileName: String) {
        if (loadingOverlay?.visibility == View.VISIBLE) return

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
                view?.let { Snackbar.make(it, "Failed to read preset", Snackbar.LENGTH_SHORT).show() }
                return@launch
            }

            showEditorDialog(fileName, readResult.second)
        }
    }

    private fun showEditorDialog(fileName: String, initialText: String) {
        val ctx = context ?: return

        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_preset_editor, null)
        val editText = dialogView.findViewById<EditText>(R.id.editPresetContent)
        editText.setText(initialText)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Edit preset")
            .setMessage(fileName)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                savePresetText(fileName, editText.text?.toString().orEmpty(), applyAfterSave = false)
            }
            .setNeutralButton("Save + Apply") { _, _ ->
                savePresetText(fileName, editText.text?.toString().orEmpty(), applyAfterSave = true)
            }
            .show()
    }

    private fun savePresetText(fileName: String, content: String, applyAfterSave: Boolean) {
        if (loadingOverlay?.visibility == View.VISIBLE) return

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(if (applyAfterSave) "Saving and applying..." else "Saving preset...")
            setActionsEnabled(false)

            val (saved, restartSuccess) = withContext(Dispatchers.IO) {
                val escapedPath = "$presetsDir/$fileName".replace("\"", "\\\"")
                val normalized = content.replace("\r\n", "\n")

                val writeCmd = buildSafeWriteCommand(escapedPath, normalized)
                val writeResult = Shell.cmd(writeCmd).exec()
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
                view?.let { Snackbar.make(it, "Failed to save preset", Snackbar.LENGTH_SHORT).show() }
                return@launch
            }

            if (applyAfterSave) {
                if (restartSuccess) {
                    view?.let { Snackbar.make(it, "Preset saved and applied", Snackbar.LENGTH_SHORT).show() }
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                } else {
                    view?.let { Snackbar.make(it, "Preset saved, restart failed", Snackbar.LENGTH_SHORT).show() }
                }
            } else {
                view?.let { Snackbar.make(it, "Preset saved", Snackbar.LENGTH_SHORT).show() }
            }

            refreshAll()
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        buttonReloadPresets?.isEnabled = enabled
        buttonUseCategories?.isEnabled = enabled
    }

    private fun showLoading(text: String) {
        loadingText?.text = text
        loadingOverlay?.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay?.visibility = View.GONE
    }

    private suspend fun loadActiveSelection(): ActiveSelection {
        val runtimeCore = RuntimeConfigStore.readCore()

        val mode = (runtimeCore["preset_mode"]
            ?: "categories")
            .ifEmpty { "categories" }
            .lowercase()

        val presetFile = (runtimeCore["preset_file"]
            ?: "")
            .trim()

        val cmdlineFile = (runtimeCore["custom_cmdline_file"]
            ?: "cmdline.txt")
            .trim()

        return ActiveSelection(
            mode = mode,
            presetFile = presetFile,
            cmdlineFile = cmdlineFile.ifEmpty { "cmdline.txt" }
        )
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var delimiter = "__ZAPRET_PRESET_EOF__"
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

    private suspend fun persistActiveSelection(mode: String, presetFile: String? = null): Boolean {
        return RuntimeConfigStore.setActiveModeValues(
            RuntimeConfigStore.CoreSettingsUpdate(
                presetMode = mode,
                presetFile = presetFile
            )
        )
    }

}
