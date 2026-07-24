package com.zapret2.app.viewmodel

import com.zapret2.app.data.ModuleMutationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INITIAL_LIFECYCLE_SETTLEMENT_DELAY_MILLIS = 1_000L
private const val MAX_LIFECYCLE_SETTLEMENT_BACKOFF_SHIFT = 3

internal fun lifecycleSettlementDelayMillis(attempt: Int): Long =
    INITIAL_LIFECYCLE_SETTLEMENT_DELAY_MILLIS shl
        attempt.coerceIn(0, MAX_LIFECYCLE_SETTLEMENT_BACKOFF_SHIFT)

/**
 * Re-observes a transient external lifecycle owner until the typed module state settles.
 *
 * Each observation remains a bounded, read-only status request. The observer has one job,
 * backs off between requests, and is cancelled when its screen leaves the started lifecycle.
 */
internal class LifecycleSettlementObserver(
    private val scope: CoroutineScope,
    private val observe: suspend () -> ModuleMutationState?,
    private val delayMillis: (attempt: Int) -> Long = ::lifecycleSettlementDelayMillis,
) {
    private var observationJob: Job? = null

    fun ensureObserving() {
        if (observationJob?.isActive == true) return

        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            var attempt = 0
            var observedState = ModuleMutationState.IN_PROGRESS
            while (observedState == ModuleMutationState.IN_PROGRESS) {
                delay(delayMillis(attempt))
                observedState = observe() ?: ModuleMutationState.IDLE
                attempt += 1
            }
        }
        observationJob = newJob
        newJob.invokeOnCompletion {
            if (observationJob === newJob) observationJob = null
        }
        newJob.start()
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
    }
}
