package dev.resticky

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val AUDIO = setOf("opus", "ogg", "wav")

enum class Added { SESSION, CHUNK, REPEAT, CONFLICT }

class Store(val root: Path) {
    private val mutex = Mutex()

    fun dir(device: String, id: String) = Path(root, device, id)

    private fun load(device: String, id: String): Session? = runCatching {
        val text = SystemFileSystem.source(Path(dir(device, id), "meta.json")).buffered().use { it.readString() }
        hubJson.decodeFromString<Session>(text)
    }.getOrNull()

    private fun save(s: Session) = write(Path(dir(s.device, s.id), "meta.json"), hubJson.encodeToString(s).encodeToByteArray())

    fun write(path: Path, data: ByteArray) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.write(data) }
        SystemFileSystem.atomicMove(tmp, path)
    }

    fun read(path: Path): ByteArray? =
        if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered().use { it.readByteArray() } else null

    fun sessionFrames(s: Session): ByteArray? {
        val last = s.chunks.keys.maxOfOrNull { it.toInt() } ?: return null
        val files = (0..last).map { read(Path(dir(s.device, s.id), "$it.opus")) }
        if (files.all { it == null }) return null
        val out = kotlinx.io.Buffer()
        files.forEachIndexed { seq, data -> out.write(data ?: ByteArray(((s.chunkMs["$seq"] ?: CHUNK_MS) / 20 * 2).toInt())) }
        return out.readByteArray()
    }

    suspend fun addChunk(
        device: String, id: String, seq: Int, final: Boolean, durationMs: Long, data: ByteArray,
        startMs: Long = 0, capturedAt: Instant? = null,
    ): Added = mutex.withLock {
        val now = Clock.System.now()
        val m = load(device, id) ?: Session(device, id, now, now, 0, chunks = mutableMapOf())
        val file = Path(dir(device, id), "$seq.opus")
        if ("$seq" in m.chunks) {
            val stored = read(file) ?: return@withLock Added.REPEAT
            return@withLock if (stored.contentEquals(data)) Added.REPEAT else Added.CONFLICT
        }
        val first = m.chunks.isEmpty()
        if (first) m.started = capturedAt ?: (now - durationMs.milliseconds)
        write(file, data)
        m.chunks["$seq"] = PENDING
        m.durationMs += durationMs
        m.chunkMs["$seq"] = durationMs
        if (startMs > 0) m.chunkStart["$seq"] = startMs
        m.diarized = false
        m.speakersFailed = false
        m.lastChunk = now
        m.final = m.final || final
        m.ready = false
        save(m)
        if (first) Added.SESSION else Added.CHUNK
    }

    suspend fun addMarks(device: String, id: String, offsets: List<Long>): Int = mutex.withLock {
        val now = Clock.System.now()
        val m = load(device, id) ?: Session(device, id, now, now, 0, chunks = mutableMapOf())
        val added = m.addMarks(offsets)
        if (added > 0) save(m)
        added
    }

    suspend fun <T> update(device: String, id: String, change: (Session) -> T): T? = mutex.withLock {
        val m = load(device, id) ?: return@withLock null
        change(m).also { save(m) }
    }

    suspend fun updateDevice(device: String, change: (List<Session>) -> List<Session>): Int = mutex.withLock {
        val changed = change(listUnlocked(device))
        changed.forEach(::save)
        changed.size
    }

    suspend fun list(device: String): List<Session> = mutex.withLock { listUnlocked(device) }

    private fun listUnlocked(device: String): List<Session> {
        val path = Path(root, device)
        if (!SystemFileSystem.exists(path)) return emptyList()
        return SystemFileSystem.list(path)
            .filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }
            .mapNotNull { load(device, it.name) }
            .sortedByDescending { it.started }
    }

    suspend fun deleteOldAudio(cutoff: Instant) = mutex.withLock {
        if (!SystemFileSystem.exists(root)) return@withLock
        for (device in SystemFileSystem.list(root)) {
            val sessions = listUnlocked(device.name)
            val open = conversations(sessions).filterNot { it.ready() }.flatMap { c -> c.parts.map { it.session.id } }.toSet()
            for (m in sessions) {
                if (m.lastChunk >= cutoff || m.pending || m.id in open) continue
                SystemFileSystem.list(dir(m.device, m.id))
                    .filter { it.name.substringAfterLast('.') in AUDIO }
                    .forEach { SystemFileSystem.delete(it) }
            }
        }
    }

    suspend fun rerunAll(): Int = mutex.withLock {
        if (!SystemFileSystem.exists(root)) return@withLock 0
        var count = 0
        for (device in SystemFileSystem.list(root)) for (m in listUnlocked(device.name)) {
            if (m.chunks.isEmpty()) continue
            var audio = false
            for (seq in m.chunks.keys.toList()) {
                if (SystemFileSystem.exists(Path(dir(m.device, m.id), "$seq.opus"))) {
                    m.chunks[seq] = PENDING
                    m.segments.remove(seq)
                    audio = true
                } else {
                    m.chunks[seq] = cleanTranscript(m.chunks.getValue(seq))
                    m.segments[seq]?.let { old -> cleanSegments(old).let { if (it.isEmpty()) m.segments.remove(seq) else m.segments[seq] = it } }
                }
            }
            if (audio) {
                m.diarized = false
                m.speakersFailed = false
                m.speakers.clear()
            }
            m.recaps().forEach { (from, r) -> m.putRecap(from, r.copy(ready = false, input = "")) }
            save(m)
            count++
        }
        count
    }

    suspend fun all(): List<Session> = mutex.withLock {
        if (!SystemFileSystem.exists(root)) return@withLock emptyList()
        SystemFileSystem.list(root).flatMap { listUnlocked(it.name) }
    }
}
