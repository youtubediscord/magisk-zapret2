package com.zapret2.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun DnsManagerScreen(viewModel: DnsManagerViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPresetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    if (showPresetPicker && state.hostsData != null) {
        val presets = state.hostsData!!.dnsPresets
        AlertDialog(
            onDismissRequest = { showPresetPicker = false },
            containerColor = SurfaceCard,
            title = { Text("DNS Preset", color = TextPrimary) },
            text = {
                Column {
                    presets.forEachIndexed { idx, name ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            RadioButton(selected = idx == state.selectedPresetIndex, onClick = { viewModel.selectPreset(idx); showPresetPicker = false },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentLightBlue))
                            Text(name, color = TextPrimary, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetPicker = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }

    Box {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            if (state.errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .clickable { viewModel.loadData() },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("Error: ${state.errorMessage}\n\nTap to retry", color = TextSecondary, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    item {
                        SettingRow(title = "DNS Preset", value = state.hostsData?.dnsPresets?.getOrElse(state.selectedPresetIndex) { "" } ?: "",
                            onClick = { showPresetPicker = true })
                    }

                    if (state.hostsData != null) {
                        item {
                            SectionHeader("DNS SERVICES")
                            val allDnsChecked = state.hostsData!!.dnsServices.isNotEmpty() && state.hostsData!!.dnsServices.all { it.name in state.selectedDnsServices }
                            DnsServiceItem(name = "Select All", info = "${state.hostsData!!.dnsServices.size} services", checked = allDnsChecked,
                                onCheckedChange = { viewModel.selectAllDns(it) })
                        }
                        items(state.hostsData!!.dnsServices) { service ->
                            DnsServiceItem(name = service.name, info = "${service.domains.size} domains",
                                checked = service.name in state.selectedDnsServices, onCheckedChange = { viewModel.toggleDnsService(service.name) })
                        }
                        item {
                            SectionHeader("DIRECT SERVICES")
                            val allDirectChecked = state.hostsData!!.directServices.isNotEmpty() && state.hostsData!!.directServices.all { it.name in state.selectedDirectServices }
                            DnsServiceItem(name = "Select All", info = "${state.hostsData!!.directServices.size} services", checked = allDirectChecked,
                                onCheckedChange = { viewModel.selectAllDirect(it) })
                        }
                        items(state.hostsData!!.directServices) { service ->
                            DnsServiceItem(name = service.name, info = "${service.entries.size} entries",
                                checked = service.name in state.selectedDirectServices, onCheckedChange = { viewModel.toggleDirectService(service.name) })
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.resetDns() }, colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary), modifier = Modifier.weight(1f)) {
                                Text("Reset", color = TextPrimary)
                            }
                            Button(onClick = { viewModel.applyDns() }, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue), modifier = Modifier.weight(1f)) {
                                Text("Apply", color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
        LoadingOverlay(text = state.loadingText, visible = state.isLoading)
    }
}
