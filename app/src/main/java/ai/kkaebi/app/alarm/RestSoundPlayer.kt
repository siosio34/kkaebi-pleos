package ai.kkaebi.app.alarm

import android.content.Context
import android.media.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** App-owned synthetic sleep sounds (rain, stream, waves, meditation tone), foreground only. Never changes system volume. */
class RestSoundPlayer(context: Context) {
    private val audio=context.applicationContext.getSystemService(AudioManager::class.java)
    private val epoch=AtomicInteger()
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    @Volatile var playing=false; private set
    @Volatile var message="휴식 소리 꺼짐"; private set
    /** rain | stream | waves | tone. 모두 앱이 합성하며 외부 음원을 쓰지 않는다. */
    @Volatile var kind="rain"
    private val names=mapOf("rain" to "빗소리","stream" to "물소리","waves" to "파도 소리","tone" to "명상 음")
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
                val sound=kind.takeIf { it in names } ?: "rain"
                track=local; local.setVolume(if(sound=="tone") .2f else .14f); local.play(); playing=true; message="${names[sound]} 재생 중 · 앱이 만든 합성 소리"
                val pcm=ShortArray(1200); var previous=0.0; var slow=0.0; var drop=0.0; var n=0L; val random=Random(41); val rate=24000.0
                while(token==epoch.get()) {
                    for(i in pcm.indices) {
                        val t=n++/rate; val white=random.nextDouble(-1.0,1.0)
                        val v=when(sound) {
                            // 빗소리: 부드러운 잡음 위에 가끔 떨어지는 빗방울
                            "rain" -> { previous=.8*previous+.2*white; if(random.nextInt(2600)==0) drop=.5; drop*=.9965; previous*.34+drop*white*.12 }
                            // 물소리: 더 밝은 잡음이 불규칙하게 일렁인다
                            "stream" -> { previous=.55*previous+.45*white; slow=.9995*slow+.0005*random.nextDouble(-1.0,1.0); previous*(.2+.9*kotlin.math.abs(slow)*6).coerceAtMost(.42) }
                            // 파도: 8초 주기로 밀려왔다 빠진다
                            "waves" -> { previous=.9*previous+.1*white; val swell=.5-.5*kotlin.math.cos(2*Math.PI*t/8.0); previous*(.12+.62*swell*swell) }
                            // 명상 음: 낮은 지속음 세 개가 천천히 겹친다
                            else -> { val breath=.6+.4*kotlin.math.sin(2*Math.PI*t/11.0); (kotlin.math.sin(2*Math.PI*174.61*t)*.2+kotlin.math.sin(2*Math.PI*261.63*t)*.11+kotlin.math.sin(2*Math.PI*176.2*t)*.14)*breath }
                        }
                        pcm[i]=(v.coerceIn(-1.0,1.0)*Short.MAX_VALUE).toInt().toShort()
                    }
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
