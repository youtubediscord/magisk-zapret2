package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostsEditorFragment : Fragment() {

    private var textHostsFilePath: TextView? = null
    private var editHostsContent: EditText? = null
    private var buttonReloadHosts: MaterialButton? = null
    private var buttonSaveHosts: MaterialButton? = null
    private var progressBar: ProgressBar? = null

    private val hostsFile = "/system/etc/hosts"

    companion object {
        // Cache content across fragment recreations
        private var cachedContent: String? = null
    }

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

        // If we have cached content, show it immediately without shell call
        val cached = cachedContent
        if (cached != null) {
            editHostsContent?.setText(cached)
        } else {
            loadHostsFile()
        }
    }

    override fun onDestroyView() {
        // Save current editor content to cache before view is destroyed
        editHostsContent?.text?.toString()?.let { text ->
            if (text.isNotBlank()) {
                cachedContent = text
            }
        }
        textHostsFilePath = null
        editHostsContent = null
        buttonReloadHosts = null
        buttonSaveHosts = null
        progressBar = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        textHostsFilePath = view.findViewById(R.id.textHostsFilePath)
        editHostsContent = view.findViewById(R.id.editHostsContent)
        buttonReloadHosts = view.findViewById(R.id.buttonReloadHosts)
        buttonSaveHosts = view.findViewById(R.id.buttonSaveHosts)
        progressBar = view.findViewById(R.id.progressBarHosts)
        textHostsFilePath?.text = hostsFile
    }

    private fun setupButtons() {
        buttonReloadHosts?.setOnClickListener {
            cachedContent = null // Force reload from disk
            loadHostsFile()
        }
        buttonSaveHosts?.setOnClickListener {
            saveHostsFile()
        }
    }

    private fun loadHostsFile() {
        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)
            progressBar?.visibility = View.VISIBLE

            val content = withContext(Dispatchers.IO) {
                val result = Shell.cmd("cat \"$hostsFile\" 2>/dev/null").exec()
                if (result.isSuccess) {
                    result.out.joinToString("\n")
                } else {
                    null
                }
            }

            if (!isAdded) return@launch

            progressBar?.visibility = View.GONE

            if (content != null) {
                cachedContent = content
                editHostsContent?.setText(content)
            } else {
                view?.let { Snackbar.make(it, "Failed to read hosts file", Snackbar.LENGTH_SHORT).show() }
            }

            setActionsEnabled(true)
        }
    }

    private fun saveHostsFile() {
        val content = editHostsContent?.text?.toString().orEmpty()
            .replace("\r\n", "\n").replace('\r', '\n')
            .trimEnd('\n') + "\n"

        if (content.isBlank()) {
            view?.let { Snackbar.make(it, "Hosts file is empty", Snackbar.LENGTH_SHORT).show() }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setActionsEnabled(false)
            progressBar?.visibility = View.VISIBLE

            val saved = withContext(Dispatchers.IO) {
                val writeResult = Shell.cmd(buildSafeWriteCommand(hostsFile, content)).exec()
                writeResult.isSuccess
            }

            if (!isAdded) return@launch

            progressBar?.visibility = View.GONE

            if (saved) {
                cachedContent = content
                view?.let { Snackbar.make(it, "Hosts file saved", Snackbar.LENGTH_SHORT).show() }
            } else {
                view?.let { Snackbar.make(it, "Failed to save hosts file", Snackbar.LENGTH_SHORT).show() }
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
        editHostsContent?.isEnabled = enabled
        buttonReloadHosts?.isEnabled = enabled
        buttonSaveHosts?.isEnabled = enabled
    }
}
