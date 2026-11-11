package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class BeepTest {
    @Test
    fun theNewestChangeWins() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-beep-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        suspend fun report(beep: Boolean?) = hub.checkin(Checkin("ab", beep = beep), "wifi")
        report(null)
        assertEquals(true, hub.beep.value)
        report(false)
        assertEquals(false, hub.beep.value)
        hub.setBeep(true)
        report(false)
        assertEquals(true, hub.beep.value)
        report(true)
        report(false)
        assertEquals(false, hub.beep.value)
        assertEquals(false, hub.display().beep)
    }
}
