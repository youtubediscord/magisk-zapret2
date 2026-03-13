package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zapret2.app.R
import com.zapret2.app.data.StrategyRepository
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun StrategiesScreen(viewModel: StrategiesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pickerState by remember { mutableStateOf<CategoryUiModel?>(null) }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    pickerState?.let { cat ->
        val strategies = remember(cat.type) { mutableStateListOf<StrategyItem>() }
        LaunchedEffect(cat.type) {
            val list = when (cat.type) {
                "tcp" -> StrategyRepository.getTcpStrategies()
                "udp" -> StrategyRepository.getUdpStrategies()
                "voice" -> StrategyRepository.getStunStrategies()
                else -> StrategyRepository.getTcpStrategies()
            }
            strategies.clear()
            strategies.addAll(list.map { StrategyItem(it.id, it.displayName, if (it.id == "disabled") "No DPI bypass" else "") })
        }
        if (strategies.isNotEmpty()) {
            StrategyPickerSheet(
                title = cat.title,
                subtitle = cat.subtitle,
                strategies = strategies,
                selectedId = cat.strategyName,
                canSwitchFilter = cat.canSwitchFilter,
                currentFilterMode = cat.filterMode,
                onDismiss = { pickerState = null },
                onSelected = { id, filter -> viewModel.selectStrategy(cat.key, id, filter); pickerState = null }
            )
        }
    }

    Box {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item { SectionHeader("CATEGORIES") }
                items(state.categories, key = { it.key }) { cat ->
                    CategoryRow(
                        title = cat.title,
                        subtitle = cat.subtitle,
                        value = cat.strategyDisplayName,
                        iconRes = resolveCategoryIcon(cat.key, cat.type),
                        iconTint = resolveCategoryColor(cat.key, cat.type),
                        onClick = { pickerState = cat }
                    )
                }
                item { Spacer(modifier = Modifier.height(12.dp)) }
                item { SectionHeader("ADVANCED") }
                item {
                    var showPktPicker by remember { mutableStateOf(false) }
                    SettingRow(title = "PKT_COUNT", value = state.pktCount, onClick = { showPktPicker = true })
                    if (showPktPicker) {
                        val pktOptions = listOf("1", "3", "5", "10", "15", "20")
                        StrategyPickerSheet(
                            title = "PKT_COUNT",
                            subtitle = "Packets to modify",
                            strategies = pktOptions.map { StrategyItem(it, it) },
                            selectedId = state.pktCount,
                            onDismiss = { showPktPicker = false },
                            onSelected = { id, _ -> viewModel.setPktCount(id); showPktPicker = false }
                        )
                    }
                }
                item {
                    var showDebugPicker by remember { mutableStateOf(false) }
                    SettingRow(title = "Debug Mode", value = when (state.debugMode) {
                        "none" -> "None"; "android" -> "Android"; "file" -> "File"; "syslog" -> "Syslog"; else -> "None"
                    }, onClick = { showDebugPicker = true })
                    if (showDebugPicker) {
                        StrategyPickerSheet(
                            title = "Debug Mode",
                            subtitle = "Log destination",
                            strategies = listOf(
                                StrategyItem("none", "None", "Logging disabled"),
                                StrategyItem("android", "Android (logcat)", "Output to logcat"),
                                StrategyItem("file", "File", "Write to file"),
                                StrategyItem("syslog", "Syslog", "System logger")
                            ),
                            selectedId = state.debugMode,
                            onDismiss = { showDebugPicker = false },
                            onSelected = { id, _ -> viewModel.setDebugMode(id); showDebugPicker = false }
                        )
                    }
                }
            }
        }
        LoadingOverlay(text = state.loadingText, visible = state.isLoading)
    }
}

private fun resolveCategoryIcon(key: String, type: String): Int {
    val k = key.lowercase()
    return when {
        k.contains("youtube") || k.contains("googlevideo") || k.contains("twitch") -> R.drawable.ic_video
        k.contains("discord") || k.contains("telegram") || k.contains("whatsapp") -> R.drawable.ic_message
        k.contains("voice") || type == "voice" -> R.drawable.ic_message
        k.contains("facebook") || k.contains("instagram") || k.contains("twitter") -> R.drawable.ic_social
        else -> R.drawable.ic_apps
    }
}

private fun resolveCategoryColor(key: String, type: String): Color {
    val k = key.lowercase()
    return when {
        k.contains("youtube") -> YoutubeRed
        k.contains("googlevideo") -> GooglevideoRed
        k.contains("twitch") -> TwitchPurple
        k.contains("discord") -> DiscordBlue
        k.contains("telegram") -> TelegramBlue
        k.contains("whatsapp") -> WhatsappGreen
        k.contains("voice") || type == "voice" -> VoicePurple
        k.contains("facebook") -> FacebookBlue
        k.contains("instagram") -> InstagramPink
        k.contains("twitter") -> TwitterBlue
        k.contains("github") -> GithubWhite
        k.contains("soundcloud") -> SoundcloudOrange
        k.contains("steam") -> SteamBlue
        type == "udp" -> UdpBlue
        else -> StatusSuccess
    }
}
