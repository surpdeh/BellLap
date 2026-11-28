package net.belllap.core.model

/** Why a locked-screen self-test failed (F33). [hint] is shown to the user. */
enum class SelfTestFailure(val hint: String) {
    SCREEN_NOT_LOCKED("The screen was still on when the simulated bell fired. Lock the phone within the countdown."),
    DID_NOT_ARM("The simulated bell did not arm the session."),
    CAMERA_DID_NOT_OPEN("The camera produced no frames while the phone was locked."),
    RECORDING_REFUSED("The recorder would not start while the phone was locked."),
    RECORDING_TOO_SHORT("The recording stopped before 5 s of video."),
}

/** The outcome of a self-test run. */
sealed interface SelfTestVerdict {
    data object Passed : SelfTestVerdict

    data class Failed(val reason: SelfTestFailure) : SelfTestVerdict
}

/** What a run observed, in the order the steps happen. A step that never ran stays at its default. */
data class SelfTestMeasurements(
    /** The screen was not interactive when the simulated bell fired. */
    val screenLockedAtBell: Boolean = false,
    /** The simulated bell took the session to ARMED. */
    val armed: Boolean = false,
    /** A camera frame arrived after the bell. */
    val frameSeen: Boolean = false,
    /** ACQUISITION began with no recorder notice. */
    val recordingStarted: Boolean = false,
    /** Camera-clock (PTS) time covered by the recording. */
    val recordedMs: Long = 0,
)

/**
 * The per-device self-test for locked-screen standby (F33): lock the phone, let it idle, fire a simulated bell, then
 * the camera must open and record [RECORD_MS]. Passing switches the "allow screen lock" option on for this device.
 */
object ScreenLockSelfTest {
    const val RECORD_MS = 5_000L

    /** Frame-clock jitter allowed on the recorded span. */
    const val TOLERANCE_MS = 500L

    /** The first failing step, in the order a run takes them. */
    fun judge(m: SelfTestMeasurements): SelfTestVerdict {
        val failure = when {
            !m.screenLockedAtBell -> SelfTestFailure.SCREEN_NOT_LOCKED
            !m.armed -> SelfTestFailure.DID_NOT_ARM
            !m.frameSeen -> SelfTestFailure.CAMERA_DID_NOT_OPEN
            !m.recordingStarted -> SelfTestFailure.RECORDING_REFUSED
            m.recordedMs < RECORD_MS - TOLERANCE_MS -> SelfTestFailure.RECORDING_TOO_SHORT
            else -> null
        }
        return failure?.let(SelfTestVerdict::Failed) ?: SelfTestVerdict.Passed
    }

    /** A passed test holds for one phone and one OS build only, so an update or a different phone must retest. */
    fun deviceId(manufacturer: String, model: String, sdk: Int, fingerprint: String) =
        "$manufacturer|$model|$sdk|$fingerprint"
}
