package com.zapret2.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.data.PresetDurableOutcome
import com.zapret2.app.data.PresetMutationOutcome
import com.zapret2.app.data.PresetProfileDocument
import com.zapret2.app.data.ProfileListEntry
import com.zapret2.app.data.ProfileMutationResult
import com.zapret2.app.data.ProfileRepository
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.ServiceEventSource
import com.zapret2.app.data.StrategyCatalogEntry
import com.zapret2.app.R
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

data class ProfileSelectorTarget(
    val profileIndex: Int,
    val selectorIndex: Int,
)

data class ProfilesUiState(
    val document: PresetProfileDocument? = null,
    val isLoading: Boolean = false,
    val error: Boolean = false,
    val message: UiText? = null,
    val strategyProfileIndex: Int? = null,
    val strategies: List<StrategyCatalogEntry> = emptyList(),
    val renameProfileIndex: Int? = null,
    val renameDraft: String = "",
    val selectorTarget: ProfileSelectorTarget? = null,
    val listEntries: List<ProfileListEntry> = emptyList(),
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val serviceEventBus: ServiceEventBus,
) : ViewModel() {
    private val busy = AtomicBoolean(false)
    private val initialLoadRequested = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    fun ensureLoaded() {
        if (initialLoadRequested.compareAndSet(false, true)) load()
    }

    fun load() {
        if (!busy.compareAndSet(false, true)) return
        _uiState.update { it.copy(isLoading = true, error = false) }
        viewModelScope.launch {
            try {
                val document = repository.loadActive()
                _uiState.update {
                    it.copy(document = document, isLoading = false, error = document == null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, error = true) }
            } finally {
                busy.set(false)
            }
        }
    }

    fun setEnabled(profileIndex: Int, enabled: Boolean) = mutate { document ->
        repository.setEnabled(document, profileIndex, enabled)
    }

    fun openRename(profileIndex: Int) {
        if (busy.get()) return
        val profile = _uiState.value.document?.profiles?.getOrNull(profileIndex) ?: return
        _uiState.update { it.copy(renameProfileIndex = profileIndex, renameDraft = profile.name) }
    }

    fun updateRenameDraft(value: String) {
        _uiState.update { it.copy(renameDraft = value.take(MAX_PROFILE_NAME_CHARS)) }
    }

    fun closeRename() {
        if (busy.get()) return
        _uiState.update { it.copy(renameProfileIndex = null, renameDraft = "") }
    }

    fun saveRename() {
        val state = _uiState.value
        val index = state.renameProfileIndex ?: return
        val name = state.renameDraft.trim()
        if (name.isEmpty()) return
        _uiState.update { it.copy(renameProfileIndex = null, renameDraft = "") }
        mutate { repository.rename(it, index, name) }
    }

    fun move(profileIndex: Int, delta: Int) {
        val document = _uiState.value.document ?: return
        val target = profileIndex + delta
        if (target !in document.profiles.indices) return
        mutate { repository.move(it, profileIndex, target) }
    }

    fun openStrategyPicker(profileIndex: Int) {
        val profile = _uiState.value.document?.profiles?.getOrNull(profileIndex) ?: return
        val scope = profile.catalogScope ?: run {
            _uiState.update { it.copy(message = UiText.resource(R.string.profiles_scope_ambiguous)) }
            return
        }
        if (!busy.compareAndSet(false, true)) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val items = repository.loadStrategies(scope, _uiState.value.document?.declaredBlobs.orEmpty())
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        strategyProfileIndex = profileIndex.takeIf { items != null },
                        strategies = items.orEmpty(),
                        message = if (items == null) UiText.resource(R.string.profiles_catalog_unavailable) else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        strategyProfileIndex = null,
                        strategies = emptyList(),
                        message = UiText.resource(R.string.profiles_catalog_unavailable),
                    )
                }
            } finally {
                busy.set(false)
            }
        }
    }

    fun openSelectorPicker(profileIndex: Int, selectorIndex: Int) {
        val profile = _uiState.value.document?.profiles?.getOrNull(profileIndex) ?: return
        val selector = profile.selectors.getOrNull(selectorIndex) ?: return
        if (!busy.compareAndSet(false, true)) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val items = repository.loadListEntries(selector)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectorTarget = ProfileSelectorTarget(profileIndex, selectorIndex).takeIf { items != null },
                        listEntries = items.orEmpty(),
                        message = if (items == null) UiText.resource(R.string.profiles_lists_unavailable) else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectorTarget = null,
                        listEntries = emptyList(),
                        message = UiText.resource(R.string.profiles_lists_unavailable),
                    )
                }
            } finally {
                busy.set(false)
            }
        }
    }

    fun closeSelectorPicker() {
        if (busy.get()) return
        _uiState.update { it.copy(selectorTarget = null, listEntries = emptyList()) }
    }

    fun selectList(entry: ProfileListEntry) {
        val target = _uiState.value.selectorTarget ?: return
        _uiState.update { it.copy(selectorTarget = null, listEntries = emptyList()) }
        mutate {
            repository.replaceSelector(
                it,
                target.profileIndex,
                target.selectorIndex,
                entry.relativePath,
            )
        }
    }

    fun closeStrategyPicker() {
        if (busy.get()) return
        _uiState.update { it.copy(strategyProfileIndex = null, strategies = emptyList()) }
    }

    fun selectStrategy(strategy: StrategyCatalogEntry) {
        val index = _uiState.value.strategyProfileIndex ?: return
        _uiState.update { it.copy(strategyProfileIndex = null, strategies = emptyList()) }
        mutate { repository.replaceStrategy(it, index, strategy) }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun mutate(block: suspend (PresetProfileDocument) -> ProfileMutationResult) {
        val document = _uiState.value.document ?: return
        if (!busy.compareAndSet(false, true)) return
        _uiState.update { it.copy(isLoading = true, message = null) }
        viewModelScope.launch {
            try {
                val result = block(document)
                val outcome = result.outcome
                if (outcome.durable in setOf(PresetDurableOutcome.APPLIED, PresetDurableOutcome.SAVED_AND_APPLIED)) {
                    serviceEventBus.notifyServiceRestarted(ServiceEventSource.PROFILES)
                }
                val refreshed = when {
                    outcome.durable in SUCCESS_OUTCOMES -> result.publishedDocument
                    outcome == PresetMutationOutcome.SourceChanged -> repository.loadActive()
                    else -> document
                }
                _uiState.update {
                    it.copy(
                        document = refreshed,
                        isLoading = false,
                        error = refreshed == null,
                        message = outcomeMessage(outcome),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false, message = UiText.resource(R.string.profiles_save_failed)) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun outcomeMessage(outcome: PresetMutationOutcome): UiText = when (outcome) {
        PresetMutationOutcome.Saved -> UiText.resource(R.string.profiles_saved_stopped)
        PresetMutationOutcome.SavedAndApplied, PresetMutationOutcome.Applied ->
            UiText.resource(R.string.profiles_applied)
        PresetMutationOutcome.SourceChanged -> UiText.resource(R.string.profiles_source_changed)
        PresetMutationOutcome.RestartFailedRolledBack ->
            UiText.resource(R.string.profiles_restart_failed_rolled_back)
        PresetMutationOutcome.WriteFailedRolledBack ->
            UiText.resource(R.string.profiles_write_failed_rolled_back)
        PresetMutationOutcome.RollbackFailed -> UiText.resource(R.string.profiles_rollback_failed)
        PresetMutationOutcome.IoFailed -> UiText.resource(R.string.profiles_io_failed)
        PresetMutationOutcome.Blocked -> UiText.resource(R.string.profiles_blocked)
        is PresetMutationOutcome.Rejected ->
            UiText.resource(R.string.profiles_rejected, outcome.issue.wireCode)
    }

    private companion object {
        const val MAX_PROFILE_NAME_CHARS = 200
        val SUCCESS_OUTCOMES = setOf(
            PresetDurableOutcome.SAVED,
            PresetDurableOutcome.SAVED_AND_APPLIED,
            PresetDurableOutcome.APPLIED,
        )
    }
}
