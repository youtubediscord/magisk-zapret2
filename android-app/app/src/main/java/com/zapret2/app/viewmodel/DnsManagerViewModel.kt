package com.zapret2.app.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.HostsIniData
import com.zapret2.app.data.HostsIniParser
import com.zapret2.app.data.HostsOverlayRepository
import com.zapret2.app.data.HostsOverlayMutationOutcome
import com.zapret2.app.data.HostsOverlaySnapshot
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.RuntimeConfigMutationResult
import com.zapret2.app.data.RuntimeConfigSectionReadResult
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.diagnosticText
import com.zapret2.app.data.diagnosticTextOrNull
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

enum class DnsManagerOperation { LOAD, APPLY, RESET }

data class DnsManagerUiState(
    val hostsData: HostsIniData? = null,
    val selectedPresetIndex: Int = 0,
    val selectedDnsServices: Set<String> = emptySet(),
    val selectedDirectServices: Set<String> = emptySet(),
    val operation: DnsManagerOperation? = null,
    val loadingText: UiText? = null,
    val loadError: UiText? = null,
    val message: UiText? = null,
) {
    val isLoading: Boolean
        get() = operation != null
    val canEditSelection: Boolean
        get() = hostsData != null && operation == null && loadError == null
}

@HiltViewModel
class DnsManagerViewModel @Inject constructor(
    private val hostsRepository: HostsOverlayRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsManagerUiState())
    val uiState: StateFlow<DnsManagerUiState> = _uiState.asStateFlow()
    private var hasEnteredScreen = false
    private var refreshAfterOperation = false

    private sealed interface ApplyOutcome {
        data object Failed : ApplyOutcome
        data object CatalogChanged : ApplyOutcome
        data object SourceChanged : ApplyOutcome
        data object RollbackFailed : ApplyOutcome
        data object SavedForReboot : ApplyOutcome
        data object Blocked : ApplyOutcome
        data class ModuleFailed(val diagnostic: String) : ApplyOutcome
    }

    init {
        loadData()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun onScreenEntered() {
        val firstEntry = !hasEnteredScreen
        hasEnteredScreen = true
        if (firstEntry) return
        if (_uiState.value.operation == null) loadData() else refreshAfterOperation = true
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    fun loadData() {
        if (_uiState.value.operation != null) return
        _uiState.update {
            it.copy(
                operation = DnsManagerOperation.LOAD,
                loadingText = UiText.resource(R.string.dns_loading_hosts),
                loadError = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { HostsIniParser.parse() }
                val data = result.data
                if (data == null) {
                    _uiState.update {
                        it.copy(
                            hostsData = null,
                            selectedPresetIndex = 0,
                            selectedDnsServices = emptySet(),
                            selectedDirectServices = emptySet(),
                            operation = null,
                            loadingText = null,
                            loadError = UiText.resource(R.string.dns_load_error_body),
                        )
                    }
                    return@launch
                }

                val saved = when (
                    val runtime = withContext(Dispatchers.IO) {
                        RuntimeConfigStore.readDnsManager()
                    }
                ) {
                    is RuntimeConfigSectionReadResult.Valid -> runtime.values
                    is RuntimeConfigSectionReadResult.ConfigUnavailable ->
                        error(runtime.config.diagnosticText())
                    is RuntimeConfigSectionReadResult.Malformed ->
                        error("runtime.ini: MALFORMED_SECTION: ${runtime.sectionName}")
                }
                val knownDns = data.dnsServices.mapTo(mutableSetOf()) { it.name }
                val knownDirect = data.directServices.mapTo(mutableSetOf()) { it.name }
                val dns = saved["selected_dns"]
                    ?.split("|")
                    ?.filterTo(mutableSetOf()) { it.isNotEmpty() && it in knownDns }
                    ?: emptySet()
                val direct = saved["selected_direct"]
                    ?.split("|")
                    ?.filterTo(mutableSetOf()) { it.isNotEmpty() && it in knownDirect }
                    ?: emptySet()
                val presetIndex = (saved["dns_preset_index"]?.toIntOrNull() ?: 0)
                    .takeIf { it in data.dnsPresets.indices } ?: 0

                _uiState.update {
                    it.copy(
                        hostsData = data,
                        selectedPresetIndex = presetIndex,
                        selectedDnsServices = dns,
                        selectedDirectServices = direct,
                        operation = null,
                        loadingText = null,
                        loadError = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        hostsData = null,
                        selectedPresetIndex = 0,
                        selectedDnsServices = emptySet(),
                        selectedDirectServices = emptySet(),
                        operation = null,
                        loadingText = null,
                        loadError = UiText.Dynamic(
                            error.message ?: "runtime.ini: UNAVAILABLE: ${error.javaClass.simpleName}",
                        ),
                    )
                }
            } finally {
                runPendingRefresh()
            }
        }
    }

    fun selectPreset(index: Int) {
        val state = _uiState.value
        if (!state.canEditSelection) return
        val data = state.hostsData ?: return
        if (index in data.dnsPresets.indices) {
            _uiState.update { it.copy(selectedPresetIndex = index) }
        }
    }

    fun toggleDnsService(name: String) {
        val state = _uiState.value
        if (!state.canEditSelection || state.hostsData?.dnsServices?.none { it.name == name } != false) return
        _uiState.update {
            val selected = it.selectedDnsServices.toMutableSet()
            if (name in selected) selected.remove(name) else selected.add(name)
            it.copy(selectedDnsServices = selected)
        }
    }

    fun toggleDirectService(name: String) {
        val state = _uiState.value
        if (!state.canEditSelection || state.hostsData?.directServices?.none { it.name == name } != false) return
        _uiState.update {
            val selected = it.selectedDirectServices.toMutableSet()
            if (name in selected) selected.remove(name) else selected.add(name)
            it.copy(selectedDirectServices = selected)
        }
    }

    fun selectAllDns(checked: Boolean) {
        val state = _uiState.value
        if (!state.canEditSelection) return
        val data = state.hostsData ?: return
        _uiState.update {
            it.copy(
                selectedDnsServices = if (checked) data.dnsServices.map { service ->
                    service.name
                }.toSet() else emptySet(),
            )
        }
    }

    fun selectAllDirect(checked: Boolean) {
        val state = _uiState.value
        if (!state.canEditSelection) return
        val data = state.hostsData ?: return
        _uiState.update {
            it.copy(
                selectedDirectServices = if (checked) data.directServices.map { service ->
                    service.name
                }.toSet() else emptySet(),
            )
        }
    }

    fun applyDns() {
        val state = _uiState.value
        if (!state.canEditSelection) return
        val data = state.hostsData ?: return
        val presetIndex = state.selectedPresetIndex
        if (presetIndex !in data.dnsPresets.indices) {
            _uiState.update { it.copy(message = UiText.resource(R.string.dns_invalid_preset)) }
            return
        }
        val selectedDns = state.selectedDnsServices
            .intersect(data.dnsServices.map { it.name }.toSet())
        val selectedDirect = state.selectedDirectServices
            .intersect(data.directServices.map { it.name }.toSet())
        _uiState.update {
            it.copy(
                operation = DnsManagerOperation.APPLY,
                loadingText = UiText.resource(R.string.dns_applying),
            )
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val liveData = HostsIniParser.parse().data
                            ?: return@withContext ApplyOutcome.Failed
                        if (liveData != data) return@withContext ApplyOutcome.CatalogChanged
                        val previousOverlay = hostsRepository.snapshotOverlay()
                        if (previousOverlay == HostsOverlaySnapshot.Unsafe) {
                            return@withContext ApplyOutcome.Failed
                        }
                        val current = hostsRepository.readEffective(previousOverlay)
                            ?: return@withContext ApplyOutcome.Failed

                        val block = HostsIniParser.generateHostsBlock(
                            data,
                            presetIndex,
                            selectedDns,
                            selectedDirect,
                        )
                        val merged = HostsIniParser.smartMerge(current, block)
                        if (!hostsRepository.isContentSizeAllowed(merged)) {
                            return@withContext ApplyOutcome.Failed
                        }
                        when (hostsRepository.writeIfUnchanged(previousOverlay, current, merged)) {
                            is HostsOverlayMutationOutcome.Applied -> Unit
                            HostsOverlayMutationOutcome.SourceChanged -> {
                                return@withContext ApplyOutcome.SourceChanged
                            }
                            HostsOverlayMutationOutcome.Failed -> {
                                val restored = restoreHostsOrFalse(previousOverlay)
                                return@withContext if (restored) ApplyOutcome.Failed else ApplyOutcome.RollbackFailed
                            }
                        }
                        val configResult = try {
                            RuntimeConfigStore.upsertDnsManagerValues(
                                mapOf(
                                    "dns_preset_index" to presetIndex.toString(),
                                    "selected_dns" to selectedDns.sorted().joinToString("|"),
                                    "selected_direct" to selectedDirect.sorted().joinToString("|"),
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (configResult?.isSuccess != true) {
                            val restored = restoreHostsOrFalse(previousOverlay)
                            val diagnostic = configResult?.diagnosticTextOrNull()
                            return@withContext when {
                                !restored -> ApplyOutcome.RollbackFailed
                                diagnostic != null -> ApplyOutcome.ModuleFailed(diagnostic)
                                else -> ApplyOutcome.Failed
                            }
                        }
                        ApplyOutcome.SavedForReboot
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                ApplyOutcome.Blocked
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ApplyOutcome.Failed
            }
            finishOperation(
                outcome = outcome,
                successMessage = R.string.dns_saved_reboot,
                failureMessage = R.string.dns_save_failed,
                blockedMessage = R.string.dns_apply_blocked,
            )
            if (outcome == ApplyOutcome.CatalogChanged) {
                refreshAfterOperation = false
                loadData()
            } else {
                runPendingRefresh()
            }
        }
    }

    fun resetDns(confirmed: Boolean = false) {
        if (!confirmed || !_uiState.value.canEditSelection) return
        _uiState.update {
            it.copy(
                operation = DnsManagerOperation.RESET,
                loadingText = UiText.resource(R.string.dns_resetting),
            )
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val previousOverlay = hostsRepository.snapshotOverlay()
                        if (previousOverlay == HostsOverlaySnapshot.Unsafe) {
                            return@withContext ApplyOutcome.Failed
                        }
                        val current = hostsRepository.readEffective(previousOverlay)
                            ?: return@withContext ApplyOutcome.Failed

                        when (
                            hostsRepository.writeIfUnchanged(
                                previousOverlay,
                                current,
                                HostsIniParser.removeZapretBlock(current),
                            )
                        ) {
                            is HostsOverlayMutationOutcome.Applied -> Unit
                            HostsOverlayMutationOutcome.SourceChanged -> {
                                return@withContext ApplyOutcome.SourceChanged
                            }
                            HostsOverlayMutationOutcome.Failed -> {
                                val restored = restoreHostsOrFalse(previousOverlay)
                                return@withContext if (restored) ApplyOutcome.Failed else ApplyOutcome.RollbackFailed
                            }
                        }
                        val configResult: RuntimeConfigMutationResult? = try {
                            RuntimeConfigStore.upsertDnsManagerValues(
                                values = emptyMap(),
                                removeKeys = setOf(
                                    "dns_preset_index",
                                    "selected_dns",
                                    "selected_direct",
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (configResult?.isSuccess != true) {
                            val restored = restoreHostsOrFalse(previousOverlay)
                            val diagnostic = configResult?.diagnosticTextOrNull()
                            return@withContext when {
                                !restored -> ApplyOutcome.RollbackFailed
                                diagnostic != null -> ApplyOutcome.ModuleFailed(diagnostic)
                                else -> ApplyOutcome.Failed
                            }
                        }
                        ApplyOutcome.SavedForReboot
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                ApplyOutcome.Blocked
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ApplyOutcome.Failed
            }
            finishOperation(
                outcome = outcome,
                successMessage = R.string.dns_reset_saved_reboot,
                failureMessage = R.string.dns_reset_failed,
                blockedMessage = R.string.dns_reset_blocked,
                clearSelectionOnSuccess = true,
            )
            runPendingRefresh()
        }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || _uiState.value.operation != null) return
        refreshAfterOperation = false
        loadData()
    }

    private fun restoreHostsOrFalse(snapshot: HostsOverlaySnapshot): Boolean = try {
        hostsRepository.restore(snapshot)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun finishOperation(
        outcome: ApplyOutcome,
        @StringRes successMessage: Int,
        @StringRes failureMessage: Int,
        @StringRes blockedMessage: Int,
        clearSelectionOnSuccess: Boolean = false,
    ) {
        val stateUncertain = outcome == ApplyOutcome.RollbackFailed
        val catalogChanged = outcome == ApplyOutcome.CatalogChanged
        val clearSelection = stateUncertain || catalogChanged ||
            clearSelectionOnSuccess && outcome == ApplyOutcome.SavedForReboot
        _uiState.update { state ->
            state.copy(
                hostsData = if (stateUncertain || catalogChanged) null else state.hostsData,
                selectedPresetIndex = if (clearSelection) 0 else state.selectedPresetIndex,
                selectedDnsServices = if (clearSelection) emptySet() else state.selectedDnsServices,
                selectedDirectServices = if (clearSelection) emptySet() else state.selectedDirectServices,
                operation = null,
                loadingText = null,
                loadError = if (stateUncertain) {
                    UiText.resource(R.string.dns_load_error_body)
                } else {
                    state.loadError
                },
                message = if (outcome is ApplyOutcome.ModuleFailed) {
                    UiText.Dynamic(outcome.diagnostic)
                } else {
                    UiText.resource(
                        when (outcome) {
                        ApplyOutcome.SavedForReboot -> successMessage
                        ApplyOutcome.Failed -> failureMessage
                        ApplyOutcome.CatalogChanged -> R.string.dns_catalog_changed
                        ApplyOutcome.SourceChanged -> R.string.dns_hosts_changed
                        ApplyOutcome.RollbackFailed -> R.string.dns_rollback_failed
                        ApplyOutcome.Blocked -> blockedMessage
                            is ApplyOutcome.ModuleFailed -> error("handled above")
                        },
                    )
                },
            )
        }
    }

}
