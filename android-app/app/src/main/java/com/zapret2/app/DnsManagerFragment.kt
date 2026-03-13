package com.zapret2.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsManagerFragment : Fragment() {

    private var hostsData: HostsIniData? = null
    private var selectedPresetIndex = 0
    private val selectedDnsServices = mutableSetOf<String>()
    private val selectedDirectServices = mutableSetOf<String>()

    // View references
    private lateinit var rowDnsPreset: View
    private lateinit var textDnsPresetValue: TextView
    private lateinit var checkSelectAllDns: CheckBox
    private lateinit var dnsServicesContainer: LinearLayout
    private lateinit var checkSelectAllDirect: CheckBox
    private lateinit var directServicesContainer: LinearLayout
    private lateinit var buttonReset: MaterialButton
    private lateinit var buttonApply: MaterialButton
    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView

    // Maps to track checkbox state
    private val dnsCheckboxes = mutableMapOf<String, CheckBox>()
    private val directCheckboxes = mutableMapOf<String, CheckBox>()

    // Guard flag to prevent checkbox listener loops
    private var isUpdatingSelectAll = false

    private val hostsReadPath = "/system/etc/hosts"
    private val hostsWritePath = "/data/adb/modules/zapret2/system/etc/hosts"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_dns_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupClickListeners()
        loadData()
    }

    private fun initViews(view: View) {
        rowDnsPreset = view.findViewById(R.id.rowDnsPreset)
        textDnsPresetValue = view.findViewById(R.id.textDnsPresetValue)
        checkSelectAllDns = view.findViewById(R.id.checkSelectAllDns)
        dnsServicesContainer = view.findViewById(R.id.dnsServicesContainer)
        checkSelectAllDirect = view.findViewById(R.id.checkSelectAllDirect)
        directServicesContainer = view.findViewById(R.id.directServicesContainer)
        buttonReset = view.findViewById(R.id.buttonReset)
        buttonApply = view.findViewById(R.id.buttonApply)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        loadingText = view.findViewById(R.id.loadingText)
    }

    private fun setupClickListeners() {
        rowDnsPreset.setOnClickListener {
            showPresetPicker()
        }

        checkSelectAllDns.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSelectAll) return@setOnCheckedChangeListener
            isUpdatingSelectAll = true
            for ((serviceName, checkbox) in dnsCheckboxes) {
                checkbox.isChecked = isChecked
                if (isChecked) {
                    selectedDnsServices.add(serviceName)
                } else {
                    selectedDnsServices.remove(serviceName)
                }
            }
            isUpdatingSelectAll = false
        }

        checkSelectAllDirect.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSelectAll) return@setOnCheckedChangeListener
            isUpdatingSelectAll = true
            for ((serviceName, checkbox) in directCheckboxes) {
                checkbox.isChecked = isChecked
                if (isChecked) {
                    selectedDirectServices.add(serviceName)
                } else {
                    selectedDirectServices.remove(serviceName)
                }
            }
            isUpdatingSelectAll = false
        }

        buttonApply.setOnClickListener {
            applyDns()
        }

        buttonReset.setOnClickListener {
            resetDns()
        }
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Loading hosts.ini...")

            val parseResult = withContext(Dispatchers.IO) {
                HostsIniParser.parse()
            }

            if (!isAdded || view == null) return@launch

            if (parseResult.data == null) {
                // Show error with retry - repurpose loading overlay
                loadingText.text = "Error: ${parseResult.error ?: "Unknown error"}\n\nTap to retry"
                loadingOverlay.visibility = View.VISIBLE
                loadingOverlay.setOnClickListener {
                    loadingOverlay.setOnClickListener(null)
                    loadData()
                }
                return@launch
            }

            hideLoading()

            val data = parseResult.data
            hostsData = data

            // Restore saved state from runtime.ini [dns_manager] section
            val savedState = withContext(Dispatchers.IO) {
                RuntimeConfigStore.readDnsManager()
            }

            if (!isAdded || view == null) return@launch

            selectedPresetIndex = savedState["dns_preset_index"]?.toIntOrNull() ?: 0
            savedState["selected_dns"]
                ?.split("|")
                ?.filter { it.isNotEmpty() }
                ?.let { selectedDnsServices.addAll(it) }
            savedState["selected_direct"]
                ?.split("|")
                ?.filter { it.isNotEmpty() }
                ?.let { selectedDirectServices.addAll(it) }

            updatePresetDisplay()
            buildServiceRows(data)
        }
    }

    private fun updatePresetDisplay() {
        val presets = hostsData?.dnsPresets ?: return
        textDnsPresetValue.text = presets.getOrElse(selectedPresetIndex) {
            presets.firstOrNull() ?: ""
        }
    }

    private fun buildServiceRows(data: HostsIniData) {
        dnsServicesContainer.removeAllViews()
        dnsCheckboxes.clear()

        for (service in data.dnsServices) {
            val row = layoutInflater.inflate(R.layout.item_dns_service, dnsServicesContainer, false)
            val check = row.findViewById<CheckBox>(R.id.checkService)
            val name = row.findViewById<TextView>(R.id.textServiceName)
            val info = row.findViewById<TextView>(R.id.textServiceInfo)

            name.text = service.name
            info.text = "${service.domains.size} domains"
            check.isChecked = service.name in selectedDnsServices

            check.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingSelectAll) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedDnsServices.add(service.name)
                } else {
                    selectedDnsServices.remove(service.name)
                }
                updateSelectAllState()
            }

            row.setOnClickListener { check.isChecked = !check.isChecked }

            dnsCheckboxes[service.name] = check
            dnsServicesContainer.addView(row)
        }

        directServicesContainer.removeAllViews()
        directCheckboxes.clear()

        for (service in data.directServices) {
            val row = layoutInflater.inflate(R.layout.item_dns_service, directServicesContainer, false)
            val check = row.findViewById<CheckBox>(R.id.checkService)
            val name = row.findViewById<TextView>(R.id.textServiceName)
            val info = row.findViewById<TextView>(R.id.textServiceInfo)

            name.text = service.name
            info.text = "${service.entries.size} entries"
            check.isChecked = service.name in selectedDirectServices

            check.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingSelectAll) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedDirectServices.add(service.name)
                } else {
                    selectedDirectServices.remove(service.name)
                }
                updateSelectAllState()
            }

            row.setOnClickListener { check.isChecked = !check.isChecked }

            directCheckboxes[service.name] = check
            directServicesContainer.addView(row)
        }

        updateSelectAllState()
    }

    private fun updateSelectAllState() {
        isUpdatingSelectAll = true
        checkSelectAllDns.isChecked = dnsCheckboxes.isNotEmpty() &&
            dnsCheckboxes.values.all { it.isChecked }
        checkSelectAllDirect.isChecked = directCheckboxes.isNotEmpty() &&
            directCheckboxes.values.all { it.isChecked }
        isUpdatingSelectAll = false
    }

    private fun showPresetPicker() {
        val presets = hostsData?.dnsPresets ?: return

        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("DNS Preset")
            .setSingleChoiceItems(presets.toTypedArray(), selectedPresetIndex) { dialog, which ->
                selectedPresetIndex = which
                updatePresetDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyDns() {
        val data = hostsData ?: return
        val ctx = context ?: return
        val cacheDir = ctx.cacheDir

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Applying DNS...")

            val success = withContext(Dispatchers.IO) {
                try {
                    // Read current hosts file
                    val currentResult = Shell.cmd("cat \"$hostsReadPath\" 2>/dev/null").exec()
                    val currentHosts = if (currentResult.isSuccess) {
                        currentResult.out.joinToString("\n")
                    } else {
                        ""
                    }

                    // Generate new block
                    val block = HostsIniParser.generateHostsBlock(
                        data,
                        selectedPresetIndex,
                        selectedDnsServices,
                        selectedDirectServices
                    )

                    // Merge
                    val merged = HostsIniParser.smartMerge(currentHosts, block)

                    // Write to Magisk overlay path (Magic Mount overlays onto /system/etc/hosts)
                    if (!writeHostsFile(hostsWritePath, merged, cacheDir)) return@withContext false

                    // Save state to runtime.ini
                    RuntimeConfigStore.upsertDnsManagerValues(
                        mapOf(
                            "dns_preset_index" to selectedPresetIndex.toString(),
                            "selected_dns" to selectedDnsServices.joinToString("|"),
                            "selected_direct" to selectedDirectServices.joinToString("|")
                        )
                    )

                    true
                } catch (e: Exception) {
                    false
                }
            }

            if (!isAdded || view == null) return@launch
            hideLoading()

            view?.let {
                Snackbar.make(
                    it,
                    if (success) "DNS applied" else "Failed to apply DNS",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun resetDns() {
        val ctx = context ?: return
        val cacheDir = ctx.cacheDir

        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Resetting DNS...")

            val success = withContext(Dispatchers.IO) {
                try {
                    val currentResult = Shell.cmd("cat \"$hostsReadPath\" 2>/dev/null").exec()
                    val currentHosts = if (currentResult.isSuccess) {
                        currentResult.out.joinToString("\n")
                    } else {
                        ""
                    }
                    val cleaned = HostsIniParser.removeZapretBlock(currentHosts)
                    writeHostsFile(hostsWritePath, cleaned, cacheDir)
                } catch (e: Exception) {
                    false
                }
            }

            if (!isAdded || view == null) return@launch
            hideLoading()

            view?.let {
                Snackbar.make(
                    it,
                    if (success) "DNS reset" else "Failed to reset DNS",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Write content to a file via root shell, handling large content safely.
     * Writes to app cache first (no root needed, no size limit), marks it
     * world-readable to bypass SELinux app_data_file restrictions, then copies
     * to the target path via root shell with atomic mv to avoid partial writes.
     *
     * For Magisk overlay paths, also attempts to apply immediately to
     * /system/etc/hosts via remount for instant effect without reboot.
     */
    private fun writeHostsFile(path: String, content: String, cacheDir: java.io.File): Boolean {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
            .trimEnd('\n') + "\n"

        // Ensure parent directory exists for the overlay path
        val parentDir = path.substringBeforeLast('/')
        Shell.cmd("mkdir -p \"$parentDir\"").exec()

        // Write to app cache first (Java I/O, no size limits)
        val tmpFile = java.io.File(cacheDir, "zapret2_hosts_${System.currentTimeMillis()}")
        try {
            tmpFile.writeText(normalized, Charsets.UTF_8)
        } catch (e: Exception) {
            tmpFile.delete()
            return false
        }

        // Make world-readable so root shell can access regardless of SELinux context
        tmpFile.setReadable(true, false)

        val src = tmpFile.absolutePath
        val destTmp = "$path.tmp"

        // Copy via root, then atomic mv
        val success = Shell.cmd("cp \"$src\" \"$destTmp\" && mv \"$destTmp\" \"$path\"").exec().isSuccess

        // Cleanup
        tmpFile.delete()
        if (!success) {
            Shell.cmd("rm -f \"$destTmp\"").exec()
            return false
        }

        // Try to apply immediately to /system/etc/hosts for instant effect
        // (may fail on read-only /system, that's OK -- reboot will pick up from overlay)
        if (path != "/system/etc/hosts") {
            Shell.cmd(
                "mount -o rw,remount / 2>/dev/null; " +
                "cp \"$path\" /system/etc/hosts 2>/dev/null; " +
                "mount -o ro,remount / 2>/dev/null"
            ).exec()
        }

        return true
    }

    private fun showLoading(text: String) {
        loadingText.text = text
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }
}
