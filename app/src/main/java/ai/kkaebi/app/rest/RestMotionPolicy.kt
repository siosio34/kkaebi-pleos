package ai.kkaebi.app.rest

import kotlin.math.abs

/** Native position units. Never degrees, percent, or an inferred physical endpoint. */
object RestMotionPolicy {
    const val TOTAL_TIMEOUT_MS = 8_000L
    const val PULSE_TIMEOUT_MS = 600L
    const val NO_PROGRESS_MS = 1_500L
    fun direction(current: Double, target: Double): Int = when {
        !current.isFinite() || !target.isFinite() -> throw IllegalArgumentException("위치 확인 실패")
        target > current -> 1
        target < current -> -1
        else -> 0
    }
    fun observation(start: Double, previous: Double, current: Double, target: Double, min: Double, max: Double): String {
        if (!current.isFinite() || current !in min..max) return "invalid"
        val direction = direction(start, target)
        if ((current - previous) * direction < 0) return "opposite"
        if ((current - target) * direction > 0) return "overshot"
        if (abs(current - target) < .05) return "reached"
        return if (current == previous) "stalled" else "progress"
    }
    fun readableAndWritable(positionAccess: String, moveAccess: String) =
        positionAccess in setOf("READ", "READ_WRITE") && moveAccess in setOf("WRITE", "READ_WRITE")
}
