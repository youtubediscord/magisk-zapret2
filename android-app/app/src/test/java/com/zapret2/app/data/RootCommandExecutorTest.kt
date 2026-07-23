package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RootCommandExecutorTest {

    private val shortBudget = RootCommandBudget(
        commandTimeoutSeconds = 1,
        transportTimeoutMillis = 80,
    )

    @Test
    fun transportTimeoutPoisonsExactSessionAndNextCommandUsesFreshGeneration() {
        val first = FakeSession()
        val second = FakeSession().apply {
            next.complete(RootCommandResult(code = 0, out = listOf("recovered")))
        }
        val sessions = ArrayDeque(listOf(first, second))
        val executor = BoundedRootCommandExecutor(
            sessionFactory = RootCommandSessionFactory { sessions.removeFirst() },
            queueTimeoutMillis = 20,
            closeDrainTimeoutMillis = 20,
        )

        val timedOut = executor.execute("first", shortBudget)
        val recovered = executor.execute("second", shortBudget)

        assertEquals(RootCommandFailure.TRANSPORT_TIMEOUT, timedOut.failure)
        assertTrue(timedOut.isIndeterminate)
        assertTrue(first.closed)
        assertEquals(listOf("recovered"), recovered.out)
        assertTrue(recovered.isSuccess)
        assertNotSame(first, second)
        assertEquals(1, first.submissions.get())
        assertEquals(1, second.submissions.get())
    }

    @Test
    fun queuedCallerFailsBoundedlyAndExecutorRecoversWhenOwnerCompletes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = object : RootCommandSession {
            override fun submit(command: String): Future<RootCommandResult> {
                val future = CompletableFuture<RootCommandResult>()
                entered.countDown()
                CompletableFuture.runAsync {
                    release.await()
                    future.complete(RootCommandResult(code = 0))
                }
                return future
            }

            override fun close() = Unit
        }
        val executor = BoundedRootCommandExecutor(
            sessionFactory = RootCommandSessionFactory { session },
            queueTimeoutMillis = 30,
            closeDrainTimeoutMillis = 20,
        )
        val holderPool = Executors.newSingleThreadExecutor()
        val holder = holderPool.submit<RootCommandResult> {
            executor.execute(
                "owner",
                RootCommandBudget(commandTimeoutSeconds = 1, transportTimeoutMillis = 500),
            )
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val queued = executor.execute("queued", shortBudget)
        assertEquals(RootCommandFailure.QUEUE_BUSY, queued.failure)

        release.countDown()
        assertTrue(holder.get(1, TimeUnit.SECONDS).isSuccess)
        holderPool.shutdownNow()
    }

    @Test
    fun commandTimeoutIsIndeterminateButKeepsHealthyTransportGeneration() {
        val session = FakeSession().apply {
            next.complete(RootCommandResult(code = 124))
        }
        val executor = BoundedRootCommandExecutor(
            sessionFactory = RootCommandSessionFactory { session },
            queueTimeoutMillis = 20,
            closeDrainTimeoutMillis = 20,
        )

        val result = executor.execute("slow", shortBudget)

        assertEquals(RootCommandFailure.COMMAND_TIMEOUT, result.failure)
        assertTrue(result.isIndeterminate)
        assertFalse(session.closed)
    }

    @Test
    fun shellFailurePoisonsGenerationWithoutRetryingMutation() {
        val sessionsCreated = AtomicInteger()
        val first = FakeSession().apply {
            next.complete(
                RootCommandResult(
                    failure = RootCommandFailure.SHELL_DIED,
                    detail = "transport disconnected",
                ),
            )
        }
        val executor = BoundedRootCommandExecutor(
            sessionFactory = RootCommandSessionFactory {
                sessionsCreated.incrementAndGet()
                first
            },
            queueTimeoutMillis = 20,
            closeDrainTimeoutMillis = 20,
        )

        val result = executor.execute("mutate-once", shortBudget)

        assertEquals(RootCommandFailure.SHELL_DIED, result.failure)
        assertTrue(result.isIndeterminate)
        assertEquals(1, sessionsCreated.get())
        assertEquals(1, first.submissions.get())
        assertTrue(first.closed)
    }

    @Test
    fun unavailableShellFailsBeforeCommandSubmission() {
        val executor = BoundedRootCommandExecutor(
            sessionFactory = RootCommandSessionFactory {
                error("root denied")
            },
            queueTimeoutMillis = 20,
            closeDrainTimeoutMillis = 20,
        )

        val result = executor.execute("must-not-run", shortBudget)

        assertEquals(RootCommandFailure.SHELL_UNAVAILABLE, result.failure)
        assertFalse(result.isIndeterminate)
        assertEquals("root denied", result.detail)
    }

    private class FakeSession : RootCommandSession {
        val next = CompletableFuture<RootCommandResult>()
        val submissions = AtomicInteger()

        @Volatile
        var closed = false

        override fun submit(command: String): Future<RootCommandResult> {
            submissions.incrementAndGet()
            return next
        }

        override fun close() {
            closed = true
            next.complete(
                RootCommandResult(
                    failure = RootCommandFailure.SHELL_DIED,
                    detail = "closed",
                ),
            )
        }
    }
}
