package ai.kkaebi.app.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 카메라 없는 졸음 점수 (0~100). 메르세데스 Attention Assist처럼 출발 후 처음 [baselineMs] 동안을 "이 운전자의 평소"로 삼고
 * 그 뒤의 변화를 본다. 입력:
 *  - 조향 미세 흔들림: 직선 주행(|각도| 작음) 중 조향 각도 표준편차와 "큰 보정" 횟수 (기준선 대비)
 *  - 대답 지연·무응답: 질문 후 첫 발화까지 시간(기준선 대비), 최근 무응답 횟수
 *  - 속도 변동: 크루즈 아닐 때 속도 표준편차
 *  - 연속 운전 시간, 시간대(22~06시, 14~16시)
 *  - 차량 주의 경고등(DAW)·차선 보조 개입은 즉시 가중
 * 순수 클래스라 단위 테스트가 가능하다.
 */
class DrowsinessScorer(
    private val windowMs: Long = 30_000,
    private val baselineMs: Long = 90_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class S(val at: Long, val deg: Float)
    private data class V(val at: Long, val kmh: Float)
    private val steer = ArrayDeque<S>()
    private val speed = ArrayDeque<V>()
    private val latencies = ArrayDeque<Long>()          // 최근 대답 지연 ms
    private var missed = ArrayDeque<Long>()             // 무응답 시각
    private var baseSteerSd: Float? = null
    private var baseLatencyMs: Long? = null
    private var startedAt: Long = -1
    private var dawUntil = 0L
    private var lkaUntil = 0L
    private var lastFrame: Frame? = null

    var score: Int = 0; private set
    var level: Level = Level.L0; private set
    val baselineReady: Boolean get() = baseSteerSd != null
    val missedCount: Int get() = missed.count { it > now() - 300_000 }

    fun start(at: Long = now()) { startedAt = at }
    fun reset() { steer.clear(); speed.clear(); latencies.clear(); missed.clear(); baseSteerSd = null; baseLatencyMs = null; startedAt = -1; score = 0; level = Level.L0; lastFrame = null }

    fun onFrame(f: Frame) {
        lastFrame = f
        if (startedAt < 0) startedAt = f.atMs
        f.steeringDeg?.let { d -> if (abs(d) < 45f) steer.addLast(S(f.atMs, d)) }   // 직선 구간만
        f.speedKmh?.let { v -> if (!f.cruiseActive) speed.addLast(V(f.atMs, v)) }
        while (steer.isNotEmpty() && steer.first().at < f.atMs - windowMs) steer.removeFirst()
        while (speed.isNotEmpty() && speed.first().at < f.atMs - windowMs) speed.removeFirst()
        if (f.driverAttentionWarning) dawUntil = f.atMs + 120_000
        if (f.laneAssistIntervention) lkaUntil = f.atMs + 60_000
        if (baseSteerSd == null && f.atMs - startedAt >= baselineMs && steer.size >= 10) baseSteerSd = max(0.5f, sd(steer.map { it.deg }))
        recompute(f.atMs)
    }

    /** 질문에 대답했다. latencyMs = 말 끝난 뒤 첫 발화까지 */
    fun onAnswered(latencyMs: Long, at: Long = now()) {
        latencies.addLast(latencyMs); while (latencies.size > 6) latencies.removeFirst()
        if (baseLatencyMs == null && latencies.size >= 2) baseLatencyMs = max(800L, latencies.average().toLong())
        recompute(at)
    }

    fun onMissed(at: Long = now()) { missed.addLast(at); while (missed.size > 10) missed.removeFirst(); recompute(at) }

    /** 쉬고 난 뒤: 점수를 내리고 기준선은 유지 */
    fun onRested(at: Long = now()) { missed.clear(); latencies.clear(); dawUntil = 0; lkaUntil = 0; steer.clear(); speed.clear(); score = 0; level = Level.L0 }

    fun driveMinutes(at: Long = now()): Int = if (startedAt < 0) 0 else ((at - startedAt) / 60_000).toInt()

    private fun recompute(at: Long) {
        var s = 0f
        // 1) 조향 흔들림 (최대 35)
        val sdNow = if (steer.size >= 10) sd(steer.map { it.deg }) else 0f
        val base = baseSteerSd ?: 2.5f
        val ratio = sdNow / base
        s += (ratio - 1f).coerceIn(0f, 2.5f) / 2.5f * 25f
        s += (bigCorrections() * 3f).coerceAtMost(10f)
        // 2) 대답 (최대 35)
        val missedRecent = missed.count { it > at - 300_000 }
        s += (missedRecent * 14f).coerceAtMost(30f)
        val lat = latencies.lastOrNull()
        if (lat != null) { val bl = baseLatencyMs ?: 1500L; s += ((lat - bl).toFloat() / 3000f).coerceIn(0f, 1f) * 12f }
        // 3) 속도 출렁임 (최대 10)
        if (speed.size >= 10) s += (sd(speed.map { it.kmh }) / 8f).coerceIn(0f, 1f) * 10f
        // 4) 운전 시간·시간대 (최대 15)
        val m = driveMinutes(at)
        s += when { m >= 150 -> 10f; m >= 120 -> 8f; m >= 90 -> 5f; m >= 60 -> 2f; else -> 0f }
        val h = java.util.Calendar.getInstance().apply { timeInMillis = at }.get(java.util.Calendar.HOUR_OF_DAY)
        if (h >= 22 || h < 6) s += 5f else if (h in 14..15) s += 3f
        // 5) 차량이 직접 본 것
        if (at < dawUntil) s = max(s, 60f) + 10f
        if (at < lkaUntil) s += 12f
        score = min(100, s.toInt())
        level = nextLevel(level, score)
    }

    /** 큰 보정: 2초 안에 각도가 8° 넘게 튄 횟수 (졸음의 전형: 가만히 있다가 크게 되잡음) */
    private fun bigCorrections(): Int {
        var n = 0; var prev: S? = null
        for (s in steer) { if (prev != null && abs(s.deg - prev.deg) > 8f && s.at - prev.at < 2000) n++; prev = s }
        return n
    }

    private fun sd(xs: List<Float>): Float { if (xs.size < 2) return 0f; val m = xs.average().toFloat(); return sqrt(xs.sumOf { ((it - m) * (it - m)).toDouble() } / (xs.size - 1)).toFloat() }

    companion object {
        /** 히스테리시스: 올라갈 때와 내려갈 때 문턱이 다르다 (깜빡임 방지) */
        fun nextLevel(cur: Level, score: Int): Level = when (cur) {
            Level.REST -> Level.REST
            Level.L0 -> if (score >= 70) Level.L3 else if (score >= 55) Level.L2 else if (score >= 40) Level.L1 else Level.L0
            Level.L1 -> if (score >= 70) Level.L3 else if (score >= 55) Level.L2 else if (score < 30) Level.L0 else Level.L1
            Level.L2 -> if (score >= 70) Level.L3 else if (score < 45) Level.L1 else Level.L2
            Level.L3 -> if (score < 55) Level.L2 else Level.L3
        }
    }
}
