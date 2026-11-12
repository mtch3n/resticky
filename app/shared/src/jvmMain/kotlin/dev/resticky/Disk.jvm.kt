package dev.resticky

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

actual fun sync(path: Path) {
    val p = java.nio.file.Path.of(path.toString())
    val directory = Files.isDirectory(p)
    try {
        FileChannel.open(p, if (directory) StandardOpenOption.READ else StandardOpenOption.WRITE).use { it.force(true) }
    } catch (e: IOException) {
        if (!directory) throw e
    }
}

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun fileStamp(path: Path): Long? = try {
    val a = Files.readAttributes(java.nio.file.Path.of(path.toString()), BasicFileAttributes::class.java)
    a.lastModifiedTime().to(TimeUnit.NANOSECONDS) * 31 + a.size()
} catch (_: IOException) {
    null
}
