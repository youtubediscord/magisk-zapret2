package com.zapret2.app.ui.theme

import androidx.compose.ui.unit.dp

object SpacingTokens {
    /** No inset; named to keep screen layouts free of unreviewed literal dimensions. */
    val None = 0.dp
    val Micro = 2.dp
    val ExtraSmall = 4.dp
    val ChipVertical = 5.dp
    val Compact = 6.dp
    val DenseVertical = 7.dp
    val Small = 8.dp
    val ItemVertical = 10.dp
    val Medium = 12.dp
    val RowVertical = 14.dp
    val Large = 16.dp
    val CardContent = 20.dp
    val ExtraLarge = 24.dp
    val DrawerHeaderStart = 28.dp
    val Section = 32.dp
}

object SizeTokens {
    val BorderThin = 1.dp
    val StatusIndicator = 10.dp
    val IconExtraSmall = 16.dp
    val IconSmall = 18.dp
    val IconCompact = 20.dp
    val IconMedium = 24.dp
    val IconEmphasized = 28.dp
    val IconProminent = 32.dp
    val LoadingMedium = 36.dp
    val BrandMarkCompact = 36.dp
    val Illustration = 40.dp
    val LoadingCompact = 44.dp
    val IconLarge = 48.dp
    val BrandMarkExpanded = 48.dp
    val MinimumTouchTarget = 48.dp
    val LineNumberColumnWidth = 48.dp
    val LeadingContentInset = 56.dp
    val TrailingValueMax = 144.dp
    val DialogContentMaxHeight = 220.dp
    val DialogContentTallMaxHeight = 240.dp
    val DialogContentExtraTallMaxHeight = 320.dp
    val SheetContentMaxHeight = 440.dp
    val DialogContentMaxWidth = 560.dp
    val MediumBreakpoint = 600.dp
    val CompactActionsBreakpoint = 640.dp
    val LogsCompactBreakpoint = 700.dp
    val ExpandedBreakpoint = 840.dp
    val ContentMax = 960.dp
    val EditorContentMax = 1_000.dp
    val LogsContentMax = 1_200.dp
}

object AccessibilityTokens {
    const val StackGroupsFontScale = 1.3f
}

object ElevationTokens {
    val Flat = 0.dp
    val Resting = 1.dp
    val Raised = 3.dp
    val Overlay = 6.dp
}

object MotionTokens {
    const val DurationImmediate = 0
    const val DurationShort = 140
    const val DurationMedium = 220
    const val DurationEmphasized = 280
    const val DurationLong = 320
    const val DurationPulse = 1_100
}
