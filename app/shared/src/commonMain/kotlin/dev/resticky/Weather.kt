package dev.resticky

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.time.Clock

const val MODE_PHONE = "phone"
const val MODE_CITY = "city"
const val HOURS = 13
const val DAYS = 5

private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

fun condition(wmo: Int): String = when (wmo) {
    0, 1 -> "sun"
    2, 3 -> "cloud"
    45, 48 -> "fog"
    in 71..77, 85, 86 -> "snow"
    in 95..99 -> "storm"
    else -> if (wmo in 51..67 || wmo in 80..82) "rain" else "cloud"
}

fun roundCoordinate(v: Double): Double = (v * 10).roundToInt() / 10.0

@Serializable
data class Place(val name: String, val lat: Double, val lon: Double)

@Serializable
data class WeatherSettings(
    val mode: String = MODE_PHONE,
    val units: String = "c",
    val city: String = "",
    val place: Place? = null,
    @SerialName("interval_min") val intervalMin: Int = WEATHER_INTERVAL,
)

const val WEATHER_INTERVAL = 30
val WEATHER_INTERVALS = listOf(15, WEATHER_INTERVAL, 60, 180)

@Serializable
data class WeatherChange(val mode: String? = null, val units: String? = null)

@Serializable
data class WeatherNow(val temp: Int, val feels: Int, val cond: String, val hi: Int, val lo: Int, val rain: Int)

@Serializable
data class WeatherDay(val wd: String, val cond: String, val lo: Int, val hi: Int)

@Serializable
data class WeatherReport(
    val place: String,
    val mode: String,
    val units: String,
    val at: Long? = null,
    val now: WeatherNow? = null,
    val hour: Int? = null,
    val temps: List<Int>? = null,
    val conds: List<String>? = null,
    val days: List<WeatherDay>? = null,
)

@Serializable
private class OmCurrent(val time: String, @SerialName("temperature_2m") val temp: Double, @SerialName("apparent_temperature") val feels: Double, @SerialName("weather_code") val code: Int)

@Serializable
private class OmHourly(val time: List<String>, @SerialName("temperature_2m") val temp: List<Double?>, @SerialName("weather_code") val code: List<Int?>)

@Serializable
private class OmDaily(
    val time: List<String>,
    @SerialName("weather_code") val code: List<Int?>,
    @SerialName("temperature_2m_max") val max: List<Double?>,
    @SerialName("temperature_2m_min") val min: List<Double?>,
    @SerialName("precipitation_probability_max") val rain: List<Int?>,
)

@Serializable
private class OmForecast(val timezone: String = "", val current: OmCurrent, val hourly: OmHourly, val daily: OmDaily)

@Serializable
private class OmPlace(val name: String, val latitude: Double, val longitude: Double)

@Serializable
private class OmGeocodeRaw(val results: List<OmPlace> = emptyList())

private fun Double?.deg() = (this ?: 0.0).roundToInt()

private val WEEKDAYS = mapOf(
    DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
    DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun",
)

fun zonePlace(timezone: String): String = timezone.substringAfterLast('/').replace('_', ' ')

fun parseForecast(body: String, settings: WeatherSettings, placeName: String?, at: Long): WeatherReport {
    val f = hubJson.decodeFromString<OmForecast>(body)
    val first = f.hourly.time.indexOf(f.current.time.take(13) + ":00").coerceAtLeast(0)
    val hours = first until (first + HOURS).coerceAtMost(f.hourly.time.size)
    val today = f.daily.time.indexOf(f.current.time.take(10)).coerceAtLeast(0)
    return WeatherReport(
        place = placeName ?: zonePlace(f.timezone),
        mode = settings.mode,
        units = settings.units,
        at = at,
        now = WeatherNow(
            temp = f.current.temp.deg(),
            feels = f.current.feels.deg(),
            cond = condition(f.current.code),
            hi = f.daily.max.getOrNull(today).deg(),
            lo = f.daily.min.getOrNull(today).deg(),
            rain = f.daily.rain.getOrNull(today) ?: 0,
        ),
        hour = f.hourly.time[first].substring(11, 13).toInt(),
        temps = hours.map { f.hourly.temp[it].deg() },
        conds = hours.filter { (it - first) % 3 == 0 }.map { condition(f.hourly.code[it] ?: 3) },
        days = (today + 1..(today + DAYS).coerceAtMost(f.daily.time.size - 1)).map {
            WeatherDay(WEEKDAYS.getValue(LocalDate.parse(f.daily.time[it]).dayOfWeek), condition(f.daily.code[it] ?: 3), f.daily.min[it].deg(), f.daily.max[it].deg())
        },
    )
}

fun parseGeocode(body: String): Place? =
    hubJson.decodeFromString<OmGeocodeRaw>(body).results.firstOrNull()?.let { Place(it.name, roundCoordinate(it.latitude), roundCoordinate(it.longitude)) }

@Serializable
private class WeatherFile(val settings: WeatherSettings = WeatherSettings(), val report: WeatherReport? = null)

class Weather(
    private val file: Path,
    private val locate: (suspend () -> Place?)?,
    private val log: (String) -> Unit,
    fixedCity: String? = null,
    fixedInterval: Int? = null,
) {
    private val http = HttpClient(CIO) {
        expectSuccess = true
        engine { requestTimeout = 20_000 }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _settings: MutableStateFlow<WeatherSettings>
    private val _report: MutableStateFlow<WeatherReport>
    val settings: StateFlow<WeatherSettings> get() = _settings
    val report: StateFlow<WeatherReport> get() = _report
    val phoneMode: Boolean get() = locate != null

    init {
        val saved = runCatching {
            hubJson.decodeFromString<WeatherFile>(SystemFileSystem.source(file).buffered().use { it.readString() })
        }.getOrElse { WeatherFile() }
        var s = saved.settings
        if (fixedCity != null && fixedCity != s.city) s = s.copy(city = fixedCity, place = null)
        if (locate == null) s = s.copy(mode = MODE_CITY)
        if (fixedInterval != null) s = s.copy(intervalMin = fixedInterval)
        _settings = MutableStateFlow(s)
        _report = MutableStateFlow(labelled(saved.report, s))
    }

    private fun labelled(r: WeatherReport?, s: WeatherSettings): WeatherReport {
        val same = r != null && r.mode == s.mode && r.units == s.units
        return if (same) r else WeatherReport(place = if (s.mode == MODE_CITY) s.place?.name ?: s.city else "", mode = s.mode, units = s.units)
    }

    private fun save() {
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$file.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(hubJson.encodeToString(WeatherFile(_settings.value, _report.value))) }
        SystemFileSystem.atomicMove(tmp, file)
    }

    suspend fun set(new: WeatherSettings) {
        lock.withLock {
            val old = _settings.value
            val s = new.copy(
                mode = if (locate == null) MODE_CITY else new.mode,
                city = new.city.trim(),
                place = if (new.city.trim() == old.city) old.place else null,
                intervalMin = new.intervalMin.takeIf { it in WEATHER_INTERVALS } ?: WEATHER_INTERVAL,
            )
            _settings.value = s
            _report.value = labelled(_report.value, s)
            save()
        }
        scope.launch { refresh() }
    }

    suspend fun change(c: WeatherChange): Boolean {
        if (c.mode != null && c.mode != MODE_PHONE && c.mode != MODE_CITY) return false
        if (c.units != null && c.units != "c" && c.units != "f") return false
        val s = _settings.value
        set(s.copy(mode = c.mode ?: s.mode, units = c.units ?: s.units))
        return true
    }

    suspend fun run() {
        settings.map { it.intervalMin }.distinctUntilChanged().collectLatest { minutes ->
            while (true) {
                refresh()
                delay(minutes.minutes)
            }
        }
    }

    suspend fun refresh() = lock.withLock {
        val s = _settings.value
        val place = runCatching {
            if (s.mode == MODE_CITY) s.place ?: s.city.takeIf { it.isNotBlank() }?.let { geocode(it) }?.also { _settings.value = s.copy(place = it) }
            else locate?.invoke()?.let { Place("", roundCoordinate(it.lat), roundCoordinate(it.lon)) }
        }.getOrElse {
            log("WARN weather location failed mode=${s.mode} err=${it.message}")
            return@withLock
        } ?: return@withLock log("WARN weather no location mode=${s.mode}")
        runCatching {
            val body = http.get(FORECAST) {
                parameter("latitude", place.lat)
                parameter("longitude", place.lon)
                parameter("current", "temperature_2m,apparent_temperature,weather_code")
                parameter("hourly", "temperature_2m,weather_code")
                parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                parameter("timezone", "auto")
                parameter("forecast_days", 7)
                if (s.units == "f") parameter("temperature_unit", "fahrenheit")
            }.bodyAsText()
            parseForecast(body, s, if (s.mode == MODE_CITY) place.name else null, Clock.System.now().epochSeconds)
        }.onSuccess {
            _report.value = it
            log("INFO weather updated place=\"${it.place}\" temp=${it.now?.temp}${s.units}")
        }.onFailure { log("WARN weather fetch failed err=${it.message}") }
        save()
    }

    private suspend fun geocode(city: String): Place? = parseGeocode(
        http.get(GEOCODE) {
            parameter("name", city)
            parameter("count", 1)
            parameter("language", "en")
            parameter("format", "json")
        }.bodyAsText(),
    )
}
