package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.MAX_PACKET_COUNT
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.RuntimeConfigRollbackException
import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.StrategyRepository
import com.zapret2.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class CategoryUiModel(
    val key: String,
    val title: String,
    val subtitle: UiText,
    val type: String,           // "tcp", "udp", "voice"
    val strategy: String,
    val strategyDisplayName: UiText,
    val filterMode: String,
    val canSwitchFilter: Boolean
)

enum class StrategyOrderSaveStatus { IDLE, SAVING, SAVED, ERROR }

private enum class StrategyOrderSaveOutcome { SAVED, FAILED, BLOCKED, ROLLBACK_FAILED }

private enum class StrategyConfigOutcome { APPLIED, SAVED_RESTART_FAILED, FAILED, BLOCKED, ROLLBACK_FAILED }

private data class StrategyPickerBaseline(
    val categoryKey: String,
    val categoryType: String,
    val catalogIds: List<String>,
    val displayIds: List<String>,
    val runtimeOrder: List<String>?,
)

private data class StrategyPickerLoad(
    val items: List<StrategyRepository.StrategyDetail>,
    val baseline: StrategyPickerBaseline,
)

private sealed interface StrategyConfigMutation {
    data class Category(
        val categoryKey: String,
        val strategyId: String,
        val filterMode: String,
    ) : StrategyConfigMutation

    data class PacketCount(val value: Int) : StrategyConfigMutation
    data class LogMode(val value: String) : StrategyConfigMutation
}

data class StrategyOrderSaveState(
    val categoryKey: String? = null,
    val status: StrategyOrderSaveStatus = StrategyOrderSaveStatus.IDLE,
    val error: UiText? = null,
)

data class StrategiesUiState(
    val categories: List<CategoryUiModel> = emptyList(),
    val pktCount: String = "20",
    val debugMode: String = "none",
    val isLoading: Boolean = false,
    val loadingText: UiText? = null,
    val loadError: UiText? = null,
    val operationNotice: UiText? = null,
    val pendingOrders: Map<String, List<String>> = emptyMap(),
    val orderSaveState: StrategyOrderSaveState = StrategyOrderSaveState(),
    val pickerCategoryKey: String? = null,
    val pickerItems: List<StrategyRepository.StrategyDetail> = emptyList(),
    val isPickerLoading: Boolean = false,
    val pickerError: UiText? = null,
    val message: UiText? = null,
)

@HiltViewModel
class StrategiesViewModel @Inject constructor(
    private val serviceEventBus: ServiceEventBus,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        StrategiesUiState(pendingOrders = restorePendingOrders()),
    )
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    private var pickerJob: Job? = null
    private var pickerGeneration = 0L
    private var pickerBaseline: StrategyPickerBaseline? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private val mutationInProgress = AtomicBoolean(false)
    private var hasEnteredScreen = false
    private var refreshAfterOperation = false

    init {
        loadConfig()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun publishMessage(message: UiText) {
        _uiState.update { it.copy(message = message) }
    }

    fun loadConfig() {
        if (mutationInProgress.get()) return
        loadConfigInternal()
    }

    fun onScreenEntered() {
        val firstEntry = !hasEnteredScreen
        hasEnteredScreen = true
        if (firstEntry) return
        if (!mutationInProgress.get() && !_uiState.value.isLoading) {
            loadConfigInternal()
        } else {
            refreshAfterOperation = true
        }
    }

    fun onScreenStopped() {
        refreshAfterOperation = false
    }

    private fun loadConfigInternal() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        invalidatePickerLoad()
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingText = UiText.Resource(R.string.strategies_loading),
                loadError = null,
                pickerCategoryKey = null,
                pickerItems = emptyList(),
                isPickerLoading = false,
                pickerError = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val categories = StrategyRepository.readCategories()
                    ?: error("categories.ini is missing, unsafe, or malformed")
                if (!StrategyRepository.validateCategoriesWithRuntimeBuilder()) {
                    error("categories.ini or strategy catalogs fail runtime validation")
                }
                val runtimeCore = withContext(Dispatchers.IO) { RuntimeConfigStore.readCore() }

                val uiModels = categories.map { (key, config) ->
                    val strategyName = config.strategy

                    CategoryUiModel(
                        key = key,
                        title = formatCategoryTitle(key, config.protocol),
                        subtitle = categorySubtitle(config),
                        type = pickerType(config.protocol),
                        strategy = strategyName,
                        strategyDisplayName = formatStrategyDisplay(strategyName),
                        filterMode = config.filterMode,
                        canSwitchFilter = config.canSwitchFilterMode,
                    )
                }

                val pktValue = RuntimeConfigStore.positiveCountOrNull(runtimeCore["pkt_out"])
                    ?.toString()
                    ?: error("runtime.ini has no canonical pkt_out value")
                val logMode = runtimeCore["log_mode"]
                    ?.takeIf { it in setOf("none", "android", "file", "syslog") }
                    ?: error("runtime.ini has no supported log_mode value")

                if (generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        categories = uiModels,
                        pktCount = pktValue,
                        debugMode = logMode,
                        isLoading = false,
                        loadingText = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != loadGeneration) return@launch
                _uiState.update {
                    it.copy(
                        categories = emptyList(),
                        isLoading = false,
                        loadingText = null,
                        loadError = UiText.Resource(R.string.strategies_load_failed),
                        pickerCategoryKey = null,
                        pickerItems = emptyList(),
                        isPickerLoading = false,
                        pickerError = null,
                    )
                }
            } finally {
                if (generation == loadGeneration) {
                    loadJob = null
                    runPendingRefresh()
                }
            }
        }
    }

    fun loadStrategyPicker(categoryKey: String, categoryType: String) {
        val currentState = _uiState.value
        if (mutationInProgress.get() || currentState.isLoading || currentState.loadError != null) return
        val category = currentState.categories.firstOrNull { it.key == categoryKey }
        if (!categoryIdentifierPattern.matches(categoryKey) ||
            categoryType !in setOf("tcp", "udp", "voice", "stun") ||
            category?.type != categoryType
        ) {
            _uiState.update {
                it.copy(
                    pickerCategoryKey = categoryKey,
                    pickerItems = emptyList(),
                    isPickerLoading = false,
                    pickerError = UiText.Resource(R.string.strategies_load_failed),
                )
            }
            return
        }
        val generation = ++pickerGeneration
        pickerJob?.cancel()
        val pendingOrder = _uiState.value.pendingOrders[categoryKey]?.takeIf { it.isNotEmpty() }
        _uiState.update {
            it.copy(
                pickerCategoryKey = categoryKey,
                pickerItems = emptyList(),
                isPickerLoading = true,
                pickerError = null,
            )
        }
        pickerJob = viewModelScope.launch {
            try {
                val pickerLoad = withContext(Dispatchers.IO) {
                    val strategies = StrategyRepository.getStrategyDetails(categoryType)
                    if (strategies.size <= 1) return@withContext null
                    val runtimeOrder = StrategyRepository.getSavedOrder(categoryType)
                    val items = StrategyRepository.applyOrder(
                        strategies,
                        pendingOrder ?: runtimeOrder,
                    )
                    StrategyPickerLoad(
                        items = items,
                        baseline = StrategyPickerBaseline(
                            categoryKey = categoryKey,
                            categoryType = categoryType,
                            catalogIds = strategies.map { it.id },
                            displayIds = items.map { it.id },
                            runtimeOrder = runtimeOrder,
                        ),
                    )
                }
                if (generation != pickerGeneration) return@launch
                pickerBaseline = pickerLoad?.baseline
                _uiState.update {
                    it.copy(
                        pickerItems = pickerLoad?.items.orEmpty(),
                        isPickerLoading = false,
                        pickerError = if (pickerLoad == null) {
                            UiText.Resource(R.string.strategies_load_failed)
                        } else {
                            null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != pickerGeneration) return@launch
                pickerBaseline = null
                _uiState.update {
                    it.copy(
                        pickerItems = emptyList(),
                        isPickerLoading = false,
                        pickerError = UiText.Resource(R.string.strategies_load_failed),
                    )
                }
            }
        }
    }

    fun selectStrategy(categoryKey: String, strategyId: String, newFilterMode: String? = null) {
        val currentState = _uiState.value
        if (currentState.isLoading || currentState.loadError != null ||
            !mutationInProgress.compareAndSet(false, true)
        ) return
        val category = currentState.categories.firstOrNull { it.key == categoryKey }
        val pickerContainsStrategy = currentState.pickerCategoryKey == categoryKey &&
            !currentState.isPickerLoading && currentState.pickerError == null &&
            currentState.pickerItems.any { it.id == strategyId }
        if (!isValidStrategyIdentifier(strategyId) ||
            category == null ||
            !pickerContainsStrategy ||
            newFilterMode != null && (
                !category.canSwitchFilter ||
                    newFilterMode !in setOf("ipset", "hostlist", "hostlist-domains", "none")
                )
        ) {
            mutationInProgress.set(false)
            return
        }
        val targetFilterMode = newFilterMode ?: category.filterMode
        if (category.strategy == strategyId && category.filterMode == targetFilterMode) {
            mutationInProgress.set(false)
            return
        }
        val previousState = currentState
        _uiState.update { state ->
            state.copy(categories = state.categories.map { cat ->
                if (cat.key == categoryKey) {
                    cat.copy(
                        strategy = strategyId,
                        strategyDisplayName = formatStrategyDisplay(strategyId),
                        filterMode = newFilterMode ?: cat.filterMode
                    )
                } else cat
            })
        }
        saveConfigAndRestart(
            previousState = previousState,
            mutation = StrategyConfigMutation.Category(
                categoryKey = categoryKey,
                strategyId = strategyId,
                filterMode = targetFilterMode,
            ),
        )
    }

    fun setPktCount(value: String) {
        val currentState = _uiState.value
        if (currentState.isLoading || currentState.loadError != null ||
            !mutationInProgress.compareAndSet(false, true)
        ) return
        val packetCount = RuntimeConfigStore.positiveCountOrNull(value)
        if (packetCount == null || packetCount !in 1..MAX_PACKET_COUNT) {
            mutationInProgress.set(false)
            return
        }
        if (currentState.pktCount == packetCount.toString()) {
            mutationInProgress.set(false)
            return
        }
        val previousState = currentState
        _uiState.update { it.copy(pktCount = packetCount.toString()) }
        saveConfigAndRestart(
            previousState = previousState,
            mutation = StrategyConfigMutation.PacketCount(packetCount),
        )
    }

    fun setDebugMode(value: String) {
        val currentState = _uiState.value
        if (currentState.isLoading || currentState.loadError != null ||
            !mutationInProgress.compareAndSet(false, true)
        ) return
        if (value !in setOf("none", "android", "file", "syslog")) {
            mutationInProgress.set(false)
            return
        }
        if (currentState.debugMode == value) {
            mutationInProgress.set(false)
            return
        }
        val previousState = currentState
        _uiState.update { it.copy(debugMode = value) }
        saveConfigAndRestart(
            previousState = previousState,
            mutation = StrategyConfigMutation.LogMode(value),
        )
    }

    private fun saveConfigAndRestart(
        previousState: StrategiesUiState,
        mutation: StrategyConfigMutation,
    ) {
        loadGeneration++
        loadJob?.cancel()
        invalidatePickerLoad()
        _uiState.update {
            it.copy(
                isLoading = true,
                loadingText = UiText.Resource(R.string.strategies_restarting_service),
                operationNotice = null,
                pickerCategoryKey = null,
                pickerItems = emptyList(),
                isPickerLoading = false,
                pickerError = null,
            )
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        persistStrategyConfiguration(mutation)
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                StrategyConfigOutcome.BLOCKED
            } catch (_: RuntimeConfigRollbackException) {
                StrategyConfigOutcome.ROLLBACK_FAILED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StrategyConfigOutcome.FAILED
            }

            _uiState.update { it.copy(isLoading = false, loadingText = null) }

            when (outcome) {
                StrategyConfigOutcome.APPLIED -> {
                    publishMessage(UiText.Resource(R.string.strategies_applied_successfully))
                    serviceEventBus.notifyServiceRestarted()
                }
                StrategyConfigOutcome.SAVED_RESTART_FAILED -> _uiState.update {
                    it.copy(operationNotice = UiText.Resource(R.string.strategies_saved_restart_failed))
                }
                StrategyConfigOutcome.BLOCKED -> _uiState.update {
                    it.copy(
                        categories = previousState.categories,
                        pktCount = previousState.pktCount,
                        debugMode = previousState.debugMode,
                        operationNotice = UiText.Resource(R.string.strategies_save_blocked),
                    )
                }
                StrategyConfigOutcome.ROLLBACK_FAILED -> _uiState.update {
                    it.copy(operationNotice = UiText.Resource(R.string.strategies_rollback_failed))
                }
                StrategyConfigOutcome.FAILED -> _uiState.update {
                    it.copy(
                        categories = previousState.categories,
                        pktCount = previousState.pktCount,
                        debugMode = previousState.debugMode,
                        operationNotice = UiText.Resource(R.string.strategies_save_failed),
                    )
                }
            }
            if (outcome == StrategyConfigOutcome.ROLLBACK_FAILED) loadConfigInternal()
        }.invokeOnCompletion { finishMutation() }
    }

    private fun invalidatePickerLoad() {
        pickerGeneration++
        pickerJob?.cancel()
        pickerJob = null
        pickerBaseline = null
    }

    private suspend fun persistStrategyConfiguration(
        mutation: StrategyConfigMutation,
    ): StrategyConfigOutcome {
        return when (mutation) {
            is StrategyConfigMutation.Category -> persistCategoryConfiguration(mutation)
            is StrategyConfigMutation.PacketCount -> persistCoreSetting(
                key = "pkt_out",
                value = mutation.value.toString(),
                removeKeys = setOf("pkt_count"),
            )
            is StrategyConfigMutation.LogMode -> persistCoreSetting("log_mode", mutation.value)
        }
    }

    private suspend fun persistCategoryConfiguration(
        mutation: StrategyConfigMutation.Category,
    ): StrategyConfigOutcome {
        val previousCategories = StrategyRepository.snapshotCategoriesContent()
            ?: return StrategyConfigOutcome.FAILED
        val previousCore = RuntimeConfigStore.readCore()
        val previousPresetMode = previousCore["preset_mode"] ?: return StrategyConfigOutcome.FAILED

        val categoriesSaved = persistRuntimeMutation {
            StrategyRepository.updateAllCategoryStrategies(
                strategyUpdates = mapOf(mutation.categoryKey to mutation.strategyId),
                filterModeUpdates = mapOf(mutation.categoryKey to mutation.filterMode),
            )
        }
        if (!categoriesSaved) {
            return if (restoreCategories(previousCategories)) {
                StrategyConfigOutcome.FAILED
            } else {
                StrategyConfigOutcome.ROLLBACK_FAILED
            }
        }

        val categoriesValid = persistRuntimeMutation {
            StrategyRepository.validateCategoriesWithRuntimeBuilder()
        }
        if (!categoriesValid) {
            return if (restoreCategories(previousCategories)) {
                StrategyConfigOutcome.FAILED
            } else {
                StrategyConfigOutcome.ROLLBACK_FAILED
            }
        }

        val runtimeSaved = try {
            persistRuntimeMutation {
                RuntimeConfigStore.upsertCoreValue("preset_mode", "categories")
            }
        } catch (_: RuntimeConfigRollbackException) {
            restoreCategories(previousCategories)
            return StrategyConfigOutcome.ROLLBACK_FAILED
        }
        if (!runtimeSaved) {
            val categoriesRestored = restoreCategories(previousCategories)
            val runtimeRestored = restorePresetMode(previousPresetMode)
            return if (categoriesRestored && runtimeRestored) {
                StrategyConfigOutcome.FAILED
            } else {
                StrategyConfigOutcome.ROLLBACK_FAILED
            }
        }

        val restarted = persistRuntimeMutation { ServiceLifecycleController.restart().success }
        return if (restarted) StrategyConfigOutcome.APPLIED else StrategyConfigOutcome.SAVED_RESTART_FAILED
    }

    private suspend fun persistCoreSetting(
        key: String,
        value: String,
        removeKeys: Set<String> = emptySet(),
    ): StrategyConfigOutcome {
        val saved = persistRuntimeMutation {
            RuntimeConfigStore.upsertCoreValue(key, value, removeKeys)
        }
        if (!saved) return StrategyConfigOutcome.FAILED
        val restarted = persistRuntimeMutation { ServiceLifecycleController.restart().success }
        return if (restarted) StrategyConfigOutcome.APPLIED else StrategyConfigOutcome.SAVED_RESTART_FAILED
    }

    private suspend fun restoreCategories(previous: String): Boolean = try {
        val current = StrategyRepository.snapshotCategoriesContent()
        current == previous || StrategyRepository.restoreCategoriesContent(previous)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun restorePresetMode(previous: String): Boolean = try {
        val current = RuntimeConfigStore.readCore()["preset_mode"]
        current == previous || RuntimeConfigStore.upsertCoreValue("preset_mode", previous)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
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

    fun dismissOperationNotice() {
        _uiState.update { it.copy(operationNotice = null) }
    }

    fun updatePendingOrder(categoryKey: String, ids: List<String>) {
        val currentState = _uiState.value
        if (mutationInProgress.get() || currentState.isLoading || currentState.loadError != null) return
        storePendingOrder(categoryKey, ids)
    }

    private fun storePendingOrder(categoryKey: String, ids: List<String>) {
        if (!isValidStrategyIdentifier(categoryKey) ||
            _uiState.value.categories.none { it.key == categoryKey }
        ) return
        val normalized = sanitizePendingStrategyOrder(ids)
        if (normalized.isEmpty()) return
        val updated = _uiState.value.pendingOrders + (categoryKey to normalized)
        persistPendingOrders(updated)
        _uiState.update {
            it.copy(
                pendingOrders = updated,
                orderSaveState = StrategyOrderSaveState(),
            )
        }
    }

    fun saveStrategyOrder(categoryKey: String, categoryType: String, currentIds: List<String>) {
        val currentState = _uiState.value
        val category = currentState.categories.firstOrNull { it.key == categoryKey }
        val normalizedIds = sanitizePendingStrategyOrder(currentIds)
        val baseline = pickerBaseline
        val pickerIds = currentState.pickerItems.map { it.id }
        if (!isValidStrategyIdentifier(categoryKey) ||
            category?.type != categoryType ||
            currentState.isLoading ||
            currentState.loadError != null ||
            categoryType !in setOf("tcp", "udp", "voice", "stun") ||
            currentState.pickerCategoryKey != categoryKey ||
            currentState.isPickerLoading ||
            currentState.pickerError != null ||
            baseline == null ||
            baseline.categoryKey != categoryKey ||
            baseline.categoryType != categoryType ||
            pickerIds != baseline.displayIds ||
            !hasExactStrategyOrderMembership(currentIds, baseline.catalogIds)
        ) return

        if (!mutationInProgress.compareAndSet(false, true)) return
        val pendingSnapshot = currentState.pendingOrders + (categoryKey to normalizedIds)
        persistPendingOrders(pendingSnapshot)
        _uiState.update {
            it.copy(
                pendingOrders = pendingSnapshot,
                orderSaveState = StrategyOrderSaveState(
                    categoryKey = categoryKey,
                    status = StrategyOrderSaveStatus.SAVING,
                ),
            )
        }
        viewModelScope.launch {
            val outcome = try {
                ModuleMutationCoordinator.withNonCancellableMutation {
                    withContext(Dispatchers.IO) {
                        val liveDetails = StrategyRepository.getStrategyDetails(categoryType)
                        val liveOrder = StrategyRepository.getSavedOrder(categoryType)
                        val liveIds = liveDetails.map { it.id }
                        val validated = normalizeStrategyOrder(normalizedIds, liveIds)
                        if (liveOrder == baseline.runtimeOrder &&
                            hasExactStrategyOrderMembership(liveIds, baseline.catalogIds) &&
                            validated.any { it != "disabled" } &&
                            StrategyRepository.saveOrder(
                                categoryType,
                                validated,
                                expectedOrder = baseline.runtimeOrder,
                            )
                        ) {
                            StrategyOrderSaveOutcome.SAVED
                        } else {
                            StrategyOrderSaveOutcome.FAILED
                        }
                    }
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                StrategyOrderSaveOutcome.BLOCKED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeConfigRollbackException) {
                StrategyOrderSaveOutcome.ROLLBACK_FAILED
            } catch (_: Exception) {
                StrategyOrderSaveOutcome.FAILED
            }

            if (outcome == StrategyOrderSaveOutcome.SAVED) {
                val remaining = _uiState.value.pendingOrders - categoryKey
                persistPendingOrders(remaining)
                _uiState.update {
                    it.copy(
                        pendingOrders = remaining,
                        orderSaveState = StrategyOrderSaveState(
                            categoryKey = categoryKey,
                            status = StrategyOrderSaveStatus.SAVED,
                        ),
                    )
                }
                publishMessage(UiText.Resource(R.string.strategies_order_saved))
            } else {
                _uiState.update {
                    it.copy(
                        orderSaveState = StrategyOrderSaveState(
                            categoryKey = categoryKey,
                            status = StrategyOrderSaveStatus.ERROR,
                            error = UiText.Resource(
                                when (outcome) {
                                    StrategyOrderSaveOutcome.BLOCKED -> R.string.strategies_save_blocked
                                    StrategyOrderSaveOutcome.ROLLBACK_FAILED ->
                                        R.string.strategies_rollback_failed
                                    StrategyOrderSaveOutcome.FAILED -> R.string.strategies_order_save_failed
                                    StrategyOrderSaveOutcome.SAVED -> R.string.strategies_order_save_failed
                                },
                            ),
                        ),
                    )
                }
            }
        }.invokeOnCompletion { finishMutation() }
    }

    private fun finishMutation() {
        viewModelScope.launch {
            mutationInProgress.set(false)
            runPendingRefresh()
        }
    }

    private fun runPendingRefresh() {
        if (!refreshAfterOperation || mutationInProgress.get() || _uiState.value.isLoading) return
        refreshAfterOperation = false
        loadConfigInternal()
    }

    fun consumeOrderSaveResult(categoryKey: String) {
        if (_uiState.value.orderSaveState.categoryKey == categoryKey &&
            _uiState.value.orderSaveState.status == StrategyOrderSaveStatus.SAVED
        ) {
            _uiState.update { it.copy(orderSaveState = StrategyOrderSaveState()) }
        }
    }

    fun dismissOrderSaveError(categoryKey: String) {
        if (_uiState.value.orderSaveState.categoryKey == categoryKey &&
            _uiState.value.orderSaveState.status == StrategyOrderSaveStatus.ERROR
        ) {
            _uiState.update { it.copy(orderSaveState = StrategyOrderSaveState()) }
        }
    }

    private fun restorePendingOrders(): Map<String, List<String>> {
        val categoryKeys = boundedPendingOrderCategoryKeys(
            savedStateHandle.restoreStringArrayListOrRemove(KEY_PENDING_ORDER_CATEGORIES).orEmpty(),
        )
        val restored = buildMap {
            categoryKeys.forEach { categoryKey ->
                val ids = savedStateHandle.restoreStringArrayListOrRemove(
                    pendingOrderKey(categoryKey),
                )
                    .orEmpty()
                sanitizePendingStrategyOrder(ids).takeIf { it.isNotEmpty() }?.let {
                    put(categoryKey, it)
                }
            }
        }
        // Rewrite immediately so stale/oversized keys from an older process cannot remain in
        // the Activity Bundle after the in-memory view has already been bounded.
        persistPendingOrders(restored)
        return restored
    }

    private fun persistPendingOrders(orders: Map<String, List<String>>) {
        val retainedKeys = boundedPendingOrderCategoryKeys(orders.keys.toList()).toSet()
        val bounded = orders.entries
            .asSequence()
            .filter { it.key in retainedKeys }
            .associate { it.key to sanitizePendingStrategyOrder(it.value) }
            .filterValues { it.isNotEmpty() }
        savedStateHandle.keys()
            .filter { it.startsWith(KEY_PENDING_ORDER_PREFIX) }
            .filterNot { it.removePrefix(KEY_PENDING_ORDER_PREFIX) in bounded }
            .toList()
            .forEach { savedStateHandle.remove<ArrayList<String>>(it) }
        savedStateHandle[KEY_PENDING_ORDER_CATEGORIES] = ArrayList(bounded.keys)
        bounded.forEach { (categoryKey, ids) ->
            savedStateHandle[pendingOrderKey(categoryKey)] = ArrayList(ids)
        }
    }

    private fun pendingOrderKey(categoryKey: String) = "$KEY_PENDING_ORDER_PREFIX$categoryKey"

    private fun formatCategoryTitle(key: String, protocol: String): String {
        val protocolToken = when (protocol) {
            "tcp", "udp", "stun" -> protocol
            else -> error("Unsupported category protocol")
        }
        val tokens = key.split("_").toMutableList()
        if (tokens.size > 1 && tokens.last().lowercase() == protocolToken) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ") { token ->
            when (token.lowercase()) {
                "youtube" -> "YouTube"; "googlevideo" -> "GoogleVideo"; "whatsapp" -> "WhatsApp"
                "github" -> "GitHub"; "anydesk" -> "AnyDesk"; "cloudflare" -> "Cloudflare"
                "warp" -> "WARP"; "claude" -> "Claude"; "chatgpt" -> "ChatGPT"
                "tcp" -> "TCP"; "udp" -> "UDP"; "stun" -> "STUN"
                else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }

    private fun protocolLabel(protocol: String) = when (protocol) {
        "tcp" -> "TCP"
        "udp" -> "UDP"
        "stun" -> "STUN"
        else -> error("Unsupported category protocol")
    }

    private fun pickerType(protocol: String) = when (protocol) {
        "tcp" -> "tcp"
        "udp" -> "udp"
        "stun" -> "voice"
        else -> error("Unsupported category protocol")
    }

    private fun categorySubtitle(config: StrategyRepository.CategoryConfig): UiText {
        val protocol = protocolLabel(config.protocol)
        val target = when (config.filterMode.lowercase()) {
            "ipset" -> config.ipsetFile
            "hostlist" -> config.hostlistFile
            "hostlist-domains" -> config.hostlistDomains
            else -> null
        }
        return target?.let {
            UiText.resource(R.string.strategies_category_subtitle_target, protocol, it)
        } ?: UiText.resource(R.string.strategies_category_subtitle_no_filter, protocol)
    }

    private fun formatStrategyDisplay(name: String): UiText {
        if (name == "disabled") return UiText.Resource(R.string.state_disabled)
        val display = name.split("_").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
        return UiText.Dynamic(if (display.length > 25) display.take(22) + "..." else display)
    }

    private companion object {
        val categoryIdentifierPattern = Regex("[A-Za-z0-9_]{1,128}")
        const val KEY_PENDING_ORDER_CATEGORIES = "strategy_pending_order_categories"
        const val KEY_PENDING_ORDER_PREFIX = "strategy_pending_order_entry_"
    }
}

private val strategyIdentifierPattern = Regex("[A-Za-z0-9._-]{1,128}")
internal const val MAX_PENDING_STRATEGY_IDS = 256
internal const val MAX_PENDING_STRATEGY_ORDER_CHARS = 16 * 1024
internal const val MAX_PENDING_ORDER_CATEGORIES = 4

internal fun isValidStrategyIdentifier(value: String): Boolean =
    strategyIdentifierPattern.matches(value)

internal fun boundedPendingOrderCategoryKeys(keys: List<String>): List<String> = keys
    .asSequence()
    .filter(::isValidStrategyIdentifier)
    .distinct()
    .toList()
    .takeLast(MAX_PENDING_ORDER_CATEGORIES)

internal fun sanitizePendingStrategyOrder(ids: List<String>): List<String> {
    val normalized = ArrayList<String>()
    val seen = HashSet<String>()
    var usedChars = 0
    for (raw in ids) {
        if (normalized.size >= MAX_PENDING_STRATEGY_IDS) break
        val id = raw.trim()
        if (!isValidStrategyIdentifier(id) || !seen.add(id)) continue
        if (usedChars + id.length > MAX_PENDING_STRATEGY_ORDER_CHARS) continue
        normalized += id
        usedChars += id.length
    }
    return normalized.sortedBy { if (it == "disabled") 0 else 1 }
}

internal fun normalizeStrategyOrder(ids: List<String>, knownIds: List<String>): List<String> {
    val known = sanitizePendingStrategyOrder(knownIds)
    val knownSet = known.toSet()
    val requested = sanitizePendingStrategyOrder(ids).filter { it in knownSet }
    return sanitizePendingStrategyOrder(requested + known.filterNot { it in requested })
}

internal fun hasExactStrategyOrderMembership(first: List<String>, second: List<String>): Boolean {
    val normalizedFirst = sanitizePendingStrategyOrder(first)
    val normalizedSecond = sanitizePendingStrategyOrder(second)
    return first.size == normalizedFirst.size &&
        second.size == normalizedSecond.size &&
        normalizedFirst.size == normalizedSecond.size &&
        normalizedFirst.toSet() == normalizedSecond.toSet()
}
