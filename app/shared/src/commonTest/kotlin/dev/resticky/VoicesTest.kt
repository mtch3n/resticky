package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class VoicesTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-voices-${Random.nextLong().toULong()}")
    private val noise = Random(7)

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private val owner = floatArrayOf(1f, 0f, 0f, 0f)
    private val guest = floatArrayOf(0f, 1f, 0f, 0f)
    private val once = floatArrayOf(0f, 0f, 1f, 0f)

    private fun near(e: FloatArray) = FloatArray(e.size) { e[it] + (noise.nextFloat() - 0.5f) * 0.3f }

    private fun session(id: String, vararg voices: FloatArray) = Session(
        "d", id, Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, chunks = mutableMapOf("0" to "hi"),
        voices = voices.mapIndexed { i, e -> "$i" to Voiceprint(near(e), 30_000L + i) }.toMap().toMutableMap(),
    )

    private fun sessions(withOwner: Int) = (0 until 6).map { i ->
        val list = listOfNotNull(owner.takeIf { i < withOwner }, guest.takeIf { i in 1..3 }, once.takeIf { i == 5 })
        session("s$i", *list.toTypedArray())
    }

    private fun counter(): () -> String {
        var n = 0
        return { "p${n++}" }
    }

    @Test
    fun theVoiceInMostRecordingsBecomesYou() {
        val people = autoPeople(emptyList(), sessions(5), counter())
        val me = people.single { it.isMe }
        assertEquals(ME_NAME, me.name)
        assertTrue(me.auto)
        assertTrue(cosine(me.embedding, owner) > 0.95)
        val others = people.filter { !it.isMe }
        assertEquals(listOf("Person 1"), others.map { it.name })
        assertTrue(cosine(others.single().embedding, guest) > 0.95)
        assertTrue(others.single().unnamed)
    }

    @Test
    fun tooFewRecordingsMakeNoOwner() {
        val people = autoPeople(emptyList(), sessions(4), counter())
        assertTrue(people.none { it.isMe })
        assertEquals(listOf("Person 1", "Person 2"), people.map { it.name })
    }

    @Test
    fun anEnrolledVoiceStaysAndAKnownOneIsNotAddedAgain() {
        val enrolled = Person("me", ME_NAME, owner, 45_000, isMe = true)
        val anna = Person("anna", "Anna", guest, 60_000)
        val people = autoPeople(listOf(enrolled, anna), sessions(6), counter())
        assertEquals(listOf("me", "anna"), people.map { it.id })
        assertEquals(1, people.count { it.isMe })
        assertFalse(people.first().auto)
    }

    @Test
    fun aNamedVoiceThatIsInMostRecordingsBecomesYou() {
        val ming = Person("ming", "Ming", owner, 45_000)
        val people = autoPeople(listOf(ming), sessions(5), counter())
        assertEquals("Ming", people.single { it.isMe }.name)
        assertEquals(2, people.size)
    }

    @Test
    fun numbersStayStable() {
        val first = autoPeople(listOf(Person("x", "Person 2", once, 10_000, auto = true)), sessions(4), counter())
        assertEquals(listOf("Person 2", "Person 3", "Person 4"), first.map { it.name })
        val again = autoPeople(first, sessions(4), counter())
        assertEquals(first.map { it.id }, again.map { it.id })
    }

    @Test
    fun aVoiceInOneSessionTwiceIsTwoPeople() {
        val list = (0 until 3).map { session("s$it", guest, guest) }
        val clusters = clusterVoices(list)
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.sessions.size == 3 })
    }

    @Test
    fun linkingNamesFreeSpeakersOnly() {
        val people = listOf(Person("p1", "Person 1", guest, 60_000, auto = true))
        val s = session("a", guest)
        assertTrue(s.linkPeople(people))
        assertEquals("Person 1", s.speakerName(0))
        assertEquals("p1", s.people["0"])
        assertFalse(s.linkPeople(people))
        val mine = session("b", guest)
        mine.userNamed += "0"
        mine.renameSpeaker(0, "Gus")
        assertFalse(mine.linkPeople(people))
        assertEquals("Gus", mine.speakerName(0))
    }

    @Test
    fun pipelineFindsYouAfterDiarization() = runTest {
        val store = Store(Path(root, "data"))
        val people = People(Path(root, "people.json"))
        for (i in 0 until 5) store.addChunk("ab", "0$i", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        val diarizer = Diarizer { Diarization(listOf(Turn(0, 1000, 0)), listOf(Voice(0, near(owner), 1000))) }
        Pipeline(store, { _, _ -> listOf(Segment(0, 1000, SPOKEN)) }, { _, _ -> Summary("T", "S") }, diarizer, people = people) {}.drain()
        assertTrue(people.all().single().isMe)
        assertTrue(store.all().all { it.speakerName(0) == ME_NAME })
    }

    @Test
    fun oldPeopleFilesLoad() = runTest {
        val path = Path(root, "people.json")
        Store(root).write(path, """[{"id":"a","name":"Ann","embedding":"AACAPw==","talk_ms":5,"is_me":true}]""".encodeToByteArray())
        val p = People(path).all().single()
        assertTrue(p.isMe)
        assertFalse(p.auto)
        assertFalse(p.unnamed)
    }
}
