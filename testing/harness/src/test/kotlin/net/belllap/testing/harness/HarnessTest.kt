package net.belllap.testing.harness

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.belllap.audio.source.PcmAudioSource
import net.belllap.testing.fixtures.Fixtures
import net.belllap.vision.core.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    private val ms = 1_000_000L

    @Test
    fun matchingIsOneToOneAndToleranceBounded() {
        val m = Metrics.match(
            listOf(1000 * ms, 2000 * ms, 3000 * ms),
            listOf(1050 * ms, 2500 * ms, 2990 * ms, 3005 * ms),
            100 * ms,
        )
        assertEquals(2, m.tp)
        assertEquals(2, m.fp)
        assertEquals(1, m.fn)
        assertEquals(2.0 / 3, Metrics.recall(m), 1e-9)
        assertEquals(0.5, Metrics.precision(m), 1e-9)
    }

    @Test
    fun emptyLabelsAndDetectionsScorePerfectly() {
        val m = Metrics.match(emptyList(), emptyList(), 1)
        assertEquals(1.0, Metrics.recall(m), 0.0)
        assertEquals(1.0, Metrics.intervalIou(emptyList(), emptyList()), 0.0)
        assertEquals(4.0, Metrics.falseAlarmsPerHour(2, 1800.0), 1e-9)
    }

    @Test
    fun intervalIouIsPartialOnPartialOverlap() {
        assertEquals(1.0 / 3, Metrics.intervalIou(listOf(0L..20L), listOf(10L..30L)), 1e-9)
    }

    @Test
    fun thresholdsReportBelowAboveAndMissing() {
        val t = Thresholds(min = mapOf("a" to 0.9, "gone" to 1.0), max = mapOf("b" to 1.0))
        val v = t.violations(mapOf("a" to 0.5, "b" to 2.0))
        assertEquals(3, v.size)
        assertTrue(v.any { "no such metric" in it })
    }

    @Test
    fun bellDetectorIgnoresNoiseOnlyAudioAndDistractors() {
        val f = Fixtures.bell(10, distractors = true)
        val audio = f.audio!!
        // Cut the audio to the stretch before the first bell onset: only noise and distractors remain.
        val cut = (f.labels.bellOnsetsNs.first() / 1e9 * audio.sampleRate).toInt() - 16_000
        val detector = BaselineBellDetector(audio.sampleRate)
        runBlocking {
            PcmAudioSource(audio.samples.copyOf(cut), audio.sampleRate, 20).chunks().toList()
        }.forEach(detector::accept)
        assertTrue(f.labels.distractors.any { it.startNs / 1e9 * audio.sampleRate < cut })
        assertEquals(emptyList<Long>(), detector.onsetsNs)
    }

    @Test
    fun flagDetectorIgnoresTheStaticBannerAndSeesTheWavingFlag() {
        val frames = Fixtures.flag().frames!!
        val quiet = BaselineFlagDetector().also { d -> frames.take(100).forEach(d::accept) }
        assertTrue(quiet.finish().isEmpty())
        val waving = BaselineFlagDetector().also { d -> frames.take(300).forEach(d::accept) }
        assertEquals(1, waving.finish().size)
    }

    @Test
    fun crossingDetectorStaysQuietOnAnEmptyRoad() {
        val flag: List<Frame> = Fixtures.flag().frames!!
        val d = BaselineCrossingDetector().also { det -> flag.forEach(det::accept) }
        assertEquals(0, d.crossingsNs.size)
    }

    @Test
    fun realFixturesAreSkippedCleanlyWhenAbsent() {
        val missing = java.io.File("build/no-such-real-fixtures")
        assertEquals(emptyList<String>(), RealFixtures.discover(missing))
        val out = java.nio.file.Files.createTempDirectory("real").toFile()
        replayRealMain(arrayOf(out.path, missing.path))
        assertTrue(java.io.File(out, "real-metrics.json").readText().contains("\"skipped\""))
    }

    @Test
    fun realFixturesAreDiscoveredAndReplayedWhenPresent() {
        val root = java.nio.file.Files.createTempDirectory("real").toFile()
        net.belllap.testing.fixtures.FixtureWriter.write(root, listOf(Fixtures.bell(20)))
        java.io.File(root, "not_a_fixture").mkdirs()
        assertEquals(listOf("bell_snr20"), RealFixtures.discover(root))
        val out = java.nio.file.Files.createTempDirectory("realout").toFile()
        replayRealMain(arrayOf(out.path, root.path))
        val text = java.io.File(out, "real-metrics.json").readText()
        assertTrue(text.contains("\"ran\"") && text.contains("bell_snr20.bell.recall"))
    }
}
