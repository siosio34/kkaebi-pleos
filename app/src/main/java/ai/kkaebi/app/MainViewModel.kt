package ai.kkaebi.app

import ai.kkaebi.app.engine.Frame
import ai.kkaebi.app.engine.Gear
import ai.kkaebi.app.engine.StimulusSettings
import ai.kkaebi.app.vehicle.DemoCar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Tab { DRIVE, REPORT, SETTINGS }

class MainViewModel(private val app: KkaebiApp) : ViewModel() {
    private val _frame = MutableStateFlow(Frame(0, null, null, Gear.UNKNOWN, false))
    val frame: StateFlow<Frame> = _frame
    private val _session = MutableStateFlow(SessionState())
    val session: StateFlow<SessionState> = _session
    val demoLog = MutableStateFlow<List<String>>(emptyList())
    var tab by mutableStateOf(Tab.DRIVE)
    var ready by mutableStateOf(false); private set
    val isDemo get() = app.car.isDemo
    val carName get() = app.car.name
    val micAvailable get() = app.ears.available
    var settings by mutableStateOf(StimulusSettings())
        private set
    private var s: Session? = null
    val log get() = s?.log

    init {
        viewModelScope.launch {
            while (!app.carResolved) delay(200)
            ready = true
            while (true) {
                _frame.value = app.car.frame.value
                s?.let { _session.value = it.state.value }
                (app.car as? DemoCar)?.let { demoLog.value = it.actionLog.value }
                delay(250)
            }
        }
    }

    val running get() = s != null && _session.value.phase != Phase.OFF && _session.value.phase != Phase.REPORT
    fun toggle() { if (running) stop() else start() }
    fun start() {
        val sess = Session(viewModelScope, app.car, app.voice, app.ears, app.sound, app.navi, app.questions, app.restAreas, app.car.isDemo)
        sess.settings = settings; s = sess; sess.start()
    }
    fun stop() { s?.stop(); tab = Tab.REPORT }
    fun updateSettings(f: (StimulusSettings) -> StimulusSettings) { settings = f(settings); s?.settings = settings }
    fun answer(text: String?, latencyMs: Long) = s?.simulateAnswer(text, latencyMs)
    fun startRest() = s?.startRest()
    fun skipRest() = s?.skipRest()

    // 데모 조작
    fun demo(): DemoCar? = app.car as? DemoCar
    fun demoDrowsy(on: Boolean) { demo()?.drowsy = on }
    fun demoGear(g: Gear) { demo()?.gear = g }
    fun demoDaw(on: Boolean) { demo()?.dawWarning = on }

    companion object {
        fun factory(app: KkaebiApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
        }
    }
}
