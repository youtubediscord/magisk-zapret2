package com.zapret2.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetNamePolicyTest {

    @Test
    fun presetNamesRejectShellSensitiveQuotesBackslashesAndReservedFiles() {
        assertTrue(PresetNamePolicy.isValid("Preset File.txt"))
        listOf(
            "UPPER.TXT",
            "bad\\name.txt",
            "bad'name.txt",
            "bad\"name.txt",
            "_internal.txt",
            "nested/name.txt",
        ).forEach { name ->
            assertFalse("Expected unsafe preset name to be rejected: $name", PresetNamePolicy.isValid(name))
        }
    }

    @Test
    fun presetNamesRejectMultibyteComponentsAbove255Utf8Bytes() {
        assertTrue(PresetNamePolicy.isValid("\u044f".repeat(125) + ".txt"))
        assertFalse(PresetNamePolicy.isValid("\u044f".repeat(126) + ".txt"))
    }
}
