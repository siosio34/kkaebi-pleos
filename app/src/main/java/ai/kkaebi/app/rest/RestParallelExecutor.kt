package ai.kkaebi.app.rest

import kotlinx.coroutines.*

data class RestParallelStep(val key: String, val lane: String, val dependencies: List<String> = emptyList())

data class RestParallelPlan(val steps: List<RestParallelStep>, val powerOnSelected: Boolean) {
    val lanes: List<List<RestParallelStep>> get() = listOf("motor", "environment", "hvac").map { lane ->
        steps.filter { it.lane == lane && !(it.key == "hvacPower" && !powerOnSelected) }
    }
    val finalPowerOff: RestParallelStep? get() = steps.firstOrNull { it.key == "hvacPower" && !powerOnSelected }

    companion object {
        fun create(targets: Map<String, Double?>, restoring: Boolean = false): RestParallelPlan {
            require(targets.keys.all { it in setOf("seatRecline", "blind", "window", "brightness", "seatVentilation", "hvacPower", "temperature", "fan") })
            val on = targets["hvacPower"] == 1.0
            val motors = if (restoring) listOf("window", "blind", "seatRecline") else listOf("seatRecline", "blind", "window")
            val steps = (motors + listOf("brightness", "seatVentilation", "hvacPower", "temperature", "fan"))
                .filter { it in targets }.map { key ->
                    RestParallelStep(key, when (key) {
                        "seatRecline", "blind", "window" -> "motor"
                        "brightness", "seatVentilation" -> "environment"
                        else -> "hvac"
                    }, when {
                        key in setOf("temperature", "fan") -> listOf("hvacPower")
                        key == "hvacPower" && !on -> targets.keys.filter { it != "hvacPower" }.sorted()
                        else -> emptyList()
                    })
                }
            return RestParallelPlan(steps, on)
        }
    }
}

/** Three independent lanes; a lane never has more than one SDK item in flight. */
class RestParallelExecutor {
    suspend fun execute(plan: RestParallelPlan, guard: () -> Unit,
        currentPowerConfirmed: suspend () -> Boolean,
        blocked: (String, List<String>, String) -> Unit,
        executeItem: suspend (String) -> Boolean) = coroutineScope {
        // Revoke outstanding reads promptly on alarm/epoch loss, even before their callbacks arrive.
        val watcher = launch { while (isActive) { delay(50); guard() } }
        try {
            plan.lanes.map { lane -> async {
                var powerReady: Boolean? = null
                for (step in lane) {
                    ensureActive(); guard()
                    if (step.key in setOf("temperature", "fan")) {
                        if (powerReady == null) powerReady = currentPowerConfirmed()
                        if (powerReady != true) {
                            blocked(step.key, listOf("hvacPower"), "공조 전원 켜짐을 확인하지 못해 후속 설정을 요청하지 않았습니다")
                            continue
                        }
                    }
                    val confirmed = executeItem(step.key)
                    if (step.key == "hvacPower") powerReady = confirmed
                }
            } }.awaitAll()
            // OFF is an ordering barrier, not a success dependency: every submitted lane has settled.
            plan.finalPowerOff?.let { ensureActive(); guard(); executeItem(it.key) }
        } finally { watcher.cancelAndJoin() }
    }
}

/** Cancellation of the user's authority must abort siblings, not look like one device failure. */
class RestOperationRevoked : IllegalStateException("알람 또는 현재 휴식 동의가 종료되어 전체 설정을 중단했습니다")
