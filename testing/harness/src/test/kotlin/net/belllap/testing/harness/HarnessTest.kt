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
    fun bellEpisodesGroupStrikesAndMatchOneDetectionEach() {
        val s = 1_000 * ms
        val strikes = listOf(8 * s, 8 * s + 300 * ms, 8 * s + 700 * ms, 17 * s, 17 * s + 400 * ms)
        assertEquals(listOf(8 * s, 17 * s), BellMetrics.episodes(strikes))
        // One detection per bell lap, a duplicate, and a stray one far from any bell.
        val r = BellMetrics.score(strikes, listOf(9 * s, 9 * s + 500 * ms, 17 * s + 800 * ms, 40 * s))
        assertEquals(2, r.episodes)
        assertEquals(2, r.matched)
        assertEquals(2, r.falseAlarms)
        assertEquals(listOf(1 * s, 800 * ms), r.latenciesNs)
        // A detection before the bell starts does not count for it.
        assertEquals(0, BellMetrics.score(strikes.take(1), listOf(7 * s)).matched)
    }

    @Test
    fun bellCascadeFindsEveryBellLapInTheRaceAndNothingInDistractors() {
        val f = Fixtures.race()
        val audio = f.audio!!
        val run = BellRun(Stage1.Off)
        runBlocking { PcmAudioSource(audio.samples, audio.sampleRate, 20).chunks().toList() }.forEach(run::accept)
        val r = BellMetrics.score(f.labels.bellOnsetsNs, run.detectionsNs)
        assertEquals(1, r.matched)
        assertEquals(0, r.falseAlarms)
        assertTrue(r.latenciesNs.single() <= 1_500 * ms)
    }

    @Test
    fun windowKeysAreStableAndPrecomputedScoresUseTheBellFamily() {
        val w = FloatArray(15_600) { (it % 7) / 10f }
        assertEquals(Stage1.windowKey(w), Stage1.windowKey(w.copyOf()))
        assertTrue(Stage1.windowKey(w) != Stage1.windowKey(w.copyOf().also { it[0] = 1f }))
        val file = kotlin.io.path.createTempFile(suffix = ".json").toFile()
        file.writeText(
            """{"labels": ["Speech", "Bell", "Chime"], "scores": {"${Stage1.windowKey(w)}": [0.9, 0.2, 0.4]}}""",
        )
        val classifier = Stage1.Precomputed(file).classifier()
        assertEquals(0.4f, classifier.bellScore(w), 0f)
        file.delete()
    }

    @Test
    fun flagRunIgnoresTheStaticBannerAndSeesTheWavingFlag() {
        val frames = Fixtures.flag().frames!!
        assertTrue(FlagRun.over(frames.take(100)).intervals.isEmpty())
        assertEquals(1, FlagRun.over(frames.take(300)).intervals.size)
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
