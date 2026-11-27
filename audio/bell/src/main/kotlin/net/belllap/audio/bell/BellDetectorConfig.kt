package net.belllap.audio.bell

import net.belllap.core.model.BellSensitivity

/**
 * Tuning for the bell cascade. All times are nanoseconds of audio PTS.
 *
 * Stage 0 (the spectral gate) passes a tonal peak in [bandHz] that rises by [onsetRiseDb], stands [prominenceDb]
 * above the local median spectrum, keeps ringing for [minRingNs] and decays at between [minDecayDbPerS] and
 * [maxDecayDbPerS] (faster than that is a thud or a syllable, not a ringing bell).
 * Stage 1 accepts a candidate when the classifier's bell-family score is at least [scoreThreshold].
 * Persistence fires BellDetected once [hitsRequired] accepted candidates, or [strikeTrain] stage-0 strikes,
 * fall within [persistenceNs].
 */
data class BellDetectorConfig(
    val bandHz: ClosedFloatingPointRange<Double> = BAND_LOW_HZ..BAND_HIGH_HZ,
    val prominenceDb: Double = 15.0,
    val onsetRiseDb: Double = 6.0,
    val minPartials: Int = 2,
    val minRingNs: Long = 150_000_000L,
    val minDecayDbPerS: Double = 4.0,
    val maxDecayDbPerS: Double = 80.0,
    val continuationNs: Long = 480_000_000L,
    val scoreThreshold: Float = 0.1f,
    val hitsRequired: Int = 2,
    val strikeTrain: Int = 4,
    val persistenceNs: Long = 3_000_000_000L,
    val refractoryNs: Long = 6_000_000_000L,
    val postOnsetNs: Long = 700_000_000L,
) {
    init {
        require(hitsRequired >= 1 && minPartials >= 1 && strikeTrain >= 2) {
            "hitsRequired, minPartials or strikeTrain too small"
        }
        require(postOnsetNs in 0..YAMNET_WINDOW_NS) { "postOnsetNs must fit inside one classifier window" }
    }

    companion object {
        const val YAMNET_WINDOW_NS = 975_000_000L
        const val BAND_LOW_HZ = 1500.0
        const val BAND_HIGH_HZ = 8000.0

        @Suppress("MagicNumber")
        fun forSensitivity(s: BellSensitivity): BellDetectorConfig = when (s) {
            BellSensitivity.LOW -> BellDetectorConfig(
                prominenceDb = 18.0,
                scoreThreshold = 0.3f,
                hitsRequired = 3,
                strikeTrain = 6,
            )
            BellSensitivity.MEDIUM -> BellDetectorConfig()
            BellSensitivity.HIGH -> BellDetectorConfig(
                prominenceDb = 12.0,
                onsetRiseDb = 5.0,
                minPartials = 1,
                minDecayDbPerS = 2.0,
                scoreThreshold = 0.08f,
                hitsRequired = 1,
                strikeTrain = 3,
            )
        }
    }
}
