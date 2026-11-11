package dev.resticky

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlin.time.Clock

const val RELEASES_REPO = "mtch3n/resticky"
const val APK_ASSET = "resticky-android.apk"
private const val DOWNLOAD_BUFFER = 64 * 1024
private const val LATEST = "https://api.github.com/repos/$RELEASES_REPO/releases/latest"
private val FIRMWARE_ASSET = Regex("""^resticky-firmware-(\d+\.\d+\.\d+)\.bin$""")
private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")

fun versionKey(v: String): List<Int>? = SEMVER.find(v)?.groupValues?.drop(1)?.map { it.toInt() }

fun isNewer(candidate: String, current: String): Boolean {
    val a = versionKey(candidate) ?: return false
    val b = versionKey(current) ?: return true
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

@Serializable
data class ReleaseAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
data class Release(@SerialName("tag_name") val tag: String, @SerialName("published_at") val published: String = "", val assets: List<ReleaseAsset> = emptyList()) {
    val version: String get() = tag.removePrefix("v")
    val firmware: Pair<String, ReleaseAsset>? get() = assets.firstNotNullOfOrNull { a -> FIRMWARE_ASSET.matchEntire(a.name)?.let { it.groupValues[1] to a } }
    val apk: ReleaseAsset? get() = assets.firstOrNull { it.name == APK_ASSET }
}

@Serializable
private class ReleaseCache(val etag: String = "", @SerialName("checked_ms") val checkedMs: Long = 0, @SerialName("retry_ms") val retryMs: Long = 0, val release: Release? = null)

class FirmwareShelf(val dir: Path) {
    fun newest(): Pair<String, Path>? {
        if (!SystemFileSystem.exists(dir)) return null
        return SystemFileSystem.list(dir).filter { it.name.endsWith(".bin") }
            .mapNotNull { p -> p.name.removeSuffix(".bin").takeIf { versionKey(it) != null && SEMVER.matchEntire(it) != null }?.let { it to p } }
            .maxWithOrNull { a, b -> if (isNewer(a.first, b.first)) 1 else if (isNewer(b.first, a.first)) -1 else 0 }
    }

    fun path(version: String) = Path(dir, "$version.bin")

    fun has(version: String) = SystemFileSystem.exists(path(version))

    fun read(version: String): ByteArray? {
        if (SEMVER.matchEntire(version) == null) return null
        val p = Path(dir, "$version.bin")
        return if (SystemFileSystem.exists(p)) SystemFileSystem.source(p).buffered().use { it.readByteArray() } else null
    }

    fun write(version: String, data: ByteArray) {
        SystemFileSystem.createDirectories(dir)
        val tmp = Path(dir, "$version.bin.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.write(data) }
        SystemFileSystem.atomicMove(tmp, Path(dir, "$version.bin"))
    }
}

class Releases(private val cacheFile: Path, private val log: (String) -> Unit, private val userAgent: String = "resticky-hub/$HUB_VERSION") {
    private val http = HttpClient(CIO) {
        expectSuccess = false
        engine { requestTimeout = 120_000 }
    }
    private val lock = Mutex()
    private var cache = runCatching { hubJson.decodeFromString<ReleaseCache>(SystemFileSystem.source(cacheFile).buffered().use { it.readString() }) }.getOrElse { ReleaseCache() }
    private val _latest = MutableStateFlow(cache.release)
    val latest: StateFlow<Release?> get() = _latest
    val checked = MutableStateFlow(cache.checkedMs)

    private fun save() {
        cacheFile.parent?.let { SystemFileSystem.createDirectories(it) }
        SystemFileSystem.sink(cacheFile).buffered().use { it.writeString(hubJson.encodeToString(cache)) }
    }

    suspend fun check(): Release? = lock.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        log("INFO releases check start cached=${cache.release?.version}")
        if (now < cache.retryMs) return@withLock cache.release.also { log("WARN releases rate limited until=${cache.retryMs}") }
        val res = runCatching {
            http.get(LATEST) {
                header("User-Agent", userAgent)
                header("Accept", "application/vnd.github+json")
                if (cache.etag.isNotEmpty() && cache.release != null) header("If-None-Match", cache.etag)
            }
        }.getOrElse {
            log("WARN releases check failed err=${it.message}")
            return@withLock cache.release
        }
        cache = when (res.status) {
            HttpStatusCode.NotModified -> ReleaseCache(cache.etag, now, 0, cache.release)
            HttpStatusCode.OK -> ReleaseCache(res.headers["ETag"].orEmpty(), now, 0, hubJson.decodeFromString<Release>(res.bodyAsText()))
            HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests -> {
                val reset = res.headers["X-RateLimit-Reset"]?.toLongOrNull()?.times(1000) ?: (now + 3_600_000)
                log("WARN releases rate limited until=$reset")
                ReleaseCache(cache.etag, cache.checkedMs, reset, cache.release)
            }
            else -> {
                log("WARN releases status=${res.status.value}")
                ReleaseCache(cache.etag, now, 0, cache.release)
            }
        }
        save()
        _latest.value = cache.release
        checked.value = cache.checkedMs
        log("INFO releases checked latest=${cache.release?.version}")
        cache.release
    }

    suspend fun download(asset: ReleaseAsset, dest: Path, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val part = Path("$dest.part")
        dest.parent?.let { SystemFileSystem.createDirectories(it) }
        log("INFO download start name=${asset.name} size=${asset.size}")
        try {
            fetch(asset, part, progress)
        } catch (e: Throwable) {
            SystemFileSystem.delete(part, mustExist = false)
            throw e
        }
        SystemFileSystem.atomicMove(part, dest)
        log("INFO download done name=${asset.name}")
    }

    private suspend fun fetch(asset: ReleaseAsset, part: Path, progress: (Long, Long) -> Unit) =
        http.prepareGet(asset.url) { header("User-Agent", userAgent) }.execute { res ->
            check(res.status == HttpStatusCode.OK) { "download failed: ${res.status.value}" }
            val total = res.contentLength() ?: asset.size
            val channel = res.bodyAsChannel()
            val buf = ByteArray(DOWNLOAD_BUFFER)
            var done = 0L
            var shown = -1L
            SystemFileSystem.sink(part).buffered().use { sink ->
                while (true) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    done += n
                    progress(done, total)
                    val pct = if (total > 0) done * 100 / total else 0
                    if (pct / 10 != shown) {
                        shown = pct / 10
                        log("INFO download name=${asset.name} percent=$pct")
                    }
                }
            }
            check(total <= 0 || done == total) { "download incomplete: $done of $total bytes" }
            check(asset.size <= 0 || done == asset.size) { "download size $done is not the release size ${asset.size}" }
        }

    suspend fun mirrorFirmware(shelf: FirmwareShelf) {
        val (version, asset) = latest.value?.firmware ?: return
        if (shelf.has(version)) return
        runCatching { download(asset, shelf.path(version)) }
            .onSuccess { log("INFO firmware mirrored version=$version") }
            .onFailure { log("WARN firmware mirror failed version=$version err=${it.message}") }
    }
}
