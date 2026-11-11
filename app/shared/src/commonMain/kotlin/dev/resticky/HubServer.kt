package dev.resticky

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Instant

private const val MAX_CHUNK = 1 shl 20
private const val MAX_ITEMS = 18
private const val TEXT_TAIL = 180

private val clock = LocalDateTime.Format { hour(); char(':'); minute() }
private val dated = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(kotlinx.datetime.format.Padding.NONE); char(' '); hour(); char(':'); minute()
}

fun whenLabel(t: Instant): String {
    val tz = TimeZone.currentSystemDefault()
    val local = t.toLocalDateTime(tz)
    return if (local.date == Clock.System.now().toLocalDateTime(tz).date) local.format(clock) else local.format(dated)
}

fun transcriptItems(sessions: List<Session>): List<TranscriptItem> = sessions
    .filterNot { it.chunks.isEmpty() || it.ready && it.title == "No speech" }
    .take(MAX_ITEMS)
    .map {
        val text = it.transcript().trim()
        val cps = text.codePointList()
        val tail = if (cps.size > TEXT_TAIL) "..." + cps.takeLast(TEXT_TAIL).joinToString("") { c -> c.codePointString() } else text
        TranscriptItem(whenLabel(it.started), it.title, it.summary, it.todos, it.ready, tail, if (it.place == PENDING) "" else it.place, it.marks.size, it.photos.size)
    }

internal fun String.codePointList(): List<Int> {
    val out = mutableListOf<Int>()
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out += ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out
}

internal fun Int.codePointString(): String = if (this < 0x10000) Char(this).toString() else {
    val v = this - 0x10000
    charArrayOf(Char(0xD800 + (v shr 10)), Char(0xDC00 + (v and 0x3ff))).concatToString()
}

fun todoList(sessions: List<Session>): TodoList {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.atStartOfDayIn(TimeZone.currentSystemDefault())
    val open = mutableListOf<TodoItem>()
    val done = mutableListOf<TodoItem>()
    for (m in sessions) m.todos.forEachIndexed { i, text ->
        val item = TodoItem("${m.id}-$i", text, "${m.title} · ${whenLabel(m.started)}")
        val at = m.done["$i"]
        if (at == null) open += item else if (at > today) done += item
    }
    return TodoList(open, done)
}

class Hub(
    private val store: Store,
    private val pipeline: Pipeline,
    val weather: Weather,
    val firmware: FirmwareShelf,
    private val log: (String) -> Unit,
    private val newSession: suspend (device: String, session: String) -> Unit = { _, _ -> },
) {
    val agenda = MutableStateFlow<Agenda?>(null)
    val clockFace = MutableStateFlow(CLOCK_DIAL)
    val weatherStyle = MutableStateFlow(WEATHER_SKETCH)
    val hourFormat = MutableStateFlow(HOUR_24)
    val soundSetting = DeviceSetting(SOUND_BEEP)
    val homeSetting = DeviceSetting(HOME_APPS)
    val sound: StateFlow<String> get() = soundSetting.value
    val home: StateFlow<String> get() = homeSetting.value
    val nightSetting = DeviceSetting(NIGHT_OFF)
    val night: StateFlow<String> get() = nightSetting.value

    fun setNight(value: String) {
        if (validNight(value)) nightSetting.set(value)
    }

    fun setSound(sound: String) {
        if (sound in SOUNDS) soundSetting.set(sound)
    }

    fun setHome(screen: String) {
        if (screen in HOME_SCREENS) homeSetting.set(screen)
    }

    private val devices = mutableMapOf<String, Checkin>()
    private val devicesLock = Mutex()
    private val healthLog = HealthLog(store.root)

    private suspend fun ApplicationCall.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respondText(text, ContentType.Application.Json, status)

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) = respondText(message, status = status)

    private fun ApplicationCall.device(): String? = request.queryParameters["device"]?.takeIf { ID_PATTERN.matches(it) }

    suspend fun checkin(c: Checkin, link: String) {
        val now = Clock.System.now()
        devicesLock.withLock {
            devices[c.device] = c.copy(seen = now, link = link, marks = emptyList())
            c.sound?.takeIf { it in SOUNDS }?.let { if (soundSetting.report(it)) log("INFO sound from device device=${c.device} sound=$it") }
            c.night?.takeIf(::validNight)?.let { if (nightSetting.report(it)) log("INFO night from device device=${c.device} night=$it") }
            c.home?.takeIf { it in HOME_SCREENS }?.let { if (homeSetting.report(it)) log("INFO home from device device=${c.device} home=$it") }
        }
        runCatching { healthLog.add(c.device, HealthSample.of(c, now.epochSeconds)) }.onFailure { log("WARN health store device=${c.device} ${it.message}") }
        var added = 0
        c.marks.filter { ID_PATTERN.matches(it.session) && it.offsetMs >= 0 }.groupBy { it.session }.forEach { (session, marks) ->
            added += store.addMarks(c.device, session, marks.map { it.offsetMs })
        }
        log("INFO checkin link=$link device=${c.device} firmware=${c.firmware} battery_pct=${c.batteryPct} current_ma=${c.currentMa} recording=${c.recording} outbox=${c.outboxChunks} dropped=${c.droppedChunks} wifi_saved=${c.wifiSaved.size} marks=${c.marks.size} new_marks=$added")
    }

    suspend fun receiveChunk(device: String, session: String, seq: Int, final: Boolean, startMs: Long, durationMs: Long, data: ByteArray, link: String) {
        val created = store.addChunk(device, session, seq, final, durationMs, data)
        log("INFO chunk link=$link device=$device session=$session seq=$seq bytes=${data.size} duration_ms=$durationMs final=$final")
        if (created) {
            val age = chunkAge(devicesLock.withLock { devices[device] }, startMs, Clock.System.now())
            if (age == null || age <= PLACE_MAX_AGE) newSession(device, session) else log("INFO session from backlog session=$session age=$age")
        }
        pipeline.kick()
    }

    suspend fun health(device: String, hours: Int): List<HealthSample> =
        healthLog.read(device, Clock.System.now().epochSeconds - hours.coerceIn(1, HEALTH_MAX_HOURS) * 3600L)

    suspend fun healthDevices(): List<String> = healthLog.devices()

    fun display(): DisplayInfo = DisplayInfo(clockFace.value, weatherStyle.value, hourFormat.value, sound.value, home.value, night.value)

    fun agendaNow(): Agenda = agenda.value ?: buildAgenda(emptyList(), false, Clock.System.now(), TimeZone.currentSystemDefault())

    suspend fun devices(): Map<String, Checkin> = devicesLock.withLock { devices.toMap() }

    suspend fun setTodo(device: String, session: String, index: Int, want: TodoState): TodoState? = store.update(device, session) { m ->
        if (index !in m.todos.indices) return@update null
        val last = m.todoChanges["$index"]
        if (last == null || want.at >= last) {
            m.todoChanges["$index"] = want.at
            if (want.done) m.done["$index"] = want.at else m.done.remove("$index")
        }
        TodoState("$index" in m.done, m.todoChanges.getValue("$index"))
    }

    suspend fun lists(device: String): String = store.list(device).let { listsJson(transcriptItems(it), todoList(it)) }

    suspend fun setTodo(device: String, set: TodoSet): TodoState? {
        val (session, index) = todoTarget(set.id) ?: return null
        return setTodo(device, session, index, TodoState(set.done, Clock.System.now()))?.also {
            log("INFO todo set device=$device session=$session index=$index done=${it.done}")
        }
    }

    fun module(app: Application) = app.routing {
        get("/api/hello") {
            call.json(hubJson.encodeToString(mapOf("service" to "resticky-hub", "version" to HUB_VERSION)))
        }
        post("/api/checkin") {
            val c = runCatching { hubJson.decodeFromString<Checkin>(call.receiveText()) }.getOrNull()
            if (c == null || !ID_PATTERN.matches(c.device)) return@post call.fail(HttpStatusCode.BadRequest, "body must be JSON with a hex device field")
            checkin(c, "wifi")
            call.json(hubJson.encodeToString(CheckinReply(HUB_VERSION, utcOffsetMin(), clockFace.value, weatherStyle.value, hourFormat.value, sound.value, home.value, night.value)))
        }
        post("/api/chunks") {
            val h = call.request.headers
            val device = h["X-Device"].orEmpty()
            val session = h["X-Session"].orEmpty()
            val seq = h["X-Seq"]?.toIntOrNull()
            val duration = h["X-Duration-Ms"]?.toLongOrNull()
            if (!ID_PATTERN.matches(device) || !ID_PATTERN.matches(session) || seq == null || seq < 0 || duration == null) {
                return@post call.fail(HttpStatusCode.BadRequest, "need X-Device, X-Session (hex), X-Seq and X-Duration-Ms headers")
            }
            val data = call.receive<ByteArray>().let { if (it.size > MAX_CHUNK) it.copyOf(MAX_CHUNK) else it }
            receiveChunk(device, session, seq, h["X-Final"] == "1", h["X-Start-Ms"]?.toLongOrNull() ?: 0, duration, data, "wifi")
            call.respondText("")
        }
        get("/api/transcripts") {
            val device = call.device() ?: return@get call.fail(HttpStatusCode.BadRequest, "need device")
            call.json(hubJson.encodeToString(transcriptItems(store.list(device))))
        }
        get("/api/todos") {
            val device = call.device() ?: return@get call.fail(HttpStatusCode.BadRequest, "need device")
            call.json(hubJson.encodeToString(todoList(store.list(device))))
        }
        put("/api/todos/{device}/{session}/{index}") {
            val p = call.parameters
            val device = p["device"].orEmpty()
            val session = p["session"].orEmpty()
            val index = p["index"]?.toIntOrNull()
            val want = runCatching { hubJson.decodeFromString<TodoState>(call.receiveText()) }.getOrNull()
            if (!ID_PATTERN.matches(device) || !ID_PATTERN.matches(session) || index == null || want == null) {
                return@put call.fail(HttpStatusCode.BadRequest, "need /api/todos/{device}/{session}/{index} and {\"done\":bool,\"at\":time}")
            }
            val state = setTodo(device, session, index, want) ?: return@put call.fail(HttpStatusCode.NotFound, "no such to-do")
            log("INFO todo set device=$device session=$session index=$index done=${state.done}")
            call.json(hubJson.encodeToString(state))
        }
        post("/api/screen") {
            val device = call.request.headers["X-Device"].orEmpty()
            val frame = call.receive<ByteArray>()
            if (!ID_PATTERN.matches(device) || frame.size != SCREEN_SIZE) {
                return@post call.fail(HttpStatusCode.BadRequest, "need X-Device and a 48000-byte panel frame")
            }
            store.write(Path(store.root, device, "screen.png"), screenPng(frame))
            call.respondText("")
        }
        get("/api/screen") {
            val device = call.device() ?: return@get call.fail(HttpStatusCode.BadRequest, "need device")
            val png = store.read(Path(store.root, device, "screen.png")) ?: return@get call.fail(HttpStatusCode.NotFound, "no screen")
            call.respondBytes(png, ContentType.Image.PNG)
        }
        get("/api/weather") {
            call.device() ?: return@get call.fail(HttpStatusCode.BadRequest, "need device")
            call.json(hubJson.encodeToString(weather.report.value))
        }
        put("/api/weather") {
            val device = call.device()
            val change = runCatching { hubJson.decodeFromString<WeatherChange>(call.receiveText()) }.getOrNull()
            if (device == null || change == null || !weather.change(change)) {
                return@put call.fail(HttpStatusCode.BadRequest, "need device and {\"mode\":\"phone|city\",\"units\":\"c|f\"}")
            }
            log("INFO weather set device=$device mode=${weather.settings.value.mode} units=${weather.settings.value.units}")
            call.json(hubJson.encodeToString(weather.report.value))
        }
        get("/api/agenda") {
            call.device() ?: return@get call.fail(HttpStatusCode.BadRequest, "need device")
            call.json(hubJson.encodeToString(agendaNow()))
        }
        get("/api/health") {
            val device = call.device()
            val hours = call.request.queryParameters["hours"]?.let { it.toIntOrNull() ?: 0 } ?: 24
            if (device == null || hours !in 1..HEALTH_MAX_HOURS) return@get call.fail(HttpStatusCode.BadRequest, "need device and hours 1 to $HEALTH_MAX_HOURS")
            call.json(healthJson.encodeToString(health(device, hours)))
        }
        get("/api/firmware") {
            val (version, _) = firmware.newest() ?: return@get call.fail(HttpStatusCode.NotFound, "no firmware")
            call.json("""{"version":"$version","size":${firmware.read(version)?.size ?: 0}}""")
        }
        get("/api/firmware/bin") {
            val version = call.request.queryParameters["version"].orEmpty()
            val data = firmware.read(version) ?: return@get call.fail(HttpStatusCode.NotFound, "no such firmware")
            call.respondBytes(data, ContentType.Application.OctetStream)
        }
        get("/api/devices") {
            call.json(hubJson.encodeToString(devices()))
        }
    }
}

class DeviceSetting<T>(initial: T) {
    private val _value = MutableStateFlow(initial)
    val value: StateFlow<T> get() = _value
    private var sent = false

    fun load(v: T) {
        _value.value = v
    }

    fun set(v: T) {
        sent = v != _value.value || sent
        _value.value = v
    }

    fun report(reported: T): Boolean {
        if (sent) {
            sent = reported != _value.value
            return false
        }
        if (reported == _value.value) return false
        _value.value = reported
        return true
    }
}
