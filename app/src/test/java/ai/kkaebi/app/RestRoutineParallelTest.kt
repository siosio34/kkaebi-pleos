package ai.kkaebi.app

import ai.kkaebi.app.rest.RestRoutineParallel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestRoutineParallelTest {
    @Test fun narrationAndHardwareOverlapWithoutWaitingForVoiceCompletion() = runTest {
        var hardwareAt = -1L; var voiceDone = false
        RestRoutineParallel.run({ delay(8000); voiceDone = true }, { fail() }) {
            hardwareAt = testScheduler.currentTime; delay(100); assertFalse(voiceDone)
        }
        assertEquals(0L, hardwareAt); assertTrue(voiceDone); assertEquals(8000L, testScheduler.currentTime)
    }
    @Test fun voiceFailureDoesNotBlockHardware() = runTest {
        var failed = false; var hardware = false
        RestRoutineParallel.run({ error("voice unavailable") }, { failed = true }) { delay(1); hardware = true }
        assertTrue(failed); assertTrue(hardware)
    }
    @Test fun parentCancellationStopsBothChildrenBeforeReturning() = runTest {
        var voiceStopped = false; var hardwareStopped = false
        val job = launch {
            RestRoutineParallel.run({ try { awaitCancellation() } finally { voiceStopped = true } }, {}) {
                try { awaitCancellation() } finally { hardwareStopped = true }
            }
        }
        runCurrent(); job.cancelAndJoin()
        assertTrue(voiceStopped); assertTrue(hardwareStopped)
    }
}
