package com.zapret2.app.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/** Locale-aware plural lookup that also observes configuration changes in Compose. */
@Composable
fun quantityStringResource(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    return LocalResources.current.getQuantityString(id, quantity, *formatArgs)
}
