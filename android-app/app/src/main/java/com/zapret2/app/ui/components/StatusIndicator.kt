package com.zapret2.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.zapret2.app.R
import com.zapret2.app.ui.theme.MotionTokens
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.extendedColors

@Composable
fun StatusIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = SizeTokens.StatusIndicator,
    activeColor: Color? = null,
    inactiveColor: Color? = null,
    exposeState: Boolean = true,
) {
    val reduceMotion = LocalReducedMotionEnabled.current
    val resolvedColor = if (isActive) {
        activeColor ?: MaterialTheme.extendedColors.success.color
    } else {
        inactiveColor ?: MaterialTheme.colorScheme.outline
    }
    val activeDescription = stringResource(R.string.state_active)
    val inactiveDescription = stringResource(R.string.state_inactive)
    val semanticsModifier = if (exposeState) {
        Modifier.clearAndSetSemantics {
            stateDescription = if (isActive) activeDescription else inactiveDescription
        }
    } else {
        Modifier.clearAndSetSemantics { }
    }

    if (isActive && !reduceMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "status pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.86f,
            targetValue = 1.16f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = MotionTokens.DurationPulse),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status scale",
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.62f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = MotionTokens.DurationPulse),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status alpha",
        )
        Box(
            modifier = modifier
                .then(semanticsModifier)
                .size(size)
                .scale(scale)
                .alpha(alpha)
                .background(resolvedColor, CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .then(semanticsModifier)
                .size(size)
                .background(resolvedColor, CircleShape),
        )
    }
}
