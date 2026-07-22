package com.zapret2.app.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Locale-aware plural lookup that also observes configuration changes in Compose. */
@Composable
fun quantityStringResource(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    LocalConfiguration.current
    return LocalContext.current.resources.getQuantityString(id, quantity, *formatArgs)
}
