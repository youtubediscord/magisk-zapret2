package com.zapret2.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.zapret2.app.ui.theme.AccessibilityTokens
import com.zapret2.app.ui.theme.SpacingTokens

@Composable
fun AdaptiveActionGroup(
    stacked: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) = AdaptiveEqualWidthGroup(
    stacked = stacked,
    modifier = modifier,
    content = content,
)

@Composable
fun AdaptiveEqualWidthGroup(
    stacked: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val useColumn = stacked ||
        LocalDensity.current.fontScale >= AccessibilityTokens.StackGroupsFontScale
    if (useColumn) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
        ) {
            content(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
        ) {
            content(Modifier.weight(1f))
        }
    }
}
