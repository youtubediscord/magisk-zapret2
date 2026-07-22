package com.zapret2.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPreferenceTest {

    @Test
    fun zeroOrNegativeAnimatorScale_requestsNoMotion() {
        assertTrue(isReducedMotionScale(0f))
        assertTrue(isReducedMotionScale(-1f))
        assertTrue(isReducedMotionScale(Float.NaN))
        assertTrue(isReducedMotionScale(Float.POSITIVE_INFINITY))
        assertTrue(isReducedMotionScale(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun positiveAnimatorScale_keepsMotionEnabled() {
        assertFalse(isReducedMotionScale(0.01f))
        assertFalse(isReducedMotionScale(1f))
        assertFalse(isReducedMotionScale(10f))
    }

    @Test
    fun systemAnimatorScale_hasOneThemeOwnedObserver() {
        val sourceRoot = File("src/main/java/com/zapret2/app")
        val users = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("rememberReducedMotionEnabled()") }
            .map { it.name }
            .toSet()

        assertEquals(setOf("MotionPreference.kt", "Theme.kt"), users)
    }
}
