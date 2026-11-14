package ai.kkaebi.app.navi

import ai.kkaebi.app.engine.RestArea
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow

/** Pleos location and navigation adapter; route choice lives in [DrowsyGuide]. */
class NaviBridge(context: Context) {
    var onLocation: ((Double?, Double?, Double?) -> Unit)? = null
    var onWaypointConfirmed: (() -> Unit)? = null
    var onRouteDistance: ((Double?) -> Unit)? = null
    var onResumeChanged: (() -> Unit)? = null
    private var pendingWaypoint: RestArea? = null
    private val ctx = context.applicationContext
    private val resumePrefs = ctx.getSharedPreferences("drowsy-route-resume", Context.MODE_PRIVATE)
    private var routeRecord = runCatching { JSONObject(resumePrefs.getString("route", "{}") ?: "{}") }.getOrDefault(JSONObject())
    @Volatile private var resumeState = "idle"
    @Volatile private var resumeMessage = ""
    @Volatile private var resumeDestination: String? = routeRecord.optString("name").takeIf { it.isNotBlank() }
    private var captureDestination = false
    private var checkingWaypoint = false
    private var removingWaypoint = false
    private var removingIndex = WaypointIndex.FIRST
    private var awaitingRoute = false
    private var routeStartedObserved = false
    private var checkingExistingDestination = false
    private val navi = NaviHelper(ctx)
    private val fused = FusedLocation(ctx)
    @Volatile var naviReady = false; private set
    @Volatile var navigating = false; private set
    @Volatile var lat: Double? = null; private set
    @Volatile var lng: Double? = null; private set
    @Volatile var heading: Double? = null; private set
    @Volatile var locationAt: Long = 0; private set
    /** 경로상 다음 쉼터까지 거리(m), 없으면 null */
    val nextRestAreaM = MutableStateFlow<Double?>(null)
    val destination = MutableStateFlow<String?>(null)

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            nextRestAreaM.value = tbtInfoList.firstOrNull { it.type == TurnType.TURN_RESTAREA || it.type == TurnType.TURN_ENTER_SERVICEAREA }?.distance
            val target = pendingWaypoint
            onRouteDistance?.invoke(tbtInfoList.firstOrNull { target != null && it.description.contains(target.name) &&
                (it.type == TurnType.TURN_RESTAREA || it.type == TurnType.TURN_ENTER_SERVICEAREA) }?.distance)
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) {
            if (info.cmdType == WaypointCmdType.ADD && info.index == WaypointIndex.FIRST)
                runCatching { navi.getWaypointInfo() }
            if (info.cmdType == WaypointCmdType.REMOVE && info.index == removingIndex && removingWaypoint) {
                removingWaypoint = false
                routeRecord.remove("waypointLat"); routeRecord.remove("waypointLng"); saveRoute()
                finishResume()
            }
        }
        override fun onWayPointInfo(info: List<WaypointInfo>) {
            if (checkingWaypoint) {
                checkingWaypoint = false
                val lat = routeRecord.optDouble("waypointLat", Double.NaN)
                val lng = routeRecord.optDouble("waypointLng", Double.NaN)
                val index = info.indexOfFirst { lat.isFinite() && lng.isFinite() && kotlin.math.abs(it.latitude-lat)<0.0001 && kotlin.math.abs(it.longitude-lng)<0.0001 }
                if (index in 0..1) {
                    removingIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND
                    removingWaypoint = true
                    runCatching { navi.removeWaypoint(removingIndex) }.onFailure { removingWaypoint = false; setResume("unconfirmed", "쉼터 경유지 제거 요청 실패: ${it.message}") }
                } else if (index < 0) {
                    routeRecord.remove("waypointLat"); routeRecord.remove("waypointLng"); saveRoute()
                    finishResume()
                } else setResume("unconfirmed", "쉼터 경유지 위치를 안전하게 지정할 수 없어요")
            }
            val target = pendingWaypoint ?: return
            if (info.any { kotlin.math.abs(it.latitude - target.lat) < 0.0001 && kotlin.math.abs(it.longitude - target.lng) < 0.0001 }) {
                onWaypointConfirmed?.invoke()
            }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {}
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destination.value = destinationInfo.name
            if (checkingExistingDestination) {
                checkingExistingDestination = false
                val same = kotlin.math.abs(destinationInfo.latitude-routeRecord.optDouble("lat", Double.NaN)) < 0.0001 &&
                    kotlin.math.abs(destinationInfo.longitude-routeRecord.optDouble("lng", Double.NaN)) < 0.0001
                if (same) { setResume("confirmed", "쉼터 경유지를 제거하고 기존 목적지를 확인했어요"); clearRoute() }
                else setResume("unconfirmed", "현재 목적지가 원래 목적지와 일치하지 않아요")
            }
            if (awaitingRoute && routeStartedObserved) {
                val same = kotlin.math.abs(destinationInfo.latitude-routeRecord.optDouble("lat", Double.NaN)) < 0.0001 &&
                    kotlin.math.abs(destinationInfo.longitude-routeRecord.optDouble("lng", Double.NaN)) < 0.0001
                awaitingRoute = false
                if (same) { setResume("confirmed", "원래 목적지 경로 안내를 확인했어요"); clearRoute() }
                else setResume("unconfirmed", "안내 목적지가 원래 목적지와 일치하지 않아요")
            }
            if (destinationInfo.latitude.isFinite() && destinationInfo.longitude.isFinite() && (kotlin.math.abs(destinationInfo.latitude) > 0.0001 || kotlin.math.abs(destinationInfo.longitude) > 0.0001))
                lastDestination = JSONObject().put("lat", destinationInfo.latitude).put("lng", destinationInfo.longitude).put("name", destinationInfo.name).put("address", destinationInfo.address).put("poiId", destinationInfo.poiId).put("poiSubId", destinationInfo.poiSubId).put("poiProvider", destinationInfo.poiProvider)
            // 경로가 없을 때 내비는 0,0과 빈 이름을 돌려준다. 그건 목적지가 아니다.
            if (captureDestination && destinationInfo.latitude.isFinite() && destinationInfo.longitude.isFinite() &&
                (kotlin.math.abs(destinationInfo.latitude) > 0.0001 || kotlin.math.abs(destinationInfo.longitude) > 0.0001)) {
                captureDestination = false
                routeRecord.put("lat", destinationInfo.latitude).put("lng", destinationInfo.longitude)
                    .put("name", destinationInfo.name).put("address", destinationInfo.address)
                    .put("poiId", destinationInfo.poiId).put("poiSubId", destinationInfo.poiSubId)
                    .put("poiProvider", destinationInfo.poiProvider)
                saveRoute(); resumeDestination = destinationInfo.name; onResumeChanged?.invoke()
                if (resumeState == "noDestination") resumeRoute()
            }
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { navigating = true; if (awaitingRoute) { routeStartedObserved = true; runCatching { navi.getDestinationInfo() } } }
        override fun onRoutingCompleted() { runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }
        override fun onNavigationStatus(status: NavigationStatus) { navigating = status == NavigationStatus.ROUTE_DRIVE; if (awaitingRoute && navigating) { routeStartedObserved = true; runCatching { navi.getDestinationInfo() } } }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error $errorCode"); if (resumeState == "requested") setResume("unconfirmed", "내비 응답 오류: $errorCode") }
    }
    private val fusedL = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) { location ?: return; lat = location.latitude; lng = location.longitude; heading = location.heading.toDouble().takeIf { it.isFinite() }; locationAt = System.currentTimeMillis(); if (!located) { located = true; Log.i(TAG, "fused: first location ${"%.2f".format(location.latitude)},${"%.2f".format(location.longitude)} heading=${location.heading} confidence=${location.confidence}") }; onLocation?.invoke(lat, lng, heading) }
        override fun onError(exception: Exception) { Log.w(TAG, "fused: ${exception.message}") }
    }

    fun start() {
        runCatching { navi.initialize({ naviReady = true; runCatching { navi.getTBTInfo(); navi.getDestinationInfo() }; if (resumeState == "unavailable" && hasPendingRoute()) resumeRoute() }, { Log.w(TAG, "navi init: ${it.message}") }); navi.addListener(listener) }.onFailure { Log.w(TAG, "navi: ${it.message}") }
        runCatching { fused.initialize() }.onFailure { Log.w(TAG, "fused init: ${it.message}") }
        registerFused(0)
        handler.postDelayed(watchdog, 20_000)
    }
    // 위치 서비스가 재시작되면 등록한 콜백이 조용히 끊긴다. 20초 넘게 위치가 없으면 다시 붙는다.
    private val watchdog = object : Runnable {
        override fun run() { reconnectFusedIfStale(20_000); handler.postDelayed(this, 20_000) }
    }
    private fun reconnectFusedIfStale(maxAgeMs: Long) {
        if (!fusedRegistered || System.currentTimeMillis() - locationAt <= maxAgeMs) return
        Log.w(TAG, "fused: no location for ${maxAgeMs / 1000}s, reconnecting")
        runCatching { fused.unregisterFusedLocationCallback(fusedL) }; runCatching { fused.release() }
        fusedRegistered = false
        runCatching { fused.initialize() }.onFailure { Log.w(TAG, "fused init: ${it.message}") }
        registerFused(0)
    }
    fun stop() { handler.removeCallbacksAndMessages(null); runCatching { navi.removeListener(listener); navi.release() }; runCatching { fused.unregisterFusedLocationCallback(fusedL); fused.release() } }
    @Volatile private var located = false
    @Volatile private var fusedRegistered = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** initialize()는 서비스 연결을 비동기로 시작하므로, 지원 여부가 확인될 때까지 몇 번 다시 시도한다. */
    private fun registerFused(attempt: Int) {
        if (fusedRegistered) return
        val supported = runCatching { fused.isSupported() }.getOrDefault(false)
        if (supported) {
            runCatching { fused.registerFusedLocationCallback(fusedL); fused.getLastKnownLocation(fusedL); fusedRegistered = true; Log.i(TAG, "fused: callback registered (attempt $attempt)") }
                .onFailure { Log.w(TAG, "fused register: ${it.message}") }
            if (!fusedRegistered && attempt < 60) handler.postDelayed({ registerFused(attempt + 1) }, 2000)
        } else if (attempt < 10) handler.postDelayed({ registerFused(attempt + 1) }, 1000)
        else Log.w(TAG, "fused: not supported after $attempt attempts")
    }
    /** 시연 준비용: 운전자가 내비에 목적지를 넣은 상황을 만든다. 정차 중에만 MainActivity가 호출한다. */
    fun requestDestination(lat: Double, lng: Double, name: String): Boolean {
        if (!naviReady) return false
        return runCatching { navi.requestRoute(RouteInfo(lat, lng, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false)); lastDestination = JSONObject().put("lat", lat).put("lng", lng).put("name", name); true }
            .onFailure { Log.w(TAG, "requestDestination: ${it.message}") }.getOrDefault(false)
    }
    fun requestLocation() { reconnectFusedIfStale(15_000); runCatching { fused.getLastKnownLocation(fusedL) } }

    /** Sends the first-waypoint request. A true result means dispatched, not confirmed. */
    fun addWaypoint(ra: RestArea): Boolean {
        if (!naviReady) return false
        return runCatching {
            pendingWaypoint = ra
            navi.addWaypoint(RequestWaypointInfo(latitude = ra.lat, longitude = ra.lng, poiName = ra.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = ra.road, poiSubId = ""))
            routeRecord.put("waypointLat", ra.lat).put("waypointLng", ra.lng); saveRoute()
            true
        }.onFailure { pendingWaypoint = null; Log.w(TAG, "addWaypoint: ${it.message}") }.getOrDefault(false)
    }
    fun clearGuide() { pendingWaypoint = null; onRouteDistance?.invoke(null) }

    private fun saveRoute() { check(resumePrefs.edit().putString("route", routeRecord.toString()).commit()) { "내비 복귀 기록 저장 실패" } }
    private fun clearRoute() { routeRecord = JSONObject(); saveRoute() }
    private fun setResume(state: String, message: String) { resumeDestination = routeRecord.optString("name").takeIf { it.isNotBlank() } ?: resumeDestination; resumeState = state; resumeMessage = message; Log.i(TAG, "routeResume state=$state $message"); onResumeChanged?.invoke() }
    fun routeResumeJson(): JSONObject = JSONObject().put("state", resumeState)
        .put("destination", resumeDestination ?: JSONObject.NULL)
        .put("message", resumeMessage)
    fun hasPendingRoute(): Boolean = routeRecord.optLong("at", 0L).let { it > 0 && System.currentTimeMillis()-it in 0..7_200_000 }
    /** 내비가 알려 준 가장 최근의 유효한 목적지. 졸음모드 시작 때 원래 목적지로 기억한다. */
    @Volatile private var lastDestination: JSONObject? = null
    fun rememberDrowsyRoute() {
        routeRecord = JSONObject().put("at", System.currentTimeMillis())
        lastDestination?.let { d -> for (k in listOf("lat", "lng", "name", "address", "poiId", "poiSubId", "poiProvider")) if (d.has(k)) routeRecord.put(k, d.get(k)) }
        resumeDestination = lastDestination?.optString("name")?.takeIf { it.isNotBlank() }
        destination.value?.takeIf { it.isNotBlank() }?.let { routeRecord.put("name", it) }
        saveRoute(); captureDestination = true
        runCatching { navi.getDestinationInfo() }.onFailure { Log.w(TAG, "destination lookup: ${it.message}") }
        setResume("idle", "기존 경로를 확인하고 있어요")
    }
    fun resumeRoute() {
        if (resumeState == "requested" || resumeState == "confirmed") return
        if (!hasPendingRoute()) { setResume("idle", "졸음모드 경로 기록이 없어요"); return }
        if (!naviReady) { setResume("unavailable", "차량 내비에 연결되지 않았어요"); return }
        val hasWaypoint = routeRecord.optDouble("waypointLat", Double.NaN).isFinite()
        if (!validDestination() && !hasWaypoint) { setResume("noDestination", "원래 목적지가 설정돼 있지 않았어요"); return }
        setResume("requested", "쉼터 경유지와 원래 경로를 확인하고 있어요")
        if (routeRecord.optDouble("waypointLat", Double.NaN).isFinite()) {
            checkingWaypoint = true
            runCatching { navi.getWaypointInfo() }.onFailure { checkingWaypoint = false; setResume("unconfirmed", "경유지 조회 실패: ${it.message}") }
        } else finishResume()
        handler.postDelayed({ if (resumeState == "requested") setResume("unconfirmed", "내비 경로 변경 이벤트를 확인하지 못했어요") }, 12_000)
    }
    private fun validDestination(): Boolean {
        val lat = routeRecord.optDouble("lat", Double.NaN); val lng = routeRecord.optDouble("lng", Double.NaN)
        return lat.isFinite() && lng.isFinite() && (kotlin.math.abs(lat) > 0.0001 || kotlin.math.abs(lng) > 0.0001)
    }
    private fun finishResume() {
        if (!validDestination()) { setResume("noDestination", "원래 목적지가 설정돼 있지 않았어요"); clearRoute(); return }
        if (!navigating) {
            val lat = routeRecord.optDouble("lat", Double.NaN)
            val lng = routeRecord.optDouble("lng", Double.NaN)
            awaitingRoute = true
            routeStartedObserved = false
            runCatching { navi.requestRoute(RouteInfo(lat, lng, null, null, routeRecord.optString("name"),
                routeRecord.optString("poiId"), routeRecord.optString("address"), routeRecord.optString("poiSubId"),
                RouteOption.RECOMMENDED, routeRecord.optString("poiProvider"), false)) }
                .onFailure { awaitingRoute = false; setResume("unconfirmed", "원래 목적지 경로 요청 실패: ${it.message}") }
        } else {
            checkingExistingDestination = true
            runCatching { navi.getDestinationInfo() }.onFailure { checkingExistingDestination = false; setResume("unconfirmed", "현재 목적지 조회 실패: ${it.message}") }
        }
    }

    companion object { const val TAG = "Kkaebi" }
}
