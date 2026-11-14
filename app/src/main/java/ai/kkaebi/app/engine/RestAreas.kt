package ai.kkaebi.app.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RestArea(val name: String, val lat: Double, val lng: Double, val road: String, val parking: Int, val toilet: Boolean)

/** 공공데이터(전국 졸음쉼터 표준데이터) 좌표에서 지금 위치·진행 방향에 맞는 가장 가까운 곳 */
object RestAreas {
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0; val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** headingDeg가 있으면 진행 방향 ±100° 안(앞쪽)의 쉼터만. 없으면 그냥 가까운 순 */
    fun nearest(all: List<RestArea>, lat: Double, lng: Double, headingDeg: Double? = null, maxM: Double = 60_000.0): Pair<RestArea, Double>? {
        val ahead = all.filter { ra ->
            if (headingDeg == null) true else {
                val b = Math.toDegrees(atan2(ra.lng - lng, ra.lat - lat)).let { if (it < 0) it + 360 else it }
                val diff = ((b - headingDeg + 540) % 360) - 180
                kotlin.math.abs(diff) <= 100
            }
        }.ifEmpty { all }
        return ahead.map { it to distanceM(lat, lng, it.lat, it.lng) }.filter { it.second <= maxM }.minByOrNull { it.second }
    }
}
