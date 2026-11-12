package dev.resticky.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScoreTest {
    @Test
    fun englishWordErrors() {
        val e = textErrors("Send the slides to Anna today.", "send the slide to anna")
        assertEquals(TextErrors(refWords = 6, wordErrors = 2), e)
    }

    @Test
    fun mixedTextScoresChineseByCharacterAndEnglishByWord() {
        val e = textErrors("我哋聽日 send the slides 啦", "我地聽日 send slides 啦")
        assertEquals(TextErrors(refWords = 3, wordErrors = 1, refChars = 5, charErrors = 1), e)
    }

    @Test
    fun insertionCountsInTheClassOfTheInsertedToken() {
        assertEquals(TextErrors(refWords = 2, wordErrors = 1, refChars = 0, charErrors = 0), textErrors("hello there", "hello there friend"))
        assertEquals(TextErrors(refWords = 2, wordErrors = 0, refChars = 0, charErrors = 1), textErrors("hello there", "hello 好 there"))
    }

    @Test
    fun fullWidthAndPunctuationAreNormalized() {
        assertEquals(TextErrors(refWords = 2, refChars = 2), textErrors("ＯＫ，好的！ Sure.", "ok 好的 sure"))
    }

    @Test
    fun ratesAreEmptyWithoutReferenceTokens() {
        val m = Score(textErrors("hello", "hello")).metrics()
        assertEquals(0.0, m["wer"])
        assertNull(m["cer"])
        assertNull(m["you_rate"])
    }

    @Test
    fun mappingIsOneToOneAndMaximizesMatchedTime() {
        val overlap = mapOf(
            ("A" to "Speaker A") to 10L,
            ("A" to "Speaker B") to 9L,
            ("B" to "Speaker A") to 8L,
            ("B" to "Speaker C") to 1L,
        )
        assertEquals(mapOf("A" to "Speaker B", "B" to "Speaker A"), bestMapping(overlap))
    }

    @Test
    fun mappingLeavesExtraReferenceSpeakersUnmapped() {
        val overlap = mapOf(("A" to "Speaker A") to 5L, ("B" to "Speaker A") to 3L)
        assertEquals(mapOf("A" to "Speaker A"), bestMapping(overlap))
    }

    @Test
    fun speakerErrorUsesBestMappingAndCountsMissedSpeech() {
        val reference = listOf(RefSegment(0, 4000, "Ann", "a"), RefSegment(4000, 6000, "Bob", "b"), RefSegment(6000, 8000, "Ann", "c"))
        val hub = listOf(HubSpan(0, 3000, "Speaker B"), HubSpan(3000, 6000, "Speaker A"), HubSpan(6000, 7000, "Speaker B"))
        val e = speakerErrors(reference, hub)
        assertEquals(8000, e.speechMs)
        assertEquals(1000, e.missedMs)
        assertEquals(6000, e.matchedMs)
        assertEquals(2.0 / 8, Score(speakers = e).metrics()["speaker_error"])
    }

    @Test
    fun unknownHubSpeakerNeverMatches() {
        val e = speakerErrors(listOf(RefSegment(0, 1000, "Ann", "a")), listOf(HubSpan(0, 1000, UNKNOWN_LABEL)))
        assertEquals(0, e.matchedMs)
        assertEquals(0, e.missedMs)
    }

    @Test
    fun youRateAndFalseYou() {
        val reference = listOf(RefSegment(0, 2000, "You", "a"), RefSegment(2000, 4000, "Ann", "b"))
        val hub = listOf(HubSpan(0, 1000, "You"), HubSpan(1000, 3000, "Speaker A"), HubSpan(3000, 4000, "You"))
        val m = Score(speakers = speakerErrors(reference, hub)).metrics()
        assertEquals(0.5, m["you_rate"])
        assertEquals(0.5, m["false_you"])
    }

    @Test
    fun scoreCaseJoinsSegmentsInTimeOrder() {
        val reference = Reference(listOf(RefSegment(1000, 2000, "B", "world"), RefSegment(0, 1000, "A", "hello")))
        val hub = listOf(RefSegment(0, 1000, "Speaker A", "hello"), RefSegment(1000, 2000, "Speaker B", "world"))
        val s = scoreCase(reference, hub, 0)
        assertEquals(0.0, s.metrics()["wer"])
        assertEquals(0.0, s.metrics()["speaker_error"])
    }

    @Test
    fun oggPacketsJoinsLacedSegments() {
        val frames = byteArrayOf(3, 0, 1, 2, 3, 0, 0) + byteArrayOf(0x2c, 1) + ByteArray(300) { 7 }
        val packets = oggPackets(dev.resticky.opusFramesToOgg(frames))
        assertEquals(listOf(3, 1, 300), packets.map { it.size })
    }
}
