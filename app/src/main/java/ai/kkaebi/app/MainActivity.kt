package ai.kkaebi.app

import ai.kkaebi.app.rest.RestVehicleGateway
import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.navi.DrowsyGuide
import ai.kkaebi.app.navi.DrowsyComfortPolicy
import ai.kkaebi.app.navi.NaviBridge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ai.kkaebi.app.speech.Ears
import ai.kkaebi.app.speech.Voice
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import ai.kkaebi.app.alarm.RestAlarmController
import ai.kkaebi.app.alarm.RestSoundPlayer
import ai.kkaebi.app.gleo.GleoIntents
import ai.kkaebi.app.gleo.GleoPreparationQueue
import ai.kkaebi.app.gleo.NativeInputPolicy
import ai.kkaebi.app.rest.RestRoutineParallel
import ai.kkaebi.app.rest.RestRoutinePolicy
import ai.kkaebi.app.rest.DemoAccessPolicy
import ai.kkaebi.app.rest.RestPosePolicy
import android.animation.ValueAnimator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.activity.OnBackPressedCallback
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayInputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Bundled offline UI. The native allowlist independently validates every vehicle request. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var webReady = false
    private var webGeneration = 0L
    private var demoMode = false
    private var demoInterrupted = false
    private var audioSteps = JSONArray()
    private val routinePrefs by lazy { getSharedPreferences("rest-routine", MODE_PRIVATE) }
    private var routineEpoch = 0L
    private var wakeAuthorizedInThisForeground = false
    private var nativeCommand: JSONObject? = null
    private var heard: JSONObject? = null
    private var speechResult: JSONObject? = null
    private lateinit var gateway: RestVehicleGateway
    private lateinit var drowsyGuide: DrowsyGuide
    private lateinit var drowsyNavi: NaviBridge
    private val drowsyLock = Mutex()
    private var drowsyRestoring = false
    private lateinit var voice: Voice
    private lateinit var ears: Ears
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
        alarmActionMessage = "요청을 완료하지 못했습니다 · 상태를 확인하고 다시 시도해 주세요"
        if (::gateway.isInitialized) publish()
    })
    private var polling: Job? = null
    private var initialization: Job? = null
    private var control: Job? = null
    private var permissionReady = false
    private var active = false
    private var initialized = false
    private var navi: NaviHelper? = null
    @Volatile private var naviReady = false
    private var nextRestAreaM: Double? = null
    private var nextRestAreaAt = 0L
    private var naviStatus = "내비게이션 서비스 확인 중"
    private var audioStatus = "음성 서비스 확인 중"
    private lateinit var alarms: RestAlarmController
    private lateinit var restSound: RestSoundPlayer
    private val preparationQueue = GleoPreparationQueue()
    private var preparationDispatch: Job? = null
    private var foregroundSignalsChecked = false
    private var alarmActionMessage = ""
    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            nextRestAreaM = tbtInfoList.firstOrNull {
                it.type == TurnType.TURN_RESTAREA || it.type == TurnType.TURN_ENTER_SERVICEAREA
            }?.distance?.takeIf { it.isFinite() && it >= 0 }
            nextRestAreaAt = System.currentTimeMillis()
        }
        override fun onNavigationStatus(status: NavigationStatus) {
            if (status != NavigationStatus.ROUTE_DRIVE) { nextRestAreaM = null; nextRestAreaAt = 0 }
        }
        override fun onError(errorCode: NaviErrorCode) { naviStatus = "내비게이션 요청 실패: $errorCode"; nextRestAreaM = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveExternalIntent(intent)
        alarms = RestAlarmController(this)
        restSound = RestSoundPlayer(this).also { it.kind = getPreferences(MODE_PRIVATE).getString("restSoundKind", "rain") ?: "rain" }
        alarms.channels()
        gateway = RestVehicleGateway(this)
        val areas: List<RestArea> = runCatching { Gson().fromJson<List<RestArea>>(
            assets.open("rest_areas.json").bufferedReader().use { it.readText() },
            object : TypeToken<List<RestArea>>() {}.type) }.getOrDefault(emptyList())
        drowsyGuide = DrowsyGuide(areas)
        drowsyNavi = NaviBridge(this).also { bridge ->
            bridge.onLocation = { lat, lng, heading -> runOnUiThread {
                val fresh = System.currentTimeMillis() - bridge.locationAt in 0..15_000
                updateDrowsyLocation(if (fresh) lat else null, if (fresh) lng else null, if (fresh) heading else null)
            } }
            bridge.onWaypointConfirmed = { runOnUiThread { drowsyGuide.waypointConfirmed(); publish() } }
            bridge.onRouteDistance = { meters -> runOnUiThread { drowsyGuide.routeDistance(meters); publish() } }
            bridge.onResumeChanged = { runOnUiThread { publish() } }
            bridge.start()
        }
        voice = Voice(this)
        ears = Ears(this)
        setupWeb()
        if(routine().optString("phase") == "waking") updateRoutine("failed", "앱이 다시 시작되어 자동 복원을 재실행하지 않았습니다 · 차량 설정을 확인해 주세요", attempted = true)
        else if(routine().optString("phase") == "preparing") updateRoutine("resting", "앱이 다시 시작되었습니다 · 환경 설정을 재적용하지 않았습니다")
        publish()
        val missing = arrayOf("android.car.permission.CAR_SPEED", Manifest.permission.RECORD_AUDIO, "pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.NAVI_ROUTE_SEARCH")
            .filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { permissionReady = true } else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) { permissionReady = true; activate() }
        if (requestCode == 11) publish()
    }
    override fun onResume() { super.onResume(); active = true; if(::web.isInitialized) { web.onResume(); web.resumeTimers() }; if(::gateway.isInitialized) gateway.setForeground(true); if (::alarms.isInitialized) alarms.reconcileOnOpen(); activate(); dispatchCommand(); publish() }
    private fun activate() {
        if (!active || !permissionReady || !::gateway.isInitialized) return
        if (!initialized) {
            if (initialization?.isActive == true) return
            initialization = scope.launch {
                gateway.start()
                initialized = true
                runCatching {
                    navi = NaviHelper(this@MainActivity).also { helper ->
                        helper.addListener(naviListener)
                        helper.initialize({ naviReady = true; naviStatus = "내비 연결 · 검색 화면 요청·경로상 쉼터 표시 지원"; runCatching { helper.getTBTInfo() } }, { naviReady = false; naviStatus = "내비 권한 또는 서비스 사용 불가" })
                    }
                }.onFailure { naviStatus = "내비 권한 또는 서비스 사용 불가" }
                activate()
            }
            return
        }
        if (polling?.isActive == true) return
        runCatching { navi?.getTBTInfo() }
        voice.start()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ears.start()
        polling = scope.launch {
            while (isActive && active) {
                gateway.poll()
                drowsyGuide.tick()
                if (drowsyGuide.snapshot.active && drowsyGuide.snapshot.restArea != null) {
                    val fresh = System.currentTimeMillis() - drowsyNavi.locationAt in 0..15_000
                    drowsyGuide.location(if (fresh) drowsyNavi.lat else null, if (fresh) drowsyNavi.lng else null,
                        if (fresh) drowsyNavi.heading else null, gateway.parked())
                }
                if (drowsyGuide.snapshot.state == "arrived" && !drowsyRestoring) {
                    drowsyRestoring = true
                    scope.launch { drowsyLock.withLock { restoreDrowsyComfort() }; publish() }
                }
                foregroundSignalsChecked = true
                dispatchCommand()
                if (restSound.playing && (!gateway.parked() || alarms.snapshot().state != "scheduled")) restSound.stop("알람 또는 차량 상태로 휴식 소리를 멈췄습니다")
                audioStatus = if (voice.engine != "none") "음성 안내 사용 가능" else "음성 안내를 사용할 수 없습니다"
                reconcileRoutine()
                refreshAudioSteps()
                publish()
                delay(1000)
            }
        }
    }
    override fun onPause() {
        active = false
        foregroundSignalsChecked = false
        clearPreparationCommand()
        if (demoMode) demoInterrupted = true
        if (demoMode) publishDemoContext()
        wakeAuthorizedInThisForeground = false
        gateway.setForeground(false)
        if(::web.isInitialized) { web.onPause(); web.pauseTimers() }
        polling?.cancel(); polling = null
        control?.cancel()
        restSound.stop("앱이 화면을 벗어나 휴식 소리를 멈췄습니다 · 예약 알람은 유지됩니다")
        stopAudioSteps()
        nextRestAreaM = null; nextRestAreaAt = 0
        gateway.invalidate()
        voice.stop(); ears.stop()
        publish()
        super.onPause()
    }
    override fun onDestroy() {
        active = false; scope.cancel()
        val previousControl = control
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            previousControl?.cancelAndJoin()
            gateway.close()
        }
        if(::web.isInitialized) { web.removeJavascriptInterface("KkaebiNative"); web.destroy() }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        if (::drowsyNavi.isInitialized) drowsyNavi.stop()
        super.onDestroy()
    }

    private fun publish() {
        if (!::gateway.isInitialized || !::alarms.isInitialized) return
        if (demoMode) { publishDemoContext(); return }
        val controlState = gateway.controlOperationState
        val state = JSONObject()
            .put("bridgeVersion", 10).put("restOrchestration", true)
            .put("drowsy", drowsyJson())
            .put("demoAvailable", canOpenDemo())
            .put("lifecycleActive", active).put("systemAnimationsEnabled", ValueAnimator.areAnimatorsEnabled())
            .put("fontScale", resources.configuration.fontScale.toDouble())
            .put("restSession", routine()).put("audioSteps", JSONArray(audioSteps.toString()))
            .put("restPose", gateway.restPose)
            .put("nativeCommand", nativeCommand ?: JSONObject.NULL)
            .put("heard", heard ?: JSONObject.NULL)
            .put("speechResult", speechResult ?: JSONObject.NULL)
            .put("mode", if (gateway.connected) "live" else "unavailable")
            .put("gear", gateway.gear)
            .put("speedKmh", gateway.speedKmh ?: JSONObject.NULL)
            .put("observedAt", System.currentTimeMillis())
            .put("gearAt", gateway.gearAt).put("speedAt", gateway.speedAt)
            .put("restricted", gateway.restricted).put("connection", gateway.connection)
            .put("tts", voice.engine != "none").put("stt", ears.available)
            .put("audioStatus", audioStatus).put("controlStatus", gateway.controlStatus)
            .put("motionStopUnconfirmed", gateway.motionStopUnconfirmed)
            .put("pendingRestore", gateway.pendingRestore).put("naviStatus", naviStatus)
            .put("routeResume", drowsyNavi.routeResumeJson())
            .put("nextRestAreaM", nextRestAreaM?.takeIf { System.currentTimeMillis() - nextRestAreaAt in 0..30_000 } ?: JSONObject.NULL)
            .put("controlCapabilities", gateway.controlCapabilities).put("controlResults", JSONObject(controlState.resultsJson))
            .put("controlOperationId", controlState.id).put("controlOperation", controlState.operation ?: JSONObject.NULL)
            .put("controlPhase", controlState.phase)
            .put("controlExecution", JSONObject().put("mode", "bounded-parallel").put("maxConcurrentLanes", 3))
            .put("controlSteps", JSONArray().apply { controlState.steps.forEach { step ->
                put(JSONObject().put("operationId", controlState.id).put("key", step.key).put("state", step.state)
                    .put("lane", step.lane ?: JSONObject.NULL).put("dependencies", JSONArray(step.dependencies)).put("blockedBy", JSONArray(step.blockedBy))
                    .put("requested", step.requested?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("target", step.requested?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("before", step.before?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("actual", step.actual?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("observed", step.observed?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("observedAt", step.observedAt ?: JSONObject.NULL)
                    .put("positionProgress", step.positionProgress?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                    .put("progressSource", step.progressSource ?: JSONObject.NULL)
                    .put("message", step.message))
            } })
            .put("proactiveCheckinsAvailable", false)
            .put("checkinMessage", "통화·내비 음성 상황을 확인할 수 없어 요청할 때만 대화합니다")
            .put("gleoStatus", "스키마 포함 · 공식 등록·검토 대기 · 외부 호출은 준비 화면만 허용")
            .put("restSoundAvailable", true).put("restSoundKind", restSound.kind).put("restSoundPlaying", restSound.playing).put("restSoundMessage", restSound.message)
            .put("alarmActionMessage", alarmActionMessage)
        val alarm = alarms.json()
        alarm.keys().forEach { key -> state.put(key, alarm.get(key)) }
        val generation = webGeneration
        runOnUiThread { if(generation == webGeneration && !demoMode && webReady && !isDestroyed) web.evaluateJavascript("window.kkaebiNativeState && window.kkaebiNativeState(${state});", null) }
    }

    private fun drowsyJson(): JSONObject {
        val s = drowsyGuide.snapshot
        val area = s.restArea?.let { JSONObject().put("name", it.name).put("road", it.road)
            .put("parking", it.parking).put("toilet", it.toilet) } ?: JSONObject.NULL
        val comfort = JSONObject().apply { s.comfort.forEach { (key, c) -> put(key, JSONObject()
            .put("state", c.state).put("value", c.value ?: JSONObject.NULL).put("original", c.original ?: JSONObject.NULL)) } }
        return JSONObject().put("active", s.active).put("requestId", s.requestId).put("state", s.state)
            .put("restArea", area).put("distanceM", s.distanceM ?: JSONObject.NULL)
            .put("routeDistanceM", s.routeDistanceM ?: JSONObject.NULL).put("waypoint", s.waypoint)
            .put("comfort", comfort).put("message", s.message)
    }

    private var drowsyWaypointRetries = 0
    private fun updateDrowsyLocation(lat: Double?, lng: Double?, heading: Double?) {
        if (!active || !drowsyGuide.snapshot.active) return
        drowsyGuide.location(lat, lng, heading, gateway.parked())
        val s = drowsyGuide.snapshot
        if (s.restArea != null && s.waypoint == "unavailable" && drowsyWaypointRetries < 5 && drowsyNavi.naviReady) { drowsyWaypointRetries++; if (drowsyNavi.addWaypoint(s.restArea)) drowsyGuide.waypointRequested() }
        if (s.restArea != null && s.waypoint == "none") {
            if (drowsyNavi.addWaypoint(s.restArea)) drowsyGuide.waypointRequested()
            else drowsyGuide.waypointUnavailable()
        }
        if (drowsyGuide.snapshot.state == "arrived" && !drowsyRestoring) {
            drowsyRestoring = true
            scope.launch { drowsyLock.withLock { restoreDrowsyComfort() }; publish() }
        }
        publish()
    }

    private suspend fun applyDrowsyComfort(data: JSONObject) {
        drowsyGuide.command(data.optString("requestId"))
        if (!drowsyGuide.snapshot.active || drowsyGuide.snapshot.state == "arrived") {
            drowsyGuide.notice("주행 안내 중에만 환기를 조절할 수 있어요"); publish(); return
        }
        val allowed = setOf("requestId", "fan", "seatVentilation", "window")
        val valid = data.keys().asSequence().all { it in allowed && (it == "requestId" || DrowsyComfortPolicy.allowed(it, data.opt(it))) }
        if (!valid) { drowsyGuide.notice("허용되지 않은 환기 요청이에요"); publish(); return }
        for (key in DrowsyGuide.keys) {
            if (!data.has(key)) continue
            val previous = drowsyGuide.snapshot.comfort.getValue(key)
            if (key == "window" && data.getBoolean(key) && previous.original != null && previous.state != "restored") continue
            val target = when (key) {
                "fan" -> data.getInt(key).toDouble()
                "seatVentilation" -> data.getInt(key).toDouble()
                else -> if (data.getBoolean(key)) null else previous.original
            }
            if (key == "window" && data.getBoolean(key) == false && previous.original == null) {
                drowsyGuide.comfort(key, "restored", null, null); continue
            }
            val result = gateway.drowsyWrite(key, target, previous.original,
                if (key == "window" && target != null) previous.value else null)
            drowsyGuide.comfort(key, if (key == "window" && target != null && result.state == "applied") "restored" else result.state,
                result.value, result.original)
            drowsyGuide.notice(when (result.state) {
                "applied" -> "요청한 환기를 적용했어요"
                "unsupported" -> "이 차량에서는 해당 환기를 조절할 수 없어요"
                else -> "환기 적용을 확인하지 못했어요"
            })
            publish()
        }
    }

    private suspend fun restoreDrowsyComfort(): Boolean {
        var allRestored = true
        for (key in listOf("window", "seatVentilation", "fan")) {
            val previous = drowsyGuide.snapshot.comfort.getValue(key)
            val original = previous.original ?: continue
            if (previous.state == "restored") continue
            val result = gateway.drowsyWrite(key, original, original, previous.value)
            drowsyGuide.comfort(key, if (result.state == "applied") "restored" else "failed", result.value, original)
            if (result.state != "applied") { allRestored = false; drowsyGuide.notice("일부 환기 설정을 되돌리지 못했어요 · 차량에서 확인해 주세요") }
            publish()
        }
        return allRestored
    }

    private fun onAction(type: String, data: JSONObject) {
            if (demoMode) return
            if (type.length > 32 || data.toString().length > 4096) return
            scope.launch {
                if (!active || demoMode) return@launch
                when (type) {
                    "drowsyStart" -> drowsyLock.withLock {
                        if (!restoreDrowsyComfort()) { publish(); return@withLock }
                        drowsyRestoring = false
                        drowsyNavi.clearGuide()
                        drowsyWaypointRetries = 0
                        drowsyGuide.start(data.optString("requestId"))
                        drowsyNavi.rememberDrowsyRoute()
                        publish()
                        drowsyNavi.requestLocation()
                        val fresh = System.currentTimeMillis() - drowsyNavi.locationAt in 0..15_000
                        updateDrowsyLocation(if (fresh) drowsyNavi.lat else null,
                            if (fresh) drowsyNavi.lng else null, if (fresh) drowsyNavi.heading else null)
                    }
                    "drowsyStop" -> drowsyLock.withLock {
                        drowsyNavi.clearGuide()
                        val restored = restoreDrowsyComfort()
                        drowsyGuide.stop()
                        if (!restored) drowsyGuide.notice("일부 환기 설정을 되돌리지 못했어요 · 차량에서 확인해 주세요")
                        publish()
                    }
                    "drowsyComfort" -> drowsyLock.withLock { applyDrowsyComfort(data) }
                    "routeResume" -> drowsyLock.withLock {
                        if (!gateway.pendingRestore && gateway.controlOperationState.phase != "running" && drowsyNavi.hasPendingRoute()) drowsyNavi.resumeRoute()
                        publish()
                    }
                    "ready" -> { webReady = true; publish(); dispatchCommand() }
                    "openDemo" -> {
                        if (canOpenDemo()) {
                            voice.cancel(); ears.cancel(); restSound.stop()
                            demoInterrupted = false
                            showWebPage(true)
                        } else {
                            alarmActionMessage = "주차를 확인하고 알람·환경 작업·복원 대기를 마친 뒤 동작 시연을 열어 주세요"
                            publish()
                        }
                    }
                    "startRest" -> startRest(data)
                    "captureRestPose" -> {
                        val running = control?.isCompleted == false || gateway.controlOperationState.phase == "running"
                        if (running) {
                            alarmActionMessage = "진행 중인 작업을 마친 뒤 현재 자세를 저장해 주세요"; publish()
                        } else if (!RestPosePolicy.canCapture(active, gateway.parked(), false, alarms.snapshot().state,
                                gateway.pendingRestore, gateway.motionStopUnconfirmed)) {
                            gateway.rejectPoseCapture("안전한 주차와 알람·복원 대기 종료를 확인한 뒤 자세를 저장해 주세요"); publish()
                        } else control = scope.launch {
                            gateway.captureRestPose(authorizeRead = {
                                active && !demoMode && alarms.snapshot().state in setOf("idle", "failed")
                            }, onChanged = { publish() })
                        }
                    }
                    "cancelRoutine", "stopControls" -> {
                        routineEpoch++
                        gateway.stopMotion(); control?.cancel()
                        voice.cancel(); restSound.stop()
                        updateRoutine("failed", "차량 환경 작업을 멈췄습니다 · 예약 알람은 유지됩니다", attempted = true)
                        stopAudioSteps(); publish()
                    }
                    "speak" -> {
                        val text = data.optString("text").take(120)
                        val requestId = data.optString("requestId").take(64)
                        if (text.isNotBlank() && voice.engine != "none") voice.speak(text) {
                            speechResult = JSONObject().put("requestId", requestId).put("completed", voice.lastCompletedSuccessfully).put("eventId", UUID.randomUUID().toString())
                            publish()
                        } else {
                            speechResult = JSONObject().put("requestId", requestId).put("completed", false).put("eventId", UUID.randomUUID().toString())
                            publish()
                        }
                    }
                    "listen" -> {
                        val available = ears.listen(6000) { text, _ ->
                            if (active) {
                                heard = JSONObject().put("id", UUID.randomUUID().toString()).put("text", text?.take(200) ?: "")
                                    .put("recognized", !text.isNullOrBlank())
                                publish()
                            }
                        }
                        if (!available) { audioStatus = "음성 인식을 사용할 수 없습니다 · 정차 후 화면으로 선택해 주세요"; publish() }
                    }
                    "stopSpeech" -> { voice.cancel(); ears.cancel() }
                    "applyRest" -> if (control?.isCompleted != false) {
                        if (!alarms.snapshot().let { it.state == "scheduled" && it.id == data.optString("alarmId") }) {
                            gateway.rejectControlOperation("apply", "현재 예약된 알람을 확인할 수 없어 차량 설정을 적용하지 않았습니다")
                            publish(); return@launch
                        }
                        val numbersValid = listOf("temperature", "fan", "seatVentilation").all { key ->
                            !data.has(key) || data.isNull(key) || when(key) {
                                "temperature" -> NativeInputPolicy.temperature(data.opt(key)) != null
                                "fan" -> NativeInputPolicy.integer(data.opt(key), 1, 3) != null
                                else -> NativeInputPolicy.integer(data.opt(key), 0, 3) != null
                            }
                        }
                        val booleansValid = listOf("dim", "hvacPower").all { key -> !data.has(key) || data.isNull(key) || data.opt(key) is Boolean }
                        if (!numbersValid || !booleansValid) {
                            alarmActionMessage = "프리셋 값 형식이 올바르지 않아 차량 설정을 적용하지 않았습니다"
                            gateway.rejectControlOperation("apply", alarmActionMessage); publish(); return@launch
                        }
                        control = scope.launch {
                            try { gateway.apply(
                                temperature = if(data.has("temperature") && !data.isNull("temperature")) data.optDouble("temperature", Double.NaN) else null,
                                fan = if(data.has("fan") && !data.isNull("fan")) data.optInt("fan", -1) else null,
                                dim = if(data.has("dim") && !data.isNull("dim")) data.optBoolean("dim") else null,
                                seatVentilation = if(data.has("seatVentilation") && !data.isNull("seatVentilation")) data.optInt("seatVentilation", -1) else null,
                                hvacPower = if(data.has("hvacPower") && !data.isNull("hvacPower")) data.optBoolean("hvacPower") else null,
                                authorizeWrite = { alarms.snapshot().let { it.state == "scheduled" && it.id == data.optString("alarmId") } },
                                onChanged = { publish() }
                            ) } finally { publish() }
                        }
                    }
                    "restoreRest" -> if (control?.isCompleted != false) {
                        updateRoutine("waking", "원래 차량 설정을 확인하고 있습니다", attempted = true)
                        control = scope.launch { try {
                            gateway.restore(onChanged = { bindRoutineOperation(routine().optString("alarmId")); publish() })
                            updateRoutine(if(gateway.pendingRestore) "failed" else "complete", gateway.controlStatus)
                            if (!gateway.pendingRestore && drowsyNavi.hasPendingRoute()) drowsyNavi.resumeRoute()
                        } finally { publish() } }
                    }
                    "navigate" -> {
                        // A constant search handoff, never a fabricated route or user-supplied coordinate.
                        naviStatus = if (!gateway.parked()) "안전하게 정차한 뒤 차량 내비 검색을 열어 주세요"
                        else if (!naviReady || navi == null || ActivityCompat.checkSelfPermission(this@MainActivity,
                                "pleos.car.permission.NAVI_CUSTOM_MAP") != PackageManager.PERMISSION_GRANTED)
                            "내비 검색 권한 또는 서비스 사용 불가 · 차량 내비에서 직접 찾아 주세요"
                        else runCatching {
                            navi!!.requestNavigationScreen(NavigationScreenRequest.SearchPlace("졸음쉼터"))
                            "차량 내비에 졸음쉼터 검색 화면 열기를 요청했습니다"
                        }.getOrElse { "내비 검색 화면을 요청하지 못했습니다 · 차량 내비에서 직접 찾아 주세요" }
                        publish()
                    }
                    "startAlarm" -> {
                        val requestId = data.optString("requestId")
                        if (!gateway.parked()) alarms.failRequest(requestId, "신선한 P·정지 상태를 확인한 뒤 알람을 설정해 주세요")
                        else if (NativeInputPolicy.integer(data.opt("durationMinutes"), 10, 30) == null) alarms.failRequest(requestId, "휴식 시간은 10~30분 중 선택해 주세요")
                        else alarms.schedule(data.optInt("durationMinutes", -1), requestId)
                        publish()
                    }
                    "ackAlarm", "cancelAlarm" -> {
                        val acknowledged=alarms.acknowledge(data.optString("alarmId"))
                        if(acknowledged) {
                            val r = routine()
                            if(r.optString("alarmId") == data.optString("alarmId")) {
                                val requested = data.opt("restore") == true && r.optBoolean("autoRestore") && active && gateway.parked()
                                wakeAuthorizedInThisForeground = requested
                                r.put("wakeRequested", requested)
                                if(!requested) r.put("phase", if(gateway.pendingRestore) "deferred" else "complete").put("message", "알람은 종료했습니다 · 안전한 정차 후 복원을 선택해 주세요")
                                saveRoutine(r)
                            }
                            routineEpoch++; gateway.stopMotion(); control?.cancelAndJoin(); control=null
                            voice.cancel(); restSound.stop(); stopAudioSteps()
                            reconcileRoutine()
                        }
                        alarmActionMessage = if(acknowledged) "알람 종료 확인" else "이전 알람 요청이어서 현재 알람은 유지했습니다"
                        publish()
                    }
                    "requestAlarmPermission" -> requestAlarmPermission()
                    "restSoundKind" -> { val k = data.optString("kind"); if (k in setOf("rain", "stream", "waves", "tone")) { restSound.kind = k; getPreferences(MODE_PRIVATE).edit().putString("restSoundKind", k).apply(); if (restSound.playing) restSound.start() }; publish() }
                    "restSound" -> {
                        if(data.optBoolean("enabled") && gateway.parked() && alarms.snapshot().state == "scheduled") restSound.start()
                        else restSound.stop()
                        publish()
                    }
                }
            }
    }
    private fun canOpenDemo() = DemoAccessPolicy.canEnter(
        active, gateway.parked(), control?.isActive == true || gateway.controlOperationState.phase == "running",
        alarms.snapshot().state, gateway.pendingRestore, gateway.motionStopUnconfirmed
    )

    private fun publishDemoContext() {
        val parked = gateway.parked()
        demoInterrupted = DemoAccessPolicy.interrupted(demoInterrupted, active, parked)
        val allowed = active && parked && !demoInterrupted
        val state = JSONObject().put("hosted", true).put("demo", true)
            .put("lifecycleActive", active).put("parked", parked)
            .put("interactiveAllowed", allowed).put("sessionInterrupted", demoInterrupted)
            .put("systemAnimationsEnabled", ValueAnimator.areAnimatorsEnabled())
            .put("motionAllowed", allowed && ValueAnimator.areAnimatorsEnabled())
            .put("fontScale", resources.configuration.fontScale.toDouble())
            .put("observedAt", System.currentTimeMillis()).put("gearAt", gateway.gearAt).put("speedAt", gateway.speedAt)
            .put("restricted", gateway.restricted)
            .put("message", if (demoInterrupted) "시연이 중지됐어요. 주차를 확인한 뒤 시연을 다시 열어 주세요." else "시연 · 차량 제어 없음")
        val generation = webGeneration
        runOnUiThread { if(generation == webGeneration && demoMode && webReady && !isDestroyed) web.evaluateJavascript("window.kkaebiDemoHostState && window.kkaebiDemoHostState(${state});", null) }
    }

    private fun setupWeb() {
        showWebPage(false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var waiting = false
            override fun handleOnBackPressed() {
                if(demoMode) { showWebPage(false); return }
                if(waiting) return
                waiting = true
                val generation = webGeneration
                val handler = Handler(Looper.getMainLooper())
                fun complete(handled: Boolean) {
                    if(!waiting) return
                    waiting = false
                    if(generation != webGeneration) return
                    if(!handled) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
                handler.postDelayed({ complete(false) }, 500)
                web.evaluateJavascript("Boolean(window.kkaebiHandleBack && window.kkaebiHandleBack())") { complete(it == "true") }
            }
        })
    }

    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    private fun showWebPage(demo: Boolean) {
        val previous = if (::web.isInitialized) web else null
        if (previous != null) clearPreparationCommand()
        demoMode = demo
        webReady = false
        val generation = ++webGeneration
        val page = if (demo) DemoAccessPolicy.DEMO_PAGE else DemoAccessPolicy.LIVE_PAGE
        web = WebView(this)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            // CSS applies the reported system fontScale once; WebView's default also scales text.
            textZoom = 100
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true; setSupportMultipleWindows(false)
        }
        // A separate WebView instance is essential: no vehicle command object ever exists in the demo.
        if (!demo) web.addJavascriptInterface(object {
            @JavascriptInterface fun post(type: String, json: String) {
                if(type.length > 32 || json.length > 4096) return
                val data = runCatching { JSONObject(json) }.getOrNull() ?: return
                runOnUiThread {
                    if(generation == webGeneration && !demoMode && (webReady || type == "ready")) onAction(type, data)
                }
            }
        }, "KkaebiNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if(generation != webGeneration) return true
                val url = request?.url?.toString()
                if(DemoAccessPolicy.isExit(demo, request?.isForMainFrame == true, url.orEmpty())) {
                    showWebPage(false)
                    return true
                }
                if(demo) {
                    val parked = gateway.parked()
                    demoInterrupted = DemoAccessPolicy.interrupted(demoInterrupted, active, parked)
                    if(DemoAccessPolicy.canNavigateDemo(true, request?.isForMainFrame == true,
                            active && parked && !demoInterrupted, url)) {
                        webReady = false
                        return false
                    }
                    publishDemoContext()
                }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if(generation == webGeneration && demo) webReady = false
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return blockedResponse()
                return if(DemoAccessPolicy.allowsAsset(demo, url.scheme, url.authority, url.path)) null else blockedResponse()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if(generation == webGeneration && (if(demo) DemoAccessPolicy.isDemoPage(url) else url == DemoAccessPolicy.LIVE_PAGE)) {
                    if(demo) webReady = true
                    if(demo) web.evaluateJavascript("typeof window.KkaebiNative !== 'undefined'") { present ->
                        if(generation == webGeneration) android.util.Log.i("KkaebiDemoIsolation", "vehicleBridgePresent=$present")
                    }
                    web.post {
                        if(generation != webGeneration) return@post
                        val d = resources.displayMetrics
                        android.util.Log.i("KkaebiWebMetrics", "demo=$demo viewPx=${web.width}x${web.height} density=${d.density} scaledDensity=${d.scaledDensity} densityDpi=${d.densityDpi} fontScale=${resources.configuration.fontScale} textZoom=${web.settings.textZoom} defaultFont=${web.settings.defaultFontSize} wide=${web.settings.useWideViewPort} overview=${web.settings.loadWithOverviewMode}")
                    }
                    publish()
                }
            }
        }
        setContentView(web)
        previous?.removeJavascriptInterface("KkaebiNative")
        previous?.stopLoading()
        previous?.destroy()
        web.loadUrl(page)
    }
    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun routine(): JSONObject = runCatching { JSONObject(routinePrefs.getString("record", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun saveRoutine(value: JSONObject) {
        check(routinePrefs.edit().putString("record", value.toString()).commit()) { "휴식 동의 저장 실패" }
    }
    private fun updateRoutine(phase: String, message: String, attempted: Boolean? = null) {
        val r = routine(); if(r.optString("alarmId").isBlank()) return
        if(phase in setOf("preparing", "waking") && r.optString("phase") != phase) r.put("controlOperationId", JSONObject.NULL)
        r.put("phase", phase).put("message", message)
        attempted?.let { r.put("wakeAttempted", it) }
        saveRoutine(r)
    }
    private fun bindRoutineOperation(alarmId: String) {
        val r = routine()
        if(r.optString("alarmId") == alarmId && r.optString("controlOperationId") != gateway.controlOperationState.id) {
            saveRoutine(r.put("controlOperationId", gateway.controlOperationState.id))
        }
    }
    private fun validPlan(plan: JSONObject): Boolean {
        val permitted = setOf("temperature", "fan", "dim", "seatVentilation", "hvacPower", "seatRecline", "blind", "blindArea", "window")
        if(plan.keys().asSequence().any { it !in permitted }) return false
        return plan.keys().asSequence().all { key -> plan.isNull(key) || when(key) {
            "temperature" -> NativeInputPolicy.temperature(plan.opt(key)) != null
            "fan" -> NativeInputPolicy.integer(plan.opt(key), 1, 3) != null
            "seatVentilation" -> NativeInputPolicy.integer(plan.opt(key), 0, 3) != null
            "seatRecline", "blind", "window" -> NativeInputPolicy.integer(plan.opt(key), -1_000_000, 1_000_000) != null
            "dim", "hvacPower" -> plan.opt(key) is Boolean
            "blindArea" -> plan.opt(key) is String && plan.getString(key).length <= 100
            else -> false
        } }
    }
    private fun hasPhysicalSelection(plan: JSONObject) = plan.keys().asSequence().any { it != "blindArea" && !plan.isNull(it) && (it != "dim" || plan.optBoolean(it)) }
    private fun startRest(data: JSONObject) {
        val request = data.optString("requestId")
        val plan = data.optJSONObject("plan")
        val strictFlags = listOf("cabinConsent", "motionConsent", "autoRestore", "voice", "restSound").all { !data.has(it) || data.opt(it) is Boolean }
        val alarm = alarms.snapshot()
        if(alarm.state in setOf("scheduled", "ringing") || control?.isCompleted == false) { publish(); return }
        val problem = when {
            plan == null || !strictFlags || !validPlan(plan) -> "휴식 설정 형식을 확인해 주세요"
            !gateway.parked() -> "안전한 곳에서 신선한 P·정지 상태를 확인해 주세요"
            gateway.pendingRestore && hasPhysicalSelection(plan) -> "이전 설정을 먼저 복원하거나 차량 설정 없이 알람만 시작해 주세요"
            listOf("seatRecline", "blind", "window").any { plan.has(it) && !plan.isNull(it) } && !data.optBoolean("motionConsent") -> "시트·블라인드·창문 주변 확인과 이동 동의가 필요합니다"
            NativeInputPolicy.integer(data.opt("durationMinutes"), 10, 30) == null -> "휴식 시간을 확인해 주세요"
            else -> null
        }
        if(problem != null) { alarms.failRequest(request, problem); alarmActionMessage = problem; publish(); return }
        val scheduled = alarms.schedule(data.getInt("durationMinutes"), request)
        if(scheduled.state != "scheduled" || scheduled.requestId != request) { publish(); return }
        val epoch = ++routineEpoch
        saveRoutine(JSONObject().put("requestId", request).put("alarmId", scheduled.id).put("phase", "preparing")
            .put("message", "예약을 확인했습니다 · 선택한 환경을 준비합니다").put("autoRestore", data.optBoolean("autoRestore"))
            .put("controlOperationId", JSONObject.NULL).put("wakeAttempted", false).put("wakeRequested", false).put("voice", data.optBoolean("voice")).put("restSound", data.optBoolean("restSound"))
            .put("cabinConsent", data.optBoolean("cabinConsent")).put("motionConsent", data.optBoolean("motionConsent"))
            .put("plan", JSONObject(plan!!.toString())))
        resetAudioSteps("sleep", data.optBoolean("voice"), data.optBoolean("restSound"))
        publish()
        control = scope.launch {
            try {
                RestRoutineParallel.run(
                    voice = if(data.optBoolean("voice")) ({ routineSpeak("안전하게 주차했어요. 선택한 환경을 준비할게요.", epoch) }) else null,
                    voiceFailed = { audioStep("voice", "failed", "음성 안내를 완료하지 못했습니다") }) {
                check(active && epoch == routineEpoch) { "휴식 준비가 중단되었습니다" }
                gateway.apply(
                    temperature = plan.opt("temperature").let { if(it is Number) it.toDouble() else null },
                    fan = plan.opt("fan").let { if(it is Number) it.toInt() else null },
                    dim = plan.opt("dim") as? Boolean,
                    seatVentilation = plan.opt("seatVentilation").let { if(it is Number) it.toInt() else null },
                    hvacPower = plan.opt("hvacPower") as? Boolean,
                    seatRecline = plan.opt("seatRecline").let { if(it is Number) it.toInt() else null },
                    blind = plan.opt("blind").let { if(it is Number) it.toInt() else null },
                    window = plan.opt("window").let { if(it is Number) it.toInt() else null },
                    blindArea = plan.optString("blindArea").takeIf { it.isNotBlank() },
                    cabinConsent = data.optBoolean("cabinConsent"), motionConsent = data.optBoolean("motionConsent"), ownerAlarmId = scheduled.id,
                    authorizeWrite = { active && epoch == routineEpoch && alarms.snapshot().let { it.state == "scheduled" && it.id == scheduled.id } },
                    onChanged = { bindRoutineOperation(scheduled.id); publish() })
                }
                if(epoch == routineEpoch) {
                    updateRoutine("resting", if(gateway.controlOperationState.phase == "complete") "휴식 환경 확인 완료" else "일부 환경을 적용하지 못했습니다 · 알람은 예약되어 있습니다")
                    if(data.optBoolean("restSound") && active && gateway.parked() && alarms.snapshot().state == "scheduled") {
                        audioStep("restSound", "writing", "휴식 소리 재생 요청")
                        restSound.start()
                    }
                }
            } catch(e: Exception) {
                if(epoch == routineEpoch) updateRoutine("resting", "환경 준비가 중단되었습니다 · 알람 예약과 복원 기록은 유지됩니다")
                throw e
            } finally { publish() }
        }
    }
    private fun reconcileRoutine() {
        val r = routine(); if(r.length() == 0) return
        val a = alarms.snapshot()
        if(a.id == r.optString("alarmId") && a.state == "idle" && !r.optBoolean("wakeRequested") && r.optString("phase") in setOf("resting", "preparing")) {
            routineEpoch++; wakeAuthorizedInThisForeground = false
            gateway.stopMotion(); control?.cancel(); voice.cancel(); restSound.stop(); stopAudioSteps()
            updateRoutine(if(gateway.pendingRestore) "deferred" else "complete", "알람만 종료했습니다 · 차량 복원은 정차 후 선택해 주세요")
            return
        }
        val decision = RestRoutinePolicy.wakeDecision(r.optString("alarmId"), a.id, a.state, r.optBoolean("autoRestore") && r.optBoolean("wakeRequested") && wakeAuthorizedInThisForeground,
            r.optBoolean("wakeAttempted"), r.optString("phase"), active, gateway.parked(), control?.isCompleted == false)
        if(decision == "defer") {
            wakeAuthorizedInThisForeground = false
            saveRoutine(r.put("wakeRequested", false).put("phase", "deferred").put("message", "안전한 정차를 확인한 후 복원을 다시 선택해 주세요"))
            return
        }
        if(decision != "wake") return
        updateRoutine("waking", "원래 차량 환경으로 돌아가고 있습니다", attempted = true) // Persist before the one allowed attempt.
        val epoch = ++routineEpoch
        restSound.stop(); voice.cancel(); resetAudioSteps("wake", r.optBoolean("voice"), false)
        control = scope.launch {
            try {
                val wakeText = if(r.optJSONObject("plan")?.has("seatRecline") == true)
                    "선택한 환경과 시트를 원래 위치로 복원할게요. 주변을 확인해 주세요." else "선택한 차량 환경을 원래 설정으로 복원할게요."
                RestRoutineParallel.run(
                    voice = if(r.optBoolean("voice")) ({ routineSpeak(wakeText, epoch) }) else null,
                    voiceFailed = { audioStep("voice", "failed", "음성 안내를 완료하지 못했습니다") }) {
                check(active && epoch == routineEpoch) { "복원이 중단되었습니다" }
                gateway.restore(expectedOwner = r.optString("alarmId"), onChanged = { bindRoutineOperation(r.optString("alarmId")); publish() })
                }
                updateRoutine(if(gateway.pendingRestore) "failed" else "complete", gateway.controlStatus)
                if (!gateway.pendingRestore && drowsyNavi.hasPendingRoute()) drowsyNavi.resumeRoute()
            } catch(e: Exception) { updateRoutine("failed", "자동 복원을 마치지 못했습니다 · 정차 후 복원을 다시 확인해 주세요"); throw e }
            finally { publish() }
        }
        publish()
    }
    private fun resetAudioSteps(stage: String, speak: Boolean, sound: Boolean) {
        val id = "${routine().optString("alarmId")}:$stage:${UUID.randomUUID()}"
        audioSteps = JSONArray().apply {
            if(speak) put(JSONObject().put("operationId", id).put("key", "voice").put("state", "queued").put("message", "음성 안내 대기"))
            if(sound) put(JSONObject().put("operationId", id).put("key", "restSound").put("state", "queued").put("message", "차량 환경 확인 후 소리 재생"))
        }
    }
    private fun audioStep(key: String, state: String, message: String) {
        audioSteps = JSONArray().apply { (0 until this@MainActivity.audioSteps.length()).forEach {
            val item = JSONObject(this@MainActivity.audioSteps.getJSONObject(it).toString())
            if(item.optString("key") == key) item.put("state", state).put("message", message)
            put(item)
        } }
        publish()
    }
    private fun stopAudioSteps() {
        (0 until audioSteps.length()).map { audioSteps.getJSONObject(it).optString("key") }.forEach { audioStep(it, "stopped", "안내·휴식 소리 중지") }
    }
    private fun refreshAudioSteps() {
        val item = (0 until audioSteps.length()).map { audioSteps.getJSONObject(it) }.firstOrNull { it.optString("key") == "restSound" } ?: return
        val state = item.optString("state")
        if(restSound.playing && state != "applied") audioStep("restSound", "applied", restSound.message)
        else if(!restSound.playing && state in setOf("writing", "applied")) audioStep("restSound", if(state == "writing") "failed" else "stopped", restSound.message)
    }
    private suspend fun routineSpeak(text: String, epoch: Long) {
        if(voice.engine == "none" || alarms.snapshot().state == "ringing") { audioStep("voice", "failed", "음성 안내를 사용할 수 없습니다"); return }
        audioStep("voice", "writing", "음성 안내 중")
        val completed = withTimeoutOrNull(8000) { suspendCancellableCoroutine<Boolean> { c ->
            voice.speak(text) { if(c.isActive) c.resume(voice.lastCompletedSuccessfully && epoch == routineEpoch) }
            c.invokeOnCancellation { voice.cancel() }
        } } ?: false
        if(epoch == routineEpoch) audioStep("voice", if(completed) "applied" else "failed", if(completed) "음성 안내 재생 완료" else "음성 안내 완료를 확인하지 못했습니다")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveExternalIntent(intent)
        dispatchCommand()
    }
    private fun receiveExternalIntent(source: Intent) {
        val request = GleoIntents.parse(source) ?: return
        GleoIntents.sendResponse(this, request, preparationAvailable = !demoMode)
        val keys = runCatching { source.extras?.keySet()?.sorted()?.take(8)?.map { it.take(80) } }.getOrNull()
        android.util.Log.i("KkaebiGleo", "received action=${request.functionName} accepted=${request.command != null && !demoMode} extraKeys=$keys")
        preparationDispatch?.cancel()
        nativeCommand = null
        preparationQueue.offer(request.command.takeIf { !demoMode }, android.os.SystemClock.elapsedRealtime())
        // A consumed external preparation request must not replay after Activity recreation.
        setIntent(Intent(source).setAction(null).replaceExtras(Bundle()))
        if (preparationQueue.pending) preparationDispatch = scope.launch {
            while (isActive && preparationQueue.pending) {
                dispatchCommand()
                if (preparationQueue.pending) delay(100)
            }
        }
    }
    private fun clearPreparationCommand() {
        preparationDispatch?.cancel(); preparationDispatch = null
        preparationQueue.clear(); nativeCommand = null
    }
    private fun dispatchCommand() {
        val parked = ::gateway.isInitialized && gateway.parked()
        val result = preparationQueue.poll(android.os.SystemClock.elapsedRealtime(), active,
            webReady, demoMode, foregroundSignalsChecked, parked)
        if (result.status in setOf(GleoPreparationQueue.Status.NONE, GleoPreparationQueue.Status.WAIT)) return
        android.util.Log.i("KkaebiGleo", "delivery=${result.status} command=${result.command} active=$active webReady=$webReady parked=$parked")
        when (result.status) {
            GleoPreparationQueue.Status.DELIVER -> {
                val command = result.command ?: return
                if(command=="quiet") { voice.cancel(); ears.cancel(); restSound.stop() }
                nativeCommand = JSONObject().put("id", UUID.randomUUID().toString()).put("type",command).put("source","gleo-draft")
            }
            GleoPreparationQueue.Status.EXPIRED -> alarmActionMessage = "차량 상태 확인을 기다리다 준비 요청이 만료됐습니다 · 정차 후 다시 요청해 주세요"
            GleoPreparationQueue.Status.BLOCKED -> alarmActionMessage = "안전한 곳에 P단 주차한 뒤 휴식 준비를 다시 요청해 주세요"
            else -> Unit
        }
        publish()
    }
    private fun requestAlarmPermission() {
        if (!gateway.parked()) { alarmActionMessage="안전한 곳에 주차한 뒤 알람 설정을 열어 주세요"; publish(); return }
        if(Build.VERSION.SDK_INT>=33 && ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),11); return
        }
        val manager=getSystemService(android.app.AlarmManager::class.java)
        val target=if(Build.VERSION.SDK_INT>=31 && !manager.canScheduleExactAlarms()) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,RestAlarmController.CHANNEL_RING)
        runCatching { startActivity(target) }.onFailure { alarmActionMessage="기기 설정에서 깨비 알림과 정확한 알람을 허용해 주세요"; publish() }
    }
}
