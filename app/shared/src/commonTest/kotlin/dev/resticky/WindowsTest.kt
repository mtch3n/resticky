package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsTest {
    private val rate = 1000
    private val context = 2000

    @Test
    fun regionsArePaddedMergedAndPackedAtSilences() {
        val regions = listOf(Piece(3000, 13000), Piece(13300, 20000), Piece(25000, 31000), Piece(500, 1500))
        assertEquals(listOf(SpeechWindow(listOf(Piece(2750, 20250), Piece(24750, 31250)))), speechWindows(regions, 32000, context, rate))
        val full = speechWindows(listOf(Piece(2000, 20000), Piece(21000, 31000)), 32000, context, rate)
        assertEquals(listOf(SpeechWindow(listOf(Piece(1750, 20250))), SpeechWindow(listOf(Piece(20750, 31250)))), full)
    }

    @Test
    fun longSpeechIsSplitAtTheLimit() {
        val windows = speechWindows(listOf(Piece(1000, 32000)), 32000, context, rate)
        assertEquals(listOf(Piece(750, 28750), Piece(28750, 32000)), windows.flatMap { it.pieces })
        assertEquals(2, windows.size)
    }

    @Test
    fun windowTimesMapBackToTheChunk() {
        val w = SpeechWindow(listOf(Piece(1000, 3000), Piece(10000, 12000)))
        assertEquals(-1000, w.chunkMs(0, context, rate))
        assertEquals(500, w.chunkMs(1500, context, rate))
        assertEquals(8500, w.chunkMs(2500, context, rate))
        assertEquals(10000, w.chunkMs(9000, context, rate))
        assertEquals(listOf(2000L, 4000L), w.ends(rate))
    }

    @Test
    fun wordsInTheLeftContextAreDropped() {
        val w = SpeechWindow(listOf(Piece(1000, 5000)))
        val seg = Segment(0, 4000, "end of it new words", words = listOf(
            Word(0, 400, "end"), Word(400, 700, "of"), Word(800, 1300, "it"), Word(1500, 2000, "new"), Word(2100, 2600, "words"),
        ))
        val placed = placeSegments(listOf(seg), w, context, rate)
        assertEquals(listOf(Segment(0, 1600, "it new words", words = listOf(Word(0, 300, "it"), Word(500, 1000, "new"), Word(1100, 1600, "words")))), placed)
        assertEquals(emptyList(), placeSegments(listOf(Segment(0, 800, "old", words = listOf(Word(0, 800, "old")))), w, context, rate))
    }

    @Test
    fun speechInsideTheContextDoesNotCount() {
        assertEquals(1500, speechMs(listOf(Piece(0, 1000), Piece(1500, 2500), Piece(3000, 3500)), context, rate))
    }
}
