package com.zapret2.app.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.ui.components.AdaptiveActionGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.HostsEditorOperation
import com.zapret2.app.viewmodel.HostsEditorViewModel
import com.zapret2.app.viewmodel.HostsEditorUiState

@Composable
fun HostsEditorScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: HostsEditorViewModel? = null,
    previewState: HostsEditorUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: HostsEditorUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showReloadDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    val hasUnsavedChanges = state.hasUnsavedChanges
    val contentIsValid = state.content.isNotBlank()
    val contentLineCount = remember(state.content) {
        if (state.content.isBlank()) 0 else state.content.lineSequence().count()
    }

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    LaunchedEffect(
        state.operation,
        state.hasAuthoritativeBaseline,
        hasUnsavedChanges,
    ) {
        if (state.operation != null || !hasUnsavedChanges) {
            showReloadDialog = false
            showExitDialog = false
        }
        if (!state.actionsEnabled) {
            showResetDialog = false
        }
        if (!state.hasAuthoritativeBaseline) {
            showReloadDialog = false
        }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    if (!LocalInspectionMode.current) {
        BackHandler(enabled = hasUnsavedChanges) {
            if (state.operation == null) showExitDialog = true
        }
    }

    if (showReloadDialog && state.actionsEnabled && hasUnsavedChanges) {
        DiscardHostsDialog(
            title = stringResource(R.string.hosts_reload_title),
            message = stringResource(R.string.hosts_reload_body),
            confirmLabel = stringResource(R.string.action_reload),
            onConfirm = {
                showReloadDialog = false
                activeViewModel?.loadHosts(discardUnsavedChanges = true)
            },
            onDismiss = { showReloadDialog = false },
        )
    }

    if (showExitDialog && state.operation == null && hasUnsavedChanges) {
        DiscardHostsDialog(
            title = stringResource(R.string.hosts_discard_title),
            message = stringResource(R.string.hosts_discard_body),
            confirmLabel = stringResource(R.string.action_discard),
            onConfirm = {
                showExitDialog = false
                activeViewModel?.discardUnsavedChanges()
                onNavigateBack()
            },
            onDismiss = { showExitDialog = false },
        )
    }

    if (showResetDialog && state.actionsEnabled) {
        DiscardHostsDialog(
            title = stringResource(R.string.hosts_reset_title),
            message = stringResource(
                if (hasUnsavedChanges) {
                    R.string.hosts_reset_body_unsaved
                } else {
                    R.string.hosts_reset_body
                },
            ),
            confirmLabel = stringResource(R.string.action_reset),
            onConfirm = {
                showResetDialog = false
                activeViewModel?.resetHostsOverlay(
                    confirmed = true,
                    discardUnsavedChanges = hasUnsavedChanges,
                )
            },
            onDismiss = { showResetDialog = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
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
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = SizeTokens.EditorContentMax)
                        .fillMaxSize()
                        .padding(horizontal = horizontalPadding, vertical = SpacingTokens.Large),
                ) {
                    ContentCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(SpacingTokens.RowVertical)
                                        .size(SizeTokens.IconEmphasized),
                                )
                            }
                            Spacer(Modifier.width(SpacingTokens.Large))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.hosts_system_file),
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                )
                                Text(
                                    text = stringResource(R.string.hosts_system_path),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(SpacingTokens.Medium))
                        Text(
                            text = stringResource(R.string.hosts_overlay_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(SpacingTokens.Large))
                    if (state.baselineLoadAttempted && !state.hasAuthoritativeBaseline && !state.isLoading) {
                        Text(
                            text = stringResource(R.string.hosts_read_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                    }
                    OutlinedTextField(
                        value = state.content,
                        onValueChange = { activeViewModel?.updateContent(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        enabled = state.actionsEnabled,
                        isError = hasUnsavedChanges && !contentIsValid,
                        textStyle = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        label = { Text(stringResource(R.string.hosts_entries)) },
                        supportingText = {
                            Text(
                                if (hasUnsavedChanges && !contentIsValid) {
                                    stringResource(R.string.hosts_empty_error)
                                } else {
                                    quantityStringResource(
                                        R.plurals.hosts_line_count,
                                        contentLineCount,
                                        contentLineCount,
                                    )
                                },
                            )
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    Spacer(Modifier.height(SpacingTokens.ItemVertical))
                    EditStateBadge(hasUnsavedChanges = hasUnsavedChanges)
                    Spacer(Modifier.height(SpacingTokens.Medium))

                    HostsEditorActions(
                        reloadEnabled = state.reloadEnabled,
                        mutationEnabled = state.actionsEnabled,
                        hasUnsavedChanges = hasUnsavedChanges,
                        contentIsValid = contentIsValid,
                        onReload = {
                            if (!state.hasAuthoritativeBaseline) {
                                activeViewModel?.revalidateHosts()
                            } else if (hasUnsavedChanges) {
                                showReloadDialog = true
                            } else {
                                activeViewModel?.loadHosts()
                            }
                        },
                        onReset = { showResetDialog = true },
                        onSave = { activeViewModel?.saveHosts() },
                        vertical = compactActions,
                    )
                }
            }
        }
        LoadingOverlay(
            text = stringResource(
                when (state.operation) {
                    HostsEditorOperation.LOAD -> R.string.hosts_loading
                    HostsEditorOperation.RESET -> R.string.hosts_resetting
                    HostsEditorOperation.SAVE,
                    null,
                    -> R.string.hosts_saving
                },
            ),
            visible = state.isLoading,
        )
    }
}

@Composable
private fun EditStateBadge(hasUnsavedChanges: Boolean) {
    val colors = if (hasUnsavedChanges) {
        MaterialTheme.extendedColors.warning
    } else {
        MaterialTheme.extendedColors.success
    }
    Surface(
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = stringResource(
                if (hasUnsavedChanges) R.string.hosts_unsaved else R.string.hosts_no_pending,
            ),
            style = MaterialTheme.typography.labelMediumEmphasized,
            modifier = Modifier.padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.DenseVertical),
        )
    }
}

@Composable
private fun HostsEditorActions(
    reloadEnabled: Boolean,
    mutationEnabled: Boolean,
    hasUnsavedChanges: Boolean,
    contentIsValid: Boolean,
    onReload: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    vertical: Boolean,
) {
    val success = MaterialTheme.extendedColors.success
    AdaptiveActionGroup(stacked = vertical) { buttonModifier ->
        FilledTonalButton(
            onClick = onReload,
            enabled = reloadEnabled,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = buttonModifier,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Small))
            Text(stringResource(R.string.action_reload))
        }
        FilledTonalButton(
            onClick = onReset,
            enabled = mutationEnabled,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = buttonModifier,
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Small))
            Text(stringResource(R.string.hosts_reset_overlay))
        }
        Button(
            onClick = onSave,
            enabled = mutationEnabled && hasUnsavedChanges && contentIsValid,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = success.container,
                contentColor = success.onContainer,
            ),
            modifier = buttonModifier,
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Small))
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun DiscardHostsDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
