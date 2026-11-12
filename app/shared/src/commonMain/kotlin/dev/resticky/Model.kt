package dev.resticky

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Instant

const val HUB_VERSION = "0.7.0"
const val PENDING = "\u0000pending"
const val CLOCK_DIAL = "dial"
val CLOCK_FACES = listOf(CLOCK_DIAL, "digits", "words", "note", "flip")
const val WEATHER_SKETCH = "sketch"
val WEATHER_STYLES = listOf(WEATHER_SKETCH, "big", "journal", "week")
const val SOUND_BEEP = "beep"
val SOUNDS = listOf("off", SOUND_BEEP, "buzz")
const val BUZZ_HZ = 175
val BUZZ_RANGE = 60..1000
const val NIGHT_OFF = "off"
private val NIGHT = Regex("""^([01]\d|2[0-3]):(00|30)-([01]\d|2[0-3]):(00|30)$""")

class NightWindow(val startMin: Int, val endMin: Int) {
    override fun toString() = "${hhmm(startMin)}-${hhmm(endMin)}"

    companion object {
        fun hhmm(min: Int) = "${(min / 60).toString().padStart(2, '0')}:${(min % 60).toString().padStart(2, '0')}"
    }
}

fun parseNight(value: String): NightWindow? {
    val m = NIGHT.matchEntire(value) ?: return null
    val (sh, sm, eh, em) = m.destructured
    val start = sh.toInt() * 60 + sm.toInt()
    val end = eh.toInt() * 60 + em.toInt()
    return if (start == end) null else NightWindow(start, end)
}

fun validNight(value: String): Boolean = value == NIGHT_OFF || parseNight(value) != null
const val HOME_APPS = "apps"
val HOME_SCREENS = listOf(HOME_APPS, "clock", "weather", "recorder", "todo")
const val HOUR_24 = "24"
val HOUR_FORMATS = listOf(HOUR_24, "12")
val ID_PATTERN = Regex("^[0-9a-f]{1,16}$")
const val CHUNK_MS = 30_000L
const val MARK_WINDOW_MS = 120_000L

val hubJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias Time = @Serializable(InstantSerializer::class) Instant

@Serializable
class Session(
    val device: String,
    val id: String,
    var started: Time,
    @SerialName("last_chunk") var lastChunk: Time,
    @SerialName("duration_ms") var durationMs: Long,
    var final: Boolean = false,
    val chunks: MutableMap<String, String>,
    var title: String = "",
    var summary: String = "",
    var todos: List<String> = emptyList(),
    var engine: String = "",
    val done: MutableMap<String, Time> = mutableMapOf(),
    @SerialName("todo_changes") val todoChanges: MutableMap<String, Time> = mutableMapOf(),
    var ready: Boolean = false,
    var place: String = "",
    @SerialName("place_lat") var placeLat: Double? = null,
    @SerialName("place_lon") var placeLon: Double? = null,
    val segments: MutableMap<String, List<Segment>> = mutableMapOf(),
    @SerialName("chunk_ms") val chunkMs: MutableMap<String, Long> = mutableMapOf(),
    val marks: MutableList<Long> = mutableListOf(),
    var diarized: Boolean = false,
    val speakers: MutableMap<String, String> = mutableMapOf(),
    @SerialName("chunk_engine") val chunkEngine: MutableMap<String, String> = mutableMapOf(),
    val photos: MutableList<Photo> = mutableListOf(),
    @SerialName("todo_quotes") var todoQuotes: List<Quote> = emptyList(),
    var input: String = "",
    val splits: MutableMap<String, Recap> = mutableMapOf(),
    @SerialName("chunk_start") val chunkStart: MutableMap<String, Long> = mutableMapOf(),
    @SerialName("speakers_failed") var speakersFailed: Boolean = false,
    @SerialName("chunk_rules") val chunkRules: MutableMap<String, Int> = mutableMapOf(),
    val voices: MutableMap<String, Voiceprint> = mutableMapOf(),
    val people: MutableMap<String, String> = mutableMapOf(),
    val suggested: MutableMap<String, String> = mutableMapOf(),
    @SerialName("user_named") val userNamed: MutableSet<String> = mutableSetOf(),
    val fixes: MutableList<Fix> = mutableListOf(),
    @SerialName("final_seq") var finalSeq: Int? = null,
    var boot: String = "",
    @SerialName("guess_anchor_ms") var guessAnchorMs: Long? = null,
    @SerialName("time_estimated") var timeEstimated: Boolean = false,
    @SerialName("chunk_retry") val chunkRetry: MutableMap<String, Retry> = mutableMapOf(),
) {
    val pending: Boolean get() = chunks.values.any { it == PENDING }

    val untranscribed: Int get() = chunkRetry.values.count { it.failed }

    private fun seqs(): List<String> = chunks.keys.sortedBy { it.toInt() }

    fun transcript(): String = seqs()
        .map { chunks.getValue(it) }
        .filter { it != PENDING && it.isNotEmpty() }
        .joinToString(" ")

    fun offsets(): Map<String, Long> {
        val last = chunks.keys.maxOfOrNull { it.toInt() } ?: return emptyMap()
        var at = 0L
        val out = mutableMapOf<String, Long>()
        for (seq in 0..last) {
            if ("$seq" in chunks) out["$seq"] = at
            at += chunkMs["$seq"] ?: CHUNK_MS
        }
        return out
    }

    fun lines(): List<Line> {
        val offsets = offsets()
        return seqs().flatMap { seq ->
            val at = offsets.getValue(seq)
            val text = chunks.getValue(seq)
            val segs = segments[seq].orEmpty()
            when {
                segs.isNotEmpty() -> segs.map { Line(at + it.startMs, it.text, it.speaker, it.words.map { w -> w.copy(startMs = at + w.startMs, endMs = at + w.endMs) }) }
                text == PENDING || text.isEmpty() -> emptyList()
                else -> listOf(Line(at, text))
            }
        }
    }

    fun endMs(): Long = offsets().entries.maxByOrNull { it.value }?.let { (seq, at) -> at + (chunkMs[seq] ?: CHUNK_MS) } ?: 0

    fun speakerName(speaker: Int): String = if (speaker < 0) speakerLetter(speaker) else speakers["$speaker"] ?: speakerLetter(speaker)

    val hasSpeakers: Boolean get() = segments.values.any { l -> l.any { it.speaker >= 0 } }

    fun assignSpeakers(turns: List<Turn>) {
        val offsets = offsets()
        var last: Int? = null
        for (seq in offsets.keys.sortedBy { it.toInt() }) {
            val at = offsets.getValue(seq)
            segments[seq] = segments[seq]?.flatMap { seg ->
                if (seg.words.isEmpty()) {
                    val speaker = speakerAt(at + seg.startMs, at + seg.endMs, turns, last ?: -1)
                    last = speaker
                    return@flatMap listOf(seg.copy(speaker = speaker))
                }
                seg.splitBySpeaker(seg.words.map { w -> wordSpeaker(at + w.startMs, at + w.endMs, turns, last).also { last = it } })
            } ?: continue
        }
    }

    fun renameSpeaker(speaker: Int, name: String) {
        val old = speakerName(speaker)
        val new = name.trim()
        if (new.isEmpty() || new == speakerLetter(speaker)) speakers.remove("$speaker") else speakers["$speaker"] = new
        val now = speakerName(speaker)
        if (now == old) return
        val word = Regex("\\b${Regex.escape(old)}\\b")
        recaps().forEach { (from, r) ->
            putRecap(from, r.copy(title = r.title.replace(word, now), summary = r.summary.replace(word, now), todos = r.todos.map { it.replace(word, now) }))
        }
    }

    fun recap(fromMs: Long): Recap =
        if (fromMs == 0L) Recap(title, summary, todos, todoQuotes, engine, done.toMap(), todoChanges.toMap(), ready, input)
        else splits["$fromMs"] ?: Recap()

    fun putRecap(fromMs: Long, r: Recap) {
        if (fromMs != 0L) {
            splits["$fromMs"] = r
            return
        }
        title = r.title
        summary = r.summary
        todos = r.todos
        todoQuotes = r.quotes
        engine = r.engine
        done.clear()
        done.putAll(r.done)
        todoChanges.clear()
        todoChanges.putAll(r.todoChanges)
        ready = r.ready
        input = r.input
    }

    fun recaps(): List<Pair<Long, Recap>> = listOf(0L to recap(0)) + splits.keys.map { it.toLong() to splits.getValue(it) }

    fun addMarks(offsets: Collection<Long>): Int {
        val new = offsets.filter { it >= 0 && it !in marks }.distinct()
        marks += new
        marks.sort()
        return new.size
    }
}

fun Session.clone(): Session = hubJson.decodeFromString(hubJson.encodeToString(this))

@Serializable
data class Retry(val attempts: Int, val error: String, val next: Time, val failed: Boolean = false)

@Serializable
data class Segment(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val text: String,
    val speaker: Int = -1,
    val words: List<Word> = emptyList(),
)

data class Line(val atMs: Long, val text: String, val speaker: Int = -1, val words: List<Word> = emptyList())

@Serializable
data class Mark(val session: String, @SerialName("offset_ms") val offsetMs: Long, val at: Long = 0)

fun timeLabel(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val mm = "${s / 60 % 60}".padStart(2, '0')
    val ss = "${s % 60}".padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:$mm:$ss" else "$mm:$ss"
}

@Serializable
data class Checkin(
    val device: String = "",
    val firmware: String = "",
    @SerialName("battery_pct") val batteryPct: Int = 0,
    @SerialName("voltage_mv") val voltageMv: Int = 0,
    @SerialName("current_ma") val currentMa: Int = 0,
    val recording: Boolean = false,
    @SerialName("outbox_chunks") val outboxChunks: Long = 0,
    @SerialName("outbox_bytes") val outboxBytes: Long = 0,
    @SerialName("dropped_chunks") val droppedChunks: Long = 0,
    @SerialName("wifi_saved") val wifiSaved: List<String> = emptyList(),
    @SerialName("wifi_ssid") val wifiSsid: String = "",
    @SerialName("wifi_rssi") val wifiRssi: Int = 0,
    val health: Health? = null,
    val seen: Time? = null,
    val link: String = "",
    val marks: List<Mark> = emptyList(),
    val sound: String? = null,
    @SerialName("buzz_hz") val buzzHz: Int? = null,
    val home: String? = null,
    val night: String? = null,
    @SerialName("boot_id") val bootId: String = "",
    @SerialName("boot_unix_ms") val bootUnixMs: Long = 0,
)

@Serializable
data class Anchor(@SerialName("unix_ms") val unixMs: Long, val estimated: Boolean = false)

fun bootKey(hex: String?): String = hex?.takeIf { ID_PATTERN.matches(it) && it.length <= 8 }?.toLong(16)?.takeIf { it != 0L }?.toString(16)?.padStart(8, '0').orEmpty()

@Serializable
data class ChunkReply(val device: String, val session: String, val seq: Int, val stored: String)

fun storedName(a: Added): String = when (a) {
    Added.SESSION, Added.CHUNK -> "added"
    else -> a.name.lowercase()
}

@Serializable
data class TranscriptItem(
    val `when`: String,
    val title: String,
    val summary: String,
    val todos: List<String>,
    val ready: Boolean,
    val text: String,
    val place: String,
    val marks: Int,
    val photos: Int,
    val complete: Boolean = true,
)

@Serializable
data class TodoItem(val id: String, val text: String, val from: String)

@Serializable
data class TodoList(val open: List<TodoItem>, val done: List<TodoItem>)

@Serializable
data class TodoState(val done: Boolean, val at: Time)

@Serializable
data class Todo(val task: String, val quote: String = "")

@Serializable
data class SpeakerName(val speaker: String, val name: String, val evidence: String = "")

@Serializable
data class Correction(val from: String, val to: String, val evidence: String = "")

@Serializable
data class Fix(val from: String, val to: String, @SerialName("at_ms") val atMs: Long, val auto: Boolean = true, val undone: Boolean = false)

@Serializable
data class Summary(
    val title: String,
    val summary: String,
    val todos: List<Todo> = emptyList(),
    val engine: String = "",
    val speakers: List<SpeakerName> = emptyList(),
    val corrections: List<Correction> = emptyList(),
)

@Serializable
data class Quote(val text: String, val session: String = "", @SerialName("at_ms") val atMs: Long = -1)

@Serializable
data class Recap(
    val title: String = "",
    val summary: String = "",
    val todos: List<String> = emptyList(),
    @SerialName("todo_quotes") val quotes: List<Quote> = emptyList(),
    val engine: String = "",
    val done: Map<String, Time> = emptyMap(),
    @SerialName("todo_changes") val todoChanges: Map<String, Time> = emptyMap(),
    val ready: Boolean = false,
    val input: String = "",
)

@Serializable
data class CheckinReply(
    @SerialName("hub_version") val hubVersion: String,
    @SerialName("utc_offset_min") val utcOffsetMin: Int,
    @SerialName("clock_face") val clockFace: String,
    @SerialName("weather_style") val weatherStyle: String,
    @SerialName("hour_format") val hourFormat: String,
    val sound: String,
    @SerialName("buzz_hz") val buzzHz: Int,
    val home: String,
    val night: String,
)

@Serializable
data class TimeInfo(@SerialName("utc_offset_min") val utcOffsetMin: Int)

@Serializable
data class DisplayInfo(
    @SerialName("clock_face") val clockFace: String,
    @SerialName("weather_style") val weatherStyle: String,
    @SerialName("hour_format") val hourFormat: String,
    val sound: String,
    @SerialName("buzz_hz") val buzzHz: Int,
    val home: String,
    val night: String,
)

@Serializable
data class WifiAdd(val ssid: String, val password: String)

@Serializable
data class WifiForget(val ssid: String)

fun utcOffsetMin(now: Instant = kotlin.time.Clock.System.now()): Int =
    TimeZone.currentSystemDefault().offsetAt(now).totalSeconds / 60
