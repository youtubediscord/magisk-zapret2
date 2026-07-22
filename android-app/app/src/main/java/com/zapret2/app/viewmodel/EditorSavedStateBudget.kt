package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle

/**
 * Per-field process-death budget for editor text.
 *
 * Four editor destinations can coexist in the navigation back stack and each owns a draft plus
 * a baseline. Keeping all eight fields within this shared ceiling leaves substantial room in the
 * Activity saved-state transaction for Navigation, Compose and the remaining typed state.
 * This does not limit the live editor or protected file size; larger drafts simply stay in memory
 * and are deliberately omitted from the process-death Bundle.
 */
internal const val MAX_SAVED_EDITOR_FIELD_CHARS = 16 * 1024

internal fun SavedStateHandle.restoreBoundedEditorText(key: String): String? {
    val value = restoreTypedOrRemove<String>(key) ?: return null
    return value.takeIf { it.length <= MAX_SAVED_EDITOR_FIELD_CHARS }.also {
        if (it == null) remove<String>(key)
    }
}

internal fun SavedStateHandle.persistBoundedEditorText(key: String, value: String) {
    if (value.length <= MAX_SAVED_EDITOR_FIELD_CHARS) this[key] = value
    else remove<String>(key)
}
