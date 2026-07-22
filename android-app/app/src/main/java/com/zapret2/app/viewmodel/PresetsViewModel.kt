package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.PresetCatalog
import com.zapret2.app.data.PresetContentPolicy
import com.zapret2.app.data.PresetDurableOutcome
import com.zapret2.app.data.PresetEntry
import com.zapret2.app.data.PresetIssue
import com.zapret2.app.data.PresetMutationOutcome
import com.zapret2.app.data.PresetNamePolicy
import com.zapret2.app.data.PresetRepository
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class PresetsOperation { LOAD, APPLY, SWITCH_MODE, OPEN_EDITOR, SAVE, SAVE_AND_APPLY }

data class PresetEditorState(
    val fileName: String,
    val content: String,
    val baselineContent: String,
    val hasAuthoritativeBaseline: Boolean = false,
) {
    val hasUnsavedChanges: Boolean
        get() = content != baselineContent
}

data class PresetsUiState(
    val activeMode: String = "categories",
    val activePresetFile: String = "",
    val activeCmdlineFile: String = "cmdline.txt",
    val hasAuthoritativeCatalog: Boolean = false,
    val presets: List<PresetEntry> = emptyList(),
    val quarantinedCount: Int = 0,
    val issueCounts: Map<PresetIssue, Int> = emptyMap(),
    val operation: PresetsOperation? = null,
    val loadingText: UiText? = null,
    val loadError: UiText? = null,
    val message: UiText? = null,
    val editingPreset: PresetEditorState? = null,
    val lastOutcome: PresetDurableOutcome? = null,
    val lastIssue: PresetIssue? = null,
) {
    val isLoading: Boolean
        get() = operation != null
}

@HiltViewModel
class PresetsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: PresetRepository,
    private val serviceEventBus: ServiceEventBus,
) : ViewModel() {

    private val restoredOutcome =
        savedStateHandle.restoreEnumNameOrRemove<PresetDurableOutcome>(LAST_OUTCOME_KEY)
    private val restoredIssue =
        savedStateHandle.restoreEnumNameOrRemove<PresetIssue>(LAST_ISSUE_KEY)
            .takeIf { restoredOutcome == PresetDurableOutcome.REJECTED }
            .also { if (it == null) savedStateHandle.remove<String>(LAST_ISSUE_KEY) }
    private val restoredEditor = restoreEditorState(savedStateHandle)

    private val _uiState = MutableStateFlow(
        PresetsUiState(
            editingPreset = restoredEditor,
            lastOutcome = restoredOutcome,
            lastIssue = restoredIssue,
        ),
    )
    val uiState: StateFlow<PresetsUiState> = _uiState.asStateFlow()
    private val operationInProgress = AtomicBoolean(false)
    private var refreshAfterOperation = false

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun onScreenEntered() {
        if (operationInProgress.get()) {
            refreshAfterOperation = true
        } else {
            loadPresets()
        }
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    fun loadPresets() {
        if (!operationInProgress.compareAndSet(false, true)) return
        beginOperation(PresetsOperation.LOAD, UiText.resource(R.string.presets_loading), clearLoadError = true)
        launchOperation { loadPresetsNow() }
    }

    internal suspend fun loadPresetsNow() {
        val catalog = try {
            repository.loadCatalog()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val revalidatedEditor = if (catalog == null) {
            _uiState.value.editingPreset?.copy(hasAuthoritativeBaseline = false)
        } else {
            revalidateEditor(catalog)
        }
        persistEditorState(revalidatedEditor)
        _uiState.update { current ->
            if (catalog == null) {
                current.copy(
                    activeMode = "",
                    activePresetFile = "",
                    activeCmdlineFile = "",
                    presets = emptyList(),
                    quarantinedCount = 0,
                    issueCounts = emptyMap(),
                    hasAuthoritativeCatalog = false,
                    operation = null,
                    loadingText = null,
                    loadError = UiText.resource(R.string.presets_load_failed),
                    editingPreset = revalidatedEditor,
                )
            } else {
                current.copy(
                    activeMode = catalog.selection.activeMode,
                    activePresetFile = catalog.selection.activePresetFile,
                    activeCmdlineFile = catalog.selection.activeCmdlineFile,
                    presets = catalog.discovery.available,
                    quarantinedCount = catalog.discovery.quarantinedCount,
                    issueCounts = catalog.discovery.issueCounts,
                    hasAuthoritativeCatalog = true,
                    operation = null,
                    loadingText = null,
                    loadError = null,
                    editingPreset = revalidatedEditor,
                )
            }
        }
    }

    fun applyPreset(fileName: String) {
        if (!acceptName(fileName)) return
        val state = _uiState.value
        if (!state.hasAuthoritativeCatalog || state.presets.none { it.fileName == fileName }) return
        if (state.activeMode in ACTIVE_PRESET_MODES && state.activePresetFile == fileName) return
        if (!operationInProgress.compareAndSet(false, true)) return
        beginOperation(
            PresetsOperation.APPLY,
            UiText.resource(R.string.presets_applying, fileName),
        )
        launchOperation { applyPresetNow(fileName) }
    }

    internal suspend fun applyPresetNow(fileName: String) {
        val outcome = repository.apply(fileName)
        finishMutation(outcome, fileName, keepOperation = true)
        loadPresetsNow()
    }

    fun switchToCategoriesMode() {
        val state = _uiState.value
        if (!state.hasAuthoritativeCatalog || state.activeMode == CATEGORY_MODE) return
        if (!operationInProgress.compareAndSet(false, true)) return
        beginOperation(
            PresetsOperation.SWITCH_MODE,
            UiText.resource(R.string.presets_switching_categories),
        )
        launchOperation { switchToCategoriesNow() }
    }

    internal suspend fun switchToCategoriesNow() {
        val outcome = repository.switchToCategories()
        finishMutation(outcome, null, keepOperation = true)
        loadPresetsNow()
    }

    fun openPresetEditor(fileName: String) {
        if (!acceptName(fileName)) return
        val state = _uiState.value
        if (state.editingPreset != null ||
            !state.hasAuthoritativeCatalog ||
            state.presets.none { it.fileName == fileName }
        ) return
        if (!operationInProgress.compareAndSet(false, true)) return
        beginOperation(
            PresetsOperation.OPEN_EDITOR,
            UiText.resource(R.string.presets_loading_file, fileName),
        )
        launchOperation { openPresetEditorNow(fileName) }
    }

    internal suspend fun openPresetEditorNow(fileName: String) {
        if (_uiState.value.editingPreset != null) return
        val content = repository.readCompatible(fileName)
        val editor = content?.let {
            PresetEditorState(
                fileName = fileName,
                content = it,
                baselineContent = it,
                hasAuthoritativeBaseline = true,
            )
        }
        persistEditorState(editor)
        _uiState.update { current ->
            current.copy(
                operation = null,
                loadingText = null,
                editingPreset = editor,
                message = if (content == null) {
                    UiText.resource(R.string.presets_read_failed)
                } else {
                    current.message
                },
            )
        }
    }

    fun closePresetEditor(discardUnsavedChanges: Boolean = false) {
        if (operationInProgress.get()) return
        val editor = _uiState.value.editingPreset ?: return
        if (editor.hasUnsavedChanges && !discardUnsavedChanges) return
        persistEditorState(null)
        _uiState.update { it.copy(editingPreset = null) }
    }

    fun updatePresetContent(content: String) {
        if (operationInProgress.get() || !_uiState.value.hasAuthoritativeCatalog) return
        val editor = _uiState.value.editingPreset ?: return
        if (!editor.hasAuthoritativeBaseline) return
        if (
            !fitsNormalizedEditorBudget(
                value = content,
                maxBytes = PresetContentPolicy.MAX_BYTES,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            )
        ) {
            _uiState.update { it.copy(message = UiText.resource(R.string.presets_editor_too_large)) }
            return
        }
        val updated = editor.copy(content = content)
        persistEditorState(updated)
        _uiState.update {
            it.copy(
                editingPreset = updated,
                message = if (it.message == UiText.resource(R.string.presets_editor_too_large)) {
                    null
                } else {
                    it.message
                },
            )
        }
    }

    fun savePreset(applyAfterSave: Boolean) {
        val state = _uiState.value
        val editor = state.editingPreset ?: return
        if (!state.hasAuthoritativeCatalog || !editor.hasAuthoritativeBaseline ||
            !editor.hasUnsavedChanges || state.presets.none { it.fileName == editor.fileName }
        ) return
        if (!acceptName(editor.fileName)) return
        if (!operationInProgress.compareAndSet(false, true)) return
        beginOperation(
            if (applyAfterSave) PresetsOperation.SAVE_AND_APPLY else PresetsOperation.SAVE,
            UiText.resource(
                if (applyAfterSave) R.string.presets_saving_applying else R.string.presets_saving,
            ),
        )
        launchOperation {
            savePresetNow(
                fileName = editor.fileName,
                expectedContent = editor.baselineContent,
                content = editor.content,
                applyAfterSave = applyAfterSave,
            )
        }
    }

    internal suspend fun savePresetNow(
        fileName: String,
        expectedContent: String,
        content: String,
        applyAfterSave: Boolean,
    ) {
        val outcome = repository.save(fileName, expectedContent, content, applyAfterSave)
        if (outcome is PresetMutationOutcome.Saved || outcome is PresetMutationOutcome.SavedAndApplied) {
            persistEditorState(null)
            _uiState.update { it.copy(editingPreset = null) }
        }
        finishMutation(outcome, fileName, keepOperation = true)
        loadPresetsNow()
    }

    private fun launchOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { current ->
                    current.copy(
                        operation = null,
                        loadingText = null,
                        loadError = if (current.operation == PresetsOperation.LOAD) {
                            UiText.resource(R.string.presets_load_failed)
                        } else {
                            current.loadError
                        },
                        message = if (current.operation == PresetsOperation.LOAD) {
                            current.message
                        } else {
                            UiText.resource(R.string.presets_io_failed)
                        },
                    )
                }
            } finally {
                operationInProgress.set(false)
                runPendingRefresh()
            }
        }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || operationInProgress.get()) return
        refreshAfterOperation = false
        loadPresets()
    }

    private suspend fun revalidateEditor(catalog: PresetCatalog): PresetEditorState? {
        val editor = _uiState.value.editingPreset ?: return null
        if (catalog.discovery.available.none { it.fileName == editor.fileName }) {
            return editor.copy(hasAuthoritativeBaseline = false)
        }
        val source = try {
            repository.readCompatible(editor.fileName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return editor.copy(hasAuthoritativeBaseline = false)
        val draft = editor.content.takeIf { editor.hasUnsavedChanges } ?: source
        return editor.copy(
            content = draft,
            baselineContent = source,
            hasAuthoritativeBaseline = true,
        )
    }

    private fun beginOperation(operation: PresetsOperation, text: UiText, clearLoadError: Boolean = false) {
        _uiState.update {
            it.copy(
                operation = operation,
                loadingText = text,
                loadError = if (clearLoadError) null else it.loadError,
                hasAuthoritativeCatalog = if (operation == PresetsOperation.LOAD) {
                    false
                } else {
                    it.hasAuthoritativeCatalog
                },
            )
        }
    }

    private fun finishMutation(
        outcome: PresetMutationOutcome,
        fileName: String?,
        keepOperation: Boolean = false,
    ) {
        if (outcome is PresetMutationOutcome.Applied ||
            outcome is PresetMutationOutcome.SavedAndApplied ||
            outcome is PresetMutationOutcome.CategoriesEnabled
        ) {
            serviceEventBus.notifyServiceRestarted()
        }
        val issue = (outcome as? PresetMutationOutcome.Rejected)?.issue
        savedStateHandle[LAST_OUTCOME_KEY] = outcome.durable.name
        if (issue == null) savedStateHandle.remove<String>(LAST_ISSUE_KEY)
        else savedStateHandle[LAST_ISSUE_KEY] = issue.name
        _uiState.update {
            it.copy(
                operation = if (keepOperation) it.operation else null,
                loadingText = if (keepOperation) it.loadingText else null,
                message = outcomeMessage(outcome, fileName),
                lastOutcome = outcome.durable,
                lastIssue = issue,
            )
        }
    }

    private fun acceptName(fileName: String): Boolean {
        if (PresetNamePolicy.isValid(fileName)) return true
        _uiState.update { it.copy(message = UiText.resource(R.string.presets_invalid_file)) }
        return false
    }

    private fun persistEditorState(editor: PresetEditorState?) {
        if (editor == null ||
            editor.content.length > MAX_SAVED_EDITOR_FIELD_CHARS ||
            editor.baselineContent.length > MAX_SAVED_EDITOR_FIELD_CHARS
        ) {
            savedStateHandle.remove<String>(EDITOR_FILE_KEY)
            savedStateHandle.remove<String>(EDITOR_DRAFT_KEY)
            savedStateHandle.remove<String>(EDITOR_BASELINE_KEY)
            return
        }
        savedStateHandle[EDITOR_FILE_KEY] = editor.fileName
        savedStateHandle[EDITOR_DRAFT_KEY] = editor.content
        savedStateHandle[EDITOR_BASELINE_KEY] = editor.baselineContent
    }

    private fun outcomeMessage(outcome: PresetMutationOutcome, fileName: String?): UiText = when (outcome) {
        PresetMutationOutcome.Applied -> UiText.resource(R.string.presets_applied_file, fileName.orEmpty())
        PresetMutationOutcome.Saved -> UiText.resource(R.string.presets_saved)
        PresetMutationOutcome.SavedAndApplied -> UiText.resource(R.string.presets_saved_applied)
        PresetMutationOutcome.CategoriesEnabled -> UiText.resource(R.string.presets_categories_enabled)
        is PresetMutationOutcome.Rejected -> UiText.resource(R.string.presets_validation_rejected)
        PresetMutationOutcome.SourceChanged -> UiText.resource(R.string.presets_source_changed)
        PresetMutationOutcome.RestartFailedRolledBack ->
            UiText.resource(R.string.presets_restart_failed_rolled_back)
        PresetMutationOutcome.WriteFailedRolledBack ->
            UiText.resource(R.string.presets_write_failed_rolled_back)
        PresetMutationOutcome.RollbackFailed -> UiText.resource(R.string.presets_rollback_failed)
        PresetMutationOutcome.IoFailed -> UiText.resource(R.string.presets_io_failed)
        PresetMutationOutcome.Blocked -> UiText.resource(R.string.presets_mutation_blocked)
    }

    private companion object {
        const val LAST_OUTCOME_KEY = "presets_last_outcome"
        const val LAST_ISSUE_KEY = "presets_last_issue"
        const val EDITOR_FILE_KEY = "presets_editor_file"
        const val EDITOR_DRAFT_KEY = "presets_editor_draft"
        const val EDITOR_BASELINE_KEY = "presets_editor_baseline"
        const val CATEGORY_MODE = "categories"
        val ACTIVE_PRESET_MODES = setOf("file", "preset", "txt")
    }
}

private fun restoreEditorState(savedStateHandle: SavedStateHandle): PresetEditorState? {
    val fileName = savedStateHandle.restoreTypedOrRemove<String>("presets_editor_file")
    val draft = savedStateHandle.restoreTypedOrRemove<String>("presets_editor_draft")
    val baseline = savedStateHandle.restoreTypedOrRemove<String>("presets_editor_baseline")
    val restored = if (
        fileName != null && PresetNamePolicy.isValid(fileName) &&
        draft != null && draft.length <= MAX_SAVED_EDITOR_FIELD_CHARS &&
        baseline != null && baseline.length <= MAX_SAVED_EDITOR_FIELD_CHARS
    ) {
        PresetEditorState(fileName, draft, baseline)
    } else {
        null
    }
    if (restored == null) {
        savedStateHandle.remove<String>("presets_editor_file")
        savedStateHandle.remove<String>("presets_editor_draft")
        savedStateHandle.remove<String>("presets_editor_baseline")
    }
    return restored
}
