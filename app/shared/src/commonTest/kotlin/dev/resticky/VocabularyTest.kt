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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VocabularyTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-vocab-${Random.nextLong().toULong()}")
    private val file = Path(root, "vocabulary.json")
    private var clock = Instant.parse("2026-11-11T09:00:00Z")
    private val tick = { clock = clock + 1.seconds; clock }

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    @Test
    fun promptPutsGroupsInOrderWithoutRepeats() {
        val p = whisperPrompt(listOf(listOf("Krista"), listOf("Sam", "krista"), listOf("Billing review"), listOf("QA")))
        assertEquals("Krista, Sam, Billing review, QA.", p)
    }

    @Test
    fun promptStopsAtTheCost() {
        val terms = List(100) { "Term$it" }
        val p = whisperPrompt(listOf(terms))
        assertTrue(p.length <= PROMPT_COST)
        assertTrue(p.startsWith("Term0, Term1, "))
        assertTrue("Term99" !in p)
        assertEquals("", whisperPrompt(listOf(listOf(" "))))
    }

    @Test
    fun promptCountsWideCharactersMore() {
        val p = whisperPrompt(listOf(listOf("王".repeat(130), "王".repeat(10))))
        assertEquals("王".repeat(130) + ".", p)
    }

    @Test
    fun ruleMatchesWholeWordsOnly() {
        val rules = listOf(Rule("Christa", "Krista"))
        assertEquals("Krista said Christabel and Krista's plan.", applyRules("christa said Christabel and CHRISTA's plan.", rules).value)
        assertEquals(2, applyRules("christa said Christabel and CHRISTA's plan.", rules).count)
    }

    @Test
    fun ruleKeepsPunctuationOnWords() {
        val words = listOf(Word(0, 100, "Ask"), Word(100, 200, "Christa,"), Word(200, 300, "then"), Word(300, 400, "(QI)."))
        val out = fixWords(words, listOf(Rule("christa", "Krista"), Rule("QI", "QA"))).value
        assertEquals(listOf(Word(0, 100, "Ask"), Word(100, 200, "Krista,"), Word(200, 300, "then"), Word(300, 400, "(QA).")), out)
    }

    @Test
    fun multiWordRuleJoinsWords() {
        val words = listOf(Word(0, 100, "the"), Word(100, 200, "Q"), Word(200, 300, "I."), Word(300, 400, "team"))
        val out = fixWords(words, listOf(Rule("q i", "QA"))).value
        assertEquals(listOf(Word(0, 100, "the"), Word(100, 300, "QA."), Word(300, 400, "team")), out)
        assertEquals("Sam and Krista Lee came", applyRules("Sam and christa  lee came", listOf(Rule("Christa Lee", "Krista Lee"))).value)
    }

    @Test
    fun segmentsGetTextFromFixedWords() {
        val s = Segment(0, 400, " hi Christa", words = listOf(Word(0, 100, "hi"), Word(100, 400, "Christa")))
        val out = applyRules(listOf(s), listOf(Rule("Christa", "Krista")))
        assertEquals("hi Krista", out.value.single().text)
        assertEquals(1, out.count)
    }

    @Test
    fun correctionMakesARuleAndATerm() = runTest {
        val v = Vocabulary(file, tick)
        v.correct("Christa", "Krista", everywhere = true)
        v.correct("christa", "Krista", everywhere = true)
        v.correct("QI", "QA", everywhere = false)
        assertEquals(listOf(Rule("christa", "Krista")), v.rules())
        val terms = v.data.value.terms.associateBy { it.text }
        assertEquals(TermSource.Correction, terms.getValue("Krista").source)
        assertEquals(2, terms.getValue("Krista").count)
        assertEquals(TermSource.Correction, terms.getValue("QA").source)
        val again = Vocabulary(file)
        assertEquals(v.data.value, again.data.value)
    }

    @Test
    fun promptOrderUsesSourcesAndRecordsCalendar() = runTest {
        val v = Vocabulary(file, tick)
        v.correct("QI", "QA", everywhere = true)
        v.addTerm("Resticky")
        v.addTerm("Krista")
        assertEquals("Krista, Resticky, Sam, Billing review, QA.", v.prompt(listOf("Sam"), listOf("Billing review")))
        assertEquals(TermSource.Calendar, v.data.value.terms.single { it.text == "Billing review" }.source)
        assertEquals("Krista, Resticky, QA.", v.prompt(emptyList(), emptyList()))
    }

    @Test
    fun storeDropsOldestNonUserTerms() = runTest {
        val v = Vocabulary(file, tick)
        v.addTerm("Mine")
        v.prompt(emptyList(), List(VOCABULARY_TERMS + 5) { "Event $it" })
        val terms = v.data.value.terms
        assertEquals(VOCABULARY_TERMS, terms.size)
        assertTrue(terms.any { it.text == "Mine" })
        assertTrue(terms.none { it.text == "Event 0" })
        assertTrue(terms.any { it.text == "Event ${VOCABULARY_TERMS + 4}" })
    }

    @Test
    fun removeTermAndRule() = runTest {
        val v = Vocabulary(file, tick)
        v.correct("Christa", "Krista", everywhere = true)
        v.removeRule(Rule("Christa", "Krista"))
        v.removeTerm("Krista")
        assertEquals(VocabularyData(), v.data.value)
    }

    @Test
    fun sessionFixOnlyChangesChosenLines() {
        val s = Session(
            "ab", "01", Instant.parse("2026-11-11T09:00:00Z"), Instant.parse("2026-11-11T09:00:30Z"), 60_000,
            chunks = mutableMapOf("0" to "Christa here", "1" to "Christa there"),
            segments = mutableMapOf(
                "0" to listOf(Segment(0, 1000, "Christa here", words = listOf(Word(0, 500, "Christa"), Word(500, 1000, "here")))),
                "1" to listOf(Segment(0, 1000, "Christa there", words = listOf(Word(0, 500, "Christa"), Word(500, 1000, "there")))),
            ),
        )
        assertEquals(1, s.applyRule(Rule("Christa", "Krista")) { it == CHUNK_MS }.size)
        assertEquals("Christa here", s.chunks["0"])
        assertEquals("Krista there", s.chunks["1"])
        assertEquals(1, s.applyRule(Rule("Christa", "Krista")) { true }.size)
        assertEquals("Krista here Krista there", s.transcript())
    }

    @Test
    fun pipelineAppliesRulesAndPassesThePrompt() = runTest {
        val store = Store(Path(root, "data"))
        val v = Vocabulary(file, tick)
        v.correct("Christa", "Krista", everywhere = true)
        store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4))
        val people = People(Path(root, "people.json"))
        people.learn("Sam", Voiceprint(floatArrayOf(1f, 0f), 1000), null)
        val prompts = mutableListOf<String>()
        Pipeline(
            store, { _, p -> prompts += p; listOf(Segment(0, 1000, "ask Christa", words = listOf(Word(0, 500, "ask"), Word(500, 1000, "Christa")))) },
            { _, _ -> Summary("T", "S") }, NO_SPEAKERS, vocabulary = v, people = people, calendarTerms = { _, _ -> listOf("Standup") }, attendees = { _, _ -> listOf("Dana Fox") },
        ) {}.drain()
        assertEquals(listOf("Sam, Standup, Dana Fox, Krista."), prompts)
        val m = store.list("ab").single()
        assertEquals("ask Krista", m.chunks["0"])
        assertEquals(1, m.chunkRules["0"])
    }
}
