package net.belllap.vision.finishline

import net.belllap.core.model.CrossingSource
import net.belllap.core.model.FinishLine
import net.belllap.core.model.SidecarCrossing
import net.belllap.vision.core.Frame

/**
 * The post-process pass over a saved recording (F18). It keeps every strip, then runs the same [OnsetTracker] with a
 * centred median background, so it has no warm-up and no ghost after a rider leaves. Use it after the acquisition
 * ends; the live pass stays the source of the beep.
 */
object CrossingRefiner {
    fun refine(
        frames: Iterable<Frame>,
        line: FinishLine,
        config: CrossingConfig = CrossingConfig.REFINE,
    ): List<DetectedCrossing> {
        var sampler: StripSampler? = null
        val strips = ArrayList<IntArray>()
        val pts = ArrayList<Long>()
        for (frame in frames) {
            val s = sampler ?: StripSampler(
                line,
                frame.width,
                frame.height,
                StripSampler.defaultSamples(line, frame.width, frame.height),
            ).also { sampler = it }
            strips += s.sample(frame)
            pts += frame.ptsNs
        }
        val samples = sampler?.samples ?: return emptyList()
        val background = CenteredMedianBackground(strips, config.medianWindow / 2)
        val tracker = OnsetTracker(config, samples)
        return strips.indices.flatMap {
            tracker.process(it, pts[it], strips[it], background.backgroundFor(it, strips[it]))
        }
    }
}

/**
 * A sidecar entry for [this]. The sidecar clock is the camera clock in microseconds. [framePtsOffsetNs] is added to
 * the detector's PTS first: 0 for live frames, and `firstFramePtsUs * 1000 - firstDecodedPtsNs` for frames decoded
 * from the saved MP4, whose own PTS starts near zero.
 */
fun DetectedCrossing.toSidecar(
    firstFramePtsUs: Long,
    fps: Int,
    source: CrossingSource,
    framePtsOffsetNs: Long = 0L,
): SidecarCrossing {
    val ptsUs = (ptsNs + framePtsOffsetNs) / NS_PER_US
    val frame = Math.round((ptsUs - firstFramePtsUs) * fps / US_PER_S).toInt().coerceAtLeast(0)
    return SidecarCrossing(ptsUs, frame, confidence.coerceIn(0f, 1f), source)
}

private const val NS_PER_US = 1000L
private const val US_PER_S = 1e6
