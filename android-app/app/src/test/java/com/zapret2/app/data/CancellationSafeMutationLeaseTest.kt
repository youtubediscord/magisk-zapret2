package com.zapret2.app.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationSafeMutationLeaseTest {

    @Test
    fun externalOwnerBlocksBeforeAdmissionOrWrite() = runBlocking {
        var admitted = false
        var released = false
        val failure = runCatching {
            CancellationSafeMutationLease.run<String, Unit>(
                acquire = { error("external lifecycle owner") },
                release = { released = true },
                block = { admitted = true },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(admitted)
        assertFalse(released)
    }

    @Test
    fun admissionAndWriteRemainInsideOneLeaseWithoutToctouGap() = runBlocking {
        val events = mutableListOf<String>()
        CancellationSafeMutationLease.run(
            acquire = { events += "acquire"; "lease" },
            release = { events += "release:$it" },
            block = {
                events += "admission:$it"
                events += "write:$it"
            },
        )

        assertEquals(listOf("acquire", "admission:lease", "write:lease", "release:lease"), events)
    }

    @Test
    fun mutationErrorReleasesExactOwnedLeaseBeforePropagating() = runBlocking {
        val events = mutableListOf<String>()
        val failure = runCatching {
            CancellationSafeMutationLease.run<String, Unit>(
                acquire = { events += "acquire"; "owned" },
                release = { events += "release:$it" },
                block = { events += "write"; error("write failed") },
            )
        }.exceptionOrNull()

        assertEquals("write failed", failure?.message)
        assertEquals(listOf("acquire", "write", "release:owned"), events)
    }

    @Test
    fun cancellationDuringPublicationReleasesBeforeCancellationEscapes() = runBlocking {
        val publicationStarted = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val released = CompletableDeferred<String>()
        var blockRan = false

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CancellationSafeMutationLease.run<String, Unit>(
                acquire = {
                    publicationStarted.complete(Unit)
                    allowPublication.await()
                    "owned"
                },
                release = { released.complete(it) },
                block = { blockRan = true },
            )
        }
        publicationStarted.await()
        job.cancel()
        allowPublication.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("owned", released.await())
        assertFalse(blockRan)
    }

    @Test
    fun cancellationDuringRelease_isRestoredBeforeSuccessfulReturn() = runBlocking {
        val releaseStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        val returnedNormally = AtomicBoolean(false)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CancellationSafeMutationLease.run(
                acquire = { "owned" },
                release = {
                    releaseStarted.complete(Unit)
                    allowRelease.await()
                },
                block = { "completed" },
            )
            returnedNormally.set(true)
        }
        releaseStarted.await()
        job.cancel()
        allowRelease.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(returnedNormally.get())
    }
}
