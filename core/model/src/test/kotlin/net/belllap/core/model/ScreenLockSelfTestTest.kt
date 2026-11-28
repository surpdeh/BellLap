package net.belllap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLockSelfTestTest {
    private val ok = SelfTestMeasurements(
        screenLockedAtBell = true,
        armed = true,
        frameSeen = true,
        recordingStarted = true,
        recordedMs = ScreenLockSelfTest.RECORD_MS,
    )

    private fun failure(m: SelfTestMeasurements) = (ScreenLockSelfTest.judge(m) as SelfTestVerdict.Failed).reason

    @Test
    fun fullRunPasses() = assertEquals(SelfTestVerdict.Passed, ScreenLockSelfTest.judge(ok))

    @Test
    fun recordedSpanWithinToleranceStillPasses() {
        val edge = ok.copy(recordedMs = ScreenLockSelfTest.RECORD_MS - ScreenLockSelfTest.TOLERANCE_MS)
        assertEquals(SelfTestVerdict.Passed, ScreenLockSelfTest.judge(edge))
    }

    @Test
    fun eachMissingStepFailsWithItsOwnReason() {
        assertEquals(SelfTestFailure.SCREEN_NOT_LOCKED, failure(ok.copy(screenLockedAtBell = false)))
        assertEquals(SelfTestFailure.DID_NOT_ARM, failure(ok.copy(armed = false)))
        assertEquals(SelfTestFailure.CAMERA_DID_NOT_OPEN, failure(ok.copy(frameSeen = false)))
        assertEquals(SelfTestFailure.RECORDING_REFUSED, failure(ok.copy(recordingStarted = false)))
        assertEquals(SelfTestFailure.RECORDING_TOO_SHORT, failure(ok.copy(recordedMs = 4_000)))
    }

    @Test
    fun anUnlockedRunNeverPassesWhateverElseHappened() {
        assertEquals(SelfTestFailure.SCREEN_NOT_LOCKED, failure(ok.copy(screenLockedAtBell = false)))
        assertEquals(SelfTestFailure.SCREEN_NOT_LOCKED, failure(SelfTestMeasurements()))
    }

    @Test
    fun optionIsOfferedOnlyOnTheTestedDevice() {
        val a = ScreenLockSelfTest.deviceId("Google", "Pixel 8", 36, "fp1")
        val b = ScreenLockSelfTest.deviceId("Google", "Pixel 8", 36, "fp2")
        val tested = CaptureSettings(allowScreenLock = true, screenLockTestedDevice = a)
        assertTrue(tested.screenLockAvailable(a))
        assertTrue(tested.screenLockActive(a))
        assertFalse("an OS update must retest", tested.screenLockAvailable(b))
        assertFalse(tested.screenLockActive(b))
        assertFalse(CaptureSettings(allowScreenLock = true).screenLockActive(a))
        assertFalse(CaptureSettings(screenLockTestedDevice = a).screenLockActive(a))
    }
}
