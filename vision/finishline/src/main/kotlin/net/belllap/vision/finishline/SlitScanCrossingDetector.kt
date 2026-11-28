package net.belllap.vision.finishline

import net.belllap.core.model.FinishLine
import net.belllap.vision.core.Frame

/**
 * Live finish-crossing detector (F18). Causal: [accept] sees each frame once, in order, and reports the riders whose
 * wheel reaches the line in that frame. Time is the frame PTS only. Pure Kotlin, so Replay and the phone run the
 * same code. The strip of the newest frame is in [lastStrip], for evidence images.
 */
class SlitScanCrossingDetector(private val line: FinishLine, private val config: CrossingConfig = CrossingConfig()) {
    private var sampler: StripSampler? = null
    private var tracker: OnsetTracker? = null
    private val background = RunningMedianBackground(config.medianWindow)
    private var index = 0

    /** The strip sampled from the newest frame, or null before the first. */
    var lastStrip: IntArray? = null
        private set

    fun accept(frame: Frame): List<DetectedCrossing> {
        val s = sampler ?: StripSampler(
            line,
            frame.width,
            frame.height,
            StripSampler.defaultSamples(line, frame.width, frame.height),
        ).also {
            sampler = it
            tracker = OnsetTracker(config, it.samples)
        }
        val strip = s.sample(frame)
        lastStrip = strip
        val i = index++
        return checkNotNull(tracker).process(i, frame.ptsNs, strip, background.backgroundFor(i, strip))
    }
}
