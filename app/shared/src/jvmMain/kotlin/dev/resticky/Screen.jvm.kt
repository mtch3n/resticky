package dev.resticky

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun encodeGrayPng(pixels: ByteArray, width: Int, height: Int): ByteArray {
    val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
    img.raster.setDataElements(0, 0, width, height, pixels)
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}
