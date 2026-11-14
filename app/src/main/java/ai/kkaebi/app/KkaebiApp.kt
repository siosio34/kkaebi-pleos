package ai.kkaebi.app

import ai.kkaebi.app.engine.Question
import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.navi.NaviBridge
import ai.kkaebi.app.speech.AmbientSound
import ai.kkaebi.app.speech.Ears
import ai.kkaebi.app.speech.Voice
import ai.kkaebi.app.vehicle.Car
import ai.kkaebi.app.vehicle.DemoCar
import ai.kkaebi.app.vehicle.PleosCar
import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class KkaebiApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var voice: Voice; private set
    lateinit var ears: Ears; private set
    lateinit var sound: AmbientSound; private set
    var navi: NaviBridge? = null; private set
    @Volatile var car: Car = DemoCar(); private set
    @Volatile var carResolved = false; private set
    lateinit var questions: List<Question>; private set
    lateinit var restAreas: List<RestArea>; private set

    override fun onCreate() {
        super.onCreate()
        val gson = Gson()
        questions = gson.fromJson(assets.open("questions.json").bufferedReader().readText(), object : TypeToken<List<Question>>() {}.type)
        restAreas = gson.fromJson(assets.open("rest_areas.json").bufferedReader().readText(), object : TypeToken<List<RestArea>>() {}.type)
        voice = Voice(this).also { it.start() }
        ears = Ears(this).also { it.start() }
        sound = AmbientSound(this)
        scope.launch {
            val pleos = PleosCar(this@KkaebiApp)
            val ok = runCatching { withTimeout(4000) { pleos.start() } }.isSuccess
            if (ok) { car = pleos; navi = NaviBridge(this@KkaebiApp).also { it.start() }; Log.i(TAG, "Pleos SDK connected") }
            else { Log.w(TAG, "Pleos SDK unavailable → demo car"); runCatching { pleos.stop() }; car = DemoCar().also { it.start() } }
            carResolved = true
        }
    }

    companion object { const val TAG = "Kkaebi" }
}
