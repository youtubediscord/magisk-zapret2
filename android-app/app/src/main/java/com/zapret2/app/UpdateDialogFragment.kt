package com.zapret2.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dialog fragment for displaying update information and handling
 * APK and Magisk module updates.
 *
 * Usage:
 * ```
 * val dialog = UpdateDialogFragment.newInstance(release, currentVersion)
 * dialog.show(supportFragmentManager, "update_dialog")
 * ```
 */
class UpdateDialogFragment : DialogFragment() {

    // UI Elements
    private lateinit var textTitle: TextView
    private lateinit var textCurrentVersion: TextView
    private lateinit var textNewVersion: TextView
    private lateinit var textChangelog: TextView
    private lateinit var checkUpdateApk: MaterialCheckBox
    private lateinit var checkUpdateModule: MaterialCheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var textProgress: TextView
    private lateinit var buttonLater: MaterialButton
    private lateinit var buttonUpdate: MaterialButton

    // Data
    private var releaseVersion: String = ""
    private var releaseApkUrl: String? = null
    private var releaseModuleUrl: String? = null
    private var releaseChangelog: String = ""
    private var currentVersion: String = ""

    // State
    private var isDownloading = false

    companion object {
        private const val ARG_VERSION = "version"
        private const val ARG_APK_URL = "apk_url"
        private const val ARG_MODULE_URL = "module_url"
        private const val ARG_CHANGELOG = "changelog"
        private const val ARG_CURRENT_VERSION = "current_version"

        /**
         * Creates a new instance of UpdateDialogFragment with release data.
         *
         * @param release The Release object from UpdateManager
         * @param currentVersion The current app version string
         * @return New instance of UpdateDialogFragment
         */
        fun newInstance(release: UpdateManager.Release, currentVersion: String): UpdateDialogFragment {
            return UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_VERSION, release.version)
                    putString(ARG_APK_URL, release.apkUrl)
                    putString(ARG_MODULE_URL, release.moduleUrl)
                    putString(ARG_CHANGELOG, release.changelog)
                    putString(ARG_CURRENT_VERSION, currentVersion)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            releaseVersion = it.getString(ARG_VERSION, "")
            releaseApkUrl = it.getString(ARG_APK_URL)
            releaseModuleUrl = it.getString(ARG_MODULE_URL)
            releaseChangelog = it.getString(ARG_CHANGELOG, "")
            currentVersion = it.getString(ARG_CURRENT_VERSION, "")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupData()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        // Set dialog width to 90% of screen width
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun initViews(view: View) {
        textTitle = view.findViewById(R.id.textTitle)
        textCurrentVersion = view.findViewById(R.id.textCurrentVersion)
        textNewVersion = view.findViewById(R.id.textNewVersion)
        textChangelog = view.findViewById(R.id.textChangelog)
        checkUpdateApk = view.findViewById(R.id.checkUpdateApk)
        checkUpdateModule = view.findViewById(R.id.checkUpdateModule)
        progressBar = view.findViewById(R.id.progressBar)
        textProgress = view.findViewById(R.id.textProgress)
        buttonLater = view.findViewById(R.id.buttonLater)
        buttonUpdate = view.findViewById(R.id.buttonUpdate)
    }

    private fun setupData() {
        textCurrentVersion.text = currentVersion
        textNewVersion.text = releaseVersion
        textChangelog.text = releaseChangelog.ifEmpty { "Нет информации об изменениях" }

        // Disable checkboxes if URLs are not available
        if (releaseApkUrl.isNullOrEmpty()) {
            checkUpdateApk.isEnabled = false
            checkUpdateApk.isChecked = false
            checkUpdateApk.text = "Обновить APK (недоступно)"
        }

        if (releaseModuleUrl.isNullOrEmpty()) {
            checkUpdateModule.isEnabled = false
            checkUpdateModule.isChecked = false
            checkUpdateModule.text = "Обновить модуль Magisk (недоступно)"
        }

        // Update button state based on available options
        updateButtonState()
    }

    private fun setupListeners() {
        checkUpdateApk.setOnCheckedChangeListener { _, _ ->
            updateButtonState()
        }

        checkUpdateModule.setOnCheckedChangeListener { _, _ ->
            updateButtonState()
        }

        buttonLater.setOnClickListener {
            if (!isDownloading) {
                dismiss()
            }
        }

        buttonUpdate.setOnClickListener {
            if (!isDownloading) {
                startUpdate()
            }
        }
    }

    private fun updateButtonState() {
        val anySelected = checkUpdateApk.isChecked || checkUpdateModule.isChecked
        buttonUpdate.isEnabled = anySelected && !isDownloading
        buttonUpdate.alpha = if (anySelected) 1.0f else 0.5f
    }

    private fun startUpdate() {
        val updateApk = checkUpdateApk.isChecked && !releaseApkUrl.isNullOrEmpty()
        val updateModule = checkUpdateModule.isChecked && !releaseModuleUrl.isNullOrEmpty()

        if (!updateApk && !updateModule) {
            context?.let {
                Toast.makeText(it, "Выберите что обновить", Toast.LENGTH_SHORT).show()
            }
            return
        }

        setDownloadingState(true)

        lifecycleScope.launch {
            val ctx = context ?: return@launch
            val updateManager = UpdateManager(ctx)
            var apkFile: File? = null
            var moduleFile: File? = null
            var hasError = false

            // Download APK if selected
            if (updateApk && releaseApkUrl != null) {
                updateProgressText("Скачивание APK...")

                val result = updateManager.downloadFile(
                    url = releaseApkUrl!!,
                    fileName = "zapret2-update.apk",
                    progress = { percent ->
                        updateProgress(percent, "Скачивание APK... $percent%")
                    }
                )

                when (result) {
                    is UpdateManager.DownloadResult.Success -> {
                        apkFile = result.file
                    }
                    is UpdateManager.DownloadResult.Error -> {
                        withContext(Dispatchers.Main) {
                            context?.let {
                                Toast.makeText(
                                    it,
                                    "Ошибка загрузки APK: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        hasError = true
                    }
                }
            }

            // Download Module if selected and no error occurred
            if (updateModule && releaseModuleUrl != null && !hasError) {
                updateProgressText("Скачивание модуля...")

                val result = updateManager.downloadFile(
                    url = releaseModuleUrl!!,
                    fileName = "zapret2-module-update.zip",
                    progress = { percent ->
                        updateProgress(percent, "Скачивание модуля... $percent%")
                    }
                )

                when (result) {
                    is UpdateManager.DownloadResult.Success -> {
                        moduleFile = result.file
                    }
                    is UpdateManager.DownloadResult.Error -> {
                        withContext(Dispatchers.Main) {
                            context?.let {
                                Toast.makeText(
                                    it,
                                    "Ошибка загрузки модуля: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        hasError = true
                    }
                }
            }

            // Install downloaded files
            withContext(Dispatchers.Main) {
                if (hasError || !isAdded) {
                    setDownloadingState(false)
                    return@withContext
                }

                val toastContext = context ?: return@withContext

                // Install Magisk module first (if downloaded)
                moduleFile?.let { file ->
                    updateProgressText("Установка модуля...")

                    val (success, needsReboot) = updateManager.installModule(file)
                    if (success) {
                        val message = if (needsReboot) {
                            "Модуль установлен. Требуется перезагрузка."
                        } else {
                            "Модуль обновлён! Перезагрузка не требуется."
                        }
                        Toast.makeText(toastContext, message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            toastContext,
                            "Ошибка установки модуля",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Install APK (if downloaded) - this will open system installer
                apkFile?.let { file ->
                    updateProgressText("Запуск установщика APK...")
                    updateManager.installApk(file)
                }

                // If only module was updated, dismiss dialog
                if (apkFile == null && moduleFile != null) {
                    setDownloadingState(false)
                    dismiss()
                }
            }
        }
    }

    private fun setDownloadingState(downloading: Boolean) {
        isDownloading = downloading

        buttonLater.isEnabled = !downloading
        buttonUpdate.isEnabled = !downloading
        checkUpdateApk.isEnabled = !downloading && !releaseApkUrl.isNullOrEmpty()
        checkUpdateModule.isEnabled = !downloading && !releaseModuleUrl.isNullOrEmpty()

        progressBar.visibility = if (downloading) View.VISIBLE else View.INVISIBLE
        textProgress.visibility = if (downloading) View.VISIBLE else View.INVISIBLE

        if (downloading) {
            progressBar.progress = 0
            buttonUpdate.text = "Загрузка..."
        } else {
            buttonUpdate.text = "Обновить"
        }

        // Prevent dialog dismissal during download
        isCancelable = !downloading
    }

    private fun updateProgress(percent: Int, text: String) {
        if (!isAdded) return
        progressBar.progress = percent
        textProgress.text = text
    }

    private suspend fun updateProgressText(text: String) {
        withContext(Dispatchers.Main) {
            if (isAdded) {
                textProgress.text = text
            }
        }
    }
}
