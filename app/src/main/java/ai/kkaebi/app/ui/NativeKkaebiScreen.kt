package ai.kkaebi.app.ui.nativeapp

import ai.kkaebi.app.ui.Pretendard
import ai.kkaebi.app.rest.RestSafety
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.round

private object Ink {
    val bg = Color(0xFF151918)
    val text = Color(0xFFF1F2E9)
    val muted = Color(0xFFB8C7C2)
    val line = Color(0xFF3B4B46)
    val mint = Color(0xFFC5E5CE)
    val amber = Color(0xFFF0C69C)
    val field = Color(0xFF202925)
}
private enum class Page { HOME, PLAN, DRIVE, SEEK, PREPARE, SETTINGS, SCHEDULING, SYNC, REST, RECOVERY, MORE, SUMMARY, JOURNAL, INTERRUPTED }
private data class ControlChoice(val key: String, val label: String, val selected: Boolean, val value: Any)
private fun JSONObject.string(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty()
private fun JSONObject.bool(key: String) = opt(key) == true
private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
private val parkingGate = NativeParkingGate()
private fun parked(s: JSONObject): Boolean = s.bool("lifecycleActive") && s.string("mode") == "live" &&
    parkingGate.permits(s.string("gear"), s.number("speedKmh"), s.optLong("gearAt"), s.optLong("speedAt"), s.opt("restricted") != false)
private fun capability(s: JSONObject, key: String): String {
    val c = s.optJSONObject("controlCapabilities")?.optJSONObject(key) ?: return "unknown"
    if (c.bool("manualRequired")) return "unsupported"
    return c.string("state").takeIf { it in listOf("supported", "unsupported") } ?: "unknown"
}
private fun supportedValue(s: JSONObject, key: String, value: Any): Boolean {
    if (capability(s, key) != "supported") return false
    val c = s.optJSONObject("controlCapabilities")?.optJSONObject(key) ?: return false
    val v = when (value) { is Boolean -> if (value) 1.0 else 0.0; is Number -> value.toDouble(); else -> return false }
    return RestSafety.validCapabilityValue(v, c.number("min"), c.number("max"), c.number("step"), c.number("origin"))
}
private fun valueLabel(key: String, value: Any): String = when (key) {
    "temperature" -> "${numberLabel((value as Number).toDouble())}°C"
    "brightness" -> "낮게"
    "hvacPower" -> if (value == true) "켜기" else "끄기"
    else -> if ((value as? Number)?.toInt() == 0) "끄기" else "${(value as? Number)?.toInt() ?: value}단계"
}
private fun numberLabel(value: Double) = if (value == value.toInt().toDouble()) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
private fun friendlyError(key: String, raw: String): String = when {
    Regex("timeout|timed.?out|시간.*초과", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "응답 시간 초과 · 정차 후 다시 확인해 주세요."
    Regex("security|permission|denied|권한", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "차량 설정 권한을 확인해 주세요."
    Regex("알람.*종료|후속.*중단").containsMatchIn(raw) -> "알람이 끝나 후속 설정을 멈췄어요."
    Regex("unsupported|범위|단계|지원.*불가", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "지원 범위 확인 필요 · 직접 조절해 주세요."
    Regex("unavailable|notavailable|not available|값 사용 불가|읽기.*실패", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> if (key in listOf("temperature", "fan", "hvacPower")) "값을 읽지 못했어요. 차량 공조를 확인해 주세요." else "값을 읽지 못했어요. 차량에서 확인해 주세요."
    else -> "완료를 확인하지 못했어요. 차량에서 직접 확인해 주세요."
}


/** UI state only. The native alarm and vehicle gateways independently authorize every operation. */
@Stable
private class RestUi(context: Context) {
    private val prefs = context.getSharedPreferences("native-rest-ui", Context.MODE_PRIVATE)
    var page by mutableStateOf(Page.HOME)
    var settingsReturn = Page.HOME
    var journalReturn = Page.HOME
    var nap by mutableIntStateOf(prefs.getInt("nap", 20).takeIf { it in listOf(10, 15, 20, 25, 30) } ?: 20)
    var temperature by mutableDoubleStateOf(prefs.getFloat("temperature", 22f).toDouble().takeIf { it in 18.0..26.0 } ?: 22.0)
    var fan by mutableIntStateOf(prefs.getInt("fan", 1).coerceIn(1, 3))
    var ventilation by mutableIntStateOf(prefs.getInt("ventilation", 1).coerceIn(0, 3))
    var temperatureOn by mutableStateOf(prefs.getBoolean("temperatureOn", true))
    var fanOn by mutableStateOf(prefs.getBoolean("fanOn", true))
    var dim by mutableStateOf(prefs.getBoolean("dim", true))
    var ventilationOn by mutableStateOf(prefs.getBoolean("ventilationOn", false))
    var powerSelected by mutableStateOf(prefs.getBoolean("powerSelected", false))
    var powerOn by mutableStateOf(prefs.getBoolean("powerOn", true))
    var restSound by mutableStateOf(prefs.getBoolean("restSound", false))
    var breakEvery by mutableIntStateOf(prefs.getInt("breakEvery", 90).takeIf { it in listOf(60, 90, 120) } ?: 90)
    var sleep by mutableStateOf("normal")
    var confirmedPlace by mutableStateOf(false)
    var environmentConsent by mutableStateOf(false)
    var sound by mutableStateOf(false)
    var listening by mutableStateOf(false)
    var notice by mutableStateOf("")
    var dialogue by mutableStateOf("")
    private val alarmSession = NativeRestSession()
    val pendingRequest get() = alarmSession.pendingRequest
    private var requestAt = 0L
    val stopRequested get() = alarmSession.stopRequested
    var waitingOperation by mutableStateOf<String?>(null)
    var waitingExpired by mutableStateOf(false)
    private var previousOperation = ""
    private var operationAt = 0L
    private var lastAlarm = ""
    private var lastCommand = ""
    private var lastHeard = ""
    private var lastSpeechResult = ""
    var observedDriveMs by mutableLongStateOf(0L)
    private var lastTick = SystemClock.elapsedRealtime()
    var drivingPlan by mutableStateOf(false)
    var logs by mutableStateOf(readLogs())
    private val confirmations = NativeConfirmationSequence()
    var accentOperation by mutableStateOf("")
        private set
    val accents = mutableStateListOf<String>()
    var accentKey by mutableStateOf<String?>(null)

    fun observeConfirmations(s: JSONObject, motion: Boolean) {
        val id = s.string("controlOperationId")
        if (accentOperation != id || !motion) { accents.clear(); accentKey = null; accentOperation = id }
        val steps = s.optJSONArray("controlSteps")
        val truth = (0 until (steps?.length() ?: 0)).mapNotNull { i -> steps?.optJSONObject(i)?.let { Triple(it.string("operationId"), it.string("key"), it.string("state")) } }
        accents.addAll(confirmations.observe(id, truth, motion))
    }

    private fun readLogs(): List<Pair<Long, String>> = runCatching {
        val array = JSONArray(prefs.getString("logs", "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { it.optLong("at") to it.string("text").take(160) } }.takeLast(50)
    }.getOrDefault(emptyList())
    fun log(text: String) { logs = (logs + (System.currentTimeMillis() to text)).takeLast(50); saveLogs() }
    private fun saveLogs() { val a = JSONArray(); logs.forEach { (at, text) -> a.put(JSONObject().put("at", at).put("text", text)) }; prefs.edit().putString("logs", a.toString()).apply() }
    fun clearLogs() { logs = emptyList(); saveLogs() }
    fun save() { prefs.edit().putInt("nap", nap).putFloat("temperature", temperature.toFloat()).putInt("fan", fan).putInt("ventilation", ventilation).putBoolean("temperatureOn", temperatureOn).putBoolean("fanOn", fanOn).putBoolean("dim", dim).putBoolean("ventilationOn", ventilationOn).putBoolean("powerSelected", powerSelected).putBoolean("powerOn", powerOn).putBoolean("restSound", restSound).putInt("breakEvery", breakEvery).apply() }
    fun choices() = listOf(ControlChoice("temperature", "온도", temperatureOn, temperature), ControlChoice("fan", "풍량", fanOn, fan), ControlChoice("brightness", "실내 조명", dim, 10), ControlChoice("seatVentilation", "시트 통풍", ventilationOn, ventilation), ControlChoice("hvacPower", "공조 전원", powerSelected, powerOn))
    fun eligible(s: JSONObject) = if (s.bool("pendingRestore") || s.string("controlPhase") == "running") emptyList() else choices().filter { it.selected && supportedValue(s, it.key, it.value) }
    fun activeAlarm(s: JSONObject) = s.string("alarmState") in listOf("scheduled", "ringing") && s.string("alarmId").isNotBlank()
    fun busy(s: JSONObject) = s.string("controlPhase") == "running" || (waitingOperation != null && !waitingExpired)
    private fun beginOperation(kind: String, s: JSONObject) { waitingOperation = kind; waitingExpired = false; previousOperation = s.string("controlOperationId"); operationAt = SystemClock.elapsedRealtime() }
    fun prepare(s: JSONObject, now: Long) {
        if (activeAlarm(s) || pendingRequest != null) { notice = "이미 예약된 알람을 먼저 확인해 주세요."; return }
        if (!parked(s)) { notice = "안전한 주차 공간에서 P단 정차를 확인해 주세요."; return }
        page = Page.PREPARE; confirmedPlace = false; environmentConsent = false; notice = ""
    }
    fun startRest(s: JSONObject, now: Long, send: (String, JSONObject) -> Unit) {
        if (!parked(s) || !confirmedPlace || page != Page.PREPARE || busy(s) || activeAlarm(s)) return
        if (!s.bool("alarmReady")) { notice = s.string("alarmPermissionMessage").ifBlank { "알람 권한과 소리를 확인해 주세요." }; return }
        val request = "native-${UUID.randomUUID()}"
        if (!alarmSession.begin(request)) return
        requestAt = SystemClock.elapsedRealtime(); page = Page.SCHEDULING; notice = ""
        send("startAlarm", JSONObject().put("durationMinutes", nap).put("requestId", request))
    }
    fun stop(s: JSONObject, send: (String, JSONObject) -> Unit) {
        if (!alarmSession.requestStop(s.string("alarmState"), s.string("alarmId"))) return
        page = Page.RECOVERY; notice = "알람 중지 응답을 기다리고 있어요."
        send("ackAlarm", JSONObject().put("alarmId", stopRequested)); send("restSound", JSONObject().put("enabled", false))
    }
    fun restore(s: JSONObject, now: Long, send: (String, JSONObject) -> Unit, summary: Boolean = false) {
        if (!parked(s) || busy(s)) return
        if (summary) { page = Page.SUMMARY; log("몸 상태 자기확인 · 운전 가능 판정 아님") }
        beginOperation("restore", s); send("restoreRest", JSONObject())
    }
    fun command(type: String, s: JSONObject, now: Long, send: (String, JSONObject) -> Unit) {
        when (type) {
            "drowsy-mode" -> if (!activeAlarm(s) && pendingRequest == null) { page = Page.SEEK; notice = ""; log("졸음모드 요청") }
            "rest-mode" -> prepare(s, now)
            "quiet" -> { sound = false; listening = false; send("stopSpeech", JSONObject()); send("restSound", JSONObject().put("enabled", false)); dialogue = if (activeAlarm(s)) "대화를 멈췄어요. 예약된 알람은 따로 유지돼요." else "대화를 멈췄어요." }
            "ack" -> stop(s, send)
            "ventilation" -> speak("주행 중 자동 환기는 지원하지 않아요. 차량 공조를 직접 조절하고, 졸리면 안전한 곳에서 쉬어 주세요.", send)
            else -> dialogue = "깨비 졸음모드, 깨비 휴식, 조용히, 알람 중지라고 말해 주세요."
        }
    }
    fun speak(text: String, send: (String, JSONObject) -> Unit) { dialogue = text; send("speak", JSONObject().put("text", text.take(120)).put("requestId", "speech-${UUID.randomUUID()}")) }
    fun snapshot(s: JSONObject, now: Long, send: (String, JSONObject) -> Unit) {
        val p = parked(s)
        if (!p) {
            confirmedPlace = false
            if (page in listOf(Page.PREPARE, Page.SETTINGS, Page.PLAN, Page.JOURNAL)) { page = Page.INTERRUPTED; environmentConsent = false }
        }
        if (waitingOperation != null && s.string("controlOperationId") != previousOperation && s.string("controlOperation") == waitingOperation) { waitingOperation = null; waitingExpired = false }
        val alarmState = s.string("alarmState")
        val id = s.string("alarmId")
        val req = s.string("alarmRequestId").ifBlank { s.string("requestId") }
        val decision = alarmSession.reconcile(alarmState, id, req, p, confirmedPlace, environmentConsent, s.bool("pendingRestore"))
        val matching = decision.matchingRequest
        if (decision.accept) when (alarmState) {
            "scheduled" -> if (id.isNotBlank()) {
                page = if (stopRequested == id) Page.RECOVERY else Page.REST
                if (matching) {
                    log("알람 예약 확인 · ${nap}분 휴식")
                    if (p && confirmedPlace) {
                        val selected = if (decision.allowEnvironment) eligible(s) else emptyList()
                        if (selected.isNotEmpty()) {
                            val data = JSONObject().put("alarmId", id)
                            selected.forEach { data.put(if (it.key == "brightness") "dim" else it.key, if (it.key == "brightness") true else it.value) }
                            beginOperation("apply", s); send("applyRest", data)
                        }
                        if (restSound) send("restSound", JSONObject().put("enabled", true))
                    } else notice = "알람은 예약됐어요. 정차 확인이 없어 차량 설정은 바꾸지 않았어요."
                }
            }
            "ringing" -> if (id.isNotBlank()) { page = Page.RECOVERY; if (lastAlarm != "$id:ringing") { send("restSound", JSONObject().put("enabled", false)); log("기상 알람 · 몸 상태는 직접 확인") } }
            "idle" -> { if (decision.stopped) { page = Page.RECOVERY; notice = "알람이 중지되었어요." } else if (page in listOf(Page.REST, Page.SYNC, Page.SCHEDULING)) { page = Page.INTERRUPTED; notice = "현재 예약된 알람이 없어요." } }
            "failed" -> if (matching || page in listOf(Page.REST, Page.SYNC, Page.SCHEDULING)) { confirmedPlace = false; page = if (p) Page.PREPARE else Page.INTERRUPTED; notice = s.string("alarmMessage").take(160).ifBlank { "알람 예약을 확인하지 못했어요." } }
        }
        lastAlarm = "$id:$alarmState"
        s.optJSONObject("nativeCommand")?.let { event -> val eventId = event.string("id"); if (eventId.isNotBlank() && eventId != lastCommand) { lastCommand = eventId; if (event.string("type") in listOf("drowsy-mode", "rest-mode", "quiet")) command(event.string("type"), s, now, send) } }
        s.optJSONObject("heard")?.let { event -> val eventId = event.string("id"); if (eventId.isNotBlank() && eventId != lastHeard) { lastHeard = eventId; listening = false; if (event.opt("recognized") == false) dialogue = "음성을 인식하지 못했어요. 필요할 때 다시 눌러 말해 주세요." else command(parseNativeCommand(event.string("text")), s, now, send) } }
        s.optJSONObject("speechResult")?.let { event -> val eventId = event.string("eventId"); if (eventId.isNotBlank() && eventId != lastSpeechResult) { lastSpeechResult = eventId; if (event.opt("completed") == false) dialogue = "음성 안내를 완료하지 못했어요. 화면을 확인해 주세요." } }
    }
    fun tick(s: JSONObject, now: Long, send: (String, JSONObject) -> Unit) {
        val elapsed = SystemClock.elapsedRealtime()
        val dt = (elapsed - lastTick).coerceIn(0, 10000)
        lastTick = elapsed
        if (drivingPlan && s.bool("lifecycleActive") && s.string("mode") == "live" && s.string("gear") == "D" && (s.number("speedKmh") ?: 0.0) > .1 && listOf(s.optLong("gearAt"), s.optLong("speedAt")).all { it > 0 && now - it in 0..4999 }) observedDriveMs += dt
        if (page == Page.DRIVE && observedDriveMs >= breakEvery * 60000L) page = Page.SEEK
        if (pendingRequest != null && elapsed - requestAt > 15000) { alarmSession.expireRequest(); page = Page.SYNC; notice = "알람 응답을 다시 확인해요. 차량 설정은 적용하지 않았어요."; send("ready", JSONObject()) }
        if (waitingOperation != null && elapsed - operationAt > 15000) waitingExpired = true
    }
}

@Composable
fun NativeKkaebiScreen(state: JSONObject, onAction: (String, JSONObject) -> Unit) {
    val context = LocalContext.current
    val ui = remember { RestUi(context.applicationContext) }
    val send by rememberUpdatedState(onAction)
    val latest by rememberUpdatedState(state)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { send("ready", JSONObject()); while (isActive) { delay(1000); now = System.currentTimeMillis(); ui.tick(latest, now, send) } }
    val p = parked(state)
    val activeAlarm = ui.activeAlarm(state)
    val busy = ui.busy(state)
    val motion = p && state.bool("lifecycleActive") && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(state.toString(), p) { ui.snapshot(state, now, send) }
    LaunchedEffect(state.toString(), motion) { ui.observeConfirmations(state, motion) }
    LaunchedEffect(ui.accentOperation, motion, ui.accents.isNotEmpty()) {
        if (!motion) return@LaunchedEffect
        try {
            while (ui.accents.isNotEmpty()) {
                val first = ui.accents.first()
                ui.accentKey = first
                delay(180)
                if (ui.accents.firstOrNull() == first) ui.accents.removeAt(0)
            }
        } finally { ui.accentKey = null }
    }
    val backDestination = nativeBackDestination(ui.page.name, busy || ui.pendingRequest != null, activeAlarm, ui.settingsReturn.name, ui.journalReturn.name)
    BackHandler(enabled = backDestination != null) {
        backDestination?.let { destination ->
            if (destination == "HOME") { ui.confirmedPlace = false; ui.environmentConsent = false }
            ui.page = Page.valueOf(destination)
        }
    }
    val type = Typography(
        headlineLarge = TextStyle(fontFamily = Pretendard, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
        titleLarge = TextStyle(fontFamily = Pretendard, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        bodyLarge = TextStyle(fontFamily = Pretendard, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = Pretendard, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontFamily = Pretendard, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        labelSmall = TextStyle(fontFamily = Pretendard, fontSize = 12.sp, lineHeight = 17.sp)
    )
    MaterialTheme(colorScheme = darkColorScheme(primary = Ink.mint, onPrimary = Ink.bg, background = Ink.bg, onBackground = Ink.text, surface = Ink.bg, onSurface = Ink.text, onSurfaceVariant = Ink.muted, outline = Ink.line), typography = type) {
        Column(Modifier.fillMaxSize().background(Ink.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { if (p && !activeAlarm && !busy) ui.page = Page.HOME }) { Text("깨비", fontFamily = Pretendard, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink.text) }
                Text(if (p) "차량 연결 · P단 정차" else if (state.string("mode") == "live") "차량 연결 · 정차 확인 필요" else "차량 연결 확인 중", Modifier.weight(1f), color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                ActionButton(if (ui.sound) "음성 끄기" else "음성 켜기") { if (ui.sound) ui.command("quiet", state, now, send) else { ui.sound = true; ui.speak("필요한 때만 짧게 안내할게요.", send) } }
                ActionButton("휴식 기록", enabled = p && !activeAlarm && !busy) { ui.journalReturn = ui.page; ui.page = Page.JOURNAL }
            }
            HorizontalDivider(color = Ink.line)
            Journey(ui.page)
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
                key(ui.page) {
                    if (ui.page == Page.SETTINGS) SettingsContent(ui, state, p, busy)
                    else if (ui.page == Page.JOURNAL) JournalContent(ui)
                    else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { MainMessage(ui, state, p, busy, motion) }
                        VerticalDivider(color = Ink.line)
                        Column(Modifier.weight(1.05f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { DetailContent(ui, state, p, busy, motion, now, send) }
                    }
                }
            }
            HorizontalDivider(color = Ink.line)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FooterActions(ui, state, p, activeAlarm, busy, now, send)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${state.string("gear").takeIf { it in setOf("P", "R", "N", "D") } ?: "기어 미확인"} · ${state.number("speedKmh")?.let { "${it.toInt()} km/h" } ?: "속도 미확인"}", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                Text("졸리면 운전을 멈추고 안전한 곳에서 쉬어 주세요.", style = MaterialTheme.typography.labelSmall, color = Ink.muted)
            }
        }
    }
}

@Composable
private fun Journey(page: Page) {
    val index = when (page) { Page.SEEK -> 0; Page.PREPARE, Page.SETTINGS, Page.SCHEDULING, Page.SYNC -> 1; Page.REST -> 2; Page.RECOVERY, Page.MORE, Page.SUMMARY -> 3; else -> -1 }
    if (index < 0) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf("장소 확인", "휴식 준비", "쉬는 중", "회복 확인").forEachIndexed { i, title ->
            Text(title, color = if (index == i) Ink.mint else Ink.muted, style = MaterialTheme.typography.labelSmall, fontWeight = if (index == i) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun MainMessage(ui: RestUi, s: JSONObject, p: Boolean, busy: Boolean, motion: Boolean) {
    val restored = (s.optJSONArray("controlSteps")?.length() ?: 0) > 0 && s.string("controlOperation") == "restore" && s.string("controlPhase") == "complete" && !s.bool("pendingRestore") && ui.waitingOperation == null
    val pose = if (ui.page == Page.REST && s.string("alarmState") == "scheduled") "resting" else if (ui.page in listOf(Page.SUMMARY, Page.RECOVERY, Page.MORE) && restored) "recovery" else "attentive"
    val title = when (ui.page) {
        Page.HOME -> "쉬어가도\n괜찮아요."
        Page.PLAN -> "출발 전에,\n쉴 시간을 정해요."
        Page.DRIVE -> "다음 휴식까지\n약 ${(ui.breakEvery - ui.observedDriveMs / 60000).coerceAtLeast(0)}분."
        Page.SEEK -> "이번에는,\n쉬어가요."
        Page.PREPARE -> "${ui.nap}분,\n쉴 준비를 해요."
        Page.SCHEDULING -> "알람을\n예약하고 있어요."
        Page.SYNC -> "기기 알람을\n확인하고 있어요."
        Page.REST -> "지금은, 편히 쉬어요."
        Page.RECOVERY -> "서두르지 말고,\n몸부터 깨워요."
        Page.MORE -> "더 쉬어도\n괜찮아요."
        Page.SUMMARY -> if (ui.waitingExpired) "요청 결과를\n직접 확인해요." else if (ui.waitingOperation != null) "차량의 응답을\n기다리고 있어요." else if (busy) "이전 설정을\n복원하고 있어요." else "쉬어간 뒤에도,\n몸을 살펴요."
        Page.INTERRUPTED -> when (s.string("alarmState")) { "idle" -> "예약된 알람이\n없어요."; "failed" -> "알람 예약을\n확인하지 못했어요."; else -> "기기 상태를\n다시 확인해요." }
        else -> "깨비와 쉬어가요."
    }
    if (ui.page == Page.HOME) Bot(pose, 154.dp, motion)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = Ink.text)
        if (ui.page !in listOf(Page.HOME, Page.REST)) Bot(pose, 62.dp, motion)
    }
    when (ui.page) {
        Page.HOME -> Body("졸리면 운전을 미루고,\n안전한 곳에서 쉬어가요.")
        Page.DRIVE -> Body("졸리면 계획보다 먼저 쉬어가요.\n확인된 주행 ${ui.observedDriveMs / 60000}분")
        Page.SEEK -> Body("졸림을 느끼면 운전을 계속하지 마세요.\n안전하게 진입할 수 있는 장소를 확인해요.")
        Page.PREPARE -> {
            ChoiceCheck("안전한 주차 공간임을 확인했어요.", ui.confirmedPlace, p) { ui.confirmedPlace = it }
            Note("갓길·밀폐된 곳·배기구가 막힌 곳은 피하세요.")
            if (!s.bool("alarmReady")) Warning(s.string("alarmPermissionMessage").ifBlank { "알람 권한과 소리 설정을 확인해 주세요." })
        }
        Page.SCHEDULING -> Body("예약 확인 전에는 차량 설정을 바꾸지 않아요.")
        Page.SYNC -> Body("확인 전에는 예약 여부나 남은 시간을 추정하지 않아요.")
        Page.REST -> {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val seconds = (s.optLong("alarmRemainingMs").coerceAtLeast(0) + 999) / 1000
                Text(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60), fontFamily = Pretendard, fontSize = 56.sp, lineHeight = 62.sp, fontWeight = FontWeight.SemiBold, color = Ink.text, style = TextStyle(fontFeatureSettings = "tnum"))
                Bot("resting", 80.dp, motion)
            }
            Note("시스템 알람 예약됨 · 화면 밖에서도 예약 유지")
            if (!p) Note("정차 신호를 확인하지 못해 차량 설정은 멈춰 두었어요.")
        }
        Page.RECOVERY -> { Body("아직 졸리거나 몽롱한가요?"); Note("응답은 운전 가능 판정이 아니에요.\n알람 중지는 주행 상태와 관계없이 가능해요.") }
        Page.MORE -> Body("졸림이 남으면 운전을 미루세요.\n추가 휴식·운전자 교대·대체 이동을 고려해요.")
        Page.SUMMARY -> Body("몸 상태는 직접 확인해 주세요.\n다시 졸리면 운전을 미루고 쉬세요.")
        Page.INTERRUPTED -> Body(when (s.string("alarmState")) { "idle" -> "기기에 예약된 깨비 알람이 없어요. 정차 후 다시 준비해 주세요."; "failed" -> "예약 성공이 확인되지 않았어요. 알람 권한과 소리를 확인해 주세요."; "scheduled" -> "기기에 예약된 알람을 확인했어요. 차량 설정은 정차 확인 후 바꿔요."; "ringing" -> "현재 기상 알람이 울리고 있어요."; else -> "기기의 알람 상태를 아직 받지 못했어요." })
        else -> Unit
    }
    if (ui.notice.isNotBlank()) Note(ui.notice)
}

@Composable
private fun DetailContent(ui: RestUi, s: JSONObject, p: Boolean, busy: Boolean, motion: Boolean, now: Long, send: (String, JSONObject) -> Unit) {
    when (ui.page) {
        Page.HOME -> {
            Heading("지금 필요한 건 무엇인가요?")
            EntryButton("깨비 졸음모드", "졸릴 때, 쉴 곳부터 확인해요", Ink.amber) { ui.command("drowsy-mode", s, now, send) }
            EntryButton("주차 후 휴식", if (p) "안전한 곳에 멈췄다면 준비해요" else "P단 정차가 확인되면 열려요", Ink.mint, p) { ui.prepare(s, now) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("출발 전 계획", enabled = p && !busy) { ui.page = Page.PLAN }
                ActionButton("말로 시작", enabled = s.bool("stt")) { ui.listening = true; ui.dialogue = "듣고 있어요. 깨비 졸음모드 또는 깨비 휴식이라고 말해 주세요."; send("listen", JSONObject()) }
            }
            Note("차량 음성 비서 연결은 준비 중이에요. 앱에서 요청해 주세요.")
        }
        Page.PLAN -> {
            Heading("지금 몸 상태는 어떤가요?")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("normal" to "보통", "short" to "잠 부족", "sleepy" to "졸려요").forEach { (v, label) -> SelectButton(label, ui.sleep == v) { ui.sleep = v } } }
            Heading("언제 쉬어갈까요?")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(60, 90, 120).forEach { value -> SelectButton("${value}분", ui.breakEvery == value) { ui.breakEvery = value; ui.save() } } }
            Note("졸리면 예정된 시간까지 기다리지 마세요.")
        }
        Page.DRIVE, Page.SEEK -> {
            Heading("차량 내비에서 쉴 곳 확인")
            val distance = s.number("nextRestAreaM")
            Body(if (distance != null) "경로상 휴식 지점까지 약 ${numberLabel(round(distance / 100) / 10)} km." else "확인된 쉼터 거리 정보가 없어요.")
            Note("진행 방향과 진입 경로를 차량 내비에서 확인해 주세요. 깨비는 경로를 변경하지 않아요.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("짧게 말해줘") { ui.speak("쉴 곳까지는 운전에 집중해 주세요. 졸림이 심하면 가장 가까운 안전한 장소에서 운전을 멈추세요.", send) }
                ActionButton("환기 안내") { ui.command("ventilation", s, now, send) }
            }
        }
        Page.PREPARE -> {
            Heading("이번 휴식 환경")
            if (s.bool("pendingRestore")) {
                Body("현재 환경을 유지하고\n알람만 새로 예약해요.")
                Note("다른 설정은 이전 설정 복원 결과를 확인한 뒤 선택해요.")
                ActionButton("이전 설정부터 복원", enabled = p && !busy) { ui.restore(s, now, send) }
            } else {
                val eligible = ui.eligible(s)
                if (eligible.isEmpty()) Body("이번에 자동 적용할 항목이 없어요.")
                else eligible.forEach { choice -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Body(choice.label); Text(valueLabel(choice.key, choice.value), color = Ink.text, style = MaterialTheme.typography.bodyLarge) } }
                ChoiceCheck("이번 휴식에 선택한 차량 설정 적용", ui.environmentConsent, p && eligible.isNotEmpty() && !busy) { ui.environmentConsent = it }
                Note("알람 예약 확인 후 요청해요. 지원 확인은 적용 성공과 달라요.")
                ui.choices().filter { it.selected && !supportedValue(s, it.key, it.value) }.forEach { Note("${it.label} · ${if (capability(s, it.key) == "unsupported") "직접 조절" else "지원·범위 확인 전"}") }
            }
            Note("시트 자세·창문·블라인드는 직접 조절해 주세요.")
            if (busy) OperationContent(ui, s, motion)
        }
        Page.SCHEDULING -> { Heading("예약 응답을 기다려요"); Body("${ui.nap}분 알람이 예약되면\n선택한 환경을 준비해요."); Note("예약 응답을 받기 전에는 차량 설정을 바꾸지 않아요.") }
        Page.SYNC -> { Heading("기기의 예약이 기준이에요"); Body("화면을 다시 열어도 기존 알람을 확인해 이어가요."); Note("차량 설정은 자동으로 다시 적용하지 않아요.") }
        Page.REST -> {
            OperationContent(ui, s, motion)
            if (s.bool("pendingRestore")) Note(if (s.string("controlPhase") == "failed") "확인할 차량 설정이 남아 있어요." else "휴식이 끝나면 변경한 차량 설정을 확인해요.")
            Note(s.string("restSoundMessage").ifBlank { "휴식 소리는 앱의 합성 소리이며 수면 효과를 보장하지 않아요." })
        }
        Page.RECOVERY, Page.MORE -> {
            Heading("출발을 재촉하지 않아요")
            Body("잠에서 깬 뒤 충분한 여유를 두세요.\n졸림이 남으면 더 쉬거나 교대하세요.")
            ManualChecklist()
            if (s.bool("pendingRestore")) Warning("원래 설정으로 돌아갔는지 아직 확인이 필요해요.")
        }
        Page.SUMMARY -> { OperationContent(ui, s, motion); ManualChecklist(); Note("깨비는 출발을 허가하거나 차량을 출발시키지 않아요.") }
        Page.INTERRUPTED -> { Heading("기기 상태를 다시 확인해요"); Note("예약 확인 전에는 알람 유지나 성공을 단정하지 않아요. 차량 설정은 정차 신호가 있어야 바꿔요.") }
        else -> Unit
    }
    if (ui.dialogue.isNotBlank()) Note(ui.dialogue)
}

@Composable
private fun RowScope.FooterActions(ui: RestUi, s: JSONObject, p: Boolean, active: Boolean, busy: Boolean, now: Long, send: (String, JSONObject) -> Unit) {
    when (ui.page) {
        Page.HOME -> Text("졸림이 걱정되면 바로 알려 주세요.", Modifier.weight(1f), color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
        Page.PREPARE -> {
            ActionButton(if (busy) "차량 결과 확인 중" else "알람 예약하고 휴식", enabled = p && ui.confirmedPlace && s.bool("alarmReady") && !busy, primary = true) { ui.startRest(s, now, send) }
            ActionButton("휴식 설정", enabled = p && !busy) { ui.settingsReturn = Page.PREPARE; ui.environmentConsent = false; ui.page = Page.SETTINGS }
            if (!s.bool("alarmReady")) ActionButton("알람 설정 확인", enabled = p) { send("requestAlarmPermission", JSONObject()) }
        }
        Page.SETTINGS -> ActionButton("설정 완료", primary = true, enabled = p) { ui.save(); ui.page = ui.settingsReturn }
        Page.PLAN -> {
            ActionButton("계획 시작", primary = true, enabled = p) { ui.drivingPlan = true; ui.observedDriveMs = 0; ui.page = if (ui.sleep == "normal") Page.DRIVE else Page.SEEK; ui.log("휴식 계획 시작") }
            ActionButton("휴식 설정", enabled = p) { ui.settingsReturn = Page.PLAN; ui.page = Page.SETTINGS }
            ActionButton("홈으로") { ui.page = Page.HOME }
        }
        Page.DRIVE -> {
            ActionButton("지금 졸려요", urgent = true) { ui.command("drowsy-mode", s, now, send) }
            ActionButton("말로 알려주기", enabled = s.bool("stt")) { send("listen", JSONObject()) }
        }
        Page.SEEK -> {
            ActionButton("주차 후 휴식 준비", primary = true, enabled = p) { ui.prepare(s, now) }
            ActionButton("말로 알려주기", enabled = s.bool("stt")) { send("listen", JSONObject()) }
        }
        Page.SCHEDULING, Page.SYNC -> {
            if (active) ActionButton("알람 중지", primary = true) { ui.stop(s, send) }
            ActionButton("기기 상태 다시 확인") { send("ready", JSONObject()) }
        }
        Page.REST -> {
            ActionButton("알람 중지 · 휴식 마치기", primary = true) { ui.stop(s, send) }
            ActionButton(if (s.bool("restSoundPlaying")) "휴식 소리 끄기" else "휴식 소리 켜기", enabled = p && s.bool("restSoundAvailable")) { send("restSound", JSONObject().put("enabled", !s.bool("restSoundPlaying"))) }
            if (s.bool("pendingRestore")) ActionButton("지금 설정 복원", enabled = p && !busy) { ui.restore(s, now, send) }
        }
        Page.RECOVERY -> {
            if (active) ActionButton("알람 중지", primary = true) { ui.stop(s, send) }
            else ActionButton("아직 졸려요", primary = true, enabled = p && !busy) { ui.page = Page.MORE; ui.log("추가 휴식·교대 선택") }
            ActionButton(if (busy) "설정 확인 중" else "상태 확인 · 설정 복원", enabled = p && !active && !busy) { ui.restore(s, now, send, summary = true) }
        }
        Page.MORE -> {
            ActionButton("추가 휴식 준비", primary = true, enabled = p && !busy) { ui.prepare(s, now) }
            if (s.bool("pendingRestore")) ActionButton("이전 설정 복원", enabled = p && !busy) { ui.restore(s, now, send) }
        }
        Page.SUMMARY -> {
            if (s.bool("pendingRestore")) ActionButton(if (busy) "복원 확인 중" else "설정 복원 다시 확인", primary = true, enabled = p && !busy) { ui.restore(s, now, send) }
            else ActionButton("홈으로", primary = true, enabled = p && !busy) { ui.page = Page.HOME; ui.notice = "" }
            ActionButton("휴식 기록", enabled = p && !busy) { ui.journalReturn = Page.SUMMARY; ui.page = Page.JOURNAL }
        }
        Page.JOURNAL -> { ActionButton("돌아가기", primary = true) { ui.page = ui.journalReturn }; ActionButton("기록 모두 지우기", enabled = p) { ui.clearLogs() } }
        Page.INTERRUPTED -> {
            if (active) ActionButton("알람 중지", primary = true) { ui.stop(s, send) }
            else ActionButton("휴식 다시 준비", primary = true, enabled = p && !busy && s.has("alarmState")) { ui.prepare(s, now) }
            ActionButton("알람 상태 확인") { send("ready", JSONObject()) }
            if (s.string("alarmState") == "failed") ActionButton("알람 설정", enabled = p) { send("requestAlarmPermission", JSONObject()) }
        }
    }
}

@Composable
private fun SettingsContent(ui: RestUi, s: JSONObject, p: Boolean, busy: Boolean) {
    val locked = !p || busy || s.bool("pendingRestore")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("나의 휴식 설정")
        Note("선호는 이 기기에 저장해요. 차량 설정은 휴식마다 다시 동의해요.")
        if (s.bool("pendingRestore")) Warning("이전 설정을 복원하기 전에는 현재 환경을 유지해요. 휴식 시간은 바꿀 수 있어요.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(10, 15, 20, 25, 30).forEach { value -> SelectButton("${value}분", ui.nap == value) { ui.nap = value; ui.save() } } }
        ChoiceCheck("휴식 소리 사용", ui.restSound, s.bool("restSoundAvailable") && p) { ui.restSound = it; ui.save() }
        Note("앱의 합성 소리예요. 음악 서비스와 연결하지 않아요.")
        HorizontalDivider(color = Ink.line)
        PreferenceRow("온도 설정", "temperature", ui.temperatureOn, ui.temperature, locked, s, { ui.temperatureOn = it; ui.save() }, { ui.temperature = it; ui.save() }, (0..16).map { 18.0 + it * .5 })
        PreferenceRow("풍량 설정", "fan", ui.fanOn, ui.fan.toDouble(), locked, s, { ui.fanOn = it; ui.save() }, { ui.fan = it.toInt(); ui.save() }, listOf(1.0, 2.0, 3.0))
        PreferenceRow("실내 조명 낮추기", "brightness", ui.dim, 10.0, locked, s, { ui.dim = it; ui.save() })
        PreferenceRow("운전석 통풍", "seatVentilation", ui.ventilationOn, ui.ventilation.toDouble(), locked, s, { ui.ventilationOn = it; ui.save() }, { ui.ventilation = it.toInt(); ui.save() }, listOf(0.0, 1.0, 2.0, 3.0))
        PreferenceRow("공조 전원도 변경", "hvacPower", ui.powerSelected, if (ui.powerOn) 1.0 else 0.0, locked, s, { ui.powerSelected = it; ui.save() })
        ChoiceCheck("공조 켜기 · 해제하면 끄기", ui.powerOn, !locked && ui.powerSelected && capability(s, "hvacPower") == "supported") { ui.powerOn = it; ui.save() }
        Heading("차량에서 직접 조절해 주세요")
        Body("시트 자세 · 창문 · 블라인드 · 문 잠금")
        Note("이 버전은 자동 조작하지 않아요. 밀폐된 곳이나 배기구가 막힌 곳에서는 쉬지 마세요.")
    }
}

@Composable
private fun PreferenceRow(label: String, key: String, selected: Boolean, value: Double, locked: Boolean, s: JSONObject, onSelect: (Boolean) -> Unit, onValue: ((Double) -> Unit)? = null, values: List<Double> = emptyList()) {
    val support = capability(s, key)
    val enabled = !locked && support == "supported"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { ChoiceCheck(label, selected, enabled, onSelect) }
            if (onValue != null) {
                val supported = values.filter { supportedValue(s, key, it) }
                val lower = supported.lastOrNull { it < value }
                val upper = supported.firstOrNull { it > value }
                ActionButton("낮추기", enabled = enabled && selected && lower != null) { lower?.let(onValue) }
                Text(valueLabel(key, value), color = Ink.text, modifier = Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodyLarge)
                ActionButton("높이기", enabled = enabled && selected && upper != null) { upper?.let(onValue) }
            }
        }
        Note(when (support) { "supported" -> if (!supportedValue(s, key, value)) "현재 값은 차량 지원 범위 밖이에요. 다른 값을 선택해 주세요." else "이 차량에서 지원 확인 · 적용 결과는 별도로 확인해요."; "unsupported" -> "이 차량에서 자동 적용 불가 · 직접 조절해 주세요."; else -> "차량 지원을 아직 확인하지 못했어요." } + if (!enabled && selected) " 선호는 저장되어 있어요." else "")
        HorizontalDivider(color = Ink.line)
    }
}

@Composable
private fun OperationContent(ui: RestUi, s: JSONObject, motion: Boolean) {
    val operation = s.string("controlOperation")
    val phase = s.string("controlPhase")
    Heading(when { ui.waitingOperation != null -> if (ui.waitingExpired) "요청 결과 확인 필요" else "차량 응답 기다리는 중"; phase == "running" -> if (operation == "restore") "이전 설정 복원 중" else "휴식 환경 적용 중"; phase == "failed" -> if (operation == "restore") "복원하지 못한 항목이 있어요" else "적용하지 못한 항목이 있어요"; operation == "restore" -> "이전 설정 확인 결과"; else -> "휴식 환경 확인" })
    if (ui.waitingOperation != null) {
        Note(if (ui.waitingExpired) "아직 완료를 확인하지 못했어요. 차량 설정을 직접 확인해 주세요." else "차량에 요청을 보냈어요. 기기의 응답을 기다려요.")
        return
    }
    val steps = s.optJSONArray("controlSteps")
    val items = if (steps != null && steps.length() > 0) (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
        else s.optJSONObject("controlResults")?.let { results -> results.keys().asSequence().mapNotNull { key -> results.optJSONObject(key)?.let { JSONObject(it.toString()).put("key", key).put("operationId", s.string("controlOperationId")) } }.toList() }.orEmpty()
    if (items.isEmpty()) Note(if (s.bool("pendingRestore")) "이전 설정 복원이 남아 있어요." else if (operation == "restore" && phase == "complete") "복원할 차량 설정이 없어요." else "선택한 차량 설정이 없어요. 현재 환경을 유지해요.")
    items.filter { it.string("operationId") == s.string("controlOperationId") }.forEach { item -> key("${item.string("operationId")}:${item.string("key")}") { ControlStep(item, motion, ui.accentKey == item.string("key") && ui.accentOperation == item.string("operationId")) } }
}

@Composable
private fun ControlStep(item: JSONObject, motion: Boolean, accent: Boolean) {
    val key = item.string("key")
    val phase = item.string("state")
    val label = mapOf("temperature" to "온도", "fan" to "풍량", "brightness" to "조명", "seatVentilation" to "시트 통풍", "hvacPower" to "공조 전원")[key] ?: "차량 설정"
    val done = phase in listOf("applied", "restored", "manualPreserved")
    val failed = phase in listOf("failed", "pendingRestore")
    val working = phase in listOf("checking", "writing", "verifying")
    val ink = if (failed) Ink.amber else if (done || working) Ink.mint else Ink.muted
    val background by animateColorAsState(if (motion && accent && !failed) Ink.mint.copy(alpha = .13f) else Color.Transparent, tween(if (motion && !failed) 180 else 0), label = "confirmed item accent")
    val settle by animateFloatAsState(if (motion && accent && !failed) 1.08f else 1f, tween(if (motion && !failed) 180 else 0), label = "readback confirmation")
    val status = when (phase) { "queued" -> "순서 대기"; "checking" -> "지원·원래 값 확인"; "writing" -> "차량에 변경 요청"; "verifying" -> "실제 값 다시 확인"; "applied" -> "적용 확인"; "restored" -> "복원 확인"; "manualPreserved" -> "직접 바꾼 값 유지"; "failed", "pendingRestore" -> "완료 못함 · 직접 확인"; else -> "상태 확인 중" }
    Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val icon = when { done -> Icons.Outlined.CheckCircle; failed -> Icons.Outlined.ErrorOutline; phase == "checking" -> Icons.Outlined.Search; phase == "writing" -> Icons.Outlined.Tune; phase == "verifying" -> Icons.Outlined.Visibility; else -> Icons.Outlined.RadioButtonUnchecked }
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(23.dp).graphicsLayer { scaleX = settle; scaleY = settle })
            Text(label, Modifier.weight(1f), color = Ink.text, style = MaterialTheme.typography.bodyMedium)
            Text(status, color = ink, style = MaterialTheme.typography.bodyMedium)
        }
        if (failed) Note(friendlyError(key, item.string("message")))
        else item.number("actual")?.let { actual -> Note("차량 확인 값 · ${if (key == "hvacPower") if (actual == 1.0) "켜짐" else "꺼짐" else valueLabel(key, actual)}") }
    }
    HorizontalDivider(color = Ink.line)
}

@Composable
private fun Bot(pose: String, size: Dp, allowMotion: Boolean) {
    val context = LocalContext.current
    val breath = remember { Animatable(1f) }
    LaunchedEffect(pose, allowMotion) {
        breath.snapTo(1f)
        if (allowMotion) {
            while (isActive) { breath.animateTo(1.012f, tween(1500)); breath.animateTo(1f, tween(1500)) }
        }
    }
    Crossfade(targetState = pose, animationSpec = tween(if (allowMotion) 150 else 0), label = "companion pose") { visiblePose ->
        val bitmap = remember(visiblePose) { runCatching { context.assets.open("character/kkaebi-$visiblePose.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()?.asImageBitmap() }
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(size).graphicsLayer { scaleX = if (allowMotion) breath.value else 1f; scaleY = if (allowMotion) breath.value else 1f })
        else Spacer(Modifier.size(size))
    }
}

@Composable
private fun JournalContent(ui: RestUi) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("쉬어간 순간들")
        Note("이 기기에만 저장해요. 음성 원문은 남기지 않아요.")
        if (ui.logs.isEmpty()) { Body("첫 휴식을 기다리고 있어요."); Note("계획을 시작하거나 쉬어가면 그 순간을 남겨요.") }
        ui.logs.asReversed().forEach { (at, text) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) { Text(SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(at)), color = Ink.muted, style = MaterialTheme.typography.bodyMedium); Text(text, color = Ink.text, style = MaterialTheme.typography.bodyMedium) }; HorizontalDivider(color = Ink.line) }
    }
}
@Composable private fun ManualChecklist() { HorizontalDivider(color = Ink.line); Text("출발 전 직접 확인해 주세요", color = Ink.text, style = MaterialTheme.typography.titleLarge); Note("시트·거울 자세 · 창문·블라인드 · 차량 주변"); Note("깨비는 자세나 주변 안전을 자동 확인하지 않아요.") }
@Composable private fun Heading(text: String) { Text(text, color = Ink.text, style = MaterialTheme.typography.titleLarge) }
@Composable private fun Body(text: String) { Text(text, color = Ink.muted, style = MaterialTheme.typography.bodyLarge) }
@Composable private fun Note(text: String) { Text(text, color = Ink.muted, style = MaterialTheme.typography.bodyMedium) }
@Composable private fun Warning(text: String) { Text(text, color = Ink.amber, style = MaterialTheme.typography.bodyMedium) }
@Composable
private fun ActionButton(text: String, enabled: Boolean = true, primary: Boolean = false, urgent: Boolean = false, onClick: () -> Unit) {
    val fill = if (urgent) Ink.amber else if (primary) Ink.mint else Color.Transparent
    Button(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp), border = if (!primary && !urgent) BorderStroke(1.dp, Ink.line) else null, colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = if (primary || urgent) Ink.bg else Ink.text, disabledContainerColor = if (primary || urgent) fill.copy(alpha = .32f) else Color.Transparent, disabledContentColor = Ink.muted.copy(alpha = .6f)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text(text, fontFamily = Pretendard, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable
private fun EntryButton(title: String, body: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink.bg, disabledContainerColor = color.copy(alpha = .28f), disabledContentColor = Ink.muted), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(14.dp)) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontFamily = Pretendard, fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(body, fontFamily = Pretendard, fontSize = 14.sp, lineHeight = 19.sp) } }
}
@Composable
private fun SelectButton(text: String, selected: Boolean, onClick: () -> Unit) { ActionButton(text, primary = selected, onClick = onClick) }
@Composable
private fun ChoiceCheck(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Ink.mint, checkmarkColor = Ink.bg, uncheckedColor = Ink.muted)); Text(text, color = if (enabled) Ink.text else Ink.muted, style = MaterialTheme.typography.bodyMedium) }
}
