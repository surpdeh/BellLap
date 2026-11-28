package net.belllap.capture

import android.graphics.Color
import android.media.MediaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** F30 splice continuity on a real encoder: pre-roll plus live samples form one gap-free, rising PTS track. */
@RunWith(AndroidJUnit4::class)
class PreRollEncoderTest {
    @Test
    fun preRollAndLiveVideoSpliceWithContinuousPts() {
        val fps = 30
        val enc = PreRollEncoder(640, 360, fps, bitRate = 2_000_000, preRollSeconds = 2)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "preroll.mp4")
        try {
            fun draw(frame: Int) {
                val c = enc.inputSurface.lockCanvas(null)
                c.drawColor(Color.rgb(frame * 7 % 256, 90, 160))
                enc.inputSurface.unlockCanvasAndPost(c)
                Thread.sleep(1000L / fps)
            }
            repeat(fps * 5) { draw(it) } // 5 s armed, only the last 2 s (+ to the key frame) are kept
            assertNotNull(enc.startRecording(file))
            repeat(fps * 2) { draw(fps * 5 + it) } // 2 s live
            enc.stopRecording()
        } finally {
            enc.release()
        }
        val ex = MediaExtractor().apply {
            setDataSource(file.path)
            selectTrack(0)
        }
        val pts = generateSequence { ex.sampleTime.takeIf { it >= 0 }?.also { ex.advance() } }.toList()
        ex.release()
        assertTrue("file has ${pts.size} samples", pts.size > fps * 3)
        assertEquals("starts at 0", 0L, pts.first())
        val gaps = pts.zipWithNext { a, b -> b - a }
        assertTrue("PTS strictly rising", gaps.all { it > 0 })
        // Continuity: no gap across the splice larger than a few frame times.
        assertTrue("max gap ${gaps.max()} us", gaps.max() < 4_000_000L / fps)
    }
}
