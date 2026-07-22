package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.CommandLineRead
import com.zapret2.app.data.CommandLineRepository
import com.zapret2.app.data.CommandLineSnapshot
import com.zapret2.app.data.CommandLineValidation
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.RuntimeConfigRollbackException
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.data.canonicalProtectedText
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

enum class ConfigEditorOperation { LOAD, SAVE, SAVE_AND_RESTART }

enum class ConfigEditorResult {
    LOAD_FAILED,
    EMPTY,
    TOO_LARGE,
    INVALID_COMMAND,
    SAVE_FAILED,
    ROLLBACK_FAILED,
    SAVED,
    SAVED_AND_RESTARTED,
    SAVED_RESTART_FAILED,
    SAVE_BLOCKED,
    SOURCE_CHANGED,
}

data class ConfigEditorUiState(
    val commandText: String = "",
    val commandFileName: String = "cmdline.txt",
    val operation: ConfigEditorOperation? = null,
    val actionsEnabled: Boolean = true,
    val hasAuthoritativeBinding: Boolean = false,
    val bindingLoadAttempted: Boolean = false,
    val bindingConflict: Boolean = false,
    val showModeDialog: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val result: ConfigEditorResult? = null,
    val message: UiText? = null,
) {
    val isLoading: Boolean
        get() = operation != null
}

private sealed interface ConfigSaveOutcome {
    data class Saved(
        val restarted: Boolean,
        val manualSnapshot: CommandLineSnapshot.Present,
        val editorText: String,
    ) : ConfigSaveOutcome
    data object Failed : ConfigSaveOutcome
    data object Invalid : ConfigSaveOutcome
    data object RollbackFailed : ConfigSaveOutcome
    data object Blocked : ConfigSaveOutcome
    data object SourceChanged : ConfigSaveOutcome
}

private data class LoadedCommandLine(
    val fileName: String,
    val text: String,
    val manualSnapshot: CommandLineSnapshot,
)

private class CommandBindingChangedException : IllegalStateException()

@HiltViewModel
class ConfigEditorViewModel @Inject constructor(
    private val serviceEventBus: ServiceEventBus,
    private val savedStateHandle: SavedStateHandle,
    private val commandLineRepository: CommandLineRepository = CommandLineRepository(),
) : ViewModel() {

    private val restoredDraft = restoreBounded(KEY_DRAFT)
    private val restoredBaseline = restoreBounded(KEY_BASELINE)
    private val restoredCommandFile = savedStateHandle.restoreTypedOrRemove<String>(KEY_COMMAND_FILE)
        ?.takeIf(commandLineRepository::isSafeFileName)
    private val restoredEditorState = restoredDraft != null && restoredBaseline != null && restoredCommandFile != null
    private var activeCommandFile = restoredCommandFile ?: DEFAULT_COMMAND_FILE
    private var baselineCommandText = restoredBaseline.orEmpty()
    private var baselineManualSnapshot: CommandLineSnapshot? = null
    private var hasEnteredScreen = false
    private var refreshAfterOperation = false
    private val restoredResult = savedStateHandle.restoreEnumNameOrRemove<ConfigEditorResult>(KEY_RESULT)
    private val _uiState = MutableStateFlow(
        ConfigEditorUiState(
            commandText = restoredDraft.takeIf { restoredEditorState }.orEmpty(),
            commandFileName = activeCommandFile,
            hasAuthoritativeBinding = false,
            showModeDialog = false,
            hasUnsavedChanges = restoredEditorState && restoredDraft != baselineCommandText,
            result = restoredResult,
            message = restoredResult?.toUiText(),
        ),
    )
    val uiState: StateFlow<ConfigEditorUiState> = _uiState.asStateFlow()

    init {
        if (!restoredEditorState) {
            savedStateHandle.remove<String>(KEY_DRAFT)
            savedStateHandle.remove<String>(KEY_BASELINE)
            savedStateHandle.remove<String>(KEY_COMMAND_FILE)
            savedStateHandle.remove<Boolean>(KEY_MODE_DIALOG)
            loadCommandLine()
        } else {
            savedStateHandle.remove<Boolean>(KEY_MODE_DIALOG)
        }
    }

    fun clearMessage() {
        savedStateHandle.remove<String>(KEY_RESULT)
        _uiState.update { it.copy(result = null, message = null) }
    }

    fun loadCommandLine(discardUnsavedChanges: Boolean = false) {
        if (_uiState.value.hasUnsavedChanges && !discardUnsavedChanges) return
        loadCommandLine(preserveUnsavedDraft = false, clearResult = true)
    }

    fun revalidateCommandLine() {
        loadCommandLine(preserveUnsavedDraft = true, clearResult = true)
    }

    fun onScreenEntered() {
        val firstEntry = !hasEnteredScreen
        hasEnteredScreen = true
        if (firstEntry && !restoredEditorState) return
        if (_uiState.value.operation == null) {
            loadCommandLine(preserveUnsavedDraft = true, clearResult = false)
        } else {
            refreshAfterOperation = true
        }
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    private fun loadCommandLine(preserveUnsavedDraft: Boolean, clearResult: Boolean) {
        if (_uiState.value.operation != null) return
        val previousFile = activeCommandFile
        val draft = _uiState.value.commandText.takeIf {
            preserveUnsavedDraft && _uiState.value.hasUnsavedChanges
        }
        _uiState.update {
            it.copy(
                operation = ConfigEditorOperation.LOAD,
                actionsEnabled = false,
                hasAuthoritativeBinding = false,
                bindingLoadAttempted = true,
                bindingConflict = false,
                showModeDialog = false,
                result = if (clearResult) null else it.result,
                message = if (clearResult) null else it.message,
            )
        }
        savedStateHandle[KEY_MODE_DIALOG] = false
        if (clearResult) savedStateHandle.remove<String>(KEY_RESULT)
        viewModelScope.launch {
            val loadResult = try {
                withContext(Dispatchers.IO) {
                    val fileName = RuntimeConfigStore.readCoreValue("custom_cmdline_file")
                        ?.takeIf(commandLineRepository::isSafeFileName)
                        ?: return@withContext Result.failure<LoadedCommandLine>(IllegalStateException())
                    if (draft != null && fileName != previousFile) {
                        return@withContext Result.failure<LoadedCommandLine>(CommandBindingChangedException())
                    }
                    val binding = commandLineRepository.readBinding(fileName)
                        ?: return@withContext Result.failure<LoadedCommandLine>(IllegalStateException())
                    when (val read = binding.read) {
                        is CommandLineRead.Content -> Result.success(
                            LoadedCommandLine(fileName, formatForEditor(read.value), binding.manualSnapshot),
                        )
                        CommandLineRead.Empty -> Result.success(
                            LoadedCommandLine(fileName, "", binding.manualSnapshot),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            loadResult.fold(
                onSuccess = { loaded ->
                    activeCommandFile = loaded.fileName
                    savedStateHandle[KEY_COMMAND_FILE] = loaded.fileName
                    baselineCommandText = loaded.text
                    baselineManualSnapshot = loaded.manualSnapshot
                    val displayedText = draft ?: loaded.text
                    persistBounded(KEY_BASELINE, loaded.text)
                    persistBounded(KEY_DRAFT, displayedText)
                    _uiState.update {
                        it.copy(
                            commandText = displayedText,
                            commandFileName = loaded.fileName,
                            operation = null,
                            actionsEnabled = true,
                            hasAuthoritativeBinding = true,
                            bindingConflict = false,
                            hasUnsavedChanges = displayedText != loaded.text,
                        )
                    }
                },
                onFailure = { failure ->
                    publishResult(
                        if (failure is CommandBindingChangedException) {
                            ConfigEditorResult.SOURCE_CHANGED
                        } else {
                            ConfigEditorResult.LOAD_FAILED
                        },
                    )
                    _uiState.update { state ->
                        state.copy(
                            operation = null,
                            actionsEnabled = true,
                            hasAuthoritativeBinding = false,
                            bindingConflict = failure is CommandBindingChangedException,
                            showModeDialog = false,
                        )
                    }
                },
            )
            runPendingRefresh()
        }
    }

    fun updateCommandText(text: String) {
        if (_uiState.value.operation != null || !_uiState.value.hasAuthoritativeBinding) return
        if (
            !fitsNormalizedEditorBudget(
                value = text,
                maxBytes = CommandLineRepository.MAX_COMMAND_BYTES,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            )
        ) {
            publishResult(ConfigEditorResult.TOO_LARGE)
            return
        }
        persistBounded(KEY_DRAFT, text)
        _uiState.update {
            val clearValidationError = it.result == ConfigEditorResult.EMPTY ||
                it.result == ConfigEditorResult.TOO_LARGE ||
                it.result == ConfigEditorResult.INVALID_COMMAND
            it.copy(
                commandText = text,
                hasUnsavedChanges = text != baselineCommandText,
                result = if (clearValidationError) null else it.result,
                message = if (clearValidationError) null else it.message,
            )
        }
    }

    fun discardUnsavedChanges() {
        if (_uiState.value.operation != null) return
        persistBounded(KEY_DRAFT, baselineCommandText)
        savedStateHandle[KEY_MODE_DIALOG] = false
        _uiState.update {
            it.copy(
                commandText = baselineCommandText,
                hasUnsavedChanges = false,
                showModeDialog = false,
            )
        }
    }

    fun saveCommandLine(restart: Boolean = false, forceCmdline: Boolean = false) {
        val state = _uiState.value
        if (state.operation != null || !state.hasAuthoritativeBinding) return
        if (forceCmdline && !restart) return
        if (!restart && !state.hasUnsavedChanges) return
        val expectedManualSnapshot = baselineManualSnapshot ?: return
        val expectedBaseline = baselineCommandText
        val expectedCommandFile = activeCommandFile
        val text = state.commandText.replace("\r\n", "\n").trimEnd('\n', '\r')
        if (text.isBlank()) {
            publishResult(ConfigEditorResult.EMPTY)
            return
        }
        if (!commandLineRepository.isContentSizeAllowed(text)) {
            publishResult(ConfigEditorResult.TOO_LARGE)
            return
        }

        _uiState.update {
            it.copy(
                operation = if (restart) {
                    ConfigEditorOperation.SAVE_AND_RESTART
                } else {
                    ConfigEditorOperation.SAVE
                },
                actionsEnabled = false,
            )
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val previousCore = RuntimeConfigStore.readCore()
                        val configuredFile = previousCore["custom_cmdline_file"]
                            ?.takeIf(commandLineRepository::isSafeFileName)
                            ?: return@withContext ConfigSaveOutcome.Failed
                        if (configuredFile != expectedCommandFile) {
                            return@withContext ConfigSaveOutcome.SourceChanged
                        }
                        val currentBinding = commandLineRepository.readBinding(expectedCommandFile)
                            ?: return@withContext ConfigSaveOutcome.Failed
                        if (!snapshotsMatch(currentBinding.manualSnapshot, expectedManualSnapshot) ||
                            editorText(currentBinding.read) != expectedBaseline
                        ) {
                            return@withContext ConfigSaveOutcome.SourceChanged
                        }
                        val commandSnapshot = currentBinding.manualSnapshot
                        if (commandSnapshot == CommandLineSnapshot.Unsafe) {
                            return@withContext ConfigSaveOutcome.Failed
                        }
                        if (forceCmdline && (
                                previousCore["preset_mode"] == null ||
                                    previousCore["custom_cmdline_file"] == null
                                )
                        ) return@withContext ConfigSaveOutcome.Failed

                        val written = writeCommandLineOrFalse(expectedCommandFile, text)
                        if (!written) {
                            val restored = restoreCommandLineOrFalse(
                                expectedCommandFile,
                                commandSnapshot,
                            )
                            return@withContext if (restored) {
                                ConfigSaveOutcome.Failed
                            } else {
                                ConfigSaveOutcome.RollbackFailed
                            }
                        }
                        val persistedSnapshot = snapshotCommandLineOrNull(expectedCommandFile)
                        if (persistedSnapshot !is CommandLineSnapshot.Present ||
                            canonicalProtectedText(persistedSnapshot.content) != canonicalProtectedText(text)
                        ) {
                            val restored = restoreCommandLineOrFalse(
                                expectedCommandFile,
                                commandSnapshot,
                            )
                            return@withContext if (restored) {
                                ConfigSaveOutcome.Failed
                            } else {
                                ConfigSaveOutcome.RollbackFailed
                            }
                        }
                        val validation = validateCommandLineOrFailed(expectedCommandFile)
                        when (validation) {
                            CommandLineValidation.VALID -> Unit
                            CommandLineValidation.INVALID,
                            CommandLineValidation.FAILED,
                            -> {
                                val restored = restoreCommandLineOrFalse(
                                    expectedCommandFile,
                                    commandSnapshot,
                                )
                                return@withContext when {
                                    !restored -> ConfigSaveOutcome.RollbackFailed
                                    validation == CommandLineValidation.INVALID -> ConfigSaveOutcome.Invalid
                                    else -> ConfigSaveOutcome.Failed
                                }
                            }
                        }

                        if (forceCmdline) {
                            val modeSaved = try {
                                persistRuntimeMutation {
                                    RuntimeConfigStore.setActiveModeValues(
                                        RuntimeConfigStore.CoreSettingsUpdate(
                                            presetMode = "cmdline",
                                            customCmdlineFile = expectedCommandFile,
                                        ),
                                    )
                                }
                            } catch (_: RuntimeConfigRollbackException) {
                                restoreCommandLineOrFalse(expectedCommandFile, commandSnapshot)
                                return@withContext ConfigSaveOutcome.RollbackFailed
                            }
                            if (!modeSaved) {
                                val commandRestored = restoreCommandLineOrFalse(
                                    expectedCommandFile,
                                    commandSnapshot,
                                )
                                val coreRestored = persistRuntimeMutation {
                                    RuntimeConfigStore.setActiveModeValues(
                                        RuntimeConfigStore.CoreSettingsUpdate(
                                            presetMode = previousCore.getValue("preset_mode"),
                                            customCmdlineFile = previousCore.getValue("custom_cmdline_file"),
                                        ),
                                    )
                                }
                                return@withContext if (commandRestored && coreRestored) {
                                    ConfigSaveOutcome.Failed
                                } else {
                                    ConfigSaveOutcome.RollbackFailed
                                }
                            }
                        }
                        ConfigSaveOutcome.Saved(
                            restarted = !restart || persistRuntimeMutation {
                                ServiceLifecycleController.restart().success
                            },
                            manualSnapshot = persistedSnapshot,
                            editorText = formatForEditor(persistedSnapshot.content),
                        )
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                ConfigSaveOutcome.Blocked
            } catch (_: RuntimeConfigRollbackException) {
                ConfigSaveOutcome.RollbackFailed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ConfigSaveOutcome.Failed
            }

            val savedOutcome = outcome as? ConfigSaveOutcome.Saved
            if (savedOutcome != null) {
                baselineCommandText = savedOutcome.editorText
                baselineManualSnapshot = savedOutcome.manualSnapshot
                persistBounded(KEY_BASELINE, savedOutcome.editorText)
                persistBounded(KEY_DRAFT, savedOutcome.editorText)
            }
            val bindingInvalidated = outcome == ConfigSaveOutcome.Failed ||
                outcome == ConfigSaveOutcome.RollbackFailed ||
                outcome == ConfigSaveOutcome.SourceChanged
            _uiState.update {
                it.copy(
                    commandText = savedOutcome?.editorText ?: it.commandText,
                    operation = null,
                    actionsEnabled = true,
                    hasAuthoritativeBinding = !bindingInvalidated,
                    bindingConflict = false,
                    hasUnsavedChanges = if (savedOutcome != null) false else it.commandText != baselineCommandText,
                )
            }
            val result = when (outcome) {
                ConfigSaveOutcome.Failed -> ConfigEditorResult.SAVE_FAILED
                ConfigSaveOutcome.Invalid -> ConfigEditorResult.INVALID_COMMAND
                ConfigSaveOutcome.RollbackFailed -> ConfigEditorResult.ROLLBACK_FAILED
                ConfigSaveOutcome.Blocked -> ConfigEditorResult.SAVE_BLOCKED
                ConfigSaveOutcome.SourceChanged -> ConfigEditorResult.SOURCE_CHANGED
                is ConfigSaveOutcome.Saved -> when {
                    !restart -> ConfigEditorResult.SAVED
                    outcome.restarted -> ConfigEditorResult.SAVED_AND_RESTARTED
                    else -> ConfigEditorResult.SAVED_RESTART_FAILED
                }
            }
            publishResult(result)
            if (result == ConfigEditorResult.SAVED_AND_RESTARTED) {
                serviceEventBus.notifyServiceRestarted()
            }
            runPendingRefresh()
        }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || _uiState.value.operation != null) return
        refreshAfterOperation = false
        loadCommandLine(preserveUnsavedDraft = true, clearResult = false)
    }

    fun showModeDialog() {
        if (_uiState.value.operation != null || !_uiState.value.hasAuthoritativeBinding) return
        savedStateHandle[KEY_MODE_DIALOG] = true
        _uiState.update { it.copy(showModeDialog = true) }
    }

    fun dismissModeDialog() {
        savedStateHandle[KEY_MODE_DIALOG] = false
        _uiState.update { it.copy(showModeDialog = false) }
    }

    suspend fun isCmdlineMode(): Boolean = withContext(Dispatchers.IO) {
        val mode = (RuntimeConfigStore.readCoreValue("preset_mode") ?: "categories").lowercase()
        mode == "cmdline"
    }

    private suspend fun persistRuntimeMutation(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (rollback: RuntimeConfigRollbackException) {
        throw rollback
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun writeCommandLineOrFalse(fileName: String, content: String): Boolean = try {
        commandLineRepository.writeManual(fileName, content)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun snapshotCommandLineOrNull(fileName: String): CommandLineSnapshot? = try {
        commandLineRepository.snapshotManual(fileName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun validateCommandLineOrFailed(fileName: String): CommandLineValidation = try {
        commandLineRepository.validateManual(fileName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CommandLineValidation.FAILED
    }

    private fun restoreCommandLineOrFalse(
        fileName: String,
        snapshot: CommandLineSnapshot,
    ): Boolean = try {
        commandLineRepository.restore(fileName, snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun publishResult(result: ConfigEditorResult) {
        savedStateHandle[KEY_RESULT] = result.name
        _uiState.update { it.copy(result = result, message = result.toUiText()) }
    }

    private fun restoreBounded(key: String): String? =
        savedStateHandle.restoreBoundedEditorText(key)

    private fun persistBounded(key: String, value: String) {
        savedStateHandle.persistBoundedEditorText(key, value)
    }

    private fun formatForEditor(raw: String): String {
        val stripped = stripBinaryPrefix(raw.replace("\r\n", "\n").replace("\r", "\n"))
        if (stripped.isBlank()) return ""
        return if (stripped.contains('\n')) {
            stripped.replace(Regex("\\n[ \t]+"), "\n").trim()
        } else {
            stripped.replace(" --", "\n--")
        }
    }

    private fun editorText(read: CommandLineRead): String? = when (read) {
        is CommandLineRead.Content -> formatForEditor(read.value)
        CommandLineRead.Empty -> ""
    }

    private fun snapshotsMatch(
        current: CommandLineSnapshot,
        expected: CommandLineSnapshot,
    ): Boolean = when {
        current is CommandLineSnapshot.Present && expected is CommandLineSnapshot.Present ->
            canonicalProtectedText(current.content) == canonicalProtectedText(expected.content)
        current == CommandLineSnapshot.Missing && expected == CommandLineSnapshot.Missing -> true
        else -> false
    }

    private fun stripBinaryPrefix(cmdline: String): String {
        val trimmed = cmdline.trimStart()
        if (trimmed.isEmpty()) return ""
        val first = trimmed.substringBefore(' ', trimmed)
        return if (first == "nfqws2" || first.endsWith("/nfqws2")) {
            trimmed.substringAfter(' ', "").trimStart()
        } else {
            trimmed
        }
    }

    private fun ConfigEditorResult.toUiText(): UiText = UiText.resource(
        when (this) {
            ConfigEditorResult.LOAD_FAILED -> R.string.config_load_failed
            ConfigEditorResult.EMPTY -> R.string.config_command_empty
            ConfigEditorResult.TOO_LARGE -> R.string.config_command_too_large
            ConfigEditorResult.INVALID_COMMAND -> R.string.config_command_invalid
            ConfigEditorResult.SAVE_FAILED -> R.string.config_save_failed
            ConfigEditorResult.ROLLBACK_FAILED -> R.string.config_rollback_failed
            ConfigEditorResult.SAVED -> R.string.config_saved
            ConfigEditorResult.SAVED_AND_RESTARTED -> R.string.config_saved_restarted
            ConfigEditorResult.SAVED_RESTART_FAILED -> R.string.config_saved_restart_failed
            ConfigEditorResult.SAVE_BLOCKED -> R.string.config_save_blocked
            ConfigEditorResult.SOURCE_CHANGED -> R.string.config_source_changed
        },
    )

    private companion object {
        const val KEY_DRAFT = "config_editor_draft"
        const val KEY_BASELINE = "config_editor_baseline"
        const val KEY_MODE_DIALOG = "config_editor_mode_dialog"
        const val KEY_RESULT = "config_editor_result"
        const val KEY_COMMAND_FILE = "config_editor_command_file"
        const val DEFAULT_COMMAND_FILE = "cmdline.txt"
    }
}
