package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.R
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsEditorDurableStateTest {

    @Test
    fun reconstruction_restoresDraftButRequiresAuthoritativeBaselineReload() {
        val savedState = SavedStateHandle(
            mapOf(
                "hosts_editor_baseline" to "127.0.0.1 localhost\n",
                "hosts_editor_draft" to "127.0.0.1 localhost\n0.0.0.0 example.test\n",
                "hosts_editor_result" to HostsEditorResult.SAVE_FAILED.name,
            ),
        )

        val viewModel = HostsEditorViewModel(savedState)
        val state = viewModel.uiState.value

        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isLoading)
        assertFalse(state.hasAuthoritativeBaseline)
        assertFalse(state.actionsEnabled)
        assertEquals(HostsEditorResult.SAVE_FAILED, state.result)
        assertEquals(UiText.Resource(R.string.hosts_overlay_save_failed), state.message)
        assertTrue(state.content.contains("example.test"))
    }

    @Test
    fun restoredDraft_cannotBeEditedBeforeBaselineRevalidation() {
        val savedState = SavedStateHandle(
            mapOf(
                "hosts_editor_baseline" to "baseline",
                "hosts_editor_draft" to "draft",
            ),
        )
        val viewModel = HostsEditorViewModel(savedState)
        val attemptedEdit = "unverified edit"

        viewModel.updateContent(attemptedEdit)

        assertEquals("draft", viewModel.uiState.value.content)
        assertFalse(viewModel.uiState.value.hasAuthoritativeBaseline)
        assertEquals("draft", savedState.get<String>("hosts_editor_draft"))
    }

    @Test
    fun reloadWithoutDiscardAcknowledgement_preservesDirtyDraft() {
        val savedState = SavedStateHandle(
            mapOf(
                "hosts_editor_baseline" to "baseline",
                "hosts_editor_draft" to "irreplaceable draft",
            ),
        )
        val viewModel = HostsEditorViewModel(savedState)

        viewModel.loadHosts()

        assertEquals("irreplaceable draft", viewModel.uiState.value.content)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertEquals("irreplaceable draft", savedState.get<String>("hosts_editor_draft"))
    }

    @Test
    fun confirmedExitDiscardClearsTheDurableDraftBeforeNavigation() {
        val savedState = SavedStateHandle(
            mapOf(
                "hosts_editor_baseline" to "baseline",
                "hosts_editor_draft" to "draft",
            ),
        )
        val viewModel = HostsEditorViewModel(savedState)

        viewModel.discardUnsavedChanges()

        assertEquals("baseline", viewModel.uiState.value.content)
        assertFalse(viewModel.uiState.value.hasUnsavedChanges)
        assertEquals("baseline", savedState.get<String>("hosts_editor_draft"))
    }
}
