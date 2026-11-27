package net.belllap.audio.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.belllap.audio.bell.BellDetector
import net.belllap.audio.bell.BellTrace
import net.belllap.audio.bell.YamnetClassMap
import net.belllap.audio.source.PcmChunk
import net.belllap.testing.fixtures.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tier 2: the real YAMNet model through LiteRT on the emulator, on the synthetic bell fixture. */
@RunWith(AndroidJUnit4::class)
class YamnetOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelLoadsAndScoresSilenceLowAndAllClassesAsProbabilities() {
        YamnetBellClassifier.load(context).use { yamnet ->
            assertEquals(521, yamnet.classMap.names.size)
            val scores = yamnet.scores(FloatArray(YamnetClassMap.WINDOW_SAMPLES))
            assertEquals(521, scores.size)
            assertTrue(scores.all { it in 0f..1f })
            val silence = yamnet.bellScore(FloatArray(YamnetClassMap.WINDOW_SAMPLES))
            Log.i(TAG, "silence bell score $silence, top=${yamnet.classMap.names[scores.indices.maxBy { scores[it] }]}")
            assertTrue("silence scored $silence as a bell", silence < 0.1f)
        }
    }

    @Test
    fun fullCascadeWithYamnetFindsBothBellLapsInTheFixture() {
        val audio = Fixtures.bell(10).audio!!
        YamnetBellClassifier.load(context).use { yamnet ->
            val traces = ArrayList<BellTrace>()
            val detector = BellDetector(audio.sampleRate, yamnet) { traces += it }
            val out = (audio.samples.indices step CHUNK).flatMap {
                val end = minOf(audio.samples.size, it + CHUNK)
                val pts = it * 1_000_000_000L / audio.sampleRate
                detector.accept(PcmChunk(pts, audio.sampleRate, audio.samples.copyOfRange(it, end)))
            }
            traces.forEach { Log.i(TAG, it.toString()) }
            Log.i(TAG, "detections at ${out.map { it / 1_000_000 }} ms")
            assertEquals(Fixtures.BELL_RINGS_S.size, out.size)
            assertTrue(traces.filterIsInstance<BellTrace.Detected>().any { it.reason == BellTrace.Reason.CLASSIFIER })
        }
    }

    private companion object {
        const val TAG = "BellLapYamnet"
        const val CHUNK = 320
    }
}
