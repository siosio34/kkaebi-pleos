package ai.kkaebi.app.navi

import ai.kkaebi.app.engine.RestArea
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 내비·위치. 경로상 다음 휴게소/졸음쉼터(TBT RESTAREA·SERVICEAREA)가 있으면 그것을, 없으면 공공데이터 좌표 중 가까운 곳을
 * 경유지로 추가한다. 에뮬레이터에 내비가 없으면 [naviReady]=false이고 안내만 한다.
 */
class NaviBridge(context: Context) {
    private val ctx = context.applicationContext
    private val navi = NaviHelper(ctx)
    private val fused = FusedLocation(ctx)
    @Volatile var naviReady = false; private set
    @Volatile var navigating = false; private set
    @Volatile var lat: Double? = null; private set
    @Volatile var lng: Double? = null; private set
    @Volatile var heading: Double? = null; private set
    /** 경로상 다음 쉼터까지 거리(m), 없으면 null */
    val nextRestAreaM = MutableStateFlow<Double?>(null)
    val destination = MutableStateFlow<String?>(null)

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            nextRestAreaM.value = tbtInfoList.firstOrNull { it.type == TurnType.TURN_RESTAREA || it.type == TurnType.TURN_ENTER_SERVICEAREA }?.distance
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {}
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { destination.value = destinationInfo.name }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { navigating = true }
        override fun onRoutingCompleted() { runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }
        override fun onNavigationStatus(status: NavigationStatus) { navigating = status == NavigationStatus.ROUTE_DRIVE }
        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error $errorCode") }
    }
    private val fusedL = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) { location ?: return; lat = location.latitude; lng = location.longitude; heading = location.heading?.toDouble() }
        override fun onError(exception: Exception) { Log.w(TAG, "fused: ${exception.message}") }
    }

    fun start() {
        runCatching { navi.initialize({ naviReady = true; runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }, { Log.w(TAG, "navi init: ${it.message}") }); navi.addListener(listener) }.onFailure { Log.w(TAG, "navi: ${it.message}") }
        runCatching { fused.initialize(); if (fused.isSupported()) fused.registerFusedLocationCallback(fusedL) }.onFailure { Log.w(TAG, "fused: ${it.message}") }
    }
    fun stop() { runCatching { navi.removeListener(listener); navi.release() }; runCatching { fused.unregisterFusedLocationCallback(fusedL); fused.release() } }

    /** 쉼터를 첫 경유지로. 내비가 없거나 경로가 없으면 false (앱은 음성으로만 안내) */
    fun addWaypoint(ra: RestArea): Boolean {
        if (!naviReady) return false
        return runCatching {
            navi.addWaypoint(RequestWaypointInfo(latitude = ra.lat, longitude = ra.lng, poiName = ra.name, waypointIndex = WaypointIndex.FIRST, poiId = "", address = ra.road, poiSubId = ""))
            true
        }.onFailure { Log.w(TAG, "addWaypoint: ${it.message}") }.getOrDefault(false)
    }

    companion object { const val TAG = "Kkaebi" }
}
