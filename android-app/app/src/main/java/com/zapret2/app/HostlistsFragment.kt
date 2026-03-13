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
import androidx.core.content.ContextCompat
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

    private var fileObserver: FileObserver? = null

    // Flag to track if view is active
    private var isViewActive = false

    // Path to hostlist files
    private val LISTS_DIR = "/data/adb/modules/zapret2/zapret2/lists"

    /**
     * Hostlist configuration with file info (simplified - no strategy)
     */
    data class HostlistConfig(
        val filename: String,
        val path: String,
        val domainCount: Int,
        val sizeBytes: Long
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
            onItemClick = { config -> openHostlistContent(config) }
        )
        recyclerView?.layoutManager = LinearLayoutManager(context ?: return)
        recyclerView?.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        val ctx = context ?: return
        swipeRefresh?.setColorSchemeColors(
            ContextCompat.getColor(ctx, R.color.accent_blue),
            ContextCompat.getColor(ctx, R.color.accent_light_blue)
        )
        swipeRefresh?.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(ctx, R.color.surface)
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
     * Load hostlist files
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

            // Load hostlist files
            val files = withContext(Dispatchers.IO) {
                try {
                    loadHostlistFiles()
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
                recyclerView?.alpha = 0f
                recyclerView?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
        }
    }

    /**
     * Load hostlist files from the lists directory.
     * Uses a single shell command to collect all file info at once,
     * avoiding N+2 shell invocations (was: 2x ls + wc/stat per file).
     */
    private fun loadHostlistFiles(): List<HostlistConfig> {
        val cmd = """for f in "$LISTS_DIR"/*.txt; do [ -f "${'$'}f" ] && echo "${'$'}(basename "${'$'}f")|${'$'}f|${'$'}(wc -l < "${'$'}f")|${'$'}(stat -c %s "${'$'}f")"; done 2>/dev/null"""
        val result = Shell.cmd(cmd).exec()
        if (!result.isSuccess || result.out.isEmpty()) {
            return emptyList()
        }

        val files = mutableListOf<HostlistConfig>()
        for (line in result.out) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 4) continue

            files.add(HostlistConfig(
                filename = parts[0],
                path = parts[1],
                domainCount = parts[2].trim().toIntOrNull() ?: 0,
                sizeBytes = parts[3].trim().toLongOrNull() ?: 0L
            ))
        }

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
     * Get icon resource based on hostlist filename
     */
    fun getHostlistIcon(filename: String): Int {
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
}
