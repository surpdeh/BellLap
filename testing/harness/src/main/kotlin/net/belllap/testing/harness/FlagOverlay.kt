package net.belllap.testing.harness

import net.belllap.vision.core.Frame
import net.belllap.vision.core.LumaImages
import net.belllap.vision.flag.FlagBox
import net.belllap.vision.flag.FlagResult
import java.awt.Color
import java.awt.image.BufferedImage

/** Debug overlay for the flag detector: green box = reported flag, orange box = chequered but static, so rejected. */
object FlagOverlay {
    private const val SCALE = 4
    private const val FONT_PX = 16f
    private val HIT = Color(0, 220, 0)
    private val REJECTED = Color(255, 140, 0)

    fun render(frame: Frame, result: FlagResult): BufferedImage {
        val src = LumaImages.toImage(frame)
        val img = BufferedImage(frame.width * SCALE, frame.height * SCALE, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.drawImage(src, 0, 0, img.width, img.height, null)
        val hit = result.box
        val rejected = result.textureBox.takeIf { hit == null }
        if (rejected != null) box(g, rejected, REJECTED, "rejected: static")
        if (hit != null) box(g, hit, HIT, if (result.visible) "FLAG" else "candidate")
        g.dispose()
        return img
    }

    private fun box(g: java.awt.Graphics2D, b: FlagBox, color: Color, label: String) {
        g.color = color
        g.font = g.font.deriveFont(java.awt.Font.BOLD, FONT_PX)
        g.stroke = java.awt.BasicStroke(2f)
        g.drawRect(b.x * SCALE, b.y * SCALE, b.width * SCALE, b.height * SCALE)
        g.drawString(label, b.x * SCALE + 2, maxOf(12, b.y * SCALE - 4))
    }
}
