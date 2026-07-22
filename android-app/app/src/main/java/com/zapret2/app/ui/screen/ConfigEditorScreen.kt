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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.R
import com.zapret2.app.ui.components.AdaptiveActionGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.ui.theme.ZapretTheme
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.ConfigEditorViewModel
import com.zapret2.app.viewmodel.ConfigEditorUiState
import kotlinx.coroutines.launch

@Composable
fun ConfigEditorScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConfigEditorViewModel? = null,
    previewState: ConfigEditorUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: ConfigEditorUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val commandLineCount = remember(state.commandText) {
        if (state.commandText.isBlank()) 0 else state.commandText.lineSequence().count()
    }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var showReloadDialog by rememberSaveable { mutableStateOf(false) }

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    LaunchedEffect(
        state.actionsEnabled,
        state.hasUnsavedChanges,
        state.hasAuthoritativeBinding,
    ) {
        if (!state.actionsEnabled || !state.hasUnsavedChanges) {
            showExitDialog = false
        }
        if (!state.actionsEnabled ||
            !state.hasUnsavedChanges ||
            !state.hasAuthoritativeBinding
        ) {
            showReloadDialog = false
        }
    }

    if (!LocalInspectionMode.current) {
        BackHandler(enabled = state.hasUnsavedChanges) {
            if (state.actionsEnabled) showExitDialog = true
        }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    if (showExitDialog && state.actionsEnabled && state.hasUnsavedChanges) {
        DiscardConfigDialog(
            title = stringResource(R.string.config_discard_title),
            body = stringResource(R.string.config_discard_body),
            onDiscard = {
                showExitDialog = false
                activeViewModel?.discardUnsavedChanges()
                onNavigateBack()
            },
            onDismiss = { showExitDialog = false },
        )
    }

    if (showReloadDialog &&
        state.actionsEnabled &&
        state.hasUnsavedChanges &&
        state.hasAuthoritativeBinding
    ) {
        DiscardConfigDialog(
            title = stringResource(R.string.config_reload_title),
            body = stringResource(R.string.config_reload_body),
            onDiscard = {
                showReloadDialog = false
                activeViewModel?.loadCommandLine(discardUnsavedChanges = true)
            },
            onDismiss = { showReloadDialog = false },
        )
    }

    if (state.showModeDialog) {
        AlertDialog(
            onDismissRequest = { activeViewModel?.dismissModeDialog() },
            shape = MaterialTheme.shapes.extraLarge,
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.config_mode_title)) },
            text = {
                Text(
                    text = stringResource(R.string.config_mode_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeViewModel?.dismissModeDialog()
                        activeViewModel?.saveCommandLine(restart = true, forceCmdline = true)
                    },
                ) { Text(stringResource(R.string.action_enable_restart)) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            activeViewModel?.dismissModeDialog()
                            activeViewModel?.saveCommandLine(restart = true)
                        },
                    ) { Text(stringResource(R.string.action_restart_current_mode)) }
                    TextButton(onClick = { activeViewModel?.dismissModeDialog() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
            ) {
                val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) {
                    SpacingTokens.ExtraLarge
                } else {
                    SpacingTokens.Large
                }
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
                            Icon(
                                Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(SizeTokens.IconProminent),
                            )
                            Spacer(Modifier.width(SpacingTokens.Large))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.config_editor_title),
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                )
                                Text(
                                    text = if (state.hasAuthoritativeBinding) {
                                        stringResource(R.string.config_cmdline_path, state.commandFileName)
                                    } else {
                                        stringResource(R.string.config_cmdline_binding_unavailable)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(SpacingTokens.Medium))
                        Text(
                            text = stringResource(R.string.config_editor_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(SpacingTokens.Large))
                    if (state.bindingLoadAttempted && !state.hasAuthoritativeBinding && !state.isLoading) {
                        Text(
                            text = stringResource(
                                if (state.bindingConflict) {
                                    R.string.config_source_changed
                                } else {
                                    R.string.config_load_failed
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(SpacingTokens.Medium))
                    }
                    OutlinedTextField(
                        value = state.commandText,
                        onValueChange = { activeViewModel?.updateCommandText(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        enabled = state.actionsEnabled && state.hasAuthoritativeBinding,
                        textStyle = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        label = { Text(stringResource(R.string.config_editor_field_label)) },
                        supportingText = {
                            Text(
                                if (state.commandText.isBlank()) {
                                    stringResource(R.string.config_editor_empty)
                                } else {
                                    quantityStringResource(
                                        R.plurals.config_line_count,
                                        commandLineCount,
                                        commandLineCount,
                                    )
                                },
                            )
                        },
                        shape = MaterialTheme.shapes.large,
                    )
                    Spacer(Modifier.height(SpacingTokens.Large))

                    EditorActions(
                        reloadEnabled = state.actionsEnabled,
                        saveEnabled = state.actionsEnabled && state.hasAuthoritativeBinding &&
                            state.hasUnsavedChanges,
                        saveAndRestartEnabled = state.actionsEnabled && state.hasAuthoritativeBinding,
                        onReload = {
                            if (!state.hasAuthoritativeBinding && !state.bindingConflict) {
                                activeViewModel?.revalidateCommandLine()
                            } else if (state.hasUnsavedChanges) showReloadDialog = true
                            else activeViewModel?.loadCommandLine()
                        },
                        onSave = { activeViewModel?.saveCommandLine() },
                        onSaveAndRestart = {
                            scope.launch {
                                if (activeViewModel?.isCmdlineMode() == true) {
                                    activeViewModel.saveCommandLine(restart = true, forceCmdline = true)
                                } else {
                                    activeViewModel?.showModeDialog()
                                }
                            }
                        },
                        vertical = compactActions,
                    )
                }
            }
        }
        LoadingOverlay(
            text = if (state.commandText.isBlank()) {
                stringResource(R.string.loading_command_line)
            } else {
                stringResource(R.string.applying_changes)
            },
            visible = !state.actionsEnabled || state.isLoading,
        )
    }
}

@Composable
private fun DiscardConfigDialog(
    title: String,
    body: String,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Code, contentDescription = null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.action_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_keep_editing))
            }
        },
    )
}

@Preview(
    name = "Discard configuration · Russian large text",
    widthDp = 411,
    fontScale = 1.5f,
    locale = "ru",
    showBackground = true,
)
@Composable
private fun DiscardConfigDialogPreview() {
    ZapretTheme(dynamicColor = false) {
        DiscardConfigDialog(
            title = stringResource(R.string.config_discard_title),
            body = stringResource(R.string.config_discard_body),
            onDiscard = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun EditorActions(
    reloadEnabled: Boolean,
    saveEnabled: Boolean,
    saveAndRestartEnabled: Boolean,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
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
        OutlinedButton(
            onClick = onSave,
            enabled = saveEnabled,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = buttonModifier,
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Small))
            Text(stringResource(R.string.action_save))
        }
        Button(
            onClick = onSaveAndRestart,
            enabled = saveAndRestartEnabled,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = success.container,
                contentColor = success.onContainer,
            ),
            modifier = buttonModifier,
        ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Small))
            Text(stringResource(R.string.action_save_restart))
        }
    }
}
