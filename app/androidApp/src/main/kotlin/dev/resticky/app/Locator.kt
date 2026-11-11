package dev.resticky.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import dev.resticky.PLACE_MAX_AGE
import kotlin.time.Duration.Companion.nanoseconds
import dev.resticky.Place
import dev.resticky.roundCoordinate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class Locator(private val context: Context) {
    private val prefs = context.getSharedPreferences("location", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(LocationManager::class.java)

    val allowed: Boolean get() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun stored(): Place? =
        if (prefs.contains("lat")) Place("", prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble()) else null

    suspend fun update(): Place? {
        if (!allowed) return stored()
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, null, context.mainExecutor) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        } ?: runCatching { manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull()
        if (fresh != null) {
            prefs.edit().putFloat("lat", roundCoordinate(fresh.latitude).toFloat()).putFloat("lon", roundCoordinate(fresh.longitude).toFloat()).apply()
            log("INFO location updated")
        }
        return stored()
    }

    suspend fun locate(): Place? = update()

    val precise: Boolean get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val background: Boolean get() = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canTag(onScreen: Boolean): Boolean = precise && (onScreen || background)

    private suspend fun current(quality: Int): Location? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val request = LocationRequest.Builder(0).setQuality(quality).setDurationMillis(FIX_TIMEOUT_MS).build()
            runCatching {
                manager.getCurrentLocation(LocationManager.FUSED_PROVIDER, request, signal, context.mainExecutor) { cont.resume(it) }
            }.onFailure { cont.resume(null) }
        }
    }

    suspend fun fix(onScreen: Boolean): Location? {
        if (!canTag(onScreen)) return null
        val last = runCatching { manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull()
        if (last != null && (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos).nanoseconds <= PLACE_MAX_AGE) return last
        return current(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
    }

    suspend fun here(): Location? = if (precise) current(LocationRequest.QUALITY_HIGH_ACCURACY) else null
}

private const val FIX_TIMEOUT_MS = 30_000L
