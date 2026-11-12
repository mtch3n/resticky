package dev.resticky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

enum class Added { SESSION, CHUNK, REPEAT, RESTORED, CONFLICT }

expect fun sync(path: Path)

expect fun fileStamp(path: Path): Long?

private class Cached(val stamp: Long, val session: Session)

expect fun sha256(data: ByteArray): ByteArray

fun conflictName(seq: Int, data: ByteArray) = "$seq.conflict-${sha256(data).copyOf(8).toHexString()}.opus"

class Store(val root: Path) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, MutableMap<String, Cached>>()
    private val version = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = version

    fun dir(device: String, id: String) = Path(root, device, id)

    private fun meta(device: String, id: String) = Path(dir(device, id), "meta.json")

    private fun cached(device: String, id: String): Session? {
        val known = cache.getOrPut(device) { mutableMapOf() }
        val path = meta(device, id)
        val stamp = fileStamp(path)
        val hit = known[id]?.takeIf { it.stamp == stamp }
        if (hit != null) return hit.session
        val s = stamp?.let { runCatching { hubJson.decodeFromString<Session>(SystemFileSystem.source(path).buffered().use { it.readString() }) }.getOrNull() }
        if (s == null) known.remove(id) else known[id] = Cached(stamp, s)
        return s
    }

    private fun load(device: String, id: String): Session? = cached(device, id)?.clone()

    private fun save(s: Session, durable: Boolean = false) {
        val path = meta(s.device, s.id)
        write(path, hubJson.encodeToString(s).encodeToByteArray(), durable)
        val stamp = fileStamp(path)
        if (stamp == null) cache[s.device]?.remove(s.id) else cache.getOrPut(s.device) { mutableMapOf() }[s.id] = Cached(stamp, s)
        version.value++
    }

    fun write(path: Path, data: ByteArray, durable: Boolean = false) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$path.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.write(data) }
        if (durable) sync(tmp)
        SystemFileSystem.atomicMove(tmp, path)
        if (durable) generateSequence(path.parent) { d -> d.parent?.takeIf { "$d" != "$root" } }.forEach(::sync)
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
        startMs: Long = 0, capturedAt: Instant? = null, boot: String = "", bootUnixMs: Long = 0,
    ): Added = mutex.withLock {
        val now = Clock.System.now()
        if (boot.isNotEmpty() && bootUnixMs > 0) putAnchor(device, boot, Anchor(bootUnixMs), skip = id)
        val m = load(device, id) ?: Session(device, id, now, now, 0, chunks = mutableMapOf())
        val file = Path(dir(device, id), "$seq.opus")
        if ("$seq" in m.chunks) {
            val stored = read(file)
            if (stored == null) {
                write(file, data, durable = true)
                return@withLock Added.RESTORED
            }
            if (stored.contentEquals(data)) return@withLock Added.REPEAT
            val copy = Path(dir(device, id), conflictName(seq, data))
            if (read(copy)?.contentEquals(data) != true) write(copy, data, durable = true)
            return@withLock Added.CONFLICT
        }
        val first = m.chunks.isEmpty()
        val guess = capturedAt ?: (now - durationMs.milliseconds)
        if (first) {
            m.started = guess
            m.timeEstimated = true
        }
        write(file, data, durable = true)
        m.chunks["$seq"] = PENDING
        m.durationMs += durationMs
        m.chunkMs["$seq"] = durationMs
        if (startMs > 0) {
            m.chunkStart["$seq"] = startMs
            if (m.guessAnchorMs == null) m.guessAnchorMs = guess.toEpochMilliseconds() - startMs
        }
        if (m.boot.isEmpty()) m.boot = boot
        m.retime(anchorOf(device, m.boot))
        m.diarized = false
        m.speakersFailed = false
        m.lastChunk = now
        if (final) m.finalSeq = seq
        m.final = m.final || final
        m.ready = false
        save(m, durable = true)
        if (first) Added.SESSION else Added.CHUNK
    }

    private fun anchorsPath(device: String) = Path(root, device, "anchors.json")

    private fun anchors(device: String): Map<String, Anchor> =
        read(anchorsPath(device))?.let { runCatching { hubJson.decodeFromString<Map<String, Anchor>>(it.decodeToString()) }.getOrNull() }.orEmpty()

    private fun anchorOf(device: String, boot: String): Anchor? = if (boot.isEmpty()) null else anchors(device)[boot]

    private fun putAnchor(device: String, boot: String, a: Anchor, skip: String? = null): Boolean {
        val all = anchors(device)
        val old = all[boot]
        if (old == a || old != null && !old.estimated && a.estimated || old != null && old.estimated && a.estimated) return false
        write(anchorsPath(device), hubJson.encodeToString(all + (boot to a)).encodeToByteArray(), durable = true)
        for (s in listUnlocked(device)) if (s.boot == boot && s.id != skip) {
            s.retime(a)
            save(s, durable = true)
        }
        return true
    }

    suspend fun setAnchor(device: String, boot: String, a: Anchor): Boolean = mutex.withLock { putAnchor(device, boot, a) }

    suspend fun anchor(device: String, boot: String): Anchor? = mutex.withLock { anchorOf(device, boot) }

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
        val changed = change(listUnlocked(device).map { it.clone() })
        changed.forEach(::save)
        changed.size
    }

    suspend fun list(device: String): List<Session> = mutex.withLock { listUnlocked(device) }

    private fun listUnlocked(device: String): List<Session> {
        val path = Path(root, device)
        if (!SystemFileSystem.exists(path)) {
            cache.remove(device)
            return emptyList()
        }
        val ids = SystemFileSystem.list(path).filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }.map { it.name }
        cache[device]?.keys?.retainAll(ids.toSet())
        return ids.mapNotNull { cached(device, it) }.sortedByDescending { it.started }
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

    suspend fun rerunAll(current: String): Int = mutex.withLock {
        if (!SystemFileSystem.exists(root)) return@withLock 0
        var count = 0
        for (device in SystemFileSystem.list(root)) for (shared in listUnlocked(device.name)) {
            val stale = shared.chunks.keys.filter { seq ->
                shared.chunks[seq] != PENDING && shared.chunkEngine[seq] != current && SystemFileSystem.exists(Path(dir(shared.device, shared.id), "$seq.opus"))
            }
            if (stale.isEmpty()) continue
            val m = shared.clone()
            for (seq in stale) {
                m.chunks[seq] = PENDING
                m.segments.remove(seq)
                m.chunkEngine.remove(seq)
                m.chunkRetry.remove(seq)
            }
            m.diarized = false
            m.speakersFailed = false
            m.speakers.clear()
            m.userNamed.clear()
            m.fixes.removeAll { !it.undone }
            m.recaps().forEach { (from, r) -> m.putRecap(from, r.copy(ready = false, input = "")) }
            save(m)
            count++
        }
        count
    }

    suspend fun all(): List<Session> = mutex.withLock {
        if (!SystemFileSystem.exists(root)) {
            cache.clear()
            return@withLock emptyList()
        }
        val devices = SystemFileSystem.list(root).filter { SystemFileSystem.metadataOrNull(it)?.isDirectory == true }.map { it.name }
        cache.keys.retainAll(devices.toSet())
        devices.flatMap { listUnlocked(it) }
    }
}
