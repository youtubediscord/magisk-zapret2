package com.zapret2.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLazyListPerformancePolicyTest {

    @Test
    fun everyLazyCollection_hasAnExplicitStableKeyAndContentType() {
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
            assertTrue(
                "$fileName lazy collection has no reusable content type: $header",
                "contentType =" in header,
            )
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

        val hostlists = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/HostlistsScreen.kt",
        ).readText()
        assertTrue(hostlists.contains("val compactNumberFormat = remember(locale)"))
        assertFalse(hostlists.contains("private fun formatNumber("))

        val hostlistItem = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/components/HostlistItem.kt",
        ).readText()
        assertTrue(hostlistItem.contains("val formattedSize = remember(context, sizeBytes)"))
    }

    @Test
    fun editorAndReorderHotPaths_avoidRepeatedLinearWorkAndStyleAllocation() {
        listOf(
            "ConfigEditorScreen.kt",
            "HostsEditorScreen.kt",
            "HostlistContentScreen.kt",
        ).forEach { fileName ->
            val source = repositoryFile(
                "android-app/app/src/main/java/com/zapret2/app/ui/screen/$fileName",
            ).readText()
            assertTrue("$fileName must remember its editor text style", source.contains("editorTextStyle = remember("))
            assertFalse("$fileName allocates its text style on every editor recomposition", source.contains("textStyle = MonospaceStyle.copy("))
        }

        val presetEditor = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/components/PresetEditorDialog.kt",
        ).readText()
        assertTrue(presetEditor.contains("editorTextStyle = remember("))
        assertFalse(presetEditor.contains("textStyle = MonospaceStyle.copy("))

        val strategies = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/screen/StrategiesScreen.kt",
        ).readText()
        val pickerRows = strategies.substringAfter("itemsIndexed(\n                            items = displayList")
            .substringBefore("private fun StrategyPickerToolbar(")
        assertFalse(pickerRows.contains("details.indexOf("))
        assertTrue(pickerRows.contains("val currentIndex = if (isReorderMode) index else 0"))
    }

    private fun uiSources(): List<File> =
        repositoryDirectory("android-app/app/src/main/java/com/zapret2/app/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isDirectory) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
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
