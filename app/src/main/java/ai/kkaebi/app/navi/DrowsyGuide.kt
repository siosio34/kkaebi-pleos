package ai.kkaebi.app.navi

import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.engine.RestAreas

/** UI-independent state for one driver-requested guide session. */
class DrowsyGuide(private val areas: List<RestArea>, private val now: () -> Long = System::currentTimeMillis) {
    data class Comfort(val state: String = "idle", val value: Double? = null, val original: Double? = null)
    data class Snapshot(
        val active: Boolean = false, val requestId: String = "", val state: String = "idle",
        val restArea: RestArea? = null, val distanceM: Double? = null, val routeDistanceM: Double? = null,
        val waypoint: String = "none", val comfort: Map<String, Comfort> = keys.associateWith { Comfort() },
        val message: String = ""
    )
    var snapshot = Snapshot(); private set
    private var requestedAt = 0L

    fun command(id: String) { snapshot = snapshot.copy(requestId = id.take(64)) }
    fun notice(message: String) { snapshot = snapshot.copy(message = message) }

    fun start(id: String) {
        snapshot = Snapshot(active = true, requestId = id.take(64), state = "locating", message = "현재 위치를 확인하고 있어요")
        requestedAt = 0L
    }
    fun location(lat: Double?, lng: Double?, heading: Double?, parked: Boolean) {
        if (!snapshot.active) return
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            snapshot = snapshot.copy(state = "noLocation", distanceM = null, routeDistanceM = null, message = "현재 위치를 확인할 수 없어요")
            return
        }
        val chosen = snapshot.restArea ?: RestAreas.nearest(areas, lat, lng, heading)?.first
        if (chosen == null) {
            snapshot = snapshot.copy(state = "noRestArea", message = "근처 졸음쉼터를 찾지 못했어요")
            return
        }
        val distance = RestAreas.distanceM(lat, lng, chosen.lat, chosen.lng)
        snapshot = snapshot.copy(restArea = chosen, distanceM = distance,
            state = if (distance <= 300 && parked) "arrived" else "guiding",
            message = when {
                distance <= 300 && parked -> "졸음쉼터에 도착했어요"
                snapshot.waypoint == "unconfirmed" -> "내비 경유지 추가를 확인하지 못했어요"
                snapshot.waypoint == "unavailable" -> "차량 내비를 사용할 수 없어요 · 직접 경로를 확인해 주세요"
                else -> "${chosen.name}까지 약 ${distance.toInt()}m 남았어요"
            })
    }
    fun waypointRequested() { if (snapshot.active && snapshot.restArea != null) { requestedAt = now(); snapshot = snapshot.copy(waypoint = "requested") } }
    fun waypointConfirmed() { if (snapshot.active && snapshot.waypoint in setOf("requested", "unconfirmed")) snapshot = snapshot.copy(waypoint = "confirmed", message = "내비 경유지를 확인했어요") }
    fun waypointUnavailable() { if (snapshot.active) snapshot = snapshot.copy(waypoint = "unavailable", message = "차량 내비를 사용할 수 없어요 · 직접 경로를 확인해 주세요") }
    fun tick() { if (snapshot.waypoint == "requested" && now() - requestedAt >= 10_000) snapshot = snapshot.copy(waypoint = "unconfirmed", message = "내비 경유지 추가를 확인하지 못했어요") }
    fun routeDistance(meters: Double?) { if (snapshot.active) snapshot = snapshot.copy(routeDistanceM = meters?.takeIf { it.isFinite() && it >= 0 }) }
    fun comfort(key: String, state: String, value: Double?, original: Double?) {
        require(key in keys && state in setOf("idle", "applied", "failed", "unsupported", "restored"))
        snapshot = snapshot.copy(comfort = snapshot.comfort + (key to Comfort(state, value, original)))
    }
    fun stop() { snapshot = Snapshot(requestId = snapshot.requestId, comfort = snapshot.comfort, message = "졸음모드를 종료했어요") }
    companion object { val keys = setOf("fan", "seatVentilation", "window") }
}

object DrowsyComfortPolicy {
    val keys = setOf("fan", "seatVentilation", "window")
    fun allowed(key: String, value: Any?): Boolean = when (key) {
        "fan" -> value is Int && value in 1..3
        "seatVentilation" -> value is Int && value in 0..3
        "window" -> value is Boolean
        else -> false
    }
    /** A single request opens at most ten percentage points and never requests full opening. */
    fun windowOpenTarget(before: Double, min: Double?, max: Double?): Double? =
        if (before.isFinite() && min == 0.0 && max == 100.0 && before in 0.0..<90.0)
            kotlin.math.min(90.0, before + 10.0) else null
}
