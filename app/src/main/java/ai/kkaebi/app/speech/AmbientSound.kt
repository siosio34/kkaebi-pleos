package ai.kkaebi.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.random.Random

/**
 * 휴식·숙면 프리셋용 배경 소리. 파일 없이 생성한다(백색소음·저음 소음·빗소리 느낌).
 * 미디어 usage로 재생해서 내비 안내 음성이 나오면 차량 정책대로 덕킹된다. 시스템 볼륨은 바꾸지 않는다.
 */
class AmbientSound(context: Context) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) stop() }.build()
    private var thread: Thread? = null
    @Volatile private var kind = "off"
    val current: String get() = kind

    fun play(type: String) {
        if (type == "off" || type.isBlank()) { stop(); return }
        if (type == kind && thread?.isAlive == true) return
        stop()
        if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        kind = type
        thread = Thread {
            val rate = 24_000
            val track = AudioTrack.Builder().setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate).setTransferMode(AudioTrack.MODE_STREAM).build()
            track.setVolume(0.35f)
            track.play()
            val buf = ShortArray(2400)
            var brown = 0.0
            var drop = 0
            val rnd = Random(7)
            try {
                while (kind == type && !Thread.currentThread().isInterrupted) {
                    for (i in buf.indices) {
                        val white = rnd.nextDouble(-1.0, 1.0)
                        val v = when (type) {
                            "brown" -> { brown = (brown + 0.02 * white).coerceIn(-1.0, 1.0); brown * 3.0 }
                            "rain" -> { if (drop <= 0 && rnd.nextDouble() < 0.002) drop = 40; val d = if (drop > 0) { drop--; rnd.nextDouble(-1.0, 1.0) * 0.9 } else 0.0; white * 0.25 + d }
                            else -> white * 0.6
                        }
                        buf[i] = (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.5).toInt().toShort()
                    }
                    track.write(buf, 0, buf.size)
                }
            } finally { runCatching { track.stop(); track.release() } }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        kind = "off"
        thread?.interrupt(); thread = null
        runCatching { am.abandonAudioFocusRequest(focus) }
    }
}
