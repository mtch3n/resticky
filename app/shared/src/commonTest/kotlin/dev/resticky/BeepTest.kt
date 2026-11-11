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

    @Test
    fun homeScreenFollowsTheSameRule() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-home-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        suspend fun report(home: String?) = hub.checkin(Checkin("ab", home = home), "wifi")
        assertEquals("apps", hub.home.value)
        report("weather")
        assertEquals("weather", hub.home.value)
        report("nope")
        assertEquals("weather", hub.home.value)
        hub.setHome("todo")
        report("weather")
        assertEquals("todo", hub.home.value)
        report("todo")
        report("clock")
        assertEquals("clock", hub.display().home)
        hub.setHome("tv")
        assertEquals("clock", hub.home.value)
    }
}
