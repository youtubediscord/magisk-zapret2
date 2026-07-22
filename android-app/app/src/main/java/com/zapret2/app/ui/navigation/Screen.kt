package com.zapret2.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.zapret2.app.R
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(
    val route: String,
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    object Control : Screen("control", R.string.screen_control, Icons.Default.PlayArrow)
    object Profiles : Screen("profiles", R.string.screen_profiles, Icons.Default.Tune)
    object Presets : Screen("presets", R.string.screen_presets, Icons.Default.FolderOpen)
    object Hostlists : Screen("hostlists", R.string.screen_hostlists, Icons.AutoMirrored.Filled.List)
    object HostsEditor : Screen("hosts_editor", R.string.screen_hosts_editor, Icons.Default.EditNote)
    object DnsManager : Screen("dns_manager", R.string.screen_dns_manager, Icons.Default.Dns)
    object Logs : Screen("logs", R.string.screen_logs, Icons.Default.Terminal)
    object About : Screen("about", R.string.screen_about, Icons.Default.Info)
    object HostlistContent : Screen(
        "hostlist_content/{name}",
        R.string.screen_hostlist_content,
        Icons.AutoMirrored.Filled.List,
    ) {
        fun createRoute(name: String): String {
            return "hostlist_content/${encodeRouteSegment(name)}"
        }

        private fun encodeRouteSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    companion object {
        val mainScreens = listOf(Control, Profiles)
        val configScreens = listOf(Presets)
        val dataScreens = listOf(Hostlists, HostsEditor, DnsManager)
        val systemScreens = listOf(Logs, About)

        val navigationGroups = listOf(
            NavigationGroup(R.string.nav_group_main, mainScreens),
            NavigationGroup(R.string.nav_group_configuration, configScreens),
            NavigationGroup(R.string.nav_group_data, dataScreens),
            NavigationGroup(R.string.nav_group_system, systemScreens),
        )

        private val navigationScreens = navigationGroups.flatMap { it.screens }
        val mediumRailScreens = listOf(Control, Profiles, Presets, Hostlists, DnsManager, Logs)
        val mediumOverflowScreens = navigationScreens.filterNot(mediumRailScreens::contains)

        @StringRes
        fun titleForRoute(route: String?): Int =
            navigationScreens.firstOrNull { it.route == route }?.titleRes
                ?: if (isDetailRoute(route)) HostlistContent.titleRes
                else R.string.app_name

        fun isDetailRoute(route: String?): Boolean {
            val segment = route?.takeIf { it.startsWith(HOSTLIST_DETAIL_PREFIX) }
                ?.removePrefix(HOSTLIST_DETAIL_PREFIX)
                ?: return false
            return segment.isNotEmpty() && '/' !in segment
        }

        fun navigationSelectionForRoute(route: String?): String? =
            if (isDetailRoute(route)) Hostlists.route else route

        private const val HOSTLIST_DETAIL_PREFIX = "hostlist_content/"
    }
}

data class NavigationGroup(
    @param:StringRes val titleRes: Int,
    val screens: List<Screen>,
)
