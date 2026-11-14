package ai.kkaebi.app.alarm

import android.content.Context
import android.media.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** App-owned synthetic rain-like noise, foreground only. Never changes system volume. */
class RestSoundPlayer(context: Context) {
    private val audio=context.applicationContext.getSystemService(AudioManager::class.java)
    private val epoch=AtomicInteger()
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    @Volatile var playing=false; private set
    @Volatile var message="휴식 소리 꺼짐"; private set
    private var track: AudioTrack?=null
    private val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener {
        if(it<=0) stop("다른 오디오가 우선되어 휴식 소리를 멈췄습니다")
    }.build()
    fun start() {
        stop()
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { message="휴식 소리 재생 권한을 얻지 못했습니다"; return }
        val token=epoch.incrementAndGet()
        Thread {
            var local: AudioTrack?=null
            try {
                local=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(12000).setTransferMode(AudioTrack.MODE_STREAM).build()
                if(token!=epoch.get()) return@Thread
                track=local; local.setVolume(.12f); local.play(); playing=true; message="잔잔한 빗소리 재생 중 · 앱이 만든 합성 소리"
                val pcm=ShortArray(1200); var previous=0.0; val random=Random(41)
                while(token==epoch.get()) {
                    for(i in pcm.indices) { previous=.8*previous+.2*random.nextDouble(-1.0,1.0); pcm[i]=(previous*Short.MAX_VALUE*.35).toInt().toShort() }
                    if(local.write(pcm,0,pcm.size)<0) error("오디오 쓰기 실패")
                }
            } catch(e:Exception) { if(token==epoch.get()) message="휴식 소리 재생 실패 · ${e.javaClass.simpleName}" }
            finally { runCatching { local?.stop(); local?.release() }; if(token==epoch.get()) { playing=false; track=null } }
        }.apply { isDaemon=true; start() }
    }
    fun stop(reason:String="휴식 소리 꺼짐") {
        epoch.incrementAndGet(); playing=false; message=reason
        runCatching { track?.pause(); track?.flush() }; track=null
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }
}
