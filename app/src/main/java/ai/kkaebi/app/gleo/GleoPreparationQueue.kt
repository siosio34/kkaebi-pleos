package ai.kkaebi.app.gleo

/** Short-lived UI preparation only. No alarm, consent or vehicle control is held in this mailbox. */
class GleoPreparationQueue {
    enum class Status { NONE, WAIT, DELIVER, BLOCKED, EXPIRED }
    data class Result(val status: Status, val command: String? = null)
    private var command: String? = null
    private var receivedAt = 0L
    val pending: Boolean get() = command != null

    fun offer(value: String?, now: Long) {
        command = value?.takeIf { it in setOf("rest-mode", "drowsy-mode", "quiet") }
        receivedAt = now
    }
    fun clear() { command = null }

    fun poll(now: Long, foreground: Boolean, webReady: Boolean, demo: Boolean,
             signalsChecked: Boolean, parked: Boolean): Result {
        val value = command ?: return Result(Status.NONE)
        if (now < receivedAt || now - receivedAt >= 7_000) {
            clear(); return Result(Status.EXPIRED, value)
        }
        if (demo) { clear(); return Result(Status.BLOCKED, value) }
        if (!foreground || !webReady) return Result(Status.WAIT)
        if (value == "rest-mode" && !parked) {
            if (!signalsChecked) return Result(Status.WAIT)
            clear(); return Result(Status.BLOCKED, value)
        }
        clear()
        return Result(Status.DELIVER, value)
    }
}
