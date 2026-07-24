package com.zapret2.app.viewmodel

import com.zapret2.app.R
import com.zapret2.app.data.ModuleInstallState
import com.zapret2.app.data.ModuleMutationState
import com.zapret2.app.data.ModuleEnvironmentSnapshot
import com.zapret2.app.data.PendingModuleState
import com.zapret2.app.data.ServiceLifecycleController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleServiceStatusPolicyTest {

    @Test
    fun activeRuntime_isTheOnlyStateThatPermitsStatusScriptExecution() {
        listOf(ModuleInstallState.READY, ModuleInstallState.DISABLED).forEach { active ->
            assertNull(
                statusWithoutQuery(
                    active,
                    PendingModuleState.NONE,
                ),
            )
        }
    }

    @Test
    fun stagedFreshInstall_requiresRebootWithoutCallingMissingStatusScript() {
        assertEquals(
            ControlStatus.REBOOT_REQUIRED,
            statusWithoutQuery(
                ModuleInstallState.MISSING,
                PendingModuleState.READY,
            ),
        )
    }

    @Test
    fun absentAndBrokenInstallations_haveSpecificStatus() {
        assertEquals(
            ControlStatus.NOT_INSTALLED,
            statusWithoutQuery(
                ModuleInstallState.MISSING,
                PendingModuleState.NONE,
            ),
        )
        listOf(
            ModuleInstallState.PARTIAL,
            ModuleInstallState.UNREADABLE,
            ModuleInstallState.UNSUPPORTED_ABI,
        ).forEach { active ->
            assertEquals(
                ControlStatus.MODULE_NOT_READY,
                statusWithoutQuery(
                    active,
                    PendingModuleState.NONE,
                ),
            )
        }
    }

    @Test
    fun pendingUpdate_doesNotDisableHealthyActiveRuntime() {
        assertNull(
            statusWithoutQuery(
                ModuleInstallState.READY,
                PendingModuleState.READY,
            ),
        )
        val state = ControlUiState(
            moduleInstallState = ModuleInstallState.READY,
            pendingModuleState = PendingModuleState.READY,
        )
        assertTrue(state.isModuleOperational)
        assertEquals(R.string.control_module_state_update_reboot, state.moduleStateLabelRes)
    }

    @Test
    fun invalidPendingGeneration_isNotReadyWhenNoActiveGenerationExists() {
        listOf(
            PendingModuleState.PARTIAL,
            PendingModuleState.UNSUPPORTED_ABI,
            PendingModuleState.UNREADABLE,
        ).forEach { pending ->
            assertEquals(
                ControlStatus.MODULE_NOT_READY,
                statusWithoutQuery(ModuleInstallState.MISSING, pending),
            )
        }
    }

    @Test
    fun lifecycleState_isAnIndependentOperationalFence() {
        val state = ControlUiState(
            moduleInstallState = ModuleInstallState.READY,
            moduleMutationState = ModuleMutationState.IN_PROGRESS,
        )

        assertFalse(state.isModuleOperational)
        assertEquals(R.string.control_module_state_updating, state.moduleStateLabelRes)
        assertNull(statusWithoutQuery(state.moduleInstallState, state.pendingModuleState))
        assertEquals(
            ModuleMutationState.IN_PROGRESS,
            ServiceLifecycleController.LifecycleState.ACTIVE.toModuleMutationState(),
        )
        assertEquals(
            ModuleMutationState.BLOCKED,
            ServiceLifecycleController.LifecycleState.AMBIGUOUS.toModuleMutationState(),
        )
        assertEquals(
            ControlStatus.LIFECYCLE_BUSY,
            projectedControlStatus(
                serviceStatus = ServiceLifecycleController.ServiceStatus(
                    rootGranted = true,
                    processRunning = false,
                    lifecycleState = ServiceLifecycleController.LifecycleState.ACTIVE,
                ),
                canStopService = false,
            ),
        )
    }

    private fun statusWithoutQuery(
        activeState: ModuleInstallState,
        pendingState: PendingModuleState,
    ): ControlStatus? = ModuleEnvironmentSnapshot(
        activeState = activeState,
        pendingState = pendingState,
        nfqueueSupported = true,
    ).serviceAccess.statusWithoutQuery()
}
