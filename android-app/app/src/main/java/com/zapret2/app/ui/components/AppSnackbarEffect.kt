package com.zapret2.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.resolve
import kotlinx.coroutines.launch

/** A one-shot Compose notification. The sequence permits repeated equal messages. */
data class AppSnackbarMessage(
    val sequence: Long,
    val text: UiText,
)

@Composable
fun AppSnackbarEffect(
    message: AppSnackbarMessage?,
    hostState: SnackbarHostState,
    onConsumed: (AppSnackbarMessage) -> Unit,
) {
    val context = LocalContext.current
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(message?.sequence, hostState, context) {
        message?.let {
            hostState.showSnackbar(it.text.resolve(context))
            currentOnConsumed(it)
        }
    }
}

/** Consumes durable state before suspending so equal future messages cannot be coalesced or replayed. */
@Composable
fun AppSnackbarEffect(
    message: UiText?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnConsumed by rememberUpdatedState(onConsumed)
    LaunchedEffect(message, hostState, context) {
        message?.let {
            val resolved = it.resolve(context)
            currentOnConsumed()
            scope.launch { hostState.showSnackbar(resolved) }
        }
    }
}
