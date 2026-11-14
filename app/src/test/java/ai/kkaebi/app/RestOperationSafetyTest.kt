package ai.kkaebi.app

import ai.kkaebi.app.rest.RestControlOperation
import ai.kkaebi.app.rest.RestOperationInterrupted
import ai.kkaebi.app.rest.RestOperationSafety
import ai.kkaebi.app.rest.RestMotionRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestOperationSafetyTest {
    private fun assertRecoveredParkingCannotResume(operation: String) {
        val safety = RestOperationSafety()
        val state = RestControlOperation().begin(operation, "current")
            .enqueue("current", listOf("seatRecline" to 4.0, "blind" to 8.0))
        safety.begin()
        safety.requireSafe(true)
        assertTrue(safety.observe(false))
        assertTrue(safety.observe(true))
        val error = runCatching { safety.requireSafe(true) }.exceptionOrNull()
        assertTrue(error is RestOperationInterrupted)
        val failed = state.finish("current", false)
        assertEquals("failed", failed.phase)
        assertTrue(failed.steps.all { it.state == "failed" && it.actual == null })
        safety.finish()
        safety.begin() // Only a new explicit operation may reacquire authorization.
        safety.requireSafe(true)
        safety.finish()
    }

    @Test fun applyCannotContinueWhenPReturnsAfterObservedSafetyLoss() = assertRecoveredParkingCannotResume("apply")
    @Test fun restoreCannotContinueWhenPReturnsAfterObservedSafetyLoss() = assertRecoveredParkingCannotResume("restore")

    @Test fun ordinaryUnsupportedItemDoesNotInvalidateTheSafeSequence() {
        val safety = RestOperationSafety()
        safety.begin()
        val unsupported = runCatching { error("unsupported item") }.exceptionOrNull()
        assertFalse(unsupported is RestOperationInterrupted)
        safety.requireSafe(true)
        assertFalse(safety.observe(true))
        safety.finish()
    }

    @Test fun staleGuardItselfLatchesEvenWithoutAnAsynchronousSignalCallback() {
        val safety = RestOperationSafety()
        safety.begin()
        assertTrue(runCatching { safety.requireSafe(false) }.exceptionOrNull() is RestOperationInterrupted)
        assertTrue(runCatching { safety.requireSafe(true) }.exceptionOrNull() is RestOperationInterrupted)
        safety.finish()
    }

    @Test fun transientUnsafeStateDuringReadStopsMotionAndCannotReachTheNextItem() = runTest {
        val safety = RestOperationSafety(); safety.begin()
        var stops = 0; var nextItemWrites = 0; var published = 0
        val failure = runCatching {
            RestMotionRunner({ testScheduler.currentTime }, StandardTestDispatcher(testScheduler)).run(0.0, 2.0, 0.0, 10.0,
                guard = { safety.requireSafe(true) },
                read = { safety.observe(false); safety.observe(true); 1.0 }, move = {}, stop = { stops++ },
                observedPosition = { published++ })
            safety.requireSafe(true)
            nextItemWrites++
        }.exceptionOrNull()
        assertTrue(failure is RestOperationInterrupted)
        assertTrue(stops > 0)
        assertEquals(0, published)
        assertEquals(0, nextItemWrites)
        safety.finish()
    }
}
