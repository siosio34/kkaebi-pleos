package ai.kkaebi.app

import ai.kkaebi.app.rest.RestWritePolicy
import ai.kkaebi.app.rest.RestJournalConfirmation
import ai.kkaebi.app.rest.RestSafety
import org.junit.Assert.*
import org.junit.Test

class RestWritePolicyTest {
    @Test fun onlyExplicitlyUndispatchedJournalCanBeDiscarded() {
        assertTrue(RestWritePolicy.knownUndispatched(false))
        assertFalse(RestWritePolicy.knownUndispatched(true))
        assertFalse(RestWritePolicy.knownUndispatched(null))
    }

    @Test fun powerOnComesBeforeDependentClimateSettings() {
        val values = linkedMapOf("temperature" to 22.0, "fan" to 1.0, "hvacPower" to 1.0, "seatVentilation" to 1.0)
        assertEquals(listOf("hvacPower", "temperature", "fan", "seatVentilation"), RestWritePolicy.orderedKeys(values))
    }

    @Test fun sleepStartsWithSeatThenBlindRegardlessOfSelectionInsertionOrder() {
        val values = linkedMapOf("brightness" to 10.0, "fan" to 1.0, "blind" to 8.0,
            "temperature" to 22.0, "seatVentilation" to 1.0, "hvacPower" to 1.0, "seatRecline" to 4.0)
        assertEquals(listOf("seatRecline", "blind", "hvacPower", "temperature", "fan", "seatVentilation", "brightness"),
            RestWritePolicy.orderedKeys(values))
        assertEquals(listOf("seatRecline", "blind", "temperature", "fan", "seatVentilation", "brightness", "hvacPower"),
            RestWritePolicy.orderedKeys(values + ("hvacPower" to 0.0)))
    }

    @Test fun powerOffComesAfterAllOtherSelectedSettings() {
        val values = linkedMapOf("hvacPower" to 0.0, "temperature" to 22.0, "fan" to 1.0, "seatVentilation" to 1.0)
        assertEquals(listOf("temperature", "fan", "seatVentilation", "hvacPower"), RestWritePolicy.orderedKeys(values))
    }

    @Test fun restorationOrdersByOriginalPowerValueAndNeverInsertsUnselectedControls() {
        assertEquals(listOf("hvacPower", "temperature"), RestWritePolicy.orderedKeys(linkedMapOf("temperature" to 24.0, "hvacPower" to 1.0)))
        assertEquals(listOf("temperature", "hvacPower"), RestWritePolicy.orderedKeys(linkedMapOf("hvacPower" to 0.0, "temperature" to 24.0)))
        assertEquals(listOf("seatVentilation"), RestWritePolicy.orderedKeys(mapOf("seatVentilation" to 0.0)))
        assertTrue(RestWritePolicy.orderedKeys(emptyMap()).isEmpty())
    }
    @Test fun wakeLightsThenClimateThenBlindThenSeatPreservesPowerDependencies() {
        val targets=linkedMapOf("seatRecline" to 5.0, "blind" to 0.0, "fan" to 2.0, "brightness" to 80.0, "hvacPower" to 1.0)
        assertEquals(listOf("brightness","hvacPower","fan","blind","seatRecline"), RestWritePolicy.orderedKeys(targets, restoring=true))
        assertEquals(listOf("brightness","fan","blind","seatRecline","hvacPower"), RestWritePolicy.orderedKeys(targets+ ("hvacPower" to 0.0), restoring=true))
    }
    @Test fun automaticRestoreRequiresOwnerAndFreshJournalNotLegacyOrFuture() {
        assertTrue(RestWritePolicy.autoRestoreOwned("a","a",1_000,2_000))
        assertFalse(RestWritePolicy.autoRestoreOwned("b","a",1_000,2_000))
        assertFalse(RestWritePolicy.autoRestoreOwned(null,"a",1_000,2_000))
        assertFalse(RestWritePolicy.autoRestoreOwned("a","a",0,2_000))
        assertFalse(RestWritePolicy.autoRestoreOwned("a","a",3_000,2_000))
        assertFalse(RestWritePolicy.autoRestoreOwned("a","a",1_000,7_201_001))
    }
    @Test fun interruptedRestoreCannotBeMistakenForManualChangeAndDiscarded() {
        val applied = RestJournalConfirmation(appliedConfirmed=true)
        assertEquals("preserveManual", RestSafety.restoreDecision(4.0,8.0,applied.appliedConfirmed))
        val restoring = applied.beginRestoreDispatch()
        assertTrue(restoring.restoreDispatched)
        assertEquals("pending", RestSafety.restoreDecision(4.0,8.0,restoring.appliedConfirmed))
        assertEquals("restore", RestSafety.restoreDecision(8.0,8.0,restoring.appliedConfirmed))
    }
}
