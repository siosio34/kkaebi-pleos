package ai.kkaebi.app.rest

import kotlin.math.abs
import kotlin.math.round

/** Capability lookup success is separate from a control write succeeding. */
data class RestControlCapability(
    val state: String = "unknown",
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val origin: Double? = null,
    val access: String = "UNKNOWN",
    val automatic: Boolean = true,
    val message: String = "차량 지원 범위를 확인하지 못했습니다",
    val checkedAt: Long = 0L,
    val area: RestControlArea? = null
)

/** No inferred or default values may satisfy this gate. Times are fresh successful reads. */
object RestSafety {
    fun parked(gear: String, speedKmh: Double?, gearAt: Long, speedAt: Long, now: Long, restricted: Boolean): Boolean =
        !restricted && gear == "P" && speedKmh != null && speedKmh.isFinite() && abs(speedKmh) <= 0.1 &&
            gearAt > 0 && speedAt > 0 && now - gearAt in 0..4_999 && now - speedAt in 0..4_999

    fun validPlan(temperature: Double, fan: Int) = temperature.isFinite() && temperature in 18.0..26.0 && fan in 1..3

    fun validAlarmDeadline(deadline: Long, now: Long) = deadline > now && deadline - now in 1..25 * 60_000L

    fun shouldRestore(current: Double, applied: Double) = current.isFinite() && applied.isFinite() && abs(current - applied) < 0.05

    fun restoreDecision(current: Double, applied: Double, appliedConfirmed: Boolean): String = when {
        !current.isFinite() || !applied.isFinite() -> "pending"
        shouldRestore(current, applied) -> "restore"
        appliedConfirmed -> "preserveManual"
        else -> "pending" // A timed-out write may still complete; do not discard its journal.
    }

    fun validCapabilityValue(value: Double, min: Double?, max: Double?, step: Double?, origin: Double?): Boolean {
        if (!value.isFinite() || min == null || max == null || !min.isFinite() || !max.isFinite() || min > max || value !in min..max) return false
        if (step == null || origin == null || !step.isFinite() || step <= 0.0 || !origin.isFinite()) return false
        val offset = (value - origin) / step
        return offset.isFinite() && abs(offset - round(offset)) < 0.001
    }

    fun canRequest(key: String, value: Double, capability: RestControlCapability, now: Long): Boolean =
        capability.automatic && capability.state == "supported" && capability.access == "READ_WRITE" &&
            capability.checkedAt > 0 && now - capability.checkedAt in 0..59_999 &&
            validCapabilityValue(value, capability.min, capability.max, capability.step, capability.origin) &&
            when (key) {
                "temperature" -> value in 18.0..26.0
                "fan" -> value in 1.0..3.0 && value == round(value)
                "brightness" -> value == 10.0
                "seatVentilation" -> value in 0.0..3.0 && value == round(value)
                "hvacPower" -> value == 0.0 || value == 1.0
                "seatRecline", "blind" -> value == round(value)
                else -> false
            }

    fun capability(
        access: String, areaPresent: Boolean, min: Double?, max: Double?, step: Double?, origin: Double?, now: Long
    ): RestControlCapability {
        val state = when {
            access !in setOf("NONE", "READ", "WRITE", "READ_WRITE") -> "unknown"
            access != "READ_WRITE" || !areaPresent -> "unsupported"
            min == null || max == null || !validCapabilityValue(min, min, max, step, origin) -> "unknown"
            else -> "supported"
        }
        val message = when (state) {
            "supported" -> "운전석 읽기·쓰기 및 범위 확인 · 실행 결과는 별도 확인"
            "unsupported" -> "운전석 단독 영역의 읽기·쓰기 지원이 확인되지 않았습니다"
            else -> "차량 범위 또는 단계 정보를 확인할 수 없습니다"
        }
        return RestControlCapability(state, min, max, step, origin, access, message = message, checkedAt = now)
    }
}
