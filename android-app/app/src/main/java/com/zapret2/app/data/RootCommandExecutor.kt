package com.zapret2.app.data

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

internal data class RootCommandBudget(
    val commandTimeoutSeconds: Long,
    val transportTimeoutMillis: Long,
) {
    init {
        require(commandTimeoutSeconds > 0)
        require(transportTimeoutMillis > 0)
    }
}

internal enum class RootCommandPolicy(
    val budget: RootCommandBudget,
) {
    OBSERVATION(RootCommandBudget(commandTimeoutSeconds = 20, transportTimeoutMillis = 25_000)),
    MUTATION(RootCommandBudget(commandTimeoutSeconds = 60, transportTimeoutMillis = 65_000)),
    LIFECYCLE(RootCommandBudget(commandTimeoutSeconds = 420, transportTimeoutMillis = 425_000)),
    PACKAGE_INSTALL(RootCommandBudget(commandTimeoutSeconds = 300, transportTimeoutMillis = 305_000)),
}

internal enum class RootCommandFailure {
    QUEUE_BUSY,
    SHELL_UNAVAILABLE,
    COMMAND_TIMEOUT,
    TRANSPORT_TIMEOUT,
    SHELL_DIED,
}

internal data class RootCommandResult(
    val out: List<String> = emptyList(),
    val err: List<String> = emptyList(),
    val code: Int? = null,
    val failure: RootCommandFailure? = null,
    val detail: String? = null,
) {
    val isSuccess: Boolean get() = failure == null && code == 0
    val isIndeterminate: Boolean
        get() = failure in setOf(
            RootCommandFailure.COMMAND_TIMEOUT,
            RootCommandFailure.TRANSPORT_TIMEOUT,
            RootCommandFailure.SHELL_DIED,
        )
}

internal interface RootCommandSession {
    fun submit(command: String): Future<RootCommandResult>
    fun close()
}

internal fun interface RootCommandSessionFactory {
    fun create(): RootCommandSession
}

/**
 * Owns one root transport generation at a time.
 *
 * A libsu job is delimited by markers written to both stdout and stderr. If either marker is
 * lost, libsu waits in FutureTask.get() indefinitely even while the root shell is idle. This
 * boundary gives every job a deadline, poisons that exact shell generation on transport loss,
 * and admits the next command only after the broken generation has been detached.
 */
internal class BoundedRootCommandExecutor(
    private val sessionFactory: RootCommandSessionFactory,
    private val queueTimeoutMillis: Long = 5_000,
    private val closeDrainTimeoutMillis: Long = 2_000,
) {
    private val gate = ReentrantLock(true)

    @Volatile
    private var session: RootCommandSession? = null

    fun execute(command: String, budget: RootCommandBudget): RootCommandResult {
        val acquired = try {
            gate.tryLock(queueTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Interrupted while waiting for the root command queue")
                .also { it.initCause(interrupted) }
        }
        if (!acquired) {
            return RootCommandResult(
                failure = RootCommandFailure.QUEUE_BUSY,
                detail = "Another root command is still running",
            )
        }

        return try {
            executeLocked(command, budget)
        } finally {
            gate.unlock()
        }
    }

    private fun executeLocked(command: String, budget: RootCommandBudget): RootCommandResult {
        val activeSession = try {
            session ?: sessionFactory.create().also { session = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return RootCommandResult(
                failure = RootCommandFailure.SHELL_UNAVAILABLE,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
        val wrapped = boundedShellCommand(command, budget.commandTimeoutSeconds)
        val future = try {
            activeSession.submit(wrapped)
        } catch (error: Exception) {
            poison(activeSession)
            return RootCommandResult(
                failure = RootCommandFailure.SHELL_DIED,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }

        val result = try {
            future.get(budget.transportTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            poison(activeSession)
            drainAfterClose(future)
            return RootCommandResult(
                failure = RootCommandFailure.TRANSPORT_TIMEOUT,
                detail = "Root transport did not complete command ${commandFingerprint(command)}",
            )
        } catch (interrupted: InterruptedException) {
            poison(activeSession)
            drainAfterClose(future)
            Thread.currentThread().interrupt()
            throw CancellationException("Root command was interrupted")
                .also { it.initCause(interrupted) }
        } catch (error: Exception) {
            poison(activeSession)
            return RootCommandResult(
                failure = RootCommandFailure.SHELL_DIED,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }

        return when {
            result.failure != null -> {
                poison(activeSession)
                result
            }
            result.code == COMMAND_TIMEOUT_EXIT_CODE -> result.copy(
                failure = RootCommandFailure.COMMAND_TIMEOUT,
                detail = "Root command exceeded ${budget.commandTimeoutSeconds}s",
            )
            result.code == null -> {
                poison(activeSession)
                result.copy(
                    failure = RootCommandFailure.SHELL_DIED,
                    detail = "Root shell returned no exit status",
                )
            }
            else -> result
        }
    }

    private fun poison(expected: RootCommandSession) {
        if (session === expected) session = null
        runCatching { expected.close() }
    }

    private fun drainAfterClose(future: Future<RootCommandResult>) {
        runCatching {
            future.get(closeDrainTimeoutMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun boundedShellCommand(command: String, timeoutSeconds: Long): String {
        val quotedCommand = shellQuote(command)
        return """
            z2_timeout=${'$'}(command -v timeout 2>/dev/null) || exit $TIMEOUT_UNAVAILABLE_EXIT_CODE
            "${'$'}z2_timeout" $timeoutSeconds /system/bin/sh -c $quotedCommand
        """.trimIndent()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun commandFingerprint(command: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(command.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val COMMAND_TIMEOUT_EXIT_CODE = 124
        const val TIMEOUT_UNAVAILABLE_EXIT_CODE = 127
    }
}

private class LibsuRootCommandSession(
    private val shell: Shell,
) : RootCommandSession {
    override fun submit(command: String): Future<RootCommandResult> {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val source = shell.newJob()
            .add(command)
            .to(stdout, stderr)
            .enqueue()
        return object : Future<RootCommandResult> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
                source.cancel(mayInterruptIfRunning)

            override fun isCancelled(): Boolean = source.isCancelled

            override fun isDone(): Boolean = source.isDone

            override fun get(): RootCommandResult = source.get().toRootCommandResult()

            override fun get(timeout: Long, unit: TimeUnit): RootCommandResult =
                source.get(timeout, unit).toRootCommandResult()
        }
    }

    override fun close() {
        shell.close()
    }

    private fun Shell.Result.toRootCommandResult(): RootCommandResult =
        RootCommandResult(
            out = out.toList(),
            err = err.toList(),
            code = code,
            failure = if (code == Shell.Result.JOB_NOT_EXECUTED) {
                RootCommandFailure.SHELL_DIED
            } else {
                null
            },
            detail = if (code == Shell.Result.JOB_NOT_EXECUTED) {
                "Root shell did not execute the submitted job"
            } else {
                null
            },
        )
}

/** The only production entry point for app-process root commands. */
internal object RootCommandExecutor {
    private val delegate = BoundedRootCommandExecutor(
        sessionFactory = RootCommandSessionFactory {
            val shell = Shell.getShell()
            if (!shell.isAlive || !shell.isRoot) {
                runCatching { shell.close() }
                error("libsu did not provide a live uid-0 shell")
            }
            LibsuRootCommandSession(shell)
        },
    )

    fun execute(
        command: String,
        policy: RootCommandPolicy = RootCommandPolicy.OBSERVATION,
    ): RootCommandResult = delegate.execute(command, policy.budget)
}
