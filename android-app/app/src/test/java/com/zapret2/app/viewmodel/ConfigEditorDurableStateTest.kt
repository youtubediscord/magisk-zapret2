package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.R
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEditorDurableStateTest {

    @Test
    fun restoredDraft_preservesUnsavedEditorStateAcrossRecreation() {
        val viewModel = ConfigEditorViewModel(
            serviceEventBus = ServiceEventBus(),
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "config_editor_baseline" to "--original",
                    "config_editor_draft" to "--edited",
                    "config_editor_command_file" to "Custom Options",
                    "config_editor_mode_dialog" to true,
                ),
            ),
        )

        assertEquals("--edited", viewModel.uiState.value.commandText)
        assertEquals("Custom Options", viewModel.uiState.value.commandFileName)
        assertFalse(viewModel.uiState.value.hasAuthoritativeBinding)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertFalse(viewModel.uiState.value.showModeDialog)
    }

    @Test
    fun discard_restoresBaselineAndClearsDurableDialogState() {
        val savedState = SavedStateHandle(
            mapOf(
                "config_editor_baseline" to "--original",
                "config_editor_draft" to "--edited",
                "config_editor_command_file" to "cmdline.txt",
                "config_editor_mode_dialog" to true,
            ),
        )
        val viewModel = ConfigEditorViewModel(ServiceEventBus(), savedState)

        viewModel.discardUnsavedChanges()

        assertEquals("--original", viewModel.uiState.value.commandText)
        assertFalse(viewModel.uiState.value.hasUnsavedChanges)
        assertFalse(viewModel.uiState.value.showModeDialog)
        assertEquals("--original", savedState.get<String>("config_editor_draft"))
        assertEquals(false, savedState.get<Boolean>("config_editor_mode_dialog"))
    }

    @Test
    fun reloadWithoutDiscardAcknowledgement_preservesDirtyDraft() {
        val savedState = SavedStateHandle(
            mapOf(
                "config_editor_baseline" to "--original",
                "config_editor_draft" to "--irreplaceable-draft",
                "config_editor_command_file" to "cmdline.txt",
            ),
        )
        val viewModel = ConfigEditorViewModel(ServiceEventBus(), savedState)

        viewModel.loadCommandLine()

        assertEquals("--irreplaceable-draft", viewModel.uiState.value.commandText)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertEquals("--irreplaceable-draft", savedState.get<String>("config_editor_draft"))
    }

    @Test
    fun restoredResult_isDurableUiTextAndEditsStayBlockedUntilRevalidation() {
        val savedState = SavedStateHandle(
            mapOf(
                "config_editor_baseline" to "--original",
                "config_editor_draft" to "--edited",
                "config_editor_command_file" to "cmdline.txt",
                "config_editor_result" to ConfigEditorResult.SAVE_FAILED.name,
            ),
        )
        val viewModel = ConfigEditorViewModel(ServiceEventBus(), savedState)

        assertEquals(ConfigEditorResult.SAVE_FAILED, viewModel.uiState.value.result)
        assertEquals(
            UiText.Resource(R.string.config_save_failed),
            viewModel.uiState.value.message,
        )

        viewModel.updateCommandText("--unverified-edit")

        assertEquals("--edited", savedState.get<String>("config_editor_draft"))
        assertEquals("--edited", viewModel.uiState.value.commandText)
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
    }
}
