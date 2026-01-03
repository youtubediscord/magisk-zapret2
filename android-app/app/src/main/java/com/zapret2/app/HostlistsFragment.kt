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

    private val hostlistFiles = mutableListOf<HostlistFile>()
    private var adapter: HostlistAdapter? = null

    private var fileObserver: FileObserver? = null

    // Flag to track if view is active
    private var isViewActive = false

    // Path to hostlist files
    private val LISTS_DIR = "/data/adb/modules/zapret2/zapret2/lists"

    data class HostlistFile(
        val name: String,
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
        loadHostlists()
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
        adapter = HostlistAdapter(hostlistFiles) { file ->
            openHostlistContent(file)
        }
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
            loadHostlists()
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
                                        loadHostlists()
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

    private fun loadHostlists() {
        // Safety check - don't proceed if view is not active
        if (!isViewActive || !isAdded) return

        viewLifecycleOwner.lifecycleScope.launch {
            // Double-check after potential suspension
            if (!isViewActive) return@launch

            if (swipeRefresh?.isRefreshing != true) {
                progressBar?.visibility = View.VISIBLE
            }
            emptyView?.visibility = View.GONE

            val files = withContext(Dispatchers.IO) {
                try {
                    loadHostlistFiles()
                } catch (e: Exception) {
                    emptyList()
                }
            }

            // Check again after IO operation
            if (!isViewActive) return@launch

            hostlistFiles.clear()
            hostlistFiles.addAll(files)
            adapter?.notifyDataSetChanged()

            // Update statistics
            val totalDomains = hostlistFiles.sumOf { it.domainCount }
            val totalFiles = hostlistFiles.size

            textTotalDomains?.text = formatNumber(totalDomains)
            textTotalFiles?.text = totalFiles.toString()

            progressBar?.visibility = View.GONE
            swipeRefresh?.isRefreshing = false

            if (hostlistFiles.isEmpty()) {
                emptyView?.visibility = View.VISIBLE
                recyclerView?.visibility = View.GONE
            } else {
                emptyView?.visibility = View.GONE
                recyclerView?.visibility = View.VISIBLE
            }
        }
    }

    private fun loadHostlistFiles(): List<HostlistFile> {
        val result = Shell.cmd("ls -la \"$LISTS_DIR\"/*.txt 2>/dev/null").exec()
        if (!result.isSuccess) {
            return emptyList()
        }

        val files = mutableListOf<HostlistFile>()

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

            files.add(HostlistFile(
                name = fileName,
                path = filePath,
                domainCount = domainCount,
                sizeBytes = sizeBytes
            ))
        }

        // Sort by domain count descending
        return files.sortedByDescending { it.domainCount }
    }

    private fun openHostlistContent(file: HostlistFile) {
        // Safety check to avoid crash when fragment is detached
        val ctx = context ?: return
        if (!isAdded || isDetached) return

        val intent = Intent(ctx, HostlistContentActivity::class.java).apply {
            putExtra(HostlistContentActivity.EXTRA_FILE_PATH, file.path)
            putExtra(HostlistContentActivity.EXTRA_FILE_NAME, file.name)
            putExtra(HostlistContentActivity.EXTRA_DOMAIN_COUNT, file.domainCount)
        }
        startActivity(intent)
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

    // RecyclerView Adapter
    inner class HostlistAdapter(
        private val items: List<HostlistFile>,
        private val onClick: (HostlistFile) -> Unit
    ) : RecyclerView.Adapter<HostlistAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textFileName: TextView = view.findViewById(R.id.textFileName)
            val textDomainCount: TextView = view.findViewById(R.id.textDomainCount)
            val textFileSize: TextView = view.findViewById(R.id.textFileSize)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hostlist_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = items[position]

            // Remove .txt extension for display
            holder.textFileName.text = file.name.removeSuffix(".txt")
            holder.textDomainCount.text = "${formatNumber(file.domainCount)} domains"
            holder.textFileSize.text = formatFileSize(file.sizeBytes)

            holder.itemView.setOnClickListener {
                onClick(file)
            }
        }

        override fun getItemCount() = items.size
    }
}
