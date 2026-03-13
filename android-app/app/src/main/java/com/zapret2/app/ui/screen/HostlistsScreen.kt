package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.zapret2.app.R
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.navigation.Screen
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun HostlistsScreen(navController: NavController, viewModel: HostlistsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                FluentCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Total domains", fontSize = 12.sp, color = TextTertiary)
                            Text(formatNumber(state.totalDomains), fontSize = 18.sp, color = AccentLightBlue)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Files", fontSize = 12.sp, color = TextTertiary)
                            Text(state.totalFiles.toString(), fontSize = 18.sp, color = TextPrimary)
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = AccentLightBlue)
                        }
                    }
                }
            }
            item { SectionHeader("HOSTLIST FILES") }

            if (state.isLoading && state.hostlists.isEmpty()) {
                item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AccentLightBlue) } }
            }

            if (state.hostlists.isEmpty() && !state.isLoading) {
                item { Text("No hostlist files found", color = TextEmpty, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            }

            items(state.hostlists, key = { it.filename }) { hostlist ->
                HostlistItem(
                    filename = hostlist.filename,
                    domainCount = hostlist.domainCount,
                    sizeBytes = hostlist.sizeBytes,
                    iconRes = getHostlistIcon(hostlist.filename),
                    iconTint = getHostlistColor(hostlist.filename),
                    onClick = { navController.navigate(Screen.HostlistContent.createRoute(hostlist.path, hostlist.filename, hostlist.domainCount)) }
                )
            }
        }

        // Loading indicator at top when refreshing
        if (state.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = AccentLightBlue,
                trackColor = SurfaceVariant
            )
        }
    }
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun getHostlistIcon(filename: String): Int {
    val n = filename.lowercase().removeSuffix(".txt")
    return when {
        n.contains("youtube") || n.contains("twitch") || n.contains("tiktok") -> R.drawable.ic_video
        n.contains("discord") || n.contains("telegram") || n.contains("whatsapp") -> R.drawable.ic_message
        n.contains("facebook") || n.contains("instagram") || n.contains("twitter") -> R.drawable.ic_social
        else -> R.drawable.ic_hostlist
    }
}

private fun getHostlistColor(filename: String): Color {
    val n = filename.lowercase().removeSuffix(".txt")
    return when {
        n.contains("youtube") -> YoutubeRed; n.contains("discord") -> DiscordBlue
        n.contains("telegram") -> TelegramBlue; n.contains("facebook") -> FacebookBlue
        n.contains("twitch") -> TwitchPurple; n.contains("instagram") -> InstagramPink
        else -> StatusSuccess
    }
}
