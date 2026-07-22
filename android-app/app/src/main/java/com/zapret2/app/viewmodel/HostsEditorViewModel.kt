package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.HostsOverlayRepository
import com.zapret2.app.data.HostsOverlayMutationOutcome
import com.zapret2.app.data.HostsOverlaySnapshot
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class HostsEditorOperation { LOAD, SAVE, RESET }

enum class HostsEditorResult {
    READ_FAILED,
    EMPTY,
    TOO_LARGE,
    INVALID,
    SAVED,
    SOURCE_CHANGED,
    SAVE_FAILED,
    ROLLBACK_FAILED,
    SAVE_BLOCKED,
    RESET_SAVED,
    RESET_FAILED,
    RESET_BLOCKED,
}

data class HostsEditorUiState(
    val content: String = "",
    val operation: HostsEditorOperation? = null,
    val hasAuthoritativeBaseline: Boolean = false,
    val baselineLoadAttempted: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val result: HostsEditorResult? = null,
    val message: UiText? = null,
) {
    val isLoading: Boolean
        get() = operation != null
    val actionsEnabled: Boolean
        get() = hasAuthoritativeBaseline && operation == null
    val reloadEnabled: Boolean
        get() = operation == null
}

private sealed interface HostsMutationOutcome {
    data class Saved(val content: String) : HostsMutationOutcome
    data object SourceChanged : HostsMutationOutcome
    data object Failed : HostsMutationOutcome
    data object RollbackFailed : HostsMutationOutcome
    data object Blocked : HostsMutationOutcome
}

@HiltViewModel
class HostsEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val hostsRepository: HostsOverlayRepository = HostsOverlayRepository(),
) : ViewModel() {

    private val restoredDraft = restoreBounded(KEY_DRAFT)
    private val restoredBaseline = restoreBounded(KEY_BASELINE)
    private val restoredEditorState = restoredDraft != null && restoredBaseline != null
    private var baselineContent = restoredBaseline.orEmpty()
    private var hasEnteredScreen = false
    private var refreshAfterOperation = false
    private val restoredResult = savedStateHandle.restoreEnumNameOrRemove<HostsEditorResult>(KEY_RESULT)
    private val _uiState = MutableStateFlow(
        HostsEditorUiState(
            content = restoredDraft.takeIf { restoredEditorState }.orEmpty(),
            hasUnsavedChanges = restoredEditorState && restoredDraft != baselineContent,
            result = restoredResult,
            message = restoredResult?.toUiText(),
        ),
    )
    val uiState: StateFlow<HostsEditorUiState> = _uiState.asStateFlow()

    init {
        if (!restoredEditorState) {
            savedStateHandle.remove<String>(KEY_DRAFT)
            savedStateHandle.remove<String>(KEY_BASELINE)
            loadHosts()
        }
    }

    fun clearMessage() {
        savedStateHandle.remove<String>(KEY_RESULT)
        _uiState.update { it.copy(result = null, message = null) }
    }

    fun loadHosts(discardUnsavedChanges: Boolean = false) {
        if (_uiState.value.hasUnsavedChanges && !discardUnsavedChanges) return
        loadHosts(preserveUnsavedDraft = false, clearResult = true)
    }

    fun revalidateHosts() {
        loadHosts(preserveUnsavedDraft = true, clearResult = true)
    }

    fun onScreenEntered() {
        val firstEntry = !hasEnteredScreen
        hasEnteredScreen = true
        if (firstEntry && !restoredEditorState) return
        if (_uiState.value.operation == null) {
            loadHosts(preserveUnsavedDraft = true, clearResult = false)
        } else {
            refreshAfterOperation = true
        }
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    private fun loadHosts(preserveUnsavedDraft: Boolean, clearResult: Boolean) {
        if (_uiState.value.operation != null) return
        val draft = _uiState.value.content.takeIf {
            preserveUnsavedDraft && _uiState.value.hasUnsavedChanges
        }
        if (clearResult) savedStateHandle.remove<String>(KEY_RESULT)
        _uiState.update {
            it.copy(
                operation = HostsEditorOperation.LOAD,
                hasAuthoritativeBaseline = false,
                baselineLoadAttempted = true,
                result = if (clearResult) null else it.result,
                message = if (clearResult) null else it.message,
            )
        }
        viewModelScope.launch {
            val content = try {
                withContext(Dispatchers.IO) { hostsRepository.readEffective() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (content != null) {
                val displayedContent = draft ?: content
                baselineContent = content
                persistBounded(KEY_BASELINE, content)
                persistBounded(KEY_DRAFT, displayedContent)
                _uiState.update {
                    it.copy(
                        content = displayedContent,
                        operation = null,
                        hasAuthoritativeBaseline = true,
                        hasUnsavedChanges = displayedContent != content,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(operation = null, hasAuthoritativeBaseline = false)
                }
                publishResult(HostsEditorResult.READ_FAILED)
            }
            runPendingRefresh()
        }
    }

    fun updateContent(text: String) {
        if (!_uiState.value.actionsEnabled) return
        if (
            !fitsNormalizedEditorBudget(
                value = text,
                maxBytes = HostsOverlayRepository.MAX_HOSTS_BYTES,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            )
        ) {
            publishResult(HostsEditorResult.TOO_LARGE)
            return
        }
        persistBounded(KEY_DRAFT, text)
        _uiState.update {
            val clearValidationError = when (it.result) {
                HostsEditorResult.EMPTY,
                HostsEditorResult.TOO_LARGE,
                HostsEditorResult.INVALID,
                -> true
                else -> false
            }
            it.copy(
                content = text,
                hasUnsavedChanges = text != baselineContent,
                result = if (clearValidationError) null else it.result,
                message = if (clearValidationError) null else it.message,
            )
        }
    }

    fun discardUnsavedChanges() {
        val state = _uiState.value
        if (state.operation != null || !state.hasUnsavedChanges) return
        persistBounded(KEY_DRAFT, baselineContent)
        _uiState.update {
            it.copy(
                content = baselineContent,
                hasUnsavedChanges = false,
            )
        }
    }

    fun saveHosts() {
        val state = _uiState.value
        if (!state.actionsEnabled || !state.hasUnsavedChanges) return
        val currentContent = state.content
        val normalized = currentContent
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n') + "\n"
        if (normalized.isBlank()) {
            publishResult(HostsEditorResult.EMPTY)
            return
        }
        if (!hostsRepository.isContentSizeAllowed(normalized)) {
            publishResult(HostsEditorResult.TOO_LARGE)
            return
        }
        if (!hostsRepository.isValidContent(normalized)) {
            publishResult(HostsEditorResult.INVALID)
            return
        }
        _uiState.update {
            it.copy(operation = HostsEditorOperation.SAVE)
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val snapshot = hostsRepository.snapshotOverlay()
                        if (snapshot == HostsOverlaySnapshot.Unsafe) return@withContext HostsMutationOutcome.Failed
                        val current = hostsRepository.readEffective(snapshot)
                            ?: return@withContext HostsMutationOutcome.Failed
                        if (current != baselineContent) return@withContext HostsMutationOutcome.SourceChanged
                        when (val mutation = hostsRepository.writeIfUnchanged(snapshot, current, normalized)) {
                            is HostsOverlayMutationOutcome.Applied -> {
                                return@withContext HostsMutationOutcome.Saved(mutation.effectiveContent)
                            }
                            HostsOverlayMutationOutcome.SourceChanged -> {
                                return@withContext HostsMutationOutcome.SourceChanged
                            }
                            HostsOverlayMutationOutcome.Failed -> Unit
                        }
                        if (restoreHostsOrFalse(snapshot)) {
                            HostsMutationOutcome.Failed
                        } else {
                            HostsMutationOutcome.RollbackFailed
                        }
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                HostsMutationOutcome.Blocked
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostsMutationOutcome.Failed
            }
            if (outcome is HostsMutationOutcome.Saved) {
                baselineContent = outcome.content
                persistBounded(KEY_BASELINE, outcome.content)
                persistBounded(KEY_DRAFT, outcome.content)
            }
            _uiState.update {
                it.copy(
                    operation = null,
                    content = (outcome as? HostsMutationOutcome.Saved)?.content ?: it.content,
                    hasAuthoritativeBaseline = when (outcome) {
                        is HostsMutationOutcome.Saved -> true
                        HostsMutationOutcome.Blocked -> it.hasAuthoritativeBaseline
                        HostsMutationOutcome.SourceChanged,
                        HostsMutationOutcome.Failed,
                        HostsMutationOutcome.RollbackFailed,
                        -> false
                    },
                    hasUnsavedChanges = outcome !is HostsMutationOutcome.Saved,
                )
            }
            publishResult(
                when (outcome) {
                    is HostsMutationOutcome.Saved -> HostsEditorResult.SAVED
                    HostsMutationOutcome.SourceChanged -> HostsEditorResult.SOURCE_CHANGED
                    HostsMutationOutcome.Failed -> HostsEditorResult.SAVE_FAILED
                    HostsMutationOutcome.RollbackFailed -> HostsEditorResult.ROLLBACK_FAILED
                    HostsMutationOutcome.Blocked -> HostsEditorResult.SAVE_BLOCKED
                },
            )
            runPendingRefresh()
        }
    }

    /** Removes only the persistent systemless override; the live mount changes after reboot. */
    fun resetHostsOverlay(
        confirmed: Boolean = false,
        discardUnsavedChanges: Boolean = false,
    ) {
        val state = _uiState.value
        if (!confirmed ||
            !state.actionsEnabled ||
            (state.hasUnsavedChanges && !discardUnsavedChanges)
        ) return
        _uiState.update {
            it.copy(operation = HostsEditorOperation.RESET)
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val snapshot = hostsRepository.snapshotOverlay()
                        if (snapshot == HostsOverlaySnapshot.Unsafe) return@withContext HostsMutationOutcome.Failed
                        val current = hostsRepository.readEffective(snapshot)
                            ?: return@withContext HostsMutationOutcome.Failed
                        if (current != baselineContent) return@withContext HostsMutationOutcome.SourceChanged
                        when (val mutation = hostsRepository.removeIfUnchanged(snapshot, current)) {
                            is HostsOverlayMutationOutcome.Applied -> {
                                return@withContext HostsMutationOutcome.Saved(mutation.effectiveContent)
                            }
                            HostsOverlayMutationOutcome.SourceChanged -> {
                                return@withContext HostsMutationOutcome.SourceChanged
                            }
                            HostsOverlayMutationOutcome.Failed -> Unit
                        }
                        if (restoreHostsOrFalse(snapshot)) {
                            HostsMutationOutcome.Failed
                        } else {
                            HostsMutationOutcome.RollbackFailed
                        }
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                HostsMutationOutcome.Blocked
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostsMutationOutcome.Failed
            }
            if (outcome is HostsMutationOutcome.Saved) {
                baselineContent = outcome.content
                persistBounded(KEY_BASELINE, outcome.content)
                persistBounded(KEY_DRAFT, outcome.content)
            }
            _uiState.update {
                it.copy(
                    operation = null,
                    content = (outcome as? HostsMutationOutcome.Saved)?.content ?: it.content,
                    hasAuthoritativeBaseline = when (outcome) {
                        is HostsMutationOutcome.Saved -> true
                        HostsMutationOutcome.Blocked -> it.hasAuthoritativeBaseline
                        HostsMutationOutcome.SourceChanged,
                        HostsMutationOutcome.Failed,
                        HostsMutationOutcome.RollbackFailed,
                        -> false
                    },
                    hasUnsavedChanges = outcome !is HostsMutationOutcome.Saved &&
                        it.content != baselineContent,
                )
            }
            publishResult(
                when (outcome) {
                    is HostsMutationOutcome.Saved -> HostsEditorResult.RESET_SAVED
                    HostsMutationOutcome.SourceChanged -> HostsEditorResult.SOURCE_CHANGED
                    HostsMutationOutcome.Failed -> HostsEditorResult.RESET_FAILED
                    HostsMutationOutcome.RollbackFailed -> HostsEditorResult.ROLLBACK_FAILED
                    HostsMutationOutcome.Blocked -> HostsEditorResult.RESET_BLOCKED
                },
            )
            runPendingRefresh()
        }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || _uiState.value.operation != null) return
        refreshAfterOperation = false
        loadHosts(preserveUnsavedDraft = true, clearResult = false)
    }

    private fun restoreHostsOrFalse(snapshot: HostsOverlaySnapshot): Boolean = try {
        hostsRepository.restore(snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun publishResult(result: HostsEditorResult) {
        savedStateHandle[KEY_RESULT] = result.name
        _uiState.update { it.copy(result = result, message = result.toUiText()) }
    }

    private fun restoreBounded(key: String): String? =
        savedStateHandle.restoreBoundedEditorText(key)

    private fun persistBounded(key: String, value: String) {
        savedStateHandle.persistBoundedEditorText(key, value)
    }

    private fun HostsEditorResult.toUiText(): UiText = UiText.resource(
        when (this) {
            HostsEditorResult.READ_FAILED -> R.string.hosts_read_failed
            HostsEditorResult.EMPTY -> R.string.hosts_file_empty
            HostsEditorResult.TOO_LARGE -> R.string.hosts_file_too_large
            HostsEditorResult.INVALID -> R.string.hosts_file_invalid
            HostsEditorResult.SAVED -> R.string.hosts_overlay_saved
            HostsEditorResult.SOURCE_CHANGED -> R.string.hosts_source_changed
            HostsEditorResult.SAVE_FAILED -> R.string.hosts_overlay_save_failed
            HostsEditorResult.ROLLBACK_FAILED -> R.string.hosts_overlay_rollback_failed
            HostsEditorResult.SAVE_BLOCKED -> R.string.hosts_save_blocked
            HostsEditorResult.RESET_SAVED -> R.string.hosts_overlay_reset_saved
            HostsEditorResult.RESET_FAILED -> R.string.hosts_overlay_reset_failed
            HostsEditorResult.RESET_BLOCKED -> R.string.hosts_reset_blocked
        },
    )

    private companion object {
        const val KEY_DRAFT = "hosts_editor_draft"
        const val KEY_BASELINE = "hosts_editor_baseline"
        const val KEY_RESULT = "hosts_editor_result"
    }
}
