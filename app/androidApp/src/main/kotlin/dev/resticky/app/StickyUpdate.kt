package dev.resticky.app

import dev.resticky.FRAME_OTA_BEGIN
import dev.resticky.FRAME_OTA_END
import dev.resticky.OtaBegin
import dev.resticky.frame
import dev.resticky.hubJson
import dev.resticky.otaFrames
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

sealed interface FwState {
    data object Idle : FwState
    data object Checking : FwState
    data class Downloading(val version: String, val percent: Int) : FwState
    data class Sending(val version: String, val percent: Int) : FwState
    data class Restarting(val version: String) : FwState
    data class Updated(val version: String) : FwState
    data class RolledBack(val version: String) : FwState
    data class Failed(val message: String) : FwState
}

private class UpdateFailed(message: String) : Exception(message)

class StickyUpdate(private val app: HubApp) {
    val state = MutableStateFlow<FwState>(FwState.Idle)
    private var job: Job? = null

    private val busy get() = job?.isActive == true

    fun check() {
        if (busy) return
        job = app.scope.launch {
            state.value = FwState.Checking
            app.releases.check()
            app.releases.mirrorFirmware(app.firmware)
            state.value = FwState.Idle
        }
    }

    fun update() {
        if (busy) return
        job = app.scope.launch {
            state.value = runCatching { run() }.getOrElse { FwState.Failed(if (it is UpdateFailed) it.message.orEmpty() else "Update failed: ${it.message}") }
            if (state.value is FwState.Failed) log("WARN sticky update failed ${(state.value as FwState.Failed).message}")
        }
    }

    private fun fail(message: String): Nothing = throw UpdateFailed(message)

    private suspend fun sticky() = app.hub.devices().values.maxByOrNull { it.seen ?: kotlin.time.Instant.DISTANT_PAST }

    private suspend fun run(): FwState {
        val release = app.releases.latest.value ?: app.releases.check() ?: fail("No release found")
        val (version, asset) = release.firmware ?: fail("The release has no firmware")
        val from = sticky()?.firmware.orEmpty()
        val image = app.firmware.read(version) ?: app.releases.download(asset) { done, total ->
            state.value = FwState.Downloading(version, if (total > 0) (done * 100 / total).toInt() else 0)
        }.also { app.firmware.write(version, it) }
        val ble = app.ble
        if (!ble.ready) fail("The Sticky is not connected over Bluetooth")
        ble.otaStatus.value = null
        state.value = FwState.Sending(version, 0)
        if (!ble.sendDrained(frame(FRAME_OTA_BEGIN, hubJson.encodeToString(OtaBegin(version, image.size)).encodeToByteArray()))) fail("The Bluetooth link closed")
        val ready = withTimeoutOrNull(20.seconds) { ble.otaStatus.first { it != null && it.state in setOf("ready", "error") } }
        if (ready?.state != "ready") fail(if (ready == null) "The Sticky did not answer" else "The Sticky reported an error")
        log("INFO sticky update sending version=$version bytes=${image.size}")
        val frames = otaFrames(image)
        frames.forEachIndexed { i, f ->
            if (ble.otaStatus.value?.state == "error") fail("The Sticky reported an error while writing")
            if (!ble.sendDrained(f)) fail("The Bluetooth link closed")
            state.value = FwState.Sending(version, maxOf((i + 1) * 100 / frames.size, ble.otaStatus.value?.percent ?: 0))
        }
        if (!ble.sendDrained(frame(FRAME_OTA_END, ByteArray(0)))) fail("The Bluetooth link closed")
        val end = withTimeoutOrNull(60.seconds) { ble.otaStatus.first { it != null && it.state in setOf("ok", "error") } }
        if (end?.state != "ok") fail(if (end == null) "The Sticky did not confirm the image" else "The Sticky reported an error while checking the image")
        state.value = FwState.Restarting(version)
        val after = Clock.System.now()
        val first = withTimeoutOrNull(5.minutes) {
            while (true) {
                val c = sticky()
                if (c != null && (c.seen ?: after) > after + 2.seconds) return@withTimeoutOrNull c.firmware
                delay(2000)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: fail("The Sticky did not connect again")
        if (first != version) return FwState.RolledBack(first.ifEmpty { from })
        state.value = FwState.Updated(version)
        log("INFO sticky updated version=$version")
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
