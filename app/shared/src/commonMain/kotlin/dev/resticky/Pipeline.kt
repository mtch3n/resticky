package dev.resticky

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

val SESSION_IDLE = 3.minutes

fun interface Transcriber {
    suspend fun transcribe(opus: Path): List<Segment>
}

fun interface Summarizer {
    suspend fun summarize(transcript: String, context: SummaryContext): Summary
}

val AUDIO_KEEP = 7.days

const val SUMMARY_SCHEMA = """{"type":"object","additionalProperties":false,"required":["title","summary","todos"],
"properties":{"title":{"type":"string"},"summary":{"type":"string"},"todos":{"type":"array","items":{"type":"string"}}}}"""

class SummaryContext(val place: String = "", val moments: List<String> = emptyList(), val photos: List<String> = emptyList())

fun summaryPrompt(c: SummaryContext): String = "This is a transcript of a conversation recorded by a desk device. Return JSON with the keys " +
    "title (at most 6 words), summary (at most 3 plain sentences) and todos (an array of short action items; " +
    "name an owner only if the transcript says the name, else no owner; empty array if none). " +
    "Use only facts from the transcript. When lines start with a speaker name and a colon, that is who speaks; " +
    "use those names for the owners of the todos. \"Speaker A\" style names are unknown people." +
    (if (c.place.isEmpty()) "" else " The recording was made at the place that the user calls \"${c.place}\"; use it only as context.") +
    (if (c.moments.isEmpty()) "" else "\n\nThe user marked important moments on the device. Give them weight in the title, the summary and the todos. " +
        "Each line is the time of the mark and the text of the 2 minutes before it:\n" + c.moments.joinToString("\n") { "- $it" }) +
    (if (c.photos.isEmpty()) "" else "\n\nThe user took photos during the conversation. Each line is the photo time and the text in the photo:\n" +
        c.photos.joinToString("\n") { "- $it" }) +
    "\n\nTranscript:\n"

fun parseSummary(raw: String): Summary {
    val text = raw.trim()
    val json = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}').let { if (it < 0) text.length else it + 1 })
    return hubJson.decodeFromString<Summary>(json)
}

fun cleanTranscript(raw: String): String =
    raw.replace("[BLANK_AUDIO]", "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

fun cleanSegments(raw: List<Segment>): List<Segment> =
    raw.mapNotNull { s -> cleanTranscript(s.text).takeIf { it.isNotEmpty() }?.let { s.copy(text = it, words = s.words.filter { w -> w.text != "[BLANK_AUDIO]" }) } }

@Serializable
private class WhisperOffsets(val from: Long, val to: Long)

@Serializable
private class WhisperToken(val text: String, val offsets: WhisperOffsets)

@Serializable
private class WhisperSegment(val offsets: WhisperOffsets, val text: String, val tokens: List<WhisperToken> = emptyList())

@Serializable
private class WhisperOutput(val transcription: List<WhisperSegment>)

fun parseWhisperJson(json: String): List<Segment> =
    hubJson.decodeFromString<WhisperOutput>(json).transcription.map { s ->
        Segment(s.offsets.from, s.offsets.to, s.text, words = wordsFromTokens(s.tokens.map { Token(it.text, it.offsets.from, it.offsets.to) }))
    }

class Pipeline(
    private val store: Store,
    private val transcriber: Transcriber,
    private val summarizer: Summarizer,
    private val diarizer: Diarizer,
    private val diarizeWindowMs: Long = Long.MAX_VALUE,
    private val log: (String) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = Mutex()

    fun kick() {
        wake.trySend(Unit)
    }

    private suspend fun step(): Boolean {
        for (m in store.all()) {
            val seq = m.chunks.entries.firstOrNull { it.value == PENDING }?.key
            if (seq != null) {
                val start = TimeSource.Monotonic.markNow()
                val segments = runCatching { cleanSegments(transcriber.transcribe(Path(store.dir(m.device, m.id), "$seq.opus"))) }
                    .getOrElse {
                        log("WARN transcribe session=${m.id} seq=$seq err=${it.message}")
                        return false
                    }
                val text = segments.joinToString(" ") { it.text }
                log("INFO transcribed session=${m.id} seq=$seq words=${text.split(' ').count { w -> w.isNotEmpty() }} segments=${segments.size} took=${start.elapsedNow()}")
                store.update(m.device, m.id) {
                    it.chunks[seq] = text
                    if (segments.isEmpty()) it.segments.remove(seq) else it.segments[seq] = segments
                }
                return true
            }
            if (m.chunks.isEmpty()) continue
            val now = Clock.System.now()
            val closed = m.final || now - m.lastChunk > SESSION_IDLE
            val locating = m.place == PENDING && now - m.lastChunk < PLACE_WAIT
            if (m.ready || !closed || m.pending || locating) continue
            if (!m.diarized) {
                val frames = if (m.transcript().isBlank()) null else store.sessionFrames(m)
                val start = TimeSource.Monotonic.markNow()
                val turns = frames?.let { f ->
                    runCatching { diarizeSession(f, diarizer, diarizeWindowMs) }
                        .onFailure { log("WARN diarize session=${m.id} err=${it.message}") }
                        .getOrNull()
                }
                store.update(m.device, m.id) {
                    it.diarized = true
                    if (turns != null) it.assignSpeakers(turns)
                }
                if (turns != null) log("INFO diarized session=${m.id} speakers=${turns.map { it.speaker }.distinct().size} turns=${turns.size} took=${start.elapsedNow()}")
                return true
            }
            val text = m.labelledTranscript().trim()
            val sum = if (text.isEmpty()) null else {
                val start = TimeSource.Monotonic.markNow()
                runCatching { summarizer.summarize(text, SummaryContext(placeContext(m.place), m.moments(), m.photoTexts())) }.getOrElse {
                    log("WARN summarize session=${m.id} err=${it.message}")
                    return false
                }.also { log("INFO summarized session=${m.id} title=\"${it.title}\" todos=${it.todos.size} marks=${m.marks.size} took=${start.elapsedNow()}") }
            }
            store.update(m.device, m.id) {
                it.title = sum?.title ?: "No speech"
                it.summary = sum?.summary.orEmpty()
                it.todos = sum?.todos.orEmpty()
                it.engine = sum?.engine.orEmpty()
                it.ready = true
                if (it.place == PENDING) it.place = ""
            }
            return true
        }
        return false
    }

    suspend fun drain() = running.withLock { while (step()) Unit }

    suspend fun run() {
        while (coroutineContext.isActive) {
            drain()
            withTimeoutOrNull(1.minutes) { wake.receive() }
        }
    }
}
