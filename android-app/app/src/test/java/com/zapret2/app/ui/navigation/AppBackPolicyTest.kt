package com.zapret2.app.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackPolicyTest {

    @Test
    fun drawerAlwaysClosesBeforeNavigationChanges() {
        assertEquals(
            AppBackAction.CLOSE_NAVIGATION,
            resolveAppBackAction(true, Screen.HostlistContent.route, 10_000L, 9_999L),
        )
    }

    @Test
    fun detailRoutePopsItsRealParentStack() {
        assertEquals(
            AppBackAction.POP_DETAIL,
            resolveAppBackAction(false, "hostlist_content/name", 10_000L, 0L),
        )
        assertEquals(
            Screen.Hostlists.route,
            Screen.navigationSelectionForRoute("hostlist_content/name"),
        )
        assertFalse(Screen.isDetailRoute("hostlist_content_evil"))
        assertFalse(Screen.isDetailRoute("hostlist_content/"))
        assertFalse(Screen.isDetailRoute("hostlist_content/name/extra"))
    }

    @Test
    fun detailBack_usesOneFallbackForSystemAndToolbarActions() {
        val policy = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/navigation/AppBackPolicy.kt"
        ).readText()
        val activity = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/MainActivity.kt"
        ).readText()
        val detail = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/HostlistContentScreen.kt"
        ).readText()
        val navHost = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/navigation/AppNavHost.kt"
        ).readText()
        val configEditor = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/ConfigEditorScreen.kt"
        ).readText()
        val hostsEditor = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/HostsEditorScreen.kt"
        ).readText()

        assertTrue(policy.contains("if (popBackStack(route, inclusive = false)) return"))
        assertTrue(policy.contains("popToOrOpen(Screen.Hostlists.route)"))
        assertTrue(policy.contains("popToOrOpen(Screen.Control.route)"))
        assertTrue(policy.contains("popUpTo(graph.id)"))
        assertTrue(activity.contains("navController.popDetailOrOpenHostlists()"))
        assertTrue(activity.contains("navController.popOrOpenControl()"))
        assertTrue(detail.contains("else navController.popDetailOrOpenHostlists()"))
        assertTrue(
            Regex("onNavigateBack = \\{ navController\\.popOrOpenControl\\(\\) }")
                .findAll(navHost)
                .count() == 2,
        )
        assertFalse(configEditor.contains("onNavigateBack: () -> Unit = {}"))
        assertFalse(hostsEditor.contains("onNavigateBack: () -> Unit = {}"))
        assertFalse(detail.contains("else navController.popBackStack()"))
        assertFalse(navHost.contains("if (!navController.popBackStack())"))
    }

    @Test
    fun hostlistDetailRoute_containsOnlyThePercentEncodedFileName() {
        val route = Screen.HostlistContent.createRoute("My List+One.txt")

        assertTrue(route.contains("My%20List%2BOne.txt"))
        assertEquals("hostlist_content/My%20List%2BOne.txt", route)
        assertFalse(route.contains('+'))
    }

    @Test
    fun topLevelRoutesReturnToControl() {
        Screen.navigationGroups
            .flatMap { it.screens }
            .filterNot { it == Screen.Control }
            .forEach { screen ->
                assertEquals(
                    screen.route,
                    AppBackAction.NAVIGATE_CONTROL,
                    resolveAppBackAction(false, screen.route, 10_000L, 0L),
                )
            }
    }

    @Test
    fun controlRequiresSecondBackInsideWindowToExit() {
        assertEquals(
            AppBackAction.PROMPT_EXIT,
            resolveAppBackAction(false, Screen.Control.route, 10_000L, 0L),
        )
        assertEquals(
            AppBackAction.EXIT,
            resolveAppBackAction(false, Screen.Control.route, 11_999L, 10_000L),
        )
        assertEquals(
            AppBackAction.PROMPT_EXIT,
            resolveAppBackAction(false, Screen.Control.route, 12_001L, 10_000L),
        )
        assertEquals(
            AppBackAction.PROMPT_EXIT,
            resolveAppBackAction(false, Screen.Control.route, 9_999L, 10_000L),
        )
    }

    private fun repositoryFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository file: $relativePath")
    }
}
