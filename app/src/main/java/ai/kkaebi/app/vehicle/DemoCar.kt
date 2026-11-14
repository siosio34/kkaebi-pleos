package ai.kkaebi.app.vehicle

import ai.kkaebi.app.engine.Action
import ai.kkaebi.app.engine.Escalation
import ai.kkaebi.app.engine.Frame
import ai.kkaebi.app.engine.Gear
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pleos 서비스가 없을 때의 가상 차. 야간 고속도로를 100km/h로 달리며, "졸음 주입"을 켜면
 * 조향이 가만히 있다가 크게 되잡는 패턴과 속도 출렁임이 커진다 (졸음의 전형). 제어는 로그로 남긴다.
 */
class DemoCar : Car {
    override val name = "데모 차량 (야간 고속도로)"
    override val isDemo = true
    override var connected = false; private set
    private val _frame = MutableStateFlow(Frame(System.currentTimeMillis(), 100f, 0f, Gear.DRIVE, false))
    override val frame: StateFlow<Frame> = _frame
    val actionLog = MutableStateFlow<List<String>>(emptyList())
    @Volatile var drowsy = false
    @Volatile var gear = Gear.DRIVE
    @Volatile var passenger = false
    @Volatile var dawWarning = false
    private var job: Job? = null
    private val rnd = Random(3)

    override suspend fun start() {
        connected = true
        job?.cancel()
        job = CoroutineScope(Dispatchers.Default).launch {
            var t = 0.0; var lane = 0f
            while (true) {
                t += 0.25
                val driving = gear == Gear.DRIVE
                val base = if (driving) (sin(t * 0.7) * 1.2f).toFloat() + rnd.nextFloat() * 0.8f - 0.4f else 0f
                val wobble = if (drowsy && driving) {
                    // 1.5초마다 한 번씩 크게 되잡는 보정 + 느린 드리프트
                    lane += (rnd.nextFloat() - 0.5f) * 0.6f
                    if (rnd.nextFloat() < 0.18f) lane * -3.5f else lane
                } else 0f
                val speed = if (!driving) 0f else 100f + (if (drowsy) (sin(t * 0.3) * 9f).toFloat() + rnd.nextFloat() * 4f else rnd.nextFloat() * 1.5f)
                _frame.value = Frame(System.currentTimeMillis(), speed, base + wobble, gear, passenger, driverAttentionWarning = dawWarning)
                delay(250)
            }
        }
    }
    override fun stop() { job?.cancel(); connected = false }

    private fun log(s: String) { actionLog.value = (actionLog.value + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date())} $s").takeLast(12) }

    override suspend fun perform(a: Action): Result<Unit> { delay(150); log(a.label); return Result.success(Unit) }
    override suspend fun enterRest(plan: Escalation.RestPlan): List<String> {
        val steps = listOf("창문 닫기", "창문 잠금", "문 잠금", "블라인드", "공조 ${plan.tempC.toInt()}°C 약풍", "조명 끄기", "등받이 눕히기")
        steps.forEach { delay(400); log("휴식: $it") }; return steps
    }
    override suspend fun exitRest(): List<String> {
        val steps = listOf("조명 서서히 밝게", "등받이 세우기", "환기", "문 잠금 해제"); steps.forEach { delay(500); log("복귀: $it") }; return steps
    }
}
