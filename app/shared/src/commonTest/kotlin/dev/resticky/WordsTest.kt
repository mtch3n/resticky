package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class WordsTest {
    private fun w(text: String, start: Long, end: Long = start + 200) = Word(start, end, text)

    @Test
    fun tokensJoinIntoWords() {
        val words = wordsFromTokens(listOf(
            Token("[_BEG_]", 0, 0), Token(" Re", 0, 100), Token("start", 100, 300), Token(",", 300, 320),
            Token(" the", 400, 500), Token(" [", 600, 610), Token("BLANK_AUDIO", 610, 900), Token("]", 900, 910), Token("<|endoftext|>", 910, 910),
        ))
        assertEquals(listOf(Word(0, 320, "Restart,"), Word(400, 500, "the")), words)
    }

    @Test
    fun wordsAreStoredAsCompactArrays() {
        val seg = Segment(0, 1000, "hi you", words = listOf(Word(0, 400, "hi"), Word(500, 900, "you")))
        val json = hubJson.encodeToString(seg)
        assertEquals("""{"start_ms":0,"end_ms":1000,"text":"hi you","words":[[0,400,"hi"],[500,900,"you"]]}""", json)
        assertEquals(seg, hubJson.decodeFromString<Segment>(json))
    }

    @Test
    fun fillersFalseStartsAndRepeatsAreFound() {
        val words = listOf("Um,", "I", "I", "think", "you", "know,", "the", "ser-", "server", "is,", "uh,", "fine.").mapIndexed { i, t -> w(t, i * 300L) }
        assertEquals(
            listOf(true, true, false, false, true, true, false, true, false, false, true, false),
            fillers(words).toList(),
        )
        assertEquals("----- I think ----- the ----- server is, ----- fine.", cleanText(words))
    }

    @Test
    fun wordsThatOnlyLookLikeFillersStay() {
        val words = listOf("I", "mean", "it", "and", "you", "knew").mapIndexed { i, t -> w(t, i * 300L) }
        assertEquals(listOf(true, true, false, false, false, false), fillers(words).toList())
        assertEquals("Dash-cam footage", cleanText(listOf(w("Dash-cam", 0), w("footage", 300))))
    }

    @Test
    fun skipRangesCoverFillersAndLongSilence() {
        val s = Session("d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0,
            chunks = mutableMapOf("0" to "um hello there", "1" to "old text"),
            segments = mutableMapOf("0" to listOf(Segment(0, 9000, "um hello there", words = listOf(w("um", 1000, 1300), w("hello", 1300, 1700), w("there", 5000, 5400))))),
            chunkMs = mutableMapOf("0" to 10_000, "1" to 10_000))
        assertEquals(emptyList(), s.skipRanges(cleanUp = false, skipSilence = false))
        assertEquals(listOf(1000L..1300L), s.skipRanges(cleanUp = true, skipSilence = false))
        assertEquals(listOf(1950L..4750L, 5650L..9750L), s.skipRanges(cleanUp = false, skipSilence = true))
        assertEquals(listOf(1000L..1300L, 1950L..4750L, 5650L..9750L), s.skipRanges(cleanUp = true, skipSilence = true))
        assertEquals(1300L, nextPlay(1100, s.skipRanges(cleanUp = true, skipSilence = false)))
        assertEquals(null, nextPlay(1400, s.skipRanges(cleanUp = true, skipSilence = false)))
    }
}
