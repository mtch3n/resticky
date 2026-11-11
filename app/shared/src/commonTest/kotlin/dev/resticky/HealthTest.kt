package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
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

class HealthTest {
    private val root = Path(SystemTemporaryDirectory, "resticky-health-${Random.nextLong().toULong()}")
    private val device = "a1b2c3d4e5f6"
    private val start = 1_794_000_000L

    @AfterTest
    fun clean() {
        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    private fun sample(t: Long, battery: Int = 50) = HealthSample(t, batteryPct = battery)

    private fun fileSize() = SystemFileSystem.metadataOrNull(Path(root, device, "health.bin"))?.size ?: 0L

    @Test
    fun keepsOneSampleEachMinute() = runTest {
        val log = HealthLog(root)
        assertTrue(log.add(device, sample(start, 1)))
        assertFalse(log.add(device, sample(start + 30, 2)))
        assertTrue(log.add(device, sample(start + 60, 3)))
        assertEquals(listOf(1, 3), log.read(device, 0).map { it.batteryPct })
        assertEquals(2L * HEALTH_RECORD, fileSize())
    }

    @Test
    fun roundTripsAllFields() = runTest {
        val h = Health(3600, 95000, 85000, 60000, 6_000_000, 5_500_000, 2100, 1800, 1500, 120, 340, 210, 9100, 2, 41.5f)
        val s = HealthSample(start, 77, 4012, -31, true, 3, 1, -61, h)
        HealthLog(root).add(device, s)
        assertEquals(s, HealthLog(root).read(device, 0).single())
        HealthLog(root).add(device, sample(start + 60))
        assertNull(HealthLog(root).read(device, start + 1).single().health)
    }

    @Test
    fun boundedToSevenDaysOfMinutes() = runTest {
        val log = HealthLog(root)
        val total = HEALTH_MAX + HEALTH_SLACK + 100
        for (i in 0 until total) log.add(device, sample(start + i * 60L))
        assertTrue(fileSize() <= (HEALTH_MAX + HEALTH_SLACK).toLong() * HEALTH_RECORD)
        val all = log.read(device, 0)
        assertTrue(all.size <= HEALTH_MAX + HEALTH_SLACK)
        assertEquals(start + (total - 1) * 60L, all.last().t)
        assertTrue(all.first().t > all.last().t - HEALTH_MAX_HOURS * 3600L - HEALTH_SLACK * 60L)
    }

    @Test
    fun oldSamplesAreDroppedAtCompaction() = runTest {
        val log = HealthLog(root)
        repeat(HEALTH_MAX + HEALTH_SLACK - 1) { log.add(device, sample(start + it * 60L)) }
        val late = start + 30L * 24 * 3600
        log.add(device, sample(late))
        assertEquals(listOf(late), log.read(device, 0).map { it.t })
        assertEquals(HEALTH_RECORD.toLong(), fileSize())
    }

    @Test
    fun partialRecordIsCutOnLoad() = runTest {
        HealthLog(root).add(device, sample(start))
        SystemFileSystem.sink(Path(root, device, "health.bin"), append = true).use { it.write(Buffer().apply { write(ByteArray(7)) }, 7) }
        val log = HealthLog(root)
        log.add(device, sample(start + 60))
        assertEquals(listOf(start, start + 60), log.read(device, 0).map { it.t })
        assertEquals(listOf(device), log.devices())
    }

    @Test
    fun checkinHealthIsOptional() {
        val c = hubJson.decodeFromString<Checkin>(
            """{"device":"ab","battery_pct":80,"health":{"uptime_s":61,"heap_free":95000,"heap_min":85000,"heap_largest":60000,"psram_free":6000000,"psram_min":5900000,"stack_main":2100,"stack_net":1800,"stack_ble":1500,"cpu_filter_pm":120,"cpu_opus_pm":340,"cpu_vad_pm":210,"opus_max_us":9100,"overflows":0,"temp_c":41.5}}""",
        )
        assertEquals(Health(61, 95000, 85000, 60000, 6000000, 5900000, 2100, 1800, 1500, 120, 340, 210, 9100, 0, 41.5f), c.health)
        assertNull(hubJson.decodeFromString<Checkin>("""{"device":"ab"}""").health)
        assertEquals(80, HealthSample.of(c, start).batteryPct)
    }

    @Test
    fun downsampleKeepsAtMostMaxPoints() {
        val points = List(1000) { it.toLong() to it.toFloat() } + (2000L to Float.NaN)
        val d = downsample(points, 300)
        assertEquals(300, d.size)
        assertEquals(0f, d.first().second, 2f)
        assertEquals(998f, d.last().second, 2f)
        assertEquals(listOf(1L to 1f), downsample(listOf(1L to 1f, 2L to Float.NaN), 300))
    }
}
