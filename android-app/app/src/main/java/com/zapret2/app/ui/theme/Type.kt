package com.zapret2.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun expressiveTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontWeight = fontWeight,
    letterSpacing = letterSpacing,
)

val ZapretTypography = Typography(
    displayLarge = expressiveTextStyle(57.sp, 64.sp, FontWeight.Normal, (-0.25).sp),
    displayMedium = expressiveTextStyle(45.sp, 52.sp, FontWeight.Normal),
    displaySmall = expressiveTextStyle(36.sp, 44.sp, FontWeight.Normal),
    headlineLarge = expressiveTextStyle(32.sp, 40.sp, FontWeight.Normal),
    headlineMedium = expressiveTextStyle(28.sp, 36.sp, FontWeight.Normal),
    headlineSmall = expressiveTextStyle(24.sp, 32.sp, FontWeight.Normal),
    titleLarge = expressiveTextStyle(22.sp, 28.sp, FontWeight.Medium),
    titleMedium = expressiveTextStyle(16.sp, 24.sp, FontWeight.Medium, 0.15.sp),
    titleSmall = expressiveTextStyle(14.sp, 20.sp, FontWeight.Medium, 0.10.sp),
    bodyLarge = expressiveTextStyle(16.sp, 24.sp, FontWeight.Normal, 0.50.sp),
    bodyMedium = expressiveTextStyle(14.sp, 20.sp, FontWeight.Normal, 0.25.sp),
    bodySmall = expressiveTextStyle(12.sp, 16.sp, FontWeight.Normal, 0.40.sp),
    labelLarge = expressiveTextStyle(14.sp, 20.sp, FontWeight.SemiBold, 0.10.sp),
    labelMedium = expressiveTextStyle(12.sp, 16.sp, FontWeight.SemiBold, 0.50.sp),
    labelSmall = expressiveTextStyle(11.sp, 16.sp, FontWeight.Medium, 0.50.sp),
    displayLargeEmphasized = expressiveTextStyle(57.sp, 64.sp, FontWeight.Bold, (-0.25).sp),
    displayMediumEmphasized = expressiveTextStyle(45.sp, 52.sp, FontWeight.Bold),
    displaySmallEmphasized = expressiveTextStyle(36.sp, 44.sp, FontWeight.Bold),
    headlineLargeEmphasized = expressiveTextStyle(32.sp, 40.sp, FontWeight.Bold),
    headlineMediumEmphasized = expressiveTextStyle(28.sp, 36.sp, FontWeight.Bold),
    headlineSmallEmphasized = expressiveTextStyle(24.sp, 32.sp, FontWeight.Bold),
    titleLargeEmphasized = expressiveTextStyle(22.sp, 28.sp, FontWeight.Bold),
    titleMediumEmphasized = expressiveTextStyle(16.sp, 24.sp, FontWeight.Bold, 0.15.sp),
    titleSmallEmphasized = expressiveTextStyle(14.sp, 20.sp, FontWeight.Bold, 0.10.sp),
    bodyLargeEmphasized = expressiveTextStyle(16.sp, 24.sp, FontWeight.SemiBold, 0.50.sp),
    bodyMediumEmphasized = expressiveTextStyle(14.sp, 20.sp, FontWeight.SemiBold, 0.25.sp),
    bodySmallEmphasized = expressiveTextStyle(12.sp, 16.sp, FontWeight.SemiBold, 0.40.sp),
    labelLargeEmphasized = expressiveTextStyle(14.sp, 20.sp, FontWeight.Bold, 0.10.sp),
    labelMediumEmphasized = expressiveTextStyle(12.sp, 16.sp, FontWeight.Bold, 0.50.sp),
    labelSmallEmphasized = expressiveTextStyle(11.sp, 16.sp, FontWeight.Bold, 0.50.sp),
)

val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
)
