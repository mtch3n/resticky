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

class NamesTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-names-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private val text = "Speaker A: Krista, can you take the budget?\n" +
        "Speaker B: Sure, I will send it on Friday.\n" +
        "Speaker C: Hi all, this is Tom from finance."

    private val session = Session("d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, chunks = mutableMapOf("0" to "hi"))

    private fun slots(vararg open: Boolean) = open.mapIndexed { i, o -> SpeakerSlot(speakerLetter(i), session, i, o) }

    private val krista = SpeakerName("Speaker B", "Krista", "Krista, can you take the budget? Speaker B: Sure")
    private val tom = SpeakerName("Speaker C", "Tom", "this is Tom from finance")

    private fun names(proposals: List<SpeakerName>, attendees: List<String> = emptyList(), slots: List<SpeakerSlot> = slots(true, true, true)) =
        acceptNames(proposals, text, attendees, slots).map { it.first.label to it.second }

    @Test
    fun namesWithEvidenceAreAccepted() {
        assertEquals(listOf("Speaker B" to "Krista", "Speaker C" to "Tom"), names(listOf(krista, tom)))
    }

    @Test
    fun evidenceIsRequired() {
        assertEquals(emptyList(), names(listOf(krista.copy(evidence = ""))))
        assertEquals(emptyList(), names(listOf(krista.copy(evidence = "Krista said she loves the plan"))))
        assertEquals(emptyList(), names(listOf(tom.copy(evidence = "Hi all"))))
    }

    @Test
    fun evidenceMustBeNearTheSpeaker() {
        assertEquals(emptyList(), names(listOf(tom.copy(speaker = "Speaker A"))))
    }

    @Test
    fun aFullNameNeedsTheCalendar() {
        val full = krista.copy(name = "Krista Lee")
        assertEquals(emptyList(), names(listOf(full)))
        assertEquals(listOf("Speaker B" to "Krista Lee"), names(listOf(full), listOf("Krista Lee")))
    }

    @Test
    fun oneNameNeverGoesToTwoSpeakers() {
        assertEquals(listOf("Speaker C" to "Tom"), names(listOf(krista, krista.copy(speaker = "Speaker A"), tom)))
        assertEquals(emptyList(), names(listOf(tom, tom.copy(name = "Tom Smith")), listOf("Tom Smith")))
        val named = slots(true, true) + SpeakerSlot("Tom", session, 2, false)
        assertEquals(emptyList(), names(listOf(tom.copy(speaker = "Speaker B", evidence = "Sure, I will send it on Friday. Speaker C: Hi all, this is Tom")), slots = named))
    }

    @Test
    fun youAndPlaceholdersAreNeverNames() {
        assertEquals(emptyList(), names(listOf(krista.copy(name = "You"), krista.copy(name = "Speaker A"), krista.copy(name = "Person 2"))))
        assertEquals(emptyList(), names(listOf(krista), slots = slots(true, false, true)))
    }

    @Test
    fun slotsProtectYouAndUserNames() {
        val s = Session(
            "d", "s", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 30_000, chunks = mutableMapOf("0" to "a b c d"),
            segments = mutableMapOf("0" to listOf(Segment(0, 1, "a", 0), Segment(1, 2, "b", 1), Segment(2, 3, "c", 2), Segment(3, 4, "d", 3))),
            people = mutableMapOf("0" to "me", "2" to "p3"),
            userNamed = mutableSetOf("1"),
        )
        s.renameSpeaker(0, ME_NAME)
        s.renameSpeaker(1, "Gus")
        s.renameSpeaker(2, "Person 3")
        val people = listOf(Person("me", ME_NAME, floatArrayOf(1f), 1, isMe = true, auto = true), Person("p3", "Person 3", floatArrayOf(1f), 1, auto = true))
        val open = conversations(listOf(s)).single().slots(people).associate { it.label to it.open }
        assertEquals(mapOf(ME_NAME to false, "Gus" to false, "Person 3" to true, "Speaker D" to true), open)
    }

    @Test
    fun pipelineLearnsANameAndAUserRenameWins() = runTest {
        val store = Store(Path(root, "data"))
        val people = People(Path(root, "people.json"))
        store.addChunk("ab", "01", 0, true, 1000, byteArrayOf(0, 0, 0, 0))
        val diarizer = Diarizer { Diarization(listOf(Turn(0, 500, 0), Turn(500, 1000, 1)), listOf(Voice(0, floatArrayOf(1f, 0f), 500), Voice(1, floatArrayOf(0f, 1f), 500))) }
        val said = listOf(Segment(0, 500, "Krista can you check $SPOKEN"), Segment(500, 1000, "sure I can $SPOKEN"))
        val proposal = SpeakerName("Speaker B", "Krista", "Krista can you check")
        Pipeline(store, { _, _ -> said }, { _, _ -> Summary("T", "Speaker B checks.", speakers = listOf(proposal)) }, diarizer, people = people) {}.drain()
        val m = store.list("ab").single()
        assertEquals("Krista", m.speakerName(1))
        assertEquals("Krista checks.", m.summary)
        val k = people.all().single()
        assertEquals("Krista", k.name)
        assertTrue(k.auto)
        assertEquals(k.id, m.people["1"])
        nameSpeaker(store, people, "ab", "01", 1, "Krysta")
        val user = people.all().single { it.name == "Krysta" }
        assertFalse(user.auto)
        val after = store.list("ab").single()
        assertTrue("1" in after.userNamed)
        assertTrue(conversations(listOf(after)).single().slots(people.all()).none { it.open && it.speaker == 1 })
    }

    @Test
    fun aNameForAnUnnamedPersonSticksEverywhere() = runTest {
        val store = Store(Path(root, "data"))
        val people = People(Path(root, "people.json"))
        people.discover((0 until 3).map { i ->
            Session("x", "v$i", Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, chunks = mutableMapOf("0" to "hi"), voices = mutableMapOf("0" to Voiceprint(floatArrayOf(0f, 1f), 9000)))
        })
        val p3 = people.all().single()
        assertEquals("Person 1", p3.name)
        for (id in listOf("01", "02")) {
            store.addChunk("ab", id, 0, true, 1000, byteArrayOf(0, 0))
            store.update("ab", id) {
                it.voices["0"] = Voiceprint(floatArrayOf(0f, 1f), 9000)
                it.people["0"] = p3.id
                it.renameSpeaker(0, p3.name)
            }
        }
        nameSpeaker(store, people, "ab", "01", 0, "Dana", auto = true)
        assertEquals(listOf("Dana"), people.all().map { it.name })
        assertTrue(people.all().single().auto)
        assertEquals(listOf("Dana", "Dana"), store.all().map { it.speakerName(0) })
        assertTrue(store.all().all { it.userNamed.isEmpty() })
    }
}
