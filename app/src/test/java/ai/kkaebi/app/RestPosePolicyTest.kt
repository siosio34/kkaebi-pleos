package ai.kkaebi.app

import ai.kkaebi.app.rest.RestControlArea
import ai.kkaebi.app.rest.RestPosePolicy
import ai.kkaebi.app.rest.RestSafety
import org.junit.Assert.*
import org.junit.Test

class RestPosePolicyTest {
    @Test fun zeroSuccessfulReadsCannotReplaceThePreviouslySavedPose() {
        assertFalse(RestPosePolicy.shouldReplaceSavedPose(0))
        assertFalse(RestPosePolicy.shouldReplaceSavedPose(-1))
        assertTrue(RestPosePolicy.shouldReplaceSavedPose(1))
        assertTrue(RestPosePolicy.shouldReplaceSavedPose(2))
        assertFalse(RestPosePolicy.shouldReplaceSavedPose(3))
    }

    @Test fun captureRequiresFreshSafeIdleSessionAndNeverCompetesWithRestore() {
        assertTrue(RestPosePolicy.canCapture(true, true, false, "idle", false, false))
        assertFalse(RestPosePolicy.canCapture(false, true, false, "idle", false, false))
        assertFalse(RestPosePolicy.canCapture(true, false, false, "idle", false, false))
        assertFalse(RestPosePolicy.canCapture(true, true, true, "idle", false, false))
        assertFalse(RestPosePolicy.canCapture(true, true, false, "scheduled", false, false))
        assertFalse(RestPosePolicy.canCapture(true, true, false, "ringing", false, false))
        assertFalse(RestPosePolicy.canCapture(true, true, false, "idle", true, false))
        assertFalse(RestPosePolicy.canCapture(true, true, false, "idle", false, true))
    }

    @Test fun savedPoseMustBeAnActualFiniteAlignedReadWithinFreshSupportedArea() {
        val capability = RestSafety.capability("READ_WRITE", true, 0.0, 10.0, 1.0, 0.0, 1000)
            .copy(area = RestControlArea("seat", listOf("ROW_1_LEFT")))
        assertTrue(RestPosePolicy.valid(5.0, capability, 2000))
        assertFalse(RestPosePolicy.valid(Double.NaN, capability, 2000))
        assertFalse(RestPosePolicy.valid(11.0, capability, 2000))
        assertFalse(RestPosePolicy.valid(5.5, capability, 2000))
        assertFalse(RestPosePolicy.valid(5.0, capability.copy(area = null), 2000))
        assertFalse(RestPosePolicy.valid(5.0, capability.copy(state = "unknown"), 2000))
        assertFalse(RestPosePolicy.valid(5.0, capability.copy(access = "READ"), 2000))
        assertFalse(RestPosePolicy.valid(5.0, capability, 999))
        assertFalse(RestPosePolicy.valid(5.0, capability, 61001))
    }
}
