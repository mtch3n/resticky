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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

const val PIPELINE_VERSION = 2

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
const val SHORT_NOTE = "Short note"
const val MIN_SPEECH_UNITS = 25
const val MIN_NOTE_UNITS = 5
val RETRY_WAIT = 10.seconds

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

internal fun normalized(text: String): String =
    text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

fun speechUnits(text: String): Int =
    text.count(::isCjk) + text.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() && !isCjk(it) } }

private class Tok(val text: String, val start: Int, val end: Int)

private fun toks(text: String): List<Tok> {
    val out = mutableListOf<Tok>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (isCjk(c)) out += Tok("$c", i, i + 1)
        if (!c.isLetterOrDigit() || isCjk(c)) {
            i++
            continue
        }
        var j = i
        while (j < text.length && text[j].isLetterOrDigit() && !isCjk(text[j])) j++
        out += Tok(text.substring(i, j).lowercase(), i, j)
        i = j
    }
    return out
}

fun tokens(text: String): List<String> = toks(text).map { it.text }

fun findQuote(quote: List<String>, text: List<String>): IntRange? {
    if (quote.isEmpty()) return null
    for (i in 0..text.size - quote.size) if (quote.indices.all { text[i + it] == quote[it] }) return i until i + quote.size
    val need = (quote.size * 9 + 9) / 10
    val window = (quote.size * 3 + 1) / 2
    val words = quote.toHashSet()
    for (i in text.indices) {
        if (text[i] !in words) continue
        val end = minOf(text.size, i + window) - 1
        if (inOrder(quote, text, i, end) < need) continue
        val j = (i..end).first { inOrder(quote, text, i, it) >= need }
        var from = i
        while (from < j && inOrder(quote, text, from + 1, j) >= need) from++
        return from..j
    }
    return null
}

private fun inOrder(quote: List<String>, text: List<String>, from: Int, to: Int): Int {
    val lcs = IntArray(quote.size + 1)
    for (j in from..to) {
        var diagonal = 0
        for (k in 1..quote.size) {
            val above = lcs[k]
            lcs[k] = if (quote[k - 1] == text[j]) diagonal + 1 else maxOf(lcs[k], lcs[k - 1])
            diagonal = above
        }
    }
    return lcs[quote.size]
}

fun quoteSpan(quote: String, transcript: String): String? {
    val q = tokens(quote)
    if (q.size < 2) return null
    val t = toks(transcript)
    val r = findQuote(q, t.map { it.text }) ?: return null
    return transcript.substring(t[r.first].start, t[r.last].end)
}

private val NEGATION = Regex("""\b(not|cannot)\b|n't\b""")

fun negated(text: String): Boolean = NEGATION.containsMatchIn(text.lowercase().replace('’', '\''))

fun parseSummary(raw: String, transcript: String): Summary {
    val text = raw.trim()
    val json = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}').let { if (it < 0) text.length else it + 1 })
    val d = hubJson.decodeFromString<SummaryDraft>(json)
    if (d.title.trim().equals(NO_SPEECH, ignoreCase = true)) return Summary(NO_SPEECH, "")
    val todos = d.todos.mapNotNull { t ->
        val task = t.task.trim()
        val span = quoteSpan(t.quote, transcript)
        if (task.isEmpty() || span == null || negated(task) != negated(span)) null else Todo(task, span)
    }
    return Summary(d.title.trim(), d.summary.trim(), todos.distinctBy { it.task })
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
    private val speechId: () -> String = { "" },
    private val log: (String) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = Mutex()
    private val failedOnce = mutableSetOf<String>()
    private val waiting = mutableSetOf<String>()

    fun kick() {
        wake.trySend(Unit)
    }

    private suspend fun step(): Boolean {
        val all = store.all()
        for (m in all) {
            val seq = m.chunks.entries.firstOrNull { it.value == PENDING }?.key ?: continue
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
                it.chunkEngine[seq] = speechId()
                if (segments.isEmpty()) it.segments.remove(seq) else it.segments[seq] = segments
            }
            return true
        }
        val now = Clock.System.now()
        for (m in all) {
            if (m.chunks.isEmpty() || !m.needsSpeakers || !m.closed(now) || "${m.device}/${m.id}" in waiting) continue
            diarize(m)
            return true
        }
        for (c in conversations(all)) {
            val sessions = c.parts.map { it.session }.distinct()
            if (sessions.any { it.pending || it.needsSpeakers || !it.closed(now) || it.place == PENDING && now - it.lastChunk < PLACE_WAIT }) continue
            if (c.ready()) continue
            return summarize(c)
        }
        return false
    }

    private suspend fun diarize(m: Session) {
        val key = "${m.device}/${m.id}"
        val frames = if (m.transcript().isBlank()) null else store.sessionFrames(m)
        val start = TimeSource.Monotonic.markNow()
        val turns = frames?.let { f ->
            runCatching { diarizeSession(f, diarizer, diarizeWindowMs) }
                .onFailure { log("WARN diarize session=${m.id} retry=${key !in failedOnce} err=${it.message}") }
                .getOrNull()
        }
        if (frames != null && turns == null && failedOnce.add(key)) {
            waiting += key
            return
        }
        failedOnce -= key
        store.update(m.device, m.id) {
            it.diarized = true
            it.speakersFailed = frames != null && turns == null
            if (turns != null) it.assignSpeakers(turns)
        }
        if (turns != null) log("INFO diarized session=${m.id} speakers=${turns.map { it.speaker }.distinct().size} turns=${turns.size} took=${start.elapsedNow()}")
    }

    private suspend fun summarize(c: Conversation): Boolean {
        val input = c.input()
        val units = speechUnits(c.transcript())
        val start = TimeSource.Monotonic.markNow()
        val sum = when {
            units < MIN_NOTE_UNITS -> Summary(NO_SPEECH, "")
            units < MIN_SPEECH_UNITS -> Summary(SHORT_NOTE, c.transcript().trim())
            else -> runCatching { summarizer.summarize(c.labelledTranscript().trim(), c.context()) }.getOrElse {
                log("WARN summarize conversation=${c.key} err=${it.message}")
                return false
            }.also { log("INFO summarized conversation=${c.key} parts=${c.parts.size} title=\"${it.title}\" todos=${it.todos.size} took=${start.elapsedNow()}") }
        }
        val todos = sum.todos.take(TODO_SLOTS)
        val quotes = todos.map { c.quote(it.quote) }
        val saved = store.updateDevice(c.device) { sessions ->
            val now = conversations(sessions).firstOrNull { it.key == c.key }
            if (now == null || now.input() != input) return@updateDevice emptyList()
            val members = now.parts.map { it.session }.distinct()
            val ticks = members.flatMap { s -> s.recaps().flatMap { it.second.ticks() } }
            val texts = todos.map { it.task }
            val done = mutableMapOf<String, Time>()
            val changes = mutableMapOf<String, Time>()
            texts.forEachIndexed { i, t ->
                val tick = ticks.filter { it.text == normalized(t) }.maxByOrNull { it.last } ?: return@forEachIndexed
                tick.done?.let { done["$i"] = it }
                tick.changed?.let { changes["$i"] = it }
            }
            val head = now.head.session
            head.putRecap(now.head.fromMs, Recap(sum.title, sum.summary, texts, quotes, sum.engine, done, changes, true, input))
            members.filter { it.place == PENDING }.forEach { it.place = "" }
            members
        }
        if (saved == 0) log("INFO summary dropped conversation=${c.key} reason=input_changed")
        return true
    }

    suspend fun drain() = running.withLock {
        waiting.clear()
        while (step()) Unit
    }

    suspend fun run() {
        while (coroutineContext.isActive) {
            drain()
            withTimeoutOrNull(if (waiting.isEmpty()) 1.minutes else RETRY_WAIT) { wake.receive() }
        }
    }
}

private class Tick(val text: String, val done: Time?, val changed: Time?) {
    val last: Time get() = listOfNotNull(done, changed).max()
}

private fun Recap.ticks(): List<Tick> = todos.mapIndexedNotNull { i, t ->
    val done = done["$i"]
    val changed = todoChanges["$i"]
    if (done == null && changed == null) null else Tick(normalized(t), done, changed)
}
