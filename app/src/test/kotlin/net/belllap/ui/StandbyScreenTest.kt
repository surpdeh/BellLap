package net.belllap.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import net.belllap.core.model.BellSensitivity
import net.belllap.core.statemachine.State
import net.belllap.session.BellStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandbyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val listening = BellStatus(listening = true, classifier = "yamnet", sensitivity = BellSensitivity.MEDIUM)

    @Test
    fun standbyShowsTheBigBellNowButtonAndListeningStatus() {
        var bellNow = 0
        var stop = 0
        composeRule.setContent { StandbyScreen(State.Standby, listening, { bellNow++ }, { stop++ }) }
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextContains("STANDBY")
        composeRule.onNodeWithTag(TestTags.BELL_STATUS)
            .assertTextContains("Listening for the bell (Medium sensitivity)")
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(TestTags.STOP_BUTTON).performClick()
        assertEquals(1, bellNow)
        assertEquals(1, stop)
    }

    @Test
    fun bellNowIsOnlyInStandby() {
        composeRule.setContent { StandbyScreen(State.Arming, listening, {}, {}) }
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.STATUS_MODE).assertTextContains("ARMING")
    }

    @Test
    fun recordNowIsOnlyInArmedAndShowsTheNotice() {
        var record = 0
        var state by mutableStateOf<State>(State.Armed)
        composeRule.setContent {
            StandbyScreen(state, listening, {}, {}, onRecordNow = { record++ }, notice = "Not enough free storage.")
        }
        composeRule.onNodeWithTag(TestTags.RECORD_NOW_BUTTON).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(TestTags.RECORD_NOTICE).assertTextContains("Not enough free storage.")
        assertEquals(1, record)
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).assertDoesNotExist()
        state = State.Standby
        composeRule.onNodeWithTag(TestTags.RECORD_NOW_BUTTON).assertDoesNotExist()
        state = State.Acquisition(flagVisible = false)
        composeRule.onNodeWithTag(TestTags.RECORD_NOW_BUTTON).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.STOP_BUTTON).assertIsDisplayed()
    }

    @Test
    fun statusExplainsWhenDetectionIsOffBrokenOrModelless() {
        assertEquals("Bell detection is off. Use Bell now.", statusLine(State.Standby, BellStatus()))
        assertEquals(
            "Bell detection stopped: mic busy. Use Bell now.",
            statusLine(State.Standby, BellStatus(error = "mic busy")),
        )
        assertEquals(
            "Listening for the bell (High sensitivity, no model: spectral gate only)",
            statusLine(State.Standby, BellStatus(true, "stage0-only", BellSensitivity.HIGH)),
        )
        assertEquals("Armed. Waiting for the chequered flag.", statusLine(State.Armed, listening))
        assertEquals("Recording the finish.", statusLine(State.Acquisition(flagVisible = true), listening))
    }

    @Test
    fun standbyScreenshot() {
        composeRule.setContent { StandbyScreen(State.Standby, listening, {}, {}) }
        composeRule.onNodeWithTag(TestTags.STANDBY_SCREEN).captureRoboImage("src/test/screenshots/standby_screen.png")
    }

    @Test
    fun armingScreenshot() {
        composeRule.setContent { StandbyScreen(State.Arming, listening, {}, {}) }
        composeRule.onNodeWithTag(TestTags.STANDBY_SCREEN).captureRoboImage("src/test/screenshots/standby_arming.png")
    }

    @Test
    fun armedScreenshot() {
        composeRule.setContent { StandbyScreen(State.Armed, listening, {}, {}) }
        composeRule.onNodeWithTag(TestTags.STANDBY_SCREEN).captureRoboImage("src/test/screenshots/standby_armed.png")
    }
}
