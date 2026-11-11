package dev.resticky

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
