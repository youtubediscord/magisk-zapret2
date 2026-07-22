package com.zapret2.app.ui.theme

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastPolicyTest {

    @Test
    fun fixedLightAndDarkSemanticPairs_meetNormalTextContrast() {
        val colors = parseColors()
        val pairs = buildList {
            listOf("Light", "Dark").forEach { mode ->
                listOf(
                    "Primary",
                    "Secondary",
                    "Tertiary",
                    "Error",
                    "Success",
                    "Warning",
                    "Info",
                ).forEach { family ->
                    add("$mode$family" to "${mode}On$family")
                    add("${mode}${family}Container" to "${mode}On${family}Container")
                }
                add("${mode}Background" to "${mode}OnBackground")
                add("${mode}Surface" to "${mode}OnSurface")
                add("${mode}SurfaceVariant" to "${mode}OnSurfaceVariant")
                add("${mode}InverseSurface" to "${mode}InverseOnSurface")
            }
        }

        val failures = pairs.mapNotNull { (backgroundName, foregroundName) ->
            val background = requireNotNull(colors[backgroundName]) {
                "Missing fixed theme color: $backgroundName"
            }
            val foreground = requireNotNull(colors[foregroundName]) {
                "Missing fixed theme color: $foregroundName"
            }
            val ratio = contrastRatio(background, foreground)
            if (ratio >= NORMAL_TEXT_MIN_CONTRAST) {
                null
            } else {
                "$foregroundName on $backgroundName = ${"%.2f".format(ratio)}:1"
            }
        }

        assertTrue(
            "Fixed semantic theme pairs below 4.5:1: ${failures.joinToString()}",
            failures.isEmpty(),
        )
    }

    private fun parseColors(): Map<String, String> {
        val source = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/ui/theme/Color.kt",
        ).readText()
        return COLOR_DECLARATION.findAll(source).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun contrastRatio(firstHex: String, secondHex: String): Double {
        val first = relativeLuminance(firstHex)
        val second = relativeLuminance(secondHex)
        return (max(first, second) + LUMINANCE_OFFSET) /
            (min(first, second) + LUMINANCE_OFFSET)
    }

    private fun relativeLuminance(hex: String): Double {
        val channels = hex.chunked(2).map { channel ->
            val normalized = channel.toInt(radix = 16) / MAX_COLOR_CHANNEL
            if (normalized <= SRGB_LINEAR_THRESHOLD) {
                normalized / SRGB_LINEAR_DIVISOR
            } else {
                ((normalized + SRGB_OFFSET) / SRGB_DIVISOR).pow(SRGB_EXPONENT)
            }
        }
        return RED_LUMINANCE * channels[0] +
            GREEN_LUMINANCE * channels[1] +
            BLUE_LUMINANCE * channels[2]
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

    private companion object {
        val COLOR_DECLARATION = Regex(
            "internal val (\\w+) = Color\\(0xFF([0-9A-Fa-f]{6})\\)",
        )
        const val NORMAL_TEXT_MIN_CONTRAST = 4.5
        const val LUMINANCE_OFFSET = 0.05
        const val MAX_COLOR_CHANNEL = 255.0
        const val SRGB_LINEAR_THRESHOLD = 0.04045
        const val SRGB_LINEAR_DIVISOR = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_DIVISOR = 1.055
        const val SRGB_EXPONENT = 2.4
        const val RED_LUMINANCE = 0.2126
        const val GREEN_LUMINANCE = 0.7152
        const val BLUE_LUMINANCE = 0.0722
    }
}
