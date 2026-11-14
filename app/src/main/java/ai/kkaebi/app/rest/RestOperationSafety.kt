package ai.kkaebi.app.rest

/** A safety interruption ends the whole confirmed sequence, even if P returns later. */
class RestOperationInterrupted : IllegalStateException("정차 신호가 끊겨 전체 작업을 중단했습니다 · 다시 확인해 주세요")

class RestOperationSafety {
    private var active = false
    private var interrupted = false

    @Synchronized fun begin() {
        check(!active) { "이전 작업이 아직 종료되지 않았습니다" }
        active = true
        interrupted = false
    }

    @Synchronized fun observe(safe: Boolean): Boolean {
        if (active && !safe) interrupted = true
        return active && interrupted
    }

    @Synchronized fun requireSafe(safe: Boolean) {
        if (observe(safe) || !safe) throw RestOperationInterrupted()
    }

    @Synchronized fun finish() { active = false }
}
