package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostlistUiModel(val filename: String, val path: String, val domainCount: Int, val sizeBytes: Long)

data class HostlistsUiState(
    val hostlists: List<HostlistUiModel> = emptyList(),
    val totalDomains: Int = 0,
    val totalFiles: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class HostlistsViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HostlistsUiState())
    val uiState: StateFlow<HostlistsUiState> = _uiState.asStateFlow()
    private val listsDir = "/data/adb/modules/zapret2/zapret2/lists"

    init { loadData() }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val files = withContext(Dispatchers.IO) {
                val cmd = """for f in "$listsDir"/*.txt; do [ -f "${'$'}f" ] && echo "${'$'}(basename "${'$'}f")|${'$'}f|${'$'}(wc -l < "${'$'}f")|${'$'}(stat -c %s "${'$'}f")"; done 2>/dev/null"""
                val result = Shell.cmd(cmd).exec()
                if (!result.isSuccess) emptyList()
                else result.out.filter { it.isNotBlank() }.mapNotNull { line ->
                    val p = line.split("|"); if (p.size < 4) null
                    else HostlistUiModel(p[0], p[1], p[2].trim().toIntOrNull() ?: 0, p[3].trim().toLongOrNull() ?: 0L)
                }.sortedByDescending { it.domainCount }
            }
            _uiState.update { it.copy(hostlists = files, totalDomains = files.sumOf { it.domainCount }, totalFiles = files.size, isLoading = false, isRefreshing = false) }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadData()
    }
}
