package com.zapret2.app.data

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleUpdateRecoveryTest {

    private enum class CleanupMatrixState { ABSENT, V1, V2, MALFORMED, DIGEST_MISMATCH }
    private enum class LockMatrixState { ABSENT, CURRENT, STALE, MALFORMED }
    private enum class MatrixExpected { NOT_NEEDED, BLOCKED, RECOVER, PRESERVE }

    private val bootId = "01234567-89ab-4def-8abc-0123456789ab"
    private val freshBootId = "89abcdef-0123-4567-89ab-cdef01234567"

    @Test
    fun recoverLocked_executesTransactionCleanupAndLockTruthTable() = runBlocking {
        for (transactionPresent in listOf(false, true)) {
            for (cleanupState in CleanupMatrixState.entries) {
                for (lock in LockMatrixState.entries) {
                    val backend = FakeRecoveryDependencies(
                        transactionPresent = transactionPresent,
                        cleanupState = cleanupState,
                        lockState = lock,
                    )
                    val actual = ModuleUpdateRecovery.recoverLocked(backend)
                    val expected = when {
                        cleanupState == CleanupMatrixState.MALFORMED -> MatrixExpected.PRESERVE
                        transactionPresent && cleanupState == CleanupMatrixState.DIGEST_MISMATCH -> MatrixExpected.PRESERVE
                        lock == LockMatrixState.MALFORMED -> MatrixExpected.PRESERVE
                        lock == LockMatrixState.CURRENT -> MatrixExpected.BLOCKED
                        transactionPresent -> MatrixExpected.RECOVER
                        cleanupState != CleanupMatrixState.ABSENT -> MatrixExpected.RECOVER
                        lock == LockMatrixState.STALE -> MatrixExpected.RECOVER
                        else -> MatrixExpected.NOT_NEEDED
                    }
                    assertEquals(
                        "transaction=$transactionPresent cleanup=$cleanupState lock=$lock",
                        expected,
                        actual.matrixOutcome(),
                    )
                    assertEquals(
                        "every acquired recovery lock must be released",
                        backend.acquireCount,
                        backend.releaseCount,
                    )
                }
            }
        }
    }

    @Test
    fun recoverLocked_isIdempotentAndPreservesCasDriftAndReleaseFailure() = runBlocking {
        val backend = FakeRecoveryDependencies(
            transactionPresent = true,
            cleanupState = CleanupMatrixState.V2,
            lockState = LockMatrixState.STALE,
        )
        assertTrue(ModuleUpdateRecovery.recoverLocked(backend) is ModuleUpdateRecovery.Result.Recovered)
        assertTrue(ModuleUpdateRecovery.recoverLocked(backend) is ModuleUpdateRecovery.Result.NotNeeded)
        assertEquals(1, backend.acquireCount)
        assertEquals(1, backend.releaseCount)

        val cleanupOnly = FakeRecoveryDependencies(
            transactionPresent = false,
            cleanupState = CleanupMatrixState.V1,
            lockState = LockMatrixState.STALE,
        )
        assertTrue(ModuleUpdateRecovery.recoverLocked(cleanupOnly) is ModuleUpdateRecovery.Result.Recovered)
        assertTrue(ModuleUpdateRecovery.recoverLocked(cleanupOnly) is ModuleUpdateRecovery.Result.NotNeeded)
        assertEquals(1, cleanupOnly.acquireCount)
        assertEquals(1, cleanupOnly.releaseCount)

        val casDrift = FakeRecoveryDependencies(
            transactionPresent = true,
            cleanupState = CleanupMatrixState.ABSENT,
            lockState = LockMatrixState.STALE,
            failOwnerRebind = true,
        )
        assertTrue(ModuleUpdateRecovery.recoverLocked(casDrift) is ModuleUpdateRecovery.Result.Failed)
        assertEquals(1, casDrift.acquireCount)
        assertEquals(1, casDrift.releaseCount)
        assertTrue(casDrift.transactionPresent)

        val releaseFailure = FakeRecoveryDependencies(
            transactionPresent = false,
            cleanupState = CleanupMatrixState.V2,
            lockState = LockMatrixState.STALE,
            releaseSucceeds = false,
        )
        val failed = ModuleUpdateRecovery.recoverLocked(releaseFailure)
        assertTrue(failed is ModuleUpdateRecovery.Result.Failed)
        assertTrue((failed as ModuleUpdateRecovery.Result.Failed).message.contains("could not be released"))
        assertEquals(1, releaseFailure.releaseCount)
    }

    @Test
    fun recoverLocked_cancellationAfterAcquireAlwaysReleasesAndPreservesEvidence() = runBlocking {
        val backend = FakeRecoveryDependencies(
            transactionPresent = false,
            cleanupState = CleanupMatrixState.V2,
            lockState = LockMatrixState.STALE,
            cancelPendingCleanup = true,
        )
        var cancelled = false
        try {
            ModuleUpdateRecovery.recoverLocked(backend)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, backend.acquireCount)
        assertEquals(1, backend.releaseCount)
        assertTrue(backend.cleanupState == CleanupMatrixState.V2)
    }

    @Test
    fun parseTransaction_acceptsOnlyExactBoundedPaths() {
        val id = "123-456"
        val transaction = ModuleUpdateRecovery.parseTransaction(validLines(id))

        assertNotNull(transaction)
        assertEquals("candidate_active", transaction?.phase)
        assertEquals(ModuleUpdateStatePolicy.VerifiedState.RUNNING, transaction?.preUpdateState)
        assertEquals(
            ModuleUpdatePreservation.DisableMarkerExpectation.ABSENT,
            transaction?.disableMarkerExpectation,
        )
        assertEquals("4242", transaction?.ownerPid)
        assertEquals(bootId, transaction?.ownerBootId)
        assertEquals("/data/adb/modules/.zapret2-backup-$id", transaction?.backupDir)
        assertEquals("/data/adb/modules/.zapret2-recovery-$id", transaction?.recoveryDir)
    }

    @Test
    fun parseTransaction_rejectsPathSubstitutionAndDuplicateFields() {
        assertNull(
            ModuleUpdateRecovery.parseTransaction(
                validLines("123-456").map {
                    if (it.startsWith("backup_dir=")) "backup_dir=/data/adb/modules/zapret2" else it
                }
            )
        )
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456") + "phase=verified"))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456") + "disable_marker_expectation=absent"))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456") + "future=value"))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456") + "malformed"))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456").map { " $it" }))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456").filterNot {
            it.startsWith("disable_marker_expectation=")
        }))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456").map {
            if (it.startsWith("disable_marker_expectation=")) "disable_marker_expectation=maybe" else it
        }))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("123-456").map {
            if (it.startsWith("version=")) "version=1" else it
        }))
        assertNull(
            ModuleUpdateRecovery.parseTransaction(
                validLines("123-456").map {
                    if (it.startsWith("pre_update_state=")) "pre_update_state=degraded" else it
                }
            )
        )
    }

    @Test
    fun parseTransaction_acceptsEveryDurableRetainedBackupRecoveryPhase() {
        listOf(
            "restore_copying",
            "restore_candidate_ready",
            "restore_active_moved",
            "restore_candidate_active",
            "restored",
        ).forEach { phase ->
            val parsed = ModuleUpdateRecovery.parseTransaction(
                validLines("phase-$phase").map { if (it.startsWith("phase=")) "phase=$phase" else it },
            )
            assertEquals(phase, parsed?.phase)
        }
    }

    @Test
    fun sharedGoldenV2Fixture_andEveryDurablePhaseAreAccepted() {
        val golden = sharedGoldenLines()
        assertNotNull(ModuleUpdateRecovery.parseTransaction(golden))
        listOf(
            "prepared", "stopped", "candidate_ready", "active_move_intent", "active_moved", "candidate_active",
            "verified", "restored", "restore_copying", "restore_candidate_ready",
            "restore_active_moved", "restore_candidate_active",
        ).forEach { phase ->
            assertEquals(phase, ModuleUpdateRecovery.parseTransaction(golden.map {
                if (it.startsWith("phase=")) "phase=$phase" else it
            })?.phase)
        }
    }

    @Test
    fun parserRejectsOwnerTamperingAndPresentRunningContradiction() {
        listOf("owner_pid", "owner_starttime", "owner_created_epoch", "owner_boot_id").forEach { field ->
            assertNull(ModuleUpdateRecovery.parseTransaction(validLines("missing-$field").filterNot {
                it.startsWith("$field=")
            }))
            assertNull(ModuleUpdateRecovery.parseTransaction(validLines("duplicate-$field") + "$field=7"))
        }
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("bad-owner").map {
            when {
                it.startsWith("owner_pid=") -> "owner_pid=0"
                it.startsWith("owner_starttime=") -> "owner_starttime=01"
                it.startsWith("owner_created_epoch=") -> "owner_created_epoch=-1"
                else -> it
            }
        }))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("bad-boot").map {
            if (it.startsWith("owner_boot_id=")) "owner_boot_id=not-a-boot-id" else it
        }))
        assertNull(ModuleUpdateRecovery.parseTransaction(validLines("present-running").map {
            if (it.startsWith("disable_marker_expectation=")) "disable_marker_expectation=present" else it
        }))
    }

    @Test
    fun recoveryDisablePredicate_requiresExactPrivateEmptyMarker() {
        val predicate = ModuleUpdatePreservation.safeDisableMarkerPredicate(
            "/data/adb/modules/.zapret2-recovery-test"
        )

        assertEquals(true, predicate.contains("[ ! -L"))
        assertEquals(true, predicate.contains("stat -c %u"))
        assertEquals(true, predicate.contains("stat -c %h"))
        assertEquals(true, predicate.contains("stat -c %s"))
        assertEquals(true, predicate.contains("stat -c %a"))
        assertEquals(true, predicate.contains("= 0"))
        assertEquals(true, predicate.contains("= 600"))
    }

    @Test
    fun promotedDisablePredicate_rejectsDeletionSymlinkSizeModeAndOwnershipTampering() {
        val predicate = ModuleUpdatePreservation.expectedDisableMarkerPredicate(
            "/data/adb/modules/zapret2",
            ModuleUpdatePreservation.DisableMarkerExpectation.PRESENT,
        )

        assertEquals(true, predicate.contains("[ -e "))
        assertEquals(true, predicate.contains("[ -f "))
        assertEquals(true, predicate.contains("[ ! -L "))
        assertEquals(true, predicate.contains("stat -c %u"))
        assertEquals(true, predicate.contains("stat -c %h"))
        assertEquals(true, predicate.contains("stat -c %s"))
        assertEquals(true, predicate.contains("stat -c %a"))
        assertEquals(true, predicate.contains("= 0"))
        assertEquals(true, predicate.contains("= 1"))
        assertEquals(true, predicate.contains("= 600"))
    }

    @Test
    fun promotedDisablePredicate_rejectsExpectedAbsenceInjection() {
        val predicate = ModuleUpdatePreservation.expectedDisableMarkerPredicate(
            "/data/adb/modules/zapret2",
            ModuleUpdatePreservation.DisableMarkerExpectation.ABSENT,
        )

        assertEquals(true, predicate.contains("[ ! -e "))
        assertEquals(true, predicate.contains("[ ! -L "))
        assertEquals(false, predicate.contains(" || "))
        assertEquals(false, predicate.contains("[ -f "))
    }

    @Test
    fun directoryProbe_requiresExactUniqueCanonicalTerminalProtocol() {
        val valid = directoryProbeLines()
        assertNotNull(ModuleUpdateRecovery.parseDirectoryProbe(valid))
        assertNull(ModuleUpdateRecovery.parseDirectoryProbe(valid.dropLast(1)))
        assertNull(ModuleUpdateRecovery.parseDirectoryProbe(valid + "Z2_DIR_PROBE_COMPLETE=1"))
        assertNull(ModuleUpdateRecovery.parseDirectoryProbe(
            valid.toMutableList().apply { add(size - 1, "Z2_DIR_UNKNOWN=0") },
        ))
        assertNull(ModuleUpdateRecovery.parseDirectoryProbe(valid.map {
            if (it.startsWith("Z2_DIR_BACKUP=")) "Z2_DIR_BACKUP=2" else it
        }))
        assertNull(ModuleUpdateRecovery.parseDirectoryProbe(valid.dropLast(1) + "Z2_DIR_PROBE_COMPLETE=01"))
    }

    @Test
    fun directoryProbe_rejectsLinksNonRootOwnersAndUnexpectedModesBeforeClassification() {
        val transaction = requireNotNull(ModuleUpdateRecovery.parseTransaction(validLines("safe-directory-probe")))
        val command = ModuleUpdateRecovery.buildDirectoryProbe(transaction)

        assertEquals(5, Regex("z2_probe_update_dir Z2_DIR_").findAll(command).count())
        assertEquals(true, command.contains("[ ! -L \"${'$'}z2_probe_path\" ]"))
        assertEquals(true, command.contains("stat -c %u \"${'$'}z2_probe_path\""))
        assertEquals(true, command.contains("stat -c %a \"${'$'}z2_probe_path\""))
        assertEquals(true, command.contains("700|711|750|751|755"))
        assertEquals(true, command.contains("Z2_DIR_PROBE_COMPLETE=1"))
    }

    @Test
    fun terminalRecoveryCommitAtomicallyRevalidatesDurableDisableExpectationBeforeRemoval() {
        val transaction = requireNotNull(ModuleUpdateRecovery.parseTransaction(
            validLines("terminal-binding").map { line ->
                when {
                    line.startsWith("pre_update_state=") -> "pre_update_state=stopped"
                    line.startsWith("disable_marker_expectation=") -> "disable_marker_expectation=present"
                    else -> line
                }
            },
        ))
        val command = ModuleUpdateRecovery.buildTerminalCommitPlan(transaction, "fresh-token").command
        val integrityIndex = command.indexOf("[ ! -L '/data/adb/modules/zapret2' ]")
        val predicateIndex = command.indexOf("/data/adb/modules/zapret2/disable")
        val removalIndex = command.indexOf("rm -f \"${'$'}transaction\"")

        assertEquals(true, integrityIndex >= 0)
        assertEquals(true, predicateIndex > integrityIndex)
        assertEquals(true, predicateIndex >= 0)
        assertEquals(true, removalIndex > predicateIndex)
        assertEquals(true, command.substring(predicateIndex, removalIndex).contains("&&"))
        assertEquals(true, command.contains("[ -e '/data/adb/modules/zapret2/disable' ]"))
        assertEquals(true, command.contains("z2_update_lock_owner_alive"))
        assertEquals(true, command.contains("4242:9223372036854775807:1770000000:$bootId:fresh-token"))
    }

    @Test
    fun activeModuleIntegrity_requiresExactRootOwnedNonLinkLifecycleFiles() {
        val required = ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = true)
        val optional = ModuleUpdateRecovery.activeModuleIntegrityPredicate(requireInstallGeneration = false)

        assertEquals(true, required.contains("[ -d '/data/adb/modules/zapret2' ]"))
        assertEquals(true, required.contains("[ ! -L '/data/adb/modules/zapret2' ]"))
        assertEquals(true, required.contains("stat -c %u '/data/adb/modules/zapret2'"))
        assertEquals(true, required.contains("700|711|750|751|755"))
        listOf("zapret2", "zapret2/scripts").forEach { relative ->
            val path = "/data/adb/modules/zapret2/$relative"
            assertEquals(relative, true, required.contains("[ -d '$path' ]"))
            assertEquals(relative, true, required.contains("[ ! -L '$path' ]"))
        }
        listOf(
            "module.prop",
            "zapret2/nfqws2",
            "zapret2/scripts/common.sh",
            "zapret2/scripts/command-builder.sh",
            "zapret2/scripts/zapret-start.sh",
            "zapret2/scripts/zapret-stop.sh",
            "zapret2/scripts/zapret-status.sh",
        ).forEach { relative ->
            val path = "/data/adb/modules/zapret2/$relative"
            assertEquals(relative, true, required.contains("[ -f '$path' ]"))
            assertEquals(relative, true, required.contains("[ ! -L '$path' ]"))
            assertEquals(relative, true, required.contains("stat -c %u '$path'"))
            assertEquals(relative, true, required.contains("stat -c %h '$path'"))
        }
        assertEquals(true, required.contains("false\n  else"))
        assertEquals(true, optional.contains("[ ! -e \"${'$'}install_meta\" ]"))

        val backupPath = "/data/adb/modules/.zapret2-backup-test"
        val backup = ModuleUpdateRecovery.moduleIntegrityPredicate(
            backupPath,
            requireInstallGeneration = false,
        )
        assertEquals(true, backup.contains("[ -d '$backupPath' ]"))
        assertEquals(true, backup.contains("$backupPath/zapret2/scripts/common.sh"))
        assertEquals(true, backup.contains("$backupPath/zapret2/scripts/command-builder.sh"))
        assertEquals(
            true,
            ModuleUpdateRecovery.safeRootDirectoryOrAbsentPredicate(backupPath)
                .contains("[ ! -e '$backupPath' ] && [ ! -L '$backupPath' ]"),
        )
        val safeDirectory = ModuleUpdateRecovery.safeRootDirectoryPredicate(backupPath)
        assertEquals(true, safeDirectory.contains("[ -d '$backupPath' ]"))
        assertEquals(true, safeDirectory.contains("[ ! -L '$backupPath' ]"))
        assertEquals(true, safeDirectory.contains("stat -c %u '$backupPath'"))
        assertEquals(true, safeDirectory.contains("700|711|750|751|755"))
    }

    @Test
    fun activeMoveEvidence_coversBothPhasesAndEveryDirectoryTruthTableRow() {
        data class Row(
            val phase: String,
            val modulePresent: Boolean,
            val backupPresent: Boolean,
            val expected: ModuleUpdateRecovery.ActiveMoveEvidence,
        )

        listOf(
            Row("active_move_intent", modulePresent = false, backupPresent = false,
                ModuleUpdateRecovery.ActiveMoveEvidence.AMBIGUOUS),
            Row("active_move_intent", modulePresent = true, backupPresent = false,
                ModuleUpdateRecovery.ActiveMoveEvidence.BEFORE_MOVE),
            Row("active_move_intent", modulePresent = false, backupPresent = true,
                ModuleUpdateRecovery.ActiveMoveEvidence.MOVED_IN_FLIGHT),
            // Both can exist after the retained-backup rollback copy is restored but
            // before active_moved is published. The backup makes this replayable.
            Row("active_move_intent", modulePresent = true, backupPresent = true,
                ModuleUpdateRecovery.ActiveMoveEvidence.MOVED_IN_FLIGHT),
            Row("active_moved", modulePresent = false, backupPresent = false,
                ModuleUpdateRecovery.ActiveMoveEvidence.AMBIGUOUS),
            Row("active_moved", modulePresent = true, backupPresent = false,
                ModuleUpdateRecovery.ActiveMoveEvidence.AMBIGUOUS),
            Row("active_moved", modulePresent = false, backupPresent = true,
                ModuleUpdateRecovery.ActiveMoveEvidence.RESULT_PUBLISHED),
            Row("active_moved", modulePresent = true, backupPresent = true,
                ModuleUpdateRecovery.ActiveMoveEvidence.RESULT_PUBLISHED),
        ).forEach { row ->
            assertEquals(
                "${row.phase}: module=${row.modulePresent}, backup=${row.backupPresent}",
                row.expected,
                ModuleUpdateRecovery.classifyActiveMoveEvidence(
                    row.phase,
                    modulePresent = row.modulePresent,
                    backupPresent = row.backupPresent,
                ),
            )
        }
    }

    @Test
    fun ownerRebindPlan_requiresFreshExactLockAndCasBeforeAtomicReplacement() {
        val transaction = requireNotNull(ModuleUpdateRecovery.parseTransaction(validLines("rebind-cas")))
        val oldDigest = "a".repeat(64)
        val plan = requireNotNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction = transaction,
            expectedSha256 = oldDigest,
            ownerPid = 7331,
            ownerStarttime = "987654321",
            ownerCreatedEpoch = "1770000099",
            ownerBootId = freshBootId,
            ownerToken = "fresh-recovery-token",
        ))

        assertEquals("7331", plan.transaction.ownerPid)
        assertEquals("987654321", plan.transaction.ownerStarttime)
        assertEquals("1770000099", plan.transaction.ownerCreatedEpoch)
        assertEquals(freshBootId, plan.transaction.ownerBootId)
        assertEquals(64, plan.digest.length)
        assertEquals(true, plan.digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(true, plan.command.contains("owner_pid=7331\n"))
        assertEquals(true, plan.command.contains("owner_starttime=987654321\n"))
        assertEquals(true, plan.command.contains("owner_created_epoch=1770000099\n"))
        assertEquals(true, plan.command.contains("owner_boot_id=$freshBootId\n"))
        assertEquals(true, plan.command.contains("7331:987654321:1770000099:$freshBootId:fresh-recovery-token"))

        val firstDigestCheck = plan.command.indexOf(oldDigest)
        val secondDigestCheck = plan.command.indexOf(oldDigest, firstDigestCheck + oldDigest.length)
        val finalLockCheck = plan.command.lastIndexOf("7331:987654321:1770000099:$freshBootId:fresh-recovery-token")
        val replacement = plan.command.indexOf("mv -f \"${'$'}tmp\" \"${'$'}transaction\"")
        assertEquals(true, firstDigestCheck >= 0)
        assertEquals(true, secondDigestCheck > firstDigestCheck)
        assertEquals(true, plan.command.contains("[ \"${'$'}new_digest\" = '${plan.digest}' ]"))
        assertEquals(true, plan.command.contains("stat -c %u \"${'$'}tmp\""))
        assertEquals(true, plan.command.contains("stat -c %a \"${'$'}tmp\""))
        assertEquals(true, finalLockCheck > secondDigestCheck)
        assertEquals(true, replacement > finalLockCheck)

        assertNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction, oldDigest, 0, "987654321", "1770000099", freshBootId, "fresh-recovery-token"
        ))
        assertNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction, oldDigest, 7331, "01", "1770000099", freshBootId, "fresh-recovery-token"
        ))
        assertNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction, "not-a-digest", 7331, "987654321", "1770000099", freshBootId, "fresh-recovery-token"
        ))
        assertNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction, oldDigest, 7331, "987654321", "1770000099", "", "fresh-recovery-token"
        ))
        assertNull(ModuleUpdateRecovery.buildOwnerRebindPlan(
            transaction, oldDigest, 7331, "987654321", "1770000099",
            "01234567-89ab-4def-8abc-0123456789aB", "fresh-recovery-token"
        ))
    }

    private fun ModuleUpdateRecovery.Result.matrixOutcome(): MatrixExpected = when (this) {
        ModuleUpdateRecovery.Result.NotNeeded -> MatrixExpected.NOT_NEEDED
        ModuleUpdateRecovery.Result.Recovered -> MatrixExpected.RECOVER
        is ModuleUpdateRecovery.Result.Blocked -> MatrixExpected.BLOCKED
        is ModuleUpdateRecovery.Result.Failed -> MatrixExpected.PRESERVE
    }

    private inner class FakeRecoveryDependencies(
        var transactionPresent: Boolean,
        var cleanupState: CleanupMatrixState,
        var lockState: LockMatrixState,
        private val failOwnerRebind: Boolean = false,
        private val cancelPendingCleanup: Boolean = false,
        private val releaseSucceeds: Boolean = true,
    ) : ModuleUpdateRecovery.RecoveryDependencies {
        var acquireCount = 0
        var releaseCount = 0

        private val transactionLines = validLines("matrix-recovery").map { line ->
            when {
                line.startsWith("phase=") -> "phase=verified"
                line.startsWith("pre_update_state=") -> "pre_update_state=stopped"
                else -> line
            }
        }
        private val transactionContent = transactionLines.joinToString("\n", postfix = "\n")
        private val transactionDigest = UpdateTransactionProtocol.sha256(transactionContent)

        override suspend fun executeRoot(command: String): ServiceLifecycleController.CommandResult {
            fun ok(lines: List<String> = emptyList()) =
                ServiceLifecycleController.CommandResult(success = true, stdout = lines)
            fun failed() = ServiceLifecycleController.CommandResult(success = false, exitCode = 1)

            return when {
                command.contains("Z2_TRANSACTION_SHA256=") -> {
                    if (!transactionPresent) ok(listOf("Z2_TRANSACTION_ABSENT=1"))
                    else ok(listOf("Z2_TRANSACTION_SHA256=$transactionDigest") + transactionLines)
                }
                command.contains("Z2_CLEANUP_SHA256=") -> {
                    if (cleanupState == CleanupMatrixState.ABSENT) {
                        ok(listOf("Z2_CLEANUP_ABSENT=1"))
                    } else {
                        val lines = cleanupLines()
                        val digest = UpdateTransactionProtocol.sha256(lines.joinToString("\n", postfix = "\n"))
                        ok(listOf("Z2_CLEANUP_SHA256=$digest") + lines)
                    }
                }
                command.contains(".update.transaction.rebind.") -> {
                    if (failOwnerRebind) failed() else ok()
                }
                command.contains("Z2_DIR_PROBE_COMPLETE=1") -> ok(directoryProbeLines().map { line ->
                    when {
                        line.startsWith("Z2_DIR_MODULE=") -> "Z2_DIR_MODULE=1"
                        line.startsWith("Z2_DIR_BACKUP=") -> "Z2_DIR_BACKUP=0"
                        else -> line
                    }
                })
                command.contains("cleanup_tmp=") -> {
                    transactionPresent = false
                    cleanupState = CleanupMatrixState.ABSENT
                    ok()
                }
                command.contains("zapret-status.sh") && command.contains("--machine") ->
                    ServiceLifecycleController.CommandResult(
                        success = false,
                        stdout = stoppedStatusLines(),
                        exitCode = 1,
                    )
                cancelPendingCleanup && !transactionPresent &&
                    command.contains("cleanup=") && command.contains("rm -rf") ->
                    throw CancellationException("injected pending-cleanup cancellation")
                !transactionPresent && command.contains("cleanup=") && command.contains("rm -rf") -> {
                    cleanupState = CleanupMatrixState.ABSENT
                    ok()
                }
                else -> ok()
            }
        }

        override suspend fun inspectLock(): UpdateLockProtocol.State = when (lockState) {
            LockMatrixState.ABSENT -> UpdateLockProtocol.State.ABSENT
            LockMatrixState.CURRENT -> UpdateLockProtocol.State.ACTIVE
            LockMatrixState.STALE -> UpdateLockProtocol.State.STALE
            LockMatrixState.MALFORMED -> UpdateLockProtocol.State.MALFORMED
        }

        override suspend fun acquireLock(
            pid: Int,
            token: String,
            requireTransaction: Boolean,
        ): UpdateLockProtocol.Record? {
            if (lockState == LockMatrixState.MALFORMED || lockState == LockMatrixState.CURRENT) return null
            assertEquals(transactionPresent, requireTransaction)
            acquireCount += 1
            lockState = LockMatrixState.CURRENT
            return UpdateLockProtocol.Record(
                pid = pid.toString(),
                starttime = "987654321",
                createdEpoch = "1770000099",
                bootId = freshBootId,
                token = token,
            )
        }

        override suspend fun releaseLock(record: UpdateLockProtocol.Record): Boolean {
            releaseCount += 1
            if (releaseSucceeds) lockState = LockMatrixState.ABSENT
            return releaseSucceeds
        }

        override fun processId(): Int = 7331
        override fun newToken(): String = "fresh-recovery-token"
        override suspend fun delay(millis: Long) = Unit

        private fun cleanupLines(): List<String> {
            if (cleanupState == CleanupMatrixState.MALFORMED) {
                return listOf("version=2", "owner_pid=broken")
            }
            val version = if (cleanupState == CleanupMatrixState.V1) "1" else "2"
            val owner = UpdateTransactionProtocol.Owner(
                pid = "4242",
                starttime = "9",
                createdEpoch = "10",
                bootId = if (version == "2") bootId else "",
                token = "interrupted-owner",
            )
            val boundDigest = if (cleanupState == CleanupMatrixState.DIGEST_MISMATCH) {
                "f".repeat(64)
            } else {
                transactionDigest
            }
            return UpdateTransactionProtocol.cleanupContent(
                UpdateTransactionProtocol.CleanupPending(
                    owner = owner,
                    transactionDigest = boundDigest,
                    paths = listOf("/data/adb/modules/.zapret2-backup-matrix-recovery"),
                    version = version,
                )
            ).trimEnd().lines()
        }
    }

    private fun stoppedStatusLines(): List<String> = listOf(
        "Z2_STATUS=stopped",
        "Z2_OWNED=0",
        "Z2_PROCESS=0",
        "Z2_ACTIVE=0",
        "Z2_PID=",
        "Z2_PID_VERIFIED=0",
        "Z2_PID_STARTTIME=",
        "Z2_OWNER_GENERATION=",
        "Z2_OWNER_METADATA_VERIFIED=0",
        "Z2_QNUM=200",
        "Z2_IPV4=0",
        "Z2_IPV6=0",
        "Z2_RULES=0",
        "Z2_EXPECTED_RULES=0",
        "Z2_IPV4_RULES=0",
        "Z2_IPV6_RULES=0",
        "Z2_RULESET_VERIFIED=1",
        "Z2_NFQUEUE=0",
        "Z2_QUEUE_BYPASS=0",
        "Z2_UPDATE_BLOCKED=0",
        "Z2_UNINSTALL_TOMBSTONE=0",
        "Z2_COMPLETE=1",
    )

    private fun validLines(id: String): List<String> = listOf(
        "version=3",
        "transaction_id=$id",
        "phase=candidate_active",
        "created_epoch=1770000000",
        "pre_update_state=running",
        "disable_marker_expectation=absent",
        "owner_pid=4242",
        "owner_starttime=9223372036854775807",
        "owner_created_epoch=1770000000",
        "owner_boot_id=$bootId",
        "module_dir=/data/adb/modules/zapret2",
        "update_dir=/data/adb/modules/.zapret2-update-$id",
        "backup_dir=/data/adb/modules/.zapret2-backup-$id",
        "failed_dir=/data/adb/modules/.zapret2-failed-$id",
    )

    private fun directoryProbeLines(): List<String> = listOf(
        "Z2_DIR_MODULE=1",
        "Z2_DIR_UPDATE=0",
        "Z2_DIR_BACKUP=1",
        "Z2_DIR_FAILED=0",
        "Z2_DIR_RECOVERY=0",
        "Z2_DIR_PROBE_COMPLETE=1",
    )

    private fun sharedGoldenLines(): List<String> {
        val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) {
            it.parentFile
        }.take(12).map { File(it, "tests/fixtures/update-transaction-v2.golden") }
            .firstOrNull(File::isFile)
            ?: error("Shared update transaction v2 fixture was not found")
        return fixture.readLines()
    }
}
