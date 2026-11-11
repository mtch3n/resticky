package dev.resticky

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleTest {
    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val n = payload.size + 1
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), 0, type.toByte()) + payload
    }

    private fun chunkPayload(audio: ByteArray): ByteArray {
        val h = ByteArray(31)
        byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte()).copyInto(h)
        byteArrayOf(0x78, 0x56, 0x34, 0x12).copyInto(h, 6)
        h[10] = 7
        h[14] = 1
        h[15] = 0x30
        h[16] = 0x75
        h[23] = 0x30
        h[24] = 0x75
        h[27] = audio.size.toByte()
        return h + audio
    }

    @Test
    fun framesSplitOverNotificationsAreReassembled() {
        val stream = frame(FRAME_CHECKIN, "{\"device\":\"ab\"}".encodeToByteArray()) + frame(FRAME_CHUNK, chunkPayload(byteArrayOf(1, 2, 3)))
        val reader = FrameReader()
        val frames = stream.toList().chunked(7).flatMap { reader.feed(it.toByteArray()) }
        assertEquals(listOf(FRAME_CHECKIN, FRAME_CHUNK), frames.map { it.type })
        assertTrue(!reader.pending)
        val chunk = parseChunk(frames[1].payload)!!
        assertEquals("a1b2c3d4e5f6", chunk.device)
        assertEquals("12345678", chunk.sessionId)
        assertEquals(7, chunk.seq)
        assertTrue(chunk.final)
        assertEquals(30000, chunk.startMs)
        assertEquals(30000, chunk.durationMs)
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.audio)
    }

    @Test
    fun chunkWithWrongAudioLengthIsRejected() {
        val p = chunkPayload(byteArrayOf(1, 2, 3))
        assertEquals(null, parseChunk(p.copyOf(p.size - 1)))
    }

    @Test
    fun ackFrameIsLittleEndian() {
        assertContentEquals(
            byteArrayOf(9, 0, 0, 0, 2, 0x78, 0x56, 0x34, 0x12, 7, 0, 0, 0),
            ackFrame(0x12345678, 7),
        )
    }

    @Test
    fun dataFrameRoundTrips() {
        val json = hubJson.encodeToString(WifiAdd("Office-5G", "secret"))
        val frames = FrameReader().feed(frame(FRAME_WIFI_ADD, json.encodeToByteArray()))
        assertEquals(FRAME_WIFI_ADD, frames.single().type)
        assertEquals("{\"ssid\":\"Office-5G\",\"password\":\"secret\"}", frames.single().payload.decodeToString())
    }

    @Test
    fun displayFrameCarriesTheDisplaySettings() {
        val frames = FrameReader().feed(frame(FRAME_DISPLAY, hubJson.encodeToString(DisplayInfo("words", "week", "12", false)).encodeToByteArray()))
        assertEquals(14, frames.single().type)
        assertEquals("{\"clock_face\":\"words\",\"weather_style\":\"week\",\"hour_format\":\"12\",\"beep\":false}", frames.single().payload.decodeToString())
    }

    @Test
    fun checkinReplyHasDisplaySettings() {
        assertEquals(
            "{\"hub_version\":\"1\",\"utc_offset_min\":-300,\"clock_face\":\"dial\",\"weather_style\":\"sketch\",\"hour_format\":\"24\",\"beep\":true}",
            hubJson.encodeToString(CheckinReply("1", -300, CLOCK_DIAL, WEATHER_SKETCH, HOUR_24, true)),
        )
        assertEquals(listOf("dial", "digits", "words", "note", "flip"), CLOCK_FACES)
        assertEquals(listOf("sketch", "big", "journal", "week"), WEATHER_STYLES)
        assertEquals(listOf("24", "12"), HOUR_FORMATS)
    }
}
