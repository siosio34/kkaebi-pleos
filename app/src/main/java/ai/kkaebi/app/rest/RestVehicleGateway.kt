package ai.kkaebi.app.rest

import ai.kkaebi.app.navi.DrowsyComfortPolicy
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.HvacTemperatureConfigArray
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Explicit rest-only allowlist. Motion requires position readback, bounded pulses and STOP. */
class RestVehicleGateway(context: Context) {
    data class DrowsyWriteResult(val state: String, val value: Double? = null, val original: Double? = null)
    private val vehicle = Vehicle(context.applicationContext)
    private val preferences = context.getSharedPreferences("rest-restore-journal", Context.MODE_PRIVATE)
    private val posePreferences = context.getSharedPreferences("rest-saved-pose", Context.MODE_PRIVATE)
    @Volatile private var poseStatus: String? = null
    @Volatile private var poseMessage: String? = null
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val controlLock = Mutex()
    private val journalStore = RestJournalStore(
        { preferences.getString("journal", "{}") ?: "{}" },
        { value -> check(preferences.edit().putString("journal", value).commit()) { "복원 기록 저장 실패" } })
    @Volatile private var activeOperationJob: Job? = null
    private val operationSafety = RestOperationSafety()
    private val capabilityLock = Mutex()
    private val stateLock = Any()
    private var controlObserver: () -> Unit = {}
    private val automaticKeys = listOf("temperature", "fan", "brightness", "seatVentilation", "hvacPower", "seatRecline", "blind", "window")
    private val manualKeys = listOf("seatHeating", "smartGlass")
    private var capabilities = (automaticKeys + manualKeys).associateWith {
        RestControlCapability(automatic = it in automaticKeys,
            message = if (it in manualKeys) "SDK API 존재 · 차량 지원 미확인 · 직접 조작 필요" else "차량 지원 범위 확인 대기")
    }
    @Volatile private var capabilitiesAt = 0L
    @Volatile var controlOperationState = RestControlOperation(); private set
    private var androidCar: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    @Volatile var gear = "UNKNOWN"; private set
    @Volatile var speedKmh: Double? = null; private set
    @Volatile var gearAt = 0L; private set
    @Volatile var speedAt = 0L; private set
    @Volatile var restricted = true; private set
    @Volatile var connection = "차량 서비스 연결 중"; private set
    @Volatile var connected = false; private set
    @Volatile var controlStatus = "차량 설정은 동의한 뒤 정차 상태에서만 변경합니다"; private set
    @Volatile private var closed = false
    @Volatile private var foreground = false
    @Volatile var motionStopUnconfirmed = preferences.getBoolean("motionStopUnconfirmed", false); private set
    private val motionEpoch = AtomicLong()
    private val unsettledMoves = AtomicInteger()
    @Volatile private var moving: Pair<String, RestControlArea>? = null

    init { runCatching { clearKnownUndispatched() } }

    val pendingRestore: Boolean get() = journalStore.snapshot() != "{}"
    val controlResults: JSONObject get() = JSONObject(controlOperationState.resultsJson)
    val restPose: JSONObject get() = runCatching { JSONObject(posePreferences.getString("pose", "{}") ?: "{}") }
        .getOrDefault(JSONObject()).apply {
            put("status", poseStatus ?: optString("status", "empty"))
            put("message", poseMessage ?: optString("message", "차량에서 편한 자세를 맞춘 뒤 현재 자세를 저장해 주세요"))
            if (!has("capturedAt")) put("capturedAt", JSONObject.NULL)
            listOf("seatRecline", "blind").forEach { if (!has(it)) put(it, JSONObject.NULL) }
        }
    val controlCapabilities: JSONObject get() = synchronized(stateLock) {
        JSONObject().apply {
            capabilities.forEach { (key, c) -> put(key, JSONObject()
                .put("state", c.state).put("min", c.min?.takeIf { it.isFinite() } ?: JSONObject.NULL).put("max", c.max?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("step", c.step?.takeIf { it.isFinite() } ?: JSONObject.NULL).put("origin", c.origin?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("access", c.access).put("automatic", c.automatic).put("manualRequired", !c.automatic)
                .put("apiAvailable", true).put("checkedAt", c.checkedAt).put("message", c.message)
                .put("scope", c.area?.scope ?: "unknown").put("areaId", c.area?.id ?: JSONObject.NULL)
                .put("areas", JSONArray(c.area?.names ?: emptyList<String>()))
                .put("requiresCabinConsent", c.area?.scope == "cabin")
                .put("requiresMotionConsent", key in setOf("seatRecline", "blind", "window"))
                .put("unit", when(key) { "temperature" -> "celsius"; "hvacPower" -> "boolean"; "seatRecline", "blind", "window" -> "position"; else -> "level" })) }
        }
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(value: VehicleGear?) { setGear(value) }
        override fun onFailed(e: Exception) { gear = "UNKNOWN"; gearAt = 0; observeSafety() }
    }
    private val speedListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status == CarPropertyValue.STATUS_AVAILABLE) setSpeed((value.value as? Number)?.toDouble())
            else { speedKmh = null; speedAt = 0; observeSafety() }
        }
        override fun onErrorEvent(propId: Int, zone: Int) { speedKmh = null; speedAt = 0; observeSafety() }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        closed = false
        runCatching {
            androidCar = Car.createCar(contextRef)
            properties = androidCar?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ux = androidCar?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            ux?.registerListener { restricted = it.isRequiresDistractionOptimization; observeSafety(); if(restricted) stopMotion() }
            restricted = ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
            properties?.registerCallback(speedListener, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        runCatching {
            vehicle.initialize()
            withTimeout(3000) { setGear(read { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }) }
            if (closed) return@runCatching
            vehicle.getGear().registerGearSelection(gearListener)
            connected = true
            // A previous process may have died while a motor request was outstanding. Only STOP here.
            val recoveryJournal = journal()
            val recoveryKeys = recoveryJournal.keys().asSequence().filter { it in setOf("seatRecline", "blind") }.toList()
            if(recoveryKeys.isNotEmpty()) {
                var allStopped = true
                for(key in recoveryKeys) {
                    val result = runCatching { stopAndConfirm(key, areaFromRecord(key, recoveryJournal.getJSONObject(key)), clearFault = false) }
                    if(result.isFailure) { allStopped = false; Log.e("KkaebiMotion", "recovery STOP unconfirmed key=$key", result.exceptionOrNull()) }
                }
                markMotionStopUnknown(!allStopped)
                if(!allStopped) controlStatus = "이전 이동 정지 확인 필요 · 차량에서 직접 정지해 주세요"
            }
            connection = "Pleos 차량 신호 연결 · 설정 권한은 실행 시 확인"
            logJournal("open")
        }.onFailure {
            connected = false
            gear = "UNKNOWN"; gearAt = 0
            connection = "Pleos 차량 신호 사용 불가 · 권한 또는 서비스 확인 필요"
        }
    }

    private val contextRef = context.applicationContext

    suspend fun poll(refresh: Boolean = true) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        if (connected) runCatching {
            withTimeout(1500) { setGear(read { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }) }
        }.onFailure { gear = "UNKNOWN"; gearAt = 0; observeSafety() }
        runCatching {
            val value = properties?.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            if (value?.status == CarPropertyValue.STATUS_AVAILABLE) setSpeed(value.value?.toDouble())
            else { speedKmh = null; speedAt = 0; observeSafety() }
        }.onFailure { speedKmh = null; speedAt = 0; observeSafety() }
        restricted = runCatching { ux?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true }.getOrDefault(true)
        observeSafety()
        if (refresh && parked() && System.currentTimeMillis() - capabilitiesAt > 60_000) refreshCapabilities()
    }

    private fun setGear(value: VehicleGear?) {
        gear = when (value) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_DRIVE -> "D"
            else -> "UNKNOWN"
        }
        gearAt = if (gear == "UNKNOWN") 0 else System.currentTimeMillis()
        observeSafety()
    }
    private fun setSpeed(metersPerSecond: Double?) {
        speedKmh = metersPerSecond?.takeIf { it.isFinite() }?.let { abs(it) * 3.6 }
        speedAt = if (speedKmh == null) 0 else System.currentTimeMillis()
        observeSafety()
    }
    fun parked() = foreground && !closed && connected && RestSafety.parked(gear, speedKmh, gearAt, speedAt, System.currentTimeMillis(), restricted)
    private fun requireParked() { operationSafety.requireSafe(parked()) }
    /** Separate driving allowlist. Sleep preset methods still require P and their existing journal. */
    suspend fun drowsyWrite(key: String, target: Double?, original: Double? = null, expectedCurrent: Double? = null): DrowsyWriteResult = controlLock.withLock {
        if (key !in DrowsyComfortPolicy.keys) return@withLock DrowsyWriteResult("unsupported")
        if (!foreground || closed || !connected) return@withLock DrowsyWriteResult("failed", original = original)
        val capability = if (key == "window") windowDrowsyCapability() else queryCapability(key)
        val area = capability.area ?: return@withLock DrowsyWriteResult("unsupported", original = original)
        if (capability.state != "supported" || capability.access != "READ_WRITE") return@withLock DrowsyWriteResult("unsupported", original = original)
        val before = try { if (key == "window") readWindow(area) else getValue(key, area) }
            catch (_: Exception) { return@withLock DrowsyWriteResult("failed", original = original) }
        if (!RestSafety.validCapabilityValue(before, capability.min, capability.max, capability.step, capability.origin))
            return@withLock DrowsyWriteResult("unsupported", before, original)
        if (expectedCurrent != null && abs(before - expectedCurrent) > 0.05)
            return@withLock DrowsyWriteResult("failed", before, original)
        val desired = if (key == "window" && target == null)
            DrowsyComfortPolicy.windowOpenTarget(before, capability.min, capability.max)
        else target
        if (desired == null) return@withLock DrowsyWriteResult("unsupported", before, original)
        val restoring = original != null && desired == original
        val allowed = if (key == "window" || restoring) RestSafety.validCapabilityValue(desired, capability.min, capability.max, capability.step, capability.origin)
            else RestSafety.canRequest(key, desired, capability, System.currentTimeMillis())
        if (!allowed) return@withLock DrowsyWriteResult("unsupported", before, original)
        val saved = original ?: before
        try {
            check(foreground && connected && !closed) { "차량 연결 또는 화면 상태 변경" }
            val dispatched = runCatching { if (key == "window") withTimeout(2000) { suspendCancellableCoroutine<Unit> { c ->
                vehicle.getWindow().setWindowPosition(windowArea(area), desired.toInt(), { returnedArea, returned ->
                    if (c.isActive) {
                        if (returnedArea == windowArea(area) && returned == desired.toInt()) c.resume(Unit)
                        else c.resumeWithException(IllegalStateException("창문 응답 불일치"))
                    }
                }, { if (c.isActive) c.resumeWithException(it) })
            } } else setDrowsyValue(key, desired, area) }
            var actual = Double.NaN
            repeat(8) { attempt ->
                actual = runCatching { if (key == "window") readWindow(area) else getValue(key, area) }.getOrDefault(Double.NaN)
                Log.i("KkaebiControl", "drowsy key=$key readback attempt=$attempt observed=$actual target=$desired write=${dispatched.exceptionOrNull()?.message}")
                if (RestSafety.shouldRestore(actual, desired)) return@withLock DrowsyWriteResult("applied", actual, saved)
                if (attempt < 7) delay(250)
            }
            DrowsyWriteResult(if (abs(actual - desired) <= 0.05) "applied" else "failed", actual, saved)
        } catch (_: Exception) {
            val actual = runCatching { if (key == "window") readWindow(area) else getValue(key, area) }.getOrNull()
            DrowsyWriteResult("failed", actual, saved)
        }
    }

    private suspend fun windowDrowsyCapability(): RestControlCapability = try {
        withTimeout(2000) {
            read<RestControlCapability> { ok, fail -> vehicle.getWindow().checkWindowPositionCapabilities({ config, areas ->
                val exact = WindowArea(WindowValue.ROW_1_LEFT)
                val bounds = areas?.get(exact)
                ok(RestSafety.capability(config?.accessType?.name ?: "UNKNOWN", bounds != null,
                    bounds?.minValue?.toDouble(), bounds?.maxValue?.toDouble(), 1.0, 0.0, System.currentTimeMillis())
                    .copy(area = if (bounds != null) describe(exact) else null))
            }, fail) }
        }
    } catch (_: Exception) { RestControlCapability() }

    private suspend fun readWindow(area: RestControlArea): Double = withTimeout(2000) {
        read<Int> { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(windowArea(area), ok, fail) }.toDouble()
    }
    private suspend fun setDrowsyValue(key: String, target: Double, area: RestControlArea) = withTimeout(2000) {
        val seat = seatArea(area)
        when (key) {
            "fan" -> write<Int> { ok, fail -> vehicle.getHVAC().setHvacFanSpeed(seat, target.toInt(), ok, fail) }
            "seatVentilation" -> write<Int> { ok, fail -> vehicle.getSeat().setHvacSeatVentilation(seat, target.toInt(), ok, fail) }
            else -> error("주행 중 허용되지 않은 설정")
        }
    }
    private fun observeSafety() {
        val safe = parked()
        val interrupted = operationSafety.observe(safe)
        if (interrupted || (moving != null && !safe)) stopMotion()
        if (interrupted) activeOperationJob?.cancel(CancellationException("정차 신호 상실로 모든 실행 흐름을 중단했습니다"))
    }

    /** Read-only discovery. Unknown/permission/timeout is never upgraded to supported. */
    suspend fun refreshCapabilities() = capabilityLock.withLock {
        if (!parked()) return@withLock
        capabilitiesAt = System.currentTimeMillis()
        coroutineScope { (automaticKeys + manualKeys).map { key -> async { queryCapability(key) } }.awaitAll() }
        val snapshot = controlCapabilities
        snapshot.keys().forEach { key -> Log.i("KkaebiCapabilities", "key=$key ${snapshot.getJSONObject(key)}") }
    }

    private suspend fun queryCapability(key: String, exactArea: RestControlArea? = null): RestControlCapability {
        val result = try {
            check(!closed && connected) { "차량 서비스 연결 필요" }
            if (key == "seatRecline" || key == "blind") motionCapability(key, exactArea) else withTimeout(2000) {
                read<RestControlCapability> { ok, fail ->
                    when (key) {
                        "temperature" -> vehicle.getHVAC().checkHvacTemperatureCapabilities(
                            { config, areas, units -> ok(temperatureCapability(config, areas, units, exactArea)) }, fail)
                        "fan" -> vehicle.getHVAC().checkHvacFanSpeedCapabilities(
                            { config, areas -> ok(numericCapability(key, config, areas, exactArea)) }, fail)
                        "brightness" -> vehicle.getLight().checkAmbientLightBrightnessCapabilities(
                            { config, areas -> ok(numericCapability(key, config, areas, exactArea)) }, fail)
                        "seatVentilation" -> vehicle.getSeat().checkSeatVentilationCapabilities(
                            { config, areas -> ok(numericCapability(key, config, areas, exactArea)) }, fail)
                        "hvacPower" -> vehicle.getHVAC().checkHvacPowerCapabilities(
                            { config, areas ->
                                val area = chooseSeatArea(key, areas ?: emptyList(), exactArea)
                                ok(RestSafety.capability(config?.accessType?.name ?: "UNKNOWN", area != null,
                                    0.0, 1.0, 1.0, 0.0, System.currentTimeMillis()).copy(area = area?.let(::describe)))
                            }, fail)
                        "window" -> vehicle.getWindow().checkWindowPositionCapabilities(
                            { config, areas ->
                                val chosen = WindowArea(WindowValue.ROW_1_LEFT)
                                val bounds = areas?.get(chosen)
                                ok(RestSafety.capability(config?.accessType?.name ?: "UNKNOWN", bounds != null,
                                    bounds?.minValue?.toDouble(), bounds?.maxValue?.toDouble(), 1.0, 0.0, System.currentTimeMillis())
                                    .copy(area = if (bounds != null) describe(chosen) else null))
                            }, fail)
                        "seatHeating" -> vehicle.getSeat().checkSeatTemperatureCapabilities(
                            { config, areas -> ok(manualCapability(config, areas?.containsKey(driver) == true, "수면 중 열선 자동 적용 제외 · 차량에서 직접 확인")) }, fail)
                        "smartGlass" -> vehicle.getWindow().checkSmartGlassLevelCapabilities(
                            { config, areas -> ok(manualCapability(config, !areas.isNullOrEmpty(), "블라인드와 다른 기능 · 차종·유리 영역 확인 후 직접 조작")) }, fail)
                        else -> fail(IllegalArgumentException("허용되지 않은 설정"))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            RestControlCapability(automatic = key in automaticKeys, checkedAt = System.currentTimeMillis(),
                message = "지원 조회 미완료 · ${e.message?.take(140) ?: "권한 또는 차량 서비스 확인"}")
        }
        val checked = hvacReadable(key, result)
        synchronized(stateLock) { capabilities = capabilities + (key to checked) }
        return checked
    }

    // 공조는 온도·풍량·전원이 한 묶음이다. 지원 조회는 통과해도 현재 온도를 읽지 못하는 차량에서는
    // 쓰기 결과를 확인할 길이 없으므로, 휴식 계획에 넣지 않고 처음부터 자동 적용 제외로 보여 준다.
    private suspend fun hvacReadable(key: String, result: RestControlCapability): RestControlCapability {
        if (key !in setOf("temperature", "fan", "hvacPower") || result.state != "supported") return result
        val area = (if (key == "temperature") result.area else capabilities["temperature"]?.area ?: result.area) ?: return result
        val readable = runCatching { getValue("temperature", area) }.isSuccess
        return if (readable) result else result.copy(state = "unsupported", message = "이 차량에서 공조 현재 값을 읽을 수 없어 자동 적용에서 제외합니다")
    }

    private fun describe(area: SeatArea) = RestControlArea("seat", area.values.map { it.name }.sorted())
    private fun describe(area: WindowArea) = RestControlArea("window", area.values.map { it.name }.sorted())
    private fun seatArea(area: RestControlArea) = SeatArea(*area.names.map { SeatValue.valueOf(it) }.toTypedArray())
    private fun windowArea(area: RestControlArea) = WindowArea(*area.names.map { WindowValue.valueOf(it) }.toTypedArray())
    private fun chooseSeatArea(key: String, areas: Collection<SeatArea>, exact: RestControlArea?): SeatArea? {
        if (exact != null) return areas.firstOrNull { describe(it) == exact }
        areas.firstOrNull { it == driver }?.let { return it }
        if (key !in setOf("temperature", "fan", "brightness", "hvacPower")) return null
        return areas.filter { SeatValue.ROW_1_LEFT in it.values }.sortedWith(compareBy({ it.values.size }, { describe(it).id })).firstOrNull()
    }
    private fun <T : Number> numericCapability(
        key: String, config: CapabilityConfig?, areas: Map<SeatArea, AreaBounds<T>>?, exact: RestControlArea?
    ): RestControlCapability {
        val area = chooseSeatArea(key, areas?.keys ?: emptySet(), exact)
        val bounds = areas?.get(area)
        return RestSafety.capability(config?.accessType?.name ?: "UNKNOWN", bounds != null,
            bounds?.minValue?.toDouble(), bounds?.maxValue?.toDouble(), 1.0, 0.0, System.currentTimeMillis())
            .copy(area = area?.let(::describe))
    }
    private fun temperatureCapability(
        config: CapabilityConfig?, areas: Map<SeatArea, AreaBounds<Float>>?, units: HvacTemperatureConfigArray?, exact: RestControlArea?
    ): RestControlCapability {
        val area = chooseSeatArea("temperature", areas?.keys ?: emptySet(), exact)
        val bounds = areas?.get(area)
        val lower = if (units != null && bounds != null) max(units.minCelsius.toDouble(), bounds.minValue.toDouble()) else null
        val upper = if (units != null && bounds != null) min(units.maxCelsius.toDouble(), bounds.maxValue.toDouble()) else null
        return RestSafety.capability(config?.accessType?.name ?: "UNKNOWN", bounds != null, lower, upper,
            units?.incrementCelsius?.toDouble(), units?.minCelsius?.toDouble(), System.currentTimeMillis()).copy(area = area?.let(::describe))
    }
    private suspend fun motionCapability(key: String, exact: RestControlArea?): RestControlCapability = withTimeout(2500) {
        if (key == "seatRecline") {
            val position = read<Pair<CapabilityConfig?, Map<SeatArea, AreaBounds<Int>>?>> { ok, fail ->
                vehicle.getSeat().checkSeatBackrestAnglePositionCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
            val movement = read<Pair<CapabilityConfig?, List<SeatArea>?>> { ok, fail ->
                vehicle.getSeat().checkMoveSeatBackrestAngleCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
            val bounds = position.second?.get(driver)
            val area = describe(driver)
            val supported = (exact == null || exact == area) && movement.second?.contains(driver) == true && bounds != null &&
                RestMotionPolicy.readableAndWritable(position.first?.accessType?.name ?: "", movement.first?.accessType?.name ?: "")
            RestSafety.capability(if(supported) "READ_WRITE" else "NONE", supported, bounds?.minValue?.toDouble(),
                bounds?.maxValue?.toDouble(), 1.0, 0.0, System.currentTimeMillis()).copy(area = area,
                message = if(supported) "운전석 위치 읽기·이동·정지 API 확인 · 실차 끼임 보호 별도 검증 필요" else "운전석 위치 읽기와 이동 쓰기 지원 확인 필요")
        } else {
            val position = read<Pair<CapabilityConfig?, Map<WindowArea, AreaBounds<Int>>?>> { ok, fail ->
                vehicle.getWindow().checkWindowBlindPositionCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
            val movement = read<Pair<CapabilityConfig?, List<WindowArea>?>> { ok, fail ->
                vehicle.getWindow().checkMoveWindowBlindCapabilities({ config, areas -> ok(config to areas) }, fail)
            }
            val candidate = position.second?.keys?.filter { it.values.size == 1 && movement.second?.contains(it) == true && (exact == null || describe(it) == exact) }
                ?.sortedBy { describe(it).id }?.firstOrNull()
            val bounds = position.second?.get(candidate)
            val supported = candidate != null && RestMotionPolicy.readableAndWritable(position.first?.accessType?.name ?: "", movement.first?.accessType?.name ?: "")
            RestSafety.capability(if(supported) "READ_WRITE" else "NONE", supported, bounds?.minValue?.toDouble(),
                bounds?.maxValue?.toDouble(), 1.0, 0.0, System.currentTimeMillis()).copy(area = candidate?.let(::describe),
                message = if(supported) "지정 블라인드 위치 읽기·이동·정지 API 확인 · 주변 장애물 확인 필요" else "동일 블라인드 영역의 위치 읽기와 이동 쓰기 지원 확인 필요")
        }
    }

    private fun manualCapability(config: CapabilityConfig?, areaPresent: Boolean, message: String): RestControlCapability {
        val access = config?.accessType?.name ?: "UNKNOWN"
        val state = when {
            access == "UNKNOWN" -> "unknown"
            access == "NONE" || !areaPresent -> "unsupported"
            else -> "supported"
        }
        return RestControlCapability(state = state, access = access, automatic = false,
            message = "$message · 차량 지원 $state", checkedAt = System.currentTimeMillis())
    }

    fun rejectPoseCapture(message: String) { poseStatus = "failed"; poseMessage = message }

    /** Read-only preference capture. It never shares or modifies the restoration journal. */
    suspend fun captureRestPose(authorizeRead: () -> Boolean, onChanged: () -> Unit = {}) = controlLock.withLock {
        val previousPose = posePreferences.getString("pose", "{}") ?: "{}"
        operationSafety.begin()
        fun requireCaptureAllowed() {
            requireParked()
            check(!pendingRestore && !motionStopUnconfirmed && unsettledMoves.get() == 0 &&
                runCatching(authorizeRead).getOrDefault(false)) { "자세 저장 조건이 바뀌었습니다" }
        }
        try {
            requireCaptureAllowed()
            poseStatus = "capturing"; poseMessage = "현재 시트·블라인드 위치를 읽고 있습니다"; onChanged()
            val captured = JSONObject()
            var successes = 0
            for (key in listOf("seatRecline", "blind")) {
                requireCaptureAllowed()
                try {
                    val capability = queryCapability(key)
                    val area = capability.area ?: error("제어 영역 확인 불가")
                    check(area.authorized(key, false)) { "단일 제어 영역 확인 불가" }
                    check(capability.state == "supported" && capability.automatic && capability.access == "READ_WRITE") { "위치 읽기·이동 지원 확인 필요" }
                    requireCaptureAllowed()
                    val value = getValue(key, area)
                    val at = System.currentTimeMillis()
                    check(RestPosePolicy.valid(value, capability, at)) { "현재 위치의 지원 범위 확인 실패" }
                    requireCaptureAllowed()
                    Log.i("KkaebiPose", "read key=$key area=${area.id} observed=$value")
                    captured.put(key, JSONObject().put("value", value).put("areaId", area.id)
                        .put("min", capability.min).put("max", capability.max).put("step", capability.step)
                        .put("unit", "position").put("capturedAt", at))
                    successes++
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    if (e is RestOperationInterrupted) throw e
                    requireCaptureAllowed()
                    Log.w("KkaebiPose", "capture key=$key failed: ${e.message?.take(160)}")
                }
            }
            requireCaptureAllowed()
            if (!RestPosePolicy.shouldReplaceSavedPose(successes)) {
                rejectPoseCapture("새 위치를 확인하지 못했습니다 · 이전에 저장한 자세는 유지됩니다")
                return@withLock
            }
            val status = RestPosePolicy.status(successes)
            val message = when(status) {
                "ready" -> "현재 시트·블라인드 위치를 저장했습니다"
                "partial" -> "확인된 위치만 저장했습니다 · 나머지는 차량 지원을 확인해 주세요"
                else -> "현재 자세를 저장하지 못했습니다 · 차량 지원과 상태를 확인해 주세요"
            }
            captured.put("capturedAt", System.currentTimeMillis()).put("status", status).put("message", message)
            if (!posePreferences.edit().putString("pose", captured.toString()).commit()) {
                // SharedPreferences may update its memory cache even when disk persistence fails.
                posePreferences.edit().putString("pose", previousPose).commit()
                error("자세 저장 실패")
            }
            Log.i("KkaebiPose", "saved count=$successes status=$status")
            poseStatus = status; poseMessage = message
        } catch (e: Exception) {
            rejectPoseCapture(if (e.message == "자세 저장 실패") "저장을 확인하지 못했습니다 · 기존 자세를 표시하고 있습니다"
                else "저장을 마치지 못했습니다 · 이전에 저장한 자세는 유지됩니다")
            if (e is CancellationException) throw e
        } finally { operationSafety.finish(); onChanged() }
    }

    suspend fun apply(
        temperature: Double? = null, fan: Int? = null, dim: Boolean? = null,
        seatVentilation: Int? = null, hvacPower: Boolean? = null,
        seatRecline: Int? = null, blind: Int? = null, blindArea: String? = null, window: Int? = null,
        cabinConsent: Boolean = false, motionConsent: Boolean = false, ownerAlarmId: String = "",
        authorizeWrite: () -> Boolean = { true },
        onChanged: () -> Unit = {}
    ) = runControlOperation("apply", onChanged) { operationId ->
        if (pendingRestore) { controlStatus = "이전 설정 복원 또는 수동 확인이 먼저 필요합니다"; return@runControlOperation false }
        // Optional means consent to this field only. Temperature/fan never turn HVAC on implicitly.
        val requests = linkedMapOf<String, Double>()
        hvacPower?.let { requests["hvacPower"] = if (it) 1.0 else 0.0 }
        temperature?.let { requests["temperature"] = it }
        fan?.let { requests["fan"] = it.toDouble() }
        if (dim == true) requests["brightness"] = 10.0
        seatVentilation?.let { requests["seatVentilation"] = it.toDouble() }
        seatRecline?.let { requests["seatRecline"] = it.toDouble() }
        blind?.let { requests["blind"] = it.toDouble() }
        window?.let { requests["window"] = it.toDouble() }
        val plan = RestParallelPlan.create(requests)
        enqueueControlSteps(operationId, plan.steps.map { it.key to requests.getValue(it.key) }, plan)
        if (requests.isEmpty()) { controlStatus = "선택한 차량 설정이 없습니다"; return@runControlOperation true }
        suspend fun executeItem(key: String): Boolean {
            requireParked(); requireAuthorization(authorizeWrite)
            val target = requests.getValue(key)
            var confirmed = false
            var recheck: (suspend () -> Boolean)? = null
            runCatching {
                progress(operationId, key, "checking")
                val capability = queryCapability(key)
                check(RestSafety.canRequest(key, target, capability, System.currentTimeMillis())) { "지원·읽기/쓰기·요청 범위 확인 실패: ${capability.message}" }
                val area = capability.area ?: error("차량 제어 영역 미확인")
                check(area.authorized(key, cabinConsent)) { "차내 공동 영역 변경에 별도 동의가 필요합니다" }
                if(key in setOf("seatRecline", "blind", "window")) check(motionConsent) { "움직이는 부분 주변 확인과 이동 동의가 필요합니다" }
                if(key == "blind") check(blindArea == area.id) { "선택한 블라인드 영역을 확인할 수 없습니다" }
                poll(refresh = false); requireParked()
                val before = getValue(key, area)
                check(RestSafety.validCapabilityValue(before, capability.min, capability.max, capability.step, capability.origin)) { "기존 값을 안전하게 복원할 수 없습니다" }
                observeBefore(operationId, key, before)
                recheck = { RestSafety.shouldRestore(getValue(key, area), before) }
                requireParked()
                requireAuthorization(authorizeWrite)
                Log.i("KkaebiControl", "operation=$operationId key=$key area=${area.id} before=$before target=$target")
                // Persist before dispatch: a process interruption must not erase recovery information.
                record(key, before, target, area = area, cabinConsent = cabinConsent, ownerAlarmId = ownerAlarmId)
                var dispatched = false
                val writeResult = runCatching { setValue(key, target, area, capability, authorizeWrite, onDispatch = {
                    markDispatched(key)
                    dispatched = true
                    progress(operationId, key, "writing")
                }, onObserved = { start, value -> observePosition(operationId, key, start, value) }) }
                writeResult.exceptionOrNull()?.let { if (it is RestOperationInterrupted || it is RestOperationRevoked || it is CancellationException && it !is TimeoutCancellationException) throw it }
                val noProgress = writeResult.exceptionOrNull()?.message?.startsWith("위치 변화가 없어") == true
                if (!dispatched || key in setOf("seatRecline", "blind") && writeResult.isFailure && !noProgress) throw writeResult.exceptionOrNull() ?: IllegalStateException("쓰기 시작 실패")
                progress(operationId, key, "verifying")
                val after = if (noProgress) before else verifyValue(key, area, target) { value -> if (key == "window") observePosition(operationId, key, before, value) }
                if (!RestSafety.shouldRestore(after, target)) {
                    // The SDK accepted the command (set onSuccess, or MOVE acknowledged then stopped) but the
                    // vehicle did not report the new value. Keep the journal so wake-up still restores, and
                    // report this as "accepted", never as "applied".
                    check(noProgress || writeResult.isSuccess) { "설정 적용 확인 실패 · observed=$after write=${writeResult.exceptionOrNull()?.message}" }
                    Log.i("KkaebiControl", "operation=$operationId key=$key accepted-without-readback observed=$after target=$target motor=$noProgress")
                    // Unconfirmed in the journal: wake-up reasserts the original value instead of assuming a manual change.
                    record(key, before, target, confirmed = false, dispatched = true, area = area, cabinConsent = cabinConsent, ownerAlarmId = ownerAlarmId)
                    confirmed = true
                    updateControlResult(operationId, key, JSONObject().put("state", "accepted").put("requested", target).put("actual", after))
                    progress(operationId, key, "accepted", actual = after)
                    return@runCatching
                }
                record(key, before, target, confirmed = true, dispatched = true, area = area, cabinConsent = cabinConsent, ownerAlarmId = ownerAlarmId)
                confirmed = true
                updateControlResult(operationId, key, JSONObject().put("state", "applied").put("requested", target).put("actual", after))
                progress(operationId, key, "applied", actual = after)
            }.onFailure {
                // A recorded request that never reached SDK dispatch cannot change the vehicle later.
                // Unknown legacy records and dispatched requests (including timeouts) always remain.
                runCatching { removeKnownUndispatched(key) }
                if (it is CancellationException && it !is TimeoutCancellationException) throw it
                if (it is RestOperationInterrupted || it is RestOperationRevoked) throw it
                requireParked()
                // A rejected write that left the vehicle at its original value has nothing to restore later.
                runCatching { if (recheck?.invoke() == true) { removeRecord(key); Log.i("KkaebiControl", "operation=$operationId key=$key unchanged-after-failure journal-cleared") } }
                val message = it.message ?: "권한/서비스 확인"
                updateControlResult(operationId, key, JSONObject().put("state", "failed").put("message", message))
                progress(operationId, key, "failed", message = message)
            }
            return confirmed
        }
        RestParallelExecutor().execute(plan,
            guard = { requireParked(); requireAuthorization(authorizeWrite) },
            currentPowerConfirmed = { readPowerForDependencies() },
            blocked = { key, dependencies, message -> blockControl(operationId, key, dependencies, message) },
            executeItem = ::executeItem)
        finishControlSummary(operationId, "apply")
    }

    suspend fun restore(expectedOwner: String? = null, onChanged: () -> Unit = {}) = runControlOperation("restore", onChanged) { operationId ->
        val journal = try { clearKnownUndispatched(); journal() } catch (_: Exception) {
            controlStatus = "복원 기록을 읽을 수 없습니다 · 차량 설정을 직접 확인해 주세요"
            return@runControlOperation false
        }
        if(expectedOwner != null && journal.keys().asSequence().any { !RestWritePolicy.autoRestoreOwned(journal.optJSONObject(it)?.optString("ownerAlarmId"), expectedOwner,
                journal.optJSONObject(it)?.optLong("createdAt", 0L) ?: 0L, System.currentTimeMillis()) }) {
            controlStatus = "이전 휴식 또는 오래된 복원 기록입니다 · 정차 후 직접 복원을 선택해 주세요"
            return@runControlOperation false
        }
        if (journal.length() == 0) { controlStatus = "복원할 설정이 없습니다"; return@runControlOperation true }
        val targets = journal.keys().asSequence().associateWith { key ->
            journal.optJSONObject(key)?.optDouble("before")?.takeIf { it.isFinite() }
        }
        val plan = RestParallelPlan.create(targets, restoring = true)
        enqueueControlSteps(operationId, plan.steps.map { it.key to targets[it.key] }, plan)
        suspend fun executeItem(key: String): Boolean {
            requireParked()
            var confirmed = false
            runCatching {
                progress(operationId, key, "checking")
                val item = journal.getJSONObject(key)
                val area = areaFromRecord(key, item)
                check(area.authorized(key, item.optBoolean("cabinConsent", false))) { "기록된 제어 영역 동의를 확인할 수 없습니다" }
                val capability = queryCapability(key, area)
                check(capability.automatic && capability.state == "supported" && capability.access == "READ_WRITE") { "복원 지원 범위 확인 필요" }
                poll(refresh = false); requireParked()
                val current = getValue(key, area)
                observeBefore(operationId, key, current)
                Log.i("KkaebiControl", "operation=$operationId key=$key area=${area.id} current=$current original=${item.getDouble("before")} applied=${item.getDouble("applied")} confirmed=${item.optBoolean("confirmed", false)}")
                val decision = RestSafety.restoreDecision(current, item.getDouble("applied"), item.optBoolean("confirmed", false))
                // An old unconfirmed apply may have left the vehicle at its original value.
                // Reassert that value through the SDK and clear the journal only after readback.
                if (decision == "restore" || decision == "pending" && RestSafety.shouldRestore(current, item.getDouble("before"))) {
                    val before = item.getDouble("before")
                    check(RestSafety.validCapabilityValue(before, capability.min, capability.max, capability.step, capability.origin)) { "원래 값이 현재 차량 범위 밖입니다" }
                    requireParked(); var dispatched = false
                    val writeResult = runCatching { setValue(key, before, area, capability, onDispatch = {
                        markRestoreDispatched(key)
                        dispatched = true
                        progress(operationId, key, "writing")
                    }, onObserved = { start, value -> observePosition(operationId, key, start, value) }) }
                    writeResult.exceptionOrNull()?.let { if (it is RestOperationInterrupted || it is RestOperationRevoked || it is CancellationException && it !is TimeoutCancellationException) throw it }
                    val noProgress = writeResult.exceptionOrNull()?.message?.startsWith("위치 변화가 없어") == true
                    if (!dispatched || key in setOf("seatRecline", "blind") && writeResult.isFailure && !noProgress) throw writeResult.exceptionOrNull() ?: IllegalStateException("복원 쓰기 시작 실패")
                    progress(operationId, key, "verifying")
                    val actual = if (noProgress) current else verifyValue(key, area, before) { value -> if (key == "window") observePosition(operationId, key, current, value) }
                    if (!RestSafety.shouldRestore(actual, before)) {
                        check(noProgress || writeResult.isSuccess) { "복원 확인 실패 · observed=$actual write=${writeResult.exceptionOrNull()?.message}" }
                        Log.i("KkaebiControl", "operation=$operationId key=$key restore-accepted-without-readback observed=$actual target=$before motor=$noProgress")
                        removeRecord(key)
                        confirmed = true
                        updateControlResult(operationId, key, JSONObject().put("state", "accepted").put("actual", actual))
                        progress(operationId, key, "accepted", actual = actual)
                        return@runCatching
                    }
                    removeRecord(key)
                    confirmed = true
                    updateControlResult(operationId, key, JSONObject().put("state", "restored").put("actual", actual))
                    progress(operationId, key, "restored", actual = actual)
                } else if (decision == "preserveManual") {
                    removeRecord(key)
                    confirmed = RestSafety.shouldRestore(current, item.getDouble("before"))
                    updateControlResult(operationId, key, JSONObject().put("state", "manualPreserved").put("actual", current))
                    progress(operationId, key, "manualPreserved", actual = current)
                } else error("적용 응답 미확인 · 지연 변경 가능성이 있어 기록을 유지합니다")
            }.onFailure {
                if (it is CancellationException && it !is TimeoutCancellationException) throw it
                if (it is RestOperationInterrupted || it is RestOperationRevoked) throw it
                requireParked()
                val message = it.message ?: "권한/서비스 확인"
                updateControlResult(operationId, key, JSONObject().put("state", "pendingRestore").put("message", message))
                progress(operationId, key, "failed", message = message)
            }
            return confirmed
        }
        RestParallelExecutor().execute(plan, guard = { requireParked() },
            currentPowerConfirmed = { readPowerForDependencies() },
            blocked = { key, dependencies, message -> blockControl(operationId, key, dependencies, message) },
            executeItem = ::executeItem)
        finishControlSummary(operationId, "restore")
    }

    private fun requireAuthorization(authorize: () -> Boolean) {
        if (!runCatching(authorize).getOrDefault(false)) throw RestOperationRevoked()
    }

    private suspend fun readPowerForDependencies(): Boolean = try {
        requireParked()
        val cap = queryCapability("hvacPower")
        check(cap.state == "supported" && cap.access == "READ_WRITE")
        val area = cap.area ?: error("공조 전원 영역 미확인")
        val current = verifyValue("hvacPower", area, 1.0)
        requireParked()
        Log.i("KkaebiControl", "dependency=hvacPower area=${area.id} observed=$current")
        current == 1.0
    } catch (e: Exception) {
        if (e is CancellationException && e !is TimeoutCancellationException || e is RestOperationInterrupted || e is RestOperationRevoked) throw e
        requireParked(); false
    }

    private fun blockControl(operationId: String, key: String, dependencies: List<String>, message: String) {
        updateControlResult(operationId, key, JSONObject().put("state", "blocked")
            .put("blockedBy", JSONArray(dependencies)).put("message", message))
        progress(operationId, key, "blocked", message = message, blockedBy = dependencies)
    }

    private fun finishControlSummary(operationId: String, operation: String): Boolean {
        val state = controlOperationState
        if (state.id != operationId) return false
        val results = JSONObject(state.resultsJson)
        controlStatus = state.steps.joinToString(" · ") { step ->
            val row = results.optJSONObject(step.key)
            val message = row?.optString("message").orEmpty()
            "${step.key} ${if(message.isNotEmpty()) message else row?.optString("state").orEmpty()}"
        }
        return RestControlOperation.allConfirmed(operation, results.keys().asSequence().map { results.getJSONObject(it).getString("state") }.toList())
    }

    /** Lifecycle bookkeeping only: all existing write, alarm, parking and journal guards stay inside the block. */
    private suspend fun runControlOperation(name: String, onChanged: () -> Unit, block: suspend (String) -> Boolean) = controlLock.withLock { coroutineScope {
        operationSafety.begin()
        activeOperationJob = currentCoroutineContext()[Job]
        val operationId = UUID.randomUUID().toString()
        synchronized(stateLock) { controlOperationState = controlOperationState.begin(name, operationId) }
        controlObserver = onChanged
        controlStatus = if (name == "apply") "휴식 환경을 확인하고 있습니다" else "복원 결과를 확인하고 있습니다"
        try {
            controlObserver()
            requireParked()
            val successful = block(operationId)
            requireParked()
            synchronized(stateLock) { controlOperationState = controlOperationState.finish(operationId, successful) }
        } catch (e: Exception) {
            controlStatus = when(e) {
                is RestOperationInterrupted -> e.message ?: "정차 신호가 끊겨 전체 작업을 중단했습니다"
                is CancellationException -> "차량 설정 작업이 중단되었습니다 · 복원 대기 상태를 확인해 주세요"
                else -> "차량 설정 작업을 완료하지 못했습니다 · 차량 상태를 확인해 주세요"
            }
            synchronized(stateLock) { controlOperationState = controlOperationState.finish(operationId, false) }
            throw e
        } finally {
            activeOperationJob = null
            operationSafety.finish()
            logJournal("$name:$operationId")
            controlObserver()
            controlObserver = {}
        }
    } }
    private fun logJournal(reason: String) {
        runCatching {
            val j = journal()
            j.keys().forEach { key -> Log.i("KkaebiJournal", "$reason key=$key ${j.getJSONObject(key)}") }
            if(j.length() == 0) Log.i("KkaebiJournal", "$reason empty")
        }
    }

    private fun enqueueControlSteps(operationId: String, items: List<Pair<String, Double?>>, plan: RestParallelPlan? = null) {
        synchronized(stateLock) { controlOperationState = controlOperationState.enqueue(operationId, items, plan) }
        controlObserver()
    }

    private fun progress(operationId: String, key: String, next: String, actual: Double? = null, message: String = "", blockedBy: List<String> = emptyList()) {
        synchronized(stateLock) { controlOperationState = controlOperationState.progress(operationId, key, next, actual, message, blockedBy) }
        Log.i("KkaebiControl", "operation=$operationId key=$key phase=$next actual=$actual message=${message.take(240)}")
        controlObserver()
    }

    private fun observeBefore(operationId: String, key: String, value: Double) {
        synchronized(stateLock) { controlOperationState = controlOperationState.observedBefore(operationId, key, value) }
        controlObserver()
    }

    private fun observePosition(operationId: String, key: String, start: Double, value: Double) {
        synchronized(stateLock) { controlOperationState = controlOperationState.observedPosition(operationId, key, start, value, System.currentTimeMillis()) }
        controlObserver()
    }

    private fun updateControlResult(operationId: String, key: String, detail: JSONObject) {
        synchronized(stateLock) {
            val state = controlOperationState
            if (state.id == operationId && state.phase == "running") {
                val merged = JSONObject(state.resultsJson).put(key, JSONObject(detail.toString()))
                controlOperationState = state.results(operationId, merged.toString())
            }
        }
        controlObserver()
    }

    fun rejectControlOperation(name: String, message: String) = synchronized(stateLock) {
        // A rejected duplicate must not replace an operation that is still running.
        if (!controlLock.isLocked) {
            val operationId = UUID.randomUUID().toString()
            controlOperationState = controlOperationState.begin(name, operationId).finish(operationId, false)
            controlStatus = message
        }
    }

    private suspend fun getValue(key: String, area: RestControlArea): Double = withTimeout(2000) {
        val targetSeat = if(area.kind == "seat") seatArea(area) else driver
        when (key) {
            "seatRecline" -> read<Int> { ok, fail -> vehicle.getSeat().getCurrentSeatBackrestAnglePosition(targetSeat, ok, fail) }.toDouble()
            "blind" -> read<Int> { ok, fail -> vehicle.getWindow().getCurrentWindowBlindPosition(windowArea(area), ok, fail) }.toDouble()
            "window" -> readWindow(area)
            "temperature" -> read<Float> { ok, fail -> vehicle.getHVAC().getHvacTemperature(targetSeat, TemperatureUnit.CELSIUS, ok, fail) }.toDouble()
            "fan" -> read<Int> { ok, fail -> vehicle.getHVAC().getHvacFanSpeed(targetSeat, ok, fail) }.toDouble()
            "brightness" -> read<Int> { ok, fail -> vehicle.getLight().getAmbientLightBrightness(targetSeat, ok, fail) }.toDouble()
            "seatVentilation" -> read<Int> { ok, fail -> vehicle.getSeat().getHvacSeatVentilationState(targetSeat, ok, fail) }.toDouble()
            "hvacPower" -> if (read<Boolean> { ok, fail -> vehicle.getHVAC().getHvacPowerState(targetSeat, ok, fail) }) 1.0 else 0.0
            else -> error("허용되지 않은 설정")
        }.also { check(it.isFinite()) }
    }
    private suspend fun verifyValue(key: String, area: RestControlArea, target: Double, onObserved: (Double) -> Unit = {}): Double {
        var observed = Double.NaN
        repeat(8) { attempt ->
            requireParked()
            observed = runCatching { getValue(key, area) }.getOrElse {
                Log.w("KkaebiControl", "key=$key readback attempt=$attempt error=${it.message}")
                Double.NaN
            }
            Log.i("KkaebiControl", "key=$key readback attempt=$attempt observed=$observed target=$target area=${area.id}")
            if (observed.isFinite()) onObserved(observed)
            if (RestSafety.shouldRestore(observed, target)) return observed
            if (attempt < 7) delay(250)
        }
        return observed
    }
    private suspend fun setValue(key: String, value: Double, area: RestControlArea, capability: RestControlCapability,
        authorizeWrite: () -> Boolean = { true }, onDispatch: () -> Unit = {}, onObserved: (Double, Double) -> Unit = { _, _ -> }) {
        if(key in setOf("seatRecline", "blind")) { moveTo(key, value, area, capability, authorizeWrite, onDispatch, onObserved); return }
        withTimeout(if (key == "window") 5000 else 2000) {
        val targetSeat = if (area.kind == "seat") seatArea(area) else driver
        requireParked()
        // Re-evaluate after suspending SDK reads: notification ACK can revoke a preset too.
        requireAuthorization(authorizeWrite)
        val windowStart = if (key == "window") readWindow(area) else null
        requireParked(); requireAuthorization(authorizeWrite)
        onDispatch()
        when (key) {
            "temperature" -> write<Float> { ok, fail -> vehicle.getHVAC().setHvacTemperature(targetSeat, value.toFloat(), TemperatureUnit.CELSIUS, ok, fail) }
            "fan" -> write<Int> { ok, fail -> vehicle.getHVAC().setHvacFanSpeed(targetSeat, value.toInt(), ok, fail) }
            "brightness" -> write<Int> { ok, fail -> vehicle.getLight().setAmbientLightBrightness(targetSeat, value.toInt(), ok, fail) }
            "seatVentilation" -> write<Int> { ok, fail -> vehicle.getSeat().setHvacSeatVentilation(targetSeat, value.toInt(), ok, fail) }
            "hvacPower" -> write<Boolean> { ok, fail -> vehicle.getHVAC().setHvacPower(targetSeat, value == 1.0, ok, fail) }
            "window" -> coroutineScope {
                val start = windowStart ?: error("창문 시작 위치 미확인")
                val sampler = launch { while (true) {
                    runCatching { withTimeout(400) { readWindow(area) } }.getOrNull()?.let { onObserved(start, it) }
                    delay(100)
                } }
                try { suspendCancellableCoroutine<Unit> { c -> vehicle.getWindow().setWindowPosition(windowArea(area), value.toInt(),
                    { returnedArea, returned -> if (c.isActive) {
                        if (returnedArea == windowArea(area) && returned == value.toInt()) c.resume(Unit)
                        else c.resumeWithException(IllegalStateException("창문 응답 불일치"))
                    } }, { if (c.isActive) c.resumeWithException(it) }) } }
                finally { sampler.cancelAndJoin() }
            }
            else -> error("허용되지 않은 설정")
        }
    }
    }

    private suspend fun moveTo(key: String, target: Double, area: RestControlArea, capability: RestControlCapability,
        authorize: () -> Boolean, onDispatch: () -> Unit, onObserved: (Double, Double) -> Unit) {
        requireParked()
        requireAuthorization(authorize)
        check(!motionStopUnconfirmed && unsettledMoves.get() == 0) { "이전 이동 정지를 확인하지 못했습니다 · 차량에서 직접 정지해 주세요" }
        val start = withTimeout(500) { getValue(key, area) }
        check(RestSafety.validCapabilityValue(start, capability.min, capability.max, capability.step, capability.origin)) { "현재 위치가 지원 범위 밖입니다" }
        requireParked()
        requireAuthorization(authorize)
        onObserved(start, start)
        onDispatch()
        if(RestMotionPolicy.direction(start, target) == 0) return
        val epoch = motionEpoch.incrementAndGet()
        moving = key to area
        check(preferences.edit().putBoolean("motionStopUnconfirmed", true).commit()) { "이동 정지 복구 기록 저장 실패" }
        RestMotionRunner(now = { SystemClock.elapsedRealtime() }).run(start, target, capability.min!!, capability.max!!,
            guard = {
                requireParked()
                check(motionEpoch.get() == epoch) { "차량 이동 작업이 중지되었습니다" }; requireAuthorization(authorize)
            },
            read = { getValue(key, area) },
            move = { direction -> requestMove(key, area, if(direction > 0) MoveControl.FORWARD else MoveControl.BACKWARD, epoch) },
            stop = { stopAndConfirm(key, area) },
            stopped = { confirmed ->
                val safe = confirmed && unsettledMoves.get() == 0
                markMotionStopUnknown(!safe); if(safe) moving = null; controlObserver()
            },
            observedPosition = { value -> onObserved(start, value) },
            confirmedPosition = { stable ->
                journalStore.update { raw ->
                    val j = JSONObject(raw)
                    j.optJSONObject(key)?.put("applied", stable)?.put("confirmed", true)
                    j.toString()
                }
            })
    }

    private fun sendMove(key: String, area: RestControlArea, command: MoveControl, ok: () -> Unit, fail: (Exception) -> Unit) {
        try {
            when(key) {
                "seatRecline" -> vehicle.getSeat().moveSeatBackrestAngle(seatArea(area), command, { actualArea, actual ->
                    if(describe(actualArea) == area && actual == command) ok() else fail(IllegalStateException("시트 이동 응답 불일치"))
                }, fail)
                "blind" -> vehicle.getWindow().moveWindowBlind(windowArea(area), command, { actualArea, actual ->
                    if(describe(actualArea) == area && actual == command) ok() else fail(IllegalStateException("블라인드 이동 응답 불일치"))
                }, fail)
                else -> fail(IllegalArgumentException("허용되지 않은 이동"))
            }
            Log.i("KkaebiMotion", "key=$key area=${area.id} command=${command.name}")
        } catch(e: Exception) { fail(e) }
    }
    private suspend fun requestMove(key: String, area: RestControlArea, command: MoveControl, epoch: Long) = withTimeout(500) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val settled = AtomicBoolean(false)
            unsettledMoves.incrementAndGet()
            sendMove(key, area, command, {
                if(settled.compareAndSet(false, true)) {
                    if(continuation.isActive && motionEpoch.get() == epoch) {
                        unsettledMoves.decrementAndGet(); continuation.resume(Unit)
                    } else {
                        // Prevent a new movement until the late MOVE has been followed by acknowledged STOP.
                        sendMove(key, area, MoveControl.STOP,
                            { if(motionEpoch.get() == epoch || moving == (key to area)) moving = null
                              markMotionStopUnknown(false); unsettledMoves.decrementAndGet() },
                            { markMotionStopUnknown(true); unsettledMoves.decrementAndGet() })
                    }
                }
            }, {
                if(settled.compareAndSet(false, true)) unsettledMoves.decrementAndGet()
                if(continuation.isActive) continuation.resumeWithException(it)
            })
            continuation.invokeOnCancellation { sendMove(key, area, MoveControl.STOP, {}, { markMotionStopUnknown(true) }) }
        }
    }
    private suspend fun stopAndConfirm(key: String, area: RestControlArea, clearFault: Boolean = true) {
        var failure: Exception? = null
        repeat(2) {
            try {
                withTimeout(700) { suspendCancellableCoroutine<Unit> { c ->
                    sendMove(key, area, MoveControl.STOP, { if(c.isActive) c.resume(Unit) }, { if(c.isActive) c.resumeWithException(it) })
                } }
                if(clearFault && unsettledMoves.get() == 0) markMotionStopUnknown(false)
                return
            } catch(e: Exception) { failure = e }
        }
        markMotionStopUnknown(true)
        throw IllegalStateException("이동 정지 응답을 확인하지 못했습니다", failure)
    }

    private fun markMotionStopUnknown(value: Boolean) {
        motionStopUnconfirmed = value
        if(!preferences.edit().putBoolean("motionStopUnconfirmed", value).commit()) motionStopUnconfirmed = true
    }
    private fun journal() = JSONObject(journalStore.snapshot())
    private fun record(key: String, before: Double, applied: Double, confirmed: Boolean = false, dispatched: Boolean = false,
        area: RestControlArea, cabinConsent: Boolean, ownerAlarmId: String) = journalStore.update { raw ->
        val j = JSONObject(raw)
        val createdAt = j.optJSONObject(key)?.optLong("createdAt", 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        j.put(key, JSONObject().put("createdAt", createdAt).put("before", before).put("applied", applied).put("confirmed", confirmed).put("dispatched", dispatched)
            .put("areaKind", area.kind).put("areas", JSONArray(area.names)).put("cabinConsent", cabinConsent).put("ownerAlarmId", ownerAlarmId)).toString()
    }
    private fun markRestoreDispatched(key: String) = journalStore.update { raw ->
        val j = JSONObject(raw)
        val item = j.getJSONObject(key)
        val state = RestJournalConfirmation(item.optBoolean("confirmed", false), item.optBoolean("restoreDispatched", false)).beginRestoreDispatch()
        item.put("confirmed", state.appliedConfirmed).put("restoreDispatched", state.restoreDispatched)
        j.toString()
    }
    private fun markDispatched(key: String) = journalStore.update { raw ->
        val j = JSONObject(raw); j.getJSONObject(key).put("dispatched", true); j.toString()
    }
    private fun removeKnownUndispatched(key: String) = journalStore.update { raw ->
        val j = JSONObject(raw)
        val item = j.optJSONObject(key)
        if (item != null && RestWritePolicy.knownUndispatched(item.opt("dispatched") as? Boolean)) j.remove(key)
        j.toString()
    }
    private fun clearKnownUndispatched() = journalStore.update { raw ->
        val j = JSONObject(raw)
        j.keys().asSequence().toList().forEach { key ->
            if (RestWritePolicy.knownUndispatched(j.optJSONObject(key)?.opt("dispatched") as? Boolean)) j.remove(key)
        }
        j.toString()
    }
    private fun removeRecord(key: String) = journalStore.update { raw ->
        val j = JSONObject(raw); j.remove(key); j.toString()
    }

    private fun areaFromRecord(key: String, item: JSONObject): RestControlArea {
        val values = item.optJSONArray("areas")
        if(values == null) {
            check(key in setOf("temperature", "fan", "brightness", "seatVentilation", "hvacPower")) { "이동 영역 복원 기록 없음" }
            return describe(driver) // Older releases wrote these five properties to this exact driver area only.
        }
        return RestControlArea(item.getString("areaKind"), (0 until values.length()).map { values.getString(it) }.sorted())
    }
    fun setForeground(value: Boolean) { foreground = value; observeSafety(); if(!value) stopMotion() }
    fun stopMotion() {
        motionEpoch.incrementAndGet()
        moving?.let { (key, area) -> sendMove(key, area, MoveControl.STOP, {}, {}) }
    }
    fun invalidate() { gearAt = 0; speedAt = 0; restricted = true; observeSafety(); stopMotion() }
    suspend fun close() {
        moving?.let { (key, area) -> runCatching { stopAndConfirm(key, area) } }
        closed = true; invalidate(); connected = false
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.release() }
        runCatching { properties?.unregisterCallback(speedListener) }
        runCatching { ux?.unregisterListener() }
        runCatching { androidCar?.disconnect() }
    }

    private suspend fun <T> read(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): T = suspendCancellableCoroutine { c ->
        call({ value -> if (c.isActive) { if (value == null) c.resumeWithException(IllegalStateException("값 사용 불가")) else c.resume(value) } }, { if (c.isActive) c.resumeWithException(it) })
    }
    private suspend fun <T> write(call: ((SeatArea, T?) -> Unit, (Exception) -> Unit) -> Unit): T? = suspendCancellableCoroutine { c ->
        call({ area, value -> Log.i("KkaebiControl", "write callback area=${describe(area).id} value=$value"); if (c.isActive) c.resume(value) },
            { Log.w("KkaebiControl", "write callback failure=${it.message}"); if (c.isActive) c.resumeWithException(it) })
    }
}
