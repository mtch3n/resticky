package dev.resticky

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
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

    private fun fileSize() = SystemFileSystem.metadataOrNull(Path(root, device, HEALTH_FILE))?.size ?: 0L

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
        val h = Health(
            3600, 95000, 85000, 60000, 6_000_000, 5_500_000, 2100, 1800, 1500, 120, 340, 210, 9100, 2, 41.5f,
            audioKeepPm = 420, earlyChunks = 1, lostChunks = 2, spoolWriteErrors = 3, spoolCorrupt = 4, bleFastS = 50, shutdownLost = 5, bleTxBytes = 5_000_000_000,
        )
        val s = HealthSample(start, 77, 4012, -31, true, 3, 1, -61, h, "0.14.9")
        HealthLog(root).add(device, s)
        assertEquals(s, HealthLog(root).read(device, 0).single())
        HealthLog(root).add(device, sample(start + 60))
        assertNull(HealthLog(root).read(device, start + 1).single().health)
    }

    @Test
    fun oldRecordsStillLoad() = runTest {
        val old = Buffer().apply {
            writeLongLe(start)
            List(23) { if (it == 0) 64 else 0 }.forEach { writeIntLe(it) }
        }
        SystemFileSystem.createDirectories(Path(root, device))
        SystemFileSystem.sink(Path(root, device, "health.bin")).use { it.write(old, old.size) }
        assertEquals(HEALTH_RECORD_V1.toLong(), 8L + 23 * 4)
        assertEquals(listOf(device), HealthLog(root).devices())
        assertEquals(listOf(HealthSample(start, 64)), HealthLog(root).read(device, 0))
        val log = HealthLog(root)
        log.add(device, HealthSample(start + 60, 63, firmware = "0.15.0"))
        assertEquals(listOf("", "0.15.0"), log.read(device, 0).map { it.firmware })
        assertFalse(SystemFileSystem.exists(Path(root, device, "health.bin")))
        assertEquals(2L * HEALTH_RECORD, fileSize())
    }

    @Test
    fun version2RecordsStillLoad() = runTest {
        val v2 = Buffer().apply {
            writeLongLe(start)
            List(23) { listOf(70, 4000, -30, 1, 2, 1, -60, 1, 600)[it.coerceAtMost(8)] }.forEachIndexed { i, v -> writeIntLe(if (i > 8) 7 else v) }
            write("0.15.3".encodeToByteArray().copyOf(HEALTH_FIRMWARE))
        }
        assertEquals(HEALTH_RECORD_V2.toLong(), v2.size)
        SystemFileSystem.createDirectories(Path(root, device))
        SystemFileSystem.sink(Path(root, device, "health2.bin")).use { it.write(v2, v2.size) }
        assertEquals(listOf(device), HealthLog(root).devices())
        val old = HealthLog(root).read(device, 0).single()
        assertEquals(HealthSample(start, 70, 4000, -30, true, 2, 1, -60, Health(600, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 0.7f), "0.15.3"), old)
        val log = HealthLog(root)
        log.add(device, HealthSample(start + 60, 69, health = Health(lostChunks = 1), firmware = "0.16.0"))
        assertEquals(listOf(0, 1), log.read(device, 0).map { it.health?.lostChunks })
        assertFalse(SystemFileSystem.exists(Path(root, device, "health2.bin")))
        assertEquals(2L * HEALTH_RECORD, fileSize())
    }

    @Test
    fun lossCountersThatGrowAreReported() {
        fun at(min: Int, uptime: Int, lost: Int, dropped: Int = 0, corrupt: Int = 0) =
            HealthSample(start + min * 60L, droppedChunks = dropped, health = Health(uptimeS = uptime, lostChunks = lost, spoolCorrupt = corrupt))
        assertEquals(emptyList(), lossGrowth(listOf(at(0, 100, 2), at(1, 160, 2), HealthSample(start + 120), at(3, 280, 2))))
        val grew = listOf(at(0, 100, 2), at(1, 160, 5, dropped = 1), at(2, 10, 1, dropped = 0), at(3, 70, 1, dropped = 0, corrupt = 1))
        assertEquals(listOf("lost" to 4L, "dropped" to 1L, "corrupt" to 1L), lossGrowth(grew.shuffled(Random(1))))
    }

    @Test
    fun checkinLossFieldsAreRead() {
        val c = hubJson.decodeFromString<Checkin>(
            """{"device":"ab","dropped_chunks":2,"health":{"uptime_s":61,"audio_keep_pm":300,"early_chunks":1,"lost_chunks":2,"spool":"ok","spool_write_errors":3,"spool_corrupt":4,"ble_fast_s":9,"ble_tx_bytes":123456,"shutdown_lost":1}}""",
        )
        assertEquals(Health(61, audioKeepPm = 300, earlyChunks = 1, lostChunks = 2, spoolWriteErrors = 3, spoolCorrupt = 4, bleFastS = 9, shutdownLost = 1, bleTxBytes = 123456), c.health)
        assertEquals(2, HealthSample.of(c, start).droppedChunks)
    }

    private fun run(from: Long, minutes: Int, startPct: Int, pctPerHour: Int, fw: String) =
        List(minutes + 1) { HealthSample(from + it * 60L, startPct - pctPerHour * it / 60, currentMa = -20, firmware = fw) }

    @Test
    fun drainUsesOnlyLongDischargeStretches() {
        val a = run(start, 120, 100, 3, "0.14.9")
        val short = run(start + 3 * 3600, 50, 90, 6, "0.14.9")
        val charging = List(60) { HealthSample(start + 5 * 3600 + it * 60L, 50 + it / 3, currentMa = 400, firmware = "0.14.9") }
        val b = run(start + 7 * 3600, 180, 80, 2, "0.15.0")
        val d = drainRates(a + short + charging + b)
        assertEquals(listOf("0.14.9", "0.15.0"), d.map { it.firmware })
        assertEquals(3f, d[0].pctPerHour!!, 0.01f)
        assertEquals(2f, d[0].hours, 0.01f)
        assertEquals(100f / 3, d[0].fullHours!!, 0.1f)
        assertEquals(2f, d[1].pctPerHour!!, 0.01f)
        assertEquals(3f, d[1].hours, 0.01f)
    }

    @Test
    fun drainSplitsAtGapsAndRises() {
        val gap = run(start, 40, 100, 3, "x") + run(start + 40 * 60 + 31 * 60, 40, 98, 3, "x")
        val d = drainRates(gap).single()
        assertNull(d.pctPerHour)
        assertEquals(0f, d.hours)
        assertNull(d.fullHours)
        val rise = run(start, 40, 80, 3, "x") + run(start + 41 * 60, 40, 90, 3, "x")
        assertNull(drainRates(rise).single().pctPerHour)
        assertEquals(emptyList(), drainRates(emptyList()))
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
        SystemFileSystem.sink(Path(root, device, HEALTH_FILE), append = true).use { it.write(Buffer().apply { write(ByteArray(7)) }, 7) }
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
