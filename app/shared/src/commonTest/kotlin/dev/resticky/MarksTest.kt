package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MarksTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-marks-${Random.nextLong().toULong()}")
    private val device = "a1b2c3d4e5f6"
    private val noSummary = Summarizer { _, _ -> error("not used") }

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun session(chunks: Map<String, String>, segments: Map<String, List<Segment>> = emptyMap(), chunkMs: Map<String, Long> = emptyMap()) =
        Session(device, "ab", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, chunks = chunks.toMutableMap(),
            segments = segments.toMutableMap(), chunkMs = chunkMs.toMutableMap())

    @Test
    fun whisperJsonGivesSegments() {
        val json = """{"systeminfo":"x","result":{"language":"en"},"transcription":[
            {"timestamps":{"from":"00:00:00,000","to":"00:00:04,200"},"offsets":{"from":0,"to":4200},"text":" Hello there."},
            {"timestamps":{"from":"00:00:04,200","to":"00:00:09,000"},"offsets":{"from":4200,"to":9000},"text":" [BLANK_AUDIO]"}]}"""
        val raw = parseWhisperJson(json)
        assertEquals(listOf(Segment(0, 4200, " Hello there."), Segment(4200, 9000, " [BLANK_AUDIO]")), raw)
        assertEquals(listOf(Segment(0, 4200, "Hello there.")), cleanSegments(raw))
    }

    @Test
    fun linesUseChunkOffsetsAndKeepOldChunksAsOneBlock() {
        val s = session(
            mapOf("0" to "old words", "1" to "one two", "2" to PENDING, "10" to "late"),
            mapOf("1" to listOf(Segment(0, 2000, "one"), Segment(2500, 4000, "two"))),
            mapOf("0" to 30_000, "1" to 20_000),
        )
        assertEquals(mapOf("0" to 0L, "1" to 30_000L, "2" to 50_000L, "10" to 290_000L), s.offsets())
        assertEquals(listOf(Line(0, "old words"), Line(30_000, "one"), Line(32_500, "two"), Line(290_000, "late")), s.lines())
        assertEquals("old words one two late", s.transcript())
    }

    @Test
    fun marksAreDedupedAndSorted() {
        val s = session(mapOf("0" to "a"))
        assertEquals(2, s.addMarks(listOf(9000, 3000, 9000)))
        assertEquals(0, s.addMarks(listOf(3000, -5)))
        assertEquals(1, s.addMarks(listOf(1000)))
        assertEquals(listOf(1000L, 3000L, 9000L), s.marks)
    }

    @Test
    fun momentsHoldTheTwoMinutesBeforeEachMark() {
        val s = session(
            mapOf("0" to "", "1" to "", "2" to "", "3" to "", "4" to ""),
            (0..4).associate { "$it" to listOf(Segment(0, 15_000, "c${it}a"), Segment(15_000, 30_000, "c${it}b")) },
        )
        s.addMarks(listOf(140_000))
        assertEquals(listOf("02:20 c0b c1a c1b c2a c2b c3a c3b c4a c4b"), conversations(listOf(s)).single().moments())
        val prompt = summaryPrompt(SummaryContext(moments = conversations(listOf(s)).single().moments()))
        assertTrue("- 02:20 c0b" in prompt)
        assertFalse("important" in summaryPrompt(SummaryContext()))
    }

    @Test
    fun dtxFramesKeepTheirTimeInOgg() {
        val ogg = opusFramesToOgg(byteArrayOf(0, 0, 2, 0, 0x5b, 0x11, 0, 0))
        val pages = ogg.indices.filter { ogg.size >= it + 4 && ogg.copyOfRange(it, it + 4).decodeToString() == "OggS" }
        assertEquals(5, pages.size)
        val last = pages.last()
        assertEquals(2880L, (0 until 8).fold(0L) { v, i -> v or ((ogg[last + 6 + i].toLong() and 0xff) shl (8 * i)) })
        assertEquals(0x48.toByte(), ogg[pages[2] + 28])
        assertEquals(0x58.toByte(), ogg[last + 28])
    }

    @Test
    fun timeLabels() {
        assertEquals("00:00", timeLabel(0))
        assertEquals("01:05", timeLabel(65_999))
        assertEquals("1:11:12", timeLabel(4_272_000))
    }

    @Test
    fun markBeforeTheFirstChunkIsKept() = runTest {
        val store = Store(root)
        assertEquals(2, store.addMarks(device, "5e55", listOf(4000, 1000)))
        assertEquals(0, store.addMarks(device, "5e55", listOf(1000)))
        assertEquals(Added.SESSION, store.addChunk(device, "5e55", 0, false, 30_000, ByteArray(4)))
        assertEquals(Added.CHUNK, store.addChunk(device, "5e55", 1, true, 12_000, ByteArray(4)))
        val m = store.list(device).single()
        assertEquals(listOf(1000L, 4000L), m.marks)
        assertEquals(mapOf("0" to 30_000L, "1" to 12_000L), m.chunkMs)
        assertEquals(42_000, m.durationMs)
        assertEquals(1, transcriptItems(store.list(device)).size)
    }

    @Test
    fun sessionWithOnlyMarksIsHiddenAndNotSummarized() = runTest {
        val store = Store(root)
        store.addMarks(device, "77", listOf(500))
        assertEquals(emptyList(), transcriptItems(store.list(device)))
        Pipeline(store, { _, _ -> emptyList() }, noSummary, NO_SPEAKERS) {}.drain()
        assertFalse(store.list(device).single().ready)
    }

    @Test
    fun pipelineStoresSegments() = runTest {
        val store = Store(root)
        store.addChunk(device, "aa", 0, false, 30_000, ByteArray(4))
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, " hi [BLANK_AUDIO]"), Segment(1000, 2000, " [BLANK_AUDIO]"), Segment(2000, 3000, " there")) }, noSummary, NO_SPEAKERS) {}.drain()
        val m = store.list(device).single()
        assertEquals("hi there", m.chunks["0"])
        assertEquals(listOf(Segment(0, 1000, "hi"), Segment(2000, 3000, "there")), m.segments["0"])
    }
}
