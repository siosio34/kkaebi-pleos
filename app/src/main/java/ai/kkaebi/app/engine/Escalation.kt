package ai.kkaebi.app.engine

/** 차 안 장치로 깨우는 행동. 운전을 방해하지 않는 것만, 짧게 */
sealed class Action(val label: String) {
    data class Ambient(val color: Int, val brightness: Int, val blink: Boolean = false) : Action("앰비언트 ${if (blink) "깜빡" else "밝게"}")
    data class Climate(val fan: Int, val tempC: Float, val direction: String, val recirc: Boolean = false) : Action("에어컨 강풍 ${tempC.toInt()}°C")
    data class Window(val pct: Int) : Action("창문 ${pct}% 열기")
    data class SeatVent(val level: Int) : Action("시트 통풍 ${level}단")
    data object SteeringHeatOff : Action("핸들 열선 끄기")
    data object WiperOnce : Action("와이퍼 1회")
    data object WarnTone : Action("경고음")
    data class Say(val text: String) : Action("음성 안내")
    data object GuideToRestArea : Action("쉼터 경유지 추가")
}

/** 사용자가 켤 때 고르는 자극 범위 (기본: 공조·조명·음성만. 창문·와이퍼는 옵션) */
data class StimulusSettings(val window: Boolean = false, val wiper: Boolean = false, val seatVent: Boolean = true, val rainy: Boolean = false)

object Escalation {
    fun plan(level: Level, s: StimulusSettings): List<Action> = when (level) {
        Level.L0, Level.REST -> emptyList()
        Level.L1 -> listOf(Action.Ambient(color = 0, brightness = 85), Action.Say("대답이 늦네요. 눈 뜨고 있죠? 문제 하나 더 갈게요."))
        Level.L2 -> buildList {
            add(Action.Say("시원하게 할게요."))
            add(Action.Climate(fan = 7, tempC = 18f, direction = "FACE"))
            if (s.seatVent) add(Action.SeatVent(2))
            add(Action.SteeringHeatOff)
            if (s.window && !s.rainy) add(Action.Window(10))
            add(Action.Ambient(color = 0, brightness = 100))
        }
        Level.L3 -> buildList {
            add(Action.WarnTone)
            if (s.wiper && !s.rainy) add(Action.WiperOnce)
            add(Action.Ambient(color = 2, brightness = 100, blink = true))
            add(Action.Say("위험해요. 가까운 졸음쉼터로 안내할게요. 20분만 쉬고 가요."))
            add(Action.GuideToRestArea)
        }
    }

    /** 휴식 모드 설정 (기어 P에서만). 소리·알람은 앱이 직접 */
    data class RestPlan(val tempC: Float = 23f, val fan: Int = 1, val ambientBrightness: Int = 0, val lockWindows: Boolean = true, val closeBlinds: Boolean = true, val reclineSec: Int = 4, val napMinutes: Int = 20, val sound: String = "brown")
}
