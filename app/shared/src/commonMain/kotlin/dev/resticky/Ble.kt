package dev.resticky

import kotlinx.serialization.Serializable

const val BLE_SERVICE = "5a391e60-e572-4f44-920b-4a82eb105232"
const val BLE_RX = "5a391e61-e572-4f44-920b-4a82eb105232"
const val BLE_TX = "5a391e62-e572-4f44-920b-4a82eb105232"
const val FRAME_CHUNK = 1
const val FRAME_ACK = 2
const val FRAME_CHECKIN = 3
const val FRAME_WEATHER = 8
const val FRAME_AGENDA = 9
const val FRAME_WIFI_ADD = 10
const val FRAME_WIFI_FORGET = 11
const val FRAME_WEATHER_SET = 12
const val FRAME_TIME = 13
const val FRAME_DISPLAY = 14
const val FRAME_PHOTO = 17
const val FRAME_OTA_BEGIN = 18
const val FRAME_OTA_DATA = 19
const val FRAME_OTA_END = 20
const val FRAME_OTA_STATUS = 21
const val OTA_BLOCK = 4096
const val MAX_DATA_PAYLOAD = 2048
private const val CHUNK_HEADER = 31

class Frame(val type: Int, val payload: ByteArray)

class ChunkFrame(
    val device: String,
    val session: Long,
    val seq: Long,
    val final: Boolean,
    val startMs: Long,
    val durationMs: Long,
    val audio: ByteArray,
) {
    val sessionId: String get() = session.toString(16).padStart(8, '0')
}

private fun ByteArray.u32(at: Int): Long = (0 until 4).fold(0L) { v, i -> v or ((this[at + i].toLong() and 0xff) shl (8 * i)) }

private fun ByteArray.i64(at: Int): Long = (0 until 8).fold(0L) { v, i -> v or ((this[at + i].toLong() and 0xff) shl (8 * i)) }

class FrameReader {
    private var buf = ByteArray(0)

    fun clear() {
        buf = ByteArray(0)
    }

    val pending: Boolean get() = buf.isNotEmpty()

    fun feed(data: ByteArray): List<Frame> {
        buf += data
        val out = mutableListOf<Frame>()
        while (buf.size >= 4) {
            val n = buf.u32(0)
            if (n < 1 || buf.size < 4 + n) break
            out += Frame(buf[4].toInt() and 0xff, buf.copyOfRange(5, 4 + n.toInt()))
            buf = buf.copyOfRange(4 + n.toInt(), buf.size)
        }
        return out
    }
}

fun parseChunk(p: ByteArray): ChunkFrame? {
    if (p.size < CHUNK_HEADER) return null
    val length = p.u32(27)
    if (p.size - CHUNK_HEADER.toLong() != length) return null
    return ChunkFrame(
        device = p.copyOfRange(0, 6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        session = p.u32(6),
        seq = p.u32(10),
        final = p[14].toInt() != 0,
        startMs = p.i64(15),
        durationMs = p.u32(23),
        audio = p.copyOfRange(CHUNK_HEADER, p.size),
    )
}

fun frame(type: Int, payload: ByteArray): ByteArray {
    val n = payload.size + 1
    return ByteArray(4) { (n shr (8 * it)).toByte() } + type.toByte() + payload
}

fun ackFrame(session: Long, seq: Long): ByteArray =
    frame(FRAME_ACK, ByteArray(8) { (if (it < 4) session shr (8 * it) else seq shr (8 * (it - 4))).toByte() })

@Serializable
data class OtaBegin(val version: String, val size: Int)

@Serializable
data class OtaStatus(val state: String, val percent: Int = 0)

fun otaData(offset: Int, block: ByteArray): ByteArray = ByteArray(4) { (offset shr (8 * it)).toByte() } + block

fun otaFrames(image: ByteArray, block: Int = OTA_BLOCK): List<ByteArray> =
    (image.indices step block).map { frame(FRAME_OTA_DATA, otaData(it, image.copyOfRange(it, minOf(it + block, image.size)))) }
