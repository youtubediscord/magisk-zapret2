package com.zapret2.app.viewmodel

import com.zapret2.app.data.ModuleMutationState
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleSettlementObserverTest {

    @Test
    fun transientLifecycleState_isObservedUntilItSettles() = runTest {
        val states = listOf(
            ModuleMutationState.IN_PROGRESS,
            ModuleMutationState.IN_PROGRESS,
            ModuleMutationState.IDLE,
        )
        var calls = 0
        val observer = LifecycleSettlementObserver(
            scope = this,
            observe = { states[calls++] },
            delayMillis = { 0L },
        )

        observer.ensureObserving()
        advanceUntilIdle()

        assertEquals(3, calls)
    }

    @Test
    fun duplicateRequests_shareOneSettlementObservation() = runTest {
        var calls = 0
        val observer = LifecycleSettlementObserver(
            scope = this,
            observe = {
                calls += 1
                ModuleMutationState.IDLE
            },
            delayMillis = { 0L },
        )

        observer.ensureObserving()
        observer.ensureObserving()
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun leavingTheScreen_cancelsPendingObservation() = runTest {
        var calls = 0
        val observer = LifecycleSettlementObserver(
            scope = this,
            observe = {
                calls += 1
                ModuleMutationState.IN_PROGRESS
            },
            delayMillis = { 1_000L },
        )

        observer.ensureObserving()
        observer.stop()
        advanceUntilIdle()

        assertEquals(0, calls)
    }

    @Test
    fun settlementBackoff_isBounded() {
        assertEquals(1_000L, lifecycleSettlementDelayMillis(0))
        assertEquals(2_000L, lifecycleSettlementDelayMillis(1))
        assertEquals(4_000L, lifecycleSettlementDelayMillis(2))
        assertEquals(8_000L, lifecycleSettlementDelayMillis(3))
        assertEquals(8_000L, lifecycleSettlementDelayMillis(100))
    }

    @Test
    fun controlScreen_reobservesTransientLifecycleAndStopsOffScreen() {
        val source = productionFile("ControlViewModel.kt").readText()
        val transientBranch = source
            .substringAfter("if (lifecycleMutationState != ModuleMutationState.IDLE)")
            .substringBefore("val netStats =")
        val screenStop = source
            .substringAfter("fun onScreenStopped()")
            .substringBefore("fun clearMessage()")

        assertTrue(transientBranch.contains("lifecycleSettlementObserver.ensureObserving()"))
        assertTrue(transientBranch.contains("networkType = currentNetworkType"))
        assertTrue(screenStop.contains("lifecycleSettlementObserver.stop()"))
    }

    private fun productionFile(fileName: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(
                current,
                "android-app/app/src/main/java/com/zapret2/app/viewmodel/$fileName",
            )
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to find $fileName")
    }
}
