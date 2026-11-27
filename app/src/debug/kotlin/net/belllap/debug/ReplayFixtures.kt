package net.belllap.debug

import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.bell.BellDetectorConfig
import net.belllap.audio.source.WavIo
import net.belllap.core.replay.BellDetector
import net.belllap.core.replay.Detectors
import net.belllap.core.replay.LabelDrivenDetectors
import net.belllap.testing.fixtures.Fixtures
import java.io.File
import net.belllap.audio.bell.BellDetector as BellCascade

/** A video and audio pair to replay, plus the detectors to run on it. */
class ReplayInput(val name: String, val video: File, val audio: File, val detectors: Detectors)

/** Builds replayable inputs on the device, so no media is committed to the repo. */
object ReplayFixtures {
    const val RACE_SYNTH = "race_synth"

    /**
     * Generates the `race_synth` fixture, writes its WAV, and encodes its frames to MP4 (cached). When the MP4 is
     * already in [dir] (cached, or placed there by the emulator tests from host-encoded assets), only the labels
     * are generated. The bell is found by the real F14 cascade with [bellClassifier]; flag and crossings are still
     * label-driven until F16 and F18.
     */
    fun raceSynth(dir: File, bellClassifier: BellClassifier): ReplayInput {
        val wav = File(dir, "$RACE_SYNTH.wav")
        val mp4 = File(dir, "$RACE_SYNTH.mp4")
        val encoded = mp4.length() > 0L
        val fixture = Fixtures.race(withFrames = !encoded)
        val labels = fixture.labels
        val audio = checkNotNull(fixture.audio)
        if (!wav.exists()) WavIo.write(wav, audio.sampleRate, audio.samples)
        if (!encoded) Mp4Encoder.encode(checkNotNull(fixture.frames), checkNotNull(labels.fps).toInt(), mp4)
        val stubs = LabelDrivenDetectors.of(
            bellOnsetsNs = emptyList(),
            flagIntervals = labels.flagIntervals.map { it.startNs until it.endNs },
            crossingsNs = labels.crossings.map { it.ptsNs },
        )
        val cascade = BellCascade(audio.sampleRate, bellClassifier, BellDetectorConfig())
        val bell = BellDetector { chunk -> cascade.accept(chunk).isNotEmpty() }
        return ReplayInput(RACE_SYNTH, mp4, wav, Detectors(bell, stubs.flag, stubs.crossing))
    }
}
