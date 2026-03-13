package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(state.logs, state.autoScroll) {
        if (state.autoScroll && state.logs.isNotBlank()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.currentTab.ordinal, containerColor = SurfaceCard, contentColor = AccentLightBlue) {
                LogTab.entries.forEach { tab ->
                    Tab(selected = state.currentTab == tab, onClick = { viewModel.selectTab(tab) },
                        text = { Text(when (tab) { LogTab.COMMAND -> "Command"; LogTab.LOGS -> "Logs"; LogTab.WARNINGS -> "Errors" }) })
                }
            }

            when (state.currentTab) {
                LogTab.COMMAND -> {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row {
                            Text("Command line", fontSize = 14.sp, color = TextSecondary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { if (state.rawCmdline.isNotBlank()) viewModel.copyToClipboard("Command line", state.rawCmdline) }) {
                                Icon(Icons.Default.ContentCopy, "Copy", tint = AccentLightBlue)
                            }
                        }
                        Text(state.cmdline, style = MonospaceStyle, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    }
                }
                LogTab.LOGS, LogTab.WARNINGS -> {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.filterText,
                            onValueChange = { viewModel.setFilter(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Filter...", color = TextHint) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentLightBlue, unfocusedBorderColor = Border, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary)
                        )
                        IconButton(onClick = { viewModel.toggleAutoScroll() }) {
                            Icon(if (state.autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignTop, "Auto-scroll",
                                tint = if (state.autoScroll) AccentLightBlue else TextSecondary)
                        }
                    }

                    val displayLogs = if (state.filterText.isNotEmpty()) {
                        state.logs.lines().filter { it.contains(state.filterText, ignoreCase = true) }.joinToString("\n")
                    } else state.logs

                    Text(
                        text = displayLogs.ifBlank { if (state.currentTab == LogTab.WARNINGS) "No warnings found" else "No logs available" },
                        style = MonospaceStyle.copy(fontSize = 11.sp, color = TextLog),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(scrollState)
                    )

                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Text("Refresh", color = TextPrimary)
                        }
                        Button(onClick = { viewModel.copyToClipboard("Logs", state.logs) }, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Text("Copy", color = TextPrimary)
                        }
                        Button(onClick = { viewModel.clearLogs() }, colors = ButtonDefaults.buttonColors(containerColor = BtnDanger), modifier = Modifier.weight(1f)) {
                            Text("Clear", color = TextPrimary)
                        }
                    }
                }
            }
        }
    }
}
