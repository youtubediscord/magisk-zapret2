package com.zapret2.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * User-visible text owned by presentation state.
 *
 * App-authored copy uses [Resource]. [Dynamic] is reserved for trusted runtime/user data and
 * bounded, redacted technical diagnostics. Resource arguments may contain another [UiText] so
 * presentation can compose localized context around a typed localized reason.
 */
sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val arguments: List<Any> = emptyList(),
    ) : UiText

    data class Dynamic(val value: String) : UiText

    companion object {
        fun resource(@StringRes id: Int, vararg arguments: Any): UiText =
            Resource(id = id, arguments = arguments.toList())
    }
}

@Composable
fun UiText.resolve(): String = resolveComposable(depth = 0)

@Composable
private fun UiText.resolveComposable(depth: Int): String = when (this) {
    is UiText.Resource -> stringResource(
        id,
        *arguments.resolveComposableArguments(depth).toTypedArray(),
    )
    is UiText.Dynamic -> value
}

@Composable
private fun List<Any>.resolveComposableArguments(depth: Int): List<Any> {
    val resolved = ArrayList<Any>(size)
    for (argument in this) {
        resolved += if (argument is UiText && depth < MAX_UI_TEXT_NESTING) {
            argument.resolveComposable(depth + 1)
        } else if (argument is UiText) {
            ""
        } else {
            argument
        }
    }
    return resolved
}

fun UiText.resolve(context: Context): String = resolveWithContext(context, depth = 0)

private fun UiText.resolveWithContext(context: Context, depth: Int): String = when (this) {
    is UiText.Resource -> context.getString(
        id,
        *arguments.map { argument ->
            if (argument is UiText && depth < MAX_UI_TEXT_NESTING) {
                argument.resolveWithContext(context, depth + 1)
            } else if (argument is UiText) {
                ""
            } else {
                argument
            }
        }.toTypedArray(),
    )
    is UiText.Dynamic -> value
}

private const val MAX_UI_TEXT_NESTING = 4
