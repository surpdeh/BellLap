package net.belllap.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.SelfTestFailure
import net.belllap.core.model.SelfTestVerdict
import net.belllap.session.SelfTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLockSettingsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun optionIsDisabledUntilTheSelfTestPassed() {
        composeRule.setContent {
            SettingsOverlay(CaptureSettings(), capabilities = null, onChange = {}, screenLockAvailable = false)
        }
        composeRule.onNodeWithTag(TestTags.TOGGLE_SCREEN_LOCK).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun optionCanBeSwitchedOnAfterAPass() {
        var latest: CaptureSettings? = null
        composeRule.setContent {
            SettingsOverlay(
                CaptureSettings(screenLockTestedDevice = "d"),
                capabilities = null,
                onChange = { latest = it },
                screenLockAvailable = true,
            )
        }
        composeRule.onNodeWithTag(TestTags.TOGGLE_SCREEN_LOCK).performScrollTo().assertIsEnabled().performClick()
        assertEquals(true, latest?.allowScreenLock)
    }

    @Test
    fun selfTestButtonStartsARun() {
        var runs = 0
        composeRule.setContent {
            SettingsOverlay(CaptureSettings(), capabilities = null, onChange = {}, onRunSelfTest = { runs++ })
        }
        composeRule.onNodeWithTag(TestTags.SELF_TEST_BUTTON).performScrollTo().performClick()
        assertEquals(1, runs)
    }

    @Test
    fun selfTestButtonIsDisabledWhileARunIsGoing() {
        composeRule.setContent {
            SettingsOverlay(CaptureSettings(), capabilities = null, onChange = {}, selfTest = SelfTestStatus.Running)
        }
        composeRule.onNodeWithTag(TestTags.SELF_TEST_BUTTON).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun messagesTellTheUserWhatToDo() {
        assertTrue("Lock the phone now" in selfTestMessage(SelfTestStatus.Countdown(12), false))
        assertTrue("12 s" in selfTestMessage(SelfTestStatus.Countdown(12), false))
        val failed = SelfTestStatus.Done(SelfTestVerdict.Failed(SelfTestFailure.CAMERA_DID_NOT_OPEN))
        assertTrue(SelfTestFailure.CAMERA_DID_NOT_OPEN.hint in selfTestMessage(failed, false))
        assertTrue("passed" in selfTestMessage(SelfTestStatus.Idle, true))
    }
}
