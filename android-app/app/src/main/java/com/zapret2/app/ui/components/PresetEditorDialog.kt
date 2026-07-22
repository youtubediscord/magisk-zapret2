package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.data.PresetCommandPreview
import com.zapret2.app.ui.theme.MonospaceStyle

@Composable
fun PresetEditorDialog(
    fileName: String,
    content: String,
    baselineContent: String,
    enabled: Boolean = true,
    dismissEnabled: Boolean = true,
    unavailableMessage: String? = null,
    commandPreview: PresetCommandPreview? = null,
    previewError: String? = null,
    previewLoading: Boolean = false,
    onContentChange: (String) -> Unit,
    onPreview: () -> Unit,
    onCopyPreview: (String) -> Unit,
    onDismiss: (discardUnsavedChanges: Boolean) -> Unit,
    onSave: () -> Unit,
    onSaveAndApply: () -> Unit,
) {
    var showDiscardConfirmation by rememberSaveable(fileName) { mutableStateOf(false) }
    var selectedPage by rememberSaveable(fileName) { mutableStateOf(0) }
    val hasUnsavedChanges = content != baselineContent
    val editorTextColor = MaterialTheme.colorScheme.onSurface
    val editorTextStyle = remember(editorTextColor) { MonospaceStyle.copy(color = editorTextColor) }
    val requestDismiss = {
        when {
            !dismissEnabled -> Unit
            hasUnsavedChanges -> showDiscardConfirmation = true
            else -> onDismiss(false)
        }
    }

    LaunchedEffect(dismissEnabled, hasUnsavedChanges) {
        if (!dismissEnabled || !hasUnsavedChanges) {
            showDiscardConfirmation = false
        }
    }

    if (showDiscardConfirmation && dismissEnabled && hasUnsavedChanges) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.config_discard_title)) },
            text = { Text(stringResource(R.string.preset_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss(true)
                    },
                    enabled = dismissEnabled,
                ) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }, enabled = dismissEnabled) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (dismissEnabled) requestDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.preset_edit_title)) },
        text = {
            Column {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(SpacingTokens.Medium))
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { selectedPage = 0 },
                        enabled = dismissEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.presets_editor_source_tab))
                    }
                    TextButton(
                        onClick = {
                            selectedPage = 1
                            if (commandPreview == null && previewError == null && !previewLoading) onPreview()
                        },
                        enabled = dismissEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.presets_editor_command_tab))
                    }
                }
                Spacer(Modifier.height(SpacingTokens.Small))
                unavailableMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(SpacingTokens.Small))
                }
                if (selectedPage == 0) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = onContentChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = SizeTokens.DialogContentExtraTallMaxHeight),
                        textStyle = editorTextStyle,
                        enabled = enabled,
                        label = { Text(stringResource(R.string.presets_editor_source_label)) },
                        minLines = 3,
                        maxLines = 10,
                    )
                } else {
                    when {
                        previewLoading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = SizeTokens.DialogContentMaxHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(SpacingTokens.Large))
                                CircularProgressIndicator()
                                Spacer(Modifier.height(SpacingTokens.Medium))
                                Text(stringResource(R.string.presets_compiling_preview))
                            }
                        }
                        commandPreview != null -> {
                            Text(
                                stringResource(
                                    R.string.presets_preview_ports,
                                    commandPreview.tcpPorts.ifBlank { "—" },
                                    commandPreview.udpPorts.ifBlank { "—" },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(SpacingTokens.Small))
                            OutlinedTextField(
                                value = commandPreview.rendered,
                                onValueChange = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = SizeTokens.DialogContentExtraTallMaxHeight),
                                textStyle = editorTextStyle,
                                readOnly = true,
                                label = { Text(stringResource(R.string.presets_editor_command_label)) },
                                minLines = 3,
                                maxLines = 10,
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = onPreview, enabled = enabled) {
                                    Text(stringResource(R.string.action_refresh))
                                }
                                TextButton(
                                    onClick = { onCopyPreview(commandPreview.rendered) },
                                    enabled = dismissEnabled,
                                ) {
                                    Text(stringResource(R.string.action_copy))
                                }
                            }
                        }
                        else -> {
                            Text(
                                previewError ?: stringResource(R.string.presets_preview_ready_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (previewError == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Spacer(Modifier.height(SpacingTokens.Medium))
                            TextButton(onClick = onPreview, enabled = enabled) {
                                Text(stringResource(R.string.presets_preview_build))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onSaveAndApply, enabled = enabled && hasUnsavedChanges) {
                    Text(stringResource(R.string.action_save_apply))
                }
                TextButton(onClick = onSave, enabled = enabled && hasUnsavedChanges) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = requestDismiss, enabled = dismissEnabled) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
