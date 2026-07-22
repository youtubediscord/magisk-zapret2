package com.zapret2.app.ui.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDestinationPolicyTest {

    @Test
    fun destinationRegistryAndNavHostRemainExactAndComplete() {
        val navigationScreens = Screen.navigationGroups.flatMap { it.screens }
        val allScreens = navigationScreens + Screen.HostlistContent
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/navigation/AppNavHost.kt",
        ).readText()
        val destinationNames = listOf(
            "Control",
            "Strategies",
            "Presets",
            "ConfigEditor",
            "Hostlists",
            "HostsEditor",
            "DnsManager",
            "Logs",
            "About",
            "HostlistContent",
        )

        assertEquals(9, navigationScreens.size)
        assertEquals(9, navigationScreens.distinct().size)
        assertEquals(10, allScreens.size)
        assertEquals(10, allScreens.map { it.route }.distinct().size)
        assertFalse(Screen.HostlistContent in navigationScreens)
        assertTrue(source.contains("startDestination = Screen.Control.route"))

        destinationNames.forEach { destination ->
            val registration = Regex(
                "composable\\(\\s*(?:route\\s*=\\s*)?Screen\\.$destination\\.route",
            )
            assertEquals(
                "$destination must have exactly one NavHost registration",
                1,
                registration.findAll(source).count(),
            )
        }
        assertEquals(
            "NavHost must not contain unregistered literal destinations",
            10,
            Regex("\\bcomposable\\s*\\(").findAll(source).count(),
        )
        assertTrue(source.contains("navArgument(\"name\") { type = NavType.StringType }"))
        assertTrue(Screen.isDetailRoute(Screen.HostlistContent.createRoute("verified.txt")))
        assertEquals(
            Screen.Hostlists.route,
            Screen.navigationSelectionForRoute(Screen.HostlistContent.createRoute("verified.txt")),
        )
    }

    private fun repositoryFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository file: $relativePath")
    }
}
