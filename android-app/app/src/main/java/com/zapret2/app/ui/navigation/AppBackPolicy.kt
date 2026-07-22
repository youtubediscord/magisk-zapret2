package com.zapret2.app.ui.navigation

import androidx.navigation.NavController

internal const val EXIT_CONFIRMATION_WINDOW_MILLIS = 2_000L

internal enum class AppBackAction {
    CLOSE_NAVIGATION,
    POP_DETAIL,
    NAVIGATE_CONTROL,
    PROMPT_EXIT,
    EXIT,
}

/** Pure navigation policy shared by the shell and JVM tests. */
internal fun resolveAppBackAction(
    drawerOpen: Boolean,
    currentRoute: String?,
    nowMillis: Long,
    lastBackMillis: Long,
): AppBackAction = when {
    drawerOpen -> AppBackAction.CLOSE_NAVIGATION
    Screen.isDetailRoute(currentRoute) -> AppBackAction.POP_DETAIL
    currentRoute != Screen.Control.route -> AppBackAction.NAVIGATE_CONTROL
    lastBackMillis > 0L &&
        nowMillis >= lastBackMillis &&
        nowMillis - lastBackMillis <= EXIT_CONFIRMATION_WINDOW_MILLIS ->
        AppBackAction.EXIT
    else -> AppBackAction.PROMPT_EXIT
}

/** Detail back must remain deterministic even if Navigation restored no parent entry. */
internal fun NavController.popDetailOrOpenHostlists() {
    popToOrOpen(Screen.Hostlists.route)
}

/** Editor exits also need a deterministic target when a restored entry has no parent. */
internal fun NavController.popOrOpenControl() {
    popToOrOpen(Screen.Control.route)
}

private fun NavController.popToOrOpen(route: String) {
    if (currentDestination?.route == route) return
    if (popBackStack(route, inclusive = false)) return
    openAtGraphRoot(route)
}

private fun NavController.openAtGraphRoot(route: String) {
    navigate(route) {
        popUpTo(graph.id)
        launchSingleTop = true
        restoreState = true
    }
}
