package dev.resticky.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.resticky.PHOTO_H
import dev.resticky.PHOTO_W
import dev.resticky.panelPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.roundToInt

fun stickyFrame(open: () -> InputStream?): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_H) sample *= 2
    val raw = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val rotation = open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    val degrees = when (rotation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val upright = if (degrees == 0f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true)
    val scale = maxOf(PHOTO_W.toFloat() / upright.width, PHOTO_H.toFloat() / upright.height)
    val scaled = Bitmap.createScaledBitmap(upright, (upright.width * scale).roundToInt().coerceAtLeast(PHOTO_W), (upright.height * scale).roundToInt().coerceAtLeast(PHOTO_H), true)
    val crop = Bitmap.createBitmap(scaled, (scaled.width - PHOTO_W) / 2, (scaled.height - PHOTO_H) / 2, PHOTO_W, PHOTO_H)
    val pixels = IntArray(PHOTO_W * PHOTO_H)
    crop.getPixels(pixels, 0, PHOTO_W, 0, 0, PHOTO_W, PHOTO_H)
    val gray = FloatArray(pixels.size) { i ->
        val c = pixels[i]
        (0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)) / 255f
    }
    return panelPhoto(gray)
}

suspend fun HubApp.showOnSticky(open: () -> InputStream?): String = withContext(Dispatchers.Default) {
    val frame = runCatching { stickyFrame(open) }.getOrNull() ?: return@withContext "The photo could not be read"
    if (ble.sendPhoto(frame)) "Sent to the Sticky" else "The Sticky is not connected over Bluetooth"
}

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HubApp
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) app.scope.launch {
            val message = app.showOnSticky { contentResolver.openInputStream(uri) }
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        }
        finish()
    }
}
