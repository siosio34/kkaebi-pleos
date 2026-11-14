package ai.kkaebi.app.rest

/** One immutable snapshot keeps the operation identity, lifecycle and results together. */
data class RestControlOperation(
    val id: String = "",
    val operation: String? = null,
    val phase: String = "idle",
    val resultsJson: String = "{}",
    val steps: List<RestControlStep> = emptyList()
) {
    fun begin(name: String, nextId: String): RestControlOperation {
        require(name == "apply" || name == "restore")
        require(nextId.isNotEmpty() && nextId != id)
        return RestControlOperation(nextId, name, "running")
    }

    fun results(expectedId: String, json: String): RestControlOperation =
        if (id == expectedId && phase == "running") copy(resultsJson = json) else this

    fun enqueue(expectedId: String, items: List<Pair<String, Double?>>, plan: RestParallelPlan? = null): RestControlOperation =
        if (id == expectedId && phase == "running" && steps.isEmpty())
            copy(steps = items.distinctBy { it.first }.map { RestControlStep(it.first, requested = it.second, lane = plan?.steps?.firstOrNull { step -> step.key == it.first }?.lane, dependencies = plan?.steps?.firstOrNull { step -> step.key == it.first }?.dependencies ?: emptyList()) }) else this

    fun progress(expectedId: String, key: String, next: String, actual: Double? = null, message: String = "", blockedBy: List<String> = emptyList()): RestControlOperation {
        if (id != expectedId || phase != "running") return this
        if (next in setOf("applied", "restored", "manualPreserved") && (actual == null || !actual.isFinite())) return this
        return copy(steps = steps.map { step ->
            if (step.key == key && step.permits(next, operation)) step.copy(state = next, actual = actual, message = message, blockedBy = blockedBy.toList()) else step
        })
    }

    /** A successful read before dispatch is distinct from verified post-write actual. */
    fun observedBefore(expectedId: String, key: String, value: Double): RestControlOperation {
        if (id != expectedId || phase != "running" || !value.isFinite()) return this
        return copy(steps = steps.map { step ->
            if (step.key == key && step.state == "checking" && step.before == null) step.copy(before = value) else step
        })
    }

    /** Position travel is measured, not elapsed time or a MOVE acknowledgement. */
    fun observedPosition(expectedId: String, key: String, start: Double, value: Double, at: Long): RestControlOperation {
        if (id != expectedId || phase != "running" || key !in setOf("seatRecline", "blind", "window") ||
            !start.isFinite() || !value.isFinite() || at <= 0) return this
        return copy(steps = steps.map { step ->
            val target = step.requested
            if (step.key != key || step.state !in setOf("checking", "writing", "verifying") ||
                target == null || !target.isFinite() || at < (step.observedAt ?: 0) ||
                value !in minOf(start, target)..maxOf(start, target)) step
            else step.copy(observed = value, observedAt = at,
                positionProgress = if (start == target) 1.0 else ((value - start) / (target - start)).coerceIn(0.0, 1.0))
        })
    }

    fun finish(expectedId: String, successful: Boolean): RestControlOperation =
        if (id == expectedId && phase == "running") {
            val confirmed = successful && steps.all { it.state in setOf("applied", "restored", "manualPreserved", "accepted") }
            copy(phase = if (confirmed) "complete" else "failed", steps = steps.map {
                if (it.terminal) it else it.copy(state = "failed", message = "작업이 중단되었습니다 · 차량 설정을 확인해 주세요")
            })
        } else this

    companion object {
        fun allConfirmed(operation: String, itemStates: Collection<String>): Boolean = when (operation) {
            "apply" -> itemStates.all { it == "applied" || it == "accepted" }
            "restore" -> itemStates.all { it == "restored" || it == "manualPreserved" || it == "accepted" }
            else -> false
        }
    }
}

/** Progress records SDK events; a write acknowledgement alone can never produce success. */
data class RestControlStep(
    val key: String,
    val state: String = "queued",
    val requested: Double? = null,
    val actual: Double? = null,
    val message: String = "",
    val before: Double? = null,
    val observed: Double? = null,
    val observedAt: Long? = null,
    val positionProgress: Double? = null,
    val lane: String? = null,
    val dependencies: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList()
) {
    val progressSource: String? get() = if (observed != null && positionProgress != null) "sdk-position" else null
    val terminal: Boolean get() = state in setOf("applied", "restored", "failed", "blocked", "manualPreserved", "accepted")
    fun permits(next: String, operation: String?): Boolean = !terminal && when (next) {
        "checking" -> state == "queued"
        "writing" -> state == "checking"
        "verifying" -> state == "writing"
        "applied" -> operation == "apply" && state == "verifying"
        "restored" -> operation == "restore" && state == "verifying"
        "manualPreserved" -> operation == "restore" && state == "checking"
        // SDK accepted the command but the vehicle did not report the new value back.
        "accepted" -> state == "verifying" || state == "writing"
        "blocked" -> state == "queued"
        "failed" -> true
        else -> false
    }
}
