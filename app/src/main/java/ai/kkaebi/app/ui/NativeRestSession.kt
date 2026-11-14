package ai.kkaebi.app.ui.nativeapp

import ai.kkaebi.app.rest.RestSafety

/** The display tick triggers recomposition; it is never the freshness clock. */
internal class NativeParkingGate(private val wallClock: () -> Long = System::currentTimeMillis) {
    fun permits(gear: String, speedKmh: Double?, gearAt: Long, speedAt: Long, restricted: Boolean): Boolean =
        RestSafety.parked(gear, speedKmh, gearAt, speedAt, wallClock(), restricted)
}

/** null delegates to Android Back. This policy never dispatches alarm or vehicle commands. */
internal fun nativeBackDestination(page: String, busy: Boolean, activeAlarm: Boolean,
                                   settingsParent: String, journalParent: String): String? = when {
    busy || page in setOf("HOME", "REST") || (page == "RECOVERY" && activeAlarm) -> null
    page == "SETTINGS" -> settingsParent
    page == "JOURNAL" -> journalParent
    activeAlarm -> "REST"
    else -> "HOME"
}

/** UI-only correlation. The vehicle gateway independently checks permission again. */
internal class NativeRestSession {
    var pendingRequest: String? = null
        private set
    var stopRequested: String? = null
        private set
    private var consumedAlarm: String? = null

    data class Decision(val accept: Boolean, val matchingRequest: Boolean = false, val allowEnvironment: Boolean = false, val stopped: Boolean = false)

    fun begin(requestId: String): Boolean {
        if (pendingRequest != null || !requestId.matches(Regex("[\\w-]{1,64}"))) return false
        pendingRequest = requestId
        return true
    }

    fun expireRequest() { pendingRequest = null }

    /** Intentionally has no gear argument: stopping an alarm is always available. */
    fun requestStop(alarmState: String, alarmId: String): Boolean {
        if (alarmState !in setOf("scheduled", "ringing") || alarmId.isBlank()) return false
        pendingRequest = null
        stopRequested = alarmId
        return true
    }

    fun reconcile(alarmState: String, alarmId: String, requestId: String, parked: Boolean,
                  placeConfirmed: Boolean, environmentConsent: Boolean, pendingRestore: Boolean): Decision {
        val matching = pendingRequest != null && pendingRequest == requestId
        if (pendingRequest != null && (!matching || alarmState == "idle")) return Decision(false)
        if (alarmState in setOf("scheduled", "ringing") && alarmId.isBlank()) return Decision(false)
        var apply = false
        if (matching && alarmState in setOf("scheduled", "ringing", "failed")) {
            pendingRequest = null
            if (alarmState == "scheduled") {
                apply = consumedAlarm != alarmId && stopRequested != alarmId && parked && placeConfirmed && environmentConsent && !pendingRestore
                // A missed or unsafe ACK can never become consent on a later snapshot.
                consumedAlarm = alarmId
            }
        }
        val stopped = alarmState == "idle" && stopRequested != null
        if (stopped) stopRequested = null
        return Decision(true, matching, apply, stopped)
    }
}

/** Completion accents are separate from truth text and never delay SDK work. */
internal class NativeConfirmationSequence {
    private var operationId = ""
    private val retired = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()

    fun observe(currentOperation: String, steps: List<Triple<String, String, String>>, motionAllowed: Boolean): List<String> {
        if (currentOperation.isBlank() || currentOperation in retired) return emptyList()
        if (currentOperation != operationId) {
            if (operationId.isNotBlank()) retired += operationId
            operationId = currentOperation
            seen.clear()
        }
        return steps.mapNotNull { (id, key, state) ->
            if (id != operationId || state !in setOf("applied", "restored", "failed", "manualPreserved") || !seen.add(key)) null
            else key.takeIf { motionAllowed && state in setOf("applied", "restored") }
        }.take(5)
    }
}

internal fun parseNativeCommand(raw: String): String {
    val text = raw.replace(Regex("[\\s.,!?。！？]"), "")
    return when {
        Regex("^(깨비)?(알람|타이머)(중지|그만|취소|꺼줘|끄기|멈춰|멈춰줘)$").matches(text) -> "ack"
        Regex("^(깨비)?(휴식|졸음모드)?(취소|그만|중지|조용히|말그만|대화그만|음성꺼줘)$").matches(text) -> "quiet"
        Regex("안졸|졸리지않|안쉬|쉬지않|휴식하지않|하지마|괜찮").containsMatchIn(text) -> "none"
        Regex("^(깨비)?(졸음모드|졸음모드켜줘|졸려|졸려요|졸립니다|졸음운전모드)$").matches(text) -> "drowsy-mode"
        Regex("^(깨비)?(휴식|휴식모드|휴식준비|쉬자|쉬고싶어|쉬고싶어요)$").matches(text) -> "rest-mode"
        Regex("^(깨비)?(환기|환기해줘|창문열어줘)$").matches(text) -> "ventilation"
        else -> "none"
    }
}
