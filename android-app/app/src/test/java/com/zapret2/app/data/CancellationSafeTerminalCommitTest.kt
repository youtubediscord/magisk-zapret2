package com.zapret2.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationSafeTerminalCommitTest {

    @Test
    fun cancellationDuringTerminalCommand_recordsCommitWithoutRollback() = runBlocking {
        val observed = runScenario(CommandBehavior.CANCEL_DURING_SUCCESS)

        assertCommittedWithoutRollback(observed)
    }

    @Test
    fun cancellationImmediatelyAfterTerminalCommand_recordsCommitWithoutRollback() = runBlocking {
        val observed = runScenario(CommandBehavior.CANCEL_AFTER_SUCCESS)

        assertCommittedWithoutRollback(observed)
    }

    @Test
    fun terminalCommandFailure_leavesStateForRollback() = runBlocking {
        val observed = runScenario(CommandBehavior.FAIL)

        assertFalse(observed.committedOutcome)
        assertEquals(1, observed.commitCount)
        assertEquals(1, observed.rollbackCount)
        assertFalse(observed.retainedArtifacts)
        assertFalse(observed.transactionCreated)
        assertFalse(observed.activeCandidate)
        assertFalse(observed.serviceStopped)
    }

    @Test
    fun deleteMayHaveOccurredAndBothResponsesUnavailable_defersWithoutRollback() {
        val resolution = UpdateTransactionProtocol.resolveTerminalAttempt(
            primarySucceeded = false,
            probeSucceeded = false,
            probeLines = emptyList(),
        )
        var rollbackCount = 0
        var retainedArtifacts = false

        when (resolution) {
            UpdateTransactionProtocol.TerminalResolution.NOT_COMMITTED -> rollbackCount += 1
            UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED -> retainedArtifacts = true
            UpdateTransactionProtocol.TerminalResolution.COMMITTED -> Unit
        }

        assertEquals(UpdateTransactionProtocol.TerminalResolution.AMBIGUOUS_COMMITTED, resolution)
        assertEquals(0, rollbackCount)
        assertTrue(retainedArtifacts)
    }

    @Test
    fun rollbackTerminalDeleteCancellation_commitsWithoutReentryOrRetainedBackupRecreation() = runBlocking {
        val result = CompletableDeferred<RollbackObserved>()
        var rollbackEntryCount = 1
        var retainedBackupPresent = true
        var retainedBackupRecreationCount = 0
        var transactionPresent = true

        val worker = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
            val callerJob = currentCoroutineContext()[Job] ?: error("Missing test job")
            val outcome = CancellationSafeTerminalCommit.run(
                command = {
                    // Models successful terminal delete: transaction and retained recovery
                    // artifacts are already irreversibly gone when cancellation is observed.
                    transactionPresent = false
                    retainedBackupPresent = false
                    callerJob.cancel()
                    yield()
                    CommandResult(success = true)
                },
                commandSucceeded = { it.success },
                onCommitted = {},
            )
            val restored = outcome is CancellationSafeTerminalCommit.Outcome.Committed
            if (!restored) {
                rollbackEntryCount += 1
                if (!retainedBackupPresent) {
                    retainedBackupRecreationCount += 1
                    retainedBackupPresent = true
                }
            }
            result.complete(RollbackObserved(
                restored = restored,
                rollbackEntryCount = rollbackEntryCount,
                retainedBackupPresent = retainedBackupPresent,
                retainedBackupRecreationCount = retainedBackupRecreationCount,
                transactionPresent = transactionPresent,
            ))
        }

        val observed = result.await()
        joinAll(worker)
        assertTrue(observed.restored)
        assertEquals(1, observed.rollbackEntryCount)
        assertFalse(observed.retainedBackupPresent)
        assertEquals(0, observed.retainedBackupRecreationCount)
        assertFalse(observed.transactionPresent)
    }

    private suspend fun runScenario(behavior: CommandBehavior): Observed {
        val result = CompletableDeferred<Observed>()
        var commitCount = 0
        var rollbackCount = 0
        var retainedArtifacts = true
        var transactionCreated = true
        var activeCandidate = true
        var serviceStopped = true

        val worker = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launch {
            val callerJob = currentCoroutineContext()[Job] ?: error("Missing test job")
            val committed = try {
                CancellationSafeTerminalCommit.run(
                    command = {
                        commitCount += 1
                        when (behavior) {
                            CommandBehavior.CANCEL_DURING_SUCCESS -> {
                                callerJob.cancel()
                                yield()
                                retainedArtifacts = false
                                CommandResult(success = true)
                            }
                            CommandBehavior.CANCEL_AFTER_SUCCESS -> CommandResult(success = true).also {
                                retainedArtifacts = false
                                callerJob.cancel()
                            }
                            CommandBehavior.FAIL -> CommandResult(success = false)
                        }
                    },
                    commandSucceeded = { it.success },
                    onCommitted = {
                        transactionCreated = false
                        activeCandidate = false
                        serviceStopped = false
                    },
                ) is CancellationSafeTerminalCommit.Outcome.Committed
            } catch (_: CancellationException) {
                false
            }
            if (!committed && transactionCreated) {
                rollbackCount += 1
                retainedArtifacts = false
                transactionCreated = false
                activeCandidate = false
                serviceStopped = false
            }
            result.complete(snapshot(
                committed,
                commitCount,
                rollbackCount,
                retainedArtifacts,
                transactionCreated,
                activeCandidate,
                serviceStopped,
            ))
        }

        val observed = result.await()
        joinAll(worker)
        return observed
    }

    private fun assertCommittedWithoutRollback(observed: Observed) {
        assertTrue(observed.committedOutcome)
        assertEquals(1, observed.commitCount)
        assertEquals(0, observed.rollbackCount)
        assertFalse(observed.retainedArtifacts)
        assertFalse(observed.transactionCreated)
        assertFalse(observed.activeCandidate)
        assertFalse(observed.serviceStopped)
    }

    private fun snapshot(
        committedOutcome: Boolean,
        commitCount: Int,
        rollbackCount: Int,
        retainedArtifacts: Boolean,
        transactionCreated: Boolean,
        activeCandidate: Boolean,
        serviceStopped: Boolean,
    ) = Observed(
        committedOutcome,
        commitCount,
        rollbackCount,
        retainedArtifacts,
        transactionCreated,
        activeCandidate,
        serviceStopped,
    )

    private enum class CommandBehavior {
        CANCEL_DURING_SUCCESS,
        CANCEL_AFTER_SUCCESS,
        FAIL,
    }

    private data class CommandResult(val success: Boolean)

    private data class RollbackObserved(
        val restored: Boolean,
        val rollbackEntryCount: Int,
        val retainedBackupPresent: Boolean,
        val retainedBackupRecreationCount: Int,
        val transactionPresent: Boolean,
    )

    private data class Observed(
        val committedOutcome: Boolean,
        val commitCount: Int,
        val rollbackCount: Int,
        val retainedArtifacts: Boolean,
        val transactionCreated: Boolean,
        val activeCandidate: Boolean,
        val serviceStopped: Boolean,
    )
}
