package dev.resticky.desktop

import dev.resticky.ME_NAME
import dev.resticky.UNKNOWN_SPEAKER
import dev.resticky.speakerLetter
import dev.resticky.tokens
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer

@Serializable
data class RefSegment(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val speaker: String,
    val text: String,
)

@Serializable
data class Reference(val segments: List<RefSegment>)

const val SPEAKER_FRAME_MS = 100L
val UNKNOWN_LABEL = speakerLetter(UNKNOWN_SPEAKER)

private fun isCjk(token: String) = token.length == 1 && (token[0] in '㐀'..'鿿' || token[0] in '豈'..'﫿')

fun scoreTokens(text: String): List<String> = tokens(Normalizer.normalize(text, Normalizer.Form.NFKC))

@Serializable
data class TextErrors(
    @SerialName("ref_words") val refWords: Int = 0,
    @SerialName("word_errors") val wordErrors: Int = 0,
    @SerialName("ref_chars") val refChars: Int = 0,
    @SerialName("char_errors") val charErrors: Int = 0,
) {
    operator fun plus(o: TextErrors) = TextErrors(refWords + o.refWords, wordErrors + o.wordErrors, refChars + o.refChars, charErrors + o.charErrors)
}

fun textErrors(reference: String, hypothesis: String): TextErrors {
    val r = scoreTokens(reference)
    val h = scoreTokens(hypothesis)
    val d = Array(r.size + 1) { i -> IntArray(h.size + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
    for (i in 1..r.size) for (j in 1..h.size) {
        d[i][j] = minOf(d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
    }
    var words = 0
    var chars = 0
    fun miss(token: String) = if (isCjk(token)) chars++ else words++
    var i = r.size
    var j = h.size
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1 -> {
                if (r[i - 1] != h[j - 1]) miss(r[i - 1])
                i--
                j--
            }
            i > 0 && d[i][j] == d[i - 1][j] + 1 -> miss(r[--i])
            else -> miss(h[--j])
        }
    }
    return TextErrors(r.count { !isCjk(it) }, words, r.count(::isCjk), chars)
}

class HubSpan(val startMs: Long, val endMs: Long, val speaker: String)

@Serializable
data class SpeakerErrors(
    @SerialName("speech_ms") val speechMs: Long = 0,
    @SerialName("matched_ms") val matchedMs: Long = 0,
    @SerialName("missed_ms") val missedMs: Long = 0,
    @SerialName("you_ms") val youMs: Long = 0,
    @SerialName("you_hit_ms") val youHitMs: Long = 0,
    @SerialName("not_you_ms") val notYouMs: Long = 0,
    @SerialName("false_you_ms") val falseYouMs: Long = 0,
) {
    operator fun plus(o: SpeakerErrors) = SpeakerErrors(
        speechMs + o.speechMs, matchedMs + o.matchedMs, missedMs + o.missedMs,
        youMs + o.youMs, youHitMs + o.youHitMs, notYouMs + o.notYouMs, falseYouMs + o.falseYouMs,
    )
}

fun bestMapping(overlap: Map<Pair<String, String>, Long>): Map<String, String> {
    val refs = overlap.keys.map { it.first }.distinct().sorted()
    val hubs = overlap.keys.map { it.second }.distinct().sorted()
    val memo = HashMap<Long, Long>()
    fun best(i: Int, used: Long): Long {
        if (i == refs.size) return 0
        val key = used * (refs.size + 1) + i
        memo[key]?.let { return it }
        var top = best(i + 1, used)
        hubs.forEachIndexed { k, h ->
            if (used and (1L shl k) == 0L) top = maxOf(top, (overlap[refs[i] to h] ?: 0) + best(i + 1, used or (1L shl k)))
        }
        return top.also { memo[key] = it }
    }
    val out = mutableMapOf<String, String>()
    var used = 0L
    refs.forEachIndexed { i, r ->
        val target = best(i, used)
        if (target == best(i + 1, used)) return@forEachIndexed
        val k = hubs.indices.first { k -> used and (1L shl k) == 0L && (overlap[r to hubs[k]] ?: 0) + best(i + 1, used or (1L shl k)) == target }
        out[r] = hubs[k]
        used = used or (1L shl k)
    }
    return out
}

fun speakerErrors(reference: List<RefSegment>, hub: List<HubSpan>): SpeakerErrors {
    val overlap = mutableMapOf<Pair<String, String>, Long>()
    var speech = 0L
    var missed = 0L
    val hasYou = reference.any { it.speaker == ME_NAME }
    var you = 0L
    var youHit = 0L
    var notYou = 0L
    var falseYou = 0L
    for (s in reference) {
        var t = s.startMs
        while (t < s.endMs) {
            val ms = minOf(SPEAKER_FRAME_MS, s.endMs - t)
            val mid = t + ms / 2
            val h = hub.firstOrNull { mid >= it.startMs && mid < it.endMs }?.speaker
            speech += ms
            if (h == null) missed += ms else if (h != UNKNOWN_LABEL) overlap.merge(s.speaker to h, ms, Long::plus)
            if (hasYou) {
                if (s.speaker == ME_NAME) {
                    you += ms
                    if (h == ME_NAME) youHit += ms
                } else {
                    notYou += ms
                    if (h == ME_NAME) falseYou += ms
                }
            }
            t += ms
        }
    }
    val mapping = bestMapping(overlap)
    val matched = mapping.entries.sumOf { (r, h) -> overlap[r to h] ?: 0 }
    return SpeakerErrors(speech, matched, missed, you, youHit, notYou, falseYou)
}

@Serializable
data class Score(val text: TextErrors = TextErrors(), val speakers: SpeakerErrors = SpeakerErrors(), val seconds: Long = 0) {
    operator fun plus(o: Score) = Score(text + o.text, speakers + o.speakers, seconds + o.seconds)

    private fun rate(a: Number, b: Number) = if (b.toDouble() == 0.0) null else a.toDouble() / b.toDouble()

    fun metrics(): Map<String, Double?> = linkedMapOf(
        "wer" to rate(text.wordErrors, text.refWords),
        "cer" to rate(text.charErrors, text.refChars),
        "mer" to rate(text.wordErrors + text.charErrors, text.refWords + text.refChars),
        "speaker_error" to rate(speakers.speechMs - speakers.matchedMs, speakers.speechMs),
        "missed_speech" to rate(speakers.missedMs, speakers.speechMs),
        "you_rate" to rate(speakers.youHitMs, speakers.youMs),
        "false_you" to rate(speakers.falseYouMs, speakers.notYouMs),
    )
}

@Serializable
data class Run(val cases: Map<String, Score>, val total: Score)

private fun percent(v: Double?) = v?.let { "%.1f%%".format(it * 100) } ?: "-"

fun table(name: String, score: Score, old: Score?): String {
    val rows = mutableListOf(listOf("metric", "value") + if (old == null) emptyList() else listOf("old", "change"))
    val before = old?.metrics()
    for ((k, v) in score.metrics()) {
        val o = before?.get(k)
        rows += listOf(k, percent(v)) + if (before == null) emptyList() else listOf(percent(o), if (v == null || o == null) "-" else "%+.1f".format((v - o) * 100))
    }
    rows += listOf("ref_tokens", "${score.text.refWords} words, ${score.text.refChars} chars")
    rows += listOf("speech", "${score.speakers.speechMs / 1000} s")
    rows += listOf("run_time", "${score.seconds} s")
    return "== $name\n" + rows.joinToString("\n") { r -> r.mapIndexed { i, c -> if (i == r.lastIndex) c else c.padEnd(15) }.joinToString("") }
}
