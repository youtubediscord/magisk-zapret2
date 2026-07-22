package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keeps an irreversible terminal command and its in-memory success transition indivisible by cancellation. */
internal object CancellationSafeTerminalCommit {
    sealed class Outcome<out T> {
        data class Committed<T>(val commandResult: T) : Outcome<T>()
        data class Failed<T>(val commandResult: T) : Outcome<T>()
    }

    suspend fun <T> run(
        command: suspend () -> T,
        commandSucceeded: (T) -> Boolean,
        onCommitted: () -> Unit,
    ): Outcome<T> {
        var recordedOutcome: Outcome<T>? = null
        return try {
            withContext(NonCancellable) {
                val commandResult = command()
                val outcome = if (commandSucceeded(commandResult)) {
                    onCommitted()
                    Outcome.Committed(commandResult)
                } else {
                    Outcome.Failed(commandResult)
                }
                recordedOutcome = outcome
                outcome
            }
        } catch (cancelled: CancellationException) {
            recordedOutcome?.takeIf { it is Outcome.Committed } ?: throw cancelled
        }
    }
}
