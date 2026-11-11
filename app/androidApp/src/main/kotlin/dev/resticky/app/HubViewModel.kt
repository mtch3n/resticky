package dev.resticky.app

import android.app.Application
import android.content.IntentSender
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.resticky.Checkin
import kotlinx.coroutines.withContext
import java.io.File
import dev.resticky.NamedPlace
import dev.resticky.PENDING
import dev.resticky.Conversation
import dev.resticky.NO_SPEECH
import dev.resticky.Recap
import dev.resticky.closed
import dev.resticky.conversations
import dev.resticky.Session
import dev.resticky.TodoList
import dev.resticky.TodoState
import dev.resticky.todoList
import dev.resticky.drainRates
import android.net.wifi.WifiManager
import dev.resticky.WeatherReport
import dev.resticky.WeatherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.net.Inet4Address
import kotlin.time.Clock
import kotlin.time.Instant

data class HubState(
    val on: Boolean = false,
    val running: Boolean = false,
    val address: String? = null,
    val sticky: Checkin? = null,
    val todayMs: Long = 0,
    val received: List<String> = emptyList(),
    val stickies: List<StickyInfo> = emptyList(),
    val now: Instant = Clock.System.now(),
)

data class StickyInfo(val address: String, val name: String)

class ConversationView(val conversation: Conversation, val recap: Recap, val ready: Boolean, val live: Boolean) {
    val device get() = conversation.device
    val key get() = conversation.key
}

data class DeviceTodos(val device: String, val list: TodoList)

data class ModelsState(
    val models: Map<Model, ModelState> = emptyMap(),
    val nano: Int? = null,
    val speeds: Map<String, Double> = emptyMap(),
)

class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as HubApp
    private val engines = app.engines

    val runs: StateFlow<List<Run>> = engines.runs
    val speech: StateFlow<Model> = engines.speech
    val summaryOpen: StateFlow<SummaryEngine> = engines.summaryOpen
    val summaryClosed: StateFlow<SummaryEngine> = engines.summaryClosed
    val ble: StateFlow<BleState> = app.ble.state

    val hub: StateFlow<HubState> = poll(HubState(), 1000) {
        val sessions = app.store.all()
        val sticky = app.hub.devices().values.maxByOrNull { it.seen ?: Instant.DISTANT_PAST }
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(zone).date
        HubState(
            on = app.hubOn,
            running = app.running.value,
            address = wifiAddress(),
            sticky = sticky,
            todayMs = sessions.filter { it.started.toLocalDateTime(zone).date == today && (sticky == null || it.device == sticky.device) }.sumOf { it.durationMs },
            received = sessions.flatMap { s -> s.chunks.filterValues { it == PENDING }.keys.map { "${s.id}/$it" } },
            stickies = app.companion.associations.mapNotNull { a ->
                val address = a.deviceMacAddress?.toString()?.uppercase() ?: return@mapNotNull null
                val name = a.displayName?.toString()?.takeUnless { it.isBlank() || it.equals("resticky", ignoreCase = true) }
                StickyInfo(address, name ?: address.replace(":", "").takeLast(4))
            },
        )
    }

    val conversations: StateFlow<List<ConversationView>> = poll(emptyList(), 2000) {
        val sessions = app.store.all()
        withContext(Dispatchers.Default) {
            val now = Clock.System.now()
            conversations(sessions).map { c ->
                val tail = c.parts.last()
                val ready = c.ready()
                ConversationView(c, c.recap(), ready, !ready && tail.toMs == tail.session.endMs() && !tail.session.closed(now))
            }.filterNot { it.ready && it.recap.title == NO_SPEECH }
        }
    }

    val todos: StateFlow<List<DeviceTodos>> = poll(emptyList(), 1000) {
        app.store.all().map { it.device }.distinct().map { DeviceTodos(it, todoList(app.store.list(it))) }
    }

    val models: StateFlow<ModelsState> = poll(ModelsState(), 1000) {
        ModelsState(
            Model.entries.associateWith { engines.state(it) },
            runCatching { engines.nanoStatus() }.getOrNull(),
            (Model.entries.map { it.label } + SummaryEngine.entries.map { it.label }).mapNotNull { l -> engines.speed(l)?.let { l to it } }.toMap(),
        )
    }

    val healthHours = MutableStateFlow(24)

    @OptIn(ExperimentalCoroutinesApi::class)
    val health: StateFlow<HealthView?> = healthHours.flatMapLatest { hours ->
        flow {
            while (true) {
                emit(readHealth(hours))
                delay(30_000)
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private suspend fun readHealth(hours: Int): HealthView {
        val sticky = app.hub.devices().values.maxByOrNull { it.seen ?: Instant.DISTANT_PAST }
        val device = sticky?.device ?: app.hub.healthDevices().firstOrNull()
        val to = Clock.System.now().epochSeconds
        val samples = device?.let { app.hub.health(it, hours) }.orEmpty()
        return HealthView(hours, to - hours * 3600L, to, healthCharts(samples), drainRates(samples), sticky?.health?.boot.orEmpty())
    }

    private fun <T> poll(initial: T, millis: Long, read: suspend () -> T): StateFlow<T> = flow {
        while (true) {
            emit(read())
            delay(millis)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    private fun wifiAddress(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    }

    suspend fun sessionAudio(s: Session): SessionAudio? = withContext(Dispatchers.IO) {
        val frames = app.store.sessionFrames(s) ?: return@withContext null
        SessionAudio(frames, File(app.store.dir(s.device, s.id).toString(), LISTEN_FILE).takeIf { it.exists() }?.readBytes())
    }

    fun denoiserModel(): String? = engines.modelPath(Model.Denoiser)

    private val playerPrefs = app.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
    val player = MutableStateFlow(
        PlayerPrefs(
            playerPrefs.getBoolean("clean_up", false),
            playerPrefs.getBoolean("skip_silence", false),
            Crisp.entries.firstOrNull { it.name == playerPrefs.getString("crisp", null) } ?: Crisp.Off,
        ),
    )

    fun setPlayer(p: PlayerPrefs) {
        playerPrefs.edit().putBoolean("clean_up", p.cleanUp).putBoolean("skip_silence", p.skipSilence).putString("crisp", p.crisp.name).apply()
        player.value = p
    }

    val release: StateFlow<dev.resticky.Release?> = app.releases.latest
    val releaseChecked: StateFlow<Long> = app.releases.checked
    val updateStatus: StateFlow<String> = app.updater.status
    val appVersion = app.updater.version
    val firmwareState: StateFlow<FwState> = app.stickyUpdate.state

    fun checkUpdates() = app.stickyUpdate.check()

    fun updateSticky() = app.stickyUpdate.update()

    fun cancelSticky() = app.stickyUpdate.cancel()

    fun installApp() = viewModelScope.launch { app.releases.latest.value?.let { app.updater.install(it) } }

    fun showOnSticky(file: java.io.File) = viewModelScope.launch {
        val message = app.showOnSticky { file.inputStream() }
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun showOnSticky(uri: android.net.Uri) = viewModelScope.launch {
        val message = app.showOnSticky { app.contentResolver.openInputStream(uri) }
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    val unlinked = app.photos.unlinked

    fun newSnap() = app.photos.newSnap()

    fun savePhoto(file: java.io.File) = viewModelScope.launch {
        val message = app.photos.save(file)
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun photoFile(s: Session, p: dev.resticky.Photo) = app.photos.file(s, p)

    fun attach(photo: Unlinked, s: Session) = viewModelScope.launch { app.photos.attach(photo, s) }

    fun renameSpeaker(device: String, id: String, speaker: Int, name: String) = viewModelScope.launch {
        app.store.update(device, id) { it.renameSpeaker(speaker, name) }
    }

    fun setHub(on: Boolean) {
        app.hubOn = on
    }

    fun setTodo(device: String, session: String, index: Int, done: Boolean) = viewModelScope.launch {
        app.hub.setTodo(device, session, index, TodoState(done, Clock.System.now()))
    }

    fun selectSpeech(m: Model) {
        engines.selectSpeech(m)
        if (engines.state(m) == ModelState.Missing) engines.download(m)
    }

    fun selectSummary(open: Boolean, e: SummaryEngine) {
        engines.selectSummary(open, e)
        if (e == SummaryEngine.Gemma && engines.state(Model.Gemma4E2B) == ModelState.Missing) engines.download(Model.Gemma4E2B)
    }

    fun download(m: Model) = engines.download(m)

    fun downloadNano() = viewModelScope.launch { runCatching { engines.downloadNano() } }

    fun enterPasskey(code: String) = app.ble.enterPasskey(code)

    fun pair(onPending: (IntentSender) -> Unit) = app.companion.associate(onPending) { app.ble.relink() }

    fun forget() {
        app.companion.forget()
        app.ble.relink()
    }

    fun selectTranscribe(m: TranscribeMode) = engines.selectTranscribe(m)

    val transcribe: StateFlow<TranscribeMode> = engines.transcribe

    val calendarChoice: StateFlow<CalendarChoice> = app.calendars.choice
    val calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendarsAllowed = MutableStateFlow(app.calendars.allowed)

    fun loadCalendars() = viewModelScope.launch(Dispatchers.IO) {
        calendarsAllowed.value = app.calendars.allowed
        calendars.value = runCatching { app.calendars.list() }.getOrElse { emptyList() }
    }

    fun setCalendars(c: CalendarChoice) = viewModelScope.launch(Dispatchers.IO) {
        app.calendars.set(c)
        if (app.running.value) app.hub.agenda.value = runCatching { app.calendars.agenda() }.getOrNull()
    }

    val weatherSettings: StateFlow<WeatherSettings> = app.weather.settings
    val weatherReport: StateFlow<WeatherReport> = app.weather.report

    fun setWeather(s: WeatherSettings) = viewModelScope.launch { app.weather.set(s) }

    fun refreshWeather() = viewModelScope.launch { app.weather.refresh() }

    val clockFace: StateFlow<String> = app.hub.clockFace

    fun setClockFace(face: String) = app.setClockFace(face)

    val weatherStyle: StateFlow<String> = app.hub.weatherStyle

    val hourFormat: StateFlow<String> = app.hub.hourFormat

    fun setHourFormat(format: String) = app.setHourFormat(format)

    val sound: StateFlow<String> = app.hub.sound
    val home: StateFlow<String> = app.hub.home

    fun setHome(screen: String) = app.setHome(screen)

    fun setSound(sound: String) = app.setSound(sound)

    val buzzHz: StateFlow<Int> = app.hub.buzzHz

    fun setBuzzHz(hz: Int) = app.setBuzzHz(hz)

    fun buzzTest(hz: Int) = app.buzzTest(hz)

    val night: StateFlow<String> = app.hub.night

    fun setNight(value: String) = app.setNight(value)

    fun setWeatherStyle(style: String) = app.setWeatherStyle(style)

    val scan = MutableStateFlow<List<ScanItem>>(emptyList())

    fun scanWifi() = viewModelScope.launch {
        val wifi = app.getSystemService(WifiManager::class.java)
        fun read() {
            scan.value = runCatching { wifi.scanResults }.getOrElse { emptyList() }
                .filter { it.frequency < 3000 && it.wifiSsid != null }
                .mapNotNull { r ->
                    val ssid = r.wifiSsid.toString().removeSurrounding("\"").takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID } ?: return@mapNotNull null
                    val secured = listOf("WEP", "WPA", "SAE", "PSK", "EAP").any { it in r.capabilities }
                    ScanItem(ssid, wifi.calculateSignalLevel(r.level) * 3 / (wifi.maxSignalLevel + 1), secured) to r.level
                }
                .sortedByDescending { it.second }
                .map { it.first }
                .distinctBy { it.ssid }
        }
        read()
        @Suppress("DEPRECATION")
        runCatching { wifi.startScan() }
        delay(4000)
        read()
    }

    val places: StateFlow<List<NamedPlace>> = app.places.list
    val locationAccess = MutableStateFlow(LocationAccess(app.locator.precise, app.locator.background))
    val here = MutableStateFlow(Here())

    fun refreshAccess() {
        locationAccess.value = LocationAccess(app.locator.precise, app.locator.background)
    }

    fun locateHere() = viewModelScope.launch {
        here.value = Here(locating = true)
        val l = app.locator.here()
        here.value = if (l == null) Here() else Here(false, l.latitude, l.longitude, l.accuracy)
    }

    fun savePlace(p: NamedPlace) = app.places.save(p)

    fun deletePlace(id: String) = app.places.delete(id)

    fun addWifi(ssid: String, password: String) = app.ble.addWifi(ssid, password)

    fun forgetWifi(ssid: String) = app.ble.forgetWifi(ssid)
}
