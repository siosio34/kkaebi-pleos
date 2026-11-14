package ai.kkaebi.app.rest

import kotlinx.coroutines.*

/** Testable motion loop. Delays are SDK sampling/watchdog intervals, never presentation timing. */
class RestMotionRunner(private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val watchdogDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    suspend fun run(start: Double, target: Double, minimum: Double, maximum: Double,
        guard: () -> Unit, read: suspend () -> Double, move: suspend (Int) -> Unit, stop: suspend () -> Unit,
        stopped: (Boolean) -> Unit = {}, confirmedPosition: (Double) -> Unit = {},
        observedPosition: (Double) -> Unit = {}) {
        val direction = RestMotionPolicy.direction(start, target)
        if(direction == 0) return
        var changedAt = now()
        var previous = start
        var acknowledged = false
        var reached = false
        try {
            withTimeout(RestMotionPolicy.TOTAL_TIMEOUT_MS) {
                while(!reached) {
                    guard()
                    val pulseStart = now()
                    acknowledged = false
                    val watchdog = launch(watchdogDispatcher) { delay(RestMotionPolicy.PULSE_TIMEOUT_MS); stop() }
                    try {
                        withTimeout(500) { move(direction) }
                        acknowledged = true
                        while(now() - pulseStart < RestMotionPolicy.PULSE_TIMEOUT_MS) {
                            guard()
                            val current = withTimeout(400) { read() }
                            val outcome = RestMotionPolicy.observation(start, previous, current, target, minimum, maximum)
                            check(outcome !in setOf("invalid", "opposite", "overshot")) { "이동 위치 이상($outcome)으로 정지했습니다" }
                            guard()
                            observedPosition(current)
                            if(current != previous) changedAt = now()
                            previous = current
                            check(now() - changedAt < RestMotionPolicy.NO_PROGRESS_MS) { "위치 변화가 없어 이동을 정지했습니다" }
                            if(outcome == "reached") { reached = true; break }
                            delay(100)
                        }
                    } finally {
                        withContext(NonCancellable) { watchdog.cancelAndJoin(); stop() }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val result = runCatching { stop() }
                stopped(result.isSuccess)
                if(result.isSuccess && acknowledged && reached) {
                    val stableResult = runCatching {
                        val actual = withTimeout(500) { read() }
                        delay(100)
                        val stable = withTimeout(500) { read() }
                        check(actual == stable && stable.isFinite() && stable in minimum..maximum)
                        check(stable == target) { "정지 후 목표 위치 불일치" }
                        confirmedPosition(stable)
                        stable
                    }
                    if(reached) check(stableResult.getOrNull() == target) { "정지 후 목표 위치가 안정적으로 확인되지 않았습니다" }
                }
                if(result.isFailure) throw IllegalStateException("STOP 응답 미확인 · 차량에서 즉시 직접 정지해 주세요", result.exceptionOrNull())
            }
        }
    }
}
