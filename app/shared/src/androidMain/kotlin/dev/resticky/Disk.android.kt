package dev.resticky

import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

actual fun sync(path: Path) {
    val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun fileStamp(path: Path): Long? = try {
    val a = Files.readAttributes(java.nio.file.Path.of(path.toString()), BasicFileAttributes::class.java)
    a.lastModifiedTime().to(TimeUnit.NANOSECONDS) * 31 + a.size()
} catch (_: IOException) {
    null
}
