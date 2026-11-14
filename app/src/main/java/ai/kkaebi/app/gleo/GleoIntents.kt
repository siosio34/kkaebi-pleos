package ai.kkaebi.app.gleo

import android.content.Context
import android.content.Intent

/** Only opens preparation. No Vehicle, alarm or consent APIs are reachable from this adapter. */
object GleoIntents {
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    @Suppress("DEPRECATION")
    fun parse(intent: Intent): GleoRequest? {
        if (!GleoCommand.recognized(intent.action)) return null
        return runCatching {
            val extras = intent.extras
            // No data URI/ClipData channel is part of this function's published schema.
            if (intent.data != null || intent.clipData != null || (extras?.size() ?: 0) > 1) {
                GleoCommand.parse(intent.action, mapOf("invalid" to true))
            } else {
                GleoCommand.parse(intent.action, extras?.keySet()?.associateWith { extras.get(it) } ?: emptyMap())
            }
        }.getOrElse { GleoCommand.parse(intent.action, mapOf("invalid" to true)) }
    }

    /** Call synchronously on receipt; the official Gleo deadline is 7 seconds, before SDK setup. */
    fun sendResponse(context: Context, request: GleoRequest, preparationAvailable: Boolean = true): Boolean {
        val response = GleoCommand.response(request, preparationAvailable) ?: return false
        return runCatching {
            // The public protocol specifies this action and these extras, without a target package.
            // Only a fixed, non-sensitive preparation result is broadcast; no vehicle/session data.
            context.sendBroadcast(Intent(RESPONSE_ACTION)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toJson()))
            true
        }.getOrDefault(false)
    }
}
