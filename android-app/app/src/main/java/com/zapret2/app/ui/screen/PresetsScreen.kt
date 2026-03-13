package com.zapret2.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun PresetsScreen(viewModel: PresetsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    state.editingPreset?.let { (fileName, content) ->
        PresetEditorDialog(
            fileName = fileName,
            initialContent = content,
            onDismiss = { viewModel.closePresetEditor() },
            onSave = { viewModel.savePreset(fileName, it, false) },
            onSaveAndApply = { viewModel.savePreset(fileName, it, true) }
        )
    }

    Box {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    FluentCard {
                        SettingRow(title = "Active mode", value = when (state.activeMode) {
                            "file", "preset", "txt" -> "Preset file"; "cmdline", "manual", "raw" -> "Cmdline"; else -> "Categories"
                        })
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingRow(title = "Active file", value = when (state.activeMode) {
                            "file", "preset", "txt" -> state.activePresetFile.ifBlank { "(not set)" }
                            "cmdline", "manual", "raw" -> state.activeCmdlineFile
                            else -> "categories.ini"
                        })
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.loadPresets() }, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Reload", color = TextPrimary)
                        }
                        Button(onClick = { viewModel.switchToCategoriesMode() }, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue), modifier = Modifier.weight(1f)) {
                            Text("Use Categories", color = TextPrimary)
                        }
                    }
                }

                item { SectionHeader("PRESET FILES") }

                if (state.presets.isEmpty() && !state.isLoading) {
                    item { Text("No preset files found", color = TextEmpty, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }

                items(state.presets, key = { it.fileName }) { preset ->
                    val isActive = (state.activeMode in listOf("file", "preset", "txt")) && state.activePresetFile == preset.fileName
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ItemBackground)
                            .clickable { if (!isActive) viewModel.applyPreset(preset.fileName) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.displayName, fontSize = 14.sp, color = TextPrimary)
                            Text(preset.fileName, fontSize = 11.sp, color = TextTertiary)
                            if (isActive) Text("Active", fontSize = 11.sp, color = StatusActive)
                        }
                        IconButton(onClick = { viewModel.openPresetEditor(preset.fileName) }) {
                            Icon(Icons.Default.Edit, "Edit", tint = TextSecondary)
                        }
                        Button(
                            onClick = { viewModel.applyPreset(preset.fileName) },
                            enabled = !isActive,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isActive) BtnSecondary else AccentBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text(if (isActive) "Applied" else "Apply", fontSize = 12.sp, color = TextPrimary) }
                    }
                }
            }
        }
        LoadingOverlay(text = state.loadingText, visible = state.isLoading)
    }
}
