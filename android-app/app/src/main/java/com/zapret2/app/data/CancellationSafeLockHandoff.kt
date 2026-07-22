package com.zapret2.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Prevents cancellation from orphaning ownership between lock publication and caller assignment. */
internal object CancellationSafeLockHandoff {
    suspend fun <T : Any> acquire(
        publish: suspend () -> T,
        releaseIfCancelled: suspend (T) -> Unit,
    ): T {
        var published: T? = null
        try {
            withContext(NonCancellable) { published = publish() }
            currentCoroutineContext().ensureActive()
            return requireNotNull(published)
        } catch (cancelled: CancellationException) {
            published?.let { ownership ->
                withContext(NonCancellable) { releaseIfCancelled(ownership) }
            }
            throw cancelled
        }
    }
}
