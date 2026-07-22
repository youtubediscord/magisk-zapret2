package com.zapret2.app.viewmodel

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.HostlistImportFailure
import com.zapret2.app.data.HostlistImportReader
import com.zapret2.app.data.HostlistImportValidation
import com.zapret2.app.data.HostlistRepository
import com.zapret2.app.data.HostlistWriteOutcome
import com.zapret2.app.data.ModuleMutationCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HostlistUiModel(val filename: String, val entryCount: Int, val sizeBytes: Long)

enum class HostlistsLoadError {
    ROOT_COMMAND_FAILED,
}

enum class HostlistImportResult(@StringRes val messageRes: Int) {
    IMPORTED(R.string.hostlists_import_success),
    UPDATED(R.string.hostlists_update_success),
    INVALID_NAME(R.string.hostlists_import_invalid_name),
    TOO_LARGE(R.string.hostlists_import_too_large),
    INVALID_ENCODING(R.string.hostlists_import_invalid_encoding),
    INVALID_CONTENT(R.string.hostlists_import_invalid_content),
    READ_FAILED(R.string.hostlists_import_read_failed),
    WRITE_FAILED(R.string.hostlists_import_write_failed),
    BLOCKED(R.string.hostlists_import_blocked),
    ROLLBACK_FAILED(R.string.hostlists_import_rollback_failed),
}

data class HostlistsUiState(
    val hostlists: List<HostlistUiModel> = emptyList(),
    val totalEntries: Int = 0,
    val totalFiles: Int = 0,
    val hasAuthoritativeCatalog: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loadError: HostlistsLoadError? = null,
    val isImporting: Boolean = false,
    val importResult: HostlistImportResult? = null,
) {
    val canReloadCatalog: Boolean
        get() = !isLoading && !isRefreshing && !isImporting
    val canStartCatalogOperation: Boolean
        get() = canReloadCatalog && hasAuthoritativeCatalog && loadError == null
    val canOpenHostlist: Boolean
        get() = canStartCatalogOperation
}

@HiltViewModel
class HostlistsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val hostlistRepository: HostlistRepository = HostlistRepository(),
    private val importReader: HostlistImportReader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HostlistsUiState(importResult = restoreImportResult(savedStateHandle)),
    )
    val uiState: StateFlow<HostlistsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var hasEnteredScreen = false
    private var refreshAfterOperation = false
    init { loadData() }

    fun onScreenEntered() {
        val firstEntry = !hasEnteredScreen
        hasEnteredScreen = true
        if (firstEntry) return
        if (!_uiState.value.isLoading && !_uiState.value.isImporting) {
            loadData()
        } else {
            refreshAfterOperation = true
        }
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    fun loadData() {
        if (_uiState.value.isImporting) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, loadError = null) }
        loadJob = viewModelScope.launch {
            val loadResult = try {
                withContext(Dispatchers.IO) {
                    hostlistRepository.listFiles().fold(
                        onSuccess = { records ->
                            HostlistsLoadResult(
                                files = records.map { record ->
                                    HostlistUiModel(
                                        filename = record.fileName,
                                        entryCount = record.entryCount,
                                        sizeBytes = record.sizeBytes,
                                    )
                                },
                            )
                        },
                        onFailure = {
                            HostlistsLoadResult(error = HostlistsLoadError.ROOT_COMMAND_FAILED)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostlistsLoadResult(error = HostlistsLoadError.ROOT_COMMAND_FAILED)
            }
            if (generation != loadGeneration) return@launch
            _uiState.update { current ->
                if (loadResult.error != null) {
                    current.copy(
                        hostlists = emptyList(),
                        totalEntries = 0,
                        totalFiles = 0,
                        hasAuthoritativeCatalog = false,
                        isLoading = false,
                        isRefreshing = false,
                        loadError = loadResult.error,
                    )
                } else {
                    current.copy(
                        hostlists = loadResult.files,
                        totalEntries = loadResult.files.sumOf { it.entryCount },
                        totalFiles = loadResult.files.size,
                        hasAuthoritativeCatalog = true,
                        isLoading = false,
                        isRefreshing = false,
                        loadError = null,
                    )
                }
            }
            loadJob = null
            runPendingRefresh()
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (!state.canReloadCatalog) return
        _uiState.update { it.copy(isRefreshing = true) }
        loadData()
    }

    fun importHostlist(uri: Uri) {
        if (!_uiState.value.canStartCatalogOperation) return
        _uiState.update { it.copy(isImporting = true, importResult = null) }
        savedStateHandle.remove<String>(KEY_IMPORT_RESULT)
        viewModelScope.launch {
            val result = try {
                val validation = withContext(Dispatchers.IO) { importReader.readAndValidate(uri) }
                when (validation) {
                    is HostlistImportValidation.Failure -> validation.reason.toImportResult()
                    is HostlistImportValidation.Valid -> withContext(Dispatchers.IO) {
                        try {
                            ModuleMutationCoordinator.withNonCancellableMutation {
                                val target = "${HostlistRepository.LISTS_DIR}/${validation.fileName}"
                                if (!hostlistRepository.ensureDirectory()) {
                                    HostlistImportResult.WRITE_FAILED
                                } else {
                                    val writeOutcome = hostlistRepository.writeWithRollback(
                                        target,
                                        validation.content,
                                        "Z2_HOSTLIST_IMPORT",
                                    )
                                    when (writeOutcome) {
                                        is HostlistWriteOutcome.Written ->
                                            if (writeOutcome.replacedExisting) {
                                                HostlistImportResult.UPDATED
                                            } else {
                                                HostlistImportResult.IMPORTED
                                            }
                                        HostlistWriteOutcome.Failed ->
                                            HostlistImportResult.WRITE_FAILED
                                        HostlistWriteOutcome.RollbackFailed ->
                                            HostlistImportResult.ROLLBACK_FAILED
                                    }
                                }
                            }
                        } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                            HostlistImportResult.BLOCKED
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            HostlistImportResult.WRITE_FAILED
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HostlistImportResult.READ_FAILED
            }
            savedStateHandle[KEY_IMPORT_RESULT] = result.name
            _uiState.update { it.copy(isImporting = false, importResult = result) }
            val refreshRequested = refreshAfterOperation
            refreshAfterOperation = false
            if (result == HostlistImportResult.IMPORTED ||
                result == HostlistImportResult.UPDATED ||
                result == HostlistImportResult.ROLLBACK_FAILED ||
                refreshRequested
            ) {
                loadData()
            }
        }
    }

    fun clearImportResult() {
        savedStateHandle.remove<String>(KEY_IMPORT_RESULT)
        _uiState.update { it.copy(importResult = null) }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || _uiState.value.isLoading || _uiState.value.isImporting) return
        refreshAfterOperation = false
        loadData()
    }

    private data class HostlistsLoadResult(
        val files: List<HostlistUiModel> = emptyList(),
        val error: HostlistsLoadError? = null,
    )

    private companion object {
        const val KEY_IMPORT_RESULT = "hostlists_import_result"
    }
}

internal fun restoreImportResult(savedStateHandle: SavedStateHandle): HostlistImportResult? =
    savedStateHandle.restoreEnumNameOrRemove("hostlists_import_result")

private fun HostlistImportFailure.toImportResult(): HostlistImportResult = when (this) {
    HostlistImportFailure.INVALID_NAME -> HostlistImportResult.INVALID_NAME
    HostlistImportFailure.TOO_LARGE -> HostlistImportResult.TOO_LARGE
    HostlistImportFailure.INVALID_ENCODING -> HostlistImportResult.INVALID_ENCODING
    HostlistImportFailure.INVALID_CONTENT -> HostlistImportResult.INVALID_CONTENT
    HostlistImportFailure.READ_FAILED -> HostlistImportResult.READ_FAILED
}
