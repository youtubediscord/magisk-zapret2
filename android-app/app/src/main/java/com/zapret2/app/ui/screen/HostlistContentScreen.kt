package com.zapret2.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.data.HostlistRepository
import com.zapret2.app.ui.components.AdaptiveEqualWidthGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.navigation.popDetailOrOpenHostlists
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.ui.theme.MotionTokens
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.HostlistContentError
import com.zapret2.app.viewmodel.HostlistContentLoadState
import com.zapret2.app.viewmodel.HostlistContentUiState
import com.zapret2.app.viewmodel.HostlistContentViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.text.NumberFormat

@Composable
fun HostlistContentScreen(
    navController: NavController,
    viewModel: HostlistContentViewModel? = null,
    previewState: HostlistContentUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: HostlistContentUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val reduceMotion = LocalReducedMotionEnabled.current
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    LaunchedEffect(activeViewModel) {
        activeViewModel?.ensureLoaded()
    }

    LaunchedEffect(state.isEditing, state.hasUnsavedChanges, state.isSaving) {
        if (!state.isEditing || !state.hasUnsavedChanges || state.isSaving) {
            showDiscardDialog = false
        }
    }

    val requestExitEdit = {
        when {
            state.isSaving -> Unit
            state.hasUnsavedChanges -> showDiscardDialog = true
            else -> {
                activeViewModel?.exitEditMode()
                Unit
            }
        }
    }

    if (!LocalInspectionMode.current) {
        BackHandler(enabled = state.isEditing, onBack = requestExitEdit)
    }

    if (showDiscardDialog &&
        state.isEditing &&
        state.hasUnsavedChanges &&
        !state.isSaving
    ) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.hostlist_discard_title)) },
            text = {
                Text(
                    text = stringResource(R.string.hostlist_discard_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        activeViewModel?.exitEditMode(discardUnsavedChanges = true)
                    },
                ) {
                    Text(
                        stringResource(R.string.action_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_keep_editing))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { scaffoldPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = SizeTokens.EditorContentMax)
                        .fillMaxSize()
                        .padding(horizontal = SpacingTokens.Large, vertical = SpacingTokens.Medium),
                ) {
                    HostlistToolbar(
                        state = state,
                        onBack = {
                            if (state.isEditing) requestExitEdit()
                            else navController.popDetailOrOpenHostlists()
                        },
                        onEdit = { activeViewModel?.enterEditMode() },
                        onSave = { activeViewModel?.saveFile() },
                    )
                    Spacer(Modifier.height(SpacingTokens.Medium))
                    Crossfade(
                        targetState = state.isEditing,
                        animationSpec = tween(
                            if (reduceMotion) {
                                MotionTokens.DurationImmediate
                            } else {
                                MotionTokens.DurationEmphasized
                            },
                        ),
                        label = "hostlist mode",
                        modifier = Modifier.weight(1f),
                    ) { editing ->
                        if (editing) {
                            if (!state.hasAuthoritativeEditorBaseline &&
                                state.loadState == HostlistContentLoadState.ERROR
                            ) {
                                HostlistLoadErrorState(
                                    error = state.loadError ?: HostlistContentError.LOAD_FAILED,
                                    onRetry = { activeViewModel?.retryLoad() },
                                )
                            } else {
                                HostlistEditContent(
                                    state = state,
                                    onContentChange = { activeViewModel?.updateEditorContent(it) },
                                    onSave = { activeViewModel?.saveFile() },
                                )
                            }
                        } else {
                            if (state.loadState == HostlistContentLoadState.ERROR) {
                                HostlistLoadErrorState(
                                    error = state.loadError ?: HostlistContentError.LOAD_FAILED,
                                    onRetry = { activeViewModel?.retryLoad() },
                                )
                            } else {
                                HostlistBrowseContent(
                                    state = state,
                                    onSearch = { activeViewModel?.search(it) },
                                    onLoadMore = { activeViewModel?.loadMore() },
                                )
                            }
                        }
                    }
                }
            }
        }
        LoadingOverlay(
            text = stringResource(
                if (state.isSaving) R.string.hostlist_saving else R.string.hostlist_loading_content,
            ),
            visible = state.isLoading || state.isSaving,
        )
    }
}

@Composable
private fun HostlistToolbar(
    state: HostlistContentUiState,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.fileName.removeSuffix(".txt").ifBlank {
                        stringResource(R.string.hostlist_fallback_name)
                    },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.isEditing) {
                        stringResource(R.string.hostlist_editing)
                    } else {
                        quantityStringResource(
                            R.plurals.hostlist_entry_count,
                            state.totalEntries,
                            state.totalEntries,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.isEditing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.isEditing) {
                IconButton(
                    onClick = onSave,
                    enabled = state.canSaveContent,
                ) {
                    Icon(
                        Icons.Default.Save,
                        contentDescription = stringResource(R.string.cd_save_hostlist),
                    )
                }
            } else {
                IconButton(
                    onClick = onEdit,
                    enabled = state.loadState == HostlistContentLoadState.READY,
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.cd_edit_hostlist),
                    )
                }
            }
        }
    }
}

@Composable
private fun HostlistEditContent(
    state: HostlistContentUiState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val success = MaterialTheme.extendedColors.success
    val warning = MaterialTheme.extendedColors.warning
    val lineCount = remember(state.editorContent) {
        if (state.editorContent.isBlank()) 0 else state.editorContent.lineSequence().count()
    }
    val editorTextColor = MaterialTheme.colorScheme.onSurface
    val editorHintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val editorTextStyle = remember(editorTextColor) { MonospaceStyle.copy(color = editorTextColor) }
    val editorHintStyle = remember(editorHintColor) { MonospaceStyle.copy(color = editorHintColor) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(SizeTokens.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            BasicTextField(
                value = state.editorContent,
                onValueChange = onContentChange,
                readOnly = !state.canEditContent,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(SpacingTokens.Large),
                textStyle = editorTextStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (state.editorContent.isEmpty()) {
                            Text(
                                text = stringResource(R.string.hostlist_editor_hint),
                                style = editorHintStyle,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Spacer(Modifier.height(SpacingTokens.ItemVertical))
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            AdaptiveEqualWidthGroup(
                stacked = maxWidth < SizeTokens.CompactActionsBreakpoint,
            ) { itemModifier ->
                Row(
                    modifier = itemModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium),
                ) {
                    Surface(
                        color = if (state.hasUnsavedChanges) warning.container else success.container,
                        contentColor = if (state.hasUnsavedChanges) warning.onContainer else success.onContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (state.hasUnsavedChanges) R.string.hosts_unsaved else R.string.hostlist_all_saved,
                            ),
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            modifier = Modifier.padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.DenseVertical),
                        )
                    }
                    Text(
                        text = quantityStringResource(
                            R.plurals.hostlist_line_count,
                            lineCount,
                            lineCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = onSave,
                    enabled = state.canSaveContent,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = success.container,
                        contentColor = success.onContainer,
                    ),
                    modifier = itemModifier,
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.Small))
                    Text(
                        stringResource(
                            if (state.isSaving) R.string.hostlist_saving else R.string.action_save,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HostlistBrowseContent(
    state: HostlistContentUiState,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    var searchText by rememberSaveable { mutableStateOf(state.searchQuery) }
    LaunchedEffect(state.searchQuery) {
        if (state.searchQuery != searchText) searchText = state.searchQuery
    }
    val listState = rememberLazyListState()
    val lineNumberFormat = remember { NumberFormat.getIntegerInstance() }
    val entryTextColor = MaterialTheme.colorScheme.onSurface
    val entryTextStyle = remember(entryTextColor) { MonospaceStyle.copy(color = entryTextColor) }
    val shouldLoadMore = remember(listState, state.entries.size, state.canLoadMore) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.canLoadMore && lastVisible >= state.entries.size - 10 && state.entries.isNotEmpty()
        }
    }
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    LaunchedEffect(state.searchQuery, listState) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(listState, shouldLoadMore) {
        snapshotFlow { shouldLoadMore.value }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadMore() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchText,
            onValueChange = {
                val bounded = it.take(HostlistRepository.MAX_QUERY_CHARS)
                searchText = bounded
                onSearch(bounded)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.hostlist_search_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            searchText = ""
                            onSearch("")
                        },
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.cd_clear_search),
                        )
                    }
                }
            },
        )
        Text(
            text = if (searchText.isNotEmpty()) {
                quantityStringResource(
                    R.plurals.hostlist_showing_matches,
                    state.matchingEntries ?: 0,
                    state.entries.size,
                    state.matchingEntries ?: 0,
                )
            } else {
                stringResource(R.string.hostlist_showing_count, state.entries.size, state.totalEntries)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.ExtraSmall, vertical = SpacingTokens.Small),
        )

        when {
            state.entries.isEmpty() && searchText.isNotEmpty() -> {
                SearchEmptyState(query = searchText)
            }
            state.entries.isEmpty() && !state.isLoading -> {
                ContentCard {
                    Text(
                        stringResource(R.string.hostlist_empty_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.hostlist_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = SpacingTokens.ExtraLarge),
                ) {
                    itemsIndexed(
                        items = state.entries,
                        key = { index, _ -> index },
                        contentType = { _, _ -> "hostlist_entry" },
                    ) { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ItemVertical),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = lineNumberFormat.format(index + 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(SizeTokens.LineNumberColumnWidth),
                            )
                            Text(
                                text = entry,
                                style = entryTextStyle,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = SizeTokens.LeadingContentInset),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostlistLoadErrorState(
    error: HostlistContentError,
    onRetry: () -> Unit,
) {
    ContentCard(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                text = stringResource(
                    when (error) {
                        HostlistContentError.INVALID_PATH -> R.string.hostlist_invalid_path
                        HostlistContentError.LOAD_FAILED -> R.string.hostlist_load_failed
                    },
                ),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.error,
            )
            if (error == HostlistContentError.LOAD_FAILED) {
                Spacer(Modifier.height(SpacingTokens.Large))
                Button(onClick = onRetry, shape = MaterialTheme.shapes.extraLarge) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.Small))
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(query: String) {
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
                stringResource(R.string.hostlist_no_matches),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.hostlist_nothing_contains, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
