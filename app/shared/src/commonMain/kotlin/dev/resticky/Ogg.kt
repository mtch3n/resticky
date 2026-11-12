package dev.resticky

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

private const val SAMPLES_PER_FRAME = 960L
private const val PRE_SKIP = 312
private const val SERIAL = 0x52535459
private const val SILENT_TOC = 0x48
private const val OPUS_MAX_PACKET = 1275
const val MAX_CHUNK = 1 shl 20

fun opusFramesOk(frames: ByteArray): Boolean {
    var i = 0
    while (i + 2 <= frames.size) {
        val n = (frames[i].toInt() and 0xff) or ((frames[i + 1].toInt() and 0xff) shl 8)
        if (n > OPUS_MAX_PACKET) return false
        i += 2 + n
    }
    return i == frames.size
}

fun chunkAudioOk(audio: ByteArray): Boolean = audio.size <= MAX_CHUNK && opusFramesOk(audio)

private val crcTable = IntArray(256) { i ->
    var r = i shl 24
    repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
    r
}

private fun ByteArray.oggCrc(): Int = fold(0) { crc, b -> (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff] }

private class Writer {
    val bytes = mutableListOf<Byte>()
    fun u8(v: Int) = bytes.add(v.toByte())
    fun u16(v: Int) = repeat(2) { u8(v shr (8 * it)) }
    fun u32(v: Int) = repeat(4) { u8(v shr (8 * it)) }
    fun u64(v: Long) = repeat(8) { u8((v shr (8 * it)).toInt()) }
    fun raw(b: ByteArray) = bytes.addAll(b.toList())
}

private fun page(flags: Int, granule: Long, seq: Int, payload: ByteArray): ByteArray {
    val lacing = List(payload.size / 255) { 255 } + (payload.size % 255)
    val w = Writer()
    w.raw("OggS".encodeToByteArray())
    w.u8(0)
    w.u8(flags)
    w.u64(granule)
    w.u32(SERIAL)
    w.u32(seq)
    w.u32(0)
    w.u8(lacing.size)
    lacing.forEach(w::u8)
    w.raw(payload)
    val out = w.bytes.toByteArray()
    val crc = out.oggCrc()
    repeat(4) { out[22 + it] = (crc shr (8 * it)).toByte() }
    return out
}

fun opusFramesToOgg(frames: ByteArray, sampleRate: Int = 16000): ByteArray {
    val head = Writer().apply {
        raw("OpusHead".encodeToByteArray()); u8(1); u8(1); u16(PRE_SKIP); u32(sampleRate); u16(0); u8(0)
    }.bytes.toByteArray()
    val vendor = "resticky".encodeToByteArray()
    val tags = Writer().apply { raw("OpusTags".encodeToByteArray()); u32(vendor.size); raw(vendor); u32(0) }.bytes.toByteArray()
    val pages = mutableListOf(Triple(2, 0L, head), Triple(0, 0L, tags))
    var granule = 0L
    var toc = SILENT_TOC
    var i = 0
    while (i + 2 <= frames.size) {
        val n = (frames[i].toInt() and 0xff) or ((frames[i + 1].toInt() and 0xff) shl 8)
        i += 2
        if (i + n > frames.size) break
        granule += SAMPLES_PER_FRAME
        if (n > 0) toc = frames[i].toInt() and 0xfc
        pages += Triple(0, granule, if (n > 0) frames.copyOfRange(i, i + n) else byteArrayOf(toc.toByte()))
        i += n
    }
    val last = pages.removeLast()
    pages += Triple(last.first or 4, maxOf(last.second, granule), last.third)
    val out = Buffer()
    pages.forEachIndexed { seq, (flags, g, payload) -> out.write(page(flags, g, seq, payload)) }
    return out.readByteArray()
}
