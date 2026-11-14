package ai.kkaebi.app

import ai.kkaebi.app.rest.RestSafety
import org.junit.Assert.*
import org.junit.Test

class RestSafetyTest {
    private val now = 20_000L
    @Test fun freshParkedAndUnrestrictedPasses() { assertTrue(RestSafety.parked("P", 0.0, now, now, now, false)) }
    @Test fun driveReverseNeutralAndUnknownNeverPass() {
        listOf("D", "R", "N", "UNKNOWN").forEach { assertFalse(RestSafety.parked(it, 0.0, now, now, now, false)) }
    }
    @Test fun movingCarNeverPassesEvenInPark() {
        listOf(0.11, -0.11, 1.0, 100.0).forEach { assertFalse(RestSafety.parked("P", it, now, now, now, false)) }
    }
    @Test fun missingAndNonFiniteSpeedFailClosed() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFalse(RestSafety.parked("P", it, now, now, now, false))
        }
    }
    @Test fun staleGearCannotBeRefreshedByNewSpeed() { assertFalse(RestSafety.parked("P", 0.0, now - 5000, now, now, false)) }
    @Test fun staleSpeedCannotBeRefreshedByNewGear() { assertFalse(RestSafety.parked("P", 0.0, now, now - 5000, now, false)) }
    @Test fun absentFutureAndExpiredTimestampsFailClosed() {
        listOf(0L, now + 1, now - 5000).forEach { assertFalse(RestSafety.parked("P", 0.0, it, it, now, false)) }
    }
    @Test fun platformRestrictionsOverridePark() { assertFalse(RestSafety.parked("P", 0.0, now, now, now, true)) }
    @Test fun plansHaveStrictBounds() {
        assertTrue(RestSafety.validPlan(22.0, 1))
        assertFalse(RestSafety.validPlan(Double.NaN, 1)); assertFalse(RestSafety.validPlan(17.0, 1))
        assertFalse(RestSafety.validPlan(27.0, 1)); assertFalse(RestSafety.validPlan(22.0, 0)); assertFalse(RestSafety.validPlan(22.0, 4))
    }
    @Test fun manualChangesAreNeverRestoredOver() {
        assertTrue(RestSafety.shouldRestore(22.0, 22.0))
        assertFalse(RestSafety.shouldRestore(24.0, 22.0)); assertFalse(RestSafety.shouldRestore(Double.NaN, 22.0))
    }
    @Test fun alarmRejectsExpiredAndExcessiveDurations() {
        assertTrue(RestSafety.validAlarmDeadline(now + 20 * 60_000, now))
        assertFalse(RestSafety.validAlarmDeadline(now, now))
        assertFalse(RestSafety.validAlarmDeadline(now - 1, now))
        assertFalse(RestSafety.validAlarmDeadline(now + 25 * 60_000 + 1, now))
        assertFalse(RestSafety.validAlarmDeadline(Long.MAX_VALUE, now))
    }
}
