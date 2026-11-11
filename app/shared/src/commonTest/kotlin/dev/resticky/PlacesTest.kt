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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlacesTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-places-${Random.nextLong().toULong()}")
    private val office = NamedPlace("1", "Office", 25.0330, 121.5654, 150)
    private val home = NamedPlace("2", "Home", 25.0340, 121.5654, 300)

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    @Test
    fun distanceIsMetres() {
        assertEquals(111_195.0, distanceM(0.0, 0.0, 1.0, 0.0), 10.0)
        assertEquals(0.0, distanceM(25.0, 121.0, 25.0, 121.0), 0.001)
    }

    @Test
    fun nearestPlaceInsideItsRadiusWins() {
        assertEquals(PlaceTag("Office", null, null), placeTag(listOf(home, office), 25.0331, 121.5654))
        assertEquals("Home", placeTag(listOf(home, office), 25.0350, 121.5654).name)
        assertEquals(PlaceTag(ELSEWHERE, 25.05, 121.57), placeTag(listOf(home, office), 25.0512, 121.5701))
        assertEquals(ELSEWHERE, placeTag(emptyList(), 25.0330, 121.5654).name)
    }

    @Test
    fun chunkAgeUsesDeviceUptime() {
        val now = Clock.System.now()
        val c = Checkin(device = "ab", health = Health(uptimeS = 1000), seen = now - 10.seconds)
        assertEquals(410.seconds, chunkAge(c, 600_000, now))
        assertNull(chunkAge(c.copy(health = null), 600_000, now))
        assertNull(chunkAge(c, 2_000_000, now))
        assertNull(chunkAge(null, 0, now))
    }

    @Test
    fun promptHasOnlyNamedPlaces() {
        assertTrue("\"Office\"" in summaryPrompt(SummaryContext(placeContext("Office"))))
        assertEquals(summaryPrompt(SummaryContext()), summaryPrompt(SummaryContext(placeContext(ELSEWHERE))))
        assertEquals(summaryPrompt(SummaryContext()), summaryPrompt(SummaryContext(placeContext(PENDING))))
        assertTrue(summaryPrompt(SummaryContext()).endsWith("Transcript:\n"))
    }

    @Test
    fun summaryWaitsForThePlaceAndGetsIt() = runTest {
        val store = Store(root)
        assertEquals(Added.SESSION, store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4)))
        assertEquals(Added.REPEAT, store.addChunk("ab", "01", 0, true, 30_000, ByteArray(4)))
        store.update("ab", "01") { it.place = PENDING }
        val seen = mutableListOf<String>()
        val pipeline = Pipeline(store, { listOf(Segment(0, 1000, SPOKEN)) }, { _, c -> seen += c.place; Summary("T", "S") }, NO_SPEAKERS) {}
        assertEquals("", transcriptItems(store.list("ab")).single().place)
        pipeline.drain()
        assertFalse(store.list("ab").single().ready)
        store.update("ab", "01") { it.place = "Office" }
        pipeline.drain()
        assertEquals(listOf("Office"), seen)
        assertEquals("Office", transcriptItems(store.list("ab")).single().place)
    }

    @Test
    fun pendingPlaceStopsWaitingAfterAMinute() = runTest {
        val store = Store(root)
        store.addChunk("ab", "02", 0, true, 30_000, ByteArray(4))
        store.update("ab", "02") { it.place = PENDING; it.lastChunk = Clock.System.now() - 2.minutes }
        Pipeline(store, { listOf(Segment(0, 1000, "hi")) }, { _, _ -> Summary("T", "S") }, NO_SPEAKERS) {}.drain()
        val m = store.list("ab").single()
        assertTrue(m.ready)
        assertEquals("", m.place)
    }
}
