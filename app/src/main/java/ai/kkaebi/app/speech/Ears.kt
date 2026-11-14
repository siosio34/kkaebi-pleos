package ai.kkaebi.app.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 듣기. Pleos SpeechToText ON_DEVICE(앱 등록 불필요). 호출어는 일반 앱에 안 열려 있으므로
 * "질문이 끝난 직후"에만 마이크를 열고, 첫 발화까지의 시간(latency)과 내용을 돌려준다.
 * 마이크가 없는 에뮬레이터에서는 [available]=false → 화면 버튼으로 대답을 흉내 낸다.
 */
class Ears(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    @Volatile var available = false; private set
    @Volatile private var listening = false
    private var openedAt = 0L
    private var firstAt = 0L
    private var onResult: ((text: String?, latencyMs: Long) -> Unit)? = null
    private val timeout = Runnable { if (listening) finish(null) }

    fun start() = runCatching {
        val s = SpeechToText(appCtx, Mode.ON_DEVICE)
        s.initialize(object : OnInitializeListener {
            override fun onSuccess() { runCatching { s.setLocale(java.util.Locale.KOREA) }; available = true; Log.i(TAG, "stt ready") }
            override fun onFailure(type: ErrorType) { Log.w(TAG, "stt init failed: $type") }
        })
        s.addListener(object : ResultListener {
            override fun onUpdated(text: String, completed: Boolean) {
                if (!listening) return
                if (firstAt == 0L && text.isNotBlank()) firstAt = System.currentTimeMillis()
                if (completed) finish(text)
            }
            override fun onUpdatedEpdData(start: Long, end: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() {}
            override fun onError(type: ErrorType) { Log.w(TAG, "stt error $type"); if (listening) finish(null) }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) { if (listening && firstAt == 0L && rms > 0.2) firstAt = System.currentTimeMillis() }
        })
        stt = s
    }.onFailure { Log.w(TAG, "stt unavailable: ${it.message}") }

    private val appCtx = context.applicationContext

    /** 질문이 끝난 순간 호출. [timeoutMs] 안에 대답이 없으면 (null, timeout) */
    fun listen(timeoutMs: Long, result: (String?, Long) -> Unit): Boolean {
        if (!available) return false
        onResult = result; listening = true; openedAt = System.currentTimeMillis(); firstAt = 0L
        runCatching { stt?.request() }.onFailure { Log.w(TAG, "stt request: ${it.message}"); listening = false; return false }
        main.postDelayed(timeout, timeoutMs)
        return true
    }

    private fun finish(text: String?) {
        main.removeCallbacks(timeout); listening = false
        runCatching { stt?.stop() }
        val lat = if (text == null) -1L else ((if (firstAt > 0) firstAt else System.currentTimeMillis()) - openedAt)
        val cb = onResult; onResult = null
        main.post { cb?.invoke(text, lat) }
    }

    fun cancel() { main.removeCallbacks(timeout); if (listening) { listening = false; runCatching { stt?.stop() } }; onResult = null }
    fun stop() { cancel(); runCatching { stt?.release() }; stt = null; available = false }

    companion object { const val TAG = "Kkaebi" }
}
