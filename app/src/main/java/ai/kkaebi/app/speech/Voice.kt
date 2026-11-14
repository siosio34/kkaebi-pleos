package ai.kkaebi.app.speech

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 말하기. Pleos TTS ON_DEVICE → 안 되면 안드로이드 TTS. [speak]의 onDone은 "말이 끝난 순간"이라 그때부터 대답 지연을 잰다.
 */
class Voice(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var pleos: TextToSpeech? = null
    @Volatile private var pleosReady = false
    private var platform: android.speech.tts.TextToSpeech? = null
    @Volatile private var platformReady = false
    @Volatile var engine = "none"; private set
    @Volatile var lastCompletedSuccessfully = false; private set
    @Volatile private var onDone: (() -> Unit)? = null

    @Volatile private var generation = 0

    fun start() {
        val epoch = ++generation
        runCatching {
            val t = TextToSpeech(ctx, Mode.ON_DEVICE)
            pleos = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    if (epoch != generation) return
                    runCatching {
                        t.setAudioAttributes(attrs)
                        t.setLocale(Locale.KOREA)
                        t.addEventListener(object : EventListener {
                            override fun onStart() {}
                            override fun onReady() {}
                            override fun onDone() { if (epoch == generation) { lastCompletedSuccessfully=true; fireDone() } }
                            override fun onError(error: ErrorType) { if (epoch == generation) fireDone() }
                            override fun onStop() { if (epoch == generation) fireDone() }
                            override fun onUpdatedRms(rms: Double) {}
                        })
                        pleosReady = true; engine = "pleos"
                    }.onFailure { startPlatform(epoch) }
                }
                override fun onFailure(error: ErrorType) {
                    if (epoch == generation) { Log.w(TAG, "pleos tts init failed: $error"); startPlatform(epoch) }
                }
            })
        }.onFailure { if (epoch == generation) { Log.w(TAG, "pleos tts unavailable: ${it.message}"); startPlatform(epoch) } }
    }

    private fun startPlatform(epoch: Int) {
        if (platform != null || epoch != generation) return
        platform = android.speech.tts.TextToSpeech(ctx) { status ->
            if (epoch != generation) return@TextToSpeech
            platformReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (platformReady) {
                engine = "android"; platform?.language = Locale.KOREA; platform?.setAudioAttributes(attrs)
                platform?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { lastCompletedSuccessfully=true; fireDone() }
                    @Deprecated("") override fun onError(utteranceId: String?) { fireDone() }
                })
            }
        }
    }

    private fun fireDone() { val cb = onDone; onDone = null; cb?.let { main.post(it) } }

    /** 말하고, 끝나면 [done]. 엔진이 없으면 글자 수로 어림한 시간 뒤 done */
    fun speak(text: String, done: (() -> Unit)? = null) {
        if (text.isBlank()) { done?.invoke(); return }
        onDone = done; lastCompletedSuccessfully=false
        when {
            pleosReady -> runCatching { pleos?.speak(text, true) }.onFailure { Log.w(TAG, "speak: ${it.message}"); fireDone() }
            platformReady -> platform?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "kkaebi-${System.nanoTime()}")
            else -> { Log.i(TAG, "tts not ready: $text"); main.postDelayed({ fireDone() }, 400L + text.length * 90L) }
        }
    }

    fun setSpeaker(type: String) { runCatching { pleos?.setSpeakerType(type) } }

    /** Stop the current utterance without destroying either engine. */
    fun cancel() {
        main.removeCallbacksAndMessages(null); onDone = null
        runCatching { pleos?.stop() }; runCatching { platform?.stop() }
    }

    /** 경고음 (미디어 볼륨 안 건드림, 알림 스트림) */
    fun warnTone() {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply { startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700); main.postDelayed({ release() }, 900) } }
    }

    fun stop() { generation++; main.removeCallbacksAndMessages(null); onDone = null; runCatching { pleos?.stop(); pleos?.release() }; runCatching { platform?.shutdown() }; pleos = null; platform = null; pleosReady = false; platformReady = false; engine = "none" }

    companion object { const val TAG = "Kkaebi" }
}
