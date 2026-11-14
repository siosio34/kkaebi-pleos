package ai.kkaebi.app.alarm

import ai.kkaebi.app.R
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Started by the user's exact alarm; never from BOOT_COMPLETED or a background activity launch. */
class RestAlarmService: Service() {
    private var player: MediaPlayer?=null
    private var lock: PowerManager.WakeLock?=null
    private var focus: AudioFocusRequest?=null
    private var id=""
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller=RestAlarmController(this)
        val record=controller.snapshot()
        id=intent?.getStringExtra("alarmId") ?: record.id
        if(record.state!="ringing" || !AlarmPolicy.matches(record,id)) { stopSelf(); return START_NOT_STICKY }
        try {
            controller.channels()
            val notification=controller.notification(record,true)
            if(Build.VERSION.SDK_INT>=29) startForeground(RestAlarmController.NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(RestAlarmController.NOTIFICATION_ID,notification)
            if(player!=null) return START_STICKY
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val manager=getSystemService(AudioManager::class.java)
            focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener {
                if(it==AudioManager.AUDIOFOCUS_LOSS || it==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    controller.fail(id,"다른 오디오가 우선되어 알람 소리가 중단되었습니다 · 화면 알림을 확인해 주세요"); stopSelf()
                }
            }.build()
            check(manager.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "오디오 포커스 거절" }
            lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"kkaebi:rest-alarm").also { it.acquire(30*60_000L) }
            player=MediaPlayer().apply {
                setAudioAttributes(attributes)
                resources.openRawResourceFd(R.raw.rest_alarm).use { asset -> setDataSource(asset.fileDescriptor, asset.startOffset, asset.length) }
                isLooping=true
                setOnErrorListener { _,_,_ -> controller.fail(id,"알람 오디오 출력 실패 · 화면 알림을 확인해 주세요"); stopSelf(); true }
                prepare(); start()
            }
            android.util.Log.i(RestAlarmController.TAG,"audio-started id=$id")
        } catch(e: Exception) {
            controller.fail(id,"알람 소리 재생 실패 · ${e.javaClass.simpleName}")
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onDestroy() {
        runCatching { player?.stop(); player?.release() }; player=null
        focus?.let { runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }
        runCatching { if(lock?.isHeld==true) lock?.release() }; lock=null
        super.onDestroy()
    }
}
