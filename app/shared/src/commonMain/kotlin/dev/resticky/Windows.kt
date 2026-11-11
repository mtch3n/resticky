package dev.resticky

const val CONTEXT_MS = 2000L
const val WINDOW_MAX_MS = 28_000L
const val WINDOW_PAD_MS = 250L
const val MIN_SPEECH_MS = 500L

data class Piece(val from: Int, val to: Int) {
    val size get() = to - from
}

data class SpeechWindow(val pieces: List<Piece>) {
    val size get() = pieces.sumOf { it.size }

    fun audio(pcm: FloatArray): FloatArray {
        val out = FloatArray(size)
        var at = 0
        for (p in pieces) {
            pcm.copyInto(out, at, p.from, p.to)
            at += p.size
        }
        return out
    }

    fun ends(rate: Int): List<Long> {
        var at = 0L
        return pieces.map { at += it.size; at * 1000 / rate }
    }

    fun chunkMs(windowMs: Long, context: Int, rate: Int): Long {
        val s = windowMs * rate / 1000
        var at = 0L
        for ((i, p) in pieces.withIndex()) {
            if (s < at + p.size || i == pieces.lastIndex) return (p.from + (s - at).coerceIn(0L, p.size.toLong()) - context) * 1000 / rate
            at += p.size
        }
        return 0
    }
}

fun speechMs(regions: List<Piece>, context: Int, rate: Int): Long =
    regions.filter { it.to > context }.sumOf { it.size.toLong() } * 1000 / rate

fun speechWindows(regions: List<Piece>, total: Int, context: Int, rate: Int): List<SpeechWindow> {
    val pad = (WINDOW_PAD_MS * rate / 1000).toInt()
    val max = (WINDOW_MAX_MS * rate / 1000).toInt()
    val merged = mutableListOf<Piece>()
    for (r in regions.filter { it.to > context }.sortedBy { it.from }) {
        val p = Piece(maxOf(0, r.from - pad), minOf(total, r.to + pad))
        val last = merged.lastOrNull()
        if (last != null && p.from <= last.to) merged[merged.lastIndex] = Piece(last.from, maxOf(last.to, p.to)) else merged += p
    }
    val pieces = merged.flatMap { p -> (p.from until p.to step max).map { Piece(it, minOf(p.to, it + max)) } }
    val windows = mutableListOf<MutableList<Piece>>()
    var used = 0
    for (p in pieces) {
        if (windows.isEmpty() || used + p.size > max) {
            windows += mutableListOf<Piece>()
            used = 0
        }
        windows.last() += p
        used += p.size
    }
    return windows.map { SpeechWindow(it) }
}

fun placeSegments(segments: List<Segment>, window: SpeechWindow, context: Int, rate: Int): List<Segment> = segments.mapNotNull { s ->
    if (s.words.isEmpty()) {
        val from = window.chunkMs(s.startMs, context, rate)
        val to = window.chunkMs(s.endMs, context, rate)
        if (from + to < 0) null else s.copy(startMs = from.coerceAtLeast(0), endMs = to.coerceAtLeast(0))
    } else {
        val words = s.words.map { w -> Word(window.chunkMs(w.startMs, context, rate), window.chunkMs(w.endMs, context, rate), w.text) }
            .filter { it.startMs + it.endMs >= 0 }
            .map { it.copy(startMs = it.startMs.coerceAtLeast(0)) }
        if (words.isEmpty()) null
        else s.copy(
            startMs = words.first().startMs,
            endMs = maxOf(words.last().endMs, words.first().startMs),
            text = if (words.size == s.words.size) s.text else words.joinToString(" ") { it.text },
            words = words,
        )
    }
}
