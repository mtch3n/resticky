package dev.resticky

const val PANEL_W = 800
const val PANEL_H = 480
const val SCREEN_SIZE = PANEL_W / 8 * PANEL_H

expect fun encodeGrayPng(pixels: ByteArray, width: Int, height: Int): ByteArray

fun screenPng(frame: ByteArray): ByteArray {
    val gray = ByteArray(PANEL_H * PANEL_W)
    for (x in 0 until PANEL_H) for (y in 0 until PANEL_W) {
        val bit = frame[(PANEL_H - 1 - x) * (PANEL_W / 8) + y / 8].toInt() and (0x80 shr (y % 8))
        if (bit != 0) gray[y * PANEL_H + x] = 0xff.toByte()
    }
    return encodeGrayPng(gray, PANEL_H, PANEL_W)
}
