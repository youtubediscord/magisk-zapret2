package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle

/** Restores a typed value and removes payloads left behind by an incompatible state schema. */
internal inline fun <reified T> SavedStateHandle.restoreTypedOrRemove(key: String): T? {
    val raw = runCatching { get<Any>(key) }.getOrNull()
    if (raw !is T) {
        if (contains(key)) remove<Any>(key)
        return null
    }
    return raw
}

/** Restores a String ArrayList without trusting its erased generic element type. */
internal fun SavedStateHandle.restoreStringArrayListOrRemove(key: String): ArrayList<String>? {
    val raw = restoreTypedOrRemove<Any>(key) ?: return null
    if (raw !is ArrayList<*> || raw.any { it !is String }) {
        remove<Any>(key)
        return null
    }
    return ArrayList(raw.map { it as String })
}

/** Restores an enum persisted by name and removes stale, mistyped, or unknown payloads. */
internal inline fun <reified E : Enum<E>> SavedStateHandle.restoreEnumNameOrRemove(
    key: String,
): E? {
    val raw = restoreTypedOrRemove<String>(key) ?: return null
    return enumValues<E>().firstOrNull { it.name == raw }.also { restored ->
        if (restored == null) remove<String>(key)
    }
}
