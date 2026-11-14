package ai.kkaebi.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Only present in the debug source set; tests the actual OS alarm, receiver and audio service. */
class AlarmQaReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val c=RestAlarmController(context)
        when(intent.getStringExtra("operation")) {
            "schedule" -> c.schedule(10,"qa-${System.currentTimeMillis()}",intent.getLongExtra("delayMs",10_000))
            "ack" -> c.acknowledge(intent.getStringExtra("alarmId") ?: c.snapshot().id)
            "open" -> c.reconcileOnOpen()
            "capabilities" -> {
                val pending=goAsync()
                Thread {
                    val gateway=ai.kkaebi.app.rest.RestVehicleGateway(context)
                    try {
                        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(8_000) { gateway.start(); gateway.poll(); gateway.refreshCapabilities() } } }
                            .onFailure { Log.w(RestAlarmController.TAG,"qa-capabilities unavailable: ${it.javaClass.simpleName}") }
                        Log.i(RestAlarmController.TAG,"qa-capabilities ${gateway.controlCapabilities}")
                    }
                    finally { kotlinx.coroutines.runBlocking { gateway.close() }; pending.finish() }
                }.start()
            }
        }
        Log.i(RestAlarmController.TAG,"qa-state ${c.json()}")
    }
}
