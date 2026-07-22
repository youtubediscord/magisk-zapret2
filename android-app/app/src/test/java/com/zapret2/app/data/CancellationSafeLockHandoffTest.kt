package com.zapret2.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellationSafeLockHandoffTest {
    @Test
    fun cancellationAfterPublication_releasesBeforePropagating() = runBlocking {
        val publicationStarted = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        val released = CompletableDeferred<String>()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CancellationSafeLockHandoff.acquire<String>(
                publish = {
                    publicationStarted.complete(Unit)
                    allowPublication.await()
                    "owned-lock"
                },
                releaseIfCancelled = { released.complete(it) },
            )
        }
        publicationStarted.await()
        job.cancel()
        allowPublication.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals("owned-lock", released.await())
    }
}
