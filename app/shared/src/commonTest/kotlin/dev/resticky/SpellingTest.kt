package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SpellingTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-spelling-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private val text = "Speaker A: Christa, can you ask Jon about the Kubernetes plan? We will see Mark and Grace later."
    private val candidates = listOf("Krista", "John", "Kubernetes", "Marc", "Grace Hopper")

    private fun fixes(vararg p: Pair<String, String>, refused: Set<Pair<String, String>> = emptySet()) =
        acceptCorrections(p.map { Correction(it.first, it.second, "") }, text, candidates, refused).map { it.from to it.to }

    @Test
    fun soundAlikeNamesFromTheCandidatesAreAccepted() {
        assertEquals(listOf("Christa" to "Krista", "Jon" to "John"), fixes("Christa" to "Krista", "Jon" to "John"))
    }

    @Test
    fun onlyCandidatesAreTargets() {
        assertEquals(emptyList(), fixes("Christa" to "Kristen"))
    }

    @Test
    fun commonWordsAreNeverChanged() {
        assertEquals(emptyList(), fixes("Mark" to "Marc", "will" to "John", "Grace" to "Grace Hopper"))
        for (w in listOf("will", "may", "mark", "grace", "bill", "art", "hope", "joy")) assertTrue(w in COMMON_WORDS)
    }

    @Test
    fun theWordMustBeInTheTranscriptAndSoundAlike() {
        assertEquals(emptyList(), fixes("Kristal" to "Krista", "plan" to "Kubernetes", "Kubernetes" to "Kubernetes"))
        assertEquals(emptyList(), fixes("Christa" to "Krista", refused = setOf("christa" to "krista")))
    }

    @Test
    fun phoneticCheck() {
        assertTrue(soundsLike("Christa", "Krista"))
        assertTrue(soundsLike("Jon", "John"))
        assertTrue(soundsLike("Sean", "Shawn"))
        assertTrue(soundsLike("Geoff", "Jeff"))
        assertTrue(soundsLike("Cooper Netties", "Kubernetes"))
        assertFalse(soundsLike("Ann", "Ben"))
        assertFalse(soundsLike("Paris", "Krista"))
        assertFalse(soundsLike("Mia", "Maya Lopez"))
        assertEquals("krst", skeleton("Christa"))
    }

    @Test
    fun recurringProperNounsBecomeTerms() {
        val now = "We moved the Zephyr launch. Ask Priya about Zephyr pricing and the Budget.\nBudget review"
        val others = listOf("the Zephyr board met and Priya agreed", "nothing here", "the budget is fine")
        assertEquals(listOf("Priya", "Zephyr"), recurringTerms(now, others))
        assertEquals(emptyList(), recurringTerms("Then Zephyr went. Monday Okay.", listOf("Then Zephyr went. Monday Okay.").map { "x " + it.lowercase() }))
    }

    @Test
    fun repeatedFixesBecomeRules() {
        fun session(id: String, at: Instant, vararg f: Fix) = Session(
            "d", id, at, at, 30_000, chunks = mutableMapOf("0" to "Krista said hi"), fixes = f.toMutableList(),
        )
        val t = Instant.parse("2026-11-11T09:00:00Z")
        val a = session("a", t, Fix("Christa", "Krista", 0))
        val b = session("b", t + 60.minutes, Fix("christa", "Krista", 0), Fix("Jon", "John", 0, undone = true))
        val c = session("c", t + 120.minutes, Fix("Jon", "John", 0, undone = true))
        assertEquals(3, conversations(listOf(a, b, c)).size)
        assertEquals(listOf(Rule("christa", "Krista")), repeatedFixes(conversations(listOf(a, b, c))))
    }

    @Test
    fun anAutoFixIsRecordedAndUndoneWithOneTap() {
        val s = Session(
            "ab", "01", Instant.parse("2026-11-11T09:00:00Z"), Instant.parse("2026-11-11T09:00:30Z"), 30_000,
            chunks = mutableMapOf("0" to "ask Christa now Christa"),
            segments = mutableMapOf("0" to listOf(Segment(0, 1000, "ask Christa", words = listOf(Word(0, 500, "ask"), Word(500, 1000, "Christa"))), Segment(1000, 2000, "now Christa"))),
        )
        assertEquals(2, s.autoFix(Rule("Christa", "Krista")) { true })
        assertEquals("ask Krista now Krista", s.transcript())
        assertEquals(listOf(Fix("Christa", "Krista", 0), Fix("Christa", "Krista", 1000)), s.fixes)
        assertTrue(s.undoFix(s.fixes[1]))
        assertEquals("ask Krista now Christa", s.transcript())
        assertEquals(setOf("christa" to "krista"), s.refusedFixes())
        assertFalse(s.undoFix(s.fixes[1]))
    }

    @Test
    fun theSchemaListsEveryKeyAndTheReplyParses() {
        val schema = hubJson.parseToJsonElement(SUMMARY_SCHEMA).jsonObject
        val keys = schema.getValue("properties").jsonObject.keys
        assertEquals(keys, schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(setOf("title", "summary", "todos", "speakers", "corrections"), keys)
        for (k in listOf("todos", "speakers", "corrections")) {
            val item = schema.getValue("properties").jsonObject.getValue(k).jsonObject.getValue("items") as JsonObject
            assertEquals(item.getValue("properties").jsonObject.keys, item.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
        val raw = """{"title":"Plan","summary":"S.","todos":[],"speakers":[{"speaker":" Speaker B ","name":"Krista","evidence":"this is Krista"}],""" +
            """"corrections":[{"from":"Christa","to":"Krista","evidence":"ask Christa"},{"from":"","to":"x","evidence":""}]}"""
        val s = parseSummary(raw, "this is Krista, ask Christa")
        assertEquals(listOf(SpeakerName("Speaker B", "Krista", "this is Krista")), s.speakers)
        assertEquals(listOf(Correction("Christa", "Krista", "ask Christa")), s.corrections)
        assertEquals(Summary("Plan", "S."), parseSummary("""{"title":"Plan","summary":"S.","todos":[]}""", "x"))
    }

    @Test
    fun thePromptAsksForNamesWithCandidates() {
        val p = summaryPrompt(SummaryContext(attendees = listOf("Krista Lee"), candidates = listOf("Krista Lee", "Zephyr")))
        assertTrue("invited to this meeting are: Krista Lee." in p)
        assertTrue("Krista Lee, Zephyr" in p)
        assertTrue("Return an empty corrections array." in summaryPrompt(SummaryContext()))
    }

    @Test
    fun pipelineFixesNamesLearnsTermsAndKeepsTheSummaryReady() = runTest {
        val store = Store(Path(root, "data"))
        val vocabulary = Vocabulary(Path(root, "vocabulary.json"))
        vocabulary.addTerm("Krista")
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0))
        val said = "ask Christa about the Zephyr plan $SPOKEN"
        var calls = 0
        val pipeline = Pipeline(
            store, { _, _ -> listOf(Segment(0, 1000, said)) },
            { _, c ->
                calls++
                assertTrue("Krista" in c.candidates && "Dana Fox" in c.attendees)
                Summary("Christa plan", "Christa asks.", corrections = listOf(Correction("Christa", "Krista", "ask Christa"), Correction("ask", "Krista", "")))
            },
            NO_SPEAKERS, vocabulary = vocabulary, attendees = { _, _ -> listOf("Dana Fox") },
        ) {}
        pipeline.drain()
        pipeline.drain()
        assertEquals(1, calls)
        val m = store.list("ab").single()
        assertTrue(m.ready)
        assertEquals("ask Krista about the Zephyr plan $SPOKEN", m.transcript())
        assertEquals("Krista plan", m.title)
        assertEquals("Krista asks.", m.summary)
        assertEquals(listOf(Fix("Christa", "Krista", 0)), m.fixes)
        assertTrue(vocabulary.data.value.terms.any { it.text == "Dana Fox" && it.source == TermSource.Calendar })
        store.update("ab", "01") { it.undoFix(it.fixes.single()) }
        pipeline.drain()
        assertEquals(2, calls)
        val undone = store.list("ab").single()
        assertEquals(said, undone.transcript())
        assertTrue(undone.ready)
    }

    @Test
    fun oldVocabularyFilesLoadAndAutoTermsAreLast() = runTest {
        val file = Path(root, "vocabulary.json")
        Store(root).write(file, """{"terms":[{"text":"Krista","source":"correction","last_used":"2026-01-01T00:00:00Z"}],"rules":[{"from":"Christa","to":"Krista"}]}""".encodeToByteArray())
        val v = Vocabulary(file)
        assertEquals(listOf("Krista"), v.terms())
        v.learnTerms(listOf("Zephyr", "Krista"), TermSource.Auto)
        assertEquals(TermSource.Correction, v.data.value.terms.single { it.text == "Krista" }.source)
        assertEquals("Krista, Zephyr.", v.prompt(emptyList(), emptyList()))
        v.learnRules(listOf(Rule("christa", "Kris"), Rule("Jon", "John")))
        assertEquals(listOf(Rule("Christa", "Krista"), Rule("Jon", "John")), v.rules())
    }
}
