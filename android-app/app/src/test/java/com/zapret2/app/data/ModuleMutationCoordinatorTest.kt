package com.zapret2.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleMutationCoordinatorTest {

    @Test
    fun safetyProbe_requiresExactUniqueCanonicalTerminalProtocol() {
        val valid = validLines()
        val parsed = ModuleMutationCoordinator.parseSafetyProbe(valid)
        assertNotNull(parsed)
        assertFalse(parsed!!.fullRollbackTransaction)
        assertFalse(parsed.moduleDisabled)

        assertNull(ModuleMutationCoordinator.parseSafetyProbe(valid.dropLast(1)))
        assertNull(ModuleMutationCoordinator.parseSafetyProbe(valid + "Z2_MUTATION_PROBE_COMPLETE=1"))
        assertNull(ModuleMutationCoordinator.parseSafetyProbe(
            valid.toMutableList().apply { add(size - 1, "Z2_UNKNOWN=0") },
        ))
        assertNull(ModuleMutationCoordinator.parseSafetyProbe(valid.map {
            if (it.startsWith("Z2_MODULE_DISABLED=")) "Z2_MODULE_DISABLED=true" else it
        }))
        assertNull(ModuleMutationCoordinator.parseSafetyProbe(valid.dropLast(1) + "Z2_MUTATION_PROBE_COMPLETE=01"))
        assertNull(ModuleMutationCoordinator.parseSafetyProbe(valid.map { " $it" }))

        val fenced = valid.map {
            when {
                it.startsWith("Z2_FULL_ROLLBACK_TRANSACTION=") -> "Z2_FULL_ROLLBACK_TRANSACTION=1"
                it.startsWith("Z2_MODULE_DISABLED=") -> "Z2_MODULE_DISABLED=1"
                else -> it
            }
        }
        val parsedFences = ModuleMutationCoordinator.parseSafetyProbe(fenced)
        assertNotNull(parsedFences)
        assertTrue(parsedFences!!.fullRollbackTransaction)
        assertTrue(parsedFences.moduleDisabled)

        assertTrue(ModuleMutationCoordinator.requiresCrossProcessLease(ModuleMutationCoordinator.Operation.MUTATION))
        assertFalse(
            ModuleMutationCoordinator.requiresCrossProcessLease(
                ModuleMutationCoordinator.Operation.PACKAGE_STAGING,
            ),
        )
        assertFalse(ModuleMutationCoordinator.requiresCrossProcessLease(ModuleMutationCoordinator.Operation.LIFECYCLE_SCRIPT))

        val inheritedCommand = ModuleMutationCoordinator.buildInheritedLifecycleCommand(
            command = "sh '/data/adb/modules/zapret2/zapret2/scripts/zapret-restart.sh'",
            lease = LifecycleMutationLockProtocol.Lease(
                pid = "7331",
                starttime = "987654321",
                bootId = "01234567-89ab-4def-8abc-0123456789ab",
                token = "app.token",
            ),
        )
        assertTrue(inheritedCommand.startsWith(
            "ZAPRET2_LIFECYCLE_TOKEN='app.token' " +
                "ZAPRET2_LIFECYCLE_OWNER_PID='7331' " +
                "ZAPRET2_LIFECYCLE_OWNER_START='987654321' ",
        ))
    }

    private fun validLines(): List<String> = listOf(
        "Z2_FULL_ROLLBACK_TRANSACTION=0",
        "Z2_UNINSTALL_TOMBSTONE=0",
        "Z2_MAGISK_REMOVE=0",
        "Z2_MODULE_DISABLED=0",
        "Z2_MUTATION_PROBE_COMPLETE=1",
    )
}
