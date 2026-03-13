package com.zapret2.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Control : Screen("control", "Control", Icons.Default.PlayArrow)
    object Strategies : Screen("strategies", "Strategies", Icons.Default.Tune)
    object Presets : Screen("presets", "Presets", Icons.Default.FolderOpen)
    object ConfigEditor : Screen("config_editor", "Cmdline", Icons.Default.Code)
    object Hostlists : Screen("hostlists", "Hostlists", Icons.Default.List)
    object HostsEditor : Screen("hosts_editor", "Hosts Editor", Icons.Default.EditNote)
    object DnsManager : Screen("dns_manager", "DNS", Icons.Default.Dns)
    object Logs : Screen("logs", "Logs", Icons.Default.Terminal)
    object About : Screen("about", "About", Icons.Default.Info)
    object HostlistContent : Screen("hostlist_content/{path}/{name}/{count}", "Hostlist", Icons.Default.List) {
        fun createRoute(path: String, name: String, count: Int): String {
            return "hostlist_content/${java.net.URLEncoder.encode(path, "UTF-8")}/${java.net.URLEncoder.encode(name, "UTF-8")}/$count"
        }
    }

    companion object {
        val mainScreens = listOf(Control, Strategies)
        val configScreens = listOf(Presets, ConfigEditor)
        val dataScreens = listOf(Hostlists, HostsEditor, DnsManager)
        val systemScreens = listOf(Logs, About)
    }
}
