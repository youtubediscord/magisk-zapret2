package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostsEditorFragment : Fragment() {

    private lateinit var textHostsFilePath: TextView
    private lateinit var editHostsContent: EditText
    private lateinit var buttonReloadHosts: MaterialButton
    private lateinit var buttonSaveHosts: MaterialButton

    private val hostsFile = "/system/etc/hosts"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_hosts_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupButtons()
        loadHostsFile()
    }

    private fun initViews(view: View) {
        textHostsFilePath = view.findViewById(R.id.textHostsFilePath)
        editHostsContent = view.findViewById(R.id.editHostsContent)
        buttonReloadHosts = view.findViewById(R.id.buttonReloadHosts)
        buttonSaveHosts = view.findViewById(R.id.buttonSaveHosts)
        textHostsFilePath.text = hostsFile
    }

    private fun setupButtons() {
        buttonReloadHosts.setOnClickListener {
            loadHostsFile()
        }
        buttonSaveHosts.setOnClickListener {
            saveHostsFile()
        }
    }

    private fun loadHostsFile() {
        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val content = withContext(Dispatchers.IO) {
                val result = Shell.cmd("cat \"$hostsFile\" 2>/dev/null").exec()
                if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    null
                }
            }

            if (!isAdded) return@launch

            if (content != null) {
                editHostsContent.setText(content)
            } else {
                Toast.makeText(requireContext(), "Failed to read hosts file", Toast.LENGTH_SHORT).show()
            }

            setActionsEnabled(true)
        }
    }

    private fun saveHostsFile() {
        val content = editHostsContent.text?.toString().orEmpty()
            .replace("\r\n", "\n").replace('\r', '\n')
            .trimEnd('\n') + "\n"

        if (content.isBlank()) {
            Toast.makeText(requireContext(), "Hosts file is empty", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)

            val saved = withContext(Dispatchers.IO) {
                val writeResult = Shell.cmd(buildSafeWriteCommand(hostsFile, content)).exec()
                writeResult.isSuccess
            }

            if (!isAdded) return@launch

            if (saved) {
                Toast.makeText(requireContext(), "Hosts file saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to save hosts file", Toast.LENGTH_SHORT).show()
            }

            setActionsEnabled(true)
        }
    }

    private fun buildSafeWriteCommand(path: String, content: String): String {
        var delimiter = "__ZAPRET_HOSTS_EOF__"
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
        editHostsContent.isEnabled = enabled
        buttonReloadHosts.isEnabled = enabled
        buttonSaveHosts.isEnabled = enabled
    }
}
