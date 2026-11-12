package dev.resticky

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleTest {
    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val n = payload.size + 1
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), 0, type.toByte()) + payload
    }

    private fun chunkPayload(audio: ByteArray): ByteArray {
        val h = ByteArray(43)
        byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0xe5.toByte(), 0xf6.toByte()).copyInto(h)
        byteArrayOf(0x78, 0x56, 0x34, 0x12).copyInto(h, 6)
        h[10] = 7
        h[14] = 1
        h[15] = 0x30
        h[16] = 0x75
        h[23] = 0x30
        h[24] = 0x75
        h[27] = audio.size.toByte()
        byteArrayOf(0x0d, 0xf0.toByte(), 0xad.toByte(), 0x8b.toByte()).copyInto(h, 31)
        1_794_000_000_000L.let { v -> for (i in 0 until 8) h[35 + i] = (v shr (8 * i)).toByte() }
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
        assertEquals("8badf00d", chunk.boot)
        assertEquals(1_794_000_000_000L, chunk.bootUnixMs)
        assertContentEquals(byteArrayOf(1, 2, 3), chunk.audio)
    }

    @Test
    fun chunkWithWrongAudioLengthIsRejected() {
        val p = chunkPayload(byteArrayOf(1, 2, 3))
        assertEquals(null, parseChunk(p.copyOf(p.size - 1)))
    }

    @Test
    fun chunkAudioMustBeWholePacketsAndAtMostOneMegabyte() {
        assertTrue(chunkAudioOk(ByteArray(0)))
        assertTrue(chunkAudioOk(byteArrayOf(0, 0, 2, 0, 9, 9)))
        assertFalse(chunkAudioOk(byteArrayOf(0, 0, 3, 0, 9, 9)))
        assertFalse(chunkAudioOk(byteArrayOf(0, 0, 0)))
        assertFalse(chunkAudioOk(byteArrayOf(0xff.toByte(), 0x7f) + ByteArray(0x7fff)))
        val full = ByteArray(MAX_CHUNK)
        assertTrue(chunkAudioOk(full))
        assertFalse(chunkAudioOk(full + byteArrayOf(0, 0)))
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
        val frames = FrameReader().feed(frame(FRAME_DISPLAY, hubJson.encodeToString(DisplayInfo("words", "week", "12", "off", 300, "clock", "22:00-07:00")).encodeToByteArray()))
        assertEquals(14, frames.single().type)
        assertEquals("{\"clock_face\":\"words\",\"weather_style\":\"week\",\"hour_format\":\"12\",\"sound\":\"off\",\"buzz_hz\":300,\"home\":\"clock\",\"night\":\"22:00-07:00\"}", frames.single().payload.decodeToString())
    }

    @Test
    fun checkinReplyHasDisplaySettings() {
        assertEquals(
            "{\"hub_version\":\"1\",\"utc_offset_min\":-300,\"clock_face\":\"dial\",\"weather_style\":\"sketch\",\"hour_format\":\"24\",\"sound\":\"beep\",\"buzz_hz\":175,\"home\":\"apps\",\"night\":\"off\"}",
            hubJson.encodeToString(CheckinReply("1", -300, CLOCK_DIAL, WEATHER_SKETCH, HOUR_24, SOUND_BEEP, BUZZ_HZ, HOME_APPS, NIGHT_OFF)),
        )
        assertEquals(listOf("dial", "digits", "words", "note", "flip"), CLOCK_FACES)
        assertEquals(listOf("sketch", "big", "journal", "week"), WEATHER_STYLES)
        assertEquals(listOf("24", "12"), HOUR_FORMATS)
    }
}

class OtaTest {
    @Test
    fun dataFrameCarriesTheOffsetLittleEndian() {
        val f = FrameReader().feed(frame(FRAME_OTA_DATA, otaData(0x01020304, byteArrayOf(9, 8)))).single()
        assertEquals(19, f.type)
        assertEquals(listOf(4, 3, 2, 1, 9, 8), f.payload.map { it.toInt() })
    }

    @Test
    fun beginAndStatusJson() {
        assertEquals("""{"version":"0.14.4","size":1234}""", hubJson.encodeToString(OtaBegin("0.14.4", 1234)))
        assertEquals(OtaStatus("writing", 35), hubJson.decodeFromString<OtaStatus>("""{"state":"writing","percent":35}"""))
        assertEquals(OtaStatus("ready"), hubJson.decodeFromString<OtaStatus>("""{"state":"ready"}"""))
        assertEquals(OtaStatus("writing", 3, 81920), hubJson.decodeFromString<OtaStatus>("""{"state":"writing","percent":3,"offset":81920}"""))
        assertEquals(22, FRAME_OTA_CANCEL)
    }

    @Test
    fun windowKeepsAtMost16KiBUnacknowledged() {
        assertTrue(otaWindowOpen(0, 0, 4096))
        assertTrue(otaWindowOpen(12288, 0, 4096))
        assertFalse(otaWindowOpen(16384, 0, 4096))
        assertTrue(otaWindowOpen(16384, 4096, 4096))
        assertFalse(otaWindowOpen(20480, 4096, 1))
    }

    @Test
    fun readerDropsAnOversizedLength() {
        val r = FrameReader()
        assertEquals(emptyList(), r.feed(byteArrayOf(-1, -1, -1, 0x7f, 1, 2)))
        assertFalse(r.pending)
        assertEquals(listOf(3), r.feed(frame(3, byteArrayOf(9))).map { it.type })
        assertEquals("The Sticky is busy with another update", otaMessage("busy"))
        assertEquals(listOf(18, 20, 21), listOf(FRAME_OTA_BEGIN, FRAME_OTA_END, FRAME_OTA_STATUS))
    }

    private fun item(i: Int, summary: String = "s$i") = TranscriptItem("10:0$i", "t$i", summary, emptyList(), true, "x".repeat(150), "", 0, 0)

    @Test
    fun listsCarryTranscriptsAndTodos() {
        val todos = TodoList(listOf(TodoItem("1a2b-0", "Call Bob", "Standup · 09:00")), emptyList())
        val json = listsJson(listOf(item(1)), todos)
        val back = hubJson.decodeFromString<Lists>(json)
        assertEquals(listOf(item(1)), back.transcripts)
        assertEquals(todos, back.todos)
        assertTrue(json.startsWith("""{"transcripts":["""))
        assertEquals(listOf(23, 24), listOf(FRAME_LISTS, FRAME_TODO_SET))
    }

    @Test
    fun listsDropOldestTranscriptsToFit() {
        val items = List(18) { item(it, "é".repeat(4000)) }
        val todos = TodoList(listOf(TodoItem("1a2b-0", "Call Bob", "Standup")), listOf(TodoItem("1a2b-1", "Mail", "Standup")))
        val json = listsJson(items, todos)
        assertTrue(json.encodeToByteArray().size <= LISTS_MAX)
        val back = hubJson.decodeFromString<Lists>(json)
        assertEquals(items.map { it.title }, back.transcripts.map { it.title })
        assertTrue(back.transcripts.all { it.summary.length <= 303 })
        assertEquals(todos, back.todos)
        val small = hubJson.decodeFromString<Lists>(listsJson(items, todos, 2000))
        assertTrue(small.transcripts.isNotEmpty() && small.transcripts.size < 18)
        assertEquals(items.take(small.transcripts.size).map { it.title }, small.transcripts.map { it.title })
        assertTrue(listsJson(items, todos, 200).encodeToByteArray().size <= 200)
    }

    @Test
    fun todoSetIdMapsToSessionAndIndex() {
        val set = hubJson.decodeFromString<TodoSet>("""{"id":"1a2b3c4d-2","done":true}""")
        assertEquals("1a2b3c4d" to 2, todoTarget(set.id))
        assertEquals(null, todoTarget("1a2b3c4d"))
        assertEquals(null, todoTarget("XYZ-1"))
        assertEquals(null, todoTarget("1a2b--1"))
    }
}
