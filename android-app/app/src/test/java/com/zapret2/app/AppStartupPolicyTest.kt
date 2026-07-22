package com.zapret2.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupPolicyTest {

    @Test
    fun applicationConfiguresLibsuWithoutStartingAnUnownedRootProbe() {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ZapretApp.kt",
        ).readText()

        assertTrue(source.contains("Shell.setDefaultBuilder("))
        assertFalse(source.contains("checkRootAccess("))
        assertFalse(source.contains("CoroutineScope("))
        assertFalse(source.contains("applicationScope.launch"))
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
