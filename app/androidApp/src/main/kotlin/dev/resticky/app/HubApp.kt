package dev.resticky.app

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.resticky.CLOCK_DIAL
import dev.resticky.CLOCK_FACES
import dev.resticky.HOUR_24
import dev.resticky.HOUR_FORMATS
import dev.resticky.FirmwareShelf
import dev.resticky.Hub
import dev.resticky.Releases
import dev.resticky.PENDING
import dev.resticky.placeTag
import dev.resticky.Pipeline
import dev.resticky.Store
import dev.resticky.WEATHER_SKETCH
import dev.resticky.WEATHER_STYLES
import dev.resticky.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import java.io.File

const val PORT = 8787

fun log(message: String) {
    Log.i("resticky", message)
}

class HubApp : Application() {
    lateinit var store: Store
    lateinit var engines: Engines
    lateinit var pipeline: Pipeline
    lateinit var hub: Hub
    lateinit var ble: BleHub
    lateinit var companion: Companion
    lateinit var weather: Weather
    lateinit var calendars: Calendars
    lateinit var locator: Locator
    lateinit var places: PlaceStore
    lateinit var photos: PhotoDesk
    lateinit var releases: Releases
    lateinit var firmware: FirmwareShelf
    lateinit var updater: Updater
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }

    var hubOn: Boolean
        get() = prefs.getBoolean("on", false)
        set(on) {
            prefs.edit().putBoolean("on", on).apply()
            val intent = Intent(this, HubService::class.java)
            if (on) startForegroundService(intent) else stopService(intent)
        }
    fun setClockFace(face: String) {
        if (face !in CLOCK_FACES) return
        prefs.edit().putString("clock_face", face).apply()
        hub.clockFace.value = face
        log("INFO clock face set face=$face")
    }

    fun setHourFormat(format: String) {
        if (format !in HOUR_FORMATS) return
        prefs.edit().putString("hour_format", format).apply()
        hub.hourFormat.value = format
        log("INFO hour format set format=$format")
    }

    fun setBeep(on: Boolean) {
        hub.setBeep(on)
        log("INFO beep set beep=$on")
    }

    fun setWeatherStyle(style: String) {
        if (style !in WEATHER_STYLES) return
        prefs.edit().putString("weather_style", style).apply()
        hub.weatherStyle.value = style
        log("INFO weather style set style=$style")
    }

    private suspend fun tagPlace(device: String, id: String) {
        if (!locator.canTag(onScreen)) return
        store.update(device, id) { it.place = PENDING }
        scope.launch {
            val fix = locator.fix(onScreen)
            val tag = fix?.let { placeTag(places.list.value, it.latitude, it.longitude) }
            store.update(device, id) { m ->
                if (m.place == PENDING || m.place.isEmpty()) {
                    m.place = tag?.name.orEmpty()
                    m.placeLat = tag?.lat
                    m.placeLon = tag?.lon
                }
            }
            log("INFO place session=$id ${if (tag == null) "no fix" else "tagged"}")
            pipeline.kick()
        }
    }

    val running = MutableStateFlow(false)
    @Volatile var onScreen = false

    override fun onCreate() {
        super.onCreate()
        store = Store(Path(File(filesDir, "data").path))
        engines = Engines(this) { onScreen }
        pipeline = Pipeline(store, engines.transcriber, engines.summarizer, engines.diarizer, DIARIZE_WINDOW_MS, ::log)
        locator = Locator(this)
        places = PlaceStore(this)
        photos = PhotoDesk(this, store)
        calendars = Calendars(this)
        weather = Weather(Path(File(filesDir, "weather.json").path), locator::locate, ::log)
        firmware = FirmwareShelf(Path(File(filesDir, "firmware").path))
        releases = Releases(Path(File(filesDir, "releases.json").path), ::log, "resticky-android/${packageManager.getPackageInfo(packageName, 0).versionName}")
        updater = Updater(this, releases)
        hub = Hub(store, pipeline, weather, firmware, ::log, ::tagPlace)
        hub.clockFace.value = prefs.getString("clock_face", null)?.takeIf { it in CLOCK_FACES } ?: CLOCK_DIAL
        hub.weatherStyle.value = prefs.getString("weather_style", null)?.takeIf { it in WEATHER_STYLES } ?: WEATHER_SKETCH
        hub.hourFormat.value = prefs.getString("hour_format", null)?.takeIf { it in HOUR_FORMATS } ?: HOUR_24
        hub.beep.value = prefs.getBoolean("beep", true)
        scope.launch { hub.beep.collect { prefs.edit().putBoolean("beep", it).apply() } }
        companion = Companion(this)
        ble = BleHub(this, hub, scope) { companion.address }
    }
}
