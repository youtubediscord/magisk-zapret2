package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zapret2.app.ui.theme.*

@Composable
fun PresetEditorDialog(
    fileName: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (content: String) -> Unit,
    onSaveAndApply: (content: String) -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text("Edit preset", color = TextPrimary) },
        text = {
            Column {
                Text(fileName, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentLightBlue,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentLightBlue
                    ),
                    textStyle = MonospaceStyle
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onSaveAndApply(content) }) {
                    Text("Save + Apply", color = AccentLight)
                }
                TextButton(onClick = { onSave(content) }) {
                    Text("Save", color = AccentLight)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
