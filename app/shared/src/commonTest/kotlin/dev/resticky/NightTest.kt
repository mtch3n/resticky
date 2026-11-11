package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightTest {
    @Test
    fun formatIsHalfHoursStartNotEnd() {
        assertTrue(validNight("off"))
        assertTrue(validNight("22:00-07:00"))
        assertTrue(validNight("00:30-23:30"))
        listOf("", "on", "22:15-07:00", "24:00-07:00", "7:00-22:00", "22:00-22:00", "22:00 - 07:00", "22:00-07:00x").forEach { assertFalse(validNight(it), it) }
        val w = parseNight("22:30-06:00")!!
        assertEquals(22 * 60 + 30, w.startMin)
        assertEquals(360, w.endMin)
        assertEquals("22:30-06:00", w.toString())
        assertNull(parseNight("off"))
    }

    @Test
    fun theNewestChangeWins() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-night-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { _, _ -> emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        suspend fun report(night: String?) = hub.checkin(Checkin("ab", night = night), "wifi")
        report(null)
        assertEquals("off", hub.night.value)
        report("23:00-06:30")
        assertEquals("23:00-06:30", hub.night.value)
        report("23:10-06:30")
        assertEquals("23:00-06:30", hub.night.value)
        hub.setNight("22:00-07:00")
        report("23:00-06:30")
        assertEquals("22:00-07:00", hub.night.value)
        report("22:00-07:00")
        report("off")
        assertEquals("off", hub.display().night)
        hub.setNight("bad")
        assertEquals("off", hub.night.value)
    }
}
