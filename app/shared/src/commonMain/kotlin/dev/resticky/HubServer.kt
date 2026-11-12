package dev.resticky

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
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
import kotlinx.io.readByteArray
import kotlin.time.Clock
import kotlin.time.Instant

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

fun startLabel(c: Conversation): String = (if (c.approximate) "~" else "") + whenLabel(c.start)

fun transcriptItems(sessions: List<Session>, now: Instant = Clock.System.now()): List<TranscriptItem> = conversations(sessions)
    .map { it to it.ready() }
    .filterNot { (c, ready) -> ready && c.recap().title == NO_SPEECH }
    .take(MAX_ITEMS)
    .map { (c, ready) ->
        val r = if (ready) c.recap() else Recap()
        val text = c.transcript().trim()
        val cps = text.codePointList()
        val tail = if (cps.size > TEXT_TAIL) "..." + cps.takeLast(TEXT_TAIL).joinToString("") { it.codePointString() } else text
        val place = c.head.session.place
        TranscriptItem(startLabel(c), r.title, r.summary, r.todos, ready, tail, if (place == PENDING) "" else place, c.marks(), c.photos(), !c.partial(now))
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
    for (c in conversations(sessions)) {
        val r = c.recap()
        r.todos.forEachIndexed { i, text ->
            val item = TodoItem(c.todoId(i), text, "${r.title} · ${startLabel(c)}")
            val at = r.done["$i"]
            if (at == null) open += item else if (at > today) done += item
        }
    }
    return TodoList(open, done)
}

val TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")

expect fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun helloProof(token: String, nonce: String): String = hmacSha256(hexBytes(token), nonce.encodeToByteArray()).hex()

fun sameSecret(given: String, secret: String): Boolean {
    val x = given.encodeToByteArray()
    val y = secret.encodeToByteArray()
    if (y.isEmpty()) return false
    var diff = x.size xor y.size
    for (i in y.indices) diff = diff or (y[i].toInt() xor x.getOrElse(i) { 0 }.toInt())
    return diff == 0
}

fun bearerOk(header: String?, token: String): Boolean = sameSecret(header.orEmpty(), "Bearer $token")

class Hub(
    private val store: Store,
    private val pipeline: Pipeline,
    val weather: Weather,
    val firmware: FirmwareShelf,
    private val log: (String) -> Unit,
    private val newSession: suspend (device: String, session: String) -> Unit = { _, _ -> },
    val token: String? = null,
) {
    val changes: StateFlow<Long> get() = store.changes
    val agenda = MutableStateFlow<Agenda?>(null)
    val clockFace = MutableStateFlow(CLOCK_DIAL)
    val weatherStyle = MutableStateFlow(WEATHER_SKETCH)
    val hourFormat = MutableStateFlow(HOUR_24)
    val soundSetting = DeviceSetting(SOUND_BEEP)
    val buzzSetting = DeviceSetting(BUZZ_HZ)
    val homeSetting = DeviceSetting(HOME_APPS)
    val sound: StateFlow<String> get() = soundSetting.value
    val buzzHz: StateFlow<Int> get() = buzzSetting.value
    val home: StateFlow<String> get() = homeSetting.value
    val nightSetting = DeviceSetting(NIGHT_OFF)
    val night: StateFlow<String> get() = nightSetting.value

    fun setNight(value: String) {
        if (validNight(value)) nightSetting.set(value)
    }

    fun setBuzzHz(hz: Int) {
        if (hz in BUZZ_RANGE) buzzSetting.set(hz)
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
            c.buzzHz?.takeIf { it in BUZZ_RANGE }?.let { if (buzzSetting.report(it)) log("INFO buzz from device device=${c.device} hz=$it") }
            c.sound?.takeIf { it in SOUNDS }?.let { if (soundSetting.report(it)) log("INFO sound from device device=${c.device} sound=$it") }
            c.night?.takeIf(::validNight)?.let { if (nightSetting.report(it)) log("INFO night from device device=${c.device} night=$it") }
            c.home?.takeIf { it in HOME_SCREENS }?.let { if (homeSetting.report(it)) log("INFO home from device device=${c.device} home=$it") }
        }
        val boot = bootKey(c.bootId)
        val anchor = when {
            boot.isEmpty() -> null
            c.bootUnixMs > 0 -> Anchor(c.bootUnixMs)
            c.health != null && c.health.uptimeS > 0 -> Anchor(now.toEpochMilliseconds() - c.health.uptimeS * 1000L, estimated = true)
            else -> null
        }
        if (anchor != null && store.setAnchor(c.device, boot, anchor)) log("INFO clock anchor device=${c.device} boot=$boot unix_ms=${anchor.unixMs} estimated=${anchor.estimated}")
        runCatching { healthLog.add(c.device, HealthSample.of(c, now.epochSeconds)) }.onFailure { log("WARN health store device=${c.device} ${it.message}") }
        var added = 0
        c.marks.filter { ID_PATTERN.matches(it.session) && it.offsetMs >= 0 }.groupBy { it.session }.forEach { (session, marks) ->
            added += store.addMarks(c.device, session, marks.map { it.offsetMs })
        }
        log("INFO checkin link=$link device=${c.device} firmware=${c.firmware} battery_pct=${c.batteryPct} current_ma=${c.currentMa} recording=${c.recording} outbox=${c.outboxChunks} dropped=${c.droppedChunks} wifi_saved=${c.wifiSaved.size} marks=${c.marks.size} new_marks=$added")
    }

    suspend fun receiveChunk(
        device: String, session: String, seq: Int, final: Boolean, startMs: Long, durationMs: Long, data: ByteArray, link: String,
        boot: String = "", bootUnixMs: Long = 0,
    ): Added {
        val now = Clock.System.now()
        val anchor = if (bootUnixMs > 0) bootUnixMs else store.anchor(device, boot)?.unixMs
        val age = chunkAge(anchor, startMs, now)
        val added = store.addChunk(device, session, seq, final, durationMs, data, startMs, age?.let { now - it }, boot, bootUnixMs)
        if (added == Added.CONFLICT) log("WARN chunk conflict link=$link device=$device session=$session seq=$seq bytes=${data.size} kept=first quarantine=${conflictName(seq, data)}")
        else if (added == Added.RESTORED) log("WARN chunk restored link=$link device=$device session=$session seq=$seq bytes=${data.size} reason=file_missing")
        else log("INFO chunk link=$link device=$device session=$session seq=$seq bytes=${data.size} duration_ms=$durationMs final=$final boot=${boot.ifEmpty { "none" }} clock=${if (bootUnixMs > 0) "device" else if (anchor != null) "table" else "unknown"} added=${added.name.lowercase()}")
        if (added == Added.SESSION) {
            if (age == null || age <= PLACE_MAX_AGE) newSession(device, session) else log("INFO session from backlog session=$session age=$age")
        }
        pipeline.kick()
        return added
    }

    suspend fun health(device: String, hours: Int): List<HealthSample> =
        healthLog.read(device, Clock.System.now().epochSeconds - hours.coerceIn(1, HEALTH_MAX_HOURS) * 3600L)

    suspend fun healthDevices(): List<String> = healthLog.devices()

    fun display(): DisplayInfo = DisplayInfo(clockFace.value, weatherStyle.value, hourFormat.value, sound.value, buzzHz.value, home.value, night.value)

    fun agendaNow(): Agenda = agenda.value ?: buildAgenda(emptyList(), false, Clock.System.now(), TimeZone.currentSystemDefault())

    suspend fun devices(): Map<String, Checkin> = devicesLock.withLock { devices.toMap() }

    suspend fun setTodo(device: String, session: String, index: Int, want: TodoState): TodoState? = store.update(device, session) { m ->
        val (from, i) = m.todoRecap(index) ?: return@update null
        var r = m.recap(from)
        if (i !in r.todos.indices) return@update null
        val last = r.todoChanges["$i"]
        if (last == null || want.at >= last) {
            r = r.copy(todoChanges = r.todoChanges + ("$i" to want.at), done = if (want.done) r.done + ("$i" to want.at) else r.done - "$i")
            m.putRecap(from, r)
        }
        TodoState("$i" in r.done, r.todoChanges.getValue("$i"))
    }

    suspend fun lists(device: String): String = store.list(device).let { listsJson(transcriptItems(it), todoList(it)) }

    suspend fun setTodo(device: String, set: TodoSet): TodoState? {
        val (session, index) = todoTarget(set.id) ?: return null
        return setTodo(device, session, index, TodoState(set.done, Clock.System.now()))?.also {
            log("INFO todo set device=$device session=$session index=$index done=${it.done}")
        }
    }

    fun module(app: Application) {
        val token = token
        if (token != null) app.intercept(ApplicationCallPipeline.Plugins) {
            val open = context.request.httpMethod == HttpMethod.Get && context.request.path() == "/api/hello"
            if (!open && !bearerOk(context.request.headers[HttpHeaders.Authorization], token)) {
                context.json("""{"error":"need hub password"}""", HttpStatusCode.Unauthorized)
                finish()
            }
        }
        routes(app)
    }

    private fun routes(app: Application) = app.routing {
        get("/api/hello") {
            val nonce = call.request.queryParameters["nonce"].orEmpty()
            val proof = if (token != null && nonce.isNotEmpty()) mapOf("proof" to helloProof(token, nonce)) else emptyMap()
            call.json(hubJson.encodeToString(mapOf("service" to "resticky-hub", "version" to HUB_VERSION) + proof))
        }
        post("/api/checkin") {
            val c = runCatching { hubJson.decodeFromString<Checkin>(call.receiveText()) }.getOrNull()
            if (c == null || !ID_PATTERN.matches(c.device)) return@post call.fail(HttpStatusCode.BadRequest, "body must be JSON with a hex device field")
            checkin(c, "wifi")
            call.json(hubJson.encodeToString(CheckinReply(HUB_VERSION, utcOffsetMin(), clockFace.value, weatherStyle.value, hourFormat.value, sound.value, buzzHz.value, home.value, night.value)))
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
            val tooLarge = "chunk body over $MAX_CHUNK bytes"
            if ((h[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_CHUNK) return@post call.fail(HttpStatusCode.PayloadTooLarge, tooLarge)
            val data = call.receiveChannel().readRemaining(MAX_CHUNK + 1L).readByteArray()
            if (data.size > MAX_CHUNK) return@post call.fail(HttpStatusCode.PayloadTooLarge, tooLarge)
            if (!opusFramesOk(data)) return@post call.fail(HttpStatusCode.BadRequest, "body must be Opus packets, each with a 2-byte little-endian length")
            val added = receiveChunk(
                device, session, seq, h["X-Final"] == "1", h["X-Start-Ms"]?.toLongOrNull() ?: 0, duration, data, "wifi",
                bootKey(h["X-Boot-Id"]), h["X-Boot-Unix-Ms"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
            )
            call.json(hubJson.encodeToString(ChunkReply(device, session, seq, storedName(added))))
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
