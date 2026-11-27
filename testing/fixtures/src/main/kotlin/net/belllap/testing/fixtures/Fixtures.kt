package net.belllap.testing.fixtures

import net.belllap.vision.core.Frame
import kotlin.random.Random

/** A generated fixture: raw media plus its ground truth. Disk layout is handled by [FixtureWriter]. */
class Fixture(val labels: Labels, val audio: BellAudio?, val frames: List<Frame>?)

/** The standard synthetic fixture set. Every call with the same seed returns identical data. */
object Fixtures {
    const val DEFAULT_SEED = 20_260_101L
    private const val NS = 1e9
    private const val MIN_GAP_FRAMES = 6
    private const val MAX_GAP_FRAMES = 12

    val SNRS_DB = listOf(20, 10, 5, 0)

    fun bell(snrDb: Int, distractors: Boolean = false, seed: Long = DEFAULT_SEED): Fixture {
        val name = "bell_snr${snrDb}${if (distractors) "_distractors" else ""}"
        val strikes = BELL_RINGS_S.flatMap { BellAudioGenerator.ring(it, Random(seed + it.toLong())) }
        val audio = BellAudioGenerator(
            seed + snrDb,
            onsetsS = strikes,
            snrDb = snrDb.toDouble(),
            distractors = distractors,
        ).generate()
        val labels = Labels(
            name = name,
            seed = seed,
            audioFile = "$name.wav",
            sampleRate = audio.sampleRate,
            bellOnsetsNs = audio.onsetsNs,
            distractors = audio.distractors,
        )
        return Fixture(labels.validate(), audio, null)
    }

    /** Waving flag at 4..9 s and 15..19 s, with the static chequered banner as a distractor throughout. */
    fun flag(seed: Long = DEFAULT_SEED): Fixture {
        val intervals = listOf(4.0 to 9.0, 15.0 to 19.0)
        val spec = SceneSpec(seed, frameCount = (FLAG_SECONDS * SceneSpec.FPS).toInt(), flagIntervalsS = intervals)
        val labels = Labels(
            name = "flag",
            seed = seed,
            framesDir = "flag_frames",
            fps = spec.fps,
            flagIntervals = intervals.map { IntervalNs((it.first * NS).toLong(), (it.second * NS).toLong()) },
        )
        return Fixture(labels.validate(), null, SceneRenderer.render(spec))
    }

    /** The chequered banner and nothing else: a distractor the flag detector must never report. */
    fun banner(seed: Long = DEFAULT_SEED): Fixture {
        val spec = SceneSpec(seed, frameCount = (FLAG_SECONDS * SceneSpec.FPS).toInt())
        val labels = Labels(
            name = "banner",
            seed = seed,
            framesDir = "banner_frames",
            fps = spec.fps,
            distractors = listOf(DistractorLabel(Labels.BANNER_KIND, 0L)),
        )
        return Fixture(labels.validate(), null, SceneRenderer.render(spec))
    }

    /** Riders crossing singly (groups of 1) and in bunches of 3 and 5, gaps of 6 to 12 frames inside a bunch. */
    fun crossings(seed: Long = DEFAULT_SEED): Fixture {
        val (riders, frameCount) = riderPlan(Random(seed), startFrame = 30, groupSizes = listOf(1, 1, 3, 5, 1))
        val spec = SceneSpec(seed, frameCount, banner = false, riders = riders)
        val labels = Labels(
            name = "crossings",
            seed = seed,
            framesDir = "crossings_frames",
            fps = spec.fps,
            crossings = crossingLabels(riders, spec.fps),
        )
        return Fixture(labels.validate(), null, SceneRenderer.render(spec))
    }

    /**
     * A whole race: bell lap rung from 12 s, flag 24 to 36 s, riders crossing from 26 s (a single, then bunches of
     * 4 and 2). [withFrames] false skips rendering the video, for callers that already have it encoded and need the
     * labels.
     */
    fun race(seed: Long = DEFAULT_SEED, withFrames: Boolean = true): Fixture {
        val duration = RACE_SECONDS
        val audio = BellAudioGenerator(
            seed + 1,
            durationS = duration,
            onsetsS = BellAudioGenerator.ring(RACE_BELL_S, Random(seed + 2)),
            snrDb = 10.0,
            distractors = true,
        ).generate()
        val (riders, _) = riderPlan(
            Random(seed),
            startFrame = (26 * SceneSpec.FPS).toInt(),
            groupSizes = listOf(1, 4, 2),
        )
        val flag = listOf(24.0 to 36.0)
        val spec = SceneSpec(seed, (duration * SceneSpec.FPS).toInt(), flagIntervalsS = flag, riders = riders)
        val labels = Labels(
            name = "race_synth", seed = seed, audioFile = "race_synth.wav", sampleRate = audio.sampleRate,
            framesDir = "race_synth_frames", fps = spec.fps, bellOnsetsNs = audio.onsetsNs,
            flagIntervals = flag.map { IntervalNs((it.first * NS).toLong(), (it.second * NS).toLong()) },
            crossings = crossingLabels(riders, spec.fps), distractors = audio.distractors,
        )
        return Fixture(labels.validate(), audio, if (withFrames) SceneRenderer.render(spec) else null)
    }

    fun all(seed: Long = DEFAULT_SEED): List<Fixture> =
        SNRS_DB.map {
            bell(it, seed = seed)
        } + bell(10, distractors = true, seed = seed) + flag(seed) + banner(seed) + crossings(seed) + race(seed)

    private fun crossingLabels(riders: List<RiderSpec>, fps: Double) = riders.map {
        CrossingLabel(it.crossFrame, Math.round(it.crossFrame * NS / fps), it.group)
    }

    private fun riderPlan(rng: Random, startFrame: Int, groupSizes: List<Int>): Pair<List<RiderSpec>, Int> {
        var frame = startFrame
        val riders = ArrayList<RiderSpec>()
        groupSizes.forEachIndexed { g, size ->
            repeat(size) { k ->
                if (k > 0) frame += rng.nextInt(MIN_GAP_FRAMES, MAX_GAP_FRAMES + 1)
                riders += RiderSpec(frame, g + 1)
            }
            frame += FRAMES_BETWEEN_GROUPS
        }
        return riders to frame + TAIL_FRAMES
    }

    /** Each bell fixture has two bell laps, each a ring of strikes (see [BellAudioGenerator.ring]). */
    val BELL_RINGS_S = listOf(8.0, 17.0)
    private const val RACE_BELL_S = 12.0
    private const val FLAG_SECONDS = 24.0
    private const val RACE_SECONDS = 45.0
    private const val FRAMES_BETWEEN_GROUPS = 60
    private const val TAIL_FRAMES = 60
}
