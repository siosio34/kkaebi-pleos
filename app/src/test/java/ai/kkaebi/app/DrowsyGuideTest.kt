package ai.kkaebi.app

import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.navi.DrowsyComfortPolicy
import ai.kkaebi.app.navi.DrowsyGuide
import org.junit.Assert.*
import org.junit.Test

class DrowsyGuideTest {
    private val area = RestArea("테스트 졸음쉼터", 37.0, 127.0, "고속도로", 20, true)

    @Test fun noLocation() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one")
        guide.location(null, null, null, false)
        assertEquals("noLocation", guide.snapshot.state)
        assertNull(guide.snapshot.distanceM)
        assertNull(guide.snapshot.restArea)
    }

    @Test fun noRestArea() {
        val guide = DrowsyGuide(emptyList())
        guide.start("one")
        guide.location(37.0, 127.0, null, false)
        assertEquals("noRestArea", guide.snapshot.state)
    }

    @Test fun choosesAheadAndUpdatesDistance() {
        val behind = area.copy(name = "뒤", lat = 36.99)
        val guide = DrowsyGuide(listOf(behind, area.copy(lat = 37.01)))
        guide.start("one")
        guide.location(37.0, 127.0, 0.0, false)
        assertNotEquals("뒤", guide.snapshot.restArea?.name)
        val first = guide.snapshot.distanceM!!
        guide.location(37.005, 127.0, 0.0, false)
        assertTrue(guide.snapshot.distanceM!! < first)
    }

    @Test fun waypointNeedsEvent() {
        var clock = 1_000L
        val guide = DrowsyGuide(listOf(area)) { clock }
        guide.start("one"); guide.location(37.0, 127.0, null, false)
        guide.waypointRequested()
        assertEquals("requested", guide.snapshot.waypoint)
        clock += 10_001
        guide.tick()
        assertEquals("unconfirmed", guide.snapshot.waypoint)
        guide.location(37.0, 127.0, null, false)
        assertTrue(guide.snapshot.message.contains("확인하지 못했어요"))
        guide.waypointConfirmed()
        assertEquals("confirmed", guide.snapshot.waypoint)
    }

    @Test fun waypointUnavailable() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one"); guide.waypointUnavailable()
        assertEquals("unavailable", guide.snapshot.waypoint)
    }

    @Test fun arrivalRequiresBothDistanceAndPark() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one")
        guide.location(37.0, 127.0, null, false)
        assertEquals("guiding", guide.snapshot.state)
        guide.location(37.0, 127.0, null, true)
        assertEquals("arrived", guide.snapshot.state)
        guide.location(37.004, 127.0, null, true)
        assertEquals("guiding", guide.snapshot.state)
    }

    @Test fun arrivalBoundaryIsThreeHundredMeters() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one")
        guide.location(37.00269, 127.0, null, true)
        assertEquals("arrived", guide.snapshot.state)
        guide.location(37.00271, 127.0, null, true)
        assertEquals("guiding", guide.snapshot.state)
    }

    @Test fun routeDistanceOnlyWhenValid() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one"); guide.routeDistance(1200.0)
        assertEquals(1200.0, guide.snapshot.routeDistanceM!!, 0.01)
        guide.routeDistance(-1.0)
        assertNull(guide.snapshot.routeDistanceM)
    }

    @Test fun comfortApplyFailRestoreAndStop() {
        val guide = DrowsyGuide(listOf(area))
        guide.start("one")
        guide.comfort("fan", "applied", 3.0, 1.0)
        guide.comfort("seatVentilation", "failed", 0.0, 0.0)
        assertEquals("applied", guide.snapshot.comfort.getValue("fan").state)
        assertEquals("failed", guide.snapshot.comfort.getValue("seatVentilation").state)
        guide.comfort("fan", "restored", 1.0, 1.0)
        guide.stop()
        assertFalse(guide.snapshot.active)
        assertEquals("restored", guide.snapshot.comfort.getValue("fan").state)
    }

    @Test fun rejectsUnlistedDrivingControls() {
        assertFalse(DrowsyComfortPolicy.allowed("seatRecline", 3))
        assertFalse(DrowsyComfortPolicy.allowed("blind", 1))
        assertFalse(DrowsyComfortPolicy.allowed("fan", 4))
        assertTrue(DrowsyComfortPolicy.allowed("fan", 3))
        assertTrue(DrowsyComfortPolicy.allowed("window", true))
    }

    @Test fun windowNeverRequestsFullOpening() {
        assertEquals(10.0, DrowsyComfortPolicy.windowOpenTarget(0.0, 0.0, 100.0)!!, 0.01)
        assertEquals(90.0, DrowsyComfortPolicy.windowOpenTarget(85.0, 0.0, 100.0)!!, 0.01)
        assertNull(DrowsyComfortPolicy.windowOpenTarget(90.0, 0.0, 100.0))
        assertNull(DrowsyComfortPolicy.windowOpenTarget(0.0, 0.0, 255.0))
    }
}
