package dev.resticky

import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path
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
