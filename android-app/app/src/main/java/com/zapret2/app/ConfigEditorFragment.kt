package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
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

    private data class EditableFile(
        val displayName: String,
        val path: String
    )

    private lateinit var spinnerConfigFile: Spinner
    private lateinit var textConfigPath: TextView
    private lateinit var editConfigContent: EditText
    private lateinit var buttonReloadConfig: MaterialButton
    private lateinit var buttonSaveConfig: MaterialButton
    private lateinit var buttonSaveRestartConfig: MaterialButton

    private val restartScript = "/data/adb/modules/zapret2/zapret2/scripts/zapret-restart.sh"

    private val editableFiles = listOf(
        EditableFile("config.sh", "/data/adb/modules/zapret2/zapret2/config.sh"),
        EditableFile("categories.ini", "/data/adb/modules/zapret2/zapret2/categories.ini"),
        EditableFile("strategies-tcp.ini", "/data/adb/modules/zapret2/zapret2/strategies-tcp.ini"),
        EditableFile("strategies-udp.ini", "/data/adb/modules/zapret2/zapret2/strategies-udp.ini"),
        EditableFile("strategies-stun.ini", "/data/adb/modules/zapret2/zapret2/strategies-stun.ini")
    )

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
        setupFileSelector()
        setupButtons()
    }

    private fun initViews(view: View) {
        spinnerConfigFile = view.findViewById(R.id.spinnerConfigFile)
        textConfigPath = view.findViewById(R.id.textConfigPath)
        editConfigContent = view.findViewById(R.id.editConfigContent)
        buttonReloadConfig = view.findViewById(R.id.buttonReloadConfig)
        buttonSaveConfig = view.findViewById(R.id.buttonSaveConfig)
        buttonSaveRestartConfig = view.findViewById(R.id.buttonSaveRestartConfig)
    }

    private fun setupFileSelector() {
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.spinner_item,
            editableFiles.map { it.displayName }
        )
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerConfigFile.adapter = adapter

        spinnerConfigFile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFile = getSelectedFile() ?: return
                textConfigPath.text = selectedFile.path
                loadCurrentFile()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupButtons() {
        buttonReloadConfig.setOnClickListener {
            loadCurrentFile()
        }

        buttonSaveConfig.setOnClickListener {
            saveCurrentFile(restartAfterSave = false)
        }

        buttonSaveRestartConfig.setOnClickListener {
            saveCurrentFile(restartAfterSave = true)
        }
    }

    private fun loadCurrentFile() {
        val selectedFile = getSelectedFile() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val content = withContext(Dispatchers.IO) {
                val result = Shell.cmd("cat \"${selectedFile.path}\" 2>/dev/null").exec()
                if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    null
                }
            }

            if (!isAdded) return@launch

            if (content != null) {
                editConfigContent.setText(content)
            } else {
                Toast.makeText(requireContext(), "Failed to read ${selectedFile.displayName}", Toast.LENGTH_SHORT).show()
            }

            setActionsEnabled(true)
        }
    }

    private fun saveCurrentFile(restartAfterSave: Boolean) {
        val selectedFile = getSelectedFile() ?: return
        val text = editConfigContent.text?.toString().orEmpty().replace("\r\n", "\n")

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val (saved, restarted) = withContext(Dispatchers.IO) {
                val writeResult = Shell.cmd(buildSafeWriteCommand(selectedFile.path, text)).exec()
                if (!writeResult.isSuccess) {
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
                    Toast.makeText(requireContext(), "Failed to save ${selectedFile.displayName}", Toast.LENGTH_SHORT).show()
                }
                restartAfterSave && restarted -> {
                    Toast.makeText(requireContext(), "Saved and restarted", Toast.LENGTH_SHORT).show()
                    setFragmentResult(LogsFragment.SERVICE_RESTARTED_KEY, bundleOf())
                }
                restartAfterSave -> {
                    Toast.makeText(requireContext(), "Saved, restart failed", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(requireContext(), "Saved ${selectedFile.displayName}", Toast.LENGTH_SHORT).show()
                }
            }

            setActionsEnabled(true)
        }
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var delimiter = "__ZAPRET_EDITOR_EOF__"
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

    private fun getSelectedFile(): EditableFile? {
        val position = spinnerConfigFile.selectedItemPosition
        if (position !in editableFiles.indices) return null
        return editableFiles[position]
    }

    private fun setActionsEnabled(enabled: Boolean) {
        spinnerConfigFile.isEnabled = enabled
        buttonReloadConfig.isEnabled = enabled
        buttonSaveConfig.isEnabled = enabled
        buttonSaveRestartConfig.isEnabled = enabled
        editConfigContent.isEnabled = enabled
    }
}
