package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*
import kotlinx.coroutines.launch

@Composable
fun ConfigEditorScreen(viewModel: ConfigEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    if (state.showModeDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissModeDialog() },
            containerColor = SurfaceCard,
            title = { Text("Command line restart mode", color = TextPrimary) },
            text = { Text("Current mode is not cmdline. Enable raw cmdline mode and restart?", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissModeDialog(); viewModel.saveCommandLine(restart = true, forceCmdline = true) }) {
                    Text("Enable cmdline mode", color = AccentLight)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.dismissModeDialog(); viewModel.saveCommandLine(restart = true) }) {
                        Text("Restart current mode", color = TextSecondary)
                    }
                    TextButton(onClick = { viewModel.dismissModeDialog() }) { Text("Cancel", color = TextSecondary) }
                }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("/data/adb/modules/zapret2/zapret2/cmdline.txt", fontSize = 12.sp, color = TextTertiary)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.commandText,
                onValueChange = { viewModel.updateCommandText(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = state.actionsEnabled,
                textStyle = MonospaceStyle,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentLightBlue, unfocusedBorderColor = Border, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentLightBlue)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.loadCommandLine() }, enabled = state.actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Reload", color = TextPrimary)
                }
                Button(onClick = { viewModel.saveCommandLine() }, enabled = state.actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                    Text("Save", color = TextPrimary)
                }
                Button(onClick = {
                    scope.launch {
                        if (viewModel.isCmdlineMode()) viewModel.saveCommandLine(restart = true, forceCmdline = true)
                        else viewModel.showModeDialog()
                    }
                }, enabled = state.actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = ButtonSaveRestart), modifier = Modifier.weight(1f)) {
                    Text("Save+Restart", color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}
