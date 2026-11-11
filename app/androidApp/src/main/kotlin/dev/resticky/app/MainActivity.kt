package dev.resticky.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.launch
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.resticky.MODE_PHONE
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    private val app get() = application as HubApp
    private val model: HubViewModel by viewModels()
    private val associate = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.light(AndroidColor.WHITE, AndroidColor.WHITE), SystemBarStyle.light(AndroidColor.WHITE, AndroidColor.WHITE))
        super.onCreate(savedInstanceState)
        val wanted = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 0)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Ink.white, surface = Ink.white)) { App(model) { model.pair { associate.launch(IntentSenderRequest.Builder(it).build()) } } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (app.running.value) app.ble.start()
    }

    override fun onStart() {
        super.onStart()
        app.onScreen = true
        if (app.weather.settings.value.mode == MODE_PHONE) app.scope.launch { app.locator.update() }
        app.pipeline.kick()
    }

    override fun onStop() {
        app.onScreen = false
        super.onStop()
    }
}

sealed interface Detail {
    data class Transcript(val device: String, val id: String, val startAt: Long? = null) : Detail
    data object Models : Detail
    data object Sticky : Detail
    data object Calendars : Detail
    data object Weather : Detail
    data object Wifi : Detail
    data object Health : Detail
    data object Places : Detail
    data class PlaceEdit(val place: dev.resticky.NamedPlace?) : Detail
    data object Clock : Detail
    data object Settings : Detail
    data object Updates : Detail
    data object Pipeline : Detail
    data class WifiAdd(val network: ScanItem?) : Detail
}

@Composable
private fun App(model: HubViewModel, pair: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Hub) }
    var choosing by remember { mutableStateOf(false) }
    val stack = remember { mutableStateListOf<Detail>() }
    BackHandler(stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val back = { stack.removeAt(stack.lastIndex); Unit }
    val runs by model.runs.collectAsState()
    val ble by model.ble.collectAsState()
    val hub by model.hub.collectAsState()
    val sessions by model.sessions.collectAsState()
    val speech by model.speech.collectAsState()
    val open by model.summaryOpen.collectAsState()
    val closed by model.summaryClosed.collectAsState()
    val calendarChoice by model.calendarChoice.collectAsState()
    val weatherSettings by model.weatherSettings.collectAsState()
    val weatherReport by model.weatherReport.collectAsState()
    val context = LocalContext.current
    val unlinked by model.unlinked.collectAsState()
    val release by model.release.collectAsState()
    val releaseChecked by model.releaseChecked.collectAsState()
    val updateStatus by model.updateStatus.collectAsState()
    val firmwareReady by model.firmwareReady.collectAsState()
    val installAsked by model.install.collectAsState()
    val offer = FirmwareOffer(hub.sticky?.firmware?.takeIf { it.isNotEmpty() }, release?.firmware?.first, firmwareReady, hub.sticky?.wifiSsid?.isNotEmpty() == true, installAsked)
    val updatesLabel = release?.takeIf { dev.resticky.isNewer(it.version, model.appVersion) }?.let { "Version ${it.version} is ready" } ?: "resticky ${model.appVersion}"
    var snapPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        snapPath?.let { path -> if (ok) model.savePhoto(java.io.File(path)) else java.io.File(path).delete() }
        snapPath = null
    }
    val snap = {
        val (file, uri) = model.newSnap()
        snapPath = file.path
        camera.launch(uri)
    }
    val calendarList by model.calendars.collectAsState()
    val calendarsAllowed by model.calendarsAllowed.collectAsState()
    val placeList by model.places.collectAsState()
    val face by model.clockFace.collectAsState()
    val hourFormat by model.hourFormat.collectAsState()
    val weatherStyle by model.weatherStyle.collectAsState()
    val beep by model.beep.collectAsState()
    val chosen = calendarList.count { it.id in calendarChoice.selected }
    val calendarsLabel = if (calendarsAllowed && calendarChoice.show) "$chosen calendar${if (chosen == 1) "" else "s"}" else "Off"
    val placesLabel = placeList.joinToString(", ") { it.name }.ifEmpty { "None yet" }
    val weatherNow = weatherReport.now
    val weatherLabel = if (weatherNow != null) "${weatherReport.place.ifEmpty { "Here" }} · ${weatherNow.temp}°" else if (weatherSettings.mode == MODE_PHONE) "From phone" else "Fixed city"
    Column(Modifier.fillMaxSize().background(Ink.white).statusBarsPadding()) {
        Box(Modifier.weight(1f).then(if (stack.isEmpty()) Modifier else Modifier.navigationBarsPadding())) {
            when (val d = stack.lastOrNull()) {
                is Detail.Transcript -> {
                    val view = sessions.find { it.device == d.device && it.id == d.id }
                    val prefs by model.player.collectAsState()
                    TranscriptScreen(view, back, model::sessionAudio, model::denoiserModel, prefs, model::setPlayer, { speaker, name -> model.renameSpeaker(d.device, d.id, speaker, name) }, { p -> model.photoFile(view!!.session, p) }, model::showOnSticky, d.startAt, { stack[stack.lastIndex] = d.copy(startAt = null) }) { i, done ->
                        model.setTodo(d.device, d.id, i, done)
                    }
                }
                Detail.Models -> {
                    val models by model.models.collectAsState()
                    ModelsScreen(models, speech, open, closed, back, model::selectSpeech, model::selectSummary, model::download) { model.downloadNano() }
                }
                Detail.Sticky -> StickyScreen(
                    stickyName(hub, ble), ble, hub.sticky,
                    StickyDisplay(
                        "${optionName(CLOCK_FACE, face)} · $hourFormat h",
                        "${optionName(WEATHER_STYLE, weatherStyle)} · ${weatherReport.place.ifEmpty { if (weatherSettings.mode == MODE_PHONE) "From phone" else "Fixed city" }}",
                        beep, wifiSummary(hub.sticky), healthStatus(hub.sticky), offer,
                    ),
                    back, model::enterPasskey, pair, model::forget, model::setBeep, model::updateSticky,
                ) { stack.add(it) }
                Detail.Pipeline -> PipelineScreen(hub, runs, back)
                Detail.Health -> {
                    val health by model.health.collectAsState()
                    val hours by model.healthHours.collectAsState()
                    HealthScreen(health?.takeIf { it.hours == hours }, hours, back) { model.healthHours.value = it }
                }
                Detail.Settings -> {
                    val transcribe by model.transcribe.collectAsState()
                    SettingsScreen(hub, transcribe, speech, placesLabel, calendarsLabel, updatesLabel, back, model::setHub, model::selectTranscribe) { stack.add(it) }
                }
                Detail.Clock -> {
                    ClockScreen(face, hourFormat, back, model::setClockFace, model::setHourFormat)
                }
                Detail.Calendars -> {
                    val allowed by model.calendarsAllowed.collectAsState()
                    val calendars by model.calendars.collectAsState()
                    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.loadCalendars() }
                    LaunchedEffect(Unit) {
                        model.loadCalendars()
                        if (!model.calendarsAllowed.value) permission.launch(Manifest.permission.READ_CALENDAR)
                    }
                    CalendarsScreen(allowed, calendars, calendarChoice, back, { permission.launch(Manifest.permission.READ_CALENDAR) }, { model.setCalendars(it) }) {
                        context.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                    }
                }
                Detail.Places -> {
                    val places by model.places.collectAsState()
                    val access by model.locationAccess.collectAsState()
                    val precise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refreshAccess() }
                    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshAccess() }
                    LaunchedEffect(Unit) { model.refreshAccess() }
                    PlacesScreen(
                        places, access, back,
                        { precise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                    ) { stack.add(Detail.PlaceEdit(it)) }
                }
                is Detail.PlaceEdit -> {
                    val here by model.here.collectAsState()
                    LaunchedEffect(d) { if (d.place == null) model.locateHere() }
                    PlaceEditScreen(d.place, here, back, { model.locateHere() }, model::savePlace, model::deletePlace)
                }
                Detail.Weather -> {
                    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.refreshWeather() }
                    LaunchedEffect(Unit) {
                        if (weatherSettings.mode == MODE_PHONE && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        } else model.refreshWeather()
                    }
                    WeatherScreen(weatherSettings, weatherReport, weatherStyle, back, { model.refreshWeather() }, model::setWeatherStyle) {
                        if (it.mode == MODE_PHONE && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        model.setWeather(it)
                    }
                }
                Detail.Wifi -> {
                    val scan by model.scan.collectAsState()
                    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.scanWifi() }
                    LaunchedEffect(Unit) {
                        if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) model.scanWifi()
                        else permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                    WifiScreen(hub.sticky, scan, back, { model.forgetWifi(it) }) { stack.add(Detail.WifiAdd(it)) }
                }
                is Detail.WifiAdd -> WifiAddScreen(d.network, back, model::addWifi)
                Detail.Updates -> UpdatesScreen(
                    model.appVersion, release, releaseChecked, updateStatus, stickyName(hub, ble), offer, back,
                    { model.checkUpdates() }, { model.installApp() }, model::updateSticky,
                )
                null -> when (tab) {
                    Tab.Hub -> {
                        LaunchedEffect(Unit) { model.loadCalendars() }
                        val tiles = listOf(
                            HubTile(R.drawable.ic_device_mobile, "Sticky", "Wi-Fi, clock, sounds") { stack.add(Detail.Sticky) },
                            HubTile(R.drawable.ic_cloud, "Weather", weatherLabel) { stack.add(Detail.Weather) },
                            HubTile(R.drawable.ic_map_pin, "Places", placesLabel) { stack.add(Detail.Places) },
                            HubTile(R.drawable.ic_calendar, "Calendars", calendarsLabel) { stack.add(Detail.Calendars) },
                            HubTile(R.drawable.ic_heart_rate_monitor, "Health", healthStatus(hub.sticky)) { stack.add(Detail.Health) },
                            HubTile(R.drawable.ic_settings, "App settings", "Models, transcribe, hubs") { stack.add(Detail.Settings) },
                        )
                        HubScreen(hub, ble, tiles, { stack.add(Detail.Sticky) }) { choosing = true }
                        if (choosing) StickySheet(hub, ble, { choosing = false }) {
                            choosing = false
                            stack.add(Detail.Sticky)
                            pair()
                        }
                    }
                    Tab.Transcripts -> TranscriptsScreen(sessions) { stack.add(Detail.Transcript(it.device, it.id)) }
                    Tab.Photos -> PhotosScreen(unlinked, sessions, model::photoFile, { s, p -> stack.add(Detail.Transcript(s.device, s.id, p.offsetMs)) }, model::showOnSticky, model::attach)
                    Tab.Todo -> {
                        val todos by model.todos.collectAsState()
                        TodoScreen(todos) { device, t, done ->
                            model.setTodo(device, t.id.substringBeforeLast('-'), t.id.substringAfterLast('-').toInt(), done)
                        }
                    }
                }
            }
        }
        if (stack.isEmpty()) BottomNav(tab, { tab = it }) { snap() }
    }
}
