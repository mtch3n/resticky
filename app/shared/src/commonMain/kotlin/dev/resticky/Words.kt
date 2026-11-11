package dev.resticky

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

const val SILENCE_MIN_MS = 1500L
const val SILENCE_PAD_MS = 250L
const val CLEANED = "-----"

private val FILLERS = setOf("um", "umm", "uh", "uhh", "erm", "er", "ah", "hmm", "mm", "mhm")
private val FILLER_PAIRS = setOf("you know", "i mean")

@Serializable(WordSerializer::class)
data class Word(val startMs: Long, val endMs: Long, val text: String)

object WordSerializer : KSerializer<Word> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Word) =
        (encoder as JsonEncoder).encodeJsonElement(JsonArray(listOf(JsonPrimitive(value.startMs), JsonPrimitive(value.endMs), JsonPrimitive(value.text))))

    override fun deserialize(decoder: Decoder): Word {
        val a = (decoder as JsonDecoder).decodeJsonElement().jsonArray
        return Word(a[0].jsonPrimitive.long, a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
    }
}

class Token(val text: String, val startMs: Long, val endMs: Long)

fun wordsFromTokens(tokens: List<Token>): List<Word> {
    val out = mutableListOf<Word>()
    for (t in tokens) {
        if (t.text.startsWith("[_") || t.text.startsWith("<|")) continue
        val last = out.lastOrNull()
        if (last != null && !t.text.startsWith(" ")) out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs), text = last.text + t.text)
        else if (t.text.isNotBlank()) out += Word(t.startMs, t.endMs, t.text.trim())
    }
    return out.filter { it.text.isNotEmpty() && it.text != "[BLANK_AUDIO]" }
}

private fun norm(w: String) = w.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

fun fillers(words: List<Word>): BooleanArray {
    val out = BooleanArray(words.size)
    val n = words.map { norm(it.text) }
    for (i in words.indices) {
        val raw = words[i].text.trimEnd { it == ',' || it == '.' || it == '?' || it == '!' }
        when {
            n[i] in FILLERS -> out[i] = true
            raw.endsWith("-") && raw.length > 1 -> out[i] = true
            i + 1 < words.size && n[i].isNotEmpty() && n[i] == n[i + 1] -> out[i] = true
            i + 1 < words.size && "${n[i]} ${n[i + 1]}" in FILLER_PAIRS -> {
                out[i] = true
                out[i + 1] = true
            }
        }
    }
    return out
}

fun cleanText(words: List<Word>): String {
    val f = fillers(words)
    val out = mutableListOf<String>()
    words.forEachIndexed { i, w ->
        if (!f[i]) out += w.text
        else if (out.lastOrNull() != CLEANED) out += CLEANED
    }
    return out.joinToString(" ")
}

fun Session.skipRanges(cleanUp: Boolean, skipSilence: Boolean): List<LongRange> {
    if (!cleanUp && !skipSilence) return emptyList()
    val offsets = offsets()
    val spans = mutableListOf<LongRange>()
    val skips = mutableListOf<LongRange>()
    for ((seq, at) in offsets.entries.sortedBy { it.value }) {
        val segs = segments[seq].orEmpty()
        val words = segs.flatMap { it.words }
        if (words.isEmpty()) {
            val text = chunks[seq].orEmpty()
            if (text.isNotEmpty() && text != PENDING) spans += at..at + (chunkMs[seq] ?: CHUNK_MS)
            continue
        }
        val f = fillers(words)
        words.forEachIndexed { i, w ->
            val r = at + w.startMs..at + w.endMs
            if (cleanUp && f[i]) skips += r else spans += r
        }
    }
    if (skipSilence) {
        spans.sortBy { it.first }
        var end = 0L
        for (s in spans) {
            if (s.first - end > SILENCE_MIN_MS) skips += end + SILENCE_PAD_MS..s.first - SILENCE_PAD_MS
            end = maxOf(end, s.last)
        }
    }
    return merge(skips)
}

private fun merge(ranges: List<LongRange>): List<LongRange> {
    val out = mutableListOf<LongRange>()
    for (r in ranges.filter { it.last > it.first }.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
    }
    return out
}

fun nextPlay(position: Long, skips: List<LongRange>): Long? = skips.firstOrNull { position in it }?.last
