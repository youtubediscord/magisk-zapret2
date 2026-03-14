package com.zapret2.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun ControlScreen(viewModel: ControlViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showUpdateDialog by remember { mutableStateOf<com.zapret2.app.data.UpdateManager.Release?>(null) }
    var errorDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ControlEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is ControlEvent.ShowUpdateDialog -> showUpdateDialog = event.release
                is ControlEvent.ShowErrorDialog -> errorDialogData = Pair(event.title, event.details)
            }
        }
    }

    showUpdateDialog?.let { release ->
        UpdateDialog(
            version = release.version,
            changelog = release.changelog,
            hasApk = release.apkUrl != null,
            hasModule = release.moduleUrl != null,
            downloadProgress = 0,
            isDownloading = false,
            statusText = "",
            onDismiss = { showUpdateDialog = null },
            onUpdateApk = { release.apkUrl?.let { viewModel.downloadAndInstallApk(it) }; showUpdateDialog = null },
            onUpdateModule = { release.moduleUrl?.let { viewModel.downloadAndInstallModule(it) }; showUpdateDialog = null }
        )
    }

    errorDialogData?.let { (title, details) ->
        AlertDialog(
            onDismissRequest = { errorDialogData = null },
            containerColor = SurfaceCard,
            titleContentColor = StatusError,
            title = { Text(title, color = StatusError) },
            text = {
                Text(
                    text = details,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Error details", details))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy", color = AccentBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDialogData = null }) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Status card
            item {
                FluentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(isActive = state.isRunning, size = 12.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = state.statusText, fontSize = 18.sp, color = if (state.isRunning) StatusActive else TextSecondary)
                            if (state.uptime.isNotEmpty()) Text("Uptime: ${state.uptime}", fontSize = 12.sp, color = TextTertiary)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val btnColor by animateColorAsState(if (state.isRunning) BtnDanger else BtnSuccess, label = "btn")
                    Button(
                        onClick = { viewModel.toggleService() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isToggling,
                        colors = ButtonDefaults.buttonColors(containerColor = btnColor)
                    ) {
                        if (state.isToggling) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TextPrimary, strokeWidth = 2.dp)
                        else Text(if (state.isRunning) "Stop Service" else "Start Service", color = TextPrimary)
                    }
                }
            }

            // Module info
            item {
                SectionHeader("MODULE")
                FluentCard {
                    SettingRow(title = "Module version", value = state.moduleVersion.ifEmpty { "N/A" })
                }
            }

            // Settings
            item {
                SectionHeader("SETTINGS")
                FluentCard {
                    SettingToggleRow(title = "Autostart on boot", checked = state.autostart, onCheckedChange = { viewModel.setAutostart(it) }, icon = Icons.Default.PowerSettingsNew)
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingToggleRow(title = "WiFi only", checked = state.wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) }, icon = Icons.Default.Wifi, subtitle = "Only run on WiFi networks")
                }
            }

            // Packet settings
            item {
                SectionHeader("PACKET INTERCEPTION")
                FluentCard {
                    SettingRow(title = "PKT_OUT", value = state.pktOut.toString(), icon = Icons.Default.CallMade, onClick = { viewModel.adjustPktOut(state.pktOut + 5) })
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingRow(title = "PKT_IN", value = state.pktIn.toString(), icon = Icons.Default.CallReceived, onClick = { viewModel.adjustPktIn(state.pktIn + 5) })
                }
            }

            // Network & iptables info
            item {
                SectionHeader("NETWORK")
                FluentCard {
                    SettingRow(title = "Network type", value = state.networkType)
                    if (state.wifiSsid != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingRow(title = "WiFi SSID", value = state.wifiSsid!!)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingRow(title = "iptables", value = if (state.iptablesActive) "Active" else "Inactive")
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingRow(title = "NFQUEUE rules", value = state.nfqueueRulesCount.toString())
                }
            }

            // Process stats (only when running)
            if (state.isRunning) {
                item {
                    SectionHeader("PROCESS")
                    FluentCard {
                        SettingRow(title = "PID", value = state.processStats.pid)
                        if (state.processStats.memory.isNotEmpty()) { Spacer(Modifier.height(4.dp)); SettingRow(title = "Memory", value = state.processStats.memory) }
                        if (state.processStats.threads.isNotEmpty()) { Spacer(Modifier.height(4.dp)); SettingRow(title = "Threads", value = state.processStats.threads) }
                    }
                }
            }

            // Update check button
            item {
                SectionHeader("UPDATES")
                Button(
                    onClick = { viewModel.checkForUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BtnSecondary)
                ) { Text("Check for updates", color = TextPrimary) }
            }
        }
    }
}
