package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(
    version: String,
    changelog: String,
    hasApk: Boolean,
    hasModule: Boolean,
    isUpdating: Boolean,
    updateProgress: Float,
    updateStatus: String,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val reduceMotion = LocalReducedMotionEnabled.current
    val progress = updateProgress.coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.update_available_version, version)) },
        text = {
            Column(
                modifier = Modifier.semantics {
                    if (isUpdating) liveRegion = LiveRegionMode.Polite
                },
            ) {
                if (isUpdating) {
                    Text(
                        text = updateStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(SpacingTokens.Large))
                    LinearWavyProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { if (reduceMotion) 0f else 1f },
                    )
                    Spacer(Modifier.height(SpacingTokens.Small))
                    Text(
                        text = stringResource(R.string.update_percent, (progress * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val updateScope = when {
                        hasModule && hasApk -> stringResource(R.string.update_scope_both)
                        hasModule -> stringResource(R.string.update_scope_module)
                        hasApk -> stringResource(R.string.update_scope_apk)
                        else -> ""
                    }
                    if (updateScope.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.update_scope_label, updateScope),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(SpacingTokens.Medium))
                    }

                    Text(
                        text = stringResource(R.string.update_whats_new),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(SpacingTokens.Compact))
                    Column(
                        modifier = Modifier
                            .heightIn(max = SizeTokens.DialogContentMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = changelog.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.update_changelog_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isUpdating) {
                TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
            }
        },
        dismissButton = {
            if (!isUpdating) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
            }
        },
    )
}
