package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhrasesTest {
    private val rate = 1000

    @Test
    fun shortPausesMergeAndCutsFallInTheMiddleOfAPause() {
        val regions = listOf(Piece(0, 1500), Piece(1700, 3000), Piece(4000, 7000), Piece(7600, 10_500), Piece(11_000, 12_000))
        assertEquals(listOf(Piece(0, 7300), Piece(7300, 13_000)), phrases(regions, 13_000, rate))
        assertEquals(listOf(Piece(0, 9000)), phrases(listOf(Piece(0, 2500), Piece(2700, 9000)), 9000, rate))
    }

    @Test
    fun longSpeechIsNeverCut() {
        assertEquals(listOf(Piece(0, 20_000)), phrases(listOf(Piece(500, 19_500)), 20_000, rate))
        assertEquals(listOf(Piece(0, 5000)), phrases(emptyList(), 5000, rate))
        assertEquals(emptyList<Piece>(), phrases(emptyList(), 0, rate))
    }

    @Test
    fun aShortLastPhraseJoinsThePreviousOne() {
        assertEquals(listOf(Piece(0, 9500)), phrases(listOf(Piece(0, 7500), Piece(8000, 9000)), 9500, rate))
    }

    @Test
    fun windowRegionsUseWindowTimes() {
        val w = SpeechWindow(listOf(Piece(1000, 3000), Piece(10_000, 12_000)))
        val regions = listOf(Piece(1250, 2750), Piece(9000, 10_500), Piece(11_000, 11_750), Piece(20_000, 21_000))
        assertEquals(listOf(Piece(250, 1750), Piece(2000, 2500), Piece(3000, 3750)), w.speechIn(regions))
    }

    @Test
    fun languageUsesTheTagAndTheChineseShare() {
        assertTrue(isChinese("zh", "我们明天开会"))
        assertTrue(isChinese("en", "我们明天开会"))
        assertFalse(isChinese("yue", "we ship the report on friday"))
        assertTrue(isChinese("yue", "我哋聽日 send the slides 啦"))
        assertFalse(isChinese("en", "send the slides 啦"))
        assertFalse(isChinese("zh", ""))
    }

    @Test
    fun englishRunsGoToWhisperWithTimesFromTheWindow() {
        val phrases = listOf(Piece(0, 3000), Piece(3000, 6000), Piece(6000, 9000), Piece(9000, 12_000))
        val heard = listOf(
            Heard("en", listOf(Word(100, 900, "hello"))),
            Heard("zh", listOf(Word(200, 400, "你"), Word(400, 600, "好"))),
            Heard("yue", listOf(Word(0, 500, "fine"))),
            Heard("en", emptyList()),
        )
        val asked = mutableListOf<Piece>()
        val routed = routePhrases(phrases, heard, rate) { span ->
            asked += span
            listOf(Segment(100, 900, "Hello", words = listOf(Word(100, 900, "Hello"))))
        }
        assertEquals(listOf(Piece(0, 3000), Piece(6000, 12_000)), asked)
        assertEquals(listOf("whisper:en", "sensevoice:zh", "whisper:yue/en"), routed.routes)
        assertEquals(listOf(100L, 3000L, 6100L), routed.segments.map { it.startMs })
        assertEquals(listOf(Word(3200, 3400, "你"), Word(3400, 3600, "好")), routed.segments[1].words)
        assertEquals(6000L, routed.segments[1].endMs)
        assertEquals(listOf(Word(6100, 6900, "Hello")), routed.segments[2].words)
    }
}
