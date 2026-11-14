package ai.kkaebi.app.vehicle

import ai.kkaebi.app.engine.Action
import ai.kkaebi.app.engine.Escalation
import ai.kkaebi.app.engine.Frame
import ai.kkaebi.app.engine.Gear
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car as AndroidCar
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos Vehicle SDK + AAOS 속도. 조향각·기어·동승석 착석은 SDK 리스너, 속도는 CarPropertyManager.
 * 제어는 HVAC·시트 통풍·창문·앰비언트·핸들 열선·와이퍼·문 잠금·블라인드·시트 등받이(상대 이동).
 */
class PleosCar(context: Context) : Car {
    private val ctx = context.applicationContext
    private val vehicle = Vehicle(ctx)
    private var androidCar: AndroidCar? = null
    private val _frame = MutableStateFlow(Frame(System.currentTimeMillis(), null, null, Gear.UNKNOWN, false))
    override val frame: StateFlow<Frame> = _frame
    override val name = "Pleos Connect"
    override val isDemo = false
    @Volatile override var connected = false; private set

    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val passenger = SeatArea(SeatValue.ROW_1_RIGHT)
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val windows = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).map { WindowArea(it) }

    @Volatile private var speed: Float? = null
    @Volatile private var speedAt = 0L

    private fun push(f: (Frame) -> Frame) { _frame.value = f(_frame.value).copy(atMs = System.currentTimeMillis()) }

    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { push { it.copy(steeringDeg = angle) } }
        override fun onFailed(e: Exception) = log(e)
    }
    private val gearL = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { push { it.copy(gear = gear.toGear()) } }
        override fun onFailed(e: Exception) = log(e)
    }
    private val seatL = object : SeatOccupancyListener {
        /** Vehicle 2.3.2 + 에뮬레이터는 착석 값이 반대로 온다(출품작 softride 기록). 실차에서 다르면 상수를 바꾼다 */
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) { push { it.copy(passengerSeated = if (INVERTED_OCCUPANCY) occupancy == false else occupancy == true) } }
        override fun onFailed(e: Exception) = log(e)
    }
    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { speed = kotlin.math.abs(it) * 3.6f; speedAt = System.currentTimeMillis(); push { f -> f.copy(speedKmh = speed) } }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {}
    }

    override suspend fun start() { withContext(Dispatchers.IO) {
        vehicle.initialize()
        // 일반 AAOS 이미지에서는 initialize()가 통과해도 권한이 없어 모든 호출이 실패한다 → 기어 한 번 읽어 확인
        val gear = await<VehicleGear> { ok, f -> vehicle.getGear().getCurrentGearSelectionState(ok, f) }
        push { it.copy(gear = gear.toGear()) }
        connected = true
        safe { vehicle.getGear().registerGearSelection(gearL) }
        safe { vehicle.getSteeringWheel().registerSteeringWheelAngle(steerL) }
        safe { vehicle.getSeat().registerSeatOccupancy(passenger, seatL) }
        safe {
            androidCar = AndroidCar.createCar(ctx)
            (androidCar?.getCarManager(AndroidCar.PROPERTY_SERVICE) as? CarPropertyManager)?.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
    } }

    override fun stop() {
        safe { vehicle.getGear().unregisterGearSelection(gearL) }
        safe { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
        safe { vehicle.getSeat().unregisterSeatOccupancy(passenger, seatL) }
        safe { (androidCar?.getCarManager(AndroidCar.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(speedCb); androidCar?.disconnect() }
        safe { vehicle.release() }
        connected = false
    }

    override suspend fun perform(a: Action): Result<Unit> = runCatching {
        withTimeout(5000) {
            val hvac = vehicle.getHVAC(); val light = vehicle.getLight()
            when (a) {
                is Action.Ambient -> {
                    set2<SeatArea, LightSwitch> { ok, f -> light.setAmbientLightSwitch(driver, LightSwitch.ON, ok, f) }
                    set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightnessColor(driver, a.color, ok, f) }
                    if (a.blink) repeat(2) {
                        set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightness(driver, 100, ok, f) }; delay(350)
                        set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightness(driver, 10, ok, f) }; delay(350)
                    }
                    set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightness(driver, a.brightness, ok, f) }
                }
                is Action.Climate -> {
                    set2<SeatArea, Boolean> { ok, f -> hvac.setHvacPower(driver, true, ok, f) }
                    set2<SeatArea, Boolean> { ok, f -> hvac.setHvacAc(driver, true, ok, f) }
                    set2<SeatArea, Boolean> { ok, f -> hvac.setHvacAuto(driver, false, ok, f) }
                    set2<SeatArea, Float> { ok, f -> hvac.setHvacTemperature(driver, a.tempC, TemperatureUnit.CELSIUS, ok, f) }
                    set2<SeatArea, FanDirectionType> { ok, f -> hvac.setHvacFanDirection(driver, runCatching { FanDirectionType.valueOf(a.direction) }.getOrDefault(FanDirectionType.FACE), ok, f) }
                    set2<SeatArea, Int> { ok, f -> hvac.setHvacFanSpeed(driver, a.fan, ok, f) }
                    set2<SeatArea, Boolean> { ok, f -> hvac.setHvacRecirculation(driver, a.recirc, ok, f) }
                }
                is Action.Window -> set2<WindowArea, Int> { ok, f -> vehicle.getWindow().setWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), a.pct, ok, f) }
                is Action.SeatVent -> set2<SeatArea, Int> { ok, f -> vehicle.getSeat().setHvacSeatVentilation(driver, a.level, ok, f) }
                Action.SteeringHeatOff -> await<Int> { ok, f -> vehicle.getSteeringWheel().setHvacSteeringWheelHeat(0, ok, f) }
                Action.WiperOnce -> {
                    set2<WindowArea, WiperSwitch> { ok, f -> vehicle.getWiper().setWiperSwitch(front, WiperSwitch.MIST, ok, f) }; delay(1500)
                    set2<WindowArea, WiperSwitch> { ok, f -> vehicle.getWiper().setWiperSwitch(front, WiperSwitch.OFF, ok, f) }
                }
                Action.WarnTone, is Action.Say, Action.GuideToRestArea -> Unit
            }
        }
        Unit
    }.onFailure(::log)

    override suspend fun enterRest(plan: Escalation.RestPlan): List<String> {
        val done = mutableListOf<String>()
        val hvac = vehicle.getHVAC(); val win = vehicle.getWindow(); val light = vehicle.getLight(); val seat = vehicle.getSeat()
        suspend fun step(label: String, block: suspend () -> Unit) { runCatching { withTimeout(5000) { block() }; done += label }.onFailure(::log) }
        windows.forEach { w -> step("창문 닫기") { set2<WindowArea, Int> { ok, f -> win.setWindowPosition(w, 0, ok, f) } } }
        if (plan.lockWindows) step("창문 잠금") { set2<WindowArea, Boolean> { ok, f -> win.setWindowLock(WindowArea(WindowValue.ROW_2_LEFT), true, ok, f) } }
        step("문 잠금") { set2<ai.pleos.playground.vehicle.api.model.area.DoorArea, Boolean> { ok, f -> vehicle.getDoor().setDoorLock(ai.pleos.playground.vehicle.api.model.area.DoorArea(ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_1_LEFT), true, ok, f) } }
        if (plan.closeBlinds) listOf(WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).forEach { v ->
            step("블라인드") { set2<WindowArea, MoveControl> { ok, f -> win.moveWindowBlind(WindowArea(v), MoveControl.FORWARD, ok, f) }; delay(2000); set2<WindowArea, MoveControl> { ok, f -> win.moveWindowBlind(WindowArea(v), MoveControl.STOP, ok, f) } }
        }
        step("공조 ${plan.tempC.toInt()}°C 약풍") {
            set2<SeatArea, Boolean> { ok, f -> hvac.setHvacPower(driver, true, ok, f) }
            set2<SeatArea, Float> { ok, f -> hvac.setHvacTemperature(driver, plan.tempC, TemperatureUnit.CELSIUS, ok, f) }
            set2<SeatArea, Int> { ok, f -> hvac.setHvacFanSpeed(driver, plan.fan, ok, f) }
            set2<SeatArea, Boolean> { ok, f -> hvac.setHvacRecirculation(driver, true, ok, f) }
        }
        step("조명 끄기") {
            set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightness(driver, plan.ambientBrightness, ok, f) }
            await<LightSwitch> { ok, f -> light.setCabinLightSwitch(LightSwitch.OFF, ok, f) }
        }
        step("등받이 눕히기") {
            set2<SeatArea, MoveControl> { ok, f -> seat.moveSeatBackrestAngle(driver, MoveControl.BACKWARD, ok, f) }; delay(plan.reclineSec * 1000L)
            set2<SeatArea, MoveControl> { ok, f -> seat.moveSeatBackrestAngle(driver, MoveControl.STOP, ok, f) }
        }
        return done
    }

    override suspend fun exitRest(): List<String> {
        val done = mutableListOf<String>()
        val win = vehicle.getWindow(); val light = vehicle.getLight(); val seat = vehicle.getSeat()
        suspend fun step(label: String, block: suspend () -> Unit) { runCatching { withTimeout(5000) { block() }; done += label }.onFailure(::log) }
        step("조명 서서히 밝게") { for (b in listOf(20, 45, 70, 100)) { set2<SeatArea, Int> { ok, f -> light.setAmbientLightBrightness(driver, b, ok, f) }; delay(700) } }
        step("등받이 세우기") {
            set2<SeatArea, MoveControl> { ok, f -> seat.moveSeatBackrestAngle(driver, MoveControl.FORWARD, ok, f) }; delay(4000)
            set2<SeatArea, MoveControl> { ok, f -> seat.moveSeatBackrestAngle(driver, MoveControl.STOP, ok, f) }
        }
        step("환기") { set2<WindowArea, Int> { ok, f -> win.setWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), 15, ok, f) }; delay(6000); set2<WindowArea, Int> { ok, f -> win.setWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), 0, ok, f) } }
        step("문 잠금 해제") { set2<ai.pleos.playground.vehicle.api.model.area.DoorArea, Boolean> { ok, f -> vehicle.getDoor().setDoorLock(ai.pleos.playground.vehicle.api.model.area.DoorArea(ai.pleos.playground.vehicle.constant.area.DoorValue.ROW_1_LEFT), false, ok, f) } }
        return done
    }

    private suspend fun <T> await(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T = suspendCancellableCoroutine { c ->
        call({ v -> if (c.isActive) { if (v != null) c.resume(v) else c.resumeWithException(NoSuchElementException("EMPTY_RESULT")) } }, { e -> if (c.isActive) c.resumeWithException(e) })
    }
    private suspend fun <A, V> set2(call: ((A, V?) -> Unit, (Exception) -> Unit) -> Unit): V? = suspendCancellableCoroutine { c ->
        call({ _, v -> if (c.isActive) c.resume(v) }, { e -> if (c.isActive) c.resumeWithException(e) })
    }
    private inline fun safe(block: () -> Unit) = runCatching(block).onFailure(::log)
    private fun log(e: Throwable) { Log.w(TAG, "car: ${e.javaClass.simpleName} ${e.message}") }

    companion object {
        const val TAG = "Kkaebi"
        const val INVERTED_OCCUPANCY = true
        private fun VehicleGear?.toGear() = when (this) {
            VehicleGear.GEAR_PARK -> Gear.PARK; VehicleGear.GEAR_REVERSE -> Gear.REVERSE; VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN; else -> Gear.DRIVE
        }
    }
}
