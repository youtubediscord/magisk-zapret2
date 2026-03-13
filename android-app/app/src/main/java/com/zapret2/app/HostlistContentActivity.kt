package com.zapret2.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostlistContentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DOMAIN_COUNT = "domain_count"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: LinearLayout
    private lateinit var textTitle: TextView
    private lateinit var textSubtitle: TextView
    private lateinit var textShowingCount: TextView
    private lateinit var editSearch: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnClearSearch: ImageButton

    private var filePath: String = ""
    private var fileName: String = ""

    // Stream-based: no allDomains list, only accumulated display items
    private val filteredDomains = mutableListOf<String>()
    private lateinit var adapter: DomainAdapter

    private var searchJob: Job? = null

    // Pagination
    private val PAGE_SIZE = 100
    private var currentPage = 0
    private var isLoading = false

    // Total line count from wc -l (no full file load)
    private var totalLineCount: Int = 0

    // Search state
    private var currentSearchQuery: String = ""
    private var searchResults: List<String>? = null // null = no search active
    private var searchTotalCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hostlist_content)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""

        if (filePath.isEmpty()) {
            Toast.makeText(this, "Invalid file path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupRecyclerView()
        setupSearch()
        loadDomains()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerDomains)
        progressBar = findViewById(R.id.progressBar)
        emptyView = findViewById(R.id.emptyView)
        textTitle = findViewById(R.id.textTitle)
        textSubtitle = findViewById(R.id.textSubtitle)
        textShowingCount = findViewById(R.id.textShowingCount)
        editSearch = findViewById(R.id.editSearch)
        btnBack = findViewById(R.id.btnBack)
        btnClearSearch = findViewById(R.id.btnClearSearch)

        // Set title
        textTitle.text = fileName.removeSuffix(".txt")
        textSubtitle.text = "Loading..."

        btnBack.setOnClickListener {
            finish()
        }

        btnClearSearch.setOnClickListener {
            editSearch.text.clear()
        }
    }

    private fun setupRecyclerView() {
        adapter = DomainAdapter(filteredDomains)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        recyclerView.isVerticalScrollBarEnabled = true
        recyclerView.isScrollbarFadingEnabled = false

        // Infinite scroll
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && (visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 10) {
                    loadMoreDomains()
                }
            }
        })
    }

    private fun setupSearch() {
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                // Debounce search
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300)
                    filterDomains(query)
                }
            }
        })
    }

    private fun loadDomains() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            emptyView.visibility = View.GONE

            // Only get line count, don't load content
            totalLineCount = withContext(Dispatchers.IO) {
                val result = Shell.cmd("wc -l < \"$filePath\" 2>/dev/null").exec()
                result.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            }

            textSubtitle.text = "$totalLineCount domains"

            currentPage = 0
            filteredDomains.clear()
            currentSearchQuery = ""
            searchResults = null
            loadMoreDomains()

            progressBar.visibility = View.GONE

            if (totalLineCount == 0) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadMoreDomains() {
        if (isLoading) return
        isLoading = true

        lifecycleScope.launch {
            val newItems = withContext(Dispatchers.IO) {
                if (currentSearchQuery.isNotEmpty()) {
                    loadSearchPage()
                } else {
                    loadPageFromFile()
                }
            }

            if (newItems.isNotEmpty()) {
                val insertStart = filteredDomains.size
                filteredDomains.addAll(newItems)
                adapter.notifyItemRangeInserted(insertStart, newItems.size)
                currentPage++
            }

            isLoading = false
            updateShowingCount()
        }
    }

    /**
     * Read a single page of lines from the file using sed.
     * Only reads PAGE_SIZE lines at a time -- no full file load.
     */
    private fun loadPageFromFile(): List<String> {
        val startLine = currentPage * PAGE_SIZE + 1
        val endLine = startLine + PAGE_SIZE - 1
        if (startLine > totalLineCount) return emptyList()

        val result = Shell.cmd("sed -n '${startLine},${endLine}p' \"$filePath\" 2>/dev/null").exec()
        if (!result.isSuccess) return emptyList()
        return result.out.filter { it.isNotBlank() }.map { it.trim() }
    }

    /**
     * Lazy-load search results via grep on first page request,
     * then paginate from the cached results list.
     */
    private fun loadSearchPage(): List<String> {
        // On first search page, run grep to get all matching lines
        if (searchResults == null) {
            val escapedQuery = currentSearchQuery.replace("'", "'\\''")
            val result = Shell.cmd("grep -i '$escapedQuery' \"$filePath\" 2>/dev/null").exec()
            searchResults = if (result.isSuccess) {
                result.out.filter { it.isNotBlank() }.map { it.trim() }
            } else {
                emptyList()
            }
            searchTotalCount = searchResults?.size ?: 0
        }

        val results = searchResults ?: return emptyList()
        val startIndex = currentPage * PAGE_SIZE
        if (startIndex >= results.size) return emptyList()
        val endIndex = minOf(startIndex + PAGE_SIZE, results.size)
        return results.subList(startIndex, endIndex)
    }

    private fun filterDomains(query: String) {
        currentSearchQuery = query
        currentPage = 0
        searchResults = null // Reset cached search results
        filteredDomains.clear()
        adapter.notifyDataSetChanged()
        loadMoreDomains()
    }

    private fun updateShowingCount() {
        val showing = filteredDomains.size
        if (currentSearchQuery.isNotEmpty()) {
            textShowingCount.text = "Showing $showing of $searchTotalCount matches"
        } else {
            textShowingCount.text = "Showing $showing of $totalLineCount"
        }
    }

    // RecyclerView Adapter
    inner class DomainAdapter(
        private val items: List<String>
    ) : RecyclerView.Adapter<DomainAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textDomain: TextView = view.findViewById(R.id.textDomain)
            val textIndex: TextView = view.findViewById(R.id.textIndex)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_domain, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val domain = items[position]
            holder.textDomain.text = domain
            holder.textIndex.text = "${position + 1}"
        }

        override fun getItemCount() = items.size
    }
}
