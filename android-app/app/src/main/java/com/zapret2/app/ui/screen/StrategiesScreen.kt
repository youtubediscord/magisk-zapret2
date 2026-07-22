package com.zapret2.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.data.StrategyRepository
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.resolve
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.components.CategoryRow
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.SettingRow
import com.zapret2.app.ui.components.StrategyItem
import com.zapret2.app.ui.components.StrategyPickerSheet
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.ui.theme.MotionTokens
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.CategoryUiModel
import com.zapret2.app.viewmodel.StrategiesUiState
import com.zapret2.app.viewmodel.StrategiesViewModel
import com.zapret2.app.viewmodel.StrategyOrderSaveState
import com.zapret2.app.viewmodel.StrategyOrderSaveStatus

private const val MAX_STRATEGY_SEARCH_CHARS = 256

@Composable
fun StrategiesScreen(
    viewModel: StrategiesViewModel? = null,
    previewState: StrategiesUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: StrategiesUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = LocalReducedMotionEnabled.current
    var pickingCategoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val pickingCategory = state.categories.firstOrNull { it.key == pickingCategoryKey }

    LifecycleStartEffect(activeViewModel) {
        pickingCategoryKey = null
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    LaunchedEffect(state.isLoading, state.loadError, pickingCategory) {
        if (pickingCategoryKey != null &&
            (state.isLoading || state.loadError != null || pickingCategory == null)
        ) {
            pickingCategoryKey = null
        }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { scaffoldPadding ->
            AnimatedContent(
                targetState = pickingCategory,
                modifier = Modifier.padding(scaffoldPadding),
                transitionSpec = {
                    if (reduceMotion) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(MotionTokens.DurationMedium)) +
                            slideInHorizontally(tween(MotionTokens.DurationLong)) { it / 8 }) togetherWith
                            (fadeOut(tween(MotionTokens.DurationShort)) +
                                slideOutHorizontally(tween(MotionTokens.DurationMedium)) { -it / 8 })
                    }
                },
                label = "strategy destination",
            ) { category ->
                if (category == null) {
                    CategoriesContent(
                        state = state,
                        onCategoryClick = { pickingCategoryKey = it.key },
                        onPacketCountChange = { activeViewModel?.setPktCount(it) },
                        onDebugModeChange = { activeViewModel?.setDebugMode(it) },
                        onReload = { activeViewModel?.loadConfig() },
                        onDismissNotice = { activeViewModel?.dismissOperationNotice() },
                    )
                } else {
                    StrategyPickerView(
                        category = category,
                        loadedDetails = state.pickerItems.takeIf {
                            state.pickerCategoryKey == category.key
                        }.orEmpty(),
                        isLoading = state.isPickerLoading && state.pickerCategoryKey == category.key,
                        loadError = state.pickerError.takeIf {
                            state.pickerCategoryKey == category.key
                        },
                        orderSaveState = state.orderSaveState,
                        onBack = { pickingCategoryKey = null },
                        onPendingOrderChange = {
                            activeViewModel?.updatePendingOrder(category.key, it)
                        },
                        onSaveOrder = {
                            activeViewModel?.saveStrategyOrder(category.key, category.type, it)
                        },
                        onConsumeOrderSaved = {
                            activeViewModel?.consumeOrderSaveResult(category.key)
                        },
                        onDismissOrderError = {
                            activeViewModel?.dismissOrderSaveError(category.key)
                        },
                        onRetry = {
                            activeViewModel?.loadStrategyPicker(category.key, category.type)
                        },
                        onSelected = { strategyId, filterMode ->
                            activeViewModel?.selectStrategy(category.key, strategyId, filterMode)
                            pickingCategoryKey = null
                        },
                    )
                }
            }
        }
        LoadingOverlay(text = state.loadingText?.resolve().orEmpty(), visible = state.isLoading)
    }
}

@Composable
private fun CategoriesContent(
    state: StrategiesUiState,
    onCategoryClick: (CategoryUiModel) -> Unit,
    onPacketCountChange: (String) -> Unit,
    onDebugModeChange: (String) -> Unit,
    onReload: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    var showPacketPicker by rememberSaveable { mutableStateOf(false) }
    var showDebugPicker by rememberSaveable { mutableStateOf(false) }
    val settingsEnabled = !state.isLoading && state.loadError == null

    LaunchedEffect(settingsEnabled) {
        if (!settingsEnabled) {
            showPacketPicker = false
            showDebugPicker = false
        }
    }

    if (showPacketPicker && settingsEnabled) {
        val options = listOf("1", "3", "5", "10", "15", "20")
        StrategyPickerSheet(
            title = stringResource(R.string.strategies_packet_count),
            subtitle = stringResource(R.string.strategies_packet_picker_body),
            strategies = options.map {
                StrategyItem(it, it, stringResource(R.string.strategies_packet_option, it))
            },
            selectedId = state.pktCount,
            onDismiss = { showPacketPicker = false },
            onSelected = { id, _ ->
                onPacketCountChange(id)
                showPacketPicker = false
            },
        )
    }

    if (showDebugPicker && settingsEnabled) {
        StrategyPickerSheet(
            title = stringResource(R.string.strategies_debug_mode),
            subtitle = stringResource(R.string.strategies_debug_picker_body),
            strategies = listOf(
                StrategyItem(
                    "none",
                    stringResource(R.string.strategies_debug_none),
                    stringResource(R.string.strategies_debug_none_body),
                ),
                StrategyItem(
                    "android",
                    stringResource(R.string.strategies_debug_android),
                    stringResource(R.string.strategies_debug_android_body),
                ),
                StrategyItem(
                    "file",
                    stringResource(R.string.strategies_debug_file),
                    stringResource(R.string.strategies_debug_file_body),
                ),
                StrategyItem(
                    "syslog",
                    stringResource(R.string.strategies_debug_syslog),
                    stringResource(R.string.strategies_debug_syslog_body),
                ),
            ),
            selectedId = state.debugMode,
            onDismiss = { showDebugPicker = false },
            onSelected = { id, _ ->
                onDebugModeChange(id)
                showDebugPicker = false
            },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) SpacingTokens.ExtraLarge else SpacingTokens.Large
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = SizeTokens.ContentMax)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.ItemVertical),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = SpacingTokens.Large,
                end = horizontalPadding,
                bottom = SpacingTokens.Section,
            ),
        ) {
            item {
                ContentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(SpacingTokens.RowVertical)
                                    .size(SizeTokens.IconEmphasized),
                            )
                        }
                        Spacer(Modifier.width(SpacingTokens.Large))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.strategies_category_title),
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                            Text(
                                text = quantityStringResource(
                                    R.plurals.strategies_category_count,
                                    state.categories.size,
                                    state.categories.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onReload, enabled = !state.isLoading) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_reload_strategies),
                            )
                        }
                    }
                }
            }

            state.loadError?.let { message ->
                item {
                    StrategyNoticeCard(
                        message = message.resolve(),
                        onRetry = onReload,
                    )
                }
            }

            state.operationNotice?.let { message ->
                item {
                    StrategyNoticeCard(
                        message = message.resolve(),
                        onDismiss = onDismissNotice,
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.strategies_categories)) }

            if (state.categories.isEmpty() && !state.isLoading && state.loadError == null) {
                item { EmptyCategoriesState(onReload = onReload) }
            }

            items(state.categories, key = { it.key }) { category ->
                CategoryRow(
                    title = category.title,
                    subtitle = category.subtitle.resolve(),
                    value = category.strategyDisplayName.resolve(),
                    icon = resolveCategoryIcon(category.key, category.type),
                    iconTint = resolveCategoryColor(category.key, category.type),
                    enabled = settingsEnabled,
                    onClick = { onCategoryClick(category) },
                )
            }

            item {
                SectionHeader(stringResource(R.string.strategies_advanced))
                ContentCard {
                    SettingRow(
                        title = stringResource(R.string.strategies_packet_count),
                        value = state.pktCount,
                        subtitle = stringResource(R.string.strategies_packet_setting_body),
                        onClick = { showPacketPicker = true },
                        enabled = settingsEnabled,
                    )
                    Spacer(Modifier.height(SpacingTokens.Small))
                    SettingRow(
                        title = stringResource(R.string.strategies_debug_mode),
                        value = debugModeLabel(state.debugMode),
                        subtitle = stringResource(R.string.strategies_debug_setting_body),
                        onClick = { showDebugPicker = true },
                        enabled = settingsEnabled,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StrategyPickerView(
    category: CategoryUiModel,
    loadedDetails: List<StrategyRepository.StrategyDetail>,
    isLoading: Boolean,
    loadError: UiText?,
    orderSaveState: StrategyOrderSaveState,
    onBack: () -> Unit,
    onPendingOrderChange: (List<String>) -> Unit,
    onSaveOrder: (List<String>) -> Unit,
    onConsumeOrderSaved: () -> Unit,
    onDismissOrderError: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (strategyId: String, filterMode: String?) -> Unit,
) {
    val reduceMotion = LocalReducedMotionEnabled.current
    val details = remember(category.key) { mutableStateListOf<StrategyRepository.StrategyDetail>() }
    var searchQuery by rememberSaveable(category.key) { mutableStateOf("") }
    var filterMode by rememberSaveable(category.key, category.filterMode) {
        mutableStateOf(category.filterMode)
    }
    var expandedId by rememberSaveable(category.key) { mutableStateOf<String?>(null) }
    var isReorderMode by rememberSaveable(category.key) { mutableStateOf(false) }
    val categoryOrderState = orderSaveState.takeIf { it.categoryKey == category.key }
        ?: StrategyOrderSaveState()
    val isOrderSaving = categoryOrderState.status == StrategyOrderSaveStatus.SAVING
    val canReorder = !isLoading && loadError == null && details.isNotEmpty()

    LaunchedEffect(category.key, loadedDetails) {
        details.clear()
        details.addAll(loadedDetails)
    }

    LaunchedEffect(category.key, category.type) {
        onRetry()
    }

    LaunchedEffect(categoryOrderState.status) {
        if (categoryOrderState.status == StrategyOrderSaveStatus.SAVED) {
            isReorderMode = false
            onConsumeOrderSaved()
        }
    }

    LaunchedEffect(canReorder) {
        if (!canReorder) {
            isReorderMode = false
        }
    }

    val displayList by remember {
        derivedStateOf {
            if (isReorderMode || searchQuery.isBlank()) {
                details.toList()
            } else {
                val query = searchQuery.trim().lowercase()
                details.filter { strategy ->
                    strategy.displayName.lowercase().contains(query) ||
                        strategy.description.lowercase().contains(query) ||
                        strategy.args.lowercase().contains(query) ||
                        strategy.id.lowercase().contains(query)
                }
            }
        }
    }

    fun saveOrderAndExit() {
        if (!isOrderSaving && canReorder) onSaveOrder(details.map { it.id })
    }

    fun handleBack() {
        when {
            isOrderSaving -> Unit
            isReorderMode && canReorder -> saveOrderAndExit()
            isReorderMode -> isReorderMode = false
            else -> onBack()
        }
    }

    BackHandler(onBack = ::handleBack)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) SpacingTokens.ExtraLarge else SpacingTokens.Large
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = SizeTokens.EditorContentMax)
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = SpacingTokens.Medium),
        ) {
            StrategyPickerToolbar(
                category = category,
                isReorderMode = isReorderMode,
                isSaving = isOrderSaving,
                reorderEnabled = canReorder,
                onBack = ::handleBack,
                onToggleReorder = {
                    if (isReorderMode) {
                        saveOrderAndExit()
                    } else {
                        isReorderMode = true
                        searchQuery = ""
                        expandedId = null
                    }
                },
            )
            Spacer(Modifier.height(SpacingTokens.Medium))

            categoryOrderState.error?.let { error ->
                StrategyNoticeCard(
                    message = error.resolve(),
                    onRetry = ::saveOrderAndExit,
                    onDismiss = onDismissOrderError,
                )
                Spacer(Modifier.height(SpacingTokens.Medium))
            }

            if (!isReorderMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(MAX_STRATEGY_SEARCH_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.strategies_search_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.cd_clear_search),
                                )
                            }
                        }
                    },
                )
            }

            if (category.canSwitchFilter && !isReorderMode) {
                Spacer(Modifier.height(SpacingTokens.ItemVertical))
                FilterModeSelector(
                    selectedMode = filterMode,
                    onSelected = { filterMode = it },
                )
            }

            Text(
                text = if (isReorderMode) {
                    quantityStringResource(
                        R.plurals.strategies_reorder_count,
                        details.size,
                        details.size,
                    )
                } else {
                    quantityStringResource(
                        R.plurals.strategies_count,
                        displayList.size,
                        displayList.size,
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SpacingTokens.ExtraSmall, vertical = SpacingTokens.ItemVertical),
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingTokens.Section),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(modifier = Modifier.size(SizeTokens.IconLarge))
                    }
                }
                loadError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(SpacingTokens.Section),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                    ) {
                        Text(
                            text = loadError.resolve(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(SpacingTokens.ExtraSmall))
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                displayList.isEmpty() -> StrategySearchEmptyState(query = searchQuery)
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isReorderMode) Modifier else Modifier.selectableGroup()),
                        contentPadding = PaddingValues(bottom = SpacingTokens.ExtraLarge),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                    ) {
                        items(displayList, key = { it.id }) { strategy ->
                            val currentIndex = details.indexOf(strategy)
                            StrategyCard(
                                strategy = strategy,
                                isSelected = strategy.id == category.strategy,
                                isExpanded = expandedId == strategy.id,
                                isReorderMode = isReorderMode,
                                reorderEnabled = !isOrderSaving,
                                position = currentIndex,
                                totalCount = details.size,
                                reduceMotion = reduceMotion,
                                onSelect = {
                                    onSelected(
                                        strategy.id,
                                        if (category.canSwitchFilter) filterMode else null,
                                    )
                                },
                                onToggleExpanded = {
                                    expandedId = if (expandedId == strategy.id) null else strategy.id
                                },
                                onMoveUp = {
                                    if (currentIndex > 1) {
                                        details.add(currentIndex - 1, details.removeAt(currentIndex))
                                        onPendingOrderChange(details.map { it.id })
                                    }
                                },
                                onMoveDown = {
                                    if (currentIndex in 1 until details.lastIndex) {
                                        details.add(currentIndex + 1, details.removeAt(currentIndex))
                                        onPendingOrderChange(details.map { it.id })
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StrategyPickerToolbar(
    category: CategoryUiModel,
    isReorderMode: Boolean,
    isSaving: Boolean,
    reorderEnabled: Boolean,
    onBack: () -> Unit,
    onToggleReorder: () -> Unit,
) {
    val success = MaterialTheme.extendedColors.success
    val reorderActionDescription = stringResource(
        if (isReorderMode) R.string.cd_save_strategy_order else R.string.cd_reorder_strategies,
    )
    Surface(
        color = if (isReorderMode) success.container else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (isReorderMode) success.onContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !isSaving) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isReorderMode) {
                        stringResource(R.string.strategies_reorder_title)
                    } else {
                        category.title
                    },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isReorderMode) {
                        stringResource(R.string.strategies_reorder_body)
                    } else {
                        category.subtitle.resolve()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isReorderMode) success.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onToggleReorder,
                enabled = !isSaving && reorderEnabled,
                modifier = Modifier.semantics {
                    contentDescription = reorderActionDescription
                },
            ) {
                if (isSaving) {
                    LoadingIndicator(modifier = Modifier.size(SizeTokens.IconMedium))
                } else {
                    Icon(
                        imageVector = if (isReorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterModeSelector(
    selectedMode: String,
    onSelected: (String) -> Unit,
) {
    val options = listOf(
        "ipset" to stringResource(R.string.strategy_filter_ipset),
        "hostlist" to stringResource(R.string.strategy_filter_hostlist),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selectedMode == mode,
                onClick = { onSelected(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun StrategyCard(
    strategy: StrategyRepository.StrategyDetail,
    isSelected: Boolean,
    isExpanded: Boolean,
    isReorderMode: Boolean,
    reorderEnabled: Boolean,
    position: Int,
    totalCount: Int,
    reduceMotion: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val disabled = strategy.id == "disabled"
    val displayName = if (disabled) stringResource(R.string.state_disabled) else strategy.displayName
    val description = if (disabled) {
        stringResource(R.string.strategy_no_bypass)
    } else {
        strategy.description
    }
    val positionDescription = stringResource(R.string.strategy_position, position + 1, totalCount)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        border = BorderStroke(
            SizeTokens.BorderThin,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = SizeTokens.MinimumTouchTarget)
                .then(
                    if (isReorderMode) {
                        Modifier.semantics(mergeDescendants = true) {
                            stateDescription = positionDescription
                        }
                    } else {
                        Modifier.selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = onSelect,
                        )
                    },
                )
                .padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.ItemVertical),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isReorderMode) {
                    if (disabled) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.cd_pinned_first),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = SpacingTokens.Medium)
                                .size(SizeTokens.IconMedium),
                        )
                    } else {
                        Column {
                            IconButton(
                                onClick = onMoveUp,
                                enabled = reorderEnabled && position > 1,
                                modifier = Modifier.size(SizeTokens.MinimumTouchTarget),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.cd_move_up),
                                )
                            }
                            IconButton(
                                onClick = onMoveDown,
                                enabled = reorderEnabled && position in 1 until totalCount - 1,
                                modifier = Modifier.size(SizeTokens.MinimumTouchTarget),
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_move_down),
                                )
                            }
                        }
                    }
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }

                Spacer(Modifier.width(SpacingTokens.Small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description.isNotEmpty()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (strategy.args.isNotEmpty() && !isReorderMode) {
                    IconButton(onClick = onToggleExpanded) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Code,
                            contentDescription = stringResource(
                                if (isExpanded) {
                                    R.string.cd_hide_arguments
                                } else {
                                    R.string.cd_preview_arguments
                                },
                            ),
                        )
                    }
                }
            }

            StrategyArguments(
                args = strategy.args,
                visible = isExpanded && !isReorderMode,
                reduceMotion = reduceMotion,
            )
        }
    }
}

@Composable
private fun StrategyArguments(args: String, visible: Boolean, reduceMotion: Boolean) {
    if (reduceMotion) {
        if (visible) ArgumentsSurface(args)
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MotionTokens.DurationMedium)) +
                expandVertically(tween(MotionTokens.DurationLong)),
            exit = fadeOut(tween(MotionTokens.DurationShort)) +
                shrinkVertically(tween(MotionTokens.DurationMedium)),
        ) {
            ArgumentsSurface(args)
        }
    }
}

@Composable
private fun ArgumentsSurface(args: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(SizeTokens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SizeTokens.LeadingContentInset, top = SpacingTokens.Small),
    ) {
        Text(
            text = args.replace(" --", "\n--"),
            style = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.padding(SpacingTokens.Medium),
        )
    }
}

@Composable
private fun StrategyNoticeCard(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(SpacingTokens.Large)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null || onDismiss != null) {
                Spacer(Modifier.height(SpacingTokens.Small))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onDismiss?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                    onRetry?.let {
                        FilledTonalButton(onClick = it) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCategoriesState(onReload: () -> Unit) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                stringResource(R.string.strategies_empty_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.strategies_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpacingTokens.Large))
            FilledTonalButton(onClick = onReload, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.action_reload))
            }
        }
    }
}

@Composable
private fun StrategySearchEmptyState(query: String) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SizeTokens.LoadingCompact),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                stringResource(R.string.strategies_no_matches),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.strategies_nothing_matches, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun resolveCategoryIcon(key: String, type: String): ImageVector {
    val normalized = key.lowercase()
    return when {
        normalized.contains("youtube") ||
            normalized.contains("googlevideo") ||
            normalized.contains("twitch") -> Icons.Default.VideoLibrary
        normalized.contains("discord") ||
            normalized.contains("telegram") ||
            normalized.contains("whatsapp") ||
            normalized.contains("voice") ||
            type == "voice" -> Icons.Default.Chat
        normalized.contains("facebook") ||
            normalized.contains("instagram") ||
            normalized.contains("twitter") -> Icons.Default.Public
        else -> Icons.Default.Apps
    }
}

@Composable
private fun resolveCategoryColor(key: String, type: String): Color {
    val normalized = key.lowercase()
    return when {
        normalized.contains("youtube") || normalized.contains("googlevideo") ->
            MaterialTheme.colorScheme.error
        normalized.contains("discord") || normalized.contains("telegram") ->
            MaterialTheme.colorScheme.primary
        normalized.contains("whatsapp") -> MaterialTheme.extendedColors.success.color
        normalized.contains("voice") || type == "voice" -> MaterialTheme.colorScheme.tertiary
        normalized.contains("facebook") ||
            normalized.contains("instagram") ||
            normalized.contains("twitter") -> MaterialTheme.colorScheme.secondary
        normalized.contains("soundcloud") -> MaterialTheme.extendedColors.warning.color
        type == "udp" -> MaterialTheme.extendedColors.info.color
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun debugModeLabel(mode: String): String = stringResource(
    when (mode) {
        "android" -> R.string.strategies_debug_android
        "file" -> R.string.strategies_debug_file
        "syslog" -> R.string.strategies_debug_syslog
        else -> R.string.strategies_debug_none
    },
)
