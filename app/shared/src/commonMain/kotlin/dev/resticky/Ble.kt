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
const val FRAME_OTA_CANCEL = 22
const val FRAME_LISTS = 23
const val FRAME_TODO_SET = 24
const val FRAME_BUZZ_TEST = 25
const val LISTS_MAX = 40 * 1024
private const val LISTS_FIELD = 300
const val OTA_WINDOW = 16 * 1024
const val MAX_FRAME = (1 shl 20) + 64
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
            if (n < 1 || n > MAX_FRAME) {
                buf = ByteArray(0)
                break
            }
            if (buf.size < 4 + n) break
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
data class OtaStatus(val state: String, val percent: Int = 0, val offset: Long = 0)

fun otaMessage(state: String): String = when (state) {
    "busy" -> "The Sticky is busy with another update"
    "battery" -> "Charge the Sticky to 20 % or connect USB first"
    "cancelled" -> "Cancelled on the Sticky"
    else -> "The Sticky reported an error"
}

fun otaWindowOpen(sentEnd: Long, acked: Long, next: Int, window: Int = OTA_WINDOW): Boolean = sentEnd + next - acked <= window

fun otaData(offset: Int, block: ByteArray): ByteArray = ByteArray(4) { (offset shr (8 * it)).toByte() } + block

@Serializable
data class Lists(val transcripts: List<TranscriptItem>, val todos: TodoList)

@Serializable
data class TodoSet(val id: String, val done: Boolean)

private fun String.cut(max: Int): String {
    val cps = codePointList()
    return if (cps.size <= max) this else cps.take(max).joinToString("") { it.codePointString() } + "..."
}

fun listsJson(transcripts: List<TranscriptItem>, todos: TodoList, max: Int = LISTS_MAX): String {
    fun encode(l: Lists) = hubJson.encodeToString(l)
    var lists = Lists(transcripts, todos)
    if (encode(lists).encodeToByteArray().size <= max) return encode(lists)
    lists = lists.copy(transcripts = transcripts.map { it.copy(summary = it.summary.cut(LISTS_FIELD), text = it.text.cut(LISTS_FIELD)) })
    while (encode(lists).encodeToByteArray().size > max) {
        val t = lists.transcripts
        val d = lists.todos
        lists = when {
            t.isNotEmpty() -> lists.copy(transcripts = t.dropLast(1))
            d.done.isNotEmpty() -> lists.copy(todos = d.copy(done = d.done.dropLast(1)))
            else -> lists.copy(todos = d.copy(open = d.open.dropLast(1)))
        }
    }
    return encode(lists)
}

fun todoTarget(id: String): Pair<String, Int>? {
    val session = id.substringBeforeLast('-', "")
    val index = id.substringAfterLast('-').toIntOrNull() ?: return null
    return if (ID_PATTERN.matches(session) && index >= 0) session to index else null
}
