package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SoundTest {
    @Test
    fun theNewestChangeWins() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-sound-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { _, _ -> emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        suspend fun report(sound: String?) = hub.checkin(Checkin("ab", sound = sound), "wifi")
        report(null)
        assertEquals("beep", hub.sound.value)
        report("buzz")
        assertEquals("buzz", hub.sound.value)
        report("loud")
        assertEquals("buzz", hub.sound.value)
        hub.setSound("off")
        report("buzz")
        assertEquals("off", hub.sound.value)
        report("off")
        report("beep")
        assertEquals("beep", hub.sound.value)
        assertEquals("beep", hub.display().sound)
    }

    @Test
    fun homeScreenFollowsTheSameRule() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-home-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { _, _ -> emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
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

    @Test
    fun buzzPitchFollowsTheSameRule() = runTest {
        val root = Path(SystemTemporaryDirectory, "resticky-buzz-${Random.nextLong().toULong()}")
        val store = Store(root)
        val hub = Hub(store, Pipeline(store, { _, _ -> emptyList() }, { _, _ -> Summary("", "") }, NO_SPEAKERS) {}, Weather(Path(root, "w.json"), null, {}), FirmwareShelf(Path(root, "fw")), {})
        suspend fun report(hz: Int?) = hub.checkin(Checkin("ab", buzzHz = hz), "wifi")
        assertEquals(175, hub.buzzHz.value)
        report(300)
        assertEquals(300, hub.buzzHz.value)
        report(5000)
        assertEquals(300, hub.buzzHz.value)
        hub.setBuzzHz(120)
        report(300)
        assertEquals(120, hub.display().buzzHz)
        hub.setBuzzHz(10)
        assertEquals(120, hub.buzzHz.value)
    }
}
