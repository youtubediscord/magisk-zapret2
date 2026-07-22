package com.zapret2.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenDesignTokenPolicyTest {

    @Test
    fun appShellAndDestinationScreens_haveNoUnreviewedLiteralDpMeasurements() {
        val screenDirectory = File("src/main/java/com/zapret2/app/ui/screen")
        assertTrue("Screen source directory is missing: ${screenDirectory.absolutePath}", screenDirectory.isDirectory)
        val appShell = File("src/main/java/com/zapret2/app/MainActivity.kt")
        assertTrue("App shell source is missing: ${appShell.absolutePath}", appShell.isFile)

        val exceptions = readExceptions()
        val policyFiles = screenDirectory
            .listFiles { file -> file.name.endsWith("Screen.kt") }
            .orEmpty()
            .toList() + appShell
        val actual = policyFiles
            .flatMap { file ->
                file.readLines().flatMap { line ->
                    DP_LITERAL.findAll(line).map { match ->
                        DpOccurrence(
                            relativeFile = if (file == appShell) "MainActivity.kt" else file.name,
                            literal = match.value,
                            sourceLine = line.trim(),
                        )
                    }.toList()
                }
            }

        val actualCounts = actual.groupingBy(DpOccurrence::key).eachCount()
        val expectedCounts = exceptions.associate { it.key to it.expectedCount }
        assertEquals(
            "Literal dp measurements in destination screens must be replaced by semantic design tokens, " +
                "or explicitly reviewed in design-token-dp-exceptions.tsv with an exact source line and reason.",
            expectedCounts,
            actualCounts,
        )
    }

    @Test
    fun appUiTweenDurations_useSemanticMotionTokens() {
        val uiDirectory = File("src/main/java/com/zapret2/app/ui")
        val appShell = File("src/main/java/com/zapret2/app/MainActivity.kt")
        val sources = uiDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList() + appShell
        val violations = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (LITERAL_TWEEN_DURATION.containsMatchIn(line)) {
                    "${file.name}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            "Tween durations must use MotionTokens instead of local numeric literals: $violations",
            violations.isEmpty(),
        )
    }

    private fun readExceptions(): List<DpException> {
        val resource = requireNotNull(javaClass.classLoader?.getResource("design-token-dp-exceptions.tsv")) {
            "Missing design-token-dp-exceptions.tsv"
        }
        return resource.readText()
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapIndexed { index, line ->
                val fields = line.split('\t', limit = 5)
                require(fields.size == 5) { "Invalid token exception at data line ${index + 1}: $line" }
                val count = fields[2].toIntOrNull()
                require(count != null && count > 0) { "Exception count must be positive: $line" }
                require(fields[4].isNotBlank()) { "Exception reason must not be blank: $line" }
                DpException(
                    relativeFile = fields[0],
                    literal = fields[1],
                    expectedCount = count,
                    sourceLine = fields[3],
                )
            }
            .toList()
    }

    private data class DpOccurrence(
        val relativeFile: String,
        val literal: String,
        val sourceLine: String,
    ) {
        val key: String = "$relativeFile\t$literal\t$sourceLine"
    }

    private data class DpException(
        val relativeFile: String,
        val literal: String,
        val expectedCount: Int,
        val sourceLine: String,
    ) {
        val key: String = "$relativeFile\t$literal\t$sourceLine"
    }

    private companion object {
        val DP_LITERAL = Regex("""(?<![A-Za-z0-9_.])\d[\d_]*(?:\.\d+)?\.dp\b""")
        val LITERAL_TWEEN_DURATION = Regex("""tween\([^)]*\b\d[\d_]*\b""")
    }
}
