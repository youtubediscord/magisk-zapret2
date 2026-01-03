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
    private var totalDomainCount: Int = 0

    private val allDomains = mutableListOf<String>()
    private val filteredDomains = mutableListOf<String>()
    private lateinit var adapter: DomainAdapter

    private var searchJob: Job? = null

    // Pagination
    private val PAGE_SIZE = 100
    private var currentPage = 0
    private var isLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hostlist_content)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
        totalDomainCount = intent.getIntExtra(EXTRA_DOMAIN_COUNT, 0)

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
        textSubtitle.text = "$totalDomainCount domains"

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

            val domains = withContext(Dispatchers.IO) {
                loadDomainsFromFile()
            }

            allDomains.clear()
            allDomains.addAll(domains)

            currentPage = 0
            filteredDomains.clear()
            loadMoreDomains()

            progressBar.visibility = View.GONE
            updateShowingCount()

            if (allDomains.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadDomainsFromFile(): List<String> {
        val result = Shell.cmd("cat \"$filePath\" 2>/dev/null").exec()
        if (!result.isSuccess) {
            return emptyList()
        }

        return result.out
            .filter { it.isNotBlank() }
            .map { it.trim() }
    }

    private fun loadMoreDomains() {
        if (isLoading) return

        val searchQuery = editSearch.text?.toString() ?: ""
        val source = if (searchQuery.isNotEmpty()) {
            allDomains.filter { it.contains(searchQuery, ignoreCase = true) }
        } else {
            allDomains
        }

        val startIndex = currentPage * PAGE_SIZE
        if (startIndex >= source.size) return

        isLoading = true

        val endIndex = minOf(startIndex + PAGE_SIZE, source.size)
        val newItems = source.subList(startIndex, endIndex)

        filteredDomains.addAll(newItems)
        adapter.notifyItemRangeInserted(filteredDomains.size - newItems.size, newItems.size)

        currentPage++
        isLoading = false

        updateShowingCount()
    }

    private fun filterDomains(query: String) {
        currentPage = 0
        filteredDomains.clear()
        adapter.notifyDataSetChanged()
        loadMoreDomains()
    }

    private fun updateShowingCount() {
        val searchQuery = editSearch.text?.toString() ?: ""
        val totalFiltered = if (searchQuery.isNotEmpty()) {
            allDomains.count { it.contains(searchQuery, ignoreCase = true) }
        } else {
            allDomains.size
        }

        val showing = filteredDomains.size
        textShowingCount.text = if (searchQuery.isNotEmpty()) {
            "Showing $showing of $totalFiltered matches"
        } else {
            "Showing $showing of $totalFiltered"
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
