package com.zapret2.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ZapretShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // Buttons
    small = RoundedCornerShape(4.dp),         // Small elements
    medium = RoundedCornerShape(8.dp),        // Cards
    large = RoundedCornerShape(16.dp),        // Dialogs, Bottom sheets
    extraLarge = RoundedCornerShape(16.dp)    // Large containers
)
