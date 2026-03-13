package com.zapret2.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zapret2.app.ui.screen.*

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Control.route,
        modifier = modifier
    ) {
        composable(Screen.Control.route) { ControlScreen() }
        composable(Screen.Strategies.route) { StrategiesScreen() }
        composable(Screen.Presets.route) { PresetsScreen() }
        composable(Screen.ConfigEditor.route) { ConfigEditorScreen() }
        composable(Screen.Hostlists.route) { HostlistsScreen(navController) }
        composable(Screen.HostsEditor.route) { HostsEditorScreen() }
        composable(Screen.DnsManager.route) { DnsManagerScreen() }
        composable(Screen.Logs.route) { LogsScreen() }
        composable(Screen.About.route) { AboutScreen() }
        composable(
            route = Screen.HostlistContent.route,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("count") { type = NavType.IntType }
            )
        ) {
            HostlistContentScreen(navController)
        }
    }
}
