package dev.resticky.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.resticky.Session
import dev.resticky.Store
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoDeskTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun photoIsShrunkReadAndLinkedToTheLiveSession() = runBlocking {
        val root = File(context.cacheDir, "photo-test").apply { deleteRecursively() }
        val store = Store(Path(root.path))
        store.addChunk("ab", "c1", 0, true, 30_000, ByteArray(4))
        val desk = PhotoDesk(context, store)
        val (file, _) = desk.newSnap()
        val bitmap = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("RESTORE TEST ENV", 200f, 800f, Paint().apply { textSize = 220f; color = Color.BLACK })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        desk.save(file)
        val s: Session = store.all().single()
        val photo = s.photos.single()
        assertTrue(photo.offsetMs in 25_000L..30_000L)
        assertTrue(photo.text, "RESTORE" in photo.text.uppercase())
        val saved = android.graphics.BitmapFactory.decodeFile(desk.file(s, photo).path)
        assertEquals(2048, saved.width)
        assertTrue(!file.exists())
        root.deleteRecursively()
        Unit
    }
}
