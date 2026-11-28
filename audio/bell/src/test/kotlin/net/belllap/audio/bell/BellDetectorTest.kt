package net.belllap.audio.bell

import net.belllap.audio.source.PcmChunk
import net.belllap.core.model.BellSensitivity
import net.belllap.testing.fixtures.AmbienceStream
import net.belllap.testing.fixtures.BellAudioGenerator
import net.belllap.testing.fixtures.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BellDetectorTest {
    private val rate = 16_000
    private val ms = 1_000_000L

    private fun chunks(samples: ShortArray, chunk: Int = 320): List<PcmChunk> =
        (samples.indices step chunk).map {
            PcmChunk(it * 1_000_000_000L / rate, rate, samples.copyOfRange(it, minOf(samples.size, it + chunk)))
        }

    private fun synth(seconds: Double, f: (Double) -> Double): ShortArray {
        val noise = Random(7)
        return ShortArray((seconds * rate).toInt()) {
            val t = it.toDouble() / rate
            ((f(t) + 0.003 * (noise.nextDouble() - 0.5)) * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun strike(t: Double, at: Double, partials: List<Double> = listOf(1800.0, 3600.0, 4968.0)): Double {
        val dt = t - at
        if (dt < 0) return 0.0
        return 0.25 * exp(-dt / 0.35) * partials.sumOf { sin(2 * PI * it * dt) }
    }

    private fun gate(samples: ShortArray, config: BellDetectorConfig = BellDetectorConfig()) =
        SpectralGate(rate, config).let { g -> chunks(samples).flatMap(g::accept) }

    private class Recorder(private val scores: (FloatArray) -> Float = { 1f }) : BellClassifier {
        val windows = ArrayList<FloatArray>()
        override val windowSamples = YamnetClassMap.WINDOW_SAMPLES
        override val name = "test"

        override fun bellScore(window: FloatArray): Float {
            windows += window
            return scores(window)
        }
    }

    private fun detect(
        samples: ShortArray,
        classifier: BellClassifier,
        config: BellDetectorConfig = BellDetectorConfig(),
    ) = BellDetector(rate, classifier, config).let { d -> chunks(samples).flatMap(d::accept) }

    @Test
    fun powerSpectrumPeaksAtTheToneBin() {
        val fft = PowerSpectrum(512)
        val out = DoubleArray(fft.bins)
        fft.compute(DoubleArray(512) { sin(2 * PI * 40 * it / 512) }, out)
        assertEquals(40, out.indices.maxBy { out[it] })
        // A unit sine through a Hann window: |X| = N/4 at the tone bin.
        assertEquals(128.0 * 128.0, out[40], 1.0)
        assertThrows(IllegalArgumentException::class.java) { PowerSpectrum(500) }
    }

    @Test
    fun quickselectFindsTheMedian() {
        val values = doubleArrayOf(5.0, 1.0, 9.0, 3.0, 7.0, 2.0, 8.0)
        assertEquals(5.0, SpectralGate.select(values.copyOf(), values.size, values.size / 2), 0.0)
        assertEquals(1.0, SpectralGate.select(values.copyOf(), values.size, 0), 0.0)
    }

    @Test
    fun aStruckBellPassesTheGateNearItsOnset() {
        val c = gate(synth(3.0) { strike(it, 1.0) })
        val onset = c.single { it.kind == BellCandidate.Kind.ONSET }
        assertTrue("onset ${onset.onsetNs / ms} ms", abs(onset.onsetNs - 1000 * ms) <= 40 * ms)
        assertTrue(onset.partials >= 2)
        assertTrue("decay ${onset.decayDbPerS}", onset.decayDbPerS in 15.0..35.0)
        assertTrue(listOf(1800.0, 3600.0, 4968.0).any { abs(it - onset.peakHz) < 50 })
        assertTrue("decided after the ring check", onset.decidedNs - onset.onsetNs >= 150 * ms)
    }

    @Test
    fun sustainedTonesClapsLowHornsAndSilenceDoNotPass() {
        val whistle = synth(3.0) { t ->
            if (t in 1.0..2.0) 0.3 * sin(2 * PI * 2950 * t + 20 * sin(2 * PI * 5 * t)) else 0.0
        }
        val squeal = synth(3.0) { t ->
            if (t in 1.0..2.5) 0.3 * minOf(1.0, (t - 1) / 0.15) * sin(2 * PI * 4200 * t) else 0.0
        }
        val rng = Random(3)
        val clap = synth(3.0) { t -> if (t in 1.0..1.1) 0.5 * (rng.nextDouble() - 0.5) * exp(-(t - 1) / 0.02) else 0.0 }
        val horn = synth(3.0) { t -> if (t in 1.0..2.0) 0.4 * sin(2 * PI * 420 * t) else 0.0 }
        for ((name, s) in listOf("whistle" to whistle, "squeal" to squeal, "clap" to clap, "horn" to horn)) {
            assertEquals(name, emptyList<BellCandidate>(), gate(s))
        }
        assertEquals(emptyList<BellCandidate>(), gate(ShortArray(rate * 2)))
    }

    @Test
    fun aSinglePartialPassesOnlyAtHighSensitivity() {
        val pure = synth(3.0) { strike(it, 1.0, listOf(2400.0)) }
        assertEquals(0, gate(pure).size)
        val high = gate(pure, BellDetectorConfig.forSensitivity(BellSensitivity.HIGH))
        assertEquals(1, high.count { it.kind == BellCandidate.Kind.ONSET })
    }

    @Test
    fun theClassifierSeesAWindowContainingTheStrike() {
        val rec = Recorder { 0f }
        detect(synth(4.0) { strike(it, 1.0) }, rec)
        val w = rec.windows.first()
        assertEquals(YamnetClassMap.WINDOW_SAMPLES, w.size)
        // Window ends 0.7 s after the onset: the strike starts 0.7 s before the end, the head is silence.
        val strikeAt = w.size - (BellDetectorConfig().postOnsetNs * rate / 1_000_000_000L).toInt()
        val before = (0 until strikeAt - 400).maxOf { abs(w[it]) }
        val after = (strikeAt + 200 until strikeAt + 2000).maxOf { abs(w[it]) }
        assertTrue("before $before after $after", before < 0.01f && after > 0.3f)
    }

    @Test
    fun twoClassifierHitsFireOnceAndTheRefractoryHoldsTheRest() {
        val ring = BellAudioGenerator.ring(1.0, Random(1))
        val s = synth(8.0) { t -> ring.sumOf { strike(t, it) } }
        val traces = ArrayList<BellTrace>()
        // Strikes 2..6 of the ring; strike train is out of reach so only the classifier can fire.
        val config = BellDetectorConfig(strikeTrain = 50)
        val detector = BellDetector(rate, AcceptAllClassifier, config) { traces += it }
        val out = chunks(s).flatMap(detector::accept)
        assertEquals(1, out.size)
        val detected = traces.filterIsInstance<BellTrace.Detected>().single()
        assertEquals(BellTrace.Reason.CLASSIFIER, detected.reason)
        assertEquals(2, detected.hits)
        assertTrue("latency ${(out[0] - 1000 * ms) / ms} ms", out[0] - 1000 * ms <= 1500 * ms)
        assertTrue(traces.none { it is BellTrace.Scored && it.ptsNs > out[0] })
    }

    @Test
    fun aRejectingClassifierStillFiresOnALongStrikeTrain() {
        val ring = BellAudioGenerator.ring(1.0, Random(1))
        val s = synth(8.0) { t -> ring.sumOf { strike(t, it) } }
        val traces = ArrayList<BellTrace>()
        val out = BellDetector(rate, Recorder { 0f }) { traces += it }.let { d -> chunks(s).flatMap(d::accept) }
        assertEquals(1, out.size)
        assertEquals(BellTrace.Reason.STRIKE_TRAIN, traces.filterIsInstance<BellTrace.Detected>().single().reason)
        // One isolated strike with a rejecting classifier never fires.
        assertEquals(emptyList<Long>(), detect(synth(4.0) { strike(it, 1.0) }, Recorder { 0f }))
    }

    @Test
    fun sensitivityTradesHitsForScore() {
        val low = BellDetectorConfig.forSensitivity(BellSensitivity.LOW)
        val medium = BellDetectorConfig.forSensitivity(BellSensitivity.MEDIUM)
        val high = BellDetectorConfig.forSensitivity(BellSensitivity.HIGH)
        assertTrue(low.hitsRequired > medium.hitsRequired && medium.hitsRequired > high.hitsRequired)
        assertTrue(low.scoreThreshold > medium.scoreThreshold && medium.scoreThreshold > high.scoreThreshold)
        assertTrue(low.strikeTrain > medium.strikeTrain && medium.strikeTrain > high.strikeTrain)
        // One strike ringing out: a lukewarm score (0.2) fires at MEDIUM and HIGH but not at LOW (needs 0.3).
        val one = synth(3.0) { strike(it, 0.5) }
        val lukewarm = Recorder { 0.2f }
        assertEquals(1, detect(one, lukewarm, high).size)
        assertEquals(1, detect(one, lukewarm, medium).size)
        assertEquals(0, detect(one, lukewarm, low).size)
    }

    @Test
    fun syntheticFixturesFireOncePerBellLapAndHoursOfAmbienceNever() {
        for (f in listOf(Fixtures.bell(10, distractors = true), Fixtures.bell(0))) {
            val audio = f.audio!!
            val out = detect(audio.samples, AcceptAllClassifier)
            assertEquals(f.labels.name, Fixtures.BELL_RINGS_S.size, out.size)
            Fixtures.BELL_RINGS_S.zip(out).forEach { (ring, at) ->
                val latency = at - (ring * 1000).toLong() * ms
                assertTrue("${f.labels.name} latency ${latency / ms}", latency in 0..1500 * ms)
            }
        }
        val stream = AmbienceStream(11)
        val d = BellDetector(rate, AcceptAllClassifier)
        var fired = 0
        repeat(20 * 60 * 50) { fired += d.accept(stream.next(320)).size } // 20 minutes
        assertEquals(0, fired)
    }

    @Test
    fun rejectsTheWrongSampleRate() {
        assertThrows(IllegalArgumentException::class.java) { BellDetector(48_000, AcceptAllClassifier) }
    }

    @Test
    fun classMapReadsBothFormatsAndScoresTheBellFamily() {
        val csv = "index,mid,display_name\n0,/m/09x0r,Speech\n1,/m/0395lw,Bell\n2,/m/0f8s22,Chime\n" +
            "3,/m/x,\"Dog, bark\"\n"
        val fromCsv = YamnetClassMap.parse(csv)
        assertEquals(listOf("Speech", "Bell", "Chime", "Dog, bark"), fromCsv.names)
        assertEquals(listOf(1, 2), fromCsv.bellFamily.toList())
        assertEquals(0.6f, fromCsv.bellScore(floatArrayOf(0.9f, 0.6f, 0.2f, 0.95f)), 0f)
        assertEquals(fromCsv.names, YamnetClassMap.parse("Speech\nBell\nChime\nDog, bark\n").names)
        assertThrows(IllegalArgumentException::class.java) { fromCsv.bellScore(FloatArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { YamnetClassMap(listOf("Speech", "Music")) }
    }
}
