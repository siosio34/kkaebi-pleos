package ai.kkaebi.app.rest

/** Alarm ACK is necessary. Ringing, expiry and signal changes never authorize seat motion. */
object RestRoutinePolicy {
    fun wakeDecision(sessionAlarmId: String, currentAlarmId: String, alarmState: String, consent: Boolean,
        attempted: Boolean, phase: String, foreground: Boolean, parked: Boolean, busy: Boolean): String {
        if(!consent || attempted || sessionAlarmId.isBlank() || sessionAlarmId != currentAlarmId || alarmState != "idle" ||
            phase !in setOf("preparing", "resting", "deferred")) return "none"
        return if(foreground && parked && !busy) "wake" else "defer"
    }
}
