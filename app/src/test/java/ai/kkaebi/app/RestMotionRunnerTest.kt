package ai.kkaebi.app

import ai.kkaebi.app.rest.RestMotionRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestMotionRunnerTest {
    @Test fun reachesThenStopsAndConfirmsStablePosition() = runTest {
        var reads = 0; var stops = 0; var confirmed: Double? = null
        RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0, 2.0, 0.0, 10.0,
            guard = {}, read = { if(reads++ == 0) 1.0 else 2.0 }, move = { assertEquals(1,it) }, stop = { stops++ },
            confirmedPosition = { confirmed = it })
        assertTrue(stops >= 2); assertEquals(2.0, confirmed!!, 0.0)
    }
    @Test fun oppositePositionStopsAndFails() = runTest {
        var stops = 0; var confirmed = false
        val failure = runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(5.0, 7.0, 0.0, 10.0,
            {}, {4.0}, {}, { stops++ }, confirmedPosition = { confirmed = true }) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("opposite")); assertTrue(stops > 0); assertFalse(confirmed)
    }
    @Test fun overshootStopsWithoutReversing() = runTest {
        val directions=mutableListOf<Int>(); var stops=0
        val failure=runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(5.0,6.0,0.0,10.0,
            {}, {7.0}, {directions+=it}, {stops++}) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("overshot")); assertEquals(listOf(1),directions); assertTrue(stops>0)
    }
    @Test fun noProgressStopsWithinBound() = runTest {
        var stops=0
        assertTrue(runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,5.0,0.0,10.0,
            {}, {0.0}, {}, {stops++}) }.isFailure)
        assertTrue(testScheduler.currentTime < 8_000); assertTrue(stops>0)
    }
    @Test fun lostParkingGuardStillSendsStop() = runTest {
        var guards=0; var stops=0
        assertTrue(runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,5.0,0.0,10.0,
            {check(++guards < 2)}, {1.0}, {}, {stops++}) }.isFailure)
        assertTrue(stops>0)
    }
    @Test fun cancellationWhileMoveCallbackPendingStopsAndDoesNotConfirmPosition() = runTest {
        var stops=0; var confirmed=false
        val job=launch { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,5.0,0.0,10.0,
            {}, {1.0}, {awaitCancellation()}, {stops++}, confirmedPosition={confirmed=true}) }
        runCurrent(); job.cancelAndJoin()
        assertTrue(stops>0); assertFalse(confirmed)
    }
    @Test fun stopFailureIsDistinctAndNeverConfirmed() = runTest {
        var stopped:Boolean?=null; var confirmed=false
        val failure=runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,1.0,0.0,10.0,
            {}, {1.0}, {}, {error("STOP denied")}, stopped={stopped=it}, confirmedPosition={confirmed=true}) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("STOP")); assertEquals(false,stopped); assertFalse(confirmed)
    }
    @Test fun unstableAfterStopDoesNotConfirmJournal() = runTest {
        var reads=0; var confirmed=false
        assertTrue(runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,1.0,0.0,10.0,
            {}, {if(reads++<2)1.0 else 2.0}, {}, {}, confirmedPosition={confirmed=true}) }.isFailure)
        assertFalse(confirmed)
    }
    @Test fun partialPositionAfterCancellationCannotBeReclassifiedAsAppOwned() = runTest {
        var confirmed=false; var entered=false; var stops=0
        val job=launch { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0,5.0,0.0,10.0,
            {}, {entered=true;1.0}, {}, {stops++}, confirmedPosition={confirmed=true}) }
        runCurrent(); assertTrue(entered)
        job.cancelAndJoin(); assertTrue(stops>0); assertFalse(confirmed)
    }

    @Test fun reportsOnlySdkReadPositionsBeforeStableFinalConfirmation() = runTest {
        var reads = 0
        val events = mutableListOf<String>()
        RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0, 2.0, 0.0, 10.0,
            {}, { if(reads++ == 0) 1.0 else 2.0 }, { events += "ack" }, {},
            confirmedPosition = { events += "confirmed:$it" }, observedPosition = { events += "observed:$it" })
        assertEquals(listOf("ack", "observed:1.0", "observed:2.0", "confirmed:2.0"), events)
    }

    @Test fun invalidReadAndLostGuardDoNotPublishPositionProgress() = runTest {
        val observed = mutableListOf<Double>()
        assertTrue(runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0, 2.0, 0.0, 10.0,
            {}, {3.0}, {}, {}, observedPosition = { observed += it }) }.isFailure)
        var parked = true
        assertTrue(runCatching { RestMotionRunner({testScheduler.currentTime}, StandardTestDispatcher(testScheduler)).run(0.0, 2.0, 0.0, 10.0,
            { check(parked) }, { parked = false; 1.0 }, {}, {}, observedPosition = { observed += it }) }.isFailure)
        assertTrue(observed.isEmpty())
    }
}
