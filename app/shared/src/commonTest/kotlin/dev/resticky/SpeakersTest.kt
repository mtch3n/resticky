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

const val SPOKEN = "we agreed to ship the billing report on Friday and Sam checks the third quarter numbers before the review meeting with the team next week"

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
        val turns = mergeSpeakers(d).turns
        assertEquals(listOf(0, 1, 0, 2), turns.map { it.speaker })
        assertEquals(listOf(0L, 5000L, 9000L, 12_000L), turns.map { it.startMs })
    }

    @Test
    fun voicesBelowTheThresholdStaySeparate() {
        val d = Diarization(listOf(Turn(0, 1000, 0), Turn(1000, 2000, 1)), listOf(v(0, 1f, 0f), v(1, 0.5f, 0.87f)))
        assertEquals(listOf(0, 1), mergeSpeakers(d).turns.map { it.speaker })
    }

    @Test
    fun speakersWhoBarelyTalkJoinTheClosestMainVoice() {
        val d = Diarization(
            listOf(Turn(0, 60_000, 0), Turn(60_000, 120_000, 1), Turn(120_000, 125_000, 2), Turn(125_000, 126_000, 3)),
            listOf(v(0, 1f, 0f), v(1, 0f, 1f), v(2, 0.3f, 0.95f)),
        )
        assertEquals(listOf(0, 1, 1, 1), mergeSpeakers(d).turns.map { it.speaker })
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
        }, 40).turns
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
        assertEquals("Speaker A: hi\nSpeaker B: there ok\nSpeaker A: bye", conversations(listOf(s)).single().labelledTranscript())
        s.todos = listOf("Speaker A sends the list", "Speaker AB stays")
        s.summary = "Speaker A and Speaker B talk."
        s.renameSpeaker(0, " Priya ")
        assertEquals("Priya: hi\nSpeaker B: there ok\nPriya: bye", conversations(listOf(s)).single().labelledTranscript())
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
        Pipeline(store, { _, _ -> listOf(Segment(0, 500, "hello $SPOKEN"), Segment(500, 1000, "world $SPOKEN")) }, { t, _ -> input = t; Summary("T", "S") }, diarizer) {}.drain()
        val m = store.list("ab").single()
        assertTrue(m.diarized && m.ready)
        assertEquals(listOf(0, 1), m.segments.getValue("0").map { it.speaker })
        assertEquals("Speaker A: hello $SPOKEN\nSpeaker B: world $SPOKEN", input)
    }

    @Test
    fun rerunQueuesAudioAgainAndCleansTextWithoutAudio() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, ByteArray(4))
        store.addChunk("ab", "01", 1, true, 30_000, ByteArray(4))
        store.update("ab", "01") {
            it.chunks["0"] = "old text"
            it.chunks["1"] = "[inaudible] >> Yes. Yes. Yes. Yes."
            it.ready = true
            it.diarized = true
            it.title = "Old"
            it.todos = listOf("Blow the whistle")
            it.speakers["0"] = "Sam"
        }
        SystemFileSystem.delete(Path(store.dir("ab", "01"), "1.opus"))
        assertEquals(1, store.rerunAll("whisper/2"))
        val m = store.list("ab").single()
        assertEquals(PENDING, m.chunks["0"])
        assertEquals("[inaudible] >> Yes. Yes. Yes. Yes.", m.chunks["1"])
        assertEquals(false, m.ready)
        assertEquals(false, m.diarized)
        assertEquals("", m.input)
        assertEquals(listOf("Blow the whistle"), m.todos)
        assertTrue(m.speakers.isEmpty())
        assertEquals(0, store.rerunAll("whisper/2"))
        store.update("ab", "01") {
            it.chunks["0"] = "new text"
            it.chunkEngine["0"] = "whisper/2"
        }
        assertEquals(0, store.rerunAll("whisper/2"))
        assertEquals("new text", store.list("ab").single().chunks["0"])
        assertEquals(1, store.rerunAll("whisper/3"))
        assertEquals(PENDING, store.list("ab").single().chunks["0"])
    }

    @Test
    fun minorSpeakerWithAFarVoiceStays() {
        val d = Diarization(
            listOf(Turn(0, 60_000, 0), Turn(60_000, 120_000, 1), Turn(120_000, 126_000, 2)),
            listOf(v(0, 1f, 0f, 0f), v(1, 0f, 1f, 0f), v(2, 0.3f, 0.2f, 0.93f)),
        )
        assertEquals(listOf(0, 1, 2), mergeSpeakers(d).turns.map { it.speaker })
    }

    @Test
    fun onlyShortSpeakersWithoutAVoiceJoinByTime() {
        val d = Diarization(
            listOf(Turn(0, 60_000, 0), Turn(60_000, 64_000, 2), Turn(64_000, 120_000, 1), Turn(120_000, 127_000, 3)),
            listOf(v(0, 1f, 0f), v(1, 0f, 1f)),
        )
        assertEquals(listOf(0, 0, 1, 2), mergeSpeakers(d).turns.map { it.speaker })
    }

    @Test
    fun speakersWhoTalkAtOnceNeverMerge() {
        val close = Diarization(
            listOf(Turn(0, 10_000, 0), Turn(8_000, 20_000, 1)),
            listOf(v(0, 1f, 0f), v(1, 0.99f, 0.05f)),
        )
        assertEquals(listOf(0, 1), mergeSpeakers(close).turns.map { it.speaker })
        val brief = Diarization(
            listOf(Turn(0, 10_000, 0), Turn(9_500, 20_000, 1)),
            listOf(v(0, 1f, 0f), v(1, 0.99f, 0.05f)),
        )
        assertEquals(listOf(0, 0), mergeSpeakers(brief).turns.map { it.speaker })
        val minor = Diarization(
            listOf(Turn(0, 60_000, 0), Turn(30_000, 33_000, 1)),
            listOf(v(0, 1f, 0f)),
        )
        assertEquals(listOf(0, 1), mergeSpeakers(minor).turns.map { it.speaker })
    }

    @Test
    fun failedDiarizationIsTriedAgainThenFlagged() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        var calls = 0
        val pipeline = Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { _, _ -> Summary("T", "S") }, { calls++; error("no models") }) {}
        pipeline.drain()
        var m = store.list("ab").single()
        assertEquals(1, calls)
        assertTrue(!m.diarized && !m.speakersFailed && !m.ready)
        pipeline.drain()
        m = store.list("ab").single()
        assertEquals(2, calls)
        assertTrue(m.diarized && m.speakersFailed && m.ready)
        store.addChunk("ab", "01", 1, true, 1000, byteArrayOf(0, 0, 1, 0))
        assertTrue(!store.list("ab").single().speakersFailed)
    }

    @Test
    fun diarizationThatWorksOnTheRetryIsNotFlagged() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        var calls = 0
        val pipeline = Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { _, _ -> Summary("T", "S") }, {
            if (calls++ == 0) error("busy") else Diarization(listOf(Turn(0, 1000, 0)), emptyList())
        }) {}
        pipeline.drain()
        pipeline.drain()
        val m = store.list("ab").single()
        assertTrue(m.diarized && !m.speakersFailed && m.ready)
        assertEquals(0, m.segments.getValue("0").single().speaker)
    }
}

class WordSpeakersTest {
    private fun session(vararg segs: Segment) = Session("d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0,
        chunks = mutableMapOf("0" to segs.joinToString(" ") { it.text }), segments = mutableMapOf("0" to segs.toList()), chunkMs = mutableMapOf("0" to 30_000))

    private fun said(vararg w: Word) = Segment(w.first().startMs, w.last().endMs, w.joinToString(" ") { it.text }, words = w.toList())

    @Test
    fun yeahInsideALongTurnGetsTheOtherSpeaker() {
        val s = session(said(Word(4000, 4500, "so"), Word(4500, 4900, "we"), Word(5050, 5350, "yeah"), Word(6000, 6400, "ship")))
        s.assignSpeakers(listOf(Turn(0, 5000, 0), Turn(5000, 5400, 1), Turn(5400, 10_000, 0)))
        assertEquals(listOf(0 to "so we", 1 to "yeah", 0 to "ship"), s.lines().map { it.speaker to it.text })
        assertEquals(listOf(4000L, 5050L, 6000L), s.lines().map { it.atMs })
        assertEquals("Speaker A: so we\nSpeaker B: yeah\nSpeaker A: ship", conversations(listOf(s)).single().labelledTranscript())
    }

    @Test
    fun backchannelThatOverlapsALongTurnGetsTheShortSpeaker() {
        val turns = listOf(Turn(0, 20_000, 0), Turn(8000, 8600, 1))
        assertEquals(0, wordSpeaker(7800, 8100, turns, null))
        assertEquals(1, wordSpeaker(8100, 8400, turns, 0))
        assertEquals(0, wordSpeaker(8500, 8900, turns, 1))
        assertEquals(0, wordSpeaker(7900, 8500, listOf(Turn(0, 20_000, 0), Turn(8000, 11_000, 1)), null))
        assertEquals(0, wordSpeaker(8100, 8700, listOf(Turn(0, 20_000, 0), Turn(8000, 8600, 1)), null))
    }

    @Test
    fun wordBetweenTurnsTakesTheNearTurnElseThePreviousWord() {
        val turns = listOf(Turn(0, 1000, 0), Turn(2000, 3000, 1))
        assertEquals(0, wordSpeaker(1200, 1250, turns, 1))
        assertEquals(1, wordSpeaker(1700, 1800, turns, 0))
        assertEquals(1, wordSpeaker(1400, 1600, turns, 1))
        assertEquals(0, wordSpeaker(1400, 1600, turns, 0))
        assertEquals(0, wordSpeaker(1400, 1600, turns, null))
        assertEquals(-1, wordSpeaker(0, 10, emptyList(), null))
    }

    @Test
    fun segmentsSplitAtSpeakerChanges() {
        val seg = Segment(0, 3000, "Hi, Sam. Yes. Go on.", words = listOf(Word(0, 400, "Hi,"), Word(400, 900, "Sam."), Word(1000, 1300, "Yes."), Word(1500, 1800, "Go"), Word(1800, 2900, "on.")))
        val out = seg.splitBySpeaker(listOf(0, 0, 1, 0, 0))
        assertEquals(listOf("Hi, Sam.", "Yes.", "Go on."), out.map { it.text })
        assertEquals(listOf(0L to 900L, 1000L to 1300L, 1500L to 2900L), out.map { it.startMs to it.endMs })
        assertEquals(listOf(0, 1, 0), out.map { it.speaker })
        assertEquals(listOf(2, 1, 2), out.map { it.words.size })
        assertEquals(listOf(seg.copy(speaker = 1)), seg.splitBySpeaker(listOf(1, 1, 1, 1, 1)))
    }

    @Test
    fun segmentsWithoutWordsKeepTheOldRule() {
        val s = session(Segment(0, 6000, "long text without words"), Segment(6000, 7000, "ok"))
        s.assignSpeakers(listOf(Turn(0, 5000, 0), Turn(5000, 5400, 1), Turn(5400, 10_000, 0)))
        assertEquals(listOf(0 to "long text without words", 0 to "ok"), s.lines().map { it.speaker to it.text })
        val again = session(Segment(0, 6000, "long text without words"))
        assertEquals("long text without words", conversations(listOf(again)).single().labelledTranscript())
    }

    @Test
    fun shortTurnIsNotAbsorbedByTimeIntoTheSpeakerItInterrupts() {
        val turns = listOf(Turn(0, 60_000, 0), Turn(60_000, 60_500, 2), Turn(60_500, 120_000, 0), Turn(120_000, 180_000, 1))
        val voices = listOf(Voice(0, floatArrayOf(1f, 0f), 60_000), Voice(1, floatArrayOf(0f, 1f), 60_000))
        assertEquals(listOf(0, -1, 0, 1), mergeSpeakers(Diarization(turns, voices)).turns.map { it.speaker })
        val heard = turns.map { if (it.speaker == 2) Turn(it.startMs, it.endMs, 2, floatArrayOf(0.1f, 0.9f)) else it }
        assertEquals(listOf(0, 1, 0, 1), mergeSpeakers(Diarization(heard, voices)).turns.map { it.speaker })
        val far = turns.map { if (it.speaker == 2) Turn(it.startMs, it.endMs, 2, floatArrayOf(-1f, -0.1f)) else it }
        assertEquals(listOf(0, -1, 0, 1), mergeSpeakers(Diarization(far, voices)).turns.map { it.speaker })
    }

    @Test
    fun unknownSpeakerShowsAsQuestionMark() {
        val s = session(said(Word(0, 500, "so"), Word(5050, 5350, "yeah"), Word(6000, 6400, "ship")))
        s.assignSpeakers(listOf(Turn(0, 5000, 0), Turn(5000, 5400, -1), Turn(5400, 10_000, 0)))
        assertEquals(listOf(0, -1, 0), s.lines().map { it.speaker })
        assertEquals("?", s.speakerName(-1))
        assertEquals("Speaker A: so\n?: yeah\nSpeaker A: ship", conversations(listOf(s)).single().labelledTranscript())
    }

    @Test
    fun shortTurnsOfSpeakersWithoutAVoiceGetALowConfidenceEmbedding() {
        val pcm = FloatArray(10 * 16)
        val seen = mutableListOf<Int>()
        val d = diarization(pcm, 16, listOf(Turn(0, 2000, 0), Turn(2000, 2900, 1), Turn(2900, 3500, 2), Turn(3500, 4400, 0))) { seen += it.size; floatArrayOf(1f, 0f) }
        assertEquals(listOf(32, 14), seen)
        assertEquals(listOf(0), d.voices.map { it.speaker })
        assertEquals(listOf(false, true, false, false), d.turns.map { it.voice != null })
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
