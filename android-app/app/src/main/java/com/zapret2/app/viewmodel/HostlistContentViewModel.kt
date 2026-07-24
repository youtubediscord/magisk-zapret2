package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.HostlistConditionalWriteOutcome
import com.zapret2.app.data.HostlistRepository
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.canonicalProtectedText
import com.zapret2.app.data.countHostlistDataLines
import com.zapret2.app.data.isValidHostlistContent
import com.zapret2.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class HostlistContentLoadState { IDLE, LOADING, READY, ERROR }

enum class HostlistContentError { INVALID_PATH, LOAD_FAILED }

enum class HostlistContentResult {
    LOAD_MORE_FAILED,
    EDITOR_LOAD_FAILED,
    TOO_LARGE,
    INVALID_CONTENT,
    SAVED,
    SOURCE_CHANGED,
    SAVE_FAILED,
    SAVE_BLOCKED,
    ROLLBACK_FAILED,
}

private sealed interface HostlistContentSaveOutcome {
    data class Saved(val persistedContent: String) : HostlistContentSaveOutcome
    data object SourceChanged : HostlistContentSaveOutcome
    data object Failed : HostlistContentSaveOutcome
    data object Blocked : HostlistContentSaveOutcome
    data object RollbackFailed : HostlistContentSaveOutcome
}

data class HostlistContentUiState(
    val fileName: String = "",
    val totalEntries: Int = 0,
    val entries: List<String> = emptyList(),
    val searchQuery: String = "",
    val matchingEntries: Int? = null,
    val loadState: HostlistContentLoadState = HostlistContentLoadState.IDLE,
    val loadError: HostlistContentError? = null,
    val result: HostlistContentResult? = null,
    val message: UiText? = null,
    val isEditing: Boolean = false,
    val hasAuthoritativeEditorBaseline: Boolean = false,
    val editorContent: String = "",
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
) {
    val isLoading: Boolean
        get() = loadState == HostlistContentLoadState.LOADING
    val canEditContent: Boolean
        get() = isEditing && hasAuthoritativeEditorBaseline &&
            loadState == HostlistContentLoadState.READY && !isSaving
    val canSaveContent: Boolean
        get() = canEditContent && hasUnsavedChanges
    val canLoadMore: Boolean
        get() = loadState == HostlistContentLoadState.READY && !isEditing && !isSaving &&
            entries.size < (matchingEntries ?: totalEntries)
}

internal fun HostlistContentUiState.pendingSearch(query: String): HostlistContentUiState = copy(
    totalEntries = 0,
    entries = emptyList(),
    searchQuery = query,
    matchingEntries = if (query.isEmpty()) null else 0,
    loadState = HostlistContentLoadState.LOADING,
    loadError = null,
)

private sealed interface PageLoadResult {
    data class Content(val items: List<String>, val matchingEntries: Int? = null) : PageLoadResult
    data object Failed : PageLoadResult
}

@HiltViewModel
class HostlistContentViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val hostlistRepository: HostlistRepository = HostlistRepository(),
) : ViewModel() {

    private val requestedFileName: String =
        savedStateHandle.restoreTypedOrRemove<String>("name").orEmpty()
    private val resolvedFilePath = hostlistRepository.pathForFileName(requestedFileName)
    private val routeIsAllowed = resolvedFilePath != null
    private val filePath: String = resolvedFilePath.orEmpty()
    val fileName: String = requestedFileName.takeIf { routeIsAllowed }.orEmpty()
    private val restoredQuery = if (routeIsAllowed) {
        sanitizeQuery(savedStateHandle.restoreTypedOrRemove<String>(KEY_QUERY).orEmpty())
    } else {
        ""
    }
    private val restoredDraft = if (routeIsAllowed) restoreBounded(KEY_EDITOR_DRAFT) else null
    private val restoredBaseline = if (routeIsAllowed) restoreBounded(KEY_EDITOR_BASELINE) else null
    private val restoredEditing = routeIsAllowed &&
        savedStateHandle.restoreTypedOrRemove<Boolean>(KEY_EDITING) == true &&
        restoredDraft != null && restoredBaseline != null
    private val restoredResult = if (routeIsAllowed) {
        savedStateHandle.restoreEnumNameOrRemove<HostlistContentResult>(KEY_RESULT)
    } else {
        null
    }
    private val _uiState = MutableStateFlow(
        HostlistContentUiState(
            fileName = fileName,
            searchQuery = restoredQuery,
            loadState = HostlistContentLoadState.IDLE,
            result = restoredResult,
            message = restoredResult?.toUiText(),
            isEditing = restoredEditing,
            editorContent = restoredDraft.orEmpty(),
            hasUnsavedChanges = restoredEditing && restoredDraft != restoredBaseline,
        ),
    )
    val uiState: StateFlow<HostlistContentUiState> = _uiState.asStateFlow()

    private val pageSize = 100
    private var currentPage = 0
    private var isLoadingMore = false
    private var searchJob: Job? = null
    private var initialLoadJob: Job? = null
    private var pageLoadJob: Job? = null
    private var editorLoadJob: Job? = null
    private var loadGeneration = 0L
    private var editorBaseline = restoredBaseline.orEmpty()
    private val initialLoadRequested = AtomicBoolean(false)

    init {
        if (isAllowedHostlistPath()) {
            // Canonicalize restored state immediately so the Activity Bundle never retains an
            // oversized raw query or a partially persisted editor transaction.
            savedStateHandle[KEY_QUERY] = restoredQuery
            if (!restoredEditing) {
                clearPersistedEditorState()
            }
        } else {
            clearRouteScopedSavedState()
            _uiState.update {
                it.copy(
                    loadState = HostlistContentLoadState.ERROR,
                    loadError = HostlistContentError.INVALID_PATH,
                )
            }
        }
    }

    fun ensureLoaded() {
        if (initialLoadRequested.compareAndSet(false, true) &&
            isAllowedHostlistPath() &&
            !restoredEditing
        ) {
            loadInitial(restoredQuery)
        }
    }

    private fun isAllowedHostlistPath(): Boolean =
        routeIsAllowed

    private fun clearRouteScopedSavedState() {
        savedStateHandle.remove<String>(KEY_QUERY)
        clearPersistedEditorState()
        savedStateHandle.remove<String>(KEY_RESULT)
    }

    private fun clearPersistedEditorState() {
        savedStateHandle.remove<Boolean>(KEY_EDITING)
        savedStateHandle.remove<String>(KEY_EDITOR_DRAFT)
        savedStateHandle.remove<String>(KEY_EDITOR_BASELINE)
    }

    fun retryLoad() {
        val state = _uiState.value
        if (state.isEditing && !state.isSaving && !state.hasAuthoritativeEditorBaseline) {
            revalidateEditorSource()
        } else if (!state.isLoading && !state.isEditing && !state.isSaving && isAllowedHostlistPath()) {
            loadInitial(state.searchQuery)
        }
    }

    fun clearMessage() {
        savedStateHandle.remove<String>(KEY_RESULT)
        _uiState.update { it.copy(result = null, message = null) }
    }

    private fun revalidateEditorSource() {
        val state = _uiState.value
        if (!state.isEditing || state.isSaving || !isAllowedHostlistPath()) return
        val draft = state.editorContent.takeIf { state.hasUnsavedChanges }
        val generation = ++loadGeneration
        initialLoadJob?.cancel()
        pageLoadJob?.cancel()
        isLoadingMore = false
        _uiState.update {
            it.copy(
                loadState = HostlistContentLoadState.LOADING,
                loadError = null,
                hasAuthoritativeEditorBaseline = false,
            )
        }
        initialLoadJob = viewModelScope.launch {
            try {
                val content = try {
                    withContext(Dispatchers.IO) { hostlistRepository.readForEditing(filePath) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (generation != loadGeneration) return@launch
                if (content == null) {
                    _uiState.update {
                        it.copy(
                            loadState = HostlistContentLoadState.ERROR,
                            loadError = HostlistContentError.LOAD_FAILED,
                            hasAuthoritativeEditorBaseline = false,
                        )
                    }
                    publishResult(HostlistContentResult.EDITOR_LOAD_FAILED)
                    return@launch
                }
                val displayedContent = draft ?: content
                editorBaseline = content
                persistBounded(KEY_EDITOR_BASELINE, content)
                persistBounded(KEY_EDITOR_DRAFT, displayedContent)
                savedStateHandle[KEY_EDITING] = true
                _uiState.update {
                    it.copy(
                        editorContent = displayedContent,
                        hasUnsavedChanges = displayedContent != content,
                        hasAuthoritativeEditorBaseline = true,
                        loadState = HostlistContentLoadState.READY,
                        loadError = null,
                    )
                }
            } finally {
                if (generation == loadGeneration) {
                    initialLoadJob = null
                }
            }
        }
    }

    private fun loadInitial(query: String = "") {
        val sanitizedQuery = sanitizeQuery(query)
        val generation = ++loadGeneration
        editorLoadJob?.cancel()
        editorLoadJob = null
        initialLoadJob?.cancel()
        pageLoadJob?.cancel()
        isLoadingMore = false
        currentPage = 0
        savedStateHandle[KEY_QUERY] = sanitizedQuery
        _uiState.update {
            it.copy(
                entries = emptyList(),
                totalEntries = 0,
                searchQuery = sanitizedQuery,
                matchingEntries = if (sanitizedQuery.isEmpty()) null else 0,
                loadState = HostlistContentLoadState.LOADING,
                loadError = null,
            )
        }
        initialLoadJob = viewModelScope.launch {
            try {
                val totalResult = withContext(Dispatchers.IO) {
                    try {
                        hostlistRepository.countEntries(filePath)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
                val total = totalResult
                if (generation != loadGeneration) return@launch
                if (total == null) {
                    _uiState.update {
                        it.copy(
                            loadState = HostlistContentLoadState.ERROR,
                            loadError = HostlistContentError.LOAD_FAILED,
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(totalEntries = total) }
                when (
                    val page = withContext(Dispatchers.IO) {
                        if (sanitizedQuery.isEmpty()) {
                            loadFilePage()
                        } else {
                            loadSearchPage(sanitizedQuery)
                        }
                    }
                ) {
                    is PageLoadResult.Content -> {
                        if (generation != loadGeneration) return@launch
                        currentPage = if (page.items.isEmpty()) 0 else 1
                        _uiState.update {
                            it.copy(
                                entries = page.items,
                                matchingEntries = page.matchingEntries,
                                loadState = HostlistContentLoadState.READY,
                                loadError = null,
                            )
                        }
                    }

                    PageLoadResult.Failed -> if (generation == loadGeneration) {
                        _uiState.update {
                            it.copy(
                                loadState = HostlistContentLoadState.ERROR,
                                loadError = HostlistContentError.LOAD_FAILED,
                            )
                        }
                    }
                }
            } finally {
                if (generation == loadGeneration) {
                    initialLoadJob = null
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (isLoadingMore || !state.canLoadMore) return
        isLoadingMore = true
        val generation = loadGeneration
        val expectedQuery = state.searchQuery
        pageLoadJob = viewModelScope.launch {
            try {
                val newPage = withContext(Dispatchers.IO) {
                    if (expectedQuery.isNotEmpty()) loadSearchPage(expectedQuery) else loadFilePage()
                }
                if (generation != loadGeneration || expectedQuery != _uiState.value.searchQuery) return@launch
                when (newPage) {
                    is PageLoadResult.Content -> {
                        if (newPage.items.isNotEmpty()) {
                            _uiState.update { it.copy(entries = it.entries + newPage.items) }
                            currentPage++
                        }
                        _uiState.update {
                            it.copy(matchingEntries = newPage.matchingEntries ?: it.matchingEntries)
                        }
                    }

                    PageLoadResult.Failed -> _uiState.update {
                        if (it.entries.isEmpty()) {
                            it.copy(
                                loadState = HostlistContentLoadState.ERROR,
                                loadError = HostlistContentError.LOAD_FAILED,
                            )
                        } else {
                            it
                        }
                    }
                }
                if (newPage == PageLoadResult.Failed && _uiState.value.entries.isNotEmpty()) {
                    publishResult(HostlistContentResult.LOAD_MORE_FAILED)
                }
            } finally {
                if (generation == loadGeneration) isLoadingMore = false
            }
        }
    }

    fun search(query: String) {
        val state = _uiState.value
        if (state.isEditing || state.isSaving || editorLoadJob?.isActive == true ||
            !isAllowedHostlistPath()
        ) return
        searchJob?.cancel()
        initialLoadJob?.cancel()
        pageLoadJob?.cancel()
        loadGeneration++
        isLoadingMore = false
        currentPage = 0
        val sanitizedQuery = sanitizeQuery(query)
        savedStateHandle[KEY_QUERY] = sanitizedQuery
        _uiState.update { it.pendingSearch(sanitizedQuery) }
        searchJob = viewModelScope.launch {
            delay(300)
            loadInitial(sanitizedQuery)
        }
    }

    fun enterEditMode() {
        if (_uiState.value.isEditing || _uiState.value.isSaving ||
            _uiState.value.loadState != HostlistContentLoadState.READY
        ) return
        if (!isAllowedHostlistPath()) {
            _uiState.update {
                it.copy(
                    loadState = HostlistContentLoadState.ERROR,
                    loadError = HostlistContentError.INVALID_PATH,
                )
            }
            return
        }
        searchJob?.cancel()
        initialLoadJob?.cancel()
        pageLoadJob?.cancel()
        val generation = ++loadGeneration
        isLoadingMore = false
        _uiState.update { it.copy(loadState = HostlistContentLoadState.LOADING) }
        editorLoadJob = viewModelScope.launch {
            try {
                val content = try {
                    withContext(Dispatchers.IO) { hostlistRepository.readForEditing(filePath) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (generation != loadGeneration) return@launch
                if (content != null) {
                    editorBaseline = content
                    persistBounded(KEY_EDITOR_BASELINE, content)
                    persistBounded(KEY_EDITOR_DRAFT, content)
                    savedStateHandle[KEY_EDITING] = true
                    _uiState.update {
                        it.copy(
                            isEditing = true,
                            hasAuthoritativeEditorBaseline = true,
                            editorContent = content,
                            loadState = HostlistContentLoadState.READY,
                            loadError = null,
                            hasUnsavedChanges = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            loadState = HostlistContentLoadState.READY,
                        )
                    }
                    publishResult(HostlistContentResult.EDITOR_LOAD_FAILED)
                }
            } finally {
                if (generation == loadGeneration) {
                    editorLoadJob = null
                }
            }
        }
    }

    fun exitEditMode(discardUnsavedChanges: Boolean = false) {
        val state = _uiState.value
        if (
            !state.isEditing ||
            state.isSaving ||
            (state.hasUnsavedChanges && !discardUnsavedChanges)
        ) return
        editorLoadJob?.cancel()
        editorLoadJob = null
        loadGeneration++
        editorBaseline = ""
        clearPersistedEditorState()
        savedStateHandle[KEY_QUERY] = ""
        _uiState.update {
            it.copy(
                isEditing = false,
                hasAuthoritativeEditorBaseline = false,
                editorContent = "",
                hasUnsavedChanges = false,
            )
        }
        loadInitial()
    }

    fun updateEditorContent(text: String) {
        if (!_uiState.value.canEditContent) return
        if (
            !fitsNormalizedEditorBudget(
                value = text,
                maxBytes = HostlistRepository.MAX_EDIT_BYTES,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            )
        ) {
            publishResult(HostlistContentResult.TOO_LARGE)
            return
        }
        persistBounded(KEY_EDITOR_DRAFT, text)
        _uiState.update {
            val clearValidationError = it.result == HostlistContentResult.TOO_LARGE ||
                it.result == HostlistContentResult.INVALID_CONTENT
            it.copy(
                editorContent = text,
                hasUnsavedChanges = text != editorBaseline,
                result = if (clearValidationError) null else it.result,
                message = if (clearValidationError) null else it.message,
            )
        }
    }

    fun saveFile() {
        if (!_uiState.value.canSaveContent) return
        if (!isAllowedHostlistPath()) {
            _uiState.update {
                it.copy(
                    loadState = HostlistContentLoadState.ERROR,
                    loadError = HostlistContentError.INVALID_PATH,
                )
            }
            return
        }
        val canonicalContent = canonicalProtectedText(_uiState.value.editorContent)
        val content = "$canonicalContent\n"
        if (!hostlistRepository.isEditableContentSizeAllowed(content)) {
            publishResult(HostlistContentResult.TOO_LARGE)
            return
        }
        if (!isValidHostlistContent(fileName, content)) {
            publishResult(HostlistContentResult.INVALID_CONTENT)
            return
        }
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        when (
                            val writeOutcome = hostlistRepository.writeIfUnchangedWithRollback(
                                filePath,
                                editorBaseline,
                                content,
                                "Z2_HOSTLIST_EDIT",
                            )
                        ) {
                            is HostlistConditionalWriteOutcome.Written ->
                                HostlistContentSaveOutcome.Saved(writeOutcome.persistedContent)
                            HostlistConditionalWriteOutcome.SourceChanged ->
                                HostlistContentSaveOutcome.SourceChanged
                            HostlistConditionalWriteOutcome.Failed -> HostlistContentSaveOutcome.Failed
                            HostlistConditionalWriteOutcome.RollbackFailed ->
                                HostlistContentSaveOutcome.RollbackFailed
                        }
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                HostlistContentSaveOutcome.Blocked
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostlistContentSaveOutcome.Failed
            }
            val saved = outcome as? HostlistContentSaveOutcome.Saved
            val requiresRevalidation = outcome is HostlistContentSaveOutcome.SourceChanged ||
                outcome is HostlistContentSaveOutcome.Failed ||
                outcome is HostlistContentSaveOutcome.RollbackFailed
            if (saved != null) {
                editorBaseline = saved.persistedContent
                persistBounded(KEY_EDITOR_BASELINE, saved.persistedContent)
                persistBounded(KEY_EDITOR_DRAFT, saved.persistedContent)
                savedStateHandle[KEY_EDITING] = true
            }
            _uiState.update {
                it.copy(
                    isSaving = false,
                    editorContent = saved?.persistedContent ?: it.editorContent,
                    hasAuthoritativeEditorBaseline = if (requiresRevalidation) {
                        false
                    } else {
                        it.hasAuthoritativeEditorBaseline
                    },
                    loadState = if (requiresRevalidation) {
                        HostlistContentLoadState.ERROR
                    } else {
                        it.loadState
                    },
                    loadError = if (requiresRevalidation) {
                        HostlistContentError.LOAD_FAILED
                    } else {
                        it.loadError
                    },
                    hasUnsavedChanges = saved == null,
                    totalEntries = if (saved != null) countHostlistDataLines(saved.persistedContent)
                    else it.totalEntries,
                )
            }
            publishResult(
                when (outcome) {
                    is HostlistContentSaveOutcome.Saved -> HostlistContentResult.SAVED
                    HostlistContentSaveOutcome.SourceChanged -> HostlistContentResult.SOURCE_CHANGED
                    HostlistContentSaveOutcome.Failed -> HostlistContentResult.SAVE_FAILED
                    HostlistContentSaveOutcome.Blocked -> HostlistContentResult.SAVE_BLOCKED
                    HostlistContentSaveOutcome.RollbackFailed -> HostlistContentResult.ROLLBACK_FAILED
                },
            )
        }
    }

    private fun loadFilePage(): PageLoadResult {
        val start = currentPage * pageSize + 1
        val end = start + pageSize - 1
        if (start > _uiState.value.totalEntries) return PageLoadResult.Content(emptyList())
        if (!isAllowedHostlistPath()) return PageLoadResult.Failed
        return try {
            hostlistRepository.readPage(filePath, offset = start - 1, limit = end - start + 1)
                ?.let { items -> PageLoadResult.Content(items) } ?: PageLoadResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageLoadResult.Failed
        }
    }

    private fun loadSearchPage(query: String = _uiState.value.searchQuery): PageLoadResult {
        if (!isAllowedHostlistPath()) return PageLoadResult.Failed
        return try {
            hostlistRepository.searchPage(
                path = filePath,
                query = query,
                offset = currentPage * pageSize,
                limit = pageSize,
            )?.let { page -> PageLoadResult.Content(page.items, page.totalMatches) }
                ?: PageLoadResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageLoadResult.Failed
        }
    }

    private fun publishResult(result: HostlistContentResult) {
        savedStateHandle[KEY_RESULT] = result.name
        _uiState.update { it.copy(result = result, message = result.toUiText()) }
    }

    private fun restoreBounded(key: String): String? =
        savedStateHandle.restoreBoundedEditorText(key)

    private fun persistBounded(key: String, value: String) {
        savedStateHandle.persistBoundedEditorText(key, value)
    }

    private fun sanitizeQuery(query: String): String = query
        .replace("\r\n", " ")
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(MAX_SAVED_QUERY_CHARS)

    private fun HostlistContentResult.toUiText(): UiText = UiText.resource(
        when (this) {
            HostlistContentResult.LOAD_MORE_FAILED -> R.string.hostlist_load_more_failed
            HostlistContentResult.EDITOR_LOAD_FAILED -> R.string.hostlist_editor_load_failed
            HostlistContentResult.TOO_LARGE -> R.string.hostlist_editor_too_large
            HostlistContentResult.INVALID_CONTENT -> R.string.hostlist_editor_invalid_content
            HostlistContentResult.SAVED -> R.string.hostlist_saved
            HostlistContentResult.SOURCE_CHANGED -> R.string.hostlist_source_changed
            HostlistContentResult.SAVE_FAILED -> R.string.hostlist_save_failed
            HostlistContentResult.SAVE_BLOCKED -> R.string.hostlist_save_blocked
            HostlistContentResult.ROLLBACK_FAILED -> R.string.hostlist_rollback_failed
        },
    )

    private companion object {
        const val MAX_SAVED_QUERY_CHARS = 256
        const val KEY_QUERY = "hostlist_content_query"
        const val KEY_EDITING = "hostlist_content_editing"
        const val KEY_EDITOR_DRAFT = "hostlist_content_editor_draft"
        const val KEY_EDITOR_BASELINE = "hostlist_content_editor_baseline"
        const val KEY_RESULT = "hostlist_content_result"
    }
}
