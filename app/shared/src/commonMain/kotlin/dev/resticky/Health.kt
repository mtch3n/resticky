package dev.resticky

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readLongLe
import kotlinx.io.writeIntLe
import kotlinx.io.writeLongLe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

const val HEALTH_MAX = 10_080
const val HEALTH_SLACK = 1_440
const val HEALTH_MAX_HOURS = 168
const val HEALTH_RECORD_V1 = 100
const val HEALTH_RECORD_V2 = 132
const val HEALTH_RECORD = 168
const val HEALTH_FIRMWARE = 32
const val HEALTH_FILE = "health3.bin"
private val HEALTH_OLD = listOf("health.bin" to HEALTH_RECORD_V1, "health2.bin" to HEALTH_RECORD_V2)
private const val HEALTH_INTS = 23
private const val HEALTH_LOSS_INTS = 7
private const val DRAIN_GAP_S = 30 * 60L
private const val DRAIN_MIN_S = 3600L

val healthJson = Json(hubJson) { encodeDefaults = true }

@Serializable
data class Health(
    @SerialName("uptime_s") val uptimeS: Int = 0,
    @SerialName("heap_free") val heapFree: Int = 0,
    @SerialName("heap_min") val heapMin: Int = 0,
    @SerialName("heap_largest") val heapLargest: Int = 0,
    @SerialName("psram_free") val psramFree: Int = 0,
    @SerialName("psram_min") val psramMin: Int = 0,
    @SerialName("stack_main") val stackMain: Int = 0,
    @SerialName("stack_net") val stackNet: Int = 0,
    @SerialName("stack_ble") val stackBle: Int = 0,
    @SerialName("cpu_filter_pm") val cpuFilterPm: Int = 0,
    @SerialName("cpu_opus_pm") val cpuOpusPm: Int = 0,
    @SerialName("cpu_vad_pm") val cpuVadPm: Int = 0,
    @SerialName("opus_max_us") val opusMaxUs: Int = 0,
    val overflows: Int = 0,
    @SerialName("temp_c") val tempC: Float = 0f,
    val boot: String = "",
    @SerialName("audio_keep_pm") val audioKeepPm: Int = 0,
    @SerialName("early_chunks") val earlyChunks: Int = 0,
    @SerialName("lost_chunks") val lostChunks: Int = 0,
    @SerialName("spool_write_errors") val spoolWriteErrors: Int = 0,
    @SerialName("spool_corrupt") val spoolCorrupt: Int = 0,
    @SerialName("ble_fast_s") val bleFastS: Int = 0,
    @SerialName("shutdown_lost") val shutdownLost: Int = 0,
    @SerialName("ble_tx_bytes") val bleTxBytes: Long = 0,
)

@Serializable
data class HealthSample(
    val t: Long,
    @SerialName("battery_pct") val batteryPct: Int = 0,
    @SerialName("voltage_mv") val voltageMv: Int = 0,
    @SerialName("current_ma") val currentMa: Int = 0,
    val recording: Boolean = false,
    @SerialName("outbox_chunks") val outboxChunks: Int = 0,
    @SerialName("dropped_chunks") val droppedChunks: Int = 0,
    @SerialName("wifi_rssi") val wifiRssi: Int = 0,
    val health: Health? = null,
    val firmware: String = "",
) {
    companion object {
        fun of(c: Checkin, t: Long) = HealthSample(
            t, c.batteryPct, c.voltageMv, c.currentMa, c.recording,
            c.outboxChunks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            c.droppedChunks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            c.wifiRssi, c.health, c.firmware,
        )
    }
}

private fun Buffer.writeSample(s: HealthSample) {
    writeLongLe(s.t)
    val h = s.health ?: Health()
    listOf(
        s.batteryPct, s.voltageMv, s.currentMa, if (s.recording) 1 else 0, s.outboxChunks, s.droppedChunks, s.wifiRssi,
        if (s.health != null) 1 else 0,
        h.uptimeS, h.heapFree, h.heapMin, h.heapLargest, h.psramFree, h.psramMin, h.stackMain, h.stackNet, h.stackBle,
        h.cpuFilterPm, h.cpuOpusPm, h.cpuVadPm, h.opusMaxUs, h.overflows, (h.tempC * 10).roundToInt(),
    ).forEach { writeIntLe(it) }
    val fw = s.firmware.encodeToByteArray().copyOf(HEALTH_FIRMWARE)
    write(fw)
    listOf(h.audioKeepPm, h.earlyChunks, h.lostChunks, h.spoolWriteErrors, h.spoolCorrupt, h.bleFastS, h.shutdownLost).forEach { writeIntLe(it) }
    writeLongLe(h.bleTxBytes)
}

private fun Buffer.readSample(record: Int): HealthSample {
    val t = readLongLe()
    val v = IntArray(HEALTH_INTS) { readIntLe() }
    val firmware = if (record == HEALTH_RECORD_V1) "" else readByteArray(HEALTH_FIRMWARE).decodeToString().trimEnd('\u0000')
    val loss = if (record == HEALTH_RECORD) IntArray(HEALTH_LOSS_INTS) { readIntLe() } else IntArray(HEALTH_LOSS_INTS)
    val txBytes = if (record == HEALTH_RECORD) readLongLe() else 0L
    val health = if (v[7] == 0) null else Health(
        v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21], v[22] / 10f,
        audioKeepPm = loss[0], earlyChunks = loss[1], lostChunks = loss[2], spoolWriteErrors = loss[3], spoolCorrupt = loss[4],
        bleFastS = loss[5], shutdownLost = loss[6], bleTxBytes = txBytes,
    )
    return HealthSample(t, v[0], v[1], v[2], v[3] != 0, v[4], v[5], v[6], health, firmware)
}

class HealthLog(private val root: Path) {
    private val mutex = Mutex()
    private val counts = mutableMapOf<String, Int>()
    private val minutes = mutableMapOf<String, Long>()

    private fun path(device: String) = Path(root, device, HEALTH_FILE)

    private fun readFile(p: Path, record: Int): List<HealthSample> {
        if (!SystemFileSystem.exists(p)) return emptyList()
        val buf = Buffer()
        SystemFileSystem.source(p).use { src -> while (src.readAtMostTo(buf, 1L shl 16) >= 0) Unit }
        val n = (buf.size / record).toInt()
        return List(n) { buf.readSample(record) }
    }

    private fun old(device: String) = HEALTH_OLD.map { (name, record) -> Path(root, device, name) to record }

    private fun load(device: String): List<HealthSample> =
        (old(device) + (path(device) to HEALTH_RECORD)).flatMap { (p, record) -> readFile(p, record) }.sortedBy { it.t }

    private fun rewrite(device: String, samples: List<HealthSample>) {
        val p = path(device)
        val tmp = Path("$p.tmp")
        val buf = Buffer()
        samples.forEach { buf.writeSample(it) }
        SystemFileSystem.sink(tmp).use { it.write(buf, buf.size) }
        SystemFileSystem.atomicMove(tmp, p)
    }

    private fun compact(samples: List<HealthSample>, now: Long): List<HealthSample> =
        samples.filter { it.t > now - HEALTH_MAX_HOURS * 3600L }.takeLast(HEALTH_MAX)

    suspend fun add(device: String, sample: HealthSample): Boolean = mutex.withLock {
        val minute = sample.t.floorDiv(60L)
        if (device !in counts) {
            val p = path(device)
            val size = SystemFileSystem.metadataOrNull(p)?.size ?: 0L
            val samples = load(device)
            val older = old(device).map { it.first }.filter { SystemFileSystem.exists(it) }
            if (size % HEALTH_RECORD != 0L || older.isNotEmpty()) {
                rewrite(device, samples)
                older.forEach { SystemFileSystem.delete(it) }
            }
            counts[device] = samples.size
            samples.lastOrNull()?.let { minutes[device] = it.t.floorDiv(60L) }
        }
        if (minutes[device] == minute) return@withLock false
        SystemFileSystem.createDirectories(Path(root, device))
        val buf = Buffer().apply { writeSample(sample) }
        SystemFileSystem.sink(path(device), append = true).use { it.write(buf, buf.size) }
        minutes[device] = minute
        val count = counts.getValue(device) + 1
        counts[device] = if (count >= HEALTH_MAX + HEALTH_SLACK) {
            compact(load(device), sample.t).also { rewrite(device, it) }.size
        } else count
        true
    }

    suspend fun read(device: String, since: Long): List<HealthSample> = mutex.withLock {
        load(device).filter { it.t >= since }.sortedBy { it.t }
    }

    suspend fun devices(): List<String> = mutex.withLock {
        if (!SystemFileSystem.exists(root)) return@withLock emptyList()
        SystemFileSystem.list(root).filter { d -> (HEALTH_OLD.map { it.first } + HEALTH_FILE).any { SystemFileSystem.exists(Path(d, it)) } }.map { it.name }
    }
}

fun downsample(points: List<Pair<Long, Float>>, max: Int): List<Pair<Long, Float>> {
    val valid = points.filter { !it.second.isNaN() }
    if (valid.size <= max) return valid
    return List(max) { b ->
        val from = (b.toLong() * valid.size / max).toInt()
        val to = ((b + 1L) * valid.size / max).toInt()
        val bucket = valid.subList(from, to)
        bucket.sumOf { it.first } / bucket.size to (bucket.sumOf { it.second.toDouble() } / bucket.size).toFloat()
    }
}

data class Drain(val firmware: String, val pctPerHour: Float?, val hours: Float) {
    val fullHours: Float? get() = pctPerHour?.takeIf { it > 0f }?.let { 100f / it }
}

fun drainRates(samples: List<HealthSample>): List<Drain> {
    val sorted = samples.sortedBy { it.t }
    val stretches = mutableListOf<List<HealthSample>>()
    var run = mutableListOf<HealthSample>()
    for (s in sorted) {
        val prev = run.lastOrNull()
        val charging = s.currentMa > 0
        val breaks = prev != null && (s.t - prev.t > DRAIN_GAP_S || s.firmware != prev.firmware || s.batteryPct > prev.batteryPct)
        if (charging || breaks) {
            stretches += run
            run = mutableListOf()
        }
        if (!charging) run += s
    }
    stretches += run
    val used = stretches.filter { it.size > 1 && it.last().t - it.first().t >= DRAIN_MIN_S }
    return sorted.map { it.firmware }.distinct().map { fw ->
        val mine = used.filter { it.first().firmware == fw }
        val seconds = mine.sumOf { it.last().t - it.first().t }
        val drop = mine.sumOf { it.first().batteryPct - it.last().batteryPct }
        Drain(fw, if (seconds > 0) drop * 3600f / seconds else null, seconds / 3600f)
    }
}

private val LOSS_COUNTERS: List<Pair<String, (HealthSample) -> Long?>> = listOf(
    "lost" to { it.health?.lostChunks?.toLong() },
    "dropped" to { it.droppedChunks.toLong() },
    "write errors" to { it.health?.spoolWriteErrors?.toLong() },
    "corrupt" to { it.health?.spoolCorrupt?.toLong() },
    "lost at power off" to { it.health?.shutdownLost?.toLong() },
)

fun lossGrowth(samples: List<HealthSample>): List<Pair<String, Long>> {
    val sorted = samples.sortedBy { it.t }
    return LOSS_COUNTERS.mapNotNull { (name, value) ->
        var grew = 0L
        sorted.zipWithNext { p, c ->
            val pv = value(p) ?: return@zipWithNext
            val cv = value(c) ?: return@zipWithNext
            val restarted = (c.health?.uptimeS ?: 0) < (p.health?.uptimeS ?: 0) || cv < pv
            grew += if (restarted) cv else cv - pv
        }
        if (grew > 0) name to grew else null
    }
}
