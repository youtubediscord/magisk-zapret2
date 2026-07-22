package com.zapret2.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SemanticColorFamily(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
)

@Immutable
data class ExtendedColors(
    val success: SemanticColorFamily,
    val warning: SemanticColorFamily,
    val info: SemanticColorFamily,
)

internal val LightExtendedColors = ExtendedColors(
    success = SemanticColorFamily(
        color = LightSuccess,
        onColor = LightOnSuccess,
        container = LightSuccessContainer,
        onContainer = LightOnSuccessContainer,
    ),
    warning = SemanticColorFamily(
        color = LightWarning,
        onColor = LightOnWarning,
        container = LightWarningContainer,
        onContainer = LightOnWarningContainer,
    ),
    info = SemanticColorFamily(
        color = LightInfo,
        onColor = LightOnInfo,
        container = LightInfoContainer,
        onContainer = LightOnInfoContainer,
    ),
)

internal val DarkExtendedColors = ExtendedColors(
    success = SemanticColorFamily(
        color = DarkSuccess,
        onColor = DarkOnSuccess,
        container = DarkSuccessContainer,
        onContainer = DarkOnSuccessContainer,
    ),
    warning = SemanticColorFamily(
        color = DarkWarning,
        onColor = DarkOnWarning,
        container = DarkWarningContainer,
        onContainer = DarkOnWarningContainer,
    ),
    info = SemanticColorFamily(
        color = DarkInfo,
        onColor = DarkOnInfo,
        container = DarkInfoContainer,
        onContainer = DarkOnInfoContainer,
    ),
)

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current
