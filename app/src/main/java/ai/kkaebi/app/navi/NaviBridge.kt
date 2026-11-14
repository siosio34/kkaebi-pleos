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
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** Pleos location and navigation adapter; route choice lives in [DrowsyGuide]. */
class NaviBridge(context: Context) {
    var onLocation: ((Double?, Double?, Double?) -> Unit)? = null
    var onWaypointConfirmed: (() -> Unit)? = null
    var onRouteDistance: ((Double?) -> Unit)? = null
    private var pendingWaypoint: RestArea? = null
    private val ctx = context.applicationContext
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
        }
        override fun onWayPointInfo(info: List<WaypointInfo>) {
            val target = pendingWaypoint ?: return
            if (info.any { kotlin.math.abs(it.latitude - target.lat) < 0.0001 && kotlin.math.abs(it.longitude - target.lng) < 0.0001 }) {
                onWaypointConfirmed?.invoke()
            }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {}
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { destination.value = destinationInfo.name }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { navigating = true }
        override fun onRoutingCompleted() { runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }
        override fun onNavigationStatus(status: NavigationStatus) { navigating = status == NavigationStatus.ROUTE_DRIVE }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error $errorCode") }
    }
    private val fusedL = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) { location ?: return; lat = location.latitude; lng = location.longitude; heading = location.heading.toDouble().takeIf { it.isFinite() }; locationAt = System.currentTimeMillis(); if (!located) { located = true; Log.i(TAG, "fused: first location ${"%.2f".format(location.latitude)},${"%.2f".format(location.longitude)} heading=${location.heading} confidence=${location.confidence}") }; onLocation?.invoke(lat, lng, heading) }
        override fun onError(exception: Exception) { Log.w(TAG, "fused: ${exception.message}") }
    }

    fun start() {
        runCatching { navi.initialize({ naviReady = true; runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }, { Log.w(TAG, "navi init: ${it.message}") }); navi.addListener(listener) }.onFailure { Log.w(TAG, "navi: ${it.message}") }
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
    fun requestLocation() { runCatching { fused.getLastKnownLocation(fusedL) } }

    /** Sends the first-waypoint request. A true result means dispatched, not confirmed. */
    fun addWaypoint(ra: RestArea): Boolean {
        if (!naviReady) return false
        return runCatching {
            pendingWaypoint = ra
            navi.addWaypoint(RequestWaypointInfo(latitude = ra.lat, longitude = ra.lng, poiName = ra.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = ra.road, poiSubId = ""))
            true
        }.onFailure { pendingWaypoint = null; Log.w(TAG, "addWaypoint: ${it.message}") }.getOrDefault(false)
    }
    fun clearGuide() { pendingWaypoint = null; onRouteDistance?.invoke(null) }

    companion object { const val TAG = "Kkaebi" }
}
