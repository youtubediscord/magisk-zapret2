package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostlistContentUiState(
    val fileName: String = "",
    val filePath: String = "",
    val totalLines: Int = 0,
    val domains: List<String> = emptyList(),
    val searchQuery: String = "",
    val showingCount: String = "",
    val isLoading: Boolean = false
)

@HiltViewModel
class HostlistContentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(HostlistContentUiState())
    val uiState: StateFlow<HostlistContentUiState> = _uiState.asStateFlow()

    private val pageSize = 100
    private var currentPage = 0
    private var isLoadingMore = false
    private var searchResults: List<String>? = null
    private var searchJob: Job? = null

    val filePath: String = savedStateHandle.get<String>("path") ?: ""
    val fileName: String = savedStateHandle.get<String>("name") ?: ""

    init {
        _uiState.update { it.copy(fileName = fileName, filePath = filePath) }
        if (filePath.isNotEmpty()) loadInitial()
    }

    private fun loadInitial() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val total = withContext(Dispatchers.IO) {
                Shell.cmd("wc -l < \"$filePath\" 2>/dev/null").exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            }
            _uiState.update { it.copy(totalLines = total, isLoading = false) }
            loadMore()
        }
    }

    fun loadMore() {
        if (isLoadingMore) return
        isLoadingMore = true
        viewModelScope.launch {
            val newItems = withContext(Dispatchers.IO) {
                if (_uiState.value.searchQuery.isNotEmpty()) loadSearchPage()
                else loadFilePage()
            }
            if (newItems.isNotEmpty()) {
                _uiState.update { it.copy(domains = it.domains + newItems) }
                currentPage++
            }
            isLoadingMore = false
            updateShowingCount()
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _uiState.update { it.copy(searchQuery = query, domains = emptyList()) }
            currentPage = 0
            searchResults = null
            loadMore()
        }
    }

    private fun loadFilePage(): List<String> {
        val start = currentPage * pageSize + 1
        val end = start + pageSize - 1
        if (start > _uiState.value.totalLines) return emptyList()
        val result = Shell.cmd("sed -n '${start},${end}p' \"$filePath\" 2>/dev/null").exec()
        return if (result.isSuccess) result.out.filter { it.isNotBlank() }.map { it.trim() } else emptyList()
    }

    private fun loadSearchPage(): List<String> {
        if (searchResults == null) {
            val q = _uiState.value.searchQuery.replace("'", "'\\''")
            val result = Shell.cmd("grep -i '$q' \"$filePath\" 2>/dev/null").exec()
            searchResults = if (result.isSuccess) result.out.filter { it.isNotBlank() }.map { it.trim() } else emptyList()
        }
        val results = searchResults ?: return emptyList()
        val start = currentPage * pageSize
        if (start >= results.size) return emptyList()
        return results.subList(start, minOf(start + pageSize, results.size))
    }

    private fun updateShowingCount() {
        val showing = _uiState.value.domains.size
        val text = if (_uiState.value.searchQuery.isNotEmpty()) "Showing $showing of ${searchResults?.size ?: 0} matches"
        else "Showing $showing of ${_uiState.value.totalLines}"
        _uiState.update { it.copy(showingCount = text) }
    }
}
