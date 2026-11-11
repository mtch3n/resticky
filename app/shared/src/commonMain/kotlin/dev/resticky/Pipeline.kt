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
"properties":{"title":{"type":"string"},"summary":{"type":"string"},"todos":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["task","quote"],"properties":{"task":{"type":"string"},"quote":{"type":"string"}}}}}}"""

const val NO_SPEECH = "No speech"
const val MIN_SPEECH_UNITS = 25

class SummaryContext(val place: String = "", val moments: List<String> = emptyList(), val photos: List<String> = emptyList())

fun summaryPrompt(c: SummaryContext): String = "This is a transcript of a conversation recorded by a desk device. It can mix English, Mandarin and Cantonese. " +
    "Return JSON with the keys title (at most 6 words, about the main topic), summary (at most 3 plain sentences) and todos. " +
    "Each todo is an object: task (a short action that starts with a verb) and quote (the exact words from the transcript that ask for or promise it). " +
    "Only list a todo when someone in the transcript asks for, agrees to or promises a concrete action; else return an empty array. " +
    "Name an owner in the task only if the transcript says the name of the person; never use \"Speaker A\" style names as owners. " +
    "Use only facts from the transcript, in the language of the transcript. " +
    "If the transcript is not a real conversation (only noise, music, or a few unrelated words), return the title \"$NO_SPEECH\", an empty summary and no todos. " +
    "When lines start with a speaker name and a colon, that is who speaks." +
    (if (c.place.isEmpty()) "" else " The recording was made at the place that the user calls \"${c.place}\"; use it only as context.") +
    (if (c.moments.isEmpty()) "" else "\n\nThe user marked important moments on the device. Give them weight in the title, the summary and the todos. " +
        "Each line is the time of the mark and the text of the 2 minutes before it:\n" + c.moments.joinToString("\n") { "- $it" }) +
    (if (c.photos.isEmpty()) "" else "\n\nThe user took photos during the conversation. Each line is the photo time and the text in the photo:\n" +
        c.photos.joinToString("\n") { "- $it" }) +
    "\n\nTranscript:\n"

@Serializable
private class TodoDraft(val task: String, val quote: String)

@Serializable
private class SummaryDraft(val title: String, val summary: String, val todos: List<TodoDraft> = emptyList())

private fun isCjk(c: Char) = c in '\u3400'..'\u9fff' || c in '\uf900'..'\ufaff'

private fun normalized(text: String): String =
    text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

fun speechUnits(text: String): Int =
    text.count(::isCjk) + text.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() && !isCjk(it) } }

fun quoteFound(quote: String, transcript: String): Boolean {
    val q = normalized(quote)
    if (speechUnits(q) < 2) return false
    val t = normalized(transcript)
    if (q in t) return true
    val words = q.split(' ')
    val have = t.split(' ').toHashSet()
    return words.size >= 4 && words.count { it in have } * 10 >= words.size * 9
}

fun parseSummary(raw: String, transcript: String): Summary {
    val text = raw.trim()
    val json = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}').let { if (it < 0) text.length else it + 1 })
    val d = hubJson.decodeFromString<SummaryDraft>(json)
    if (d.title.trim().equals(NO_SPEECH, ignoreCase = true)) return Summary(NO_SPEECH, "")
    return Summary(d.title.trim(), d.summary.trim(), d.todos.filter { it.task.isNotBlank() && quoteFound(it.quote, transcript) }.map { it.task.trim() }.distinct())
}

private val TAG_OPEN = setOf('[', '(', '*', '<')
private val TAG_CLOSE = mapOf('[' to ']', '(' to ')', '*' to '*', '<' to '>')
private val NOISE_WORDS = setOf(">>", "\u266a", "-", "\u2014")

fun cleanWords(words: List<Word>): List<Word> {
    val kept = mutableListOf<Word>()
    var close: Char? = null
    for (w in words) {
        val t = w.text.trim()
        if (close != null) {
            if (t.endsWith(close)) close = null
            continue
        }
        val open = t.firstOrNull()?.takeIf { it in TAG_OPEN }
        if (open != null) {
            if (!(t.length > 1 && t.endsWith(TAG_CLOSE.getValue(open)))) close = TAG_CLOSE.getValue(open)
            continue
        }
        if (t in NOISE_WORDS || t.none { it.isLetterOrDigit() } || t == "[BLANK_AUDIO]") continue
        kept += w
    }
    return dropRepeats(kept)
}

private const val REPEAT_MAX_N = 8
private const val REPEAT_KEEP = 2

fun dropRepeats(words: List<Word>): List<Word> {
    val key = words.map { normalized(it.text) }
    val out = mutableListOf<Int>()
    var i = 0
    while (i < words.size) {
        var skipped = false
        for (n in REPEAT_MAX_N downTo 1) {
            if (out.size < n * REPEAT_KEEP) continue
            val tail = out.takeLast(n).map { key[it] }
            val before = out.subList(out.size - n * REPEAT_KEEP, out.size - n * (REPEAT_KEEP - 1)).map { key[it] }
            if (i + n <= words.size && tail == before && (0 until n).all { key[i + it] == tail[it] }) {
                i += n
                skipped = true
                break
            }
        }
        if (!skipped) out += i++
    }
    return out.map { words[it] }
}

fun cleanTranscript(raw: String): String =
    cleanWords(raw.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Word(0, 0, it) }).joinToString(" ") { it.text }

fun cleanSegments(raw: List<Segment>): List<Segment> = raw.mapNotNull { s ->
    if (s.words.isEmpty()) cleanTranscript(s.text).takeIf { it.isNotEmpty() }?.let { s.copy(text = it) }
    else cleanWords(s.words).takeIf { it.isNotEmpty() }?.let { w -> s.copy(text = w.joinToString(" ") { it.text }, words = w) }
}

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
            val sum = if (speechUnits(m.transcript()) < MIN_SPEECH_UNITS) null else {
                val start = TimeSource.Monotonic.markNow()
                runCatching { summarizer.summarize(text, SummaryContext(placeContext(m.place), m.moments(), m.photoTexts())) }.getOrElse {
                    log("WARN summarize session=${m.id} err=${it.message}")
                    return false
                }.also { log("INFO summarized session=${m.id} title=\"${it.title}\" todos=${it.todos.size} marks=${m.marks.size} took=${start.elapsedNow()}") }
            }
            store.update(m.device, m.id) {
                it.title = sum?.title ?: NO_SPEECH
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
