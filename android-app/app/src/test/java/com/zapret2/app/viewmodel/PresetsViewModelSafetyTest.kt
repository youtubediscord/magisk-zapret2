package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.data.PresetCatalog
import com.zapret2.app.data.PresetDiscovery
import com.zapret2.app.data.PresetDurableOutcome
import com.zapret2.app.data.PresetEntry
import com.zapret2.app.data.PresetIssue
import com.zapret2.app.data.PresetMutationOutcome
import com.zapret2.app.data.PresetRepository
import com.zapret2.app.data.PresetSelection
import com.zapret2.app.data.ServiceEventBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsViewModelSafetyTest {

    @Test
    fun discoveryFixture_exposes20AndReports49Quarantined() = runBlocking {
        val repository = FakeRepository().apply {
            catalog = PresetCatalog(
                discovery = PresetDiscovery(
                    available = List(20) { PresetEntry("valid-$it.txt") },
                    quarantinedCount = 49,
                    issueCounts = mapOf(PresetIssue.DEPENDENCY_MISSING to 49),
                ),
                selection = PresetSelection("categories", "", "cmdline.txt"),
            )
        }
        val viewModel = PresetsViewModel(SavedStateHandle(), repository, ServiceEventBus())

        viewModel.loadPresetsNow()

        assertEquals(20, viewModel.uiState.value.presets.size)
        assertEquals(49, viewModel.uiState.value.quarantinedCount)
        assertEquals(49, viewModel.uiState.value.issueCounts[PresetIssue.DEPENDENCY_MISSING])
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun rejectedApplyPersistsTypedDurableOutcomeAndUsesRepositoryRevalidationPath() = runBlocking {
        val handle = SavedStateHandle()
        val repository = FakeRepository().apply {
            mutation = PresetMutationOutcome.Rejected(PresetIssue.UNSAFE_DEPENDENCY_PATH)
        }
        val viewModel = PresetsViewModel(handle, repository, ServiceEventBus())

        viewModel.applyPresetNow("valid.txt")

        assertEquals(1, repository.applyCalls)
        assertEquals(PresetDurableOutcome.REJECTED, viewModel.uiState.value.lastOutcome)
        assertEquals(PresetIssue.UNSAFE_DEPENDENCY_PATH, viewModel.uiState.value.lastIssue)
        assertEquals(PresetDurableOutcome.REJECTED.name, handle.get<String>("presets_last_outcome"))
        assertEquals(PresetIssue.UNSAFE_DEPENDENCY_PATH.name, handle.get<String>("presets_last_issue"))
    }

    @Test
    fun alreadyActivePreset_isRejectedBeforeRepositoryMutation() = runBlocking {
        val repository = FakeRepository().apply {
            catalog = PresetCatalog(
                PresetDiscovery(listOf(PresetEntry("valid.txt")), 0, emptyMap()),
                PresetSelection("file", "valid.txt", "cmdline.txt"),
            )
        }
        val viewModel = PresetsViewModel(SavedStateHandle(), repository, ServiceEventBus())
        viewModel.loadPresetsNow()

        viewModel.applyPreset("valid.txt")

        assertEquals(0, repository.applyCalls)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun alreadyActiveCategoriesMode_isRejectedBeforeRepositoryMutation() = runBlocking {
        val repository = FakeRepository().apply {
            catalog = PresetCatalog(
                PresetDiscovery(listOf(PresetEntry("valid.txt")), 0, emptyMap()),
                PresetSelection("categories", "", "cmdline.txt"),
            )
        }
        val viewModel = PresetsViewModel(SavedStateHandle(), repository, ServiceEventBus())
        viewModel.loadPresetsNow()

        viewModel.switchToCategoriesMode()

        assertEquals(0, repository.switchCalls)
        assertNull(viewModel.uiState.value.operation)
    }

    @Test
    fun restartRollbackOutcomeSurvivesViewModelRecreation() = runBlocking {
        val handle = SavedStateHandle()
        val repository = FakeRepository().apply {
            mutation = PresetMutationOutcome.RestartFailedRolledBack
        }
        PresetsViewModel(handle, repository, ServiceEventBus()).applyPresetNow("valid.txt")

        val recreated = PresetsViewModel(handle, repository, ServiceEventBus())

        assertEquals(PresetDurableOutcome.RESTART_FAILED_ROLLED_BACK, recreated.uiState.value.lastOutcome)
        assertNull(recreated.uiState.value.lastIssue)
    }

    @Test
    fun restoredIssue_isRemovedWhenItDoesNotBelongToARejectedOutcome() {
        val handle = SavedStateHandle(
            mapOf(
                "presets_last_outcome" to PresetDurableOutcome.SAVED.name,
                "presets_last_issue" to PresetIssue.MALFORMED_PROTOCOL.name,
            ),
        )

        val restored = PresetsViewModel(handle, FakeRepository(), ServiceEventBus()).uiState.value

        assertEquals(PresetDurableOutcome.SAVED, restored.lastOutcome)
        assertNull(restored.lastIssue)
        assertNull(handle.get<String>("presets_last_issue"))
    }

    @Test
    fun failedSaveKeepsEditorDraftAndSuccessfulSaveClosesIt() = runBlocking {
        val repository = FakeRepository()
        val viewModel = PresetsViewModel(SavedStateHandle(), repository, ServiceEventBus())
        viewModel.loadPresetsNow()
        viewModel.openPresetEditorNow("valid.txt")
        viewModel.updatePresetContent("edited content")

        repository.mutation = PresetMutationOutcome.Rejected(PresetIssue.MALFORMED_PROTOCOL)
        viewModel.savePresetNow("valid.txt", "content", "edited content", applyAfterSave = false)
        assertNotNull(viewModel.uiState.value.editingPreset)
        assertEquals("edited content", viewModel.uiState.value.editingPreset?.content)

        repository.mutation = PresetMutationOutcome.Saved
        viewModel.savePresetNow("valid.txt", "content", "edited content", applyAfterSave = false)
        assertNull(viewModel.uiState.value.editingPreset)
    }

    @Test
    fun dirtyEditor_requiresExplicitDiscardAcknowledgement() = runBlocking {
        val viewModel = PresetsViewModel(SavedStateHandle(), FakeRepository(), ServiceEventBus())
        viewModel.loadPresetsNow()
        viewModel.openPresetEditorNow("valid.txt")
        viewModel.updatePresetContent("edited content")

        viewModel.closePresetEditor()

        assertNotNull(viewModel.uiState.value.editingPreset)
        assertEquals("edited content", viewModel.uiState.value.editingPreset?.content)

        viewModel.closePresetEditor(discardUnsavedChanges = true)

        assertNull(viewModel.uiState.value.editingPreset)
    }

    @Test
    fun openingAnotherEditor_cannotReplaceAnExistingDirtyDraft() = runBlocking {
        val repository = FakeRepository()
        val viewModel = PresetsViewModel(SavedStateHandle(), repository, ServiceEventBus())
        viewModel.loadPresetsNow()
        viewModel.openPresetEditorNow("valid.txt")
        viewModel.updatePresetContent("irreplaceable draft")
        repository.compatibleContent = "new source"

        viewModel.openPresetEditorNow("valid.txt")

        assertEquals("irreplaceable draft", viewModel.uiState.value.editingPreset?.content)
        assertEquals("content", viewModel.uiState.value.editingPreset?.baselineContent)
        assertTrue(viewModel.uiState.value.editingPreset?.hasUnsavedChanges == true)
    }

    @Test
    fun editorDraftRestoresWithinBundleBound() {
        val handle = SavedStateHandle(
            mapOf(
                "presets_editor_file" to "valid.txt",
                "presets_editor_baseline" to "original",
                "presets_editor_draft" to "edited",
            ),
        )

        val restored = PresetsViewModel(handle, FakeRepository(), ServiceEventBus())
            .uiState.value.editingPreset

        assertEquals("valid.txt", restored?.fileName)
        assertEquals("edited", restored?.content)
        assertTrue(restored?.hasUnsavedChanges == true)
        assertTrue(restored?.hasAuthoritativeBaseline == false)
    }

    @Test
    fun restoredEditor_revalidatesCurrentSourceAndPreservesDirtyDraft() = runBlocking {
        val handle = SavedStateHandle(
            mapOf(
                "presets_editor_file" to "valid.txt",
                "presets_editor_baseline" to "old source",
                "presets_editor_draft" to "my draft",
            ),
        )
        val repository = FakeRepository().apply {
            compatibleContent = "current source"
            catalog = PresetCatalog(
                PresetDiscovery(listOf(PresetEntry("valid.txt")), 0, emptyMap()),
                PresetSelection("categories", "", "cmdline.txt"),
            )
        }
        val viewModel = PresetsViewModel(handle, repository, ServiceEventBus())

        viewModel.loadPresetsNow()

        val editor = viewModel.uiState.value.editingPreset
        assertEquals("my draft", editor?.content)
        assertEquals("current source", editor?.baselineContent)
        assertTrue(editor?.hasAuthoritativeBaseline == true)
    }

    private class FakeRepository : PresetRepository {
        var catalog: PresetCatalog? = PresetCatalog(
            PresetDiscovery(listOf(PresetEntry("valid.txt")), 0, emptyMap()),
            PresetSelection("categories", "", "cmdline.txt"),
        )
        var mutation: PresetMutationOutcome = PresetMutationOutcome.Applied
        var compatibleContent: String? = "content"
        var applyCalls = 0
        var switchCalls = 0

        override suspend fun loadCatalog(): PresetCatalog? = catalog
        override suspend fun readCompatible(fileName: String): String? = compatibleContent
        override suspend fun apply(fileName: String): PresetMutationOutcome {
            applyCalls++
            return mutation
        }
        override suspend fun save(
            fileName: String,
            expectedContent: String?,
            content: String,
            applyAfterSave: Boolean,
        ): PresetMutationOutcome = mutation
        override suspend fun switchToCategories(): PresetMutationOutcome {
            switchCalls++
            return mutation
        }
    }
}
