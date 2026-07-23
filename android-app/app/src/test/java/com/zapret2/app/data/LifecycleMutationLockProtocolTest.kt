package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleMutationLockProtocolTest {

    private val pid = 7331
    private val token = "app.0123456789abcdef"
    private val boot = "01234567-89ab-4def-8abc-0123456789ab"

    @Test
    fun acquire_usesSharedGateAndOnlyReapsStableExactBootBoundAndroidOwner() {
        val command = requireNotNull(LifecycleMutationLockProtocol.buildAcquireCommand(pid, token))

        assertTrue(command.contains("claim_lifecycle_gate"))
        assertTrue(command.contains("kind=android-mutation"))
        assertTrue(command.contains("boot_id=%s"))
        assertTrue(command.contains("z2_owner_state=ambiguous"))
        assertTrue(command.contains("[ \"\$z2_owner_state\" = stale ]"))
        assertTrue(command.contains("sleep 1"))
        assertTrue(command.contains("foreign, malformed, or unknown lifecycle owner was preserved"))
        assertTrue(command.contains("entries=\$(find \"\$LIFECYCLE_LOCK\" -mindepth 1 -maxdepth 1"))
        assertFalse(command.contains("rm -rf"))
    }

    @Test
    fun acquireProtocol_isExactUniqueAndBoundToRequestedOwner() {
        val valid = listOf(
            "Z2_MUTATION_LOCK_PID=$pid",
            "Z2_MUTATION_LOCK_START=987654321",
            "Z2_MUTATION_LOCK_BOOT=$boot",
            "Z2_MUTATION_LOCK_TOKEN=$token",
            "Z2_MUTATION_LOCK_COMPLETE=1",
        )
        val lease = LifecycleMutationLockProtocol.parseAcquireOutput(valid, pid, token)
        assertNotNull(lease)
        assertEquals("987654321", lease?.starttime)

        assertNull(LifecycleMutationLockProtocol.parseAcquireOutput(valid.dropLast(1), pid, token))
        assertNull(LifecycleMutationLockProtocol.parseAcquireOutput(valid + valid.last(), pid, token))
        assertNull(LifecycleMutationLockProtocol.parseAcquireOutput(valid.map {
            if (it.startsWith("Z2_MUTATION_LOCK_TOKEN=")) "Z2_MUTATION_LOCK_TOKEN=foreign" else it
        }, pid, token))
        assertNull(LifecycleMutationLockProtocol.parseAcquireOutput(valid.map {
            if (it.startsWith("Z2_MUTATION_LOCK_BOOT=")) "Z2_MUTATION_LOCK_BOOT=UNKNOWN" else it
        }, pid, token))
    }

    @Test
    fun release_comparesEveryLeaseFieldAndRemovesOnlyExactOwnerDirectory() {
        val lease = LifecycleMutationLockProtocol.Lease(pid.toString(), "987654321", boot, token)
        val command = requireNotNull(
            LifecycleMutationLockProtocol.buildReleaseCommand(lease, "release0123456789")
        )

        assertTrue(command.contains("[ \"\$pid\" = \"\$expected_pid\" ]"))
        assertTrue(command.contains("[ \"\$start\" = \"\$expected_start\" ]"))
        assertTrue(command.contains("[ \"\$boot\" = \"\$expected_boot\" ]"))
        assertTrue(command.contains("[ \"\$token\" = \"\$expected_token\" ]"))
        assertTrue(command.contains("rm -f \"\$quarantine/owner\""))
        assertTrue(command.contains("rmdir \"\$quarantine\""))
        assertFalse(command.contains("rm -rf"))
        assertTrue(LifecycleMutationLockProtocol.parseReleaseOutput(listOf("Z2_MUTATION_LOCK_RELEASED=1")))
        assertFalse(LifecycleMutationLockProtocol.parseReleaseOutput(listOf("noise", "Z2_MUTATION_LOCK_RELEASED=1")))
    }

    @Test
    fun ambiguousAcquireProbeCanIdentifyOnlyTheExactStillLivePublisher() {
        val command = requireNotNull(
            LifecycleMutationLockProtocol.buildOwnedLeaseProbeCommand(pid, token)
        )

        assertTrue(command.contains("[ \"\$pid_line\" = \"pid=\$expected_pid\" ]"))
        assertTrue(command.contains("[ \"\$token_line\" = \"token=\$expected_token\" ]"))
        assertTrue(command.contains("[ \"\$current_boot\" = \"\$boot\" ]"))
        assertTrue(command.contains("actual=\$(proc_starttime \"\$expected_pid\")"))
        assertTrue(command.contains("[ \"\$after\" = \"\$before\" ]"))
        assertTrue(command.contains("Z2_MUTATION_LOCK_ABSENT=1"))
        assertFalse(command.contains("rm -"))
        assertFalse(command.contains("mv "))
        assertTrue(
            LifecycleMutationLockProtocol.isOwnedLeaseAbsentOutput(
                listOf("Z2_MUTATION_LOCK_ABSENT=1"),
            ),
        )
        assertFalse(
            LifecycleMutationLockProtocol.isOwnedLeaseAbsentOutput(
                listOf("noise", "Z2_MUTATION_LOCK_ABSENT=1"),
            ),
        )
    }

    @Test
    fun invalidMetadataNeverBuildsMutationCommands() {
        assertNull(LifecycleMutationLockProtocol.buildAcquireCommand(0, token))
        assertNull(LifecycleMutationLockProtocol.buildAcquireCommand(pid, "bad token"))
        assertNull(LifecycleMutationLockProtocol.buildReleaseCommand(
            LifecycleMutationLockProtocol.Lease(pid.toString(), "01", boot, token),
            "release",
        ))
    }
}
