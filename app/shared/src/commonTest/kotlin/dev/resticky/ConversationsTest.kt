package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConversationsTest {
    private val t0 = Instant.fromEpochSeconds(1_794_000_000)

    private fun session(id: String, start: Instant, texts: List<String>, segments: Map<String, List<Segment>> = emptyMap()) = Session(
        "d", id, start, start, texts.size * CHUNK_MS, final = true,
        chunks = texts.withIndex().associate { "${it.index}" to it.value }.toMutableMap(),
        segments = segments.toMutableMap(),
    )

    @Test
    fun sessionsLessThanTenMinutesApartAreOneConversation() {
        val a = session("a", t0, listOf("one", "two"))
        val b = session("b", t0 + 6.minutes, listOf("three"))
        val c = session("c", t0 + 6.minutes + 30.seconds + 10.minutes, listOf("four"))
        val list = conversations(listOf(c, a, b))
        assertEquals(listOf(listOf("c"), listOf("a", "b")), list.map { v -> v.parts.map { it.session.id } })
        assertEquals("one two three", list[1].transcript())
        assertEquals(t0, list[1].start)
        assertEquals("a@0", list[1].key)
        assertEquals(2, transcriptItems(listOf(a, b, c)).size)
    }

    @Test
    fun otherDevicesAndEmptySessionsStaySeparate() {
        val a = session("a", t0, listOf("one"))
        val b = Session("e", "b", t0 + 1.minutes, t0, 30_000, chunks = mutableMapOf("0" to "two"))
        val marksOnly = Session("d", "m", t0 + 1.minutes, t0, 0, chunks = mutableMapOf(), marks = mutableListOf(5))
        assertEquals(2, conversations(listOf(a, b, marksOnly)).size)
    }

    @Test
    fun fiveMinutesWithoutSpeechSplitASession() {
        val texts = List(13) { "" }.toMutableList().apply { this[0] = "hello there"; this[12] = "new topic" }
        val s = session("s", t0, texts, mapOf("0" to listOf(Segment(0, 5000, "hello there")), "12" to listOf(Segment(1000, 4000, "new topic"))))
        assertEquals(listOf(0L to 361_000L, 361_000L to 390_000L), s.pieces())
        val list = conversations(listOf(s))
        assertEquals(listOf("new topic", "hello there"), list.map { it.transcript() })
        assertEquals("s@361000", list[0].key)
        assertEquals(t0 + 361.seconds, list[0].start)
        assertEquals("s-600", list[0].todoId(0))
        assertEquals("s-1", list[1].todoId(1))
        val pending = session("p", t0, texts.toMutableList().apply { this[6] = PENDING }, s.segments)
        assertEquals(1, pending.pieces().size)
    }

    @Test
    fun shortSilenceDoesNotSplit() {
        val texts = List(10) { "" }.toMutableList().apply { this[0] = "a"; this[9] = "b" }
        assertEquals(1, session("s", t0, texts).pieces().size)
    }

    @Test
    fun captureTimeComesFromChunkStarts() {
        val s = session("s", t0, listOf("a", "b"))
        s.chunkStart["0"] = 1_000_000
        s.chunkStart["1"] = 1_090_000
        assertEquals(90_000 + 5_000, s.captureMs(35_000))
        assertEquals(t0 + 120.seconds, s.wallAt(s.endMs()))
        s.final = false
        s.lastChunk = t0 + 30.minutes
        assertFalse(s.closed(t0 + 49.minutes))
        assertTrue(s.closed(t0 + 50.minutes))
        assertEquals(5_000, session("x", t0, listOf("a")).captureMs(5_000))
    }

    @Test
    fun legacyReadyRecapIsReadyOnlyForTheWholeSession() {
        val a = session("a", t0, listOf("one")).apply { ready = true; title = "Old" }
        assertTrue(conversations(listOf(a)).single().ready())
        val b = session("b", t0 + 2.minutes, listOf("two"))
        assertFalse(conversations(listOf(a, b)).single().ready())
        a.input = "x"
        assertFalse(conversations(listOf(a)).single().ready())
    }

    @Test
    fun inputChangesWithTheTextButNotWithSpeakerNames() {
        val a = session("a", t0, listOf("one two"), mapOf("0" to listOf(Segment(0, 1000, "one two", speaker = 0))))
        val before = conversations(listOf(a)).single().input()
        a.renameSpeaker(0, "Sam")
        assertEquals(before, conversations(listOf(a)).single().input())
        assertEquals("Sam: one two", conversations(listOf(a)).single().labelledTranscript())
        a.chunks["1"] = "three"
        assertTrue(before != conversations(listOf(a)).single().input())
    }

    @Test
    fun todoIndexFindsTheRecap() {
        val s = session("s", t0, listOf("a"))
        s.splits["361000"] = Recap(todos = listOf("x"))
        assertEquals(0L to 3, s.todoRecap(3))
        assertEquals(361_000L to 2, s.todoRecap(602))
        assertNull(s.todoRecap(702))
    }

    @Test
    fun quotesGetTheirSessionTime() {
        val words = listOf(Word(0, 400, "please"), Word(500, 900, "send"), Word(1000, 1400, "the"), Word(1500, 1900, "report."))
        val a = session("a", t0, listOf("hi", "please send the report."), mapOf("1" to listOf(Segment(0, 2000, "please send the report.", 0, words))))
        val c = conversations(listOf(a)).single()
        assertEquals(Quote("send the report", "a", 30_500), c.quote("send the report"))
        assertEquals(Quote("buy a boat"), c.quote("buy a boat"))
    }
}
