package ai.kkaebi.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RestAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller=RestAlarmController(context)
        val id=intent.getStringExtra("alarmId") ?: return
        if(intent.action==RestAlarmController.ACTION_ACK) { runCatching { controller.acknowledge(id) }; return }
        if(intent.action!=RestAlarmController.ACTION_FIRE || !runCatching { controller.fire(id) }.getOrDefault(false)) return
        runCatching {
            ContextCompat.startForegroundService(context,Intent(context,RestAlarmService::class.java).putExtra("alarmId",id))
        }.onFailure { controller.fail(id,"알람 소리 서비스를 시작하지 못했습니다 · ${it.javaClass.simpleName}") }
    }
}
class RestAlarmBootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action==Intent.ACTION_BOOT_COMPLETED) RestAlarmController(context).markReboot()
    }
}
