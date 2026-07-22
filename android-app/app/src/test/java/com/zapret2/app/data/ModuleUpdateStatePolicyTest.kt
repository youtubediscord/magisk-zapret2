package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUpdateStatePolicyTest {

    @Test
    fun snapshot_acceptsOnlyVerifiedRunningOrStoppedState() {
        assertEquals(
            ModuleUpdateStatePolicy.VerifiedState.RUNNING,
            ModuleUpdateStatePolicy.snapshot(healthy = true, fullyStopped = false)
        )
        assertEquals(
            ModuleUpdateStatePolicy.VerifiedState.STOPPED,
            ModuleUpdateStatePolicy.snapshot(healthy = false, fullyStopped = true)
        )
        assertNull(ModuleUpdateStatePolicy.snapshot(healthy = false, fullyStopped = false))
        assertNull(ModuleUpdateStatePolicy.snapshot(healthy = true, fullyStopped = true))
    }

    @Test
    fun snapshot_rejectsAuthorizationFromInvalidMachineStatus() {
        val invalidStatus = ServiceLifecycleController.parseStatusCommandResult(
            ServiceLifecycleController.CommandResult(
                success = true,
                exitCode = 0,
                stdout = healthyStatusLines().map {
                    if (it.startsWith("Z2_RULES=")) "Z2_RULES=2147483648" else it
                },
            ),
        )

        assertFalse(invalidStatus.metadataComplete)
        assertNull(ModuleUpdateStatePolicy.snapshot(invalidStatus.healthy, invalidStatus.fullyStopped))
    }

    @Test
    fun matches_requiresExactOriginalState() {
        assertTrue(ModuleUpdateStatePolicy.matches(ModuleUpdateStatePolicy.VerifiedState.RUNNING, true, false))
        assertFalse(ModuleUpdateStatePolicy.matches(ModuleUpdateStatePolicy.VerifiedState.RUNNING, false, true))
        assertTrue(ModuleUpdateStatePolicy.matches(ModuleUpdateStatePolicy.VerifiedState.STOPPED, false, true))
        assertFalse(ModuleUpdateStatePolicy.matches(ModuleUpdateStatePolicy.VerifiedState.STOPPED, true, false))
    }

    @Test
    fun rollbackResult_retainsFatalRecoveryEvidence() {
        val result = ModuleUpdateStatePolicy.RollbackResult.Incomplete(
            message = "restore verification failed",
            backupRetained = true,
            transactionRetained = true,
        )
        assertTrue(result.backupRetained)
        assertTrue(result.transactionRetained)
    }


    private fun healthyStatusLines(): List<String> = listOf(
        "Z2_STATUS=ok",
        "Z2_OWNED=1",
        "Z2_PROCESS=1",
        "Z2_ACTIVE=1",
        "Z2_PID=4242",
        "Z2_PID_VERIFIED=1",
        "Z2_PID_STARTTIME=98765",
        "Z2_OWNER_GENERATION=generation-1",
        "Z2_OWNER_METADATA_VERIFIED=1",
        "Z2_QNUM=200",
        "Z2_IPV4=1",
        "Z2_IPV6=1",
        "Z2_RULES=3",
        "Z2_EXPECTED_RULES=3",
        "Z2_IPV4_RULES=2",
        "Z2_IPV6_RULES=1",
        "Z2_RULESET_VERIFIED=1",
        "Z2_NFQUEUE=1",
        "Z2_QUEUE_BYPASS=1",
        "Z2_UPDATE_BLOCKED=0",
        "Z2_UNINSTALL_TOMBSTONE=0",
        "Z2_COMPLETE=1",
    )
}
