package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSavedStateBudgetTest {

    @Test
    fun editorTextFields_shareOneCumulativeSavedStateBudget() {
        val editorSources = listOf(
            "ConfigEditorViewModel.kt",
            "HostsEditorViewModel.kt",
            "HostlistContentViewModel.kt",
            "PresetsViewModel.kt",
        ).joinToString("\n") { viewModelSource(it).readText() }
        val savedEditorKeys = listOf(
            "config_editor_draft",
            "config_editor_baseline",
            "hosts_editor_draft",
            "hosts_editor_baseline",
            "hostlist_content_editor_draft",
            "hostlist_content_editor_baseline",
            "presets_editor_draft",
            "presets_editor_baseline",
        )
        assertTrue(savedEditorKeys.all(editorSources::contains))

        val simultaneousSavedEditorFields = savedEditorKeys.size
        val combinedBudget = MAX_SAVED_EDITOR_FIELD_CHARS * simultaneousSavedEditorFields

        assertEquals(16 * 1024, MAX_SAVED_EDITOR_FIELD_CHARS)
        assertEquals(128 * 1024, combinedBudget)
        assertTrue(combinedBudget <= 128 * 1024)
    }

    @Test
    fun oversizedEditorText_isNeverLeftInSavedState() {
        val state = SavedStateHandle()
        val atLimit = "a".repeat(MAX_SAVED_EDITOR_FIELD_CHARS)
        val overLimit = atLimit + "a"

        state.persistBoundedEditorText("draft", atLimit)
        assertEquals(atLimit, state.restoreBoundedEditorText("draft"))

        state.persistBoundedEditorText("draft", overLimit)
        assertNull(state.get<String>("draft"))

        state["draft"] = overLimit
        assertNull(state.restoreBoundedEditorText("draft"))
        assertNull(state.get<String>("draft"))
    }

    private fun viewModelSource(fileName: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidates = listOf(
                File(current, "src/main/java/com/zapret2/app/viewmodel/$fileName"),
                File(current, "app/src/main/java/com/zapret2/app/viewmodel/$fileName"),
                File(current, "android-app/app/src/main/java/com/zapret2/app/viewmodel/$fileName"),
            )
            for (candidate in candidates) {
                if (candidate.isFile) return candidate
            }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate ViewModel source: $fileName")
    }
}
