package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.R
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostlistContentDurableStateTest {

    @Test
    fun invalidRouteName_isNotExposedOrLoaded() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "../different.txt",
                "hostlist_content_query" to "stale",
                "hostlist_content_editing" to true,
                "hostlist_content_editor_baseline" to "baseline",
                "hostlist_content_editor_draft" to "draft",
                "hostlist_content_result" to HostlistContentResult.SAVED.name,
            ),
        )
        val viewModel = HostlistContentViewModel(savedState)
        viewModel.search("must remain rejected")

        assertEquals("", viewModel.uiState.value.fileName)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertNull(viewModel.uiState.value.result)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(savedState.contains("hostlist_content_query"))
        assertFalse(savedState.contains("hostlist_content_editing"))
        assertFalse(savedState.contains("hostlist_content_editor_baseline"))
        assertFalse(savedState.contains("hostlist_content_editor_draft"))
        assertFalse(savedState.contains("hostlist_content_result"))
    }

    @Test
    fun pendingSearch_invalidatesTheOldViewerPageBeforeDebounce() {
        val previous = HostlistContentUiState(
            fileName = "example.txt",
            totalEntries = 3,
            entries = listOf("one.example", "two.example", "three.example"),
            searchQuery = "old",
            matchingEntries = 3,
            loadState = HostlistContentLoadState.READY,
            loadError = HostlistContentError.LOAD_FAILED,
        )

        val pending = previous.pendingSearch("new")

        assertEquals("new", pending.searchQuery)
        assertEquals(HostlistContentLoadState.LOADING, pending.loadState)
        assertEquals(0, pending.totalEntries)
        assertEquals(0, pending.matchingEntries)
        assertTrue(pending.entries.isEmpty())
        assertNull(pending.loadError)
    }

    @Test
    fun pagingStopsAtTheAuthoritativeViewerOrSearchCount() {
        val viewer = HostlistContentUiState(
            totalEntries = 101,
            entries = List(100) { "viewer-$it.example" },
            loadState = HostlistContentLoadState.READY,
        )
        assertTrue(viewer.canLoadMore)
        assertFalse(viewer.copy(entries = viewer.entries + "viewer-last.example").canLoadMore)

        val search = viewer.copy(matchingEntries = 100)
        assertFalse(search.canLoadMore)
        assertTrue(search.copy(matchingEntries = 101).canLoadMore)
        assertFalse(search.copy(isEditing = true, matchingEntries = 101).canLoadMore)
        assertFalse(search.copy(isSaving = true, matchingEntries = 101).canLoadMore)
    }

    @Test
    fun reconstruction_preservesDraftButRevalidatesEditorBaseline() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "example.txt",
                "hostlist_content_query" to "video",
                "hostlist_content_editing" to true,
                "hostlist_content_editor_baseline" to "example.org\n",
                "hostlist_content_editor_draft" to "example.org\nvideo.example\n",
                "hostlist_content_result" to HostlistContentResult.SAVED.name,
            ),
        )

        val viewModel = HostlistContentViewModel(savedState)
        val state = viewModel.uiState.value

        assertEquals("example.txt", state.fileName)
        assertEquals("video", state.searchQuery)
        assertTrue(state.isEditing)
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isLoading)
        assertFalse(state.hasAuthoritativeEditorBaseline)
        assertFalse(state.canEditContent)
        assertEquals(HostlistContentResult.SAVED, state.result)
        assertEquals(UiText.Resource(R.string.hostlist_saved), state.message)
    }

    @Test
    fun reconstruction_rewritesOversizedQueryToTheBoundedCanonicalValue() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "example.txt",
                "hostlist_content_query" to ("x".repeat(400) + "\nignored"),
            ),
        )

        val viewModel = HostlistContentViewModel(savedState)
        val expected = "x".repeat(256)

        assertEquals(expected, viewModel.uiState.value.searchQuery)
        assertEquals(expected, savedState.get<String>("hostlist_content_query"))
    }

    @Test
    fun reconstruction_removesPartialEditorTransactionBeforeLoadingViewer() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "example.txt",
                "hostlist_content_editing" to true,
                "hostlist_content_editor_baseline" to "orphan baseline",
            ),
        )

        val viewModel = HostlistContentViewModel(savedState)

        assertFalse(viewModel.uiState.value.isEditing)
        assertFalse(savedState.contains("hostlist_content_editing"))
        assertFalse(savedState.contains("hostlist_content_editor_baseline"))
        assertFalse(savedState.contains("hostlist_content_editor_draft"))
    }

    @Test
    fun restoredEditor_rejectsChangesUntilBaselineIsAuthoritative() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "example.txt",
                "hostlist_content_editing" to true,
                "hostlist_content_editor_baseline" to "baseline",
                "hostlist_content_editor_draft" to "draft",
            ),
        )
        val viewModel = HostlistContentViewModel(savedState)
        val attemptedEdit = "unverified edit"

        viewModel.updateEditorContent(attemptedEdit)

        assertEquals("draft", viewModel.uiState.value.editorContent)
        assertFalse(viewModel.uiState.value.hasAuthoritativeEditorBaseline)
        assertEquals("draft", savedState.get<String>("hostlist_content_editor_draft"))
    }

    @Test
    fun editingState_rejectsViewerSearchAndPagingEvents() {
        val savedState = SavedStateHandle(
            mapOf(
                "name" to "example.txt",
                "hostlist_content_query" to "original",
                "hostlist_content_editing" to true,
                "hostlist_content_editor_baseline" to "example.org\n",
                "hostlist_content_editor_draft" to "example.org\n",
            ),
        )
        val viewModel = HostlistContentViewModel(savedState)

        viewModel.search("stale viewer event")
        viewModel.loadMore()

        assertEquals("original", viewModel.uiState.value.searchQuery)
        assertTrue(viewModel.uiState.value.isEditing)
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
