package com.zapret2.app

import android.content.Intent
import android.os.Bundle
import android.os.FileObserver
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostlistsFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: LinearLayout? = null
    private var textTotalDomains: TextView? = null
    private var textTotalFiles: TextView? = null

    private val hostlistConfigs = mutableListOf<HostlistConfig>()
    private var adapter: HostlistsAdapter? = null
    private var strategies: List<StrategyRepository.StrategyInfo> = emptyList()

    private var fileObserver: FileObserver? = null

    // Flag to track if view is active
    private var isViewActive = false

    // Path to hostlist files
    private val LISTS_DIR = "/data/adb/modules/zapret2/zapret2/lists"
    private val CONFIG_FILE = "/data/local/tmp/zapret2-hostlists.conf"

    // Default strategy name (first working strategy)
    private val DEFAULT_STRATEGY = "syndata_multisplit_tls_google_700"

    /**
     * Hostlist configuration with file info and selected strategy
     * Now uses strategy NAME instead of index
     */
    data class HostlistConfig(
        val filename: String,
        val path: String,
        val domainCount: Int,
        val sizeBytes: Long,
        var strategyName: String  // "disabled" or actual strategy name like "syndata_multisplit_tls_google_700"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_hostlists, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isViewActive = true
        initViews(view)
        setupRecyclerView()
        setupSwipeRefresh()
        loadData()
        setupFileObserver()
    }

    override fun onDestroyView() {
        isViewActive = false
        fileObserver?.stopWatching()
        fileObserver = null
        // Clear view references to prevent memory leaks
        recyclerView?.adapter = null
        recyclerView = null
        swipeRefresh = null
        progressBar = null
        emptyView = null
        textTotalDomains = null
        textTotalFiles = null
        adapter = null
        super.onDestroyView()
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.recyclerHostlists)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        progressBar = view.findViewById(R.id.progressBar)
        emptyView = view.findViewById(R.id.emptyView)
        textTotalDomains = view.findViewById(R.id.textTotalDomains)
        textTotalFiles = view.findViewById(R.id.textTotalFiles)
    }

    private fun setupRecyclerView() {
        adapter = HostlistsAdapter(
            hostlistConfigs,
            strategies,
            onViewClick = { config -> openHostlistContent(config) },
            onStrategyClick = { config, position -> showStrategyPicker(config, position) }
        )
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        swipeRefresh?.setColorSchemeColors(
            resources.getColor(R.color.accent_blue, null),
            resources.getColor(R.color.accent_light_blue, null)
        )
        swipeRefresh?.setProgressBackgroundColorSchemeColor(
            resources.getColor(R.color.surface, null)
        )
        swipeRefresh?.setOnRefreshListener {
            loadData()
        }
    }

    private fun setupFileObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dirExists = withContext(Dispatchers.IO) {
                try {
                    Shell.cmd("[ -d \"$LISTS_DIR\" ] && echo 'exists'").exec().out.firstOrNull() == "exists"
                } catch (e: Exception) {
                    false
                }
            }

            if (dirExists && isViewActive) {
                try {
                    @Suppress("DEPRECATION")
                    fileObserver = object : FileObserver(LISTS_DIR, CREATE or DELETE or MODIFY or MOVED_FROM or MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path?.endsWith(".txt") == true && isViewActive) {
                                // Use view?.post for safer UI updates
                                view?.post {
                                    if (isViewActive && isAdded && !isDetached) {
                                        loadData()
                                    }
                                }
                            }
                        }
                    }
                    fileObserver?.startWatching()
                } catch (e: Exception) {
                    // FileObserver may fail on certain paths or Android versions
                    // Silently fail - manual refresh is still available
                }
            }
        }
    }

    /**
     * Load strategies and hostlist configurations
     */
    private fun loadData() {
        // Safety check - don't proceed if view is not active
        if (!isViewActive || !isAdded) return

        viewLifecycleOwner.lifecycleScope.launch {
            // Double-check after potential suspension
            if (!isViewActive) return@launch

            if (swipeRefresh?.isRefreshing != true) {
                progressBar?.visibility = View.VISIBLE
            }
            emptyView?.visibility = View.GONE

            // Load strategies first
            strategies = withContext(Dispatchers.IO) {
                try {
                    StrategyRepository.getTcpStrategies()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // Update adapter with strategies
            adapter?.updateStrategies(strategies)

            // Load saved config (now returns Map<String, String> - filename to strategyName)
            val savedConfig = withContext(Dispatchers.IO) {
                loadSavedConfig()
            }

            // Load hostlist files
            val files = withContext(Dispatchers.IO) {
                try {
                    loadHostlistFiles(savedConfig)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // Check again after IO operation
            if (!isViewActive) return@launch

            hostlistConfigs.clear()
            hostlistConfigs.addAll(files)
            adapter?.notifyDataSetChanged()

            // Update statistics
            val totalDomains = hostlistConfigs.sumOf { it.domainCount }
            val totalFiles = hostlistConfigs.size

            textTotalDomains?.text = formatNumber(totalDomains)
            textTotalFiles?.text = totalFiles.toString()

            progressBar?.visibility = View.GONE
            swipeRefresh?.isRefreshing = false

            if (hostlistConfigs.isEmpty()) {
                emptyView?.visibility = View.VISIBLE
                recyclerView?.visibility = View.GONE
            } else {
                emptyView?.visibility = View.GONE
                recyclerView?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Load saved hostlist configuration from file
     * Returns Map<filename, strategyName> where strategyName is "disabled" or actual strategy ID
     */
    private fun loadSavedConfig(): Map<String, String> {
        val configMap = mutableMapOf<String, String>()

        try {
            val result = Shell.cmd("cat $CONFIG_FILE 2>/dev/null").exec()
            if (result.isSuccess) {
                result.out.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            val filename = parts[0].trim()
                            val strategyName = parts[1].trim()
                            // Store strategy name directly (no conversion needed)
                            configMap[filename] = strategyName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Config file doesn't exist yet, use defaults
        }

        return configMap
    }

    /**
     * Save hostlist configuration to file
     * Format: HOSTLIST_FILE=STRATEGY_NAME
     */
    private fun saveConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val content = buildString {
                        appendLine("# Zapret2 Hostlists Configuration")
                        appendLine("# Format: HOSTLIST_FILE=STRATEGY_NAME")
                        appendLine("# Use 'disabled' to disable a hostlist")
                        appendLine()
                        hostlistConfigs.forEach { config ->
                            appendLine("${config.filename}=${config.strategyName}")
                        }
                    }

                    // Use heredoc for safe multiline write
                    Shell.cmd("cat > $CONFIG_FILE << 'EOFCONFIG'\n$content\nEOFCONFIG").exec()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save config", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Load hostlist files from the lists directory
     * Uses strategy names from saved config, defaults to DEFAULT_STRATEGY
     */
    private fun loadHostlistFiles(savedConfig: Map<String, String>): List<HostlistConfig> {
        val result = Shell.cmd("ls -la \"$LISTS_DIR\"/*.txt 2>/dev/null").exec()
        if (!result.isSuccess) {
            return emptyList()
        }

        val files = mutableListOf<HostlistConfig>()

        // Get list of .txt files
        val listResult = Shell.cmd("ls \"$LISTS_DIR\"/*.txt 2>/dev/null").exec()
        if (!listResult.isSuccess) {
            return emptyList()
        }

        for (filePath in listResult.out) {
            if (filePath.isBlank()) continue

            val fileName = filePath.substringAfterLast("/")

            // Get domain count (line count)
            val wcResult = Shell.cmd("wc -l < \"$filePath\" 2>/dev/null").exec()
            val domainCount = wcResult.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            // Get file size
            val statResult = Shell.cmd("stat -c %s \"$filePath\" 2>/dev/null").exec()
            val sizeBytes = statResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L

            // Get saved strategy name or default
            val strategyName = savedConfig[fileName] ?: DEFAULT_STRATEGY

            files.add(HostlistConfig(
                filename = fileName,
                path = filePath,
                domainCount = domainCount,
                sizeBytes = sizeBytes,
                strategyName = strategyName
            ))
        }

        // Sort by domain count descending
        return files.sortedByDescending { it.domainCount }
    }

    private fun openHostlistContent(config: HostlistConfig) {
        // Safety check to avoid crash when fragment is detached
        val ctx = context ?: return
        if (!isAdded || isDetached) return

        val intent = Intent(ctx, HostlistContentActivity::class.java).apply {
            putExtra(HostlistContentActivity.EXTRA_FILE_PATH, config.path)
            putExtra(HostlistContentActivity.EXTRA_FILE_NAME, config.filename)
            putExtra(HostlistContentActivity.EXTRA_DOMAIN_COUNT, config.domainCount)
        }
        startActivity(intent)
    }

    /**
     * Show strategy picker bottom sheet for a hostlist
     * Now passes strategy NAME instead of index
     */
    private fun showStrategyPicker(config: HostlistConfig, position: Int) {
        if (!isAdded || isDetached) return

        // Get icon based on hostlist name
        val iconRes = getHostlistIcon(config.filename)
        val displayName = config.filename.removeSuffix(".txt")

        val bottomSheet = StrategyPickerBottomSheet.newInstance(
            categoryKey = config.filename,
            categoryName = displayName,
            protocol = "TCP 443",
            iconRes = iconRes,
            currentStrategyName = config.strategyName,  // Pass name, not index!
            strategyType = StrategyPickerBottomSheet.TYPE_TCP
        )

        bottomSheet.setOnStrategySelectedListener { selectedName ->
            // Update config with strategy NAME
            config.strategyName = selectedName

            // Update UI
            adapter?.notifyItemChanged(position)

            // Save config
            saveConfig()

            // Show toast with strategy name
            val toastText = if (selectedName == "disabled") {
                "$displayName: Disabled"
            } else {
                "$displayName: ${formatStrategyNameForDisplay(selectedName)}"
            }
            Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
        }

        bottomSheet.show(parentFragmentManager, "strategy_picker")
    }

    /**
     * Format strategy name for display in toast
     */
    private fun formatStrategyNameForDisplay(name: String): String {
        val formatted = name.replace("_", " ")
        return if (formatted.length > 30) formatted.take(27) + "..." else formatted
    }

    /**
     * Get icon resource based on hostlist filename
     */
    private fun getHostlistIcon(filename: String): Int {
        val name = filename.lowercase().removeSuffix(".txt")
        return when {
            name.contains("youtube") -> R.drawable.ic_video
            name.contains("discord") -> R.drawable.ic_message
            name.contains("telegram") -> R.drawable.ic_message
            name.contains("whatsapp") -> R.drawable.ic_message
            name.contains("facebook") -> R.drawable.ic_social
            name.contains("instagram") -> R.drawable.ic_social
            name.contains("twitter") -> R.drawable.ic_social
            name.contains("tiktok") -> R.drawable.ic_video
            name.contains("twitch") -> R.drawable.ic_video
            name.contains("spotify") -> R.drawable.ic_apps
            name.contains("soundcloud") -> R.drawable.ic_apps
            name.contains("steam") -> R.drawable.ic_apps
            name.contains("google") -> R.drawable.ic_apps
            else -> R.drawable.ic_hostlist
        }
    }

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000 -> String.format("%.1fK", number / 1_000.0)
            else -> number.toString()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }
}
