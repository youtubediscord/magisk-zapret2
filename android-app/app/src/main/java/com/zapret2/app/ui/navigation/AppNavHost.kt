package com.zapret2.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.zapret2.app.ui.screen.AboutScreen
import com.zapret2.app.ui.screen.ControlScreen
import com.zapret2.app.ui.screen.DnsManagerScreen
import com.zapret2.app.ui.screen.HostlistContentScreen
import com.zapret2.app.ui.screen.HostlistsScreen
import com.zapret2.app.ui.screen.HostsEditorScreen
import com.zapret2.app.ui.screen.LogsScreen
import com.zapret2.app.ui.screen.PresetsScreen
import com.zapret2.app.ui.screen.ProfilesScreen
import com.zapret2.app.ui.theme.MotionTokens

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Control.route,
        modifier = modifier,
        enterTransition = {
            if (reduceMotion) EnterTransition.None
            else fadeIn(tween(MotionTokens.DurationMedium)) +
                slideInHorizontally(tween(MotionTokens.DurationLong)) { it / 10 }
        },
        exitTransition = {
            if (reduceMotion) ExitTransition.None
            else fadeOut(tween(MotionTokens.DurationShort)) +
                slideOutHorizontally(tween(MotionTokens.DurationMedium)) { -it / 12 }
        },
        popEnterTransition = {
            if (reduceMotion) EnterTransition.None
            else fadeIn(tween(MotionTokens.DurationMedium)) +
                slideInHorizontally(tween(MotionTokens.DurationLong)) { -it / 10 }
        },
        popExitTransition = {
            if (reduceMotion) ExitTransition.None
            else fadeOut(tween(MotionTokens.DurationShort)) +
                slideOutHorizontally(tween(MotionTokens.DurationMedium)) { it / 12 }
        },
    ) {
        composable(Screen.Control.route) { ControlScreen() }
        composable(Screen.Profiles.route) { ProfilesScreen() }
        composable(Screen.Presets.route) { PresetsScreen() }
        composable(Screen.Hostlists.route) { HostlistsScreen(navController) }
        composable(Screen.HostsEditor.route) {
            HostsEditorScreen(
                onNavigateBack = { navController.popOrOpenControl() },
            )
        }
        composable(Screen.DnsManager.route) { DnsManagerScreen() }
        composable(Screen.Logs.route) { LogsScreen() }
        composable(Screen.About.route) { AboutScreen() }
        composable(
            route = Screen.HostlistContent.route,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
            )
        ) {
            HostlistContentScreen(navController)
        }
    }
}
