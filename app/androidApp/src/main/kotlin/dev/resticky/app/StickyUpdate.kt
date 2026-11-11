package dev.resticky.app

import dev.resticky.FRAME_OTA_BEGIN
import dev.resticky.FRAME_OTA_CANCEL
import dev.resticky.FRAME_OTA_DATA
import dev.resticky.FRAME_OTA_END
import dev.resticky.OTA_BLOCK
import dev.resticky.OtaBegin
import dev.resticky.OtaStatus
import dev.resticky.frame
import dev.resticky.hubJson
import dev.resticky.otaData
import dev.resticky.otaMessage
import dev.resticky.otaWindowOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val STALL_MS = 20_000L

sealed interface FwState {
    data object Idle : FwState
    data object Checking : FwState
    data class Downloading(val version: String, val percent: Int) : FwState
    data class Sending(val version: String, val percent: Int, val kbps: Int) : FwState
    data class Installing(val version: String) : FwState
    data class Restarting(val version: String) : FwState
    data class Updated(val version: String) : FwState
    data class RolledBack(val version: String) : FwState
    data class Failed(val message: String) : FwState
    data object Cancelled : FwState
}

private class UpdateFailed(message: String) : Exception(message)

class StickyUpdate(private val app: HubApp) {
    val state = MutableStateFlow<FwState>(FwState.Idle)
    private var shown = ""
    private var job: Job? = null
    @Volatile private var began = false

    private val busy get() = job?.isActive == true

    private fun set(s: FwState) {
        state.value = s
        val line = when (s) {
            is FwState.Downloading -> "downloading ${s.version} ${s.percent / 10 * 10}%"
            is FwState.Sending -> "sending ${s.version} ${s.percent / 10 * 10}%"
            else -> s.toString()
        }
        if (line != shown) {
            shown = line
            log("INFO sticky update state=$line")
        }
    }

    fun check() {
        if (busy) return
        job = app.scope.launch {
            set(FwState.Checking)
            val release = runCatching { app.releases.check() }.onFailure { log("WARN sticky update check failed err=${it.message}") }.getOrNull()
            log("INFO sticky update check result latest=${release?.version} firmware=${release?.firmware?.first} device=${sticky()?.firmware}")
            set(FwState.Idle)
        }
    }

    fun update() {
        if (busy) return
        job = app.scope.launch {
            began = false
            try {
                set(run())
            } catch (e: CancellationException) {
                withContext(NonCancellable) { stop() }
                set(FwState.Cancelled)
            } catch (e: Throwable) {
                withContext(NonCancellable) { stop() }
                set(FwState.Failed(if (e is UpdateFailed) e.message.orEmpty() else "Update failed: ${e.message}"))
            } finally {
                app.ble.updating = false
            }
        }
    }

    fun cancel() {
        job?.takeIf { it.isActive }?.cancel()
    }

    private fun stop() {
        if (!began) return
        began = false
        app.ble.send(frame(FRAME_OTA_CANCEL, ByteArray(0)))
        log("INFO sticky update ota_cancel sent")
    }

    private fun fail(message: String): Nothing = throw UpdateFailed(message)

    private suspend fun sticky() = app.hub.devices().values.maxByOrNull { it.seen ?: kotlin.time.Instant.DISTANT_PAST }

    private fun status(): OtaStatus? = app.ble.otaStatus.value

    private suspend fun awaitState(states: Set<String>, timeoutMs: Long): OtaStatus? =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                status()?.takeIf { it.state in states }?.let { return@withTimeoutOrNull it }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private suspend fun run(): FwState {
        val release = app.releases.latest.value ?: app.releases.check() ?: fail("No release found")
        val (version, asset) = release.firmware ?: fail("The release has no firmware")
        val ble = app.ble
        val address = ble.state.value.address ?: fail("No Sticky is paired")
        val target = sticky()
        val from = target?.firmware.orEmpty()
        val file = File(app.firmware.path(version).toString())
        file.delete()
        set(FwState.Downloading(version, 0))
        app.releases.download(asset, app.firmware.path(version)) { done, total ->
            set(FwState.Downloading(version, if (total > 0) (done * 100 / total).toInt() else 0))
        }
        val size = file.length()
        if (size != asset.size) fail("The download has ${size} bytes, the release says ${asset.size}")
        if (!ble.ready || ble.state.value.address != address) fail("The Sticky is not connected over Bluetooth")
        ble.updating = true
        ble.otaStatus.value = null
        set(FwState.Sending(version, 0, 0))
        log("INFO sticky update ota_begin version=$version size=$size from=$from address=${address.takeLast(5)}")
        if (!ble.sendDrained(frame(FRAME_OTA_BEGIN, hubJson.encodeToString(OtaBegin(version, size.toInt())).encodeToByteArray()))) fail("The Bluetooth link closed")
        val begin = awaitState(setOf("ready", "busy", "battery", "error", "cancelled"), 10_000) ?: fail("The Sticky did not answer")
        if (begin.state != "ready") fail(otaMessage(begin.state))
        began = true
        val start = System.currentTimeMillis()
        var acked = 0L
        var sentEnd = 0L
        var lastProgress = start
        file.inputStream().buffered().use { input ->
            val block = ByteArray(OTA_BLOCK)
            while (acked < size) {
                val s = status()
                if (s != null && s.state in setOf("error", "cancelled")) fail(otaMessage(s.state))
                if (s?.state == "writing" && s.offset > acked) {
                    acked = s.offset
                    lastProgress = System.currentTimeMillis()
                    val seconds = (lastProgress - start).coerceAtLeast(1) / 1000.0
                    set(FwState.Sending(version, (acked * 100 / size).toInt(), (acked / 1024 / seconds).toInt()))
                }
                if (!ble.ready || ble.state.value.address != address) fail("The Bluetooth link closed")
                if (System.currentTimeMillis() - lastProgress > STALL_MS) fail("The Sticky stopped answering")
                val next = minOf(OTA_BLOCK.toLong(), size - sentEnd).toInt()
                if (sentEnd < size && otaWindowOpen(sentEnd, acked, next)) {
                    val n = input.readNBytes(block, 0, next)
                    if (n != next) fail("The firmware file is short")
                    if (!ble.send(frame(FRAME_OTA_DATA, otaData(sentEnd.toInt(), block.copyOf(n))))) fail("The Bluetooth link closed")
                    sentEnd += n
                } else delay(5)
            }
        }
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        log("INFO sticky update sent bytes=$size seconds=$seconds rate_kbs=${"%.1f".format(size / 1024 / seconds)}")
        if (!ble.sendDrained(frame(FRAME_OTA_END, ByteArray(0)))) fail("The Bluetooth link closed")
        set(FwState.Installing(version))
        val end = awaitState(setOf("ok", "error", "cancelled"), 60_000) ?: fail("The Sticky did not confirm the image")
        if (end.state != "ok") fail(otaMessage(end.state))
        began = false
        ble.updating = false
        set(FwState.Restarting(version))
        val after = Clock.System.now()
        val first = withTimeoutOrNull(5.minutes) {
            while (true) {
                val c = sticky()
                if (c != null && (target == null || c.device == target.device) && (c.seen ?: after) > after + 2.seconds) return@withTimeoutOrNull c.firmware
                delay(2000)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: fail("The Sticky did not connect again")
        log("INFO sticky update first check-in firmware=$first")
        if (first != version) return FwState.RolledBack(first.ifEmpty { from })
        set(FwState.Updated(version))
        val rolled = withTimeoutOrNull(4.minutes) {
            while (true) {
                val v = sticky()?.firmware
                if (v != null && v != version) return@withTimeoutOrNull v
                delay(5000)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return if (rolled != null) FwState.RolledBack(rolled) else FwState.Updated(version)
    }
}
