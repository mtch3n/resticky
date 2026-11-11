package dev.resticky

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

val PHOTO_NEAR = 10.minutes
const val PHOTO_MAX_PX = 2048

@Serializable
data class Photo(
    @SerialName("offset_ms") val offsetMs: Long,
    val file: String,
    val text: String = "",
    @SerialName("taken_ms") val takenMs: Long = 0,
)

fun photoOffset(s: Session, taken: Instant): Long = (taken - s.started).inWholeMilliseconds.coerceIn(0, maxOf(s.durationMs, s.endMs()))

fun linkPhoto(sessions: List<Session>, taken: Instant): Session? {
    val withAudio = sessions.filter { it.chunks.isNotEmpty() }
    withAudio.filter { taken >= it.started && taken <= it.lastChunk + SESSION_IDLE }.maxByOrNull { it.started }?.let { return it }
    return withAudio.map { s ->
        s to when {
            taken < s.started -> s.started - taken
            else -> taken - s.lastChunk
        }
    }.filter { it.second <= PHOTO_NEAR }.minByOrNull { it.second }?.first
}
