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
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun HostsEditorScreen(viewModel: HostsEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("/system/etc/hosts", fontSize = 12.sp, color = TextTertiary)
            Spacer(modifier = Modifier.height(8.dp))
            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentLightBlue, trackColor = SurfaceVariant)
            OutlinedTextField(
                value = state.content,
                onValueChange = { viewModel.updateContent(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = state.actionsEnabled,
                textStyle = MonospaceStyle,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentLightBlue, unfocusedBorderColor = Border, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = AccentLightBlue)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.loadHosts() }, enabled = state.actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Reload", color = TextPrimary)
                }
                Button(onClick = { viewModel.saveHosts() }, enabled = state.actionsEnabled, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue), modifier = Modifier.weight(1f)) {
                    Text("Save", color = TextPrimary)
                }
            }
        }
    }
}
