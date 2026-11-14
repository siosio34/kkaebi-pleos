package ai.kkaebi.app

import ai.kkaebi.app.rest.*
import org.junit.Assert.*
import org.junit.Test

class RestRoutineAndAreaTest {
    private fun decision(state:String="idle", consent:Boolean=true, attempted:Boolean=false, phase:String="resting", foreground:Boolean=true, parked:Boolean=true, busy:Boolean=false, owner:String="a", current:String="a") =
        RestRoutinePolicy.wakeDecision(owner,current,state,consent,attempted,phase,foreground,parked,busy)
    @Test fun ringingNeverMoves() { assertEquals("none",decision(state="ringing")) }
    @Test fun notificationAckWithoutWakeConsentNeverMoves() { assertEquals("none",decision(consent=false)) }
    @Test fun explicitEligibleWakeIsOneAttempt() { assertEquals("wake",decision()); assertEquals("none",decision(attempted=true)) }
    @Test fun otherAlarmCannotOwnWake() { assertEquals("none",decision(current="b")) }
    @Test fun foregroundAndParkingRequired() { assertEquals("defer",decision(foreground=false));assertEquals("defer",decision(parked=false)) }
    @Test fun runningOrTerminalCannotRestart() { assertEquals("none",decision(phase="waking"));assertEquals("none",decision(phase="failed"));assertEquals("defer",decision(busy=true)) }
    @Test fun driverSeatNeverAcceptsCabinArea() {
        val area=RestControlArea("seat",listOf("ROW_1_LEFT","ROW_1_RIGHT"))
        assertFalse(area.authorized("seatRecline",true));assertFalse(area.authorized("seatVentilation",true))
    }
    @Test fun cabinHvacRequiresSeparateConsent() {
        val area=RestControlArea("seat",listOf("ROW_1_LEFT","ROW_1_RIGHT"))
        assertFalse(area.authorized("fan",false));assertTrue(area.authorized("fan",true));assertEquals("cabin",area.scope)
    }
    @Test fun blindUsesOneActualWindowAreaOnly() {
        assertTrue(RestControlArea("window",listOf("ROOF_TOP_1")).authorized("blind",false))
        assertFalse(RestControlArea("window",listOf("ROOF_TOP_1","ROOF_TOP_2")).authorized("blind",true))
        assertFalse(RestControlArea("seat",listOf("ROW_1_LEFT")).authorized("blind",true))
    }
    @Test fun positionReadAndMoveWriteBothRequired() {
        assertTrue(RestMotionPolicy.readableAndWritable("READ","WRITE"))
        assertFalse(RestMotionPolicy.readableAndWritable("WRITE","WRITE"))
        assertFalse(RestMotionPolicy.readableAndWritable("READ","READ"))
    }
    @Test fun positionBoundsAndOppositeAreRejected() {
        assertEquals("invalid",RestMotionPolicy.observation(3.0,3.0,Double.NaN,4.0,0.0,10.0))
        assertEquals("opposite",RestMotionPolicy.observation(3.0,3.0,2.0,4.0,0.0,10.0))
    }
}
