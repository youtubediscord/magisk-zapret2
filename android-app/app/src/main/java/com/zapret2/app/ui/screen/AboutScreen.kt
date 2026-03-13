package com.zapret2.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zapret2.app.BuildConfig
import com.zapret2.app.ui.components.FluentCard
import com.zapret2.app.ui.theme.*

@Composable
fun AboutScreen() {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluentCard {
            Text("Zapret2", fontSize = 20.sp, color = TextPrimary)
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = TextSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("DPI bypass module for Android with Magisk", fontSize = 13.sp, color = TextTertiary)
        }

        FluentCard(modifier = Modifier.clickable { openUrl("https://t.me/bypassblock") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = TelegramBlue)
                Spacer(Modifier.width(12.dp))
                Column { Text("Telegram Group", color = TextPrimary); Text("@bypassblock", fontSize = 12.sp, color = TextSecondary) }
            }
        }

        FluentCard(modifier = Modifier.clickable { openUrl("https://t.me/zapretvpns_bot") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, null, tint = AccentLightBlue)
                Spacer(Modifier.width(12.dp))
                Column { Text("VPN Bot", color = TextPrimary); Text("@zapretvpns_bot", fontSize = 12.sp, color = TextSecondary) }
            }
        }

        FluentCard(modifier = Modifier.clickable { openUrl("https://github.com/bol-van/zapret") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = GithubWhite)
                Spacer(Modifier.width(12.dp))
                Column { Text("bol-van/zapret", color = TextPrimary); Text("Original project", fontSize = 12.sp, color = TextSecondary) }
            }
        }

        FluentCard(modifier = Modifier.clickable { openUrl("https://github.com/youtubediscord") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, tint = AccentLightBlue)
                Spacer(Modifier.width(12.dp))
                Column { Text("youtubediscord", color = TextPrimary); Text("Android module maintainer", fontSize = 12.sp, color = TextSecondary) }
            }
        }
    }
}
