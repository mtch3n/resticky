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
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

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
    fun sessionsComeFromMemoryUntilAWriteOrAFileChange() = runTest {
        val store = Store(root)
        store.addChunk("ab", "01", 0, false, 30_000, byteArrayOf(0, 0))
        val v = store.changes.value
        val first = store.all().single()
        assertSame(first, store.all().single())
        assertSame(first, store.list("ab").single())
        store.update("ab", "01") { it.title = "New" }
        assertEquals(v + 1, store.changes.value)
        assertEquals("", first.title)
        val second = store.list("ab").single()
        assertEquals("New", second.title)
        assertNotSame(first, second)
        val meta = Path(store.dir("ab", "01"), "meta.json")
        val text = store.read(meta)!!.decodeToString().replace("\"New\"", "\"From outside\"")
        store.write(meta, text.encodeToByteArray())
        assertEquals("From outside", store.all().single().title)
        store.write(Path(store.dir("ab", "02"), "meta.json"), """{"device":"ab","id":"02","started":"2026-11-10T10:00:00Z","last_chunk":"2026-11-10T10:00:00Z","duration_ms":0,"chunks":{}}""".encodeToByteArray())
        assertEquals(setOf("01", "02"), store.list("ab").map { it.id }.toSet())
        SystemFileSystem.delete(Path(store.dir("ab", "02"), "meta.json"))
        SystemFileSystem.delete(store.dir("ab", "02"))
        assertEquals(listOf("01"), store.all().map { it.id })
    }

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
}
