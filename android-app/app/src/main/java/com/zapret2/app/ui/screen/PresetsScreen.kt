package com.zapret2.app.ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.data.PresetDurableOutcome
import com.zapret2.app.data.PresetEntry
import com.zapret2.app.data.PresetIssue
import com.zapret2.app.ui.resolve
import com.zapret2.app.ui.components.AdaptiveActionGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.PresetEditorDialog
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.SettingRow
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.PresetsViewModel
import com.zapret2.app.viewmodel.PresetsUiState

@Composable
fun PresetsScreen(
    viewModel: PresetsViewModel? = null,
    previewState: PresetsUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: PresetsUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val settingsEnabled = !state.isLoading && state.hasAuthoritativeCatalog
    val presetModeActive = state.activeMode in presetModes

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    state.editingPreset?.let { editor ->
        PresetEditorDialog(
            fileName = editor.fileName,
            content = editor.content,
            baselineContent = editor.baselineContent,
            enabled = settingsEnabled && editor.hasAuthoritativeBaseline,
            dismissEnabled = !state.isLoading,
            unavailableMessage = if (editor.hasAuthoritativeBaseline) {
                null
            } else {
                stringResource(R.string.presets_editor_source_unavailable)
            },
            onContentChange = { activeViewModel?.updatePresetContent(it) },
            onDismiss = { discardUnsavedChanges ->
                activeViewModel?.closePresetEditor(discardUnsavedChanges)
            },
            onSave = { activeViewModel?.savePreset(false) },
            onSaveAndApply = { activeViewModel?.savePreset(true) },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { scaffoldPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
            ) {
                val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) SpacingTokens.ExtraLarge else SpacingTokens.Large
                val compactActions = maxWidth < SizeTokens.CompactActionsBreakpoint
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
                                Icon(
                                    Icons.Default.SettingsSuggest,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(SizeTokens.IconProminent),
                                )
                                Spacer(Modifier.width(SpacingTokens.Large))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.presets_active_source),
                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                    )
                                    Text(
                                        text = if (state.hasAuthoritativeCatalog) {
                                            activeModeLabel(state.activeMode)
                                        } else {
                                            stringResource(R.string.presets_state_unavailable)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(SpacingTokens.Large))
                            SettingRow(
                                title = stringResource(R.string.presets_active_file),
                                value = when {
                                    !state.hasAuthoritativeCatalog ->
                                        stringResource(R.string.presets_state_unavailable)
                                    presetModeActive -> state.activePresetFile.ifBlank {
                                        stringResource(R.string.presets_not_set)
                                    }
                                    state.activeMode in commandLineModes -> state.activeCmdlineFile
                                    else -> stringResource(R.string.presets_categories_file)
                                },
                            )
                        }
                    }

                    item {
                        AdaptiveActionGroup(stacked = compactActions) { buttonModifier ->
                            FilledTonalButton(
                                onClick = { activeViewModel?.loadPresets() },
                                enabled = !state.isLoading,
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = buttonModifier,
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(SpacingTokens.Small))
                                Text(stringResource(R.string.action_reload))
                            }
                            Button(
                                onClick = { activeViewModel?.switchToCategoriesMode() },
                                enabled = settingsEnabled && state.activeMode !in categoryModes,
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = buttonModifier,
                            ) {
                                Icon(Icons.Default.Category, contentDescription = null)
                                Spacer(Modifier.width(SpacingTokens.Small))
                                Text(stringResource(R.string.presets_use_categories))
                            }
                        }
                    }

                    if (state.quarantinedCount > 0) {
                        item {
                            QuarantinedPresetsBanner(
                                count = state.quarantinedCount,
                                issueCounts = state.issueCounts,
                            )
                        }
                    }

                    state.lastOutcome?.let { outcome ->
                        item {
                            PresetDurableOutcomeBanner(outcome, state.lastIssue)
                        }
                    }

                    item { SectionHeader(stringResource(R.string.presets_files)) }

                    val loadError = state.loadError
                    if (loadError != null) {
                        item {
                            PresetLoadErrorState(
                                message = loadError.resolve(),
                                onReload = { activeViewModel?.loadPresets() },
                            )
                        }
                    } else if (state.presets.isEmpty() && !state.isLoading) {
                        item { EmptyPresetState(onReload = { activeViewModel?.loadPresets() }) }
                    }

                    items(state.presets, key = PresetEntry::fileName) { preset ->
                        val isActive = presetModeActive && state.activePresetFile == preset.fileName
                        PresetCard(
                            preset = preset,
                            isActive = isActive,
                            enabled = settingsEnabled,
                            onApply = { activeViewModel?.applyPreset(preset.fileName) },
                            onEdit = { activeViewModel?.openPresetEditor(preset.fileName) },
                        )
                    }
                }
            }
        }
        LoadingOverlay(
            text = state.loadingText?.resolve().orEmpty(),
            visible = state.isLoading,
        )
    }
}

@Composable
private fun QuarantinedPresetsBanner(
    count: Int,
    issueCounts: Map<PresetIssue, Int>,
) {
    val warning = MaterialTheme.extendedColors.warning
    val reasons = mutableListOf<String>()
    for ((issue, issueCount) in issueCounts.entries.sortedBy { it.key.wireCode }) {
        reasons += stringResource(
            R.string.presets_quarantined_reason_item,
            issueCount,
            stringResource(issue.labelResource()),
        )
    }
    Surface(
        color = warning.container,
        contentColor = warning.onContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(SpacingTokens.Large),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = warning.color)
            Spacer(Modifier.width(SpacingTokens.Medium))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    quantityStringResource(R.plurals.presets_quarantined_count, count, count),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    stringResource(R.string.presets_quarantined_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (reasons.isNotEmpty()) {
                    Spacer(Modifier.height(SpacingTokens.ExtraSmall))
                    Text(
                        reasons.joinToString(separator = " · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetDurableOutcomeBanner(
    outcome: PresetDurableOutcome,
    issue: PresetIssue?,
) {
    val success = outcome in setOf(
        PresetDurableOutcome.APPLIED,
        PresetDurableOutcome.SAVED,
        PresetDurableOutcome.SAVED_AND_APPLIED,
        PresetDurableOutcome.CATEGORIES_ENABLED,
    )
    val colors = if (success) MaterialTheme.extendedColors.success else MaterialTheme.extendedColors.warning
    val detail = if (outcome == PresetDurableOutcome.REJECTED && issue != null) {
        stringResource(R.string.presets_outcome_rejected_reason, stringResource(issue.labelResource()))
    } else {
        stringResource(outcome.labelResource())
    }
    Surface(
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = detail,
            modifier = Modifier.padding(horizontal = SpacingTokens.Large, vertical = SpacingTokens.Medium),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun PresetIssue.labelResource(): Int = when (this) {
    PresetIssue.UNSAFE_PRESET_NAME,
    PresetIssue.PRESET_NOT_DIRECT_CHILD,
    PresetIssue.UNSAFE_DEPENDENCY_PATH -> R.string.presets_issue_unsafe
    PresetIssue.PRESET_MISSING,
    PresetIssue.DEPENDENCY_MISSING -> R.string.presets_issue_missing
    PresetIssue.DEPENDENCY_NOT_DECLARED -> R.string.presets_issue_not_packaged
    PresetIssue.PRESET_SYMLINK,
    PresetIssue.DEPENDENCY_SYMLINK -> R.string.presets_issue_symlink
    PresetIssue.PRESET_EMPTY,
    PresetIssue.DEPENDENCY_EMPTY,
    PresetIssue.NO_VALID_OPTIONS -> R.string.presets_issue_empty
    PresetIssue.PRESET_TOO_LARGE -> R.string.presets_issue_too_large
    PresetIssue.PRESET_UNREADABLE,
    PresetIssue.DEPENDENCY_UNREADABLE -> R.string.presets_issue_unreadable
    PresetIssue.MALFORMED_PROTOCOL,
    PresetIssue.UNKNOWN -> R.string.presets_issue_unknown
}

private fun PresetDurableOutcome.labelResource(): Int = when (this) {
    PresetDurableOutcome.APPLIED -> R.string.presets_applied
    PresetDurableOutcome.SAVED -> R.string.presets_saved
    PresetDurableOutcome.SAVED_AND_APPLIED -> R.string.presets_saved_applied
    PresetDurableOutcome.CATEGORIES_ENABLED -> R.string.presets_categories_enabled
    PresetDurableOutcome.REJECTED -> R.string.presets_validation_rejected
    PresetDurableOutcome.SOURCE_CHANGED -> R.string.presets_source_changed
    PresetDurableOutcome.RESTART_FAILED_ROLLED_BACK -> R.string.presets_restart_failed_rolled_back
    PresetDurableOutcome.WRITE_FAILED_ROLLED_BACK -> R.string.presets_write_failed_rolled_back
    PresetDurableOutcome.ROLLBACK_FAILED -> R.string.presets_rollback_failed
    PresetDurableOutcome.IO_FAILED -> R.string.presets_io_failed
    PresetDurableOutcome.BLOCKED -> R.string.presets_mutation_blocked
}

@Composable
private fun PresetCard(
    preset: PresetEntry,
    isActive: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
) {
    val success = MaterialTheme.extendedColors.success
    val containerColor = if (isActive) {
        success.container
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (isActive) success.onContainer else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        border = BorderStroke(
            width = SizeTokens.BorderThin,
            color = if (isActive) success.color else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SizeTokens.MinimumTouchTarget)
                .padding(start = SpacingTokens.CardContent, top = SpacingTokens.RowVertical, end = SpacingTokens.Small, bottom = SpacingTokens.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preset.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) success.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Spacer(Modifier.height(SpacingTokens.Small))
                    Surface(
                        color = success.color,
                        contentColor = success.onColor,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = SpacingTokens.ItemVertical, vertical = SpacingTokens.ChipVertical),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(SizeTokens.IconExtraSmall),
                            )
                            Spacer(Modifier.width(SpacingTokens.Compact))
                            Text(
                                stringResource(R.string.presets_active),
                                style = MaterialTheme.typography.labelMediumEmphasized,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.presets_edit_item, preset.displayName),
                )
            }
            FilledTonalButton(
                onClick = onApply,
                enabled = enabled && !isActive,
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = SpacingTokens.RowVertical, vertical = SpacingTokens.Small),
            ) {
                Text(
                    stringResource(
                        if (isActive) R.string.presets_applied else R.string.action_apply,
                    ),
                )
            }
        }
    }
}

@Composable
private fun EmptyPresetState(onReload: () -> Unit) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                text = stringResource(R.string.presets_empty_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.presets_empty_body),
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
private fun PresetLoadErrorState(message: String, onReload: () -> Unit) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                text = stringResource(R.string.presets_load_error_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpacingTokens.Large))
            FilledTonalButton(onClick = onReload, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

private val presetModes = setOf("file", "preset", "txt")
private val commandLineModes = setOf("cmdline")
private val categoryModes = setOf("categories")

@Composable
private fun activeModeLabel(mode: String): String = stringResource(
    when (mode) {
        in presetModes -> R.string.presets_mode_file
        in commandLineModes -> R.string.presets_mode_command
        else -> R.string.presets_mode_categories
    },
)
