package ai.kkaebi.app.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import ai.kkaebi.app.MainActivity
import ai.kkaebi.app.R
import org.json.JSONObject
import java.util.UUID

class RestAlarmController(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("system-rest-alarm", Context.MODE_PRIVATE)
    private val manager = ctx.getSystemService(AlarmManager::class.java)
    private val notifications = ctx.getSystemService(NotificationManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    fun bootCount(): Int = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    fun channels() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_RING, "깨비 휴식 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "사용자가 예약한 휴식 종료 알람"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        })
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "깨비 알람 예약", NotificationManager.IMPORTANCE_LOW))
    }
    fun permissionMessage(): String? {
        channels()
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) return "정확한 알람 권한을 허용해 주세요"
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "알림 권한을 허용해 주세요"
        if (!notifications.areNotificationsEnabled()) return "깨비 알림이 꺼져 있습니다"
        val channel = notifications.getNotificationChannel(CHANNEL_RING)
        if (channel == null || channel.importance < NotificationManager.IMPORTANCE_HIGH || channel.sound == null) return "휴식 알람 채널의 소리와 높은 중요도를 켜 주세요"
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) return "알람 음량이 0입니다 · 기기에서 음량을 올려 주세요"
        if (notifications.currentInterruptionFilter !in listOf(NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_ALARMS)) return "방해 금지 설정에서 알람이 들리는지 확인하고 해제해 주세요"
        if (bootCount() < 0) return "기기 재부팅 상태를 확인할 수 없어 알람을 설정할 수 없습니다"
        return null
    }
    private fun raw(): AlarmRecord {
        val text = prefs.getString("record", null) ?: return AlarmRecord()
        return runCatching {
            val j = JSONObject(text)
            AlarmRecord(j.getString("state"), j.getString("id"), j.getString("requestId"), j.getInt("bootCount"), j.getLong("elapsedDeadline"), j.getLong("wallDeadline"), j.getString("message"))
        }.getOrElse { AlarmRecord(state="failed", message="알람 저장 정보를 읽지 못했습니다 · 다시 설정해 주세요") }
    }
    private fun save(r: AlarmRecord) {
        val j=JSONObject().put("state",r.state).put("id",r.id).put("requestId",r.requestId).put("bootCount",r.bootCount)
            .put("elapsedDeadline",r.elapsedDeadline).put("wallDeadline",r.wallDeadline).put("message",r.message)
        check(prefs.edit().putString("record",j.toString()).commit()) { "알람 저장 실패" }
    }
    fun snapshot(): AlarmRecord = synchronized(LOCK) {
        var r=raw()
        if ((AlarmPolicy.active(r) || r.state=="arming") && !AlarmPolicy.sameBoot(r,bootCount())) {
            r=r.copy(state="failed",message="기기가 재부팅되어 이전 알람이 해제되었습니다 · 다시 설정해 주세요"); runCatching { save(r) }
        } else if (r.state=="arming") {
            manager.cancel(pending(r.id)); r=r.copy(state="failed",message="알람 예약 완료를 확인하지 못했습니다 · 다시 설정해 주세요"); runCatching { save(r) }
        } else if (r.state=="scheduled" && Build.VERSION.SDK_INT>=31 && !manager.canScheduleExactAlarms()) {
            r=r.copy(state="failed",message="정확한 알람 권한이 해제되어 시스템 예약을 유지할 수 없습니다 · 다시 허용하고 설정해 주세요"); runCatching { save(r) }
        }
        r
    }
    fun failRequest(requestId: String, message: String): AlarmRecord = synchronized(LOCK) {
        val old=snapshot()
        if (AlarmPolicy.active(old)) old else AlarmRecord(state="failed",requestId=requestId.take(64),message=message).also { runCatching { save(it) } }
    }
    /** Force-stop removes OS alarms but preserves preferences. Reconcile only when the user reopens. */
    fun reconcileOnOpen(): AlarmRecord = synchronized(LOCK) {
        val r=snapshot()
        if(r.state=="ringing") {
            val problem=permissionMessage()
            if(problem!=null) return@synchronized r.copy(state="failed",message=problem).also { runCatching { save(it) } }
            return@synchronized runCatching {
                ContextCompat.startForegroundService(ctx,Intent(ctx,RestAlarmService::class.java).putExtra("alarmId",r.id))
                r
            }.getOrElse { r.copy(state="failed",message="알람 소리 복구 실패 · 알람을 다시 설정해 주세요").also { failed -> runCatching { save(failed) } } }
        }
        if(r.state!="scheduled") return@synchronized r
        if(!AlarmPolicy.canRestoreOnOpen(r,SystemClock.elapsedRealtime(),bootCount())) {
            return@synchronized r.copy(state="failed",message="앱을 다시 여는 동안 예약 시간이 지났습니다 · 알람을 다시 설정해 주세요").also { runCatching { save(it) } }
        }
        val problem=permissionMessage()
        if(problem!=null) return@synchronized r.copy(state="failed",message=problem).also { runCatching { save(it) } }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,r.elapsedDeadline,pending(r.id))
            r.copy(message="기존 시스템 알람 예약을 다시 확인했습니다").also(::save)
        } catch(e:Exception) {
            r.copy(state="failed",message="기존 알람 복구 실패 · 다시 설정해 주세요").also { runCatching { save(it) } }
        }
    }
    /** override is used exclusively by the debug-only QA receiver; release has no exported QA component. */
    fun schedule(minutes: Int, requestId: String, testDelayMs: Long? = null): AlarmRecord = synchronized(LOCK) {
        val previous=snapshot()
        if (AlarmPolicy.active(previous)) return@synchronized previous
        if (!AlarmPolicy.validRequest(minutes,requestId)) return@synchronized failRequest(requestId,"휴식 시간 또는 요청 식별자가 올바르지 않습니다")
        permissionMessage()?.let { return@synchronized failRequest(requestId,it) }
        val duration=testDelayMs?.coerceIn(3_000,30_000) ?: minutes*60_000L
        val r=AlarmRecord("arming",UUID.randomUUID().toString(),requestId,bootCount(),SystemClock.elapsedRealtime()+duration,System.currentTimeMillis()+duration,"시스템 알람 예약 중")
        try {
            save(r)
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,r.elapsedDeadline,pending(r.id))
            val scheduled=r.copy(state="scheduled",message="시스템 휴식 알람 예약됨 · 앱을 닫아도 유지됩니다")
            save(scheduled)
            notifications.notify(NOTIFICATION_ID,notification(scheduled,false))
            android.util.Log.i(TAG,"scheduled id=${r.id} request=${r.requestId} boot=${r.bootCount} elapsedNow=${SystemClock.elapsedRealtime()} elapsedDeadline=${r.elapsedDeadline} wallDeadline=${r.wallDeadline}")
            scheduled
        } catch (e: Exception) {
            runCatching { manager.cancel(pending(r.id)) }
            r.copy(state="failed",message="시스템 알람 예약 실패 · ${e.javaClass.simpleName}").also { runCatching { save(it) } }
        }
    }
    fun fire(id: String): Boolean = synchronized(LOCK) {
        val r=snapshot()
        if (!AlarmPolicy.canFire(r,id,SystemClock.elapsedRealtime(),bootCount())) return@synchronized false
        val problem=permissionMessage()
        if (problem!=null) { fail(id,"알람을 울릴 수 없습니다 · $problem"); return@synchronized false }
        save(r.copy(state="ringing",message="휴식 시간이 끝났습니다 · 알람을 확인해 주세요"))
        android.util.Log.i(TAG,"ringing id=$id elapsedNow=${SystemClock.elapsedRealtime()} elapsedDeadline=${r.elapsedDeadline}")
        true
    }
    fun acknowledge(id: String): Boolean = synchronized(LOCK) {
        val r=snapshot()
        if (!AlarmPolicy.matches(r,id)) return@synchronized false
        manager.cancel(pending(id))
        save(r.copy(state="idle",message="알람을 종료했습니다 · 회복 상태를 확인해 주세요"))
        ctx.stopService(Intent(ctx,RestAlarmService::class.java))
        notifications.cancel(NOTIFICATION_ID)
        android.util.Log.i(TAG,"acknowledged id=$id")
        true
    }
    fun fail(id: String, message: String) = synchronized(LOCK) {
        val r=raw()
        if (AlarmPolicy.matches(r,id)) {
            save(r.copy(state="failed",message=message))
            runCatching { notifications.notify(NOTIFICATION_ID,notification(r.copy(state="failed",message=message),false)) }
        }
    }
    fun markReboot() = synchronized(LOCK) {
        val r=raw()
        // Android 15 also broadcasts BOOT_COMPLETED on un-stop: compare actual boot identity.
        if ((AlarmPolicy.active(r) || r.state=="arming") && !AlarmPolicy.sameBoot(r,bootCount())) {
            save(r.copy(state="failed",message="재부팅으로 알람이 해제되었습니다 · 다시 설정해 주세요"))
            notifications.cancel(NOTIFICATION_ID)
        }
    }
    fun json(): JSONObject {
        val r=snapshot()
        val problem=runCatching { permissionMessage() }.getOrElse { "알람 권한을 확인할 수 없습니다" }
        return JSONObject().put("alarmState",r.state).put("alarmId",r.id).put("alarmRequestId",r.requestId).put("requestId",r.requestId)
            .put("alarmDeadline",r.wallDeadline).put("alarmRemainingMs",AlarmPolicy.remaining(r,SystemClock.elapsedRealtime(),bootCount()))
            .put("alarmMessage",r.message).put("alarmReady",problem==null).put("alarmPermissionMessage",problem ?: "정확한 알람과 알림 사용 가능")
    }
    private fun identity(id:String, operation:String)=Uri.Builder().scheme("kkaebi").authority("alarm").appendPath(id).appendPath(operation).build()
    private fun pending(id: String)=PendingIntent.getBroadcast(ctx,0,Intent(ctx,RestAlarmReceiver::class.java).setAction(ACTION_FIRE).setData(identity(id,"fire")).putExtra("alarmId",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun notification(record: AlarmRecord, ringing: Boolean): Notification {
        val open=PendingIntent.getActivity(ctx,0,Intent(ctx,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getBroadcast(ctx,1,Intent(ctx,RestAlarmReceiver::class.java).setAction(ACTION_ACK).setData(identity(record.id,"ack")).putExtra("alarmId",record.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx,if(ringing) CHANNEL_RING else CHANNEL_STATUS).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if(ringing) "깨비 · 휴식 시간이 끝났어요" else "깨비 · 휴식 알람")
            .setContentText(record.message).setContentIntent(open).setCategory(if(ringing) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setOngoing(ringing).setOnlyAlertOnce(true).setSilent(true)
            .addAction(NotificationCompat.Action.Builder(0,if(ringing) "알람 확인" else "알람 취소",stop).build()).build()
    }
    companion object {
        private val LOCK=Any()
        const val TAG="KkaebiAlarm"
        const val CHANNEL_RING="rest-alarm-v1"
        const val CHANNEL_STATUS="rest-alarm-status-v1"
        const val NOTIFICATION_ID=703
        const val ACTION_FIRE="ai.kkaebi.app.alarm.FIRE"
        const val ACTION_ACK="ai.kkaebi.app.alarm.ACK"
    }
}
