package com.zapret2.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.viewmodel.LogSharePreparation
import com.zapret2.app.viewmodel.LogTab
import com.zapret2.app.viewmodel.LogsLoadState
import com.zapret2.app.viewmodel.LogsUiState
import com.zapret2.app.viewmodel.LogsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LogsScreen(
    viewModel: LogsViewModel? = null,
    previewState: LogsUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: LogsUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val reduceMotion = LocalReducedMotionEnabled.current
    val commandClipboardLabel = stringResource(R.string.logs_clip_command)
    val logsClipboardLabel = stringResource(R.string.logs_clip_logs)
    val shareChooserTitle = stringResource(R.string.logs_share_chooser)

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenStarted()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = state.currentTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                LogTab.entries.forEach { tab ->
                    Tab(
                        selected = state.currentTab == tab,
                        onClick = { activeViewModel?.selectTab(tab) },
                        enabled = !state.isClearing,
                        text = {
                            Text(
                                text = stringResource(
                                    when (tab) {
                                        LogTab.COMMAND -> R.string.logs_tab_command
                                        LogTab.LOGS -> R.string.logs_tab_logs
                                        LogTab.WARNINGS -> R.string.logs_tab_warnings
                                    },
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    LogTab.COMMAND -> Icons.Default.Code
                                    LogTab.LOGS -> Icons.Default.Description
                                    LogTab.WARNINGS -> Icons.Default.Warning
                                },
                                contentDescription = null,
                            )
                        },
                    )
                }
            }

            when (state.currentTab) {
                LogTab.COMMAND -> CommandTab(
                    state = state,
                    onCopy = {
                        if (state.commandLoadState == LogsLoadState.READY &&
                            state.rawCmdline.isNotBlank() && !state.isClearing
                        ) {
                            activeViewModel?.copyToClipboard(commandClipboardLabel, state.rawCmdline)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )

                LogTab.LOGS,
                LogTab.WARNINGS,
                -> LogStreamTab(
                    state = state,
                    reduceMotion = reduceMotion,
                    onFilterChange = { activeViewModel?.setFilter(it) },
                    onToggleAutoScroll = { activeViewModel?.toggleAutoScroll() },
                    onRefresh = { activeViewModel?.refresh() },
                    onCopy = {
                        if (state.outputLoadState == LogsLoadState.READY && !state.isClearing) {
                            activeViewModel?.copyToClipboard(logsClipboardLabel, state.logs)
                        }
                    },
                    onShare = {
                        when (val prepared = activeViewModel?.prepareShare(state.currentTab, state.logs)) {
                            is LogSharePreparation.Ready -> runCatching {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, logsClipboardLabel)
                                    putExtra(Intent.EXTRA_TEXT, prepared.text)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, shareChooserTitle))
                            }.onFailure { activeViewModel?.reportShareFailed() }
                            LogSharePreparation.Empty -> activeViewModel?.reportShareEmpty()
                            LogSharePreparation.Rejected,
                            null,
                            -> Unit
                        }
                    },
                    onClear = {
                        activeViewModel?.clearLogs(
                            expectedTab = state.currentTab,
                            confirmed = true,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommandTab(
    state: LogsUiState,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxWidth < SizeTokens.MediumBreakpoint
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = SizeTokens.EditorContentMax)
                .padding(horizontal = if (compact) SpacingTokens.Large else SpacingTokens.ExtraLarge, vertical = SpacingTokens.CardContent),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Large),
        ) {
            ContentCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.logs_active_command),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.logs_active_command_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = onCopy,
                        enabled = state.commandLoadState == LogsLoadState.READY &&
                            state.rawCmdline.isNotBlank() && !state.isClearing,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Text(
                            text = stringResource(R.string.action_copy),
                            modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                        )
                    }
                }
            }

            ContentCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(SpacingTokens.CardContent),
            ) {
                when (state.commandLoadState) {
                    LogsLoadState.LOADING -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(SizeTokens.IconLarge),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    LogsLoadState.ERROR -> LogErrorState(
                        title = stringResource(R.string.logs_command_load_error),
                        message = stringResource(R.string.logs_command_load_error_body),
                    )

                    LogsLoadState.IDLE,
                    LogsLoadState.READY,
                    -> if (state.cmdline.isBlank()) {
                        EmptyCommandState()
                    } else {
                        SelectionContainer {
                            Text(
                                text = state.cmdline,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                style = MonospaceStyle.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCommandState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.IconLarge),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.logs_no_command),
            modifier = Modifier.padding(top = SpacingTokens.Medium),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.logs_no_command_body),
            modifier = Modifier.padding(top = SpacingTokens.ExtraSmall),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogStreamTab(
    state: LogsUiState,
    reduceMotion: Boolean,
    onFilterChange: (String) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showClearConfirmation by rememberSaveable(state.currentTab) { mutableStateOf(false) }
    val autoScrollDescription = stringResource(R.string.cd_auto_scroll)
    val autoScrollState = stringResource(
        if (state.autoScroll) R.string.state_enabled else R.string.state_disabled,
    )
    val isLoading = state.outputLoadState == LogsLoadState.LOADING
    val isError = state.outputLoadState == LogsLoadState.ERROR
    val logLineColor = MaterialTheme.colorScheme.onSurface
    val logLineStyle = remember(logLineColor) { MonospaceStyle.copy(color = logLineColor) }
    val displayLines = remember(state.logs, state.filterText) {
        val lines = state.logs.lines()
        if (state.filterText.isNotEmpty()) {
            lines.filter { line -> line.contains(state.filterText, ignoreCase = true) }
        } else {
            lines
        }
    }
    val isEmpty = displayLines.isEmpty() ||
        (displayLines.size == 1 && displayLines.first().isBlank())

    LaunchedEffect(state.isClearing) {
        if (state.isClearing) showClearConfirmation = false
    }
    LaunchedEffect(displayLines.size, state.autoScroll, reduceMotion) {
        if (state.autoScroll && displayLines.isNotEmpty() && !isLoading) {
            val lastIndex = displayLines.lastIndex
            if (reduceMotion) {
                listState.scrollToItem(lastIndex)
            } else {
                listState.animateScrollToItem(lastIndex)
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val compact = maxWidth < SizeTokens.LogsCompactBreakpoint
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = SizeTokens.LogsContentMax)
                .padding(horizontal = if (compact) SpacingTokens.Large else SpacingTokens.ExtraLarge, vertical = SpacingTokens.Large),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium),
        ) {
            ContentCard(contentPadding = PaddingValues(SpacingTokens.Large)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.filterText,
                        onValueChange = onFilterChange,
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.logs_filter)) },
                        placeholder = { Text(stringResource(R.string.logs_search_hint)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = if (state.filterText.isNotEmpty()) {
                            {
                                IconButton(onClick = { onFilterChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cd_clear_filter),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                    )
                    IconToggleButton(
                        checked = state.autoScroll,
                        onCheckedChange = { onToggleAutoScroll() },
                        modifier = Modifier.semantics {
                            contentDescription = autoScrollDescription
                            stateDescription = autoScrollState
                        },
                    ) {
                        Icon(
                            imageVector = if (state.autoScroll) {
                                Icons.Default.VerticalAlignBottom
                            } else {
                                Icons.Default.VerticalAlignTop
                            },
                            contentDescription = null,
                            tint = if (state.autoScroll) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            ContentCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(SpacingTokens.None),
            ) {
                when {
                    isLoading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(SizeTokens.IconLarge),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    isError -> LogErrorState(
                        title = stringResource(R.string.logs_load_error),
                        message = stringResource(R.string.logs_load_error_body),
                    )

                    isEmpty -> LogEmptyState(
                        filtered = state.filterText.isNotEmpty(),
                        warnings = state.currentTab == LogTab.WARNINGS,
                    )

                    else -> SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = SpacingTokens.Large, vertical = SpacingTokens.Medium),
                        ) {
                            itemsIndexed(
                                items = displayLines,
                                key = { index, _ -> index },
                            ) { _, line ->
                                Text(
                                    text = line,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = SpacingTokens.Micro),
                                    style = logLineStyle,
                                )
                            }
                        }
                    }
                }
            }

            LogActions(
                compact = compact,
                actionsEnabled = !state.isClearing,
                copyEnabled = state.outputLoadState == LogsLoadState.READY &&
                    state.logs.isNotBlank() && !state.isClearing,
                isClearing = state.isClearing,
                onRefresh = onRefresh,
                onCopy = onCopy,
                onShare = onShare,
                onClear = { showClearConfirmation = true },
            )
        }
    }

    if (showClearConfirmation && !state.isClearing) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LogActions(
    compact: Boolean,
    actionsEnabled: Boolean,
    copyEnabled: Boolean,
    isClearing: Boolean,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = onRefresh,
            enabled = actionsEnabled,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.action_refresh),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
        OutlinedButton(
            onClick = onCopy,
            enabled = copyEnabled,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.action_copy),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
        OutlinedButton(
            onClick = onShare,
            enabled = copyEnabled,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(R.string.action_share),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
        Button(
            onClick = onClear,
            enabled = actionsEnabled,
            modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(
                    if (isClearing) R.string.logs_clearing else R.string.action_clear,
                ),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
    }

    if (compact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
        ) {
            content()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun LogErrorState(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.ExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.IconLarge),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = title,
            modifier = Modifier.padding(top = SpacingTokens.Medium),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            modifier = Modifier
                .padding(top = SpacingTokens.ExtraSmall)
                .widthIn(max = SizeTokens.DialogContentMaxWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogEmptyState(filtered: Boolean, warnings: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.ExtraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (warnings) Icons.Default.Warning else Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(SizeTokens.IconLarge),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                filtered -> stringResource(R.string.logs_no_matches)
                warnings -> stringResource(R.string.logs_no_warnings)
                else -> stringResource(R.string.logs_none)
            },
            modifier = Modifier.padding(top = SpacingTokens.Medium),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = when {
                filtered -> stringResource(R.string.logs_filter_try_again)
                warnings -> stringResource(R.string.logs_no_warnings_body)
                else -> stringResource(R.string.logs_none_body)
            },
            modifier = Modifier.padding(top = SpacingTokens.ExtraSmall),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
