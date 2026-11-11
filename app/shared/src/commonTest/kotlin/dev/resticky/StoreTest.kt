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
        assertEquals(setOf("0.opus", "meta.json"), files(store, "01"))
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
