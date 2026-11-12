package dev.resticky

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

const val ELSEWHERE = "Elsewhere"
const val PLACE_RADIUS = 150
val PLACE_RADII = listOf(100, PLACE_RADIUS, 300)
val PLACE_MAX_AGE = 10.minutes
val PLACE_WAIT = 1.minutes

@Serializable
data class NamedPlace(val id: String, val name: String, val lat: Double, val lon: Double, @SerialName("radius_m") val radiusM: Int = PLACE_RADIUS)

data class PlaceTag(val name: String, val lat: Double?, val lon: Double?)

fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = kotlin.math.PI / 180
    val a = sin((lat2 - lat1) * r / 2).let { it * it } + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).let { it * it }
    return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

fun roundKm(v: Double): Double = (v * 100).roundToLong() / 100.0

fun placeTag(places: List<NamedPlace>, lat: Double, lon: Double): PlaceTag =
    places.map { it to distanceM(lat, lon, it.lat, it.lon) }
        .filter { (p, d) -> d <= p.radiusM }
        .minByOrNull { it.second }
        ?.let { PlaceTag(it.first.name, null, null) }
        ?: PlaceTag(ELSEWHERE, roundKm(lat), roundKm(lon))

fun chunkAge(anchorMs: Long?, startMs: Long, now: Instant): Duration? {
    if (anchorMs == null || startMs <= 0) return null
    return (now.toEpochMilliseconds() - anchorMs - startMs).takeIf { it >= 0 }?.milliseconds
}

fun placeContext(place: String): String = if (place.isEmpty() || place == ELSEWHERE || place == PENDING) "" else place
