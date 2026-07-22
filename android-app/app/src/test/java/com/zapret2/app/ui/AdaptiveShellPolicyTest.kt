package com.zapret2.app.ui

import com.zapret2.app.ui.navigation.Screen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveShellPolicyTest {

    @Test
    fun appShell_hasCompactMediumAndExpandedNavigationModes() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/MainActivity.kt"
        ).readText()

        assertTrue(source.contains("val compactShell = maxWidth < SizeTokens.MediumBreakpoint"))
        assertTrue(source.contains("railExpanded = maxWidth >= SizeTokens.ExpandedBreakpoint"))
        assertTrue(source.contains("drawerOpen = compactShell && drawerState.isOpen"))
        assertTrue(source.contains("if (!compactShell) drawerState.close()"))
        assertTrue(source.contains("remember(currentRoute, drawerState.currentValue, compactShell)"))
        assertTrue(source.contains("LaunchedEffect(currentRoute, compactShell, drawerState.currentValue)"))
        assertTrue(source.contains("snackbarHostState.currentSnackbarData?.dismiss()"))
        assertTrue(source.contains("WideNavigationRailValue.Collapsed"))
        assertTrue(source.contains("WideNavigationRailValue.Expanded"))
        assertTrue(source.contains("railExpanded = railExpanded"))
        assertTrue(source.contains("showText = railExpanded"))
        assertTrue(source.contains("contentDescription = if (railExpanded)"))
        assertTrue(source.contains("Screen.mediumRailScreens.forEach"))
        assertTrue(source.contains("Screen.mediumOverflowScreens.any"))
        assertTrue(source.contains("MediumNavigationSheet("))
        assertTrue(source.contains("\"medium_app_shell\""))
        assertTrue(source.contains("\"expanded_app_shell\""))
        assertTrue(source.contains("\"compact_app_shell\""))
        assertTrue(source.contains("railExpanded = true"))
        assertTrue(source.contains("railExpanded = false"))
    }

    @Test
    fun collapsedMediumRail_staysWithinSevenItemsAndKeepsEveryRouteReachable() {
        val allNavigationScreens = Screen.navigationGroups.flatMap { it.screens }

        assertEquals(6, Screen.mediumRailScreens.size)
        assertEquals(6, Screen.mediumRailScreens.distinct().size)
        assertEquals(7, Screen.mediumRailScreens.size + 1)
        assertEquals(2, Screen.mediumOverflowScreens.size)
        assertEquals(
            listOf(Screen.HostsEditor, Screen.About),
            Screen.mediumOverflowScreens,
        )
        assertEquals(
            allNavigationScreens.toSet(),
            (Screen.mediumRailScreens + Screen.mediumOverflowScreens).toSet(),
        )
        assertEquals(
            allNavigationScreens.size,
            (Screen.mediumRailScreens + Screen.mediumOverflowScreens).size,
        )
        assertTrue(Screen.HostlistContent !in Screen.mediumRailScreens)
        assertTrue(Screen.HostlistContent !in Screen.mediumOverflowScreens)
    }

    private fun repositoryFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Cannot locate repository file: $relativePath")
    }
}
