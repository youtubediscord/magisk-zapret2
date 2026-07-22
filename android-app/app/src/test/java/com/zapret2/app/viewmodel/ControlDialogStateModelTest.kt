package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.R
import com.zapret2.app.data.ModulePurgeController
import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDialogStateModelTest {

    @Test
    fun uiDiagnostics_areRedactedBeforeTheyCanBePersistedOrDisplayed() {
        val diagnostic = sanitizedBoundedUiDiagnostic(
            "token=super-secret host=my-phone 192.168.1.2 " +
                "/data/user/0/com.zapret2.app/cache/update.apk"
        )

        assertFalse(diagnostic.contains("super-secret"))
        assertFalse(diagnostic.contains("my-phone"))
        assertFalse(diagnostic.contains("192.168.1.2"))
        assertFalse(diagnostic.contains("/data/user/0"))
        assertTrue(diagnostic.contains("[REDACTED_SECRET]"))
        assertTrue(diagnostic.contains("[REDACTED_PRIVATE]"))
    }

    @Test
    fun restoredDynamicDiagnostics_areSanitizedAgain() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_error_kind" to ControlErrorKind.UPDATE.name,
                "control_error_detail_dynamic" to "token=old-secret host=old-phone",
            ),
        )
        val restored = restoreControlUiState(savedState)

        val details = restored.errorDialog?.details as UiText.Dynamic
        assertFalse(details.value.contains("old-secret"))
        assertFalse(details.value.contains("old-phone"))
        assertTrue(details.value.contains("[REDACTED_SECRET]"))
        assertEquals(details.value, savedState.get<String>("control_error_detail_dynamic"))
    }

    @Test
    fun localizedDiagnosticWrapper_isSanitizedAndRestoredWithItsAllowlistedResource() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_error_kind" to ControlErrorKind.UPDATE.name,
                "control_error_detail_resource" to
                    R.string.control_update_module_recovery_required_details,
                "control_error_detail_dynamic" to "token=old-secret cleanup incomplete",
            ),
        )

        val restored = restoreControlUiState(savedState)

        val details = restored.errorDialog?.details as UiText.Resource
        val diagnostic = details.arguments.single() as String
        assertEquals(R.string.control_update_module_recovery_required_details, details.id)
        assertFalse(diagnostic.contains("old-secret"))
        assertTrue(diagnostic.contains("[REDACTED_SECRET]"))
        assertEquals(diagnostic, savedState.get<String>("control_error_detail_dynamic"))
        assertEquals(details.id, savedState.get<Int>("control_error_detail_resource"))
    }

    @Test
    fun incompleteLocalizedDiagnosticWrapper_fallsBackToGenericRecoveryCopy() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_error_kind" to ControlErrorKind.UPDATE.name,
                "control_error_detail_resource" to
                    R.string.control_update_module_recovery_required_details,
            ),
        )

        val restored = restoreControlUiState(savedState)

        assertEquals(
            UiText.Resource(R.string.control_update_module_recovery_required),
            restored.errorDialog?.details,
        )
        assertEquals(
            R.string.control_update_module_recovery_required,
            savedState.get<Int>("control_error_detail_resource"),
        )
        assertFalse(savedState.contains("control_error_detail_dynamic"))
    }

    @Test
    fun packetDialog_reconstructionKeepsTargetAndBoundedDraft() {
        val restored = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.PACKET.name,
                    "control_packet_target" to PacketTarget.OUT.name,
                    "control_packet_draft" to "32",
                ),
            ),
        )

        assertEquals(ControlDialogKind.PACKET, restored.pendingDialog)
        assertEquals(PacketTarget.OUT, restored.packetTarget)
        assertEquals("32", restored.packetDraft)
        assertNull(restored.errorDialog)
    }

    @Test
    fun packetDialog_reconstructionNormalizesCorruptDraft() {
        val restored = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.PACKET.name,
                    "control_packet_target" to PacketTarget.IN.name,
                    "control_packet_draft" to "9x8y7654321",
                ),
            ),
        )

        assertEquals("987654321", restored.packetDraft)
    }

    @Test
    fun incompletePacketDialog_isDroppedInsteadOfBecomingInvisibleModalState() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.PACKET.name,
                "control_packet_draft" to "32",
            ),
        )

        val restored = restoreControlUiState(savedState)

        assertNull(restored.pendingDialog)
        assertNull(restored.packetTarget)
        assertFalse(savedState.contains("control_dialog_kind"))
        assertFalse(savedState.contains("control_packet_draft"))
    }

    @Test
    fun errorDialog_reconstructionRequiresKindAndDetails() {
        val restored = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.ERROR.name,
                    "control_error_kind" to ControlErrorKind.START_SERVICE.name,
                    "control_error_detail_resource" to R.string.control_unknown_error,
                ),
            ),
        )

        assertEquals(ControlDialogKind.ERROR, restored.pendingDialog)
        assertEquals(ControlErrorKind.START_SERVICE, restored.errorDialog?.kind)
        assertEquals(
            UiText.Resource(R.string.control_unknown_error),
            restored.errorDialog?.details,
        )
        assertNull(restored.packetTarget)
    }

    @Test
    fun errorDialog_reconstructionRejectsUnknownResourceIds() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_error_kind" to ControlErrorKind.START_SERVICE.name,
                "control_error_detail_resource" to Int.MAX_VALUE,
                "control_error_detail_dynamic" to "must not bypass the resource allowlist",
            ),
        )
        val restored = restoreControlUiState(savedState)

        assertEquals(
            UiText.Resource(R.string.control_unknown_error),
            restored.errorDialog?.details,
        )
        assertFalse(savedState.contains("control_error_detail_dynamic"))
    }

    @Test
    fun incompleteErrorDialog_isDroppedAndItsPayloadIsCleared() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_error_detail_dynamic" to "stale diagnostic",
            ),
        )

        val restored = restoreControlUiState(savedState)

        assertNull(restored.pendingDialog)
        assertNull(restored.errorDialog)
        assertFalse(savedState.contains("control_dialog_kind"))
        assertFalse(savedState.contains("control_error_detail_dynamic"))
    }

    @Test
    fun fullRollbackConfirmationAndResultRemainTypedAcrossReconstruction() {
        val confirmation = restoreControlUiState(
            SavedStateHandle(
                mapOf("control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_CONFIRM.name),
            ),
        )
        assertEquals(FullRollbackUiState.Confirmation, confirmation.fullRollback)

        val result = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_RESULT.name,
                    "control_full_rollback_outcome" to
                        ServiceLifecycleController.FullRollbackOutcome.PARTIAL.name,
                    "control_full_rollback_reboot_required" to true,
                    "control_full_rollback_diagnostic" to "firewall cleanup incomplete",
                ),
            ),
        )
        val rollback = result.fullRollback as FullRollbackUiState.Result
        assertEquals(ServiceLifecycleController.FullRollbackOutcome.PARTIAL, rollback.outcome)
        assertEquals(true, rollback.rebootRequired)
        assertEquals("firewall cleanup incomplete", rollback.diagnostic)
    }

    @Test
    fun incompleteRollbackResult_isDroppedInsteadOfBlockingTheControlScreen() {
        val savedState = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_RESULT.name,
                "control_full_rollback_outcome" to "corrupt",
                "control_full_rollback_reboot_required" to true,
                "control_full_rollback_diagnostic" to "stale",
            ),
        )

        val restored = restoreControlUiState(savedState)

        assertNull(restored.pendingDialog)
        assertEquals(FullRollbackUiState.Idle, restored.fullRollback)
        assertFalse(savedState.contains("control_dialog_kind"))
        assertFalse(savedState.contains("control_full_rollback_outcome"))
        assertFalse(savedState.contains("control_full_rollback_reboot_required"))
        assertFalse(savedState.contains("control_full_rollback_diagnostic"))
    }

    @Test
    fun modulePurgeConfirmationAndResultRemainTypedAcrossReconstruction() {
        val confirmation = restoreControlUiState(
            SavedStateHandle(
                mapOf("control_dialog_kind" to ControlDialogKind.MODULE_PURGE_CONFIRM.name),
            ),
        )
        assertEquals(ModulePurgeUiState.Confirmation, confirmation.modulePurge)

        val result = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.MODULE_PURGE_RESULT.name,
                    "control_module_purge_outcome" to ModulePurgeController.Outcome.PARTIAL.name,
                    "control_module_purge_reboot_required" to true,
                    "control_module_purge_diagnostic" to "state cleanup incomplete",
                ),
            ),
        )
        val purge = result.modulePurge as ModulePurgeUiState.Result
        assertEquals(ModulePurgeController.Outcome.PARTIAL, purge.outcome)
        assertTrue(purge.rebootRequired)
        assertEquals("state cleanup incomplete", purge.diagnostic)
    }

    @Test
    fun persistedModulePurgeProgressRestoresWithoutConfirmationAndWinsNoRollbackState() {
        val restored = restoreControlUiState(
            SavedStateHandle(mapOf("control_module_purge_in_progress" to true)),
        )

        assertEquals(ModulePurgeUiState.InProgress, restored.modulePurge)
        assertEquals(FullRollbackUiState.Idle, restored.fullRollback)
        assertNull(restored.pendingDialog)
    }

    @Test
    fun lastResultRestoresIndependentlyFromDialogAndRejectsCorruption() {
        val handle = SavedStateHandle(
            mapOf(
                "control_dialog_kind" to ControlDialogKind.ERROR.name,
                "control_last_result" to ControlLastResult.UPDATE_REBOOT_REQUIRED.name,
            ),
        )

        assertEquals(ControlLastResult.UPDATE_REBOOT_REQUIRED, restoreControlLastResult(handle))
        handle.remove<String>("control_dialog_kind")
        assertEquals(ControlLastResult.UPDATE_REBOOT_REQUIRED, restoreControlLastResult(handle))

        handle["control_last_result"] = "corrupt"
        assertNull(restoreControlLastResult(handle))
        assertFalse(handle.contains("control_last_result"))
    }

    @Test
    fun persistedFullRollbackProgress_restoresInProgressWithoutConfirmation() {
        val restored = restoreControlUiState(
            SavedStateHandle(
                mapOf(
                    "control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_CONFIRM.name,
                    "control_full_rollback_in_progress" to true,
                ),
            ),
        )

        assertEquals(FullRollbackUiState.InProgress, restored.fullRollback)
        assertNull(restored.pendingDialog)
    }

    @Test
    fun restoredProgress_isAutomaticallyEligibleExactlyOnce() {
        val savedState = SavedStateHandle(
            mapOf("control_full_rollback_in_progress" to true),
        )
        val restored = restoreControlUiState(savedState)
        val coordinator = FullRollbackOperationCoordinator(savedState)
        var launches = 0

        assertTrue(
            coordinator.tryBegin(FullRollbackLaunchReason.RESTORED, restored) { launches += 1 },
        )
        assertFalse(
            coordinator.tryBegin(FullRollbackLaunchReason.RESTORED, restored) { launches += 1 },
        )
        assertEquals(1, launches)
    }

    @Test
    fun duplicateConfirmedRollback_isBlockedAfterProgressIsPersisted() {
        val savedState = SavedStateHandle(
            mapOf("control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_CONFIRM.name),
        )
        val state = ControlUiState(
            status = ControlStatus.RUNNING,
            hasRootAccess = true,
            moduleInstallState = com.zapret2.app.data.ModuleInstallState.READY,
            fullRollback = FullRollbackUiState.Confirmation,
        )
        val coordinator = FullRollbackOperationCoordinator(savedState)
        var launches = 0

        assertTrue(
            coordinator.tryBegin(FullRollbackLaunchReason.CONFIRMED, state) {
                assertEquals(true, savedState["control_full_rollback_in_progress"])
                launches += 1
            },
        )
        assertFalse(
            coordinator.tryBegin(FullRollbackLaunchReason.CONFIRMED, state) { launches += 1 },
        )
        assertEquals(1, launches)
    }

    @Test
    fun terminalResult_clearsProgressOnlyAfterCompleteTerminalPersistence() {
        val savedState = SavedStateHandle(
            mapOf("control_full_rollback_in_progress" to true),
        )
        var terminalObserved = false
        val coordinator = FullRollbackOperationCoordinator(
            savedStateHandle = savedState,
            onTerminalPersisted = {
                terminalObserved = true
                assertEquals(true, savedState["control_full_rollback_in_progress"])
                assertEquals(
                    ServiceLifecycleController.FullRollbackOutcome.PARTIAL.name,
                    savedState["control_full_rollback_outcome"],
                )
                assertEquals(true, savedState["control_full_rollback_reboot_required"])
                assertEquals("cleanup incomplete", savedState["control_full_rollback_diagnostic"])
                assertEquals(
                    ControlDialogKind.FULL_ROLLBACK_RESULT.name,
                    savedState["control_dialog_kind"],
                )
                assertEquals(
                    ControlLastResult.ROLLBACK_FAILED.name,
                    savedState["control_last_result"],
                )
                assertTrue(
                    restoreControlUiState(savedState).fullRollback is FullRollbackUiState.Result,
                )
            },
        )
        val result = ServiceLifecycleController.FullRollbackResult(
            outcome = ServiceLifecycleController.FullRollbackOutcome.PARTIAL,
            report = ServiceLifecycleController.FullRollbackReport(
                status = ServiceLifecycleController.FullRollbackStatus.PARTIAL,
                processClean = false,
                firewallClean = false,
                rollbackArmed = true,
                hostsPreserved = true,
                rebootRequired = true,
                userDataPreserved = true,
                legacyAmbiguous = false,
                diagnostic = "cleanup incomplete",
            ),
        )

        coordinator.persistTerminal(
            result = result,
            diagnostic = "cleanup incomplete",
            lastResult = ControlLastResult.ROLLBACK_FAILED,
        )

        assertTrue(terminalObserved)
        assertNull(savedState.get<Boolean>("control_full_rollback_in_progress"))
    }

    @Test
    fun cancelledAttempt_retainsProgressForRecreationAndResume() {
        val savedState = SavedStateHandle(
            mapOf("control_dialog_kind" to ControlDialogKind.FULL_ROLLBACK_CONFIRM.name),
        )
        val confirmation = ControlUiState(
            status = ControlStatus.STOPPED,
            hasRootAccess = true,
            moduleInstallState = com.zapret2.app.data.ModuleInstallState.READY,
            fullRollback = FullRollbackUiState.Confirmation,
        )
        val originalCoordinator = FullRollbackOperationCoordinator(savedState)
        assertTrue(
            originalCoordinator.tryBegin(FullRollbackLaunchReason.CONFIRMED, confirmation) {},
        )

        originalCoordinator.finishAttempt()

        assertEquals(true, savedState["control_full_rollback_in_progress"])
        val restored = restoreControlUiState(savedState)
        assertEquals(FullRollbackUiState.InProgress, restored.fullRollback)
        var resumed = 0
        assertTrue(
            FullRollbackOperationCoordinator(savedState).tryBegin(
                FullRollbackLaunchReason.RESTORED,
                restored,
            ) { resumed += 1 },
        )
        assertEquals(1, resumed)
    }
}
