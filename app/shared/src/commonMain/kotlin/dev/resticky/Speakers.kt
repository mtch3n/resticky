package dev.resticky

import kotlin.math.sqrt

const val SPEAKER_MERGE = 0.95
const val VOICE_MIN_MS = 1500L
private const val FRAME_MS = 20L

class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

class Voice(val speaker: Int, val embedding: FloatArray, val weightMs: Long)

class Diarization(val turns: List<Turn>, val voices: List<Voice>)

fun interface Diarizer {
    suspend fun diarize(frames: ByteArray): Diarization
}

fun speakerLetter(speaker: Int): String =
    "Speaker " + if (speaker < 26) "${'A' + speaker}" else "${speaker + 1}"

private fun FloatArray.unit(): FloatArray {
    val n = sqrt(sumOf { it.toDouble() * it }).toFloat()
    return if (n == 0f) this else FloatArray(size) { this[it] / n }
}

private fun cosine(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] }

fun voiceOf(speaker: Int, parts: List<Pair<FloatArray, Long>>): Voice? {
    if (parts.isEmpty()) return null
    val dim = parts.first().first.size
    val sum = FloatArray(dim)
    parts.forEach { (e, w) -> val u = e.unit(); for (i in 0 until dim) sum[i] += u[i] * w }
    return Voice(speaker, sum.unit(), parts.sumOf { it.second })
}

fun mergeSpeakers(d: Diarization, minCosine: Double = SPEAKER_MERGE): List<Turn> {
    val voices = d.voices.associate { it.speaker to (it.embedding.unit() to it.weightMs) }.toMutableMap()
    val target = mutableMapOf<Int, Int>()
    fun root(s: Int): Int = target[s]?.let(::root) ?: s
    while (voices.size > 1) {
        val keys = voices.keys.sorted()
        var best: Triple<Int, Int, Double>? = null
        for (i in keys.indices) for (j in i + 1 until keys.size) {
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
    }
    val sorted = d.turns.sortedBy { it.startMs }
    val order = mutableMapOf<Int, Int>()
    return sorted.map { t ->
        val r = root(t.speaker)
        Turn(t.startMs, t.endMs, order.getOrPut(r) { order.size })
    }
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

suspend fun diarizeSession(frames: ByteArray, diarizer: Diarizer, windowMs: Long): List<Turn> {
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
