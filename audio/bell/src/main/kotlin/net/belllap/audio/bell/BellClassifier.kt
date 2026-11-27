package net.belllap.audio.bell

/**
 * Stage 1: scores one window of 16 kHz mono audio (samples in [-1, 1]) for "this is a bell", 0..1.
 * The Android implementation runs YAMNet through LiteRT; JVM tests and the replay harness use fakes or
 * precomputed scores, because LiteRT has no desktop JVM runtime.
 */
interface BellClassifier {
    /** Samples per window. YAMNet takes 15 600 (0.975 s at 16 kHz). */
    val windowSamples: Int

    /** Human-readable name for logs and metrics. */
    val name: String

    fun bellScore(window: FloatArray): Float
}

/** Stage 1 switched off: every stage-0 candidate counts. Used when the model is missing and in stage-0 metrics. */
object AcceptAllClassifier : BellClassifier {
    override val windowSamples: Int = YamnetClassMap.WINDOW_SAMPLES
    override val name: String = "stage0-only"

    override fun bellScore(window: FloatArray): Float = 1f
}

/**
 * YAMNet's 521 AudioSet class names and the bell family BellLap scores. Indices are looked up by name from the
 * label list shipped with the model, never hard-coded, so a model update cannot silently shift them.
 */
class YamnetClassMap(val names: List<String>) {
    val bellFamily: IntArray = names.indices.filter { names[it] in BELL_FAMILY }.toIntArray()

    init {
        require(bellFamily.isNotEmpty()) { "no bell-family class in the label list" }
    }

    /** The highest bell-family score in one frame of YAMNet output. */
    fun bellScore(scores: FloatArray): Float {
        require(scores.size == names.size) { "expected ${names.size} scores, got ${scores.size}" }
        return bellFamily.maxOf { scores[it] }
    }

    companion object {
        const val WINDOW_SAMPLES = 15_600
        const val SAMPLE_RATE = 16_000

        /**
         * AudioSet classes that are a struck bell. A hand bell swung hard is heard by YAMNet as a bell rung fast,
         * so the mechanical ringers count too ("Alarm clock", "Telephone bell ringing", "Doorbell"); on the
         * synthetic ring fixtures they are its top classes. Generic "Alarm", "Beep, bleep" and "Buzzer" do not
         * count: they are electronic.
         */
        val BELL_FAMILY = setOf(
            "Bell",
            "Church bell",
            "Jingle bell",
            "Bicycle bell",
            "Cowbell",
            "Tubular bells",
            "Chime",
            "Wind chime",
            "Tuning fork",
            "Change ringing (campanology)",
            "Ding",
            "Ding-dong",
            "Doorbell",
            "Telephone bell ringing",
            "Alarm clock",
        )

        /** Parses either the plain label list (one name per line) or `yamnet_class_map.csv` (index,mid,name). */
        fun parse(text: String): YamnetClassMap {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val csv = lines.firstOrNull()?.startsWith("index,") == true
            val names = if (csv) lines.drop(1).map(::csvName) else lines
            return YamnetClassMap(names)
        }

        private fun csvName(line: String): String {
            val rest = line.substringAfter(',').substringAfter(',')
            return rest.removeSurrounding("\"")
        }
    }
}
