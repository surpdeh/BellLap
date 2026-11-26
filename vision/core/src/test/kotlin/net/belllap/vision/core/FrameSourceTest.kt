package net.belllap.vision.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.io.path.createTempDirectory

class FrameSourceTest {
    private fun frame(seed: Int) = Frame(0, 6, 4, 6, ByteArray(24) { (it * 9 + seed).toByte() })

    @Test
    fun pngRoundTripKeepsLuma() {
        val dir = createTempDirectory().toFile()
        val f = frame(3)
        LumaImages.writePng(f, dir.resolve("f.png"))
        assertArrayEquals(f.luma, LumaImages.readPng(dir.resolve("f.png"), 5).luma)
    }

    @Test
    fun imageSequenceOrdersByNameAndStampsPtsFromFps() {
        val dir = createTempDirectory().toFile()
        (0 until 3).forEach { LumaImages.writePng(frame(it * 20), dir.resolve("f%04d.png".format(it))) }
        val frames = runBlocking { ImageSequenceFrameSource.fromDirectory(dir, 30.0).frames().toList() }
        assertEquals(listOf(0L, 33_333_333L, 66_666_667L), frames.map { it.ptsNs })
        assertEquals(frame(20).lumaAt(2, 1), frames[1].lumaAt(2, 1))
    }

    @Test
    fun videoSourceDelegatesToTheDecoder() {
        val decoder = object : VideoDecoder {
            override fun decode(file: java.io.File) = sequenceOf(frame(0), frame(1))
        }
        val frames = runBlocking { VideoFileFrameSource(java.io.File("x.mp4"), decoder).frames().toList() }
        assertEquals(2, frames.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun undersizedBufferIsRejected() {
        Frame(0, 4, 4, 4, ByteArray(3))
    }
}
