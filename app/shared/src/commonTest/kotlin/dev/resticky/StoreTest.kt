package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class StoreTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-store-${Random.nextLong().toULong()}")

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun files(store: Store, id: String) = SystemFileSystem.list(store.dir("ab", id)).map { it.name }.toSet()

    @Test
    fun repeatedChunkKeepsTheFirstAudio() = runTest {
        val store = Store(root)
        assertEquals(Added.SESSION, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1, 2)))
        store.update("ab", "01") { it.chunks["0"] = "text"; it.ready = true }
        assertEquals(Added.REPEAT, store.addChunk("ab", "01", 0, true, 30_000, byteArrayOf(1, 2)))
        assertEquals(Added.CONFLICT, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(9, 9, 9)))
        assertContentEquals(byteArrayOf(1, 2), store.read(Path(store.dir("ab", "01"), "0.opus")))
        val m = store.list("ab").single()
        assertEquals("text", m.chunks["0"])
        assertTrue(m.ready && !m.final)
        assertEquals(30_000, m.durationMs)
        assertEquals(setOf("0.opus", "meta.json", conflictName(0, byteArrayOf(9, 9, 9))), files(store, "01"))
    }

    @Test
    fun conflictingChunkIsKeptAsACopy() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1, 2))
        assertEquals(Added.CONFLICT, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(9, 9, 9)))
        assertEquals(Added.CONFLICT, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(9, 9, 9)))
        assertEquals(Added.CONFLICT, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(7)))
        val dir = store.dir("ab", "01")
        assertContentEquals(byteArrayOf(1, 2), store.read(Path(dir, "0.opus")))
        assertContentEquals(byteArrayOf(9, 9, 9), store.read(Path(dir, conflictName(0, byteArrayOf(9, 9, 9)))))
        assertContentEquals(byteArrayOf(7), store.read(Path(dir, conflictName(0, byteArrayOf(7)))))
        assertEquals(4, files(store, "01").size)
        assertEquals(30_000, store.list("ab").single().durationMs)
    }

    @Test
    fun repeatedChunkWithTheFileMissingIsStoredAgain() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1, 2))
        store.update("ab", "01") { it.chunks["0"] = "text" }
        SystemFileSystem.delete(Path(store.dir("ab", "01"), "0.opus"))
        assertEquals(Added.RESTORED, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1, 2)))
        assertContentEquals(byteArrayOf(1, 2), store.read(Path(store.dir("ab", "01"), "0.opus")))
        val m = store.list("ab").single()
        assertEquals("text", m.chunks["0"])
        assertEquals(30_000, m.durationMs)
        assertEquals(Added.REPEAT, store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1, 2)))
    }

    @Test
    fun chunkStartIsStored() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(1), 5_000)
        store.addChunk("ab", "01", 1, false, 30_000, byteArrayOf(2))
        assertEquals(mapOf("0" to 5_000L), store.list("ab").single().chunkStart)
    }

    @Test
    fun oldAudioStaysWhileTheWorkIsOpen() = runTest {
        val store = Store(root)
        for (id in listOf("01", "02", "03")) store.addChunk("ab", id, 0, true, 30_000, byteArrayOf(1))
        val old = Clock.System.now() - 30.days
        store.update("ab", "01") { it.chunks["0"] = "done"; it.ready = true; it.lastChunk = old; it.started = old }
        store.update("ab", "02") { it.lastChunk = old; it.started = old + 1.days }
        store.update("ab", "03") { it.chunks["0"] = "not summarized"; it.lastChunk = old; it.started = old + 2.days }
        store.deleteOldAudio(Clock.System.now() - AUDIO_KEEP)
        assertEquals(setOf("meta.json"), files(store, "01"))
        assertEquals(setOf("0.opus", "meta.json"), files(store, "02"))
        assertEquals(setOf("0.opus", "meta.json"), files(store, "03"))
    }

    @Test
    fun startedFollowsTheLowestSeq() = runTest {
        val store = Store(root)
        val anchor = 1_794_000_000_000L
        store.addChunk("ab", "01", 5, false, 30_000, byteArrayOf(5), 1_150_000, boot = "0000beef", bootUnixMs = anchor)
        assertEquals(Instant.fromEpochMilliseconds(anchor + 1_000_000), store.list("ab").single().started)
        store.addChunk("ab", "01", 2, false, 30_000, byteArrayOf(2), 1_090_000, boot = "0000beef", bootUnixMs = anchor)
        store.addChunk("ab", "01", 7, false, 30_000, byteArrayOf(7), 1_210_000, boot = "0000beef", bootUnixMs = anchor)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(0), 1_030_000, boot = "0000beef", bootUnixMs = anchor)
        val m = store.list("ab").single()
        assertEquals(Instant.fromEpochMilliseconds(anchor + 1_030_000), m.started)
        assertEquals(Instant.fromEpochMilliseconds(anchor + 1_210_000), m.wallAt(m.offsets().getValue("7")))
        assertTrue(!m.timeEstimated)
    }

    @Test
    fun clockKnownInTheMiddleOfASessionTimesTheEarlierChunks() = runTest {
        val store = Store(root)
        val anchor = 1_794_000_000_000L
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(0), 40_000, capturedAt = Instant.fromEpochMilliseconds(5_000_000), boot = "0000beef")
        store.addChunk("ab", "02", 0, false, 30_000, byteArrayOf(0), 100_000, boot = "0000beef")
        store.addChunk("ab", "03", 0, false, 30_000, byteArrayOf(0), 100_000, capturedAt = Instant.fromEpochMilliseconds(9_000_000), boot = "0000cafe")
        store.list("ab").forEach { assertTrue(it.timeEstimated) }
        assertEquals(Instant.fromEpochMilliseconds(5_000_000), store.list("ab").single { it.id == "01" }.started)
        store.addChunk("ab", "01", 1, false, 30_000, byteArrayOf(1), 70_000, boot = "0000beef", bootUnixMs = anchor)
        val all = store.list("ab").associateBy { it.id }
        assertEquals(Instant.fromEpochMilliseconds(anchor + 40_000), all.getValue("01").started)
        assertEquals(Instant.fromEpochMilliseconds(anchor + 100_000), all.getValue("02").started)
        assertTrue(!all.getValue("01").timeEstimated && !all.getValue("02").timeEstimated)
        assertEquals(Instant.fromEpochMilliseconds(9_000_000), all.getValue("03").started)
        assertTrue(all.getValue("03").timeEstimated)
    }

    @Test
    fun estimatedAnchorGivesWayToTheDeviceClock() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(0), 40_000, boot = "0000beef")
        assertTrue(store.setAnchor("ab", "0000beef", Anchor(2_000_000_000_000L, estimated = true)))
        assertTrue(!store.setAnchor("ab", "0000beef", Anchor(2_000_000_005_000L, estimated = true)))
        store.list("ab").single().let {
            assertEquals(Instant.fromEpochMilliseconds(2_000_000_040_000L), it.started)
            assertTrue(it.timeEstimated)
        }
        assertTrue(store.setAnchor("ab", "0000beef", Anchor(2_000_000_001_000L)))
        assertTrue(!store.setAnchor("ab", "0000beef", Anchor(2_000_000_009_000L, estimated = true)))
        store.list("ab").single().let {
            assertEquals(Instant.fromEpochMilliseconds(2_000_000_041_000L), it.started)
            assertTrue(!it.timeEstimated)
        }
    }

    @Test
    fun gapsAndStates() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(0), 40_000)
        store.addChunk("ab", "01", 3, true, 30_000, byteArrayOf(3), 130_000)
        val m = store.list("ab").single()
        val now = Clock.System.now()
        assertEquals(listOf(1, 2), m.gaps())
        assertTrue(!m.complete())
        assertEquals(SessionState.OPEN, m.state(now))
        assertEquals(SessionState.PROVISIONAL, m.state(now + PROVISIONAL_IDLE))
        assertTrue(m.partial(now + PROVISIONAL_IDLE))
        assertEquals(SessionState.FINAL, m.state(now + FINAL_IDLE))
        assertTrue(m.partial(now + FINAL_IDLE))
        store.addChunk("ab", "01", 1, false, 30_000, byteArrayOf(1), 70_000)
        store.addChunk("ab", "01", 2, false, 30_000, byteArrayOf(2), 100_000)
        val done = store.list("ab").single()
        assertTrue(done.complete())
        assertEquals(SessionState.FINAL, done.state(now))
        assertTrue(!done.partial(now + 2.hours))
    }

    @Test
    fun oldMetaWithoutTheNewFieldsLoads() = runTest {
        val store = Store(root)
        store.write(Path(store.dir("ab", "01"), "meta.json"), """{"device":"ab","id":"01","started":"2026-11-01T10:00:00Z","last_chunk":"2026-11-01T10:01:00Z","duration_ms":60000,"final":true,"chunks":{"0":"a","1":"b"},"chunk_start":{"0":5000}}""".encodeToByteArray())
        val m = store.list("ab").single()
        assertTrue(m.complete() && !m.timeEstimated && m.boot.isEmpty())
        assertEquals(Added.CHUNK, store.addChunk("ab", "01", 2, false, 30_000, byteArrayOf(1)))
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), store.list("ab").single().started)
    }
}
