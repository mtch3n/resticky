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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PipelineTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-pipeline-${Random.nextLong().toULong()}")
    private val unused = Summarizer { _, _ -> error("not used") }

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun words(n: Int) = List(n) { "word$it" }.joinToString(" ")

    @Test
    fun shortSpeechIsANoteWithoutTheModel() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, words(10))) }, unused, NO_SPEAKERS) {}.drain()
        val m = store.list("ab").single()
        assertEquals(SHORT_NOTE, m.title)
        assertEquals(words(10), m.summary)
        assertTrue(m.ready)
        assertEquals(SHORT_NOTE, transcriptItems(store.list("ab")).single().title)
    }

    @Test
    fun fourWordsAreNoSpeech() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, words(4))) }, unused, NO_SPEAKERS) {}.drain()
        assertEquals(NO_SPEECH, store.list("ab").single().title)
        assertEquals(emptyList(), transcriptItems(store.list("ab")))
    }

    @Test
    fun summaryForOlderInputIsDropped() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        val inputs = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val summarizer = Summarizer { t, _ ->
            inputs += t
            if (inputs.size == 1) store.addChunk("ab", "01", 1, true, 30_000, ByteArray(5))
            Summary("T${inputs.size}", "S")
        }
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, summarizer, NO_SPEAKERS) { logs += it }.drain()
        val m = store.list("ab").single()
        assertEquals(2, inputs.size)
        assertEquals("$SPOKEN $SPOKEN", inputs[1])
        assertEquals("T2", m.title)
        assertTrue(m.ready)
        assertTrue(logs.any { "summary dropped" in it })
    }

    @Test
    fun oneSummaryForSessionsOfOneConversation() = runTest {
        val store = Store(root)
        val now = Clock.System.now()
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4), capturedAt = now - 20.minutes)
        store.addChunk("ab", "02", 0, true, 30_000, ByteArray(4), capturedAt = now - 15.minutes)
        val inputs = mutableListOf<String>()
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { t, _ -> inputs += t; Summary("Both", "S", listOf(Todo("Ship it", "ship the billing report"))) }, NO_SPEAKERS) {}.drain()
        assertEquals(listOf("$SPOKEN\n$SPOKEN"), inputs)
        val head = store.list("ab").single { it.id == "01" }
        assertEquals("Both", head.title)
        assertEquals(listOf(Quote("ship the billing report", "01", 0)), head.todoQuotes)
        assertEquals("", store.list("ab").single { it.id == "02" }.title)
        val item = transcriptItems(store.list("ab")).single()
        assertEquals("Both", item.title)
        assertTrue(item.ready)
        assertEquals(listOf("01-0"), todoList(store.list("ab")).open.map { it.id })
    }

    @Test
    fun sessionClosesByCaptureTimeAfterAnOutboxBurst() = runTest {
        val store = Store(root)
        val now = Clock.System.now()
        store.addChunk("ab", "01", 0, false, 30_000, ByteArray(4), 1_000_000, now - 20.minutes)
        store.addChunk("ab", "01", 1, false, 30_000, ByteArray(5), 1_030_000)
        store.addChunk("ab", "02", 0, false, 30_000, ByteArray(4), 2_000_000, now - 1.minutes)
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { _, _ -> Summary("T", "S") }, NO_SPEAKERS) {}.drain()
        assertTrue(store.list("ab").single { it.id == "01" }.ready)
        assertTrue(!store.list("ab").single { it.id == "02" }.ready)
    }

    @Test
    fun rerunKeepsTicksByTodoText() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        val t1 = Instant.parse("2026-11-10T10:00:00Z")
        val t2 = Instant.parse("2026-11-10T11:00:00Z")
        store.update("ab", "01") {
            it.chunks["0"] = SPOKEN
            it.diarized = true
            it.ready = true
            it.todos = listOf("Send the report", "Call Sam", "Old task")
            it.done["0"] = t1
            it.done["2"] = t1
            it.todoChanges["0"] = t1
            it.todoChanges["1"] = t2
        }
        store.rerunAll("whisper/2")
        val sum = Summary("New", "S", listOf(Todo("Call Sam", "Sam checks"), Todo("send the report!", "ship the billing report"), Todo("Book a room", "the review meeting")))
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { _, _ -> sum }, NO_SPEAKERS) {}.drain()
        val m = store.list("ab").single()
        assertEquals(listOf("Call Sam", "send the report!", "Book a room"), m.todos)
        assertEquals(mapOf("1" to t1), m.done)
        assertEquals(mapOf("0" to t2, "1" to t1), m.todoChanges)
        assertTrue(m.ready)
    }

    @Test
    fun ticksOnASplitConversationUseItsOwnIds() = runTest {
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { _, _ -> emptyList() }, unused, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        store.update("ab", "01") {
            it.chunks["0"] = "x"
            it.splits["361000"] = Recap("Later", "S", listOf("A", "B"), ready = true)
        }
        val at = Clock.System.now()
        assertEquals(TodoState(true, at), hub.setTodo("ab", "01", 601, TodoState(true, at)))
        assertEquals(mapOf("1" to at), store.list("ab").single().splits.getValue("361000").done)
        assertEquals(null, hub.setTodo("ab", "01", 605, TodoState(true, at)))
        assertEquals(null, hub.setTodo("ab", "01", 0, TodoState(true, at)))
    }
}
