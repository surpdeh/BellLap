package net.belllap.vision.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Conversions between [Frame] and image files, used by file-backed sources and by evidence tooling. */
object LumaImages {
    private const val R_WEIGHT = 0.299
    private const val G_WEIGHT = 0.587
    private const val B_WEIGHT = 0.114
    private const val BYTE_MASK = 0xFF
    private const val G_SHIFT = 8
    private const val R_SHIFT = 16

    fun toFrame(image: BufferedImage, ptsNs: Long): Frame {
        val w = image.width
        val h = image.height
        val luma = ByteArray(w * h)
        if (image.type == BufferedImage.TYPE_BYTE_GRAY) {
            // getRGB would gamma-convert gray images; read the samples as stored so a round trip is lossless.
            image.raster.getDataElements(0, 0, w, h, luma)
            return Frame(ptsNs, w, h, w, luma)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr R_SHIFT) and BYTE_MASK
                val g = (rgb shr G_SHIFT) and BYTE_MASK
                val b = rgb and BYTE_MASK
                luma[y * w + x] = (R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b).toInt().toByte()
            }
        }
        return Frame(ptsNs, w, h, w, luma)
    }

    fun toImage(frame: Frame): BufferedImage {
        val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_BYTE_GRAY)
        val packed =
            ByteArray(frame.width * frame.height) { frame.luma[(it / frame.width) * frame.stride + it % frame.width] }
        image.raster.setDataElements(0, 0, frame.width, frame.height, packed)
        return image
    }

    fun writePng(frame: Frame, file: File) {
        file.parentFile?.mkdirs()
        check(ImageIO.write(toImage(frame), "png", file)) { "no PNG writer available" }
    }

    fun readPng(file: File, ptsNs: Long): Frame = toFrame(
        requireNotNull(ImageIO.read(file)) {
            "cannot decode $file"
        },
        ptsNs,
    )
}
