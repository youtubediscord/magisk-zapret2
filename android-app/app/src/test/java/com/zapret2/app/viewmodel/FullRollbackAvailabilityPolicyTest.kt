package com.zapret2.app.viewmodel

import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.data.ModuleInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullRollbackAvailabilityPolicyTest {

    @Test
    fun rootAcquisitionFailuresMapToExactUserVisibleStatuses() {
        assertEquals(
            ControlStatus.ROOT_DENIED,
            ServiceLifecycleController.RootAccessState.DENIED.toControlStatus(),
        )
        assertEquals(
            ControlStatus.ROOT_MANAGER_UNAVAILABLE,
            ServiceLifecycleController.RootAccessState.MANAGER_UNAVAILABLE.toControlStatus(),
        )
        assertEquals(
            ControlStatus.ROOT_SHELL_FAILED,
            ServiceLifecycleController.RootAccessState.SHELL_FAILURE.toControlStatus(),
        )
        assertEquals(
            ControlStatus.ROOT_TIMEOUT,
            ServiceLifecycleController.RootAccessState.TIMEOUT.toControlStatus(),
        )
    }

    @Test
    fun runningDegradedAndStoppedAreAvailableWithRootAndInstalledModule() {
        listOf(
            ControlStatus.RUNNING,
            ControlStatus.DEGRADED,
            ControlStatus.STOPPED,
        ).forEach { status ->
            assertTrue(
                FullRollbackAvailabilityPolicy.isAvailable(
                    status = status,
                    hasRootAccess = true,
                    moduleInstallState = ModuleInstallState.READY,
                    isToggling = false,
                    isCheckingForUpdates = false,
                    isUpdating = false,
                    isRollingBack = false,
                ),
            )
        }
    }

    @Test
    fun checkingAndUnavailableStatesAreNeverAvailable() {
        listOf(
            ControlStatus.CHECKING,
            ControlStatus.ROOT_DENIED,
            ControlStatus.ROOT_MANAGER_UNAVAILABLE,
            ControlStatus.ROOT_SHELL_FAILED,
            ControlStatus.ROOT_TIMEOUT,
            ControlStatus.UNAVAILABLE,
        ).forEach { status ->
            assertFalse(
                FullRollbackAvailabilityPolicy.isAvailable(
                    status = status,
                    hasRootAccess = true,
                    moduleInstallState = ModuleInstallState.READY,
                    isToggling = false,
                    isCheckingForUpdates = false,
                    isUpdating = false,
                    isRollingBack = false,
                ),
            )
        }
    }

    @Test
    fun rootModuleAndBusyGuardsAreFailClosed() {
        val baseline = ControlUiState(
            status = ControlStatus.STOPPED,
            hasRootAccess = true,
            moduleInstallState = ModuleInstallState.READY,
            nfqueueSupported = false,
            canStopService = false,
        )
        assertTrue(baseline.canFullRollback)
        assertTrue(baseline.canPurgeModule)
        assertFalse(baseline.copy(hasRootAccess = false).canFullRollback)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.MISSING).canFullRollback)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.PARTIAL).canFullRollback)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.UPDATING).canFullRollback)
        assertFalse(baseline.copy(isToggling = true).canFullRollback)
        assertFalse(baseline.copy(isCheckingForUpdates = true).canFullRollback)
        assertFalse(baseline.copy(isUpdating = true).canFullRollback)
        assertFalse(baseline.copy(isSavingSettings = true).canFullRollback)
        assertFalse(
            baseline.copy(fullRollback = FullRollbackUiState.InProgress).canFullRollback,
        )
        assertFalse(baseline.copy(hasRootAccess = false).canPurgeModule)
        assertTrue(baseline.copy(moduleInstallState = ModuleInstallState.DISABLED).canPurgeModule)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.MISSING).canPurgeModule)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.PARTIAL).canPurgeModule)
        assertFalse(baseline.copy(isUpdating = true).canPurgeModule)
        assertFalse(
            baseline.copy(modulePurge = ModulePurgeUiState.InProgress).canPurgeModule,
        )
    }

    @Test
    fun settingEditsAreAvailableOnlyWhenTheModuleIsReadyAndControlIsIdle() {
        val baseline = ControlUiState(
            status = ControlStatus.STOPPED,
            hasRootAccess = true,
            moduleInstallState = ModuleInstallState.READY,
            hasAuthoritativeRuntimeSettings = true,
        )

        assertTrue(baseline.canEditSettings)
        assertFalse(baseline.copy(hasAuthoritativeRuntimeSettings = false).canEditSettings)
        assertFalse(baseline.copy(hasRootAccess = false).canEditSettings)
        assertFalse(baseline.copy(status = ControlStatus.CHECKING).canEditSettings)
        assertFalse(baseline.copy(status = ControlStatus.UNAVAILABLE).canEditSettings)
        assertFalse(baseline.copy(moduleInstallState = ModuleInstallState.MISSING).canEditSettings)
        assertFalse(baseline.copy(isToggling = true).canEditSettings)
        assertFalse(baseline.copy(isCheckingForUpdates = true).canEditSettings)
        assertFalse(baseline.copy(isUpdating = true).canEditSettings)
        assertFalse(baseline.copy(isSavingSettings = true).canEditSettings)
        assertFalse(
            baseline.copy(fullRollback = FullRollbackUiState.InProgress).canEditSettings,
        )
    }
}
