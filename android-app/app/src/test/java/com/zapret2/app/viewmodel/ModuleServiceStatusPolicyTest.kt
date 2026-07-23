package com.zapret2.app.viewmodel

import com.zapret2.app.R
import com.zapret2.app.data.ModuleInstallState
import com.zapret2.app.data.ModuleMutationState
import com.zapret2.app.data.ModuleEnvironmentSnapshot
import com.zapret2.app.data.PendingModuleState
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
                    ModuleMutationState.IDLE,
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
                ModuleMutationState.IDLE,
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
                ModuleMutationState.IDLE,
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
                    ModuleMutationState.IDLE,
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
                ModuleMutationState.IDLE,
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
    fun mutation_isAnIndependentOperationalFence() {
        val state = ControlUiState(
            moduleInstallState = ModuleInstallState.READY,
            moduleMutationState = ModuleMutationState.IN_PROGRESS,
        )

        assertFalse(state.isModuleOperational)
        assertEquals(R.string.control_module_state_updating, state.moduleStateLabelRes)
        assertEquals(
            ControlStatus.CHECKING,
            statusWithoutQuery(
                state.moduleInstallState,
                state.pendingModuleState,
                state.moduleMutationState,
            ),
        )
    }

    private fun statusWithoutQuery(
        activeState: ModuleInstallState,
        pendingState: PendingModuleState,
        mutationState: ModuleMutationState,
    ): ControlStatus? = ModuleEnvironmentSnapshot(
        activeState = activeState,
        pendingState = pendingState,
        mutationState = mutationState,
        nfqueueSupported = true,
    ).serviceAccess.statusWithoutQuery()
}
