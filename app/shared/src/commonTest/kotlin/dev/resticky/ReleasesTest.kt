package dev.resticky

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    @Test
    fun versionsCompareAsNumbers() {
        assertTrue(isNewer("0.13.10", "0.13.9"))
        assertTrue(isNewer("v1.0.0", "0.99.99"))
        assertFalse(isNewer("0.13.5", "0.13.5"))
        assertFalse(isNewer("0.13.4", "0.13.5"))
        assertTrue(isNewer("0.1.0", ""))
    }

    @Test
    fun releaseJsonGivesTheAssets() {
        val r = hubJson.decodeFromString<Release>("""{"tag_name":"v0.13.6","published_at":"2026-11-10T10:00:00Z","assets":[
            {"name":"resticky-android.apk","browser_download_url":"https://x/a.apk","size":9},
            {"name":"resticky-firmware-0.13.6.bin","browser_download_url":"https://x/f.bin","size":7},
            {"name":"resticky-bootloader.bin","browser_download_url":"https://x/b.bin"}]}""")
        assertEquals("0.13.6", r.version)
        assertEquals("0.13.6" to "https://x/f.bin", r.firmware?.let { it.first to it.second.url })
        assertEquals("https://x/a.apk", r.apk?.url)
        assertNull(Release("v1").firmware)
    }

    @Test
    fun shelfOffersTheNewestVersion() {
        val shelf = FirmwareShelf(Path(SystemTemporaryDirectory, "resticky-fw-${Random.nextLong().toULong()}"))
        assertNull(shelf.newest())
        shelf.write("0.13.10", byteArrayOf(1))
        shelf.write("0.13.9", byteArrayOf(2, 3))
        assertEquals("0.13.10", shelf.newest()?.first)
        assertEquals(2, shelf.read("0.13.9")?.size)
        assertNull(shelf.read("../x"))
        SystemFileSystem.list(shelf.dir).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(shelf.dir)
    }
}
