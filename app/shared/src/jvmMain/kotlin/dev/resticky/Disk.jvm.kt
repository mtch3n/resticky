package dev.resticky

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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
