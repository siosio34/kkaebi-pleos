package ai.kkaebi.app.rest

/** A saved pose is a user-selected measured position, never an inferred comfortable endpoint. */
object RestPosePolicy {
    fun canCapture(active: Boolean, parked: Boolean, running: Boolean, alarmState: String,
                   pendingRestore: Boolean, stopUnconfirmed: Boolean) =
        active && parked && !running && alarmState in setOf("idle", "failed") && !pendingRestore && !stopUnconfirmed

    fun valid(value: Double, capability: RestControlCapability, now: Long): Boolean =
        capability.automatic && capability.area != null && capability.checkedAt > 0 &&
            now - capability.checkedAt in 0..60_000 && capability.state == "supported" &&
            capability.access == "READ_WRITE" &&
            RestSafety.validCapabilityValue(value, capability.min, capability.max, capability.step, capability.origin)

    fun status(successCount: Int) = when(successCount) { 2 -> "ready"; 1 -> "partial"; else -> "failed" }
    fun shouldReplaceSavedPose(successCount: Int) = successCount in 1..2
}
