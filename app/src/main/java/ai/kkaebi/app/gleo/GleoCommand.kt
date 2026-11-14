package ai.kkaebi.app.gleo

import com.google.gson.JsonObject

/** A recognized action is input, not proof of the caller's identity or permission to control a car. */
object GleoCommand {
    const val ACTION_PREPARE="ai.kkaebi.app.action.PREPARE"
    const val ACTION_PREPARE_SLEEP = "PREPARE_SLEEP_MODE"
    const val ACTION_PARAM = "ACTION_PARAM"
    // JSON permits only these four whitespace characters. Avoid platform-specific Regex dialects.
    private fun jsonWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun emptyObject(value: String): Boolean {
        if (value.length > 256) return false
        val trimmed = value.trim(::jsonWhitespace)
        return trimmed.length >= 2 && trimmed.first() == '{' && trimmed.last() == '}' &&
            trimmed.substring(1, trimmed.lastIndex).all(::jsonWhitespace)
    }

    /** Legacy callers remain preparation-only; no coercion of arbitrary extras to text. */
    fun validate(action:String?, command:String?):String? =
        if(action==ACTION_PREPARE && command in setOf("drowsy-mode","rest-mode","quiet")) command else null

    fun recognized(action: String?) = action == ACTION_PREPARE_SLEEP || action == ACTION_PREPARE

    /**
     * The published Gleo function has no parameters. Both documented wire shapes (individual
     * extras and ACTION_PARAM JSON) therefore mean an empty argument set. Reject added commands,
     * nesting, duplicate keys, malformed JSON, wrong types and oversize input rather than ignoring it.
     * A null command on a known Gleo action permits a truthful failure callback.
     */
    fun parse(action: String?, extras: Map<String, Any?> = emptyMap()): GleoRequest? = when (action) {
        ACTION_PREPARE_SLEEP -> {
            val valid = extras.isEmpty() || (extras.keys == setOf(ACTION_PARAM) &&
                (extras[ACTION_PARAM] as? String)?.let { emptyObject(it) } == true)
            GleoRequest(ACTION_PREPARE_SLEEP, if (valid) "rest-mode" else null, official = true)
        }
        ACTION_PREPARE -> GleoRequest(ACTION_PREPARE,
            if (extras.keys == setOf("command")) validate(action, extras["command"] as? String) else null,
            official = false)
        else -> null
    }

    fun response(request: GleoRequest, preparationAvailable: Boolean = true): GleoResponse? {
        if (!request.official || request.functionName != ACTION_PREPARE_SLEEP) return null
        return when {
            request.command != "rest-mode" -> GleoResponse(ACTION_PREPARE_SLEEP, "fail",
                "This sleep preparation action accepts no parameters.")
            !preparationAvailable -> GleoResponse(ACTION_PREPARE_SLEEP, "fail",
                "Sleep preparation is unavailable while another session is active.")
            else -> GleoResponse(ACTION_PREPARE_SLEEP, "asking",
                "After parking safely, confirm the sleep preset in Kkaebi to begin.")
        }
    }
}

data class GleoRequest(val functionName: String, val command: String?, val official: Boolean)
data class GleoResponse(val functionName: String, val status: String, val message: String, val mode: String = "voice") {
    /** Explicit wire names survive release shrinking; do not reflect over obfuscatable fields. */
    fun toJson(): String = JsonObject().apply {
        addProperty("functionName", functionName)
        addProperty("status", status)
        addProperty("message", message)
        addProperty("mode", mode)
    }.toString()
}
