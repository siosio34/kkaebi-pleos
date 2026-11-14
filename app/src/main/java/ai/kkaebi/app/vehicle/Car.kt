package ai.kkaebi.app.vehicle

import ai.kkaebi.app.engine.Action
import ai.kkaebi.app.engine.Escalation
import ai.kkaebi.app.engine.Frame
import kotlinx.coroutines.flow.StateFlow

/** 차 신호 + 차 제어. 실차/에뮬레이터는 Pleos SDK, 아니면 데모 */
interface Car {
    val name: String
    val isDemo: Boolean
    val frame: StateFlow<Frame>
    val connected: Boolean
    suspend fun start()
    fun stop()
    /** 깨우기 행동 하나. Say/WarnTone/GuideToRestArea는 앱이 처리하므로 여기 오지 않는다 */
    suspend fun perform(a: Action): Result<Unit>
    /** 휴식 모드 들어가기 / 나오기 (기어 P 확인은 호출자가) */
    suspend fun enterRest(plan: Escalation.RestPlan): List<String>
    suspend fun exitRest(): List<String>
}
