package dev.resticky

import kotlinx.datetime.TimeZone
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExportTest {
    private val t0 = Instant.fromEpochSeconds(1_794_000_000)
    private val root = Path(SystemTemporaryDirectory, "resticky-export-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        if (SystemFileSystem.exists(root)) SystemFileSystem.list(root).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(root, mustExist = false)
    }

    private fun meeting() = Session(
        "d", "a", t0, t0, 60_000, final = true,
        chunks = mutableMapOf("0" to "Let us plan the launch. I will draft the budget.", "1" to "我们明天开会。"),
        title = "Launch plan",
        summary = "Mira and a colleague plan the launch.",
        todos = listOf("Draft the budget", "Book a room"),
        done = mutableMapOf("0" to t0),
        ready = true,
        place = "Office",
        segments = mutableMapOf(
            "0" to listOf(Segment(0, 4000, "Let us plan the launch.", 0), Segment(5000, 9000, " I will draft the budget.", 1)),
            "1" to listOf(Segment(2000, 6000, "我们明天开会。", 0)),
        ),
        speakers = mutableMapOf("0" to "Mira"),
        notes = mutableMapOf("0" to "Budget first"),
    )

    private val expected = """
        # Launch plan

        2026-11-06 21:20–21:21

        Place: Office

        ## Notes

        Budget first

        ## Summary

        Mira and a colleague plan the launch.

        ## To-dos

        - [x] Draft the budget
        - [ ] Book a room

        ## Transcript

        00:00 **Mira:** Let us plan the launch.

        00:05 **Speaker B:** I will draft the budget.

        00:32 **Mira:** 我们明天开会。

    """.trimIndent()

    @Test
    fun markdownHasRecapAndNamedTranscript() {
        val c = conversations(listOf(meeting())).single()
        assertEquals(expected, c.markdown(TimeZone.UTC))
        assertEquals("2026-11-06 2120 Launch plan.md", c.fileName(TimeZone.UTC))
    }

    @Test
    fun searchLooksAtRecapTranscriptAndNames() {
        val c = conversations(listOf(meeting())).single()
        listOf("", "launch PLAN", "colleague", "book a", "mira", "明天", "budget first").forEach { assertTrue(c.matches(it), it) }
        listOf("Friday", "明年").forEach { assertFalse(c.matches(it), it) }
    }

    @Test
    fun notesChangeTheInputAndThePrompt() {
        val s = meeting().apply { notes.clear() }
        val before = conversations(listOf(s)).single().input()
        s.setNotes(0, "Budget first")
        val c = conversations(listOf(s)).single()
        assertTrue(c.input() != before)
        assertTrue("give them weight" in summaryPrompt(c.context()) && "Budget first" in summaryPrompt(c.context()))
        assertFalse("give them weight" in summaryPrompt(SummaryContext()))
        s.setNotes(0, " ")
        assertEquals(before, conversations(listOf(s)).single().input())
    }

    @Test
    fun exportWritesOneFilePerConversation() {
        val s = meeting()
        val exporter = Exporter(root, TimeZone.UTC)
        assertEquals(1, exporter.export(conversations(listOf(s))))
        assertEquals(expected, read("2026-11-06 2120 Launch plan.md"))
        assertEquals(0, exporter.export(conversations(listOf(s))))
        s.title = "Launch: plan/v2"
        assertEquals(1, exporter.export(conversations(listOf(s))))
        assertEquals(listOf("2026-11-06 2120 Launch plan v2.md"), SystemFileSystem.list(root).map { it.name })
        s.ready = false
        s.title = "Other"
        assertEquals(0, exporter.export(conversations(listOf(s))))
    }

    private fun read(name: String) = SystemFileSystem.source(Path(root, name)).buffered().use { it.readString() }
}
