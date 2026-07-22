package com.zapret2.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLazyListPerformancePolicyTest {

    @Test
    fun everyLazyCollection_hasAnExplicitStableKey() {
        val calls = uiSources().flatMap { file ->
            val source = file.readText()
            Regex("\\b(?:items|itemsIndexed)\\s*\\(").findAll(source).map { match ->
                val end = source.indexOf(") {", match.range.first)
                assertTrue("Unterminated lazy collection in ${file.name}", end > match.range.first)
                file.name to source.substring(match.range.first, end)
            }.toList()
        }

        assertTrue(calls.isNotEmpty())
        calls.forEach { (fileName, header) ->
            assertTrue("$fileName lazy collection has no stable key: $header", "key =" in header)
        }
    }

    @Test
    fun highVolumeTextRows_reuseFormattersAndTextStyles() {
        val hostlist = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/HostlistContentScreen.kt"
        ).readText()
        val hostlistItems = hostlist.substringAfter("itemsIndexed(\n                        items = state.entries")
            .substringBefore("quantityStringResource(")
        assertTrue(hostlist.contains("val lineNumberFormat = remember { NumberFormat.getIntegerInstance() }"))
        assertTrue(hostlist.contains("val entryTextStyle = remember(entryTextColor)"))
        assertFalse(hostlistItems.contains("NumberFormat.getIntegerInstance()"))
        assertFalse(hostlistItems.contains("MonospaceStyle.copy("))

        val logs = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/LogsScreen.kt"
        ).readText()
        val logItems = logs.substringAfter("itemsIndexed(\n                                items = displayLines")
            .substringBefore("LogActions(")
        assertTrue(logs.contains("val logLineStyle = remember(logLineColor)"))
        assertFalse(logItems.contains("MonospaceStyle.copy("))
    }

    private fun uiSources(): List<File> =
        repositoryDirectory("android-app/app/src/main/java/com/zapret2/app/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isDirectory) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
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
