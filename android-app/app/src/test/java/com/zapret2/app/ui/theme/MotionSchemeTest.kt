package com.zapret2.app.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MotionSchemeTest {

    @Test
    fun reducedMotion_usesImmediateSpecsForEveryMaterialMotionRole() {
        val scheme = motionSchemeFor(reducedMotion = true)

        assertTrue(scheme.defaultSpatialSpec<Float>() is SnapSpec)
        assertTrue(scheme.fastSpatialSpec<Float>() is SnapSpec)
        assertTrue(scheme.slowSpatialSpec<Float>() is SnapSpec)
        assertTrue(scheme.defaultEffectsSpec<Float>() is SnapSpec)
        assertTrue(scheme.fastEffectsSpec<Float>() is SnapSpec)
        assertTrue(scheme.slowEffectsSpec<Float>() is SnapSpec)
    }

    @Test
    fun regularMotion_keepsExpressiveSpecs() {
        val scheme = motionSchemeFor(reducedMotion = false)

        assertFalse(scheme.defaultSpatialSpec<Float>() is SnapSpec)
        assertFalse(scheme.defaultEffectsSpec<Float>() is SnapSpec)
    }

    @Test
    fun minimumTouchTarget_meetsAndroidAccessibilityGuidance() {
        assertTrue(SizeTokens.MinimumTouchTarget.value >= 48f)
    }
}
