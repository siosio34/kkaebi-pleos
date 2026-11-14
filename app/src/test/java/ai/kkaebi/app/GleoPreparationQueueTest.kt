package ai.kkaebi.app

import ai.kkaebi.app.gleo.GleoPreparationQueue
import ai.kkaebi.app.gleo.GleoPreparationQueue.Status
import org.junit.Assert.*
import org.junit.Test

class GleoPreparationQueueTest {
    private fun poll(q: GleoPreparationQueue, now: Long = 1001, active: Boolean = true,
                     ready: Boolean = true, checked: Boolean = false, parked: Boolean = false,
                     demo: Boolean = false) = q.poll(now, active, ready, demo, checked, parked)

    @Test fun resumedRequestWaitsForFreshSignalsThenDeliversExactlyOnce() {
        val q = GleoPreparationQueue(); q.offer("rest-mode", 1000)
        assertEquals(Status.WAIT, poll(q).status)
        assertEquals("rest-mode", poll(q, parked = true).command)
        assertEquals(Status.NONE, poll(q, parked = true).status)
    }
    @Test fun confirmedUnsafeRequestCannotReappearWhenParkedLater() {
        val q = GleoPreparationQueue(); q.offer("rest-mode", 1000)
        assertEquals(Status.BLOCKED, poll(q, checked = true).status)
        assertEquals(Status.NONE, poll(q, parked = true).status)
    }
    @Test fun sevenSecondDeadlineDoesNotDependOnUiOrSdkAvailability() {
        val q = GleoPreparationQueue(); q.offer("rest-mode", 1000)
        assertEquals(Status.WAIT, poll(q, now = 7999, active = false, ready = false).status)
        assertEquals(Status.EXPIRED, poll(q, now = 8000, active = false, ready = false).status)
        assertEquals(Status.NONE, poll(q, now = 8001, parked = true).status)
    }
    @Test fun pauseClearPreventsReplayInAnotherForeground() {
        val q = GleoPreparationQueue(); q.offer("rest-mode", 1000)
        assertEquals(Status.WAIT, poll(q, ready = false, parked = true).status)
        q.clear()
        assertEquals(Status.NONE, poll(q, parked = true).status)
    }
    @Test fun demoAndInvalidReplacementNeverDeliverPreparation() {
        val q = GleoPreparationQueue(); q.offer("rest-mode", 1000)
        assertEquals(Status.BLOCKED, poll(q, demo = true, parked = true).status)
        q.offer("rest-mode", 1000); q.offer("applyRest", 1001)
        assertFalse(q.pending)
        assertEquals(Status.NONE, poll(q, parked = true).status)
    }
    @Test fun quietAndDrowsyRemainAvailableWithoutParkingButWaitForVisibleUi() {
        for (command in listOf("quiet", "drowsy-mode")) {
            val q = GleoPreparationQueue(); q.offer(command, 1000)
            assertEquals(Status.WAIT, poll(q, active = false).status)
            assertEquals(command, poll(q, checked = true).command)
        }
    }
}
