package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Holds a published cross-process lease for the complete admission + mutation transaction. */
internal object CancellationSafeMutationLease {

    suspend fun <L : Any, T> run(
        acquire: suspend () -> L,
        release: suspend (L) -> Unit,
        block: suspend (L) -> T,
    ): T {
        var lease: L? = null
        try {
            withContext(NonCancellable) { lease = acquire() }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            lease?.let { published ->
                try {
                    withContext(NonCancellable) { release(published) }
                } catch (releaseError: Throwable) {
                    cancelled.addSuppressed(releaseError)
                }
            }
            throw cancelled
        }

        val owned = requireNotNull(lease)
        val blockResult = try {
            Result.success(block(owned))
        } catch (error: Throwable) {
            Result.failure(error)
        }
        try {
            withContext(NonCancellable) { release(owned) }
        } catch (releaseError: Throwable) {
            val original = blockResult.exceptionOrNull()
            if (original != null) {
                original.addSuppressed(releaseError)
            } else {
                throw releaseError
            }
        }
        val value = blockResult.getOrThrow()
        currentCoroutineContext().ensureActive()
        return value
    }
}
