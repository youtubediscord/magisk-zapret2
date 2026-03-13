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
    downloadProgress: Int,
    isDownloading: Boolean,
    statusText: String,
    onDismiss: () -> Unit,
    onUpdateApk: () -> Unit,
    onUpdateModule: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = SurfaceCard,
        title = {
            Text("Доступно обновление v$version", color = TextPrimary)
        },
        text = {
            Column {
                if (isDownloading) {
                    Text(statusText, color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentLightBlue,
                        trackColor = SurfaceVariant
                    )
                } else {
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
            if (!isDownloading) {
                Column {
                    if (hasApk) {
                        TextButton(onClick = onUpdateApk) {
                            Text("Обновить APK", color = AccentLight)
                        }
                    }
                    if (hasModule) {
                        TextButton(onClick = onUpdateModule) {
                            Text("Обновить модуль", color = AccentLight)
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Позже", color = TextSecondary)
                }
            }
        }
    )
}
