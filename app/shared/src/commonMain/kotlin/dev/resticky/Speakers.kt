package dev.resticky

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.math.sqrt

const val SPEAKER_MERGE = 0.6
const val SPEAKER_MIN_MS = 20_000L
const val SPEAKER_MIN_SHARE = 0.02
const val VOICE_MIN_MS = 1500L
const val ABSORB_MIN_COSINE = 0.4
const val ABSORB_TIME_MAX_MS = 5_000L
const val OVERLAP_MAX_MS = 1_000L
private const val FRAME_MS = 20L

class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

class Voice(val speaker: Int, val embedding: FloatArray, val weightMs: Long)

class Diarization(val turns: List<Turn>, val voices: List<Voice>)

object FloatsBase64 : KSerializer<FloatArray> {
    override val descriptor = PrimitiveSerialDescriptor("FloatsBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FloatArray) {
        val bytes = ByteArray(value.size * 4)
        value.forEachIndexed { i, f ->
            val b = f.toRawBits()
            for (k in 0 until 4) bytes[i * 4 + k] = (b shr (8 * k)).toByte()
        }
        encoder.encodeString(Base64.encode(bytes))
    }

    override fun deserialize(decoder: Decoder): FloatArray {
        val bytes = Base64.decode(decoder.decodeString())
        return FloatArray(bytes.size / 4) { i -> Float.fromBits((0 until 4).fold(0) { acc, k -> acc or ((bytes[i * 4 + k].toInt() and 0xff) shl (8 * k)) }) }
    }
}

@Serializable
class Voiceprint(@Serializable(FloatsBase64::class) val embedding: FloatArray, @SerialName("talk_ms") val talkMs: Long)

fun interface Diarizer {
    suspend fun diarize(frames: ByteArray): Diarization
}

fun speakerLetter(speaker: Int): String =
    "Speaker " + if (speaker < 26) "${'A' + speaker}" else "${speaker + 1}"

internal fun FloatArray.unit(): FloatArray {
    val n = sqrt(sumOf { it.toDouble() * it }).toFloat()
    return if (n == 0f) this else FloatArray(size) { this[it] / n }
}

internal fun cosine(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

fun voiceOf(speaker: Int, parts: List<Pair<FloatArray, Long>>): Voice? {
    if (parts.isEmpty()) return null
    val dim = parts.first().first.size
    val sum = FloatArray(dim)
    parts.forEach { (e, w) -> val u = e.unit(); for (i in 0 until dim) sum[i] += u[i] * w }
    return Voice(speaker, sum.unit(), parts.sumOf { it.second })
}

private fun overlaps(turns: List<Turn>): MutableMap<Int, MutableMap<Int, Long>> {
    val out = mutableMapOf<Int, MutableMap<Int, Long>>()
    fun add(a: Int, b: Int, ms: Long) {
        val m = out.getOrPut(a) { mutableMapOf() }
        m[b] = (m[b] ?: 0) + ms
    }
    val sorted = turns.sortedBy { it.startMs }
    for (i in sorted.indices) {
        val a = sorted[i]
        var j = i + 1
        while (j < sorted.size && sorted[j].startMs < a.endMs) {
            val b = sorted[j++]
            if (a.speaker == b.speaker) continue
            val ms = minOf(a.endMs, b.endMs) - b.startMs
            add(a.speaker, b.speaker, ms)
            add(b.speaker, a.speaker, ms)
        }
    }
    return out
}

fun mergeSpeakers(d: Diarization, minCosine: Double = SPEAKER_MERGE): Diarization {
    val voices = d.voices.associate { it.speaker to (it.embedding.unit() to it.weightMs) }.toMutableMap()
    val together = overlaps(d.turns)
    fun overlap(a: Int, b: Int): Long = together[a]?.get(b) ?: 0
    val target = mutableMapOf<Int, Int>()
    fun root(s: Int): Int = target[s]?.let(::root) ?: s
    while (voices.size > 1) {
        val keys = voices.keys.sorted()
        var best: Triple<Int, Int, Double>? = null
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            if (overlap(keys[i], keys[j]) > OVERLAP_MAX_MS) continue
            val c = cosine(voices.getValue(keys[i]).first, voices.getValue(keys[j]).first)
            if (best == null || c > best.third) best = Triple(keys[i], keys[j], c)
        }
        val (a, b, c) = best ?: break
        if (c <= minCosine) break
        val (ea, wa) = voices.getValue(a)
        val (eb, wb) = voices.getValue(b)
        voices[a] = FloatArray(ea.size) { ea[it] * wa + eb[it] * wb }.unit() to wa + wb
        voices.remove(b)
        target[b] = a
        for ((x, ms) in together.remove(b).orEmpty()) {
            if (x == a) continue
            val ax = together.getOrPut(a) { mutableMapOf() }
            ax[x] = (ax[x] ?: 0) + ms
            val xs = together.getValue(x)
            xs[a] = (xs[a] ?: 0) + ms
            xs.remove(b)
        }
        together[a]?.remove(b)
    }
    val byStart = d.turns.sortedBy { it.startMs }
    val sorted = byStart.map { Turn(it.startMs, it.endMs, root(it.speaker)) }
    val talk = sorted.groupBy { it.speaker }.mapValues { (_, l) -> l.sumOf { it.endMs - it.startMs } }
    val total = talk.values.sum()
    val major = talk.filter { (_, ms) -> ms >= SPEAKER_MIN_MS && ms >= total * SPEAKER_MIN_SHARE }.keys
    val absorbed = if (major.isEmpty()) sorted else sorted.mapIndexed { i, t ->
        if (t.speaker in major) return@mapIndexed t
        val allowed = major.filter { overlap(t.speaker, it) <= OVERLAP_MAX_MS }
        val own = voices[t.speaker]?.first
        val into = when {
            own != null -> allowed.filter { it in voices }.map { it to cosine(own, voices.getValue(it).first) }
                .filter { it.second >= ABSORB_MIN_COSINE }.maxByOrNull { it.second }?.first
            talk.getValue(t.speaker) < ABSORB_TIME_MAX_MS ->
                (i - 1 downTo 0).firstOrNull { sorted[it].speaker in allowed }?.let { sorted[it].speaker }
                    ?: (i + 1 until sorted.size).firstOrNull { sorted[it].speaker in allowed }?.let { sorted[it].speaker }
            else -> null
        }
        Turn(t.startMs, t.endMs, into ?: t.speaker)
    }
    val order = mutableMapOf<Int, Int>()
    val turns = absorbed.map { t -> Turn(t.startMs, t.endMs, order.getOrPut(t.speaker) { order.size }) }
    val went = mutableMapOf<Int, MutableMap<Int, Long>>()
    byStart.forEachIndexed { i, t ->
        val m = went.getOrPut(t.speaker) { mutableMapOf() }
        m[turns[i].speaker] = (m[turns[i].speaker] ?: 0) + (t.endMs - t.startMs)
    }
    val finalVoices = d.voices.mapNotNull { v -> went[v.speaker]?.maxByOrNull { it.value }?.let { it.key to v } }
        .groupBy({ it.first }, { it.second.embedding to it.second.weightMs })
        .mapNotNull { (speaker, parts) -> voiceOf(speaker, parts) }
        .sortedBy { it.speaker }
    return Diarization(turns, finalVoices)
}

fun speakerAt(startMs: Long, endMs: Long, turns: List<Turn>, last: Int): Int {
    var best = -1
    var bestOverlap = 0L
    for (t in turns) {
        val overlap = minOf(endMs, t.endMs) - maxOf(startMs, t.startMs)
        if (overlap > bestOverlap) {
            best = t.speaker
            bestOverlap = overlap
        }
    }
    if (best >= 0) return best
    if (last >= 0) return last
    val mid = (startMs + endMs) / 2
    return turns.minByOrNull { minOf(kotlin.math.abs(mid - it.startMs), kotlin.math.abs(mid - it.endMs)) }?.speaker ?: -1
}

fun frameWindows(frames: ByteArray, windowMs: Long): List<Pair<Long, ByteArray>> {
    val maxFrames = (windowMs / FRAME_MS).coerceAtLeast(1)
    val out = mutableListOf<Pair<Long, ByteArray>>()
    var start = 0
    var startFrame = 0L
    var count = 0L
    var i = 0
    while (i + 2 <= frames.size) {
        val n = (frames[i].toInt() and 0xff) or ((frames[i + 1].toInt() and 0xff) shl 8)
        if (i + 2 + n > frames.size) break
        i += 2 + n
        count++
        if (count == maxFrames) {
            out += startFrame * FRAME_MS to frames.copyOfRange(start, i)
            start = i
            startFrame += count
            count = 0
        }
    }
    if (count > 0) out += startFrame * FRAME_MS to frames.copyOfRange(start, i)
    return out
}

suspend fun diarizeSession(frames: ByteArray, diarizer: Diarizer, windowMs: Long): Diarization {
    val turns = mutableListOf<Turn>()
    val voices = mutableListOf<Voice>()
    frameWindows(frames, windowMs).forEachIndexed { w, (offset, part) ->
        val d = diarizer.diarize(part)
        turns += d.turns.map { Turn(it.startMs + offset, it.endMs + offset, w * 1000 + it.speaker) }
        voices += d.voices.map { Voice(w * 1000 + it.speaker, it.embedding, it.weightMs) }
    }
    return mergeSpeakers(Diarization(turns, voices))
}

const val CLUSTER_THRESHOLD = 1.1f
const val MIN_DURATION_ON = 0.3f
const val MIN_DURATION_OFF = 0.5f

fun diarization(pcm: FloatArray, rate: Int, turns: List<Turn>, embed: (FloatArray) -> FloatArray): Diarization {
    val voices = turns.filter { it.endMs - it.startMs >= VOICE_MIN_MS }.groupBy { it.speaker }.mapNotNull { (speaker, list) ->
        voiceOf(speaker, list.map { t ->
            val from = (t.startMs * rate / 1000).toInt().coerceIn(0, pcm.size)
            val to = (t.endMs * rate / 1000).toInt().coerceIn(from, pcm.size)
            embed(pcm.copyOfRange(from, to)) to (t.endMs - t.startMs)
        })
    }
    return Diarization(turns, voices)
}

const val VOICE_WINDOW_MS = 3_000L
const val VOICED_SHARE = 0.1
private const val LEVEL_MS = 30L

fun voicedWindows(pcm: FloatArray, rate: Int): List<FloatArray> {
    val step = (rate * LEVEL_MS / 1000).toInt()
    val frames = (0 until pcm.size / step).map { f ->
        val from = f * step
        from to sqrt((from until from + step).sumOf { pcm[it].toDouble() * pcm[it] } / step)
    }
    if (frames.isEmpty()) return emptyList()
    val loud = frames.map { it.second }.sorted()[(frames.size * 95 / 100).coerceAtMost(frames.size - 1)]
    val gate = maxOf(loud * VOICED_SHARE, 1e-3)
    val voiced = frames.filter { it.second >= gate }.flatMap { (from, _) -> (from until from + step).map { pcm[it] } }.toFloatArray()
    val size = (rate * VOICE_WINDOW_MS / 1000).toInt()
    return (0 until voiced.size / size).map { voiced.copyOfRange(it * size, (it + 1) * size) }
}

fun enrollVoice(pcm: FloatArray, rate: Int, embed: (FloatArray) -> FloatArray): Voiceprint? {
    val v = voiceOf(0, voicedWindows(pcm, rate).map { embed(it) to VOICE_WINDOW_MS }) ?: return null
    return Voiceprint(v.embedding, v.weightMs)
}
