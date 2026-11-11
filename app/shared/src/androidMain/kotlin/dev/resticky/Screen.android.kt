package dev.resticky

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

actual fun encodeGrayPng(pixels: ByteArray, width: Int, height: Int): ByteArray {
    val argb = IntArray(pixels.size) { i -> (pixels[i].toInt() and 0xff).let { v -> (0xff shl 24) or (v shl 16) or (v shl 8) or v } }
    val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
