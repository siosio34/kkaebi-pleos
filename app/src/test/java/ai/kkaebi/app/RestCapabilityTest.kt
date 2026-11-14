package ai.kkaebi.app

import ai.kkaebi.app.rest.RestControlCapability
import ai.kkaebi.app.rest.RestSafety
import org.junit.Assert.*
import org.junit.Test

class RestCapabilityTest {
    private val now = 100_000L
    private fun capability(min: Double = 0.0, max: Double = 3.0, step: Double = 1.0, origin: Double = 0.0) =
        RestSafety.capability("READ_WRITE", true, min, max, step, origin, now)

    @Test fun readOnlyWriteOnlyAndAbsentAreaCannotBeAutomaticallyControlled() {
        listOf("NONE", "READ", "WRITE").forEach {
            val c = RestSafety.capability(it, true, 0.0, 3.0, 1.0, 0.0, now)
            assertEquals("unsupported", c.state)
            assertFalse(RestSafety.canRequest("seatVentilation", 1.0, c, now))
        }
        assertEquals("unsupported", RestSafety.capability("READ_WRITE", false, 0.0, 3.0, 1.0, 0.0, now).state)
    }

    @Test fun missingOrMalformedBoundsAreUnknownInsteadOfInvented() {
        for ((min, max) in listOf(null to 3.0, 0.0 to null, 3.0 to 0.0, Double.NaN to 3.0, 0.0 to Double.POSITIVE_INFINITY)) {
            assertEquals("unknown", RestSafety.capability("READ_WRITE", true, min, max, 1.0, 0.0, now).state)
        }
        assertEquals("unknown", RestSafety.capability("UNKNOWN", true, 0.0, 3.0, 1.0, 0.0, now).state)
    }

    @Test fun celsiusIncrementAndHardwareRangeAreBothEnforced() {
        val c = capability(19.0, 25.0, 0.5, 19.0)
        assertTrue(RestSafety.canRequest("temperature", 22.5, c, now))
        assertFalse(RestSafety.canRequest("temperature", 22.25, c, now))
        assertFalse(RestSafety.canRequest("temperature", 18.0, c, now))
        assertFalse(RestSafety.canRequest("temperature", 26.0, c, now))
    }

    @Test fun invalidStepsNeverEnableWrites() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals("unknown", RestSafety.capability("READ_WRITE", true, 18.0, 26.0, it, 18.0, now).state)
        }
    }

    @Test fun explicitVentilationOffAndSupportedStepsOnly() {
        assertTrue(RestSafety.canRequest("seatVentilation", 0.0, capability(), now))
        assertTrue(RestSafety.canRequest("seatVentilation", 3.0, capability(), now))
        assertFalse(RestSafety.canRequest("seatVentilation", 3.0, capability(max = 2.0), now))
        assertFalse(RestSafety.canRequest("seatVentilation", 4.0, capability(max = 5.0), now))
        assertFalse(RestSafety.canRequest("seatVentilation", 1.5, capability(), now))
        assertFalse(RestSafety.canRequest("seatVentilation", -1.0, capability(min = -1.0), now))
    }

    @Test fun temperatureFanAndLightingKeepAppAllowlistEvenOnBroadHardware() {
        assertFalse(RestSafety.canRequest("temperature", 30.0, capability(16.0, 32.0), now))
        assertFalse(RestSafety.canRequest("fan", 0.0, capability(0.0, 8.0), now))
        assertFalse(RestSafety.canRequest("fan", 4.0, capability(0.0, 8.0), now))
        assertTrue(RestSafety.canRequest("brightness", 10.0, capability(0.0, 100.0), now))
        assertFalse(RestSafety.canRequest("brightness", 100.0, capability(0.0, 100.0), now))
        assertFalse(RestSafety.canRequest("brightness", 10.0, capability(20.0, 100.0), now))
    }

    @Test fun powerIsStrictlyBooleanAndOnlyBoundedPositionKeysMayBeSelected() {
        assertTrue(RestSafety.canRequest("hvacPower", 0.0, capability(0.0, 1.0), now))
        assertTrue(RestSafety.canRequest("hvacPower", 1.0, capability(0.0, 1.0), now))
        assertFalse(RestSafety.canRequest("hvacPower", 2.0, capability(), now))
        listOf("seatRecline", "blind").forEach {
            assertTrue(RestSafety.canRequest(it, 1.0, capability(), now))
            assertFalse(RestSafety.canRequest(it, 1.5, capability(), now))
            assertFalse(RestSafety.canRequest(it, 1.0, capability().copy(automatic = false), now))
        }
        assertTrue(RestSafety.canRequest("window", 0.0, capability(0.0, 100.0), now))
        assertFalse(RestSafety.canRequest("window", 10.0, capability(0.0, 100.0), now))
        listOf("windowClose", "seatHeating", "smartGlass", "wiper").forEach {
            assertFalse(RestSafety.canRequest(it, 1.0, capability(), now))
        }
    }

    @Test fun unknownFailedStaleFutureAndManualCapabilitiesCannotWrite() {
        assertFalse(RestSafety.canRequest("fan", 1.0, RestControlCapability(), now))
        assertFalse(RestSafety.canRequest("fan", 1.0, capability().copy(automatic = false), now))
        assertFalse(RestSafety.canRequest("fan", 1.0, capability().copy(checkedAt = now + 1), now))
        assertFalse(RestSafety.canRequest("fan", 1.0, capability().copy(checkedAt = now - 60_000), now))
        assertTrue(RestSafety.canRequest("fan", 1.0, capability().copy(checkedAt = now - 59_999), now))
    }

    @Test fun restorationCanUseOriginalHardwareValueOutsideTheRestPreset() {
        assertFalse(RestSafety.canRequest("fan", 6.0, capability(0.0, 8.0), now))
        assertTrue(RestSafety.validCapabilityValue(6.0, 0.0, 8.0, 1.0, 0.0))
        assertFalse(RestSafety.validCapabilityValue(Double.NaN, 0.0, 8.0, 1.0, 0.0))
        assertFalse(RestSafety.validCapabilityValue(9.0, 0.0, 8.0, 1.0, 0.0))
        assertFalse(RestSafety.shouldRestore(2.0, 1.0))
    }

    @Test fun timedOutUnconfirmedWriteDoesNotEraseTheRecoveryJournal() {
        assertEquals("pending", RestSafety.restoreDecision(22.0, 23.0, false))
        assertEquals("restore", RestSafety.restoreDecision(23.0, 23.0, false))
        assertEquals("preserveManual", RestSafety.restoreDecision(24.0, 23.0, true))
        assertEquals("pending", RestSafety.restoreDecision(Double.NaN, 23.0, true))
    }
}
