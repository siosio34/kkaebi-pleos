package ai.kkaebi.app.engine

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차가 보내는 신호 한 프레임 (약 2~5Hz). 카메라 없이 얻을 수 있는 것만 */
data class Frame(
    val atMs: Long,
    val speedKmh: Float?,       // AAOS PERF_VEHICLE_SPEED
    val steeringDeg: Float?,    // Vehicle SDK SteeringWheelAngle (±540°)
    val gear: Gear,
    val passengerSeated: Boolean,
    val cruiseActive: Boolean = false,
    /** 차량 주의 경고(DAW) 텔테일이 켜졌는지. 텔테일 API가 없으면 false */
    val driverAttentionWarning: Boolean = false,
    val laneAssistIntervention: Boolean = false,
)

enum class Level(val label: String, val face: String) {
    L0("괜찮아요", "😊"), L1("조금 졸린가요?", "🙂"), L2("깨울게요", "😟"), L3("쉼터로 가요", "😱"), REST("쉬는 중", "😴")
}
