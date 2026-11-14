package ai.kkaebi.app

import ai.kkaebi.app.engine.Action
import ai.kkaebi.app.engine.DrowsinessScorer
import ai.kkaebi.app.engine.Escalation
import ai.kkaebi.app.engine.Frame
import ai.kkaebi.app.engine.Gear
import ai.kkaebi.app.engine.Level
import ai.kkaebi.app.engine.Question
import ai.kkaebi.app.engine.QuestionPicker
import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.engine.RestAreas
import ai.kkaebi.app.engine.StimulusSettings
import ai.kkaebi.app.engine.TripLog
import ai.kkaebi.app.navi.NaviBridge
import ai.kkaebi.app.speech.AmbientSound
import ai.kkaebi.app.speech.Ears
import ai.kkaebi.app.speech.Voice
import ai.kkaebi.app.vehicle.Car
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Phase { OFF, COMPANION, ASKING, LISTENING, WAKING, GUIDING, RESTING, WAKEUP, REPORT }

data class SessionState(
    val phase: Phase = Phase.OFF,
    val level: Level = Level.L0,
    val score: Int = 0,
    val baselineReady: Boolean = false,
    val lastQuestion: String? = null,
    val lastAnswer: String? = null,
    val lastLatencyMs: Long? = null,
    val nextPromptInSec: Int = 0,
    val restArea: RestArea? = null,
    val restAreaM: Double? = null,
    val restRemainingSec: Int = 0,
    val restSteps: List<String> = emptyList(),
    val lastActions: List<String> = emptyList(),
    val driveMinutes: Int = 0,
    val missed: Int = 0,
    val statusLine: String = "졸음 모드가 꺼져 있어요",
)

/**
 * 한 트립의 두뇌. 차 신호 → 점수 → 단계 → (대화 간격·깨우기·쉼터 안내·휴식) 를 한 코루틴 루프로 돌린다.
 * 사용자 결정 반영: 깨우기 자극은 3초 안에 끝나고, "그만"이라고 말하면 멈추며, 휴식 모드는 기어 P에서만 들어간다.
 */
class Session(
    private val scope: CoroutineScope,
    private val car: Car,
    private val voice: Voice,
    private val ears: Ears,
    private val sound: AmbientSound,
    private val navi: NaviBridge?,
    private val questions: List<Question>,
    private val restAreas: List<RestArea>,
    val demo: Boolean,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state
    val log = TripLog()
    val scorer = DrowsinessScorer(baselineMs = if (demo) 20_000 else 90_000)
    var settings = StimulusSettings()
    private val picker = QuestionPicker(questions)
    private var loop: Job? = null
    private var frameJob: Job? = null
    private var nextPromptAt = 0L
    private var lastLevel = Level.L0
    private var answerDeferred: ((String?, Long) -> Unit)? = null
    private var restJob: Job? = null
    private var stopRequested = false

    private fun update(f: (SessionState) -> SessionState) { _state.value = f(_state.value) }

    fun start() {
        if (loop != null) return
        val now = System.currentTimeMillis()
        scorer.reset(); scorer.start(now); log.start(now)
        update { SessionState(phase = Phase.COMPANION, statusLine = "같이 가요. 잠깐 평소 운전을 볼게요") }
        nextPromptAt = now + (if (demo) 6_000 else 60_000)
        frameJob = scope.launch { car.frame.collect { onFrame(it) } }
        loop = scope.launch { mainLoop() }
        voice.speak(if (demo) "졸음 모드 켰어요. 제가 가끔 말을 걸 테니 대답해 주세요." else "졸음 모드 켰어요. 잠깐 평소 운전 패턴을 보고, 그 뒤부터 가끔 말을 걸게요.")
    }

    fun stop() {
        loop?.cancel(); frameJob?.cancel(); restJob?.cancel(); loop = null; frameJob = null
        ears.cancel(); sound.stop()
        update { it.copy(phase = Phase.REPORT, statusLine = "트립이 끝났어요") }
    }

    private fun onFrame(f: Frame) {
        scorer.onFrame(f)
        val s = _state.value
        if (s.phase == Phase.RESTING || s.phase == Phase.WAKEUP) return
        log.sample(f.atMs, scorer.score, scorer.level)
        update { it.copy(score = scorer.score, level = scorer.level, baselineReady = scorer.baselineReady, driveMinutes = scorer.driveMinutes(f.atMs), missed = scorer.missedCount) }
    }

    private suspend fun mainLoop() {
        while (true) {
            delay(500)
            val s = _state.value
            val now = System.currentTimeMillis()
            if (s.phase == Phase.RESTING || s.phase == Phase.WAKEUP || s.phase == Phase.REPORT) continue
            // 단계가 올라가면 깨우기
            val lv = scorer.level
            if (lv != lastLevel) {
                if (lv.ordinal > lastLevel.ordinal && lv != Level.REST) escalate(lv)
                lastLevel = lv
                nextPromptAt = minOf(nextPromptAt, now + 3000)
            }
            // 정차(P)했고 L2 이상이면 휴식 제안
            if (car.frame.value.gear == Gear.PARK && lv.ordinal >= Level.L2.ordinal && s.phase != Phase.ASKING && s.phase != Phase.LISTENING) {
                offerRest(); continue
            }
            if (s.phase == Phase.COMPANION || s.phase == Phase.GUIDING) {
                update { it.copy(nextPromptInSec = ((nextPromptAt - now) / 1000).toInt().coerceAtLeast(0), statusLine = statusFor(it)) }
                if (now >= nextPromptAt && car.frame.value.gear == Gear.DRIVE) ask()
            }
        }
    }

    private fun statusFor(s: SessionState): String = when (s.level) {
        Level.L0 -> if (!s.baselineReady) "평소 운전을 보는 중… ${s.nextPromptInSec}초 뒤 말 걸게요" else "${s.nextPromptInSec}초 뒤 말 걸게요 · 운전 ${s.driveMinutes}분"
        Level.L1 -> "조금 느려졌어요. 자주 말 걸게요"
        Level.L2 -> "시원하게 했어요. 쉼터 찾는 중"
        Level.L3 -> s.restArea?.let { "${it.name}까지 ${((s.restAreaM ?: 0.0) / 1000).let { k -> "%.1f".format(k) }}km" } ?: "가까운 쉼터로 안내 중"
        Level.REST -> "쉬는 중"
    }

    // ---------------------------------------------------------------- 대화
    private suspend fun ask() {
        val q = picker.next(scorer.level)
        log.prompts++
        update { it.copy(phase = Phase.ASKING, lastQuestion = q.q, lastAnswer = null, lastLatencyMs = null) }
        val spokenAt = System.currentTimeMillis()
        voice.speak(q.q) { listen(q) }
        // 말이 끝나지 않아도 20초 지나면 다음으로
        scope.launch { delay(20_000); if (_state.value.phase == Phase.ASKING && _state.value.lastQuestion == q.q && System.currentTimeMillis() - spokenAt > 19_000) listen(q) }
    }

    private fun listen(q: Question) {
        if (_state.value.phase != Phase.ASKING) return
        update { it.copy(phase = Phase.LISTENING) }
        val handler: (String?, Long) -> Unit = { text, lat -> onAnswer(q, text, lat) }
        answerDeferred = handler
        val opened = ears.listen(QuestionPicker.ANSWER_TIMEOUT_MS) { t, l -> if (answerDeferred === handler) { answerDeferred = null; handler(t, l) } }
        if (!opened) {
            // 마이크 없음(에뮬레이터): 화면 버튼으로 대답을 흉내 낸다. 7초 안에 안 누르면 무응답
            scope.launch { delay(QuestionPicker.ANSWER_TIMEOUT_MS); if (answerDeferred === handler) { answerDeferred = null; handler(null, -1) } }
        }
    }

    /** 화면 버튼("대답했어요"/"무응답")용 */
    fun simulateAnswer(text: String?, latencyMs: Long) { val h = answerDeferred ?: return; answerDeferred = null; ears.cancel(); h(text, latencyMs) }

    private fun onAnswer(q: Question, text: String?, latencyMs: Long) {
        val now = System.currentTimeMillis()
        if (text != null && text.contains("그만")) { voice.speak("알겠어요. 조용히 갈게요."); nextPromptAt = now + 180_000; update { it.copy(phase = Phase.COMPANION, lastAnswer = text) }; return }
        if (text == null) {
            scorer.onMissed(now); log.missed++; log.event(now, "무응답")
            update { it.copy(phase = Phase.COMPANION, lastAnswer = "(대답 없음)", lastLatencyMs = null, missed = scorer.missedCount) }
            voice.speak("대답이 없네요. 괜찮아요?")
            nextPromptAt = now + minOf(QuestionPicker.intervalMs(scorer.level, demo), if (demo) 8_000 else 25_000)
            return
        }
        val ok = q.accepts(text)
        scorer.onAnswered(latencyMs.coerceAtLeast(0), now); log.answered++
        update { it.copy(phase = Phase.COMPANION, lastAnswer = text, lastLatencyMs = latencyMs) }
        voice.speak(QuestionPicker.praise(ok, latencyMs))
        nextPromptAt = now + QuestionPicker.intervalMs(scorer.level, demo)
    }

    // ---------------------------------------------------------------- 깨우기
    private suspend fun escalate(level: Level) {
        val plan = Escalation.plan(level, settings.copy(rainy = settings.rainy))
        if (plan.isEmpty()) return
        log.escalations++; log.event(System.currentTimeMillis(), "${level.name} 깨우기")
        update { it.copy(phase = Phase.WAKING, lastActions = emptyList()) }
        val done = mutableListOf<String>()
        for (a in plan) {
            when (a) {
                is Action.Say -> voice.speak(a.text)
                Action.WarnTone -> voice.warnTone()
                Action.GuideToRestArea -> guideToRestArea()
                else -> car.perform(a).onSuccess { done += a.label }.onFailure { done += "${a.label} 실패" }
            }
            update { it.copy(lastActions = done.toList()) }
        }
        update { it.copy(phase = if (level == Level.L3) Phase.GUIDING else Phase.COMPANION) }
    }

    private fun guideToRestArea() {
        val lat = navi?.lat ?: DEMO_LAT; val lng = navi?.lng ?: DEMO_LNG
        val found = RestAreas.nearest(restAreas, lat, lng, navi?.heading)
        if (found == null) { voice.speak("가까운 쉼터를 못 찾았어요. 다음 휴게소에서 꼭 쉬어 가요."); return }
        val (ra, m) = found
        val added = navi?.addWaypoint(ra) ?: false
        log.event(System.currentTimeMillis(), "쉼터 안내: ${ra.name} ${(m / 1000).toInt()}km${if (added) " (경유지 추가)" else ""}")
        update { it.copy(restArea = ra, restAreaM = m) }
        voice.speak("${"%.1f".format(m / 1000)}킬로미터 앞 ${ra.name}${if (ra.toilet) ", 화장실 있어요" else ""}. ${if (added) "경유지로 넣었어요." else "내비에서 경유지로 잡아 주세요."}")
    }

    // ---------------------------------------------------------------- 휴식
    private var restOffered = false
    private suspend fun offerRest() {
        if (restOffered) return
        restOffered = true
        voice.speak("정차했네요. 휴식 모드로 바꿀까요? 창문 닫고 조명 끄고 20분 알람을 맞출게요.")
        update { it.copy(statusLine = "휴식 모드로 바꿀까요?") }
    }

    fun startRest(minutes: Int = if (demo) 1 else 20) {
        if (car.frame.value.gear != Gear.PARK) { voice.speak("정차한 뒤에 쉬어요."); return }
        restJob?.cancel()
        restJob = scope.launch {
            update { it.copy(phase = Phase.RESTING, level = Level.REST, restRemainingSec = minutes * 60, restSteps = emptyList()) }
            log.rests++; log.event(System.currentTimeMillis(), "휴식 ${minutes}분")
            val steps = car.enterRest(Escalation.RestPlan(napMinutes = minutes))
            update { it.copy(restSteps = steps) }
            sound.play("brown")
            voice.speak("${minutes}분 뒤에 깨울게요. 좋은 꿈 꾸세요.")
            val total = if (demo) 30 else minutes * 60
            for (s in total downTo 1) { update { it.copy(restRemainingSec = s) }; delay(1000) }
            wakeUp()
        }
    }

    fun skipRest() { restJob?.cancel(); scope.launch { wakeUp() } }

    private suspend fun wakeUp() {
        update { it.copy(phase = Phase.WAKEUP, statusLine = "일어날 시간이에요") }
        sound.stop()
        val steps = car.exitRest()
        voice.speak("잘 쉬었어요? 오늘 날짜가 며칠인지 말해 보세요.")
        update { it.copy(restSteps = steps) }
        scorer.onRested(); restOffered = false; lastLevel = Level.L0
        delay(if (demo) 4000 else 15_000)
        update { it.copy(phase = Phase.COMPANION, level = Level.L0, score = 0, restArea = null, restAreaM = null, statusLine = "다시 출발해요") }
        nextPromptAt = System.currentTimeMillis() + (if (demo) 10_000 else 120_000)
    }

    companion object { const val DEMO_LAT = 36.80; const val DEMO_LNG = 127.20 }  // 경부선 천안 부근
}
