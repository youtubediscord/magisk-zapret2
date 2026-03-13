package com.zapret2.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ZapretTypography = Typography(
    // Display/Headline styles (use defaults)

    // Title Large - screen titles
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp
    ),

    // Title Medium - section headers in cards
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp
    ),

    // Title Small - row titles
    titleSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),

    // Body Large - main text
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),

    // Body Medium - row values
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),

    // Body Small - subtitles
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),

    // Label Large - buttons
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),

    // Label Medium - section headers (uppercase)
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp
    ),

    // Label Small - row subtitles
    labelSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp
    )
)

// Custom text styles not covered by Material3 Typography
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    color = TextPrimary
)

val SectionHeaderStyle = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.8.sp,
    color = TextSecondary
)
