package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapret2.app.ui.theme.*

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
    onUpdate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isUpdating) onDismiss() },
        containerColor = SurfaceCard,
        title = {
            Text("Доступно обновление v$version", color = TextPrimary)
        },
        text = {
            Column {
                if (isUpdating) {
                    Text(updateStatus, color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { updateProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentBlue,
                        trackColor = SurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${(updateProgress * 100).toInt()}%",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                } else {
                    // Show what will be updated
                    val updateScope = when {
                        hasModule && hasApk -> "Модуль + APK"
                        hasModule -> "Модуль"
                        hasApk -> "APK"
                        else -> ""
                    }
                    if (updateScope.isNotEmpty()) {
                        Text(
                            "Будет обновлено: $updateScope",
                            color = AccentLightBlue,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text("Что нового:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(changelog, color = TextChangelog, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!isUpdating) {
                TextButton(onClick = onUpdate) {
                    Text("Обновить", color = AccentLight)
                }
            }
        },
        dismissButton = {
            if (!isUpdating) {
                TextButton(onClick = onDismiss) {
                    Text("Позже", color = TextSecondary)
                }
            }
        }
    )
}
