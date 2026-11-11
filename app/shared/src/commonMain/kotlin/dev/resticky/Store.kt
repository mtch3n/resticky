package dev.resticky

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

private val AUDIO = setOf("opus", "ogg", "wav")

class Store(val root: Path) {
    private val mutex = Mutex()

    fun dir(device: String, id: String) = Path(root, device, id)

    private fun load(device: String, id: String): Session? = runCatching {
        val text = SystemFileSystem.source(Path(dir(device, id), "meta.json")).buffered().use { it.readString() }
        hubJson.decodeFromString<Session>(text)
    }.getOrNull()

    private fun save(s: Session) {
        val path = Path(dir(s.device, s.id), "meta.json")
        val tmp = Path(path.toString() + ".tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(hubJson.encodeToString(s)) }
        SystemFileSystem.atomicMove(tmp, path)
    }

    fun write(path: Path, data: ByteArray) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(path).buffered().use { it.write(data) }
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

    suspend fun addChunk(device: String, id: String, seq: Int, final: Boolean, durationMs: Long, data: ByteArray): Boolean = mutex.withLock {
        SystemFileSystem.createDirectories(dir(device, id))
        val now = Clock.System.now()
        val m = load(device, id) ?: Session(device, id, now, now, 0, chunks = mutableMapOf())
        val first = m.chunks.isEmpty()
        if (first) m.started = now - durationMs.milliseconds
        write(Path(dir(device, id), "$seq.opus"), data)
        if ("$seq" !in m.chunks) {
            m.chunks["$seq"] = PENDING
            m.durationMs += durationMs
        }
        m.chunkMs["$seq"] = durationMs
        m.diarized = false
        m.lastChunk = now
        m.final = m.final || final
        m.ready = false
        save(m)
        first
    }

    suspend fun addMarks(device: String, id: String, offsets: List<Long>): Int = mutex.withLock {
        val now = Clock.System.now()
        val m = load(device, id) ?: Session(device, id, now, now, 0, chunks = mutableMapOf())
        val added = m.addMarks(offsets)
        if (added > 0) {
            SystemFileSystem.createDirectories(dir(device, id))
            save(m)
        }
        added
    }

    suspend fun <T> update(device: String, id: String, change: (Session) -> T): T? = mutex.withLock {
        val m = load(device, id) ?: return@withLock null
        change(m).also { save(m) }
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
        for (device in SystemFileSystem.list(root)) for (m in listUnlocked(device.name)) {
            if (m.lastChunk >= cutoff) continue
            SystemFileSystem.list(dir(m.device, m.id))
                .filter { it.name.substringAfterLast('.') in AUDIO }
                .forEach { SystemFileSystem.delete(it) }
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
                m.speakers.clear()
            }
            m.title = ""
            m.summary = ""
            m.todos = emptyList()
            m.engine = ""
            m.done.clear()
            m.todoChanges.clear()
            m.ready = false
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
