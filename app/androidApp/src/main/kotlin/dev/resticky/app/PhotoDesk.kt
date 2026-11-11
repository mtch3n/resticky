package dev.resticky.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.resticky.PHOTO_MAX_PX
import dev.resticky.Photo
import dev.resticky.Session
import dev.resticky.Store
import dev.resticky.linkPhoto
import dev.resticky.photoOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

data class Unlinked(val file: File, val takenMs: Long, val text: String)

class PhotoDesk(private val context: Context, private val store: Store) {
    private val snaps = File(context.cacheDir, "snaps")
    private val unlinkedDir = File(context.filesDir, "unlinked")
    val unlinked = MutableStateFlow(readUnlinked())

    fun newSnap(): Pair<File, Uri> {
        snaps.mkdirs()
        val file = File(snaps, "${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(context, "dev.resticky.app.snaps", file)
    }

    private fun readUnlinked(): List<Unlinked> = unlinkedDir.listFiles { f -> f.extension == "jpg" }.orEmpty()
        .map { Unlinked(it, it.nameWithoutExtension.toLongOrNull() ?: 0, File(it.path.removeSuffix(".jpg") + ".txt").takeIf { t -> t.exists() }?.readText().orEmpty()) }
        .sortedByDescending { it.takenMs }

    private fun shrink(src: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_PX) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, PHOTO_MAX_PX.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text.trim()) }
            .addOnFailureListener { cont.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }

    suspend fun save(snap: File): String = withContext(Dispatchers.IO) {
        val taken = snap.nameWithoutExtension.toLong()
        val bitmap = shrink(snap) ?: return@withContext "The photo could not be read"
        val text = runCatching { read(bitmap) }.getOrElse { "" }
        val session = linkPhoto(store.all(), Instant.fromEpochMilliseconds(taken))
        val dest = if (session == null) File(unlinkedDir, "$taken.jpg") else File(store.dir(session.device, session.id).toString(), "photos/$taken.jpg")
        dest.parentFile!!.mkdirs()
        dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        snap.delete()
        if (session == null) {
            File(dest.path.removeSuffix(".jpg") + ".txt").writeText(text)
            unlinked.value = readUnlinked()
            log("INFO photo unlinked taken=$taken chars=${text.length}")
            return@withContext "Saved to Unlinked photos"
        }
        val offset = photoOffset(session, Instant.fromEpochMilliseconds(taken))
        store.update(session.device, session.id) { it.photos += Photo(offset, "photos/$taken.jpg", text, taken); it.photos.sortBy { p -> p.offsetMs } }
        log("INFO photo session=${session.id} offset_ms=$offset chars=${text.length}")
        "Added to ${session.title.ifEmpty { "the live session" }}"
    }

    suspend fun attach(photo: Unlinked, s: Session) = withContext(Dispatchers.IO) {
        val dest = File(store.dir(s.device, s.id).toString(), "photos/${photo.takenMs}.jpg")
        dest.parentFile!!.mkdirs()
        photo.file.copyTo(dest, overwrite = true)
        photo.file.delete()
        File(photo.file.path.removeSuffix(".jpg") + ".txt").delete()
        store.update(s.device, s.id) {
            it.photos += Photo(photoOffset(s, Instant.fromEpochMilliseconds(photo.takenMs)), "photos/${photo.takenMs}.jpg", photo.text, photo.takenMs)
            it.photos.sortBy { p -> p.offsetMs }
        }
        unlinked.value = readUnlinked()
    }

    fun file(s: Session, p: Photo) = File(store.dir(s.device, s.id).toString(), p.file)
}
