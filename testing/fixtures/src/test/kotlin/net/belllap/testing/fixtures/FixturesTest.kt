package net.belllap.testing.fixtures

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.belllap.audio.source.WavFileAudioSource
import net.belllap.vision.core.ImageSequenceFrameSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs

class FixturesTest {
    @Test
    fun sameSeedGivesIdenticalAudioAndDifferentSeedDiffers() {
        val a = Fixtures.bell(10, seed = 1).audio!!.samples
        assertArrayEquals(a, Fixtures.bell(10, seed = 1).audio!!.samples)
        assertFalse(a.contentEquals(Fixtures.bell(10, seed = 2).audio!!.samples))
    }

    @Test
    fun sameSeedGivesIdenticalFrames() {
        val a = Fixtures.crossings(seed = 5).frames!!
        val b = Fixtures.crossings(seed = 5).frames!!
        assertEquals(a.size, b.size)
        a.indices.step(17).forEach { assertArrayEquals(a[it].luma, b[it].luma) }
    }

    @Test
    fun achievedSnrMatchesTheRequestedSnr() {
        // Mean power in the 0.5 s after the onset (bell + noise) vs a quiet stretch (noise only).
        for (snr in listOf(20, 0)) {
            val bell = Fixtures.bell(snr)
            val s = bell.audio!!.samples.map { it.toDouble() }.toDoubleArray()
            val rate = bell.audio.sampleRate
            val onset = (bell.labels.bellOnsetsNs.first() / 1e9 * rate).toInt()
            val ringing = Noise.power(s, onset, onset + rate / 2)
            val noise = Noise.power(s, 0, rate * 2)
            val measured = 10 * Math.log10((ringing - noise) / noise)
            assertEquals("snr $snr", snr.toDouble(), measured, 3.0)
        }
    }

    @Test
    fun distractorsAreLabelledAndAwayFromBellOnsets() {
        val f = Fixtures.bell(10, distractors = true)
        assertTrue(f.labels.distractors.size >= 3)
        assertEquals(setOf("whistle", "clap", "horn"), f.labels.distractors.map { it.kind }.toSet())
        for (d in f.labels.distractors) for (b in f.labels.bellOnsetsNs) assertTrue(abs(d.startNs - b) > 2_000_000_000L)
    }

    @Test
    fun crossingLabelPointsAtTheFirstFrameTheLineIsCovered() {
        val f = Fixtures.crossings()
        val frames = f.frames!!
        assertEquals(11, f.labels.crossings.size)
        assertEquals(listOf(1, 2, 3, 4, 5), f.labels.crossings.map { it.group }.distinct())
        for ((i, c) in f.labels.crossings.withIndex()) {
            val y = SceneRenderer.LANE_TOP + SceneRenderer.lane(i) * SceneRenderer.LANE_PITCH + 2

            fun covered(frame: Int) = frames[frame].lumaAt(SceneRenderer.LINE_X, y) < 60
            assertTrue("rider $i covers line at ${c.frame}", covered(c.frame))
            assertFalse("rider $i does not cover line at ${c.frame - 1}", covered(c.frame - 1))
        }
    }

    @Test
    fun flagFramesChangeFasterInsideTheLabelledInterval() {
        val f = Fixtures.flag()
        val frames = f.frames!!

        fun flagMotion(i: Int) = (SceneRenderer.FLAG_Y until SceneRenderer.FLAG_Y + SceneRenderer.FLAG_H).sumOf { y ->
            (SceneRenderer.FLAG_X until SceneRenderer.FLAG_X + SceneRenderer.FLAG_W).count { x ->
                abs(frames[i].lumaAt(x, y) - frames[i - 1].lumaAt(x, y)) > 60
            }
        }
        val inside = (150..250).map(::flagMotion).average()
        val outside = (20..100).map(::flagMotion).average()
        assertTrue("inside $inside outside $outside", inside > 20 * (outside + 1))
    }

    @Test
    fun bannerIsPresentEvenWhenNoFlagIsWaving() {
        val frame = Fixtures.flag().frames!![10]
        val values = (0 until 60).map { frame.lumaAt(it, SceneRenderer.ROAD_TOP - 6) }
        assertTrue(values.any { it < 60 } && values.any { it > 200 })
    }

    @Test
    fun labelsRoundTripAndValidationRejectsBadFiles() {
        val labels = Fixtures.race().labels
        assertEquals(labels, Labels.parse(labels.encode()))
        assertThrows { Labels.parse(labels.copy(bellOnsetsNs = listOf(5, 1)).encode()) }
        assertThrows { Labels.parse(labels.copy(flagIntervals = listOf(IntervalNs(9, 3))).encode()) }
        assertThrows { Labels.parse(labels.copy(schemaVersion = 99).encode()) }
    }

    @Test
    fun finishLineIsOptionalAndMustBeANormalisedSegment() {
        val labels = Fixtures.race().labels
        assertEquals(null, labels.finishLine)
        val line = LineLabel(0.1f, 0.6f, 0.9f, 0.62f)
        assertEquals(line, Labels.parse(labels.copy(finishLine = line).encode()).finishLine)
        assertThrows { Labels.parse(labels.copy(finishLine = line.copy(x2 = 1.2f)).encode()) }
        assertThrows { Labels.parse(labels.copy(finishLine = LineLabel(0.5f, 0.5f, 0.5f, 0.5f)).encode()) }
    }

    @Test
    fun flagBoxesMustBeSortedAndInsideTheFrame() {
        val labels = Fixtures.race().labels
        val boxes = listOf(BoxLabel(3, 0.1f, 0.3f, 0.07f, 0.09f), BoxLabel(9, 0.2f, 0.3f, 0.07f, 0.09f))
        assertEquals(boxes, Labels.parse(labels.copy(flagBoxes = boxes).encode()).flagBoxes)
        assertThrows { Labels.parse(labels.copy(flagBoxes = boxes.reversed()).encode()) }
        assertThrows { Labels.parse(labels.copy(flagBoxes = listOf(BoxLabel(1, 0.95f, 0.1f, 0.1f, 0.1f))).encode()) }
    }

    @Test
    fun writtenFixturesLoadBackThroughTheFileBackedSources() {
        val root = createTempDirectory().toFile()
        val race = Fixtures.race()
        FixtureWriter.write(root, listOf(Fixtures.crossings(), race))
        assertEquals(listOf("crossings", "race_synth"), FixtureWriter.loadManifest(root))
        val dir = File(root, "race_synth")
        val labels = Labels.load(File(dir, Labels.FILE_NAME))
        assertEquals(race.labels, labels)
        val audio = runBlocking { WavFileAudioSource(File(dir, labels.audioFile!!)).chunks().toList() }
        assertEquals(race.audio!!.samples.size, audio.sumOf { it.samples.size })
        val frames =
            runBlocking {
                ImageSequenceFrameSource.fromDirectory(
                    File(dir, labels.framesDir!!),
                    labels.fps!!,
                ).frames().toList()
            }
        assertEquals(race.frames!!.size, frames.size)
        assertArrayEquals(race.frames[700].luma, frames[700].luma)
        assertNotEquals(0L, frames[1].ptsNs)
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: kotlinx.serialization.SerializationException) {
            true
        }
        assertTrue("expected rejection", threw)
    }

    @Test
    fun bellLapsAreRingsOfStrikesAndAmbienceIsDeterministic() {
        val strikes = Fixtures.bell(10).labels.bellOnsetsNs
        assertEquals(Fixtures.BELL_RINGS_S.size * BellAudioGenerator.RING_STRIKES, strikes.size)
        strikes.zipWithNext().forEach { (a, b) ->
            assertTrue(b - a in 300_000_000L..450_000_000L || b - a > 3_000_000_000L)
        }
        val a = AmbienceStream(5)
        val b = AmbienceStream(5)
        repeat(16 * 60) { assertArrayEquals(a.next(1000).samples, b.next(1000).samples) } // one minute
        assertEquals(a.distractorCounts, b.distractorCounts)
        assertTrue(a.distractorCounts.values.sum() >= 60 / 8)
    }
}
