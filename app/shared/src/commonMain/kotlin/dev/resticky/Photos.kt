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

fun photoOffset(s: Session, taken: Instant): Long {
    val capture = (taken - s.started).inWholeMilliseconds
    val offsets = s.offsets()
    val seq = offsets.keys.filter { s.captureMs(offsets.getValue(it)) <= capture }.maxByOrNull { s.captureMs(offsets.getValue(it)) }
        ?: return capture.coerceIn(0, s.endMs())
    val at = offsets.getValue(seq)
    return (at + capture - s.captureMs(at)).coerceAtMost(at + (s.chunkMs[seq] ?: CHUNK_MS)).coerceIn(0, s.endMs())
}

fun linkPhoto(sessions: List<Session>, taken: Instant): Session? {
    val withAudio = sessions.filter { it.chunks.isNotEmpty() }
    withAudio.filter { taken >= it.wallAt(0) && taken <= it.wallAt(it.endMs()) + SESSION_IDLE }.maxByOrNull { it.wallAt(0) }?.let { return it }
    return withAudio.map { s ->
        s to when {
            taken < s.wallAt(0) -> s.wallAt(0) - taken
            else -> taken - s.wallAt(s.endMs())
        }
    }.filter { it.second <= PHOTO_NEAR }.minByOrNull { it.second }?.first
}
