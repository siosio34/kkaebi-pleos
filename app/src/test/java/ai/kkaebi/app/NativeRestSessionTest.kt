package ai.kkaebi.app

import ai.kkaebi.app.ui.nativeapp.NativeConfirmationSequence
import ai.kkaebi.app.ui.nativeapp.NativeRestSession
import ai.kkaebi.app.ui.nativeapp.NativeParkingGate
import ai.kkaebi.app.ui.nativeapp.nativeBackDestination
import ai.kkaebi.app.ui.nativeapp.parseNativeCommand
import org.junit.Assert.*
import org.junit.Test

class NativeRestSessionTest {
    @Test fun busyBackDelegatesToAndroidInsteadOfAbsorbingTheGesture() {
        assertNull(nativeBackDestination("SUMMARY", true, false, "PREPARE", "SUMMARY"))
        assertNull(nativeBackDestination("SETTINGS", true, false, "PREPARE", "SUMMARY"))
    }
    @Test fun activeAlarmTopLevelBackDelegatesToAndroid() {
        assertNull(nativeBackDestination("REST", false, true, "PREPARE", "SUMMARY"))
        assertNull(nativeBackDestination("RECOVERY", false, true, "PREPARE", "SUMMARY"))
        assertNull(nativeBackDestination("HOME", false, false, "PREPARE", "SUMMARY"))
    }
    @Test fun settingsBackReturnsItsActualParent() {
        assertEquals("PREPARE", nativeBackDestination("SETTINGS", false, false, "PREPARE", "HOME"))
        assertEquals("PLAN", nativeBackDestination("SETTINGS", false, false, "PLAN", "HOME"))
    }
    @Test fun journalBackReturnsItsActualParent() {
        assertEquals("SUMMARY", nativeBackDestination("JOURNAL", false, false, "PLAN", "SUMMARY"))
    }
    @Test fun otherBackDestinationsPreserveActiveAlarmAndOtherwiseReturnHome() {
        assertEquals("REST", nativeBackDestination("SUMMARY", false, true, "HOME", "HOME"))
        assertEquals("HOME", nativeBackDestination("PREPARE", false, false, "HOME", "HOME"))
    }

    @Test fun parkingReadsActualClockWhenSignalIsNewerThanPreviousUiTick() {
        var clock = 1000L
        val gate = NativeParkingGate { clock }
        assertTrue(gate.permits("P", 0.0, 900, 900, false))
        // The display's previous tick is still 1000; this new snapshot arrived at 1500.
        clock = 1600L
        assertTrue(gate.permits("P", 0.0, 1500, 1500, false))
    }
    @Test fun parkingStillRejectsGenuinelyFutureSignal() {
        val gate = NativeParkingGate { 1600L }
        assertFalse(gate.permits("P", 0.0, 1700, 1500, false))
        assertFalse(gate.permits("P", 0.0, 1500, 1700, false))
    }
    @Test fun parkingExpiresWithoutANewSnapshot() {
        var clock = 1600L
        val gate = NativeParkingGate { clock }
        assertTrue(gate.permits("P", 0.0, 1500, 1500, false))
        clock = 6500L
        assertFalse(gate.permits("P", 0.0, 1500, 1500, false))
    }

    private fun ack(session: NativeRestSession, request: String = "request-1", parked: Boolean = true,
                    consent: Boolean = true, place: Boolean = true, pending: Boolean = false,
                    state: String = "scheduled", id: String = "alarm-1") =
        session.reconcile(state, id, request, parked, place, consent, pending)

    @Test fun mismatchedResponseCannotApplyOrConsumeNewRequest() {
        val s = NativeRestSession(); s.begin("request-1")
        assertFalse(ack(s, request = "old-request").accept)
        assertEquals("request-1", s.pendingRequest)
        assertTrue(ack(s).allowEnvironment)
    }
    @Test fun matchingScheduledAckAppliesAtMostOnce() {
        val s = NativeRestSession(); s.begin("request-1")
        assertTrue(ack(s).allowEnvironment)
        repeat(3) { assertFalse(ack(s).allowEnvironment) }
    }
    @Test fun restoredReservationAfterRotationNeverReapplies() {
        val recreated = NativeRestSession()
        val result = ack(recreated)
        assertTrue(result.accept)
        assertFalse(result.matchingRequest)
        assertFalse(result.allowEnvironment)
    }
    @Test fun lostParkingAtAckCannotBecomeLaterConsent() {
        val s = NativeRestSession(); s.begin("request-1")
        assertFalse(ack(s, parked = false).allowEnvironment)
        assertFalse(ack(s, parked = true).allowEnvironment)
    }
    @Test fun newAlarmWithPendingRestoreNeverChangesEnvironment() {
        val s = NativeRestSession(); s.begin("request-1")
        val result = ack(s, pending = true)
        assertTrue(result.accept)
        assertTrue(result.matchingRequest)
        assertFalse(result.allowEnvironment)
    }
    @Test fun placeAndEnvironmentConsentAreBothRequired() {
        val a = NativeRestSession(); a.begin("request-1")
        assertFalse(ack(a, place = false).allowEnvironment)
        val b = NativeRestSession(); b.begin("request-1")
        assertFalse(ack(b, consent = false).allowEnvironment)
    }
    @Test fun alarmStopHasNoParkingGateAndStaleScheduledDoesNotClearIt() {
        val s = NativeRestSession()
        assertTrue(s.requestStop("ringing", "alarm-1"))
        ack(s, parked = false)
        assertEquals("alarm-1", s.stopRequested)
        val stopped = ack(s, parked = false, state = "idle", id = "")
        assertTrue(stopped.stopped)
        assertNull(s.stopRequested)
    }
    @Test fun timeoutReconcilesLateReservationWithoutApplying() {
        val s = NativeRestSession(); s.begin("request-1"); s.expireRequest()
        assertTrue(ack(s).accept)
        assertFalse(ack(s).allowEnvironment)
    }
    @Test fun stoppingExistingAlarmWhileNewRequestWaitsRevokesPendingConsent() {
        val s = NativeRestSession(); s.begin("request-1")
        assertTrue(s.requestStop("scheduled", "existing-alarm"))
        assertNull(s.pendingRequest)
        assertFalse(ack(s).allowEnvironment)
        assertTrue(ack(s, state = "idle", id = "").stopped)
    }
    @Test fun failedRequestCanBeRetriedAndRepeatedFailureDoesNotMatch() {
        val s = NativeRestSession(); s.begin("request-1")
        assertTrue(ack(s, state = "failed").matchingRequest)
        assertFalse(ack(s, state = "failed").matchingRequest)
        assertTrue(s.begin("request-2"))
        assertTrue(ack(s, request = "request-2", id = "alarm-2").allowEnvironment)
    }
    @Test fun idleOrEmptyIdCannotAcknowledgeAReservation() {
        val s = NativeRestSession(); s.begin("request-1")
        assertFalse(ack(s, state = "idle").accept)
        assertFalse(ack(s, id = "").accept)
        assertEquals("request-1", s.pendingRequest)
    }
    @Test fun malformedOrConcurrentRequestIsRejected() {
        val s = NativeRestSession()
        assertFalse(s.begin("request with spaces"))
        assertTrue(s.begin("request-1"))
        assertFalse(s.begin("request-2"))
    }
    @Test fun mergedFastCompletionsStillGetOrderedAccentsWithoutRepeats() {
        val q = NativeConfirmationSequence()
        val steps = listOf(Triple("op1", "temperature", "applied"), Triple("op1", "fan", "applied"))
        assertEquals(listOf("temperature", "fan"), q.observe("op1", steps, true))
        assertEquals(emptyList<String>(), q.observe("op1", steps, true))
    }
    @Test fun failuresAndManualPreservationNeverGetSuccessAccents() {
        val q = NativeConfirmationSequence()
        assertTrue(q.observe("op1", listOf(Triple("op1", "fan", "failed"), Triple("op1", "temperature", "manualPreserved")), true).isEmpty())
    }
    @Test fun staleOperationCannotTriggerNewAccents() {
        val q = NativeConfirmationSequence()
        q.observe("old", listOf(Triple("old", "fan", "applied")), true)
        q.observe("new", listOf(Triple("new", "fan", "restored")), true)
        assertTrue(q.observe("old", listOf(Triple("old", "temperature", "applied")), true).isEmpty())
        assertTrue(q.observe("new", listOf(Triple("old", "temperature", "applied")), true).isEmpty())
    }
    @Test fun disabledMotionConsumesCompletionsWithoutReplayingAfterParking() {
        val q = NativeConfirmationSequence()
        val step = listOf(Triple("op1", "fan", "applied"))
        assertTrue(q.observe("op1", step, false).isEmpty())
        assertTrue(q.observe("op1", step, true).isEmpty())
    }
    @Test fun commandNegationAndCancellationPrecedeAffirmativeMatches() {
        assertEquals("none", parseNativeCommand("안 졸려"))
        assertEquals("none", parseNativeCommand("깨비 휴식 하지마"))
        assertEquals("quiet", parseNativeCommand("깨비 졸음모드 취소"))
        assertEquals("ack", parseNativeCommand("깨비 알람 중지"))
        assertEquals("drowsy-mode", parseNativeCommand("깨비 졸음모드"))
        assertEquals("rest-mode", parseNativeCommand("깨비 휴식"))
    }
}
