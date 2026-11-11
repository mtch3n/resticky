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
    val photos: MutableList<Photo> = mutableListOf(),
) {
    val pending: Boolean get() = chunks.values.any { it == PENDING }

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
                segs.isNotEmpty() -> segs.map { Line(at + it.startMs, it.text, it.speaker, it.words) }
                text == PENDING || text.isEmpty() -> emptyList()
                else -> listOf(Line(at, text))
            }
        }
    }

    fun photoTexts(): List<String> = photos.filter { it.text.isNotBlank() }.map { "${timeLabel(it.offsetMs)} ${it.text.replace('\n', ' ')}" }

    fun endMs(): Long = offsets().entries.maxByOrNull { it.value }?.let { (seq, at) -> at + (chunkMs[seq] ?: CHUNK_MS) } ?: 0

    fun speakerName(speaker: Int): String = speakers["$speaker"] ?: speakerLetter(speaker)

    fun labelledTranscript(): String {
        val lines = lines()
        if (lines.none { it.speaker >= 0 }) return transcript()
        val out = StringBuilder()
        var last = -2
        for (l in lines) {
            if (l.speaker != last) {
                if (out.isNotEmpty()) out.append('\n')
                if (l.speaker >= 0) out.append(speakerName(l.speaker)).append(": ")
                last = l.speaker
            } else out.append(' ')
            out.append(l.text)
        }
        return out.toString()
    }

    fun assignSpeakers(turns: List<Turn>) {
        val offsets = offsets()
        var last = -1
        for (seq in offsets.keys.sortedBy { it.toInt() }) {
            val at = offsets.getValue(seq)
            segments[seq] = segments[seq]?.map { seg ->
                val speaker = speakerAt(at + seg.startMs, at + seg.endMs, turns, last)
                last = speaker
                seg.copy(speaker = speaker)
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
        title = title.replace(word, now)
        summary = summary.replace(word, now)
        todos = todos.map { it.replace(word, now) }
    }

    fun addMarks(offsets: Collection<Long>): Int {
        val new = offsets.filter { it >= 0 && it !in marks }.distinct()
        marks += new
        marks.sort()
        return new.size
    }

    fun moments(): List<String> {
        val lines = lines()
        return marks.map { mark ->
            val text = lines.filterIndexed { i, l -> l.atMs <= mark && (lines.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE) > mark - MARK_WINDOW_MS }
                .joinToString(" ") { it.text }
            "${timeLabel(mark)} $text".trim()
        }
    }
}

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
    val beep: Boolean? = null,
    val home: String? = null,
)

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
)

@Serializable
data class TodoItem(val id: String, val text: String, val from: String)

@Serializable
data class TodoList(val open: List<TodoItem>, val done: List<TodoItem>)

@Serializable
data class TodoState(val done: Boolean, val at: Time)

@Serializable
data class Summary(val title: String, val summary: String, val todos: List<String> = emptyList(), val engine: String = "")

@Serializable
data class CheckinReply(
    @SerialName("hub_version") val hubVersion: String,
    @SerialName("utc_offset_min") val utcOffsetMin: Int,
    @SerialName("clock_face") val clockFace: String,
    @SerialName("weather_style") val weatherStyle: String,
    @SerialName("hour_format") val hourFormat: String,
    val beep: Boolean,
    val home: String,
)

@Serializable
data class TimeInfo(@SerialName("utc_offset_min") val utcOffsetMin: Int)

@Serializable
data class DisplayInfo(
    @SerialName("clock_face") val clockFace: String,
    @SerialName("weather_style") val weatherStyle: String,
    @SerialName("hour_format") val hourFormat: String,
    val beep: Boolean,
    val home: String,
)

@Serializable
data class WifiAdd(val ssid: String, val password: String)

@Serializable
data class WifiForget(val ssid: String)

fun utcOffsetMin(now: Instant = kotlin.time.Clock.System.now()): Int =
    TimeZone.currentSystemDefault().offsetAt(now).totalSeconds / 60
