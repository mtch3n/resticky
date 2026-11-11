package dev.resticky.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.resticky.PHOTO_FRAME_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StickyPhotoTest {
    @Test
    fun landscapePhotoIsCroppedToAPortraitFrame() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "frame-test.jpg")
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(700f, 0f, 900f, 900f, android.graphics.Paint().apply { color = Color.BLACK })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val frame = stickyFrame { file.inputStream() }!!
        file.delete()
        assertEquals(PHOTO_FRAME_SIZE, frame.size)
        val row = (0 until 480).map { x -> frame[400 * 60 + x / 8].toInt() and (0x80 shr (x % 8)) != 0 }
        assertTrue(row[240])
        assertTrue(!row[10] && !row[470])
    }
}
