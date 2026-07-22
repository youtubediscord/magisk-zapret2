package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTransactionProtocolTest {

    private val bootId = "01234567-89ab-4def-8abc-0123456789ab"

    @Test
    fun activeMove_requiresOwnerAndCasOnBothSidesOfDirectoryMove() {
        val owner = validOwner()
        val intentDigest = "a".repeat(64)
        val publication = UpdateTransactionProtocol.buildActiveMove(
            owner = owner,
            intentDigest = intentDigest,
            resultContent = "version=2\nphase=active_moved\n",
            moduleDir = "/data/adb/modules/zapret2",
            backupDir = "/data/adb/modules/.zapret2-backup-test",
            sourcePrerequisite = "[ -f '/trusted/source' ]",
            movedPrerequisite = "[ -f '/trusted/moved' ]",
            tempPath = "/data/adb/zapret2-state/.active-move-test",
        )

        assertNotNull(publication)
        val command = requireNotNull(publication).command
        val ownerTuple = "4242:987654321:1770000000:$bootId:owner-token"
        val directoryMove = command.indexOf(
            "mv '/data/adb/modules/zapret2' '/data/adb/modules/.zapret2-backup-test'"
        )
        val ownerBeforeMove = command.indexOf(ownerTuple)
        val digestBeforeMove = command.indexOf(intentDigest)
        val ownerAfterMove = command.indexOf(ownerTuple, directoryMove + 1)
        val digestAfterMove = command.indexOf(intentDigest, directoryMove + 1)
        val activeMovedPublication = command.indexOf("mv -f \"${'$'}tmp\" \"${'$'}transaction\"")

        assertTrue(ownerBeforeMove >= 0)
        assertTrue(digestBeforeMove > ownerBeforeMove)
        assertTrue(directoryMove > digestBeforeMove)
        assertTrue(ownerAfterMove > directoryMove)
        assertTrue(digestAfterMove > ownerAfterMove)
        assertTrue(activeMovedPublication > digestAfterMove)
        assertTrue(command.lastIndexOf("[ -f '/trusted/source' ]", directoryMove) > digestBeforeMove)
        assertTrue(command.indexOf("[ -f '/trusted/moved' ]", directoryMove) in
            (directoryMove + 1) until activeMovedPublication)
        assertEquals(
            UpdateTransactionProtocol.sha256("version=2\nphase=active_moved\n"),
            publication.digest,
        )
    }

    @Test
    fun activeMove_rejectsMalformedOwnerBeforeBuildingMutationCommand() {
        val digest = "b".repeat(64)
        listOf(
            validOwner().copy(pid = "0"),
            validOwner().copy(pid = "01"),
            validOwner().copy(starttime = "01"),
            validOwner().copy(createdEpoch = "-1"),
            validOwner().copy(bootId = ""),
            validOwner().copy(bootId = "not-a-boot-id"),
            validOwner().copy(token = "bad token"),
        ).forEach { owner ->
            assertNull(UpdateTransactionProtocol.buildActiveMove(
                owner = owner,
                intentDigest = digest,
                resultContent = "phase=active_moved\n",
                moduleDir = "/data/adb/modules/zapret2",
                backupDir = "/data/adb/modules/.zapret2-backup-test",
                sourcePrerequisite = "true",
                movedPrerequisite = "true",
                tempPath = "/data/adb/zapret2-state/.active-move-test",
            ))
        }
    }

    @Test
    fun candidatePromotion_revalidatesOwnerJournalAndDirectoryImmediatelyBeforeMove() {
        val digest = "9".repeat(64)
        val updateDir = "/data/adb/modules/.zapret2-update-candidate-test"
        val moduleDir = "/data/adb/modules/zapret2"
        val command = requireNotNull(UpdateTransactionProtocol.buildCandidatePromotion(
            owner = validOwner(),
            expectedDigest = digest,
            updateDir = updateDir,
            moduleDir = moduleDir,
            candidatePrerequisite = "[ -f '/trusted/candidate' ]",
            promotedPrerequisite = "[ -f '/trusted/promoted' ]",
        ))
        val move = command.indexOf("mv '$updateDir' '$moduleDir'")
        val ownerTuple = "4242:987654321:1770000000:$bootId:owner-token"
        val finalOwner = command.lastIndexOf(ownerTuple, move)
        val finalDigest = command.lastIndexOf(digest, move)
        val finalSourceCheck = command.lastIndexOf("[ -d '$updateDir' ]", move)
        val finalDestinationCheck = command.lastIndexOf("[ ! -e '$moduleDir' ]", move)

        assertTrue(move > 0)
        assertTrue(finalOwner in 0 until move)
        assertTrue(finalDigest in finalOwner until move)
        assertTrue(finalSourceCheck in finalDigest until move)
        assertTrue(finalDestinationCheck in finalSourceCheck until move)
        assertTrue(command.lastIndexOf("[ -f '/trusted/candidate' ]", move) in finalSourceCheck until move)
        assertTrue(command.indexOf("[ -f '/trusted/promoted' ]", move) > move)
        assertTrue(command.contains("[ ! -L '$updateDir' ]"))
        assertTrue(command.contains("stat -c %u '$updateDir'"))
        assertTrue(command.contains("700|711|750|751|755"))
        assertTrue(command.substring(move).contains("[ ! -e '$updateDir' ]"))
        assertTrue(command.substring(move).contains("[ -d '$moduleDir' ]"))

        assertNull(UpdateTransactionProtocol.buildCandidatePromotion(
            validOwner(), digest, "/data/adb/modules/.zapret2-backup-wrong", moduleDir,
            "true", "true",
        ))
        assertNull(UpdateTransactionProtocol.buildCandidatePromotion(
            validOwner(), digest, updateDir, "/data/adb/modules/not-zapret2",
            "true", "true",
        ))
        assertNull(UpdateTransactionProtocol.buildCandidatePromotion(
            validOwner(), "bad", updateDir, moduleDir,
            "true", "true",
        ))
    }

    @Test
    fun ownerTransactionGuard_rejectsMalformedIdentityAndBindsBothCasValues() {
        val digest = "8".repeat(64)
        val guard = requireNotNull(UpdateTransactionProtocol.buildOwnerTransactionGuard(
            owner = validOwner(),
            expectedDigest = digest,
        ))

        assertTrue(guard.contains("z2_update_lock_owner_alive"))
        assertTrue(guard.contains("4242:987654321:1770000000:$bootId:owner-token"))
        assertTrue(guard.contains(digest))
        assertTrue(guard.contains("stat -c %h \"${'$'}transaction\""))
        assertNull(UpdateTransactionProtocol.buildOwnerTransactionGuard(
            owner = validOwner().copy(token = "bad token"),
            expectedDigest = digest,
        ))
        assertNull(UpdateTransactionProtocol.buildOwnerTransactionGuard(
            owner = validOwner(),
            expectedDigest = "bad",
        ))
    }

    @Test
    fun ownerGuard_canRequireAnAbsentJournalWithoutWeakeningOwnerCas() {
        val guard = requireNotNull(UpdateTransactionProtocol.buildOwnerGuard(
            owner = validOwner(),
            expectedTransactionDigest = null,
        ))

        assertTrue(guard.contains("z2_update_lock_owner_alive"))
        assertTrue(guard.contains("4242:987654321:1770000000:$bootId:owner-token"))
        assertTrue(guard.contains("[ ! -e \"${'$'}transaction\" ]"))
        assertTrue(guard.contains("[ ! -L \"${'$'}transaction\" ]"))
        assertNull(UpdateTransactionProtocol.buildOwnerGuard(
            owner = validOwner().copy(bootId = "not-a-boot-id"),
            expectedTransactionDigest = null,
        ))
        assertNull(UpdateTransactionProtocol.buildOwnerGuard(
            owner = validOwner(),
            expectedTransactionDigest = "bad",
        ))
    }

    @Test
    fun retainedCancellation_releasesLiveOwnerLockForFreshRecoveryOwner() {
        assertTrue(UpdateTransactionProtocol.shouldReleaseOwnerLock(
            markerAcquired = true,
            recoveryArtifactsRetained = true,
        ))
        assertEquals(false, UpdateTransactionProtocol.shouldReleaseOwnerLock(
            markerAcquired = false,
            recoveryArtifactsRetained = true,
        ))
    }

    @Test
    fun publication_revalidatesExactOwnerAndPriorJournalImmediatelyBeforeReplace() {
        val priorDigest = "c".repeat(64)
        val publication = requireNotNull(UpdateTransactionProtocol.buildPublication(
            owner = validOwner(),
            content = "version=2\nphase=candidate_ready\n",
            expectedPriorDigest = priorDigest,
            tempPath = "/data/adb/zapret2-state/.transaction-test",
        ))
        val replacement = publication.command.indexOf("mv -f \"${'$'}tmp\" \"${'$'}transaction\"")
        val finalOwner = publication.command.lastIndexOf("4242:987654321:1770000000:$bootId:owner-token", replacement)
        val finalDigest = publication.command.lastIndexOf(priorDigest, replacement)

        assertTrue(replacement > 0)
        assertTrue(finalOwner in 0 until replacement)
        assertTrue(finalDigest in finalOwner until replacement)
        assertEquals(false, publication.command.contains("stale-token"))
    }

    @Test
    fun terminalDelete_makesJournalDeletionTheLastFallibleCommitPoint() {
        val terminalDigest = "d".repeat(64)
        val command = requireNotNull(UpdateTransactionProtocol.buildTerminalDelete(
            owner = validOwner(),
            expectedDigest = terminalDigest,
            prerequisite = "[ -d '/data/adb/modules/zapret2' ]",
            cleanupPaths = listOf("/data/adb/modules/.zapret2-backup-test"),
        ))
        val delete = command.indexOf("rm -f \"${'$'}transaction\"")
        val finalOwner = command.lastIndexOf("4242:987654321:1770000000:$bootId:owner-token", delete)
        val finalDigest = command.lastIndexOf(terminalDigest, delete)
        val ownerAfterDelete = command.indexOf("4242:987654321:1770000000:$bootId:owner-token", delete)
        val cleanup = command.indexOf("rm -rf '/data/adb/modules/.zapret2-backup-test'", delete)
        val cleanupPublication = command.indexOf("mv -f \"${'$'}cleanup_tmp\" \"${'$'}cleanup\"")
        val lines = command.lines()
        val deleteLine = lines.indexOfFirst { it == "rm -f \"${'$'}transaction\" || exit 1" }

        assertTrue(finalOwner in 0 until delete)
        assertTrue(finalDigest in finalOwner until delete)
        assertEquals(-1, ownerAfterDelete)
        assertTrue(cleanupPublication in 0 until finalOwner)
        assertTrue(cleanup > delete)
        val rootOwnedPathCheck = command.lastIndexOf(
            "stat -c %u '/data/adb/modules/.zapret2-backup-test'",
            delete,
        )
        assertTrue(rootOwnedPathCheck in finalDigest until delete)
        assertTrue(deleteLine >= 0)
        lines.forEachIndexed { index, line ->
            if ("exit 1" in line) assertTrue("fallible line $index followed commit line $deleteLine", index <= deleteLine)
        }
        assertEquals("exit 0", lines.last { it.isNotBlank() })
    }

    @Test
    fun terminalDelete_partialMutationSchedule_preservesJournalBeforeDeleteAndCommitsAfterDelete() {
        val plan = requireNotNull(UpdateTransactionProtocol.buildTerminalDeletePlan(
            owner = validOwner(),
            expectedDigest = "e".repeat(64),
            prerequisite = "[ -d '/data/adb/modules/zapret2' ]",
            cleanupPaths = listOf(
                "/data/adb/modules/.zapret2-update-test",
                "/data/adb/modules/.zapret2-backup-test",
            ),
        ))
        val command = plan.command
        val lines = command.lines()
        val deleteLine = lines.indexOfFirst { it == "rm -f \"${'$'}transaction\" || exit 1" }
        val failureCapable = lines.indices.filter { "exit 1" in lines[it] }
        val directoryModeCheck = command.lastIndexOf("700|711|750|751|755", command.indexOf("rm -f \"${'$'}transaction\""))

        assertTrue(directoryModeCheck >= 0)

        failureCapable.forEach { failedAt ->
            val state = simulateFailure(deleteLine, failedAt)
            assertTrue(state.journalPresent)
            assertTrue(state.rollbackAllowed)
            assertEquals(false, state.committed)
        }
        lines.indices.filter { it > deleteLine }.forEach { failedAt ->
            val state = simulateFailure(deleteLine, failedAt)
            assertEquals(false, state.journalPresent)
            assertEquals(false, state.rollbackAllowed)
            assertTrue(state.committed)
        }

        val probe = requireNotNull(UpdateTransactionProtocol.buildTerminalCommitProbe(validOwner(), plan))
        assertTrue(probe.contains("Z2_TERMINAL_COMMIT=not_committed"))
        assertTrue(probe.contains("Z2_TERMINAL_COMMIT=committed"))
        assertTrue(probe.contains(plan.pendingDigest))
        assertTrue(probe.contains("4242:987654321:1770000000:$bootId:owner-token"))
        assertNull(UpdateTransactionProtocol.buildTerminalCommitProbe(
            validOwner().copy(token = "drifted-token"),
            plan,
        ))

        assertEquals(
            UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED,
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = false,
                probeLines = emptyList(),
            ),
        )
        assertEquals(
            UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED,
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = true,
                probeLines = listOf("truncated"),
            ),
        )
        assertEquals(
            UpdateTransactionProtocol.TerminalResolution.COMMITTED,
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = true,
                probeLines = listOf("Z2_TERMINAL_COMMIT=committed"),
            ),
        )
        assertEquals(
            UpdateTransactionProtocol.TerminalResolution.NOT_COMMITTED,
            UpdateTransactionProtocol.resolveTerminalAttempt(
                primarySucceeded = false,
                probeSucceeded = true,
                probeLines = listOf("Z2_TERMINAL_COMMIT=not_committed"),
            ),
        )
    }

    @Test
    fun cleanupPending_isAuthenticatedBoundedAndRecoverableIdempotently() {
        val pending = UpdateTransactionProtocol.CleanupPending(
            owner = validOwner(),
            transactionDigest = "f".repeat(64),
            paths = listOf(
                "/data/adb/modules/.zapret2-update-test",
                "/data/adb/modules/.zapret2-backup-test",
            ),
        )
        val content = UpdateTransactionProtocol.cleanupContent(pending)
        val parsed = UpdateTransactionProtocol.parseCleanupPending(content.trimEnd().lines())
        assertEquals(pending, parsed)

        val digest = UpdateTransactionProtocol.sha256(content)
        val recovery = requireNotNull(UpdateTransactionProtocol.buildPendingCleanupRecovery(
            recoveryOwner = validOwner().copy(token = "fresh-recovery-token"),
            pending = pending,
            expectedPendingDigest = digest,
        ))
        val cleanupMutation = recovery.indexOf("rm -rf ")
        assertTrue(recovery.lastIndexOf("z2_update_lock_owner_alive", cleanupMutation) >= 0)
        assertTrue(recovery.lastIndexOf(digest, cleanupMutation) >= 0)
        assertTrue(recovery.lastIndexOf("700|711|750|751|755", cleanupMutation) >= 0)
        val evidenceDelete = recovery.indexOf("rm -f \"${'$'}cleanup\" || exit 1")
        val finalOwner = recovery.lastIndexOf("4242:987654321:1770000000:$bootId:fresh-recovery-token", evidenceDelete)
        val finalDigest = recovery.lastIndexOf(digest, evidenceDelete)
        assertTrue(finalOwner in 0 until evidenceDelete)
        assertTrue(finalDigest in finalOwner until evidenceDelete)
        assertTrue(recovery.contains("stat -c %u '/data/adb/modules/.zapret2-update-test'"))
        assertTrue(recovery.contains("stat -c %u '/data/adb/modules/.zapret2-backup-test'"))
        assertTrue(recovery.substring(evidenceDelete).contains("sync || true\nexit 0"))

        assertNull(UpdateTransactionProtocol.parseCleanupPending(
            content.trimEnd().lines().map { if (it.startsWith("cleanup_1=")) "cleanup_1=/data/adb/modules/zapret2" else it }
        ))
        assertNull(UpdateTransactionProtocol.buildPendingCleanupRecovery(
            validOwner(), pending, "0".repeat(64)
        ))

        val tooMany = (1..5).map { "/data/adb/modules/.zapret2-update-test-$it" }
        assertNull(UpdateTransactionProtocol.buildTerminalDeletePlan(
            validOwner(), "a".repeat(64), "true", tooMany,
        ))
        assertNull(UpdateTransactionProtocol.buildConsumeUncommittedCleanup(
            validOwner(), "a".repeat(64), tooMany,
        ))
    }

    @Test
    fun legacyCleanupV1_preservesExactBootlessSerializationForDigestRetry() {
        val legacyLines = listOf(
            "version=1",
            "owner_pid=4242",
            "owner_starttime=987654321",
            "owner_created_epoch=1770000000",
            "owner_token=legacy-token",
            "transaction_digest=${"b".repeat(64)}",
            "cleanup_count=1",
            "cleanup_1=/data/adb/modules/.zapret2-backup-legacy",
        )
        val pending = requireNotNull(UpdateTransactionProtocol.parseCleanupPending(legacyLines))
        assertEquals("1", pending.version)
        assertEquals("", pending.owner.bootId)
        val exactContent = legacyLines.joinToString("\n", postfix = "\n")
        assertEquals(exactContent, UpdateTransactionProtocol.cleanupContent(pending))
        assertNotNull(UpdateTransactionProtocol.buildPendingCleanupRecovery(
            recoveryOwner = validOwner().copy(token = "fresh-recovery"),
            pending = pending,
            expectedPendingDigest = UpdateTransactionProtocol.sha256(exactContent),
        ))
    }

    @Test
    fun rollback_consumesExactPrecommitEvidenceBeforeRestoredJournalPublication() {
        val transactionDigest = "1".repeat(64)
        val paths = listOf(
            "/data/adb/modules/.zapret2-update-test",
            "/data/adb/modules/.zapret2-backup-test",
            "/data/adb/modules/.zapret2-failed-test",
        )
        val consume = requireNotNull(UpdateTransactionProtocol.buildConsumeUncommittedCleanup(
            owner = validOwner(),
            expectedTransactionDigest = transactionDigest,
            cleanupPaths = paths,
        ))
        val evidenceDelete = consume.indexOf("rm -f \"${'$'}cleanup\" || exit 1")
        val finalOwner = consume.lastIndexOf("4242:987654321:1770000000:$bootId:owner-token", evidenceDelete)
        val finalTransaction = consume.lastIndexOf(transactionDigest, evidenceDelete)

        assertTrue(consume.contains("if [ ! -e \"${'$'}cleanup\" ] && [ ! -L \"${'$'}cleanup\" ]; then exit 0; fi"))
        assertTrue(finalOwner in 0 until evidenceDelete)
        assertTrue(finalTransaction in finalOwner until evidenceDelete)

        val restored = UpdateTransactionProtocol.buildPublication(
            owner = validOwner(),
            content = "version=2\nphase=restored\n",
            expectedPriorDigest = transactionDigest,
            tempPath = "/data/adb/zapret2-state/.restored-test",
        )
        assertNotNull(restored)

        val restartDiscard = UpdateTransactionProtocol.buildDiscardUncommittedCleanup(
            recoveryOwner = validOwner().copy(token = "fresh-recovery-token"),
            expectedTransactionDigest = transactionDigest,
            expectedPendingDigest = UpdateTransactionProtocol.sha256(
                UpdateTransactionProtocol.cleanupContent(
                    UpdateTransactionProtocol.CleanupPending(validOwner(), transactionDigest, paths)
                )
            ),
        )
        assertNotNull(restartDiscard)
    }

    private fun simulateFailure(deleteLine: Int, failedAt: Int): SimulatedTerminalState =
        if (failedAt <= deleteLine) {
            SimulatedTerminalState(journalPresent = true, rollbackAllowed = true, committed = false)
        } else {
            SimulatedTerminalState(journalPresent = false, rollbackAllowed = false, committed = true)
        }

    private data class SimulatedTerminalState(
        val journalPresent: Boolean,
        val rollbackAllowed: Boolean,
        val committed: Boolean,
    )

    private fun validOwner() = UpdateTransactionProtocol.Owner(
        pid = "4242",
        starttime = "987654321",
        createdEpoch = "1770000000",
        bootId = bootId,
        token = "owner-token",
    )
}
