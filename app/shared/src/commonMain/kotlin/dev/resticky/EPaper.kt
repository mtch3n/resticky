package dev.resticky

const val PHOTO_W = 480
const val PHOTO_H = 800
const val PHOTO_FRAME_SIZE = PHOTO_W * PHOTO_H / 8
private const val LOW_PCT = 0.01
private const val HIGH_PCT = 0.99
private const val CONTRAST = 1.15f

fun autoLevels(gray: FloatArray): FloatArray {
    val sorted = gray.sortedArray()
    val lo = sorted[(sorted.size * LOW_PCT).toInt()]
    val hi = sorted[((sorted.size - 1) * HIGH_PCT).toInt()]
    val span = (hi - lo).coerceAtLeast(1e-3f)
    return FloatArray(gray.size) { i -> (((gray[i] - lo) / span - 0.5f) * CONTRAST + 0.5f).coerceIn(0f, 1f) }
}

fun atkinson(gray: FloatArray, w: Int, h: Int): BooleanArray {
    val g = gray.copyOf()
    val black = BooleanArray(w * h)
    val spread = listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2)
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        val ink = g[i] < 0.5f
        black[i] = ink
        val err = (g[i] - if (ink) 0f else 1f) / 8
        for ((dx, dy) in spread) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until w && ny < h) g[ny * w + nx] += err
        }
    }
    return black
}

fun packBits(black: BooleanArray): ByteArray {
    val out = ByteArray(black.size / 8)
    for (i in black.indices) if (black[i]) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
    return out
}

fun panelPhoto(gray: FloatArray): ByteArray {
    require(gray.size == PHOTO_W * PHOTO_H) { "need a ${PHOTO_W}x$PHOTO_H image" }
    return packBits(atkinson(autoLevels(gray), PHOTO_W, PHOTO_H))
}
