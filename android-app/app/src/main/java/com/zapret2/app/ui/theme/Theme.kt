package com.zapret2.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ZapretDarkColorScheme = darkColorScheme(
    primary = AccentLightBlue,           // #60CDFF
    onPrimary = BackgroundDark,          // #202020 (dark text on light accent)
    primaryContainer = AccentBlue,       // #0078D4
    onPrimaryContainer = TextPrimary,    // #FFFFFF

    secondary = AccentLight,             // #60CDFF
    onSecondary = BackgroundDark,

    background = BackgroundDark,         // #202020
    onBackground = TextPrimary,          // #FFFFFF

    surface = Surface,                   // #2D2D2D
    onSurface = TextPrimary,             // #FFFFFF
    surfaceVariant = SurfaceVariant,     // #383838
    onSurfaceVariant = TextSecondary,    // #888888

    error = StatusError,                 // #FF6B6B
    onError = TextPrimary,

    outline = Border,                    // #3D3D3D
    outlineVariant = BorderLight,        // #505050

    surfaceContainerLowest = SurfaceVeryDark,   // #1E1E1E
    surfaceContainerLow = BackgroundDarker,     // #1A1A1A
    surfaceContainer = SurfaceCard,             // #2D2D2D
    surfaceContainerHigh = SurfaceVariant,      // #383838
    surfaceContainerHighest = SurfaceInput      // #353535
)

@Composable
fun ZapretTheme(content: @Composable () -> Unit) {
    val colorScheme = ZapretDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BackgroundDarker.toArgb()
            window.navigationBarColor = BackgroundDarker.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZapretTypography,
        shapes = ZapretShapes,
        content = content
    )
}
