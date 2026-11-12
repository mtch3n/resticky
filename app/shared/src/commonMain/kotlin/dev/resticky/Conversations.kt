package dev.resticky

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

val CONVERSATION_GAP = 10.minutes
const val SILENCE_SPLIT_MS = 300_000L
const val TODO_SLOTS = 100
private const val MINUTE_MS = 60_000L

fun Session.captureMs(timelineMs: Long): Long {
    val offsets = offsets()
    val seq = offsets.entries.filter { it.value <= timelineMs }.maxByOrNull { it.value }?.key ?: return timelineMs
    val anchor = chunkStart.keys.minByOrNull { it.toInt() } ?: return timelineMs
    val start = chunkStart[seq] ?: return timelineMs
    return offsets.getValue(anchor) + start - chunkStart.getValue(anchor) + timelineMs - offsets.getValue(seq)
}

fun Session.wallAt(timelineMs: Long): Instant = started + captureMs(timelineMs).milliseconds

fun Session.closed(now: Instant): Boolean = final || now - wallAt(endMs()) > SESSION_IDLE

val Session.needsSpeakers: Boolean get() = !diarized && !ready

fun Session.speechSpans(): List<LongRange> {
    val offsets = offsets()
    return chunks.keys.flatMap { seq ->
        val at = offsets.getValue(seq)
        val segs = segments[seq].orEmpty()
        when {
            segs.isNotEmpty() -> segs.map { at + it.startMs..at + it.endMs }
            chunks.getValue(seq).isEmpty() -> emptyList()
            else -> listOf(at..at + (chunkMs[seq] ?: CHUNK_MS))
        }
    }.sortedBy { it.first }
}

fun Session.pieces(): List<Pair<Long, Long>> {
    val starts = mutableListOf(0L)
    var last: Long? = null
    for (s in speechSpans()) {
        if (last != null && s.first - last >= SILENCE_SPLIT_MS) starts += s.first
        last = maxOf(last ?: s.last, s.last)
    }
    val end = endMs()
    return starts.mapIndexed { i, from -> from to (starts.getOrNull(i + 1) ?: end) }
}

fun Session.todoRecap(index: Int): Pair<Long, Int>? {
    val minute = index / TODO_SLOTS
    val from = if (minute == 0) 0L else splits.keys.map { it.toLong() }.firstOrNull { it / MINUTE_MS == minute.toLong() } ?: return null
    return from to index % TODO_SLOTS
}

class Part(val session: Session, val fromMs: Long, val toMs: Long) {
    fun covers(ms: Long): Boolean = ms >= fromMs && (ms < toMs || toMs == session.endMs())

    fun lines(): List<Line> = session.lines().filter { covers(it.atMs) }
}

class Spoken(val text: String, val session: String, val atMs: Long)

class Conversation(val parts: List<Part>) {
    val head: Part get() = parts.first()
    val device: String get() = head.session.device
    val key: String get() = "${head.session.id}@${head.fromMs}"
    val start: Instant get() = head.session.wallAt(head.fromMs)

    fun recap(): Recap = head.session.recap(head.fromMs)

    fun todoId(i: Int): String = "${head.session.id}-${head.fromMs / MINUTE_MS * TODO_SLOTS + i}"

    fun ready(): Boolean {
        val r = recap()
        val whole = parts.size == 1 && head.fromMs == 0L && head.toMs == head.session.endMs()
        return r.ready && (r.input == input() || r.input.isEmpty() && whole)
    }

    fun transcript(): String = parts.flatMap { it.lines() }.joinToString(" ") { it.text }

    private fun turns(named: Boolean): List<List<Spoken>> {
        val out = mutableListOf<MutableList<Spoken>>()
        var last: Pair<Session, Int>? = null
        val labelled = parts.map { it.session }.filter { it.hasSpeakers }.map { it.id }.toSet()
        for (p in parts) for (l in p.lines()) {
            val who = p.session to l.speaker
            if (who != last) {
                out += mutableListOf<Spoken>()
                if (l.speaker >= 0 || p.session.id in labelled) out.last() += Spoken(if (named) p.session.speakerName(l.speaker) + ":" else speakerLetter(l.speaker) + ":", p.session.id, l.atMs)
                last = who
            }
            out.last() += if (l.words.isEmpty()) listOf(Spoken(l.text, p.session.id, l.atMs)) else l.words.map { Spoken(it.text, p.session.id, it.startMs) }
        }
        return out
    }

    private fun labelled(named: Boolean): String = turns(named).joinToString("\n") { t -> t.joinToString(" ") { it.text } }

    fun labelledTranscript(): String = labelled(true)

    private fun offset(p: Part, ms: Long): Long = (p.session.wallAt(ms) - start).inWholeMilliseconds

    fun moments(): List<String> = parts.flatMap { p ->
        val lines = p.lines()
        p.session.marks.filter(p::covers).map { mark ->
            val text = lines.filterIndexed { i, l -> l.atMs <= mark && (lines.getOrNull(i + 1)?.atMs ?: Long.MAX_VALUE) > mark - MARK_WINDOW_MS }
                .joinToString(" ") { it.text }
            "${timeLabel(offset(p, mark))} $text".trim()
        }
    }

    fun photoTexts(): List<String> = parts.flatMap { p ->
        p.session.photos.filter { p.covers(it.offsetMs) && it.text.isNotBlank() }.map { "${timeLabel(offset(p, it.offsetMs))} ${it.text.replace('\n', ' ')}" }
    }

    fun marks(): Int = parts.sumOf { p -> p.session.marks.count(p::covers) }

    fun photos(): Int = parts.sumOf { p -> p.session.photos.count { p.covers(it.offsetMs) } }

    fun context(): SummaryContext = SummaryContext(placeContext(head.session.place), moments(), photoTexts())

    fun input(): String {
        val c = context()
        val text = parts.joinToString(";") { "${it.session.id}:${it.fromMs}-${it.toMs}" } + "\n" + labelled(false) + "\n" +
            c.place + "\n" + c.moments.joinToString("\n") + "\n" + c.photos.joinToString("\n")
        var h = -0x340d631b7bdddcdbL
        for (ch in text) h = (h xor ch.code.toLong()) * 0x100000001b3L
        return h.toULong().toString(16)
    }

    fun quote(text: String): Quote {
        val words = mutableListOf<String>()
        val where = mutableListOf<Spoken>()
        for (s in turns(true).flatten()) for (w in tokens(s.text)) {
            words += w
            where += s
        }
        val r = findQuote(tokens(text), words) ?: return Quote(text)
        return Quote(text, where[r.first].session, where[r.first].atMs)
    }
}

fun conversations(sessions: List<Session>): List<Conversation> {
    val out = mutableListOf<Conversation>()
    for (list in sessions.filter { it.chunks.isNotEmpty() }.groupBy { it.device }.values) {
        var parts = mutableListOf<Part>()
        var end: Instant? = null
        for (s in list.sortedBy { it.started }) for ((i, piece) in s.pieces().withIndex()) {
            val (from, to) = piece
            val gap = end?.let { s.wallAt(from) - it }
            if (parts.isNotEmpty() && (i > 0 || gap == null || gap >= CONVERSATION_GAP)) {
                out += Conversation(parts)
                parts = mutableListOf()
            }
            parts += Part(s, from, to)
            end = s.wallAt(to)
        }
        if (parts.isNotEmpty()) out += Conversation(parts)
    }
    return out.sortedByDescending { it.start }
}
