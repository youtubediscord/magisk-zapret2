package com.zapret2.app.viewmodel

import com.zapret2.app.R
import com.zapret2.app.data.HostsIniData
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableAsyncUiStateTest {

    @Test
    fun logsState_keepsRawPayloadsFreeOfPresentationSentinels() {
        val state = LogsUiState()

        assertEquals(LogsLoadState.IDLE, state.commandLoadState)
        assertEquals(LogsLoadState.IDLE, state.outputLoadState)
        assertTrue(state.cmdline.isEmpty())
        assertTrue(state.logs.isEmpty())
        assertFalse(state.isClearing)
        assertFalse(state.logs.startsWith("Error:"))
        assertFalse(state.logs == "Loading...")
    }

    @Test
    fun hostlistState_usesNumericCountsAndTypedLoadError() {
        val state = HostlistContentUiState(
            matchingEntries = 42,
            loadState = HostlistContentLoadState.ERROR,
            loadError = HostlistContentError.LOAD_FAILED,
        )

        assertEquals(42, state.matchingEntries)
        assertEquals(HostlistContentLoadState.ERROR, state.loadState)
        assertEquals(HostlistContentError.LOAD_FAILED, state.loadError)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun protectedEditors_requireAuthoritativeBaselinesForMutations() {
        val hostsReady = HostsEditorUiState(hasAuthoritativeBaseline = true)
        val hostlistReady = HostlistContentUiState(
            isEditing = true,
            hasAuthoritativeEditorBaseline = true,
            hasUnsavedChanges = true,
            loadState = HostlistContentLoadState.READY,
        )

        assertTrue(hostsReady.actionsEnabled)
        assertFalse(HostsEditorUiState().actionsEnabled)
        assertTrue(hostlistReady.canEditContent)
        assertTrue(hostlistReady.canSaveContent)
        assertFalse(hostlistReady.copy(hasAuthoritativeEditorBaseline = false).canEditContent)
        assertFalse(hostlistReady.copy(loadState = HostlistContentLoadState.ERROR).canEditContent)
    }

    @Test
    fun criticalOperationResults_remainTypedInDnsAndPresetState() {
        val dnsMessage = UiText.resource(R.string.dns_save_failed)
        val presetMessage = UiText.resource(R.string.presets_io_failed)
        val dnsState = DnsManagerUiState(
            operation = DnsManagerOperation.APPLY,
            message = dnsMessage,
        )
        val presetsState = PresetsUiState(
            operation = PresetsOperation.APPLY,
            message = presetMessage,
        )

        assertTrue(dnsState.isLoading)
        assertTrue(presetsState.isLoading)
        assertEquals(dnsMessage, dnsState.message)
        assertEquals(presetMessage, presetsState.message)
        assertNull(DnsManagerUiState().message)
        assertNull(PresetsUiState().message)
    }

    @Test
    fun dnsSelection_isDisabledWithoutDataDuringOperationsAndAfterLoadFailure() {
        val ready = DnsManagerUiState(
            hostsData = HostsIniData(emptyList(), emptyList(), emptyList()),
        )

        assertTrue(ready.canEditSelection)
        assertFalse(DnsManagerUiState().canEditSelection)
        assertFalse(ready.copy(loadError = UiText.resource(R.string.dns_load_error_body)).canEditSelection)
        DnsManagerOperation.entries.forEach { operation ->
            assertFalse(ready.copy(operation = operation).canEditSelection)
        }
    }
}
