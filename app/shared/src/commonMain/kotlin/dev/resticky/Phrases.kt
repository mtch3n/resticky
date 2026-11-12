package dev.resticky

const val PHRASE_GAP_MS = 300L
const val PHRASE_MIN_MS = 2_000L
const val PHRASE_MAX_MS = 8_000L
const val CHINESE_SHARE = 0.5
const val TAGGED_CHINESE_SHARE = 0.3
val CHINESE_TAGS = setOf("zh", "yue")

class Heard(val lang: String, val words: List<Word>) {
    val text get() = words.joinToString(" ") { it.text }
}

fun SpeechWindow.speechIn(regions: List<Piece>): List<Piece> {
    var at = 0
    return pieces.flatMap { p ->
        val start = at
        at += p.size
        regions.filter { it.to > p.from && it.from < p.to }
            .map { Piece(maxOf(it.from, p.from) - p.from + start, minOf(it.to, p.to) - p.from + start) }
    }
}

fun phrases(regions: List<Piece>, total: Int, rate: Int): List<Piece> {
    if (total <= 0) return emptyList()
    val gap = (PHRASE_GAP_MS * rate / 1000).toInt()
    val min = (PHRASE_MIN_MS * rate / 1000).toInt()
    val max = (PHRASE_MAX_MS * rate / 1000).toInt()
    val merged = mutableListOf<Piece>()
    for (r in regions.map { Piece(it.from.coerceIn(0, total), it.to.coerceIn(0, total)) }.filter { it.size > 0 }.sortedBy { it.from }) {
        val last = merged.lastOrNull()
        if (last != null && r.from - last.to < gap) merged[merged.lastIndex] = Piece(last.from, maxOf(last.to, r.to)) else merged += r
    }
    if (merged.isEmpty()) return listOf(Piece(0, total))
    val groups = mutableListOf(mutableListOf(merged.first()))
    for (r in merged.drop(1)) {
        val g = groups.last()
        if (g.last().to - g.first().from >= min && r.to - g.first().from > max) groups += mutableListOf(r) else g += r
    }
    if (groups.size > 1 && groups.last().let { it.last().to - it.first().from } < min) {
        val tail = groups.removeAt(groups.lastIndex)
        groups.last().addAll(tail)
    }
    val cuts = groups.zipWithNext { a, b -> (a.last().to + b.first().from) / 2 }
    return (listOf(0) + cuts).zip(cuts + total) { from, to -> Piece(from, to) }
}

fun isChinese(lang: String, text: String): Boolean {
    val units = speechUnits(text)
    if (units == 0) return false
    val share = text.count(::isCjk).toDouble() / units
    return share >= if (lang in CHINESE_TAGS) TAGGED_CHINESE_SHARE else CHINESE_SHARE
}

fun Segment.shifted(ms: Long): Segment =
    copy(startMs = startMs + ms, endMs = endMs + ms, words = words.map { Word(it.startMs + ms, it.endMs + ms, it.text) })

class Routed(val routes: List<String>, val segments: List<Segment>)

fun routePhrases(phrases: List<Piece>, heard: List<Heard>, rate: Int, english: (Piece) -> List<Segment>): Routed {
    val chinese = phrases.indices.map { isChinese(heard[it].lang, heard[it].text) }
    val runs = mutableListOf<MutableList<Int>>()
    for (i in phrases.indices) if (i > 0 && chinese[i] == chinese[i - 1]) runs.last() += i else runs += mutableListOf(i)
    val routes = mutableListOf<String>()
    val segments = runs.flatMap { run ->
        val langs = run.map { heard[it].lang }.distinct().joinToString("/")
        if (chinese[run.first()]) {
            routes += "sensevoice:$langs"
            run.mapNotNull { i ->
                val p = phrases[i]
                val from = p.from * 1000L / rate
                heard[i].words.takeIf { it.isNotEmpty() }?.let { w -> Segment(0, p.size * 1000L / rate, heard[i].text, words = w).shifted(from) }
            }
        } else {
            routes += "whisper:$langs"
            val span = Piece(phrases[run.first()].from, phrases[run.last()].to)
            english(span).map { it.shifted(span.from * 1000L / rate) }
        }
    }
    return Routed(routes, segments)
}
