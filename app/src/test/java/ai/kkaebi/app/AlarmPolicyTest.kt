package ai.kkaebi.app

import ai.kkaebi.app.alarm.*
import ai.kkaebi.app.gleo.GleoCommand
import ai.kkaebi.app.gleo.NativeInputPolicy
import org.junit.Assert.*
import org.junit.Test

class AlarmPolicyTest {
    private val scheduled=AlarmRecord("scheduled","alarm-a","req-a",7,120_000,8_000_000,"ready")
    @Test fun everySupportedMinuteAccepted() { listOf(10,15,20,25,30).forEach { assertTrue(AlarmPolicy.validRequest(it,"req-1")) } }
    @Test fun invalidDurationsRejected() { listOf(-1,0,1,9,11,31,Int.MAX_VALUE).forEach { assertFalse(AlarmPolicy.validRequest(it,"req")) } }
    @Test fun malformedRequestIdRejected() { listOf("","has spaces","<script>","a".repeat(65),"../../x").forEach { assertFalse(AlarmPolicy.validRequest(20,it)) } }
    @Test fun scheduledAndRingingAreRetained() { assertTrue(AlarmPolicy.active(scheduled)); assertTrue(AlarmPolicy.active(scheduled.copy(state="ringing"))); assertFalse(AlarmPolicy.active(scheduled.copy(state="failed"))) }
    @Test fun staleCancellationIdRejected() { assertFalse(AlarmPolicy.matches(scheduled,"alarm-b")); assertFalse(AlarmPolicy.matches(scheduled,"")); assertTrue(AlarmPolicy.matches(scheduled,"alarm-a")) }
    @Test fun remainingUsesOnlyMonotonicTime() { assertEquals(60_000,AlarmPolicy.remaining(scheduled.copy(wallDeadline=1),60_000,7)); assertEquals(60_000,AlarmPolicy.remaining(scheduled.copy(wallDeadline=Long.MAX_VALUE),60_000,7)) }
    @Test fun rebootInvalidatesDeadline() { assertFalse(AlarmPolicy.sameBoot(scheduled,8)); assertEquals(0,AlarmPolicy.remaining(scheduled,10,8)) }
    @Test fun missingBootIdentifierFailsClosed() { assertFalse(AlarmPolicy.sameBoot(scheduled.copy(bootCount=-1),-1)) }
    @Test fun earlyOrStaleBroadcastCannotRing() { assertFalse(AlarmPolicy.canFire(scheduled,"alarm-a",119_999,7)); assertFalse(AlarmPolicy.canFire(scheduled,"alarm-b",120_000,7)); assertFalse(AlarmPolicy.canFire(scheduled,"alarm-a",120_000,8)) }
    @Test fun currentDueAlarmCanRingExactlyOnce() { assertTrue(AlarmPolicy.canFire(scheduled,"alarm-a",120_000,7)); assertFalse(AlarmPolicy.canFire(scheduled.copy(state="ringing"),"alarm-a",120_000,7)) }
    @Test fun expirationNeverReturnsNegative() { assertEquals(0,AlarmPolicy.remaining(scheduled,200_000,7)) }
    @Test fun reopenRestoresOnlyFutureSameBootAlarm() {
        assertTrue(AlarmPolicy.canRestoreOnOpen(scheduled,119_999,7))
        assertFalse(AlarmPolicy.canRestoreOnOpen(scheduled,120_000,7))
        assertFalse(AlarmPolicy.canRestoreOnOpen(scheduled,119_999,8))
        assertFalse(AlarmPolicy.canRestoreOnOpen(scheduled.copy(state="ringing"),119_999,7))
    }
    @Test fun bridgeRejectsFractionalCoercedAndOverflowedControlValues() {
        assertNull(NativeInputPolicy.integer(4294967297L,1,3))
        assertNull(NativeInputPolicy.integer(4294967306L,10,30))
        assertNull(NativeInputPolicy.integer(1.9,1,3))
        assertNull(NativeInputPolicy.integer("1",1,3))
        assertNull(NativeInputPolicy.integer(Double.NaN,1,3))
        assertEquals(1,NativeInputPolicy.integer(1,1,3))
        assertNull(NativeInputPolicy.temperature("22"))
        assertNull(NativeInputPolicy.temperature(Double.POSITIVE_INFINITY))
    }
    @Test fun gleoAllowsPreparationOnly() { assertEquals("rest-mode",GleoCommand.validate(GleoCommand.ACTION_PREPARE,"rest-mode")); assertNull(GleoCommand.validate(GleoCommand.ACTION_PREPARE,"applyRest")); assertNull(GleoCommand.validate("android.intent.action.MAIN","rest-mode")); assertNull(GleoCommand.validate(GleoCommand.ACTION_PREPARE,"rest-mode;applyRest")) }
}
