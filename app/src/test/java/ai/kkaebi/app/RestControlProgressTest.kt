package ai.kkaebi.app

import ai.kkaebi.app.rest.RestControlOperation
import org.junit.Assert.*
import org.junit.Test

class RestControlProgressTest {
    private fun queued(operation: String = "apply") = RestControlOperation().begin(operation, "one")
        .enqueue("one", listOf("temperature" to 22.0, "seatVentilation" to 1.0))

    @Test fun queuesOnlySelectedItemsInSdkOrderAndOldSnapshotStaysUnchanged() {
        val start = queued()
        val next = start.progress("one", "temperature", "checking")
        assertEquals(listOf("temperature", "seatVentilation"), start.steps.map { it.key })
        assertEquals(listOf("queued", "queued"), start.steps.map { it.state })
        assertEquals(listOf("checking", "queued"), next.steps.map { it.state })
        assertEquals(22.0, next.steps[0].requested!!, 0.0)
    }

    @Test fun cannotShowSuccessBeforeWriteAndVerificationOrWithoutFiniteReadback() {
        var state = queued().progress("one", "temperature", "checking")
        assertEquals(state, state.progress("one", "temperature", "applied", 22.0))
        state = state.progress("one", "temperature", "writing")
        assertEquals(state, state.progress("one", "temperature", "applied", 22.0))
        state = state.progress("one", "temperature", "verifying")
        assertEquals(state, state.progress("one", "temperature", "applied"))
        assertEquals(state, state.progress("one", "temperature", "applied", Double.NaN))
        val confirmed = state.progress("one", "temperature", "applied", 22.0)
        assertEquals("applied", confirmed.steps[0].state)
        assertEquals(22.0, confirmed.steps[0].actual!!, 0.0)
    }

    @Test fun staleProgressCannotChangeAnotherOperationOrTerminalItem() {
        val failed = queued().progress("one", "temperature", "failed", message = "timeout")
        assertEquals(failed, failed.progress("old", "seatVentilation", "checking"))
        assertEquals(failed, failed.progress("one", "temperature", "checking"))
        assertEquals(failed, failed.progress("one", "unselected", "checking"))
    }

    @Test fun cancellationTerminatesQueuedAndActiveRowsWithoutSuccess() {
        val interrupted = queued().progress("one", "temperature", "checking").finish("one", false)
        assertEquals("failed", interrupted.phase)
        assertEquals(listOf("failed", "failed"), interrupted.steps.map { it.state })
        assertEquals(interrupted, interrupted.progress("one", "temperature", "applied", 22.0))
    }

    @Test fun partialFailureKeepsConfirmedReadbackAndCannotFinishSuccessful() {
        val partial = queued().progress("one", "temperature", "checking")
            .progress("one", "temperature", "writing").progress("one", "temperature", "verifying")
            .progress("one", "temperature", "applied", 22.0)
            .progress("one", "seatVentilation", "failed", message = "permission denied")
            .finish("one", true)
        assertEquals("failed", partial.phase)
        assertEquals(listOf("applied", "failed"), partial.steps.map { it.state })
    }

    @Test fun restoreAllowsManualPreservationWithoutAnotherWrite() {
        val manual = queued("restore").progress("one", "temperature", "checking")
            .progress("one", "temperature", "manualPreserved", 24.0)
        assertEquals("manualPreserved", manual.steps[0].state)
        assertEquals(24.0, manual.steps[0].actual!!, 0.0)
        val applying = queued().progress("one", "temperature", "checking")
        assertEquals(applying, applying.progress("one", "temperature", "manualPreserved", 24.0))
    }

    @Test fun restoreReadbackCannotBeReportedAsApplyAndNewOperationClearsSteps() {
        val verifying = queued("restore").progress("one", "temperature", "checking")
            .progress("one", "temperature", "writing").progress("one", "temperature", "verifying")
        assertEquals(verifying, verifying.progress("one", "temperature", "applied", 22.0))
        val restored = verifying.progress("one", "temperature", "restored", 22.0)
        assertEquals("restored", restored.steps[0].state)
        assertTrue(restored.finish("one", false).begin("apply", "two").steps.isEmpty())
    }

    @Test fun cannotFinishWhileAnySdkStepRemainsUnconfirmed() {
        val premature = queued().progress("one", "temperature", "checking").finish("one", true)
        assertEquals("failed", premature.phase)
        assertTrue(premature.steps.all { it.state == "failed" })
    }

    @Test fun successfulBeforeReadIsImmutableAndNeverBecomesVerifiedActual() {
        val checking = queued().progress("one", "temperature", "checking")
        val observed = checking.observedBefore("one", "temperature", 24.0)
        assertNull(checking.steps[0].before)
        assertEquals(24.0, observed.steps[0].before!!, 0.0)
        assertEquals(22.0, observed.steps[0].requested!!, 0.0)
        assertNull(observed.steps[0].actual)
        val verifying = observed.progress("one", "temperature", "writing").progress("one", "temperature", "verifying")
        assertEquals(24.0, verifying.steps[0].before!!, 0.0)
        assertNull(verifying.steps[0].actual)
        val applied = verifying.progress("one", "temperature", "applied", 22.0)
        assertEquals(24.0, applied.steps[0].before!!, 0.0)
        assertEquals(22.0, applied.steps[0].actual!!, 0.0)
    }

    @Test fun staleRepeatedAndInvalidBeforeReadsCannotRewriteTheTimeline() {
        val initial = queued()
        assertEquals(initial, initial.observedBefore("one", "temperature", 24.0))
        val checking = initial.progress("one", "temperature", "checking")
        assertEquals(checking, checking.observedBefore("old", "temperature", 24.0))
        assertEquals(checking, checking.observedBefore("one", "temperature", Double.NaN))
        val observed = checking.observedBefore("one", "temperature", 24.0)
        assertEquals(observed, observed.observedBefore("one", "temperature", 25.0))
        val terminal = observed.finish("one", false)
        assertEquals(terminal, terminal.observedBefore("one", "temperature", 26.0))
    }

    @Test fun restoreReportsCurrentBeforeAndOriginalTargetSeparately() {
        val state = queued("restore").progress("one", "temperature", "checking")
            .observedBefore("one", "temperature", 20.0)
        assertEquals(20.0, state.steps[0].before!!, 0.0)
        assertEquals(22.0, state.steps[0].requested!!, 0.0)
        assertNull(state.steps[0].actual)
    }

    @Test fun moveAcknowledgementCannotCreatePositionOrCompletionAndMeasuredTargetIsNotSuccess() {
        val writing = RestControlOperation().begin("apply", "move").enqueue("move", listOf("seatRecline" to 8.0))
            .progress("move", "seatRecline", "checking").progress("move", "seatRecline", "writing")
        assertNull(writing.steps[0].observed)
        assertNull(writing.steps[0].positionProgress)
        val middle = writing.observedPosition("move", "seatRecline", 2.0, 5.0, 100)
        assertEquals(0.5, middle.steps[0].positionProgress!!, 0.0)
        assertEquals("sdk-position", middle.steps[0].progressSource)
        val target = middle.observedPosition("move", "seatRecline", 2.0, 8.0, 200)
        assertEquals(1.0, target.steps[0].positionProgress!!, 0.0)
        assertEquals("writing", target.steps[0].state)
        assertNull(target.steps[0].actual)
        assertEquals("failed", target.finish("move", true).phase)
    }

    @Test fun reverseTravelUsesMeasuredDistanceAndRejectsStaleInvalidOrUnrelatedSamples() {
        val writing = RestControlOperation().begin("restore", "move").enqueue("move", listOf("blind" to 2.0))
            .progress("move", "blind", "checking").progress("move", "blind", "writing")
            .observedPosition("move", "blind", 8.0, 5.0, 200)
        assertEquals(0.5, writing.steps[0].positionProgress!!, 0.0)
        assertEquals(writing, writing.observedPosition("old", "blind", 8.0, 3.0, 300))
        assertEquals(writing, writing.observedPosition("move", "blind", 8.0, 3.0, 100))
        assertEquals(writing, writing.observedPosition("move", "blind", 8.0, 1.0, 300))
        assertEquals(writing, writing.observedPosition("move", "blind", 8.0, Double.NaN, 300))
        assertEquals(writing, writing.observedPosition("move", "temperature", 8.0, 3.0, 300))
        val terminal = writing.finish("move", false)
        assertEquals(terminal, terminal.observedPosition("move", "blind", 8.0, 2.0, 400))
    }

    @Test fun positionAlreadyAtTargetStillRequiresFinalVerification() {
        val reading = RestControlOperation().begin("apply", "move").enqueue("move", listOf("blind" to 2.0))
            .progress("move", "blind", "checking").observedPosition("move", "blind", 2.0, 2.0, 100)
        assertEquals(1.0, reading.steps[0].positionProgress!!, 0.0)
        assertNull(reading.steps[0].actual)
        assertEquals("checking", reading.steps[0].state)
    }
}
