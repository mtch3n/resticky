package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PeopleTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-people-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun person(id: String, vararg e: Float, ms: Long = 60_000) = Person(id, id.replaceFirstChar { it.uppercase() }, e, ms)

    private fun print(vararg e: Float, ms: Long = 10_000) = Voiceprint(e, ms)

    private fun session() = Session("d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, chunks = mutableMapOf("0" to "hi"))

    @Test
    fun aCloseVoiceGetsTheNameAndAFairOneOnlyASuggestion() {
        val people = listOf(person("anna", 1f, 0f, 0f), person("carl", 0f, 0f, 1f))
        val m = matchPeople(mapOf(0 to print(1f, 0f, 0f), 1 to print(0f, 0.8f, 0.6f), 2 to print(0f, 1f, 0f)), people)
        assertEquals(listOf(0, 1), m.map { it.speaker })
        assertTrue(m[0].sure && m[0].person == "anna")
        assertTrue(!m[1].sure && m[1].person == "carl")
    }

    @Test
    fun thresholdsDecideSureSuggestionOrNothing() {
        val people = listOf(person("anna", 1f, 0f, 0f))
        fun at(c: Double) = matchPeople(mapOf(0 to print(c.toFloat(), sqrt1(c), 0f)), people).singleOrNull()
        assertEquals(true, at(PERSON_MATCH + 0.01)?.sure)
        assertEquals(false, at(PERSON_MATCH - 0.01)?.sure)
        assertEquals(false, at(PERSON_SUGGEST + 0.01)?.sure)
        assertNull(at(PERSON_SUGGEST - 0.01))
    }

    private fun sqrt1(c: Double) = kotlin.math.sqrt(1 - c * c).toFloat()

    @Test
    fun twoPeopleWithAlmostTheSameVoiceGiveOnlyASuggestion() {
        val people = listOf(person("anna", 1f, 0f, 0f), person("anne", 0.999f, 0.04f, 0f))
        val m = matchPeople(mapOf(0 to print(1f, 0f, 0f)), people).single()
        assertEquals("anna", m.person)
        assertTrue(!m.sure)
    }

    @Test
    fun aPersonIsNeverGivenToTwoSpeakers() {
        val people = listOf(person("anna", 1f, 0f, 0f), person("ben", 0f, 1f, 0f))
        val m = matchPeople(mapOf(0 to print(0.9f, 0.436f, 0f), 1 to print(1f, 0f, 0f), 2 to print(0.95f, 0.312f, 0f)), people)
        assertEquals(listOf("anna"), m.filter { it.person == "anna" }.map { it.person })
        assertEquals(1, m.single { it.person == "anna" }.speaker)
        assertEquals(m.size, m.map { it.person }.distinct().size)
    }

    @Test
    fun labelsNameSureSpeakersAndKeepSuggestions() {
        val s = session()
        s.summary = "Speaker A talks to Speaker B."
        s.labelVoices(listOf(Voice(0, floatArrayOf(1f, 0f, 0f), 5000), Voice(1, floatArrayOf(0f, 0.835f, 0.55f), 3000)), listOf(person("anna", 1f, 0f, 0f), person("ben", 0f, 0f, 1f)))
        assertEquals("Anna", s.speakerName(0))
        assertEquals("anna", s.people["0"])
        assertEquals("Speaker B", s.speakerName(1))
        assertEquals(mapOf("1" to "ben"), s.suggested.toMap())
        assertEquals("Anna talks to Speaker B.", s.summary)
        assertEquals(5000, s.voices.getValue("0").talkMs)
    }

    @Test
    fun renamingLearnsMergesAndMovesTheVoice() {
        var n = 0
        val id = { "p${n++}" }
        val (one, krista) = learnVoice(emptyList(), " Krista ", print(1f, 0f, 0f, ms = 10_000), null, id)
        assertEquals("p0", krista)
        assertEquals("Krista", one.single().name)
        val (two, same) = learnVoice(one, "krista", print(0f, 1f, 0f, ms = 10_000), null, id)
        assertEquals("p0", same)
        val k = two.single()
        assertEquals(20_000, k.talkMs)
        assertEquals(0.7071, k.embedding[0].toDouble(), 1e-3)
        val (again, still) = learnVoice(two, "Krista", print(0f, 1f, 0f, ms = 10_000), "p0", id)
        assertEquals("p0", still)
        assertEquals(20_000, again.single().talkMs)
        val (moved, sam) = learnVoice(two, "Sam", print(0f, 1f, 0f, ms = 10_000), "p0", id)
        assertEquals("p1", sam)
        assertEquals(10_000, moved.first { it.id == "p0" }.talkMs)
        assertTrue(moved.first { it.id == "p0" }.embedding[0] > 0.9f)
        val (gone, none) = learnVoice(moved, "", print(0f, 1f, 0f, ms = 10_000), "p1", id)
        assertNull(none)
        assertEquals(listOf("p0"), gone.map { it.id })
    }

    @Test
    fun peopleFileRoundTripsAndDeleteRemovesTheVoiceprint() = runTest {
        val path = Path(root, "people.json")
        val people = People(path)
        val me = people.setMe(print(0f, 1f, ms = 40_000))
        val k = people.learn("Krista", print(1f, 0f), null)!!
        people.rename(me, "Ming")
        val back = People(path).all()
        assertEquals(listOf("Ming", "Krista"), back.map { it.name })
        assertTrue(back.first().isMe)
        assertContentEquals(floatArrayOf(0f, 1f), back.first().embedding)
        assertEquals(40_000, back.first().talkMs)
        people.delete(k)
        assertEquals(listOf(me), People(path).all().map { it.id })
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        assertTrue("Krista" !in text)
    }

    @Test
    fun oldMetaWithoutVoicesLoadsAndNewVoicesRoundTrip() = runTest {
        val dir = Path(root, "data", "ab", "01")
        SystemFileSystem.createDirectories(dir)
        val old = """{"device":"ab","id":"01","started":"2026-11-01T10:00:00Z","last_chunk":"2026-11-01T10:01:00Z","duration_ms":30000,
            |"chunks":{"0":"hi"},"diarized":true,"speakers":{"0":"Sam"}}""".trimMargin()
        SystemFileSystem.sink(Path(dir, "meta.json")).buffered().use { it.write(old.encodeToByteArray()) }
        val store = Store(Path(root, "data"))
        val m = store.list("ab").single()
        assertEquals("Sam", m.speakerName(0))
        assertTrue(m.voices.isEmpty() && m.people.isEmpty() && m.suggested.isEmpty())
        store.update("ab", "01") { it.voices["0"] = print(0.25f, -1.5f, 3e-7f, ms = 1234) }
        val v = store.list("ab").single().voices.getValue("0")
        assertContentEquals(floatArrayOf(0.25f, -1.5f, 3e-7f), v.embedding)
        assertEquals(1234, v.talkMs)
    }

    @Test
    fun theTranscriptRenameTeachesTheLibrary() = runTest {
        val store = Store(Path(root, "data"))
        val people = People(Path(root, "people.json"))
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0))
        store.update("ab", "01") {
            it.voices["0"] = print(1f, 0f, ms = 8000)
            it.suggested["0"] = "x"
        }
        nameSpeaker(store, people, "ab", "01", 0, "Krista")
        val k = people.all().single()
        assertEquals("Krista", k.name)
        assertEquals(8000, k.talkMs)
        val m = store.list("ab").single()
        assertEquals("Krista", m.speakerName(0))
        assertEquals(k.id, m.people["0"])
        assertTrue(m.suggested.isEmpty())
        renamePerson(store, people, k.id, "Krista Lee")
        assertEquals("Krista Lee", store.list("ab").single().speakerName(0))
    }

    @Test
    fun pipelineNamesAKnownVoice() = runTest {
        val store = Store(Path(root, "data"))
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        val diarizer = Diarizer { Diarization(listOf(Turn(0, 600, 0), Turn(600, 1000, 1)), listOf(Voice(0, floatArrayOf(0f, 1f), 600), Voice(1, floatArrayOf(1f, 0f), 400))) }
        Pipeline(store, { listOf(Segment(0, 500, "hello $SPOKEN"), Segment(500, 1000, "world $SPOKEN")) }, { _, _ -> Summary("T", "S") }, diarizer, people = { listOf(person("ann", 1f, 0f)) }) {}.drain()
        val m = store.list("ab").single()
        assertEquals("Speaker A", m.speakerName(0))
        assertEquals("Ann", m.speakerName(1))
        assertEquals(setOf("0", "1"), m.voices.keys)
    }

    @Test
    fun mergedSpeakersKeepOneVoiceEach() {
        val d = Diarization(
            listOf(Turn(0, 4000, 3), Turn(5000, 9000, 7), Turn(9000, 12_000, 5)),
            listOf(Voice(3, floatArrayOf(1f, 0f), 4000), Voice(5, floatArrayOf(0.99f, 0.05f), 3000), Voice(7, floatArrayOf(0f, 1f), 4000)),
        )
        val out = mergeSpeakers(d)
        assertEquals(listOf(0, 1), out.voices.map { it.speaker })
        assertEquals(7000, out.voices[0].weightMs)
    }

    @Test
    fun enrollmentUsesOnlyVoicedAudio() {
        val rate = 16_000
        val tone = FloatArray(rate * 7) { (0.3 * sin(2 * PI * 200 * it / rate)).toFloat() }
        val quiet = FloatArray(rate * 10)
        val windows = voicedWindows(quiet + tone + quiet, rate)
        assertEquals(2, windows.size)
        assertTrue(windows.all { w -> w.size == rate * 3 })
        val v = enrollVoice(quiet + tone, rate) { floatArrayOf(it.size.toFloat(), 0f) }!!
        assertEquals(6000, v.talkMs)
        assertContentEquals(floatArrayOf(1f, 0f), v.embedding)
        assertNull(enrollVoice(quiet, rate) { floatArrayOf(1f) })
    }
}
