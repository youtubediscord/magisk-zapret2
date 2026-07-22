package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.ui.theme.MonospaceStyle

@Composable
fun PresetEditorDialog(
    fileName: String,
    content: String,
    baselineContent: String,
    enabled: Boolean = true,
    dismissEnabled: Boolean = true,
    unavailableMessage: String? = null,
    onContentChange: (String) -> Unit,
    onDismiss: (discardUnsavedChanges: Boolean) -> Unit,
    onSave: () -> Unit,
    onSaveAndApply: () -> Unit,
) {
    var showDiscardConfirmation by rememberSaveable(fileName) { mutableStateOf(false) }
    val hasUnsavedChanges = content != baselineContent
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
                unavailableMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(SpacingTokens.Small))
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SizeTokens.DialogContentExtraTallMaxHeight),
                    textStyle = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.preset_strategy_command)) },
                    minLines = 3,
                    maxLines = 10,
                )
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
