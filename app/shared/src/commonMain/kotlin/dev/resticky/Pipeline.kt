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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

const val PIPELINE_VERSION = 2

val SESSION_IDLE = 3.minutes

fun interface Transcriber {
    suspend fun transcribe(opus: Path, prompt: String): List<Segment>
}

fun interface Summarizer {
    suspend fun summarize(transcript: String, context: SummaryContext): Summary
}

val AUDIO_KEEP = 7.days

const val SUMMARY_SCHEMA = """{"type":"object","additionalProperties":false,"required":["title","summary","todos","speakers","corrections"],
"properties":{"title":{"type":"string"},"summary":{"type":"string"},"todos":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["task","quote"],"properties":{"task":{"type":"string"},"quote":{"type":"string"}}}},
"speakers":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["speaker","name","evidence"],"properties":{"speaker":{"type":"string"},"name":{"type":"string"},"evidence":{"type":"string"}}}},
"corrections":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["from","to","evidence"],"properties":{"from":{"type":"string"},"to":{"type":"string"},"evidence":{"type":"string"}}}}}}"""

const val NO_SPEECH = "No speech"
const val SHORT_NOTE = "Short note"
const val MIN_SPEECH_UNITS = 25
const val MIN_NOTE_UNITS = 5
val RETRY_WAIT = 10.seconds
const val TRANSCRIBE_ATTEMPTS = 8
const val SUMMARY_ATTEMPTS = 4
val RETRY_FIRST = 30.seconds
val RETRY_MAX = 1.hours

fun retryWait(attempts: Int): Duration =
    (RETRY_FIRST * (1L shl (attempts - 1).coerceIn(0, 20)).toDouble()).coerceAtMost(RETRY_MAX)

data class SummaryContext(
    val place: String = "",
    val moments: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val attendees: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
)

fun summaryPrompt(c: SummaryContext): String = "This is a transcript of a conversation recorded by a desk device. It can mix English, Mandarin and Cantonese. " +
    "Return JSON with the keys title (at most 6 words, about the main topic), summary (at most 3 plain sentences), todos, speakers and corrections. " +
    "Each todo is an object: task (a short action that starts with a verb) and quote (the exact words from the transcript that ask for or promise it, at most 12 words). " +
    "Only list a todo when someone in the transcript asks for, agrees to or promises a concrete action; else return an empty array. " +
    "Name an owner in the task only if the transcript says the name of the person; never use \"Speaker A\" style names as owners. " +
    "Use only facts from the transcript, in the language of the transcript. " +
    "If the transcript is not a real conversation (only noise, music, or a few unrelated words), return the title \"$NO_SPEECH\", an empty summary and no todos. " +
    "When lines start with a speaker name and a colon, that is who speaks. " +
    "Each speaker is an object: speaker (the label at the start of the line, for example \"Speaker B\"), name and evidence (the exact words from the transcript that show the name, at most 12 words). " +
    "Only add a speaker when the transcript shows the real name of that speaker: the speaker says their own name (\"this is Krista\"), " +
    "or another speaker says the name to them and they answer in the next line. Never guess a name; else return an empty array." +
    (if (c.attendees.isEmpty()) "" else " The people invited to this meeting are: ${c.attendees.joinToString(", ")}.") +
    " Each correction is an object: from (the exact word or words in the transcript), to and evidence (the exact words around it, at most 12 words). " +
    (if (c.candidates.isEmpty()) "Return an empty corrections array."
    else "Only add a correction when speech recognition wrote one of these names or terms wrongly and from sounds like it: ${c.candidates.joinToString(", ")}. Else return an empty array.") +
    (if (c.place.isEmpty()) "" else " The recording was made at the place that the user calls \"${c.place}\"; use it only as context.") +
    (if (c.moments.isEmpty()) "" else "\n\nThe user marked important moments on the device. Give them weight in the title, the summary and the todos. " +
        "Each line is the time of the mark and the text of the 2 minutes before it:\n" + c.moments.joinToString("\n") { "- $it" }) +
    (if (c.photos.isEmpty()) "" else "\n\nThe user took photos during the conversation. Each line is the photo time and the text in the photo:\n" +
        c.photos.joinToString("\n") { "- $it" }) +
    "\n\nTranscript:\n"

@Serializable
private class TodoDraft(val task: String, val quote: String)

@Serializable
private class SummaryDraft(
    val title: String,
    val summary: String,
    val todos: List<TodoDraft> = emptyList(),
    val speakers: List<SpeakerName> = emptyList(),
    val corrections: List<Correction> = emptyList(),
)

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

private val SPEAKER_PREFIX = Regex("""^\s*(speaker [a-z]|person \d+|you)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
private val COURTESY = setOf("thank", "thanks", "you", "ok", "okay", "yes", "yeah", "sure", "bye", "hello", "hi", "great", "good", "nice", "cool", "sorry", "please", "谢", "好", "拜", "嗯", "对")
const val TODO_QUOTE_MIN = 3

fun todoTask(task: String): String? {
    val t = task.replace(SPEAKER_PREFIX, "").trim()
    val words = tokens(t)
    return if (words.isEmpty() || words.all { it in COURTESY }) null else t
}

fun parseSummary(raw: String, transcript: String): Summary {
    val text = raw.trim()
    val json = text.substring(text.indexOf('{').coerceAtLeast(0), text.lastIndexOf('}').let { if (it < 0) text.length else it + 1 })
    val d = runCatching { hubJson.decodeFromString<SummaryDraft>(json) }.getOrElse { e ->
        val cut = Regex(""",\s*"(speakers|corrections)"\s*:""").find(json) ?: throw e
        hubJson.decodeFromString<SummaryDraft>(json.substring(0, cut.range.first) + "}")
    }
    if (d.title.trim().equals(NO_SPEECH, ignoreCase = true)) return Summary(NO_SPEECH, "")
    val todos = d.todos.mapNotNull { t ->
        val task = todoTask(t.task) ?: return@mapNotNull null
        val span = quoteSpan(t.quote, transcript)?.takeIf { tokens(it).size >= TODO_QUOTE_MIN }
        if (span == null || negated(task) != negated(span)) null else Todo(task, span)
    }
    val speakers = d.speakers.map { SpeakerName(it.speaker.trim(), it.name.trim(), it.evidence.trim()) }.filter { it.speaker.isNotEmpty() && it.name.isNotEmpty() }
    val corrections = d.corrections.map { Correction(it.from.trim(), it.to.trim(), it.evidence.trim()) }.filter { it.from.isNotEmpty() && it.to.isNotEmpty() }
    return Summary(d.title.trim(), d.summary.trim(), todos.distinctBy { normalized(it.task) }, speakers = speakers, corrections = corrections)
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
    private val diarizeWindowMs: Long = DIARIZE_WINDOW_MS,
    private val speechId: () -> String = { "" },
    private val vocabulary: Vocabulary? = null,
    private val calendarTerms: suspend (Instant, Instant) -> List<String> = { _, _ -> emptyList() },
    private val attendees: suspend (Instant, Instant) -> List<String> = { _, _ -> emptyList() },
    private val people: People? = null,
    private val log: (String) -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = Mutex()
    private val failedOnce = mutableSetOf<String>()
    private val waiting = mutableSetOf<String>()
    private var nextRetry: Instant? = null

    fun kick() {
        wake.trySend(Unit)
    }

    private suspend fun step(): Boolean {
        val all = store.all()
        val now = Clock.System.now()
        nextRetry = all.flatMap { m -> m.chunkRetry.filter { (seq, r) -> m.chunks[seq] == PENDING && r.next > now }.map { it.value.next } }.minOrNull()
        for (m in all) {
            val seq = m.chunks.entries.firstOrNull { (seq, text) -> text == PENDING && m.chunkRetry[seq].let { it == null || it.next <= now } }?.key ?: continue
            val start = TimeSource.Monotonic.markNow()
            val prompt = vocabulary?.let { v ->
                val from = m.started
                val to = m.wallAt(m.endMs())
                val calendar = runCatching { calendarTerms(from, to) }.getOrElse { emptyList() } + invited(from, to)
                v.prompt(spokenNames(people?.all().orEmpty()), calendar)
            }.orEmpty()
            val raw = runCatching { cleanSegments(transcriber.transcribe(Path(store.dir(m.device, m.id), "$seq.opus"), prompt)) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    transcribeFailed(m, seq, it)
                    return true
                }
            val fixed = vocabulary?.let { applyRules(raw, it.rules()) }
            val segments = fixed?.value ?: raw
            val text = segments.joinToString(" ") { it.text }
            log("INFO transcribed session=${m.id} seq=$seq words=${text.split(' ').count { w -> w.isNotEmpty() }} segments=${segments.size} prompt_chars=${prompt.length} replaced=${fixed?.count ?: 0} took=${start.elapsedNow()}")
            store.update(m.device, m.id) {
                it.chunks[seq] = text
                it.chunkEngine[seq] = speechId()
                if (fixed == null) it.chunkRules.remove(seq) else it.chunkRules[seq] = fixed.count
                if (segments.isEmpty()) it.segments.remove(seq) else it.segments[seq] = segments
                it.chunkRetry.remove(seq)
            }
            return true
        }
        for (m in all) {
            if (m.chunks.isEmpty() || m.pending || !m.needsSpeakers || !m.closed(now) || "${m.device}/${m.id}" in waiting) continue
            diarize(m)
            return true
        }
        val list = conversations(all)
        for (c in list) {
            val sessions = c.parts.map { it.session }.distinct()
            if (sessions.any { it.pending || it.needsSpeakers || !it.closed(now) || it.place == PENDING && now - it.lastChunk < PLACE_WAIT }) continue
            if (c.ready()) continue
            val retry = c.summaryRetry()
            if (retry != null && (retry.failed || retry.next > now)) {
                if (!retry.failed) nextRetry = listOfNotNull(nextRetry, retry.next).min()
                continue
            }
            summarize(c, list)
            return true
        }
        return false
    }

    private suspend fun transcribeFailed(m: Session, seq: String, e: Throwable) {
        val retry = store.update(m.device, m.id) {
            if (it.chunks[seq] != PENDING) return@update null
            val attempts = (it.chunkRetry[seq]?.attempts ?: 0) + 1
            val failed = attempts >= TRANSCRIBE_ATTEMPTS
            Retry(attempts, e.message ?: "${e::class.simpleName}", Clock.System.now() + retryWait(attempts), failed).also { r ->
                it.chunkRetry[seq] = r
                if (failed) {
                    it.chunks[seq] = ""
                    it.chunkEngine[seq] = speechId()
                }
            }
        } ?: return
        val then = if (retry.failed) "gave_up" else "next=${retry.next}"
        log("WARN transcribe session=${m.id} seq=$seq attempt=${retry.attempts}/$TRANSCRIBE_ATTEMPTS $then err=${retry.error}")
    }

    private suspend fun diarize(m: Session) {
        val key = "${m.device}/${m.id}"
        val input = m.speakerInput()
        val frames = if (m.transcript().isBlank()) null else store.sessionFrames(m)
        val start = TimeSource.Monotonic.markNow()
        val found = frames?.let { f ->
            runCatching { diarizeSession(f, diarizer, diarizeWindowMs) }
                .onFailure { log("WARN diarize session=${m.id} retry=${key !in failedOnce} err=${it.message}") }
                .getOrNull()
        }
        if (frames != null && found == null && failedOnce.add(key)) {
            waiting += key
            return
        }
        failedOnce -= key
        val current = store.update(m.device, m.id) {
            if (it.speakerInput() != input) return@update false
            it.diarized = true
            it.speakersFailed = frames != null && found == null
            if (found != null) {
                it.assignSpeakers(found.turns)
                it.labelVoices(found.voices, people?.all().orEmpty())
            }
            true
        }
        if (current != true) return log("INFO speakers dropped session=${m.id} reason=input_changed")
        if (found != null) {
            log("INFO diarized session=${m.id} speakers=${found.turns.map { it.speaker }.distinct().size} turns=${found.turns.size} took=${start.elapsedNow()}")
            learnVoices()
        }
    }

    private suspend fun invited(from: Instant, to: Instant): List<String> = runCatching { attendees(from, to) }.getOrElse { emptyList() }

    private suspend fun learnVoices() {
        val book = people ?: return
        val all = store.all()
        val before = book.all().size
        book.discover(all)
        val now = book.all()
        var linked = 0
        for (s in all) if (s.clone().linkPeople(now)) {
            store.update(s.device, s.id) { it.linkPeople(now) }
            linked++
        }
        if (now.size != before || linked > 0) log("INFO voices people=${now.size} new=${now.size - before} linked_sessions=$linked")
    }

    private suspend fun learnNames(c: Conversation, sum: Summary, text: String, invited: List<String>) {
        val book = people ?: return
        val accepted = acceptNames(sum.speakers, text, invited, c.slots(book.all()))
        for ((slot, name) in accepted) nameSpeaker(store, book, slot.session.device, slot.session.id, slot.speaker, name, auto = true)
        if (accepted.isNotEmpty()) log("INFO named conversation=${c.key} proposed=${sum.speakers.size} accepted=${accepted.size}")
    }

    private suspend fun learnWords(c: Conversation, title: String, all: List<Conversation>, invited: List<String>) {
        val v = vocabulary ?: return
        val others = all.filter { it.key != c.key }.take(AUTO_TERMS_WINDOW).map { it.transcript() + "\n" + it.recap().title }
        v.learnTerms(invited, TermSource.Calendar)
        v.learnTerms(recurringTerms(c.transcript() + "\n" + title, others), TermSource.Auto)
        v.learnRules(repeatedFixes(conversations(store.list(c.device))))
    }

    private suspend fun summaryFailed(c: Conversation, input: String, e: Throwable) {
        val attempts = (c.summaryRetry()?.attempts ?: 0) + 1
        val retry = Retry(attempts, e.message ?: "${e::class.simpleName}", Clock.System.now() + retryWait(attempts), attempts >= SUMMARY_ATTEMPTS, input)
        store.update(c.head.session.device, c.head.session.id) { it.summaryRetry["${c.head.fromMs}"] = retry }
        val then = if (retry.failed) "gave_up" else "next=${retry.next}"
        log("WARN summarize conversation=${c.key} attempt=$attempts/$SUMMARY_ATTEMPTS $then err=${retry.error.lineSequence().first().take(200)}")
    }

    private suspend fun summarize(c: Conversation, all: List<Conversation>) {
        val input = c.input()
        val units = speechUnits(c.transcript())
        val start = TimeSource.Monotonic.markNow()
        val text = c.labelledTranscript().trim()
        val invited = invited(c.start, c.parts.last().let { it.session.wallAt(it.toMs) })
        val candidates = (invited + spokenNames(people?.all().orEmpty()) + vocabulary?.terms().orEmpty())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(CANDIDATES_MAX)
        val sum = when {
            units < MIN_NOTE_UNITS -> Summary(NO_SPEECH, "")
            units < MIN_SPEECH_UNITS -> Summary(SHORT_NOTE, c.transcript().trim())
            else -> runCatching { summarizer.summarize(text, c.context().copy(attendees = invited, candidates = candidates)) }.getOrElse {
                if (it is CancellationException) throw it
                return summaryFailed(c, input, it)
            }.also { log("INFO summarized conversation=${c.key} parts=${c.parts.size} title=\"${it.title}\" todos=${it.todos.size} took=${start.elapsedNow()}") }
        }
        val todos = sum.todos.take(TODO_SLOTS)
        val quotes = todos.map { c.quote(it.quote) }
        var fixed = 0
        val saved = store.updateDevice(c.device) { sessions ->
            val now = conversations(sessions).firstOrNull { it.key == c.key }
            if (now == null || now.input() != input) return@updateDevice emptyList()
            val members = now.parts.map { it.session }.distinct()
            val rules = acceptCorrections(sum.corrections, text, candidates, members.flatMap { it.refusedFixes() }.toSet())
            val ticks = members.flatMap { s -> s.recaps().flatMap { it.second.ticks() } }
            val texts = todos.map { it.task }
            val done = mutableMapOf<String, Time>()
            val changes = mutableMapOf<String, Time>()
            texts.forEachIndexed { i, t ->
                val tick = ticks.filter { it.text == normalized(t) }.maxByOrNull { it.last } ?: return@forEachIndexed
                tick.done?.let { done["$i"] = it }
                tick.changed?.let { changes["$i"] = it }
            }
            for (r in rules) for (s in members) fixed += s.autoFix(r) { ms -> now.parts.any { it.session === s && it.covers(ms) } }
            val recap = rules.fold(Recap(sum.title, sum.summary, texts, quotes, sum.engine, done, changes, true, input)) { r, rule -> r.fixed(rule) }
            now.head.session.putRecap(now.head.fromMs, recap.copy(input = now.input()))
            now.head.session.summaryRetry.remove("${now.head.fromMs}")
            members.filter { it.place == PENDING }.forEach { it.place = "" }
            members
        }
        if (saved == 0) return log("INFO summary dropped conversation=${c.key} reason=input_changed")
        if (fixed > 0) log("INFO fixed conversation=${c.key} proposed=${sum.corrections.size} changed=$fixed")
        learnNames(c, sum, text, invited)
        learnWords(c, sum.title, all, invited)
    }

    suspend fun drain() = running.withLock {
        waiting.clear()
        while (step()) Unit
    }

    suspend fun run() {
        while (coroutineContext.isActive) {
            drain()
            val idle = if (waiting.isEmpty()) 1.minutes else RETRY_WAIT
            val retry = nextRetry?.let { (it - Clock.System.now()).coerceAtLeast(1.seconds) } ?: idle
            withTimeoutOrNull(minOf(idle, retry)) { wake.receive() }
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
