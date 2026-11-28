package net.belllap.core.statemachine

/** The three audible cues of F15. Pure data: the Android player maps each to a synthesized tone. */
enum class Cue { ARMED_BEEPS, CROSSING_BEEP, STAND_DOWN_CHIME }

/** Plays a [Cue]. Hardware sits behind this interface; tests use [FakeBeepSink]. Must not block the caller. */
interface BeepSink {
    fun play(cue: Cue)
}

/** Records the cues it is asked to play. */
class FakeBeepSink : BeepSink {
    private val recorded = java.util.Collections.synchronizedList(ArrayList<Cue>())
    val played: List<Cue> get() = synchronized(recorded) { recorded.toList() }

    override fun play(cue: Cue) {
        recorded += cue
    }
}

/** The cue an [Effect] asks for, or null when the effect is not audible. */
fun Effect.cueOrNull(): Cue? = when (this) {
    Effect.PlayArmedBeeps -> Cue.ARMED_BEEPS
    Effect.PlayCrossingBeep -> Cue.CROSSING_BEEP
    Effect.PlayStandDownChime -> Cue.STAND_DOWN_CHIME
    else -> null
}

/** One audio stream's volume in device steps (0..[max]). The Android adapter wraps AudioManager. */
interface StreamVolume {
    val max: Int
    var current: Int
}

/**
 * Raises a stream to a target level while at least one cue plays, then puts the level back (F15).
 *
 * Reference counted: the level saved by the first holder is the one restored by the last releaser, so an armed series
 * overlapping a crossing beep neither saves the raised level nor restores early. Each [acquire] sets the target
 * again. The saved level is restored unconditionally, so a volume change the user makes during a cue is overwritten.
 * An unbalanced [release] does nothing.
 */
class VolumeGuard(private val volume: StreamVolume) {
    private var holders = 0
    private var saved = 0

    /** Sets the stream to [targetPercent] (0..100, rounded to a device step; 100 is the maximum). */
    @Synchronized
    fun acquire(targetPercent: Int = FULL_PERCENT) {
        if (holders == 0) saved = volume.current
        holders++
        val pct = targetPercent.coerceIn(0, FULL_PERCENT)
        volume.current = (volume.max * pct + HALF) / FULL_PERCENT
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) volume.current = saved
    }

    /** Runs [block] with the volume raised; the volume is restored even if [block] throws. */
    inline fun <T> withVolume(targetPercent: Int = FULL_PERCENT, block: () -> T): T {
        acquire(targetPercent)
        try {
            return block()
        } finally {
            release()
        }
    }

    companion object {
        const val FULL_PERCENT = 100
        private const val HALF = 50
    }
}
