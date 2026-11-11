package dev.resticky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EPaperTest {
    @Test
    fun bitsArePackedRowMajorMsbFirstWithOneForBlack() {
        val black = BooleanArray(16)
        black[0] = true
        black[7] = true
        black[9] = true
        assertEquals(listOf(0x81, 0x40), packBits(black).map { it.toInt() and 0xff })
    }

    @Test
    fun aFrameIsExactly48000Bytes() {
        val frame = panelPhoto(FloatArray(PHOTO_W * PHOTO_H) { (it % PHOTO_W).toFloat() / PHOTO_W })
        assertEquals(48_000, frame.size)
        assertEquals(PHOTO_FRAME_SIZE, frame.size)
    }

    @Test
    fun ditherKeepsTheGrayLevel() {
        val w = 64
        val h = 64
        listOf(0.25f, 0.5f, 0.75f).forEach { level ->
            val ink = atkinson(FloatArray(w * h) { level }, w, h).count { it }.toFloat() / (w * h)
            assertTrue(kotlin.math.abs(ink - (1 - level)) < 0.08f, "level $level ink $ink")
        }
        assertEquals(0, atkinson(FloatArray(16) { 1f }, 4, 4).count { it })
        assertEquals(16, atkinson(FloatArray(16) { 0f }, 4, 4).count { it })
    }

    @Test
    fun levelsStretchAFlatImage() {
        val out = autoLevels(FloatArray(1000) { 0.4f + 0.2f * it / 999 })
        assertTrue(out.first() < 0.05f && out.last() > 0.95f)
    }

    @Test
    fun photoFrameIsAFrameOfType17() {
        val frames = FrameReader().feed(frame(FRAME_PHOTO, ByteArray(PHOTO_FRAME_SIZE)))
        assertEquals(17, frames.single().type)
        assertEquals(48_000, frames.single().payload.size)
    }
}
