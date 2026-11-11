package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

val NO_SPEAKERS = Diarizer { Diarization(emptyList(), emptyList()) }

class SpeakersTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-speakers-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun v(speaker: Int, vararg e: Float, w: Long = 10_000) = Voice(speaker, e, w)

    @Test
    fun closeVoicesMergeAndSpeakersAreNumberedByFirstTurn() {
        val d = Diarization(
            listOf(Turn(5000, 9000, 7), Turn(0, 4000, 3), Turn(9000, 12_000, 5), Turn(12_000, 13_000, 9)),
            listOf(v(3, 1f, 0f, 0f), v(5, 0.99f, 0.05f, 0f), v(7, 0f, 1f, 0f)),
        )
        val turns = mergeSpeakers(d)
        assertEquals(listOf(0, 1, 0, 2), turns.map { it.speaker })
        assertEquals(listOf(0L, 5000L, 9000L, 12_000L), turns.map { it.startMs })
    }

    @Test
    fun voicesBelowTheThresholdStaySeparate() {
        val d = Diarization(listOf(Turn(0, 1000, 0), Turn(1000, 2000, 1)), listOf(v(0, 1f, 0f), v(1, 0.9f, 0.43f)))
        assertEquals(listOf(0, 1), mergeSpeakers(d).map { it.speaker })
    }

    @Test
    fun segmentGetsTheSpeakerWithTheMostOverlap() {
        val turns = listOf(Turn(0, 3000, 0), Turn(2500, 9000, 1))
        assertEquals(1, speakerAt(2000, 6000, turns, -1))
        assertEquals(1, speakerAt(9500, 9800, turns, 1))
        assertEquals(1, speakerAt(9500, 9800, turns, -1))
        assertEquals(-1, speakerAt(0, 10, emptyList(), -1))
    }

    @Test
    fun windowsSplitOnFrames() {
        val frames = ByteArray(0) + List(5) { byteArrayOf(1, 0, 9) }.reduce { a, b -> a + b }
        val w = frameWindows(frames, 40)
        assertEquals(listOf(0L, 40L, 80L), w.map { it.first })
        assertEquals(listOf(6, 6, 3), w.map { it.second.size })
    }

    @Test
    fun windowsAreDiarizedSeparatelyAndJoinedByVoice() = runTest {
        val frames = List(4) { byteArrayOf(0, 0) }.reduce { a, b -> a + b }
        var call = 0
        val turns = diarizeSession(frames, {
            val w = call++
            Diarization(listOf(Turn(0, 30, 0), Turn(30, 40, 1)), listOf(v(0, 1f, 0f), v(1, 0f, if (w == 0) 1f else -1f)))
        }, 40)
        assertEquals(listOf(0L to 0, 30L to 1, 40L to 0, 70L to 2), turns.map { it.startMs to it.speaker })
    }

    @Test
    fun speakersGoToSegmentsAndTheSummaryInput() {
        val s = Session("d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0,
            chunks = mutableMapOf("0" to "hi there ok", "1" to "bye"),
            segments = mutableMapOf("0" to listOf(Segment(0, 1000, "hi"), Segment(1000, 2000, "there"), Segment(2000, 3000, "ok")), "1" to listOf(Segment(0, 1000, "bye"))),
            chunkMs = mutableMapOf("0" to 3000))
        s.assignSpeakers(listOf(Turn(0, 1200, 0), Turn(1200, 2600, 1), Turn(3000, 4000, 0)))
        assertEquals(listOf(0, 1, 1, 0), s.lines().map { it.speaker })
        assertEquals("Speaker A: hi\nSpeaker B: there ok\nSpeaker A: bye", s.labelledTranscript())
        s.todos = listOf("Speaker A sends the list", "Speaker AB stays")
        s.summary = "Speaker A and Speaker B talk."
        s.renameSpeaker(0, " Priya ")
        assertEquals("Priya: hi\nSpeaker B: there ok\nPriya: bye", s.labelledTranscript())
        assertEquals(listOf("Priya sends the list", "Speaker AB stays"), s.todos)
        assertEquals("Priya and Speaker B talk.", s.summary)
        s.renameSpeaker(0, "")
        assertEquals("Speaker A", s.speakerName(0))
        assertTrue(s.speakers.isEmpty())
    }

    @Test
    fun pipelineDiarizesTheClosedSessionBeforeTheSummary() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        var input = ""
        val diarizer = Diarizer { Diarization(listOf(Turn(0, 600, 0), Turn(600, 1000, 1)), emptyList()) }
        Pipeline(store, { listOf(Segment(0, 500, "hello"), Segment(500, 1000, "world")) }, { t, _ -> input = t; Summary("T", "S") }, diarizer) {}.drain()
        val m = store.list("ab").single()
        assertTrue(m.diarized && m.ready)
        assertEquals(listOf(0, 1), m.segments.getValue("0").map { it.speaker })
        assertEquals("Speaker A: hello\nSpeaker B: world", input)
    }
}

class DiarizationTest {
    @Test
    fun longTurnsGiveOneVoicePerSpeaker() {
        val pcm = FloatArray(10 * 16) { it.toFloat() }
        val seen = mutableListOf<Int>()
        val d = diarization(pcm, 16, listOf(Turn(0, 2000, 0), Turn(2000, 2500, 1), Turn(2500, 6000, 0), Turn(6000, 8000, 1))) { seen += it.size; floatArrayOf(it.size.toFloat(), 0f) }
        assertEquals(listOf(32, 56, 32), seen)
        assertEquals(listOf(0, 1), d.voices.map { it.speaker })
        assertEquals(5500L, d.voices.first().weightMs)
        assertEquals(1f, d.voices.first().embedding[0])
    }
}
