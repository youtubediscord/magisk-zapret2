package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SavedStateValidationTest {

    private enum class ExampleState { READY }

    @Test
    fun enumRestore_acceptsKnownNames() {
        val state = SavedStateHandle(mapOf("state" to ExampleState.READY.name))

        assertEquals(ExampleState.READY, state.restoreEnumNameOrRemove<ExampleState>("state"))
        assertEquals(ExampleState.READY.name, state.get<String>("state"))
    }

    @Test
    fun enumRestore_removesUnknownAndMistypedPayloads() {
        val unknown = SavedStateHandle(mapOf("state" to "REMOVED_IN_NEW_SCHEMA"))
        val mistyped = SavedStateHandle(mapOf("state" to 42))

        assertNull(unknown.restoreEnumNameOrRemove<ExampleState>("state"))
        assertNull(mistyped.restoreEnumNameOrRemove<ExampleState>("state"))
        assertFalse(unknown.contains("state"))
        assertFalse(mistyped.contains("state"))
    }

    @Test
    fun stringListRestore_rejectsErasedListsWithNonStringElements() {
        val valid = SavedStateHandle(mapOf("items" to arrayListOf("one", "two")))
        val invalid = SavedStateHandle(mapOf("items" to arrayListOf<Any>("one", 2)))

        assertEquals(
            arrayListOf("one", "two"),
            valid.restoreStringArrayListOrRemove("items"),
        )
        assertNull(invalid.restoreStringArrayListOrRemove("items"))
        assertFalse(invalid.contains("items"))
    }
}
