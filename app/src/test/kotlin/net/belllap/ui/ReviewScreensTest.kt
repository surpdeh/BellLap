package net.belllap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.github.takahirom.roborazzi.captureRoboImage
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.CrossingSource
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.core.statemachine.State
import net.belllap.review.ReviewItem
import net.belllap.review.ReviewListScreen
import net.belllap.review.ReviewPlayerScreen
import net.belllap.review.ReviewState
import net.belllap.review.ReviewTags
import net.belllap.session.BellStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReviewScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val items = listOf(
        ReviewItem("b", "2026-11-28 Finish 2", "3 finishers, 60 fps"),
        ReviewItem("a", "A grade", "5 finishers, 30 fps"),
    )
    private val seeks = mutableListOf<Long>()
    private val saved = mutableListOf<Sidecar>()

    private fun sidecar() = Sidecar(
        id = "a",
        name = "A grade",
        videoFile = "acq_a.mp4",
        device = SidecarDevice("G", "P", 36),
        fps = 30,
        width = 1920,
        height = 1080,
        firstFramePtsUs = 0,
        crossings = listOf(30, 90, 150).map { SidecarCrossing(it * 1_000_000L / 30, it, 0.8f, CrossingSource.REFINED) },
        appVersion = "t",
    )

    private fun state() = ReviewState(sidecar(), 10_000_000L, { seeks += it }, { saved += it })

    private fun setPlayer(state: ReviewState) = composeRule.setContent {
        ReviewPlayerScreen(state, onClose = {}) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2E5E4E)), contentAlignment = Alignment.Center) {
                Text("video", color = Color.White)
            }
        }
    }

    @Test
    fun listOpensAnAcquisition() {
        var opened: String? = null
        composeRule.setContent { ReviewListScreen(items, { opened = it }, { _, _ -> }, {}, {}) }
        composeRule.onNodeWithTag(ReviewTags.LIST_SCREEN).captureRoboImage("src/test/screenshots/review_list.png")
        composeRule.onNodeWithTag(ReviewTags.ITEM_PREFIX + "b").performClick()
        assertEquals("b", opened)
    }

    @Test
    fun listOffersRenamePerRow() {
        // The rename dialog holds a text field, whose cursor blink keeps Robolectric from idling; the emulator test
        // ReviewPlayerTest types a name instead.
        composeRule.setContent { ReviewListScreen(items, {}, { _, _ -> }, {}, {}) }
        composeRule.onNodeWithTag(ReviewTags.RENAME_PREFIX + "a").assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.RENAME_PREFIX + "b").assertIsDisplayed()
    }

    @Test
    fun deleteAsksToConfirm() {
        var deleted: String? = null
        composeRule.setContent { ReviewListScreen(items, {}, { _, _ -> }, { deleted = it }, {}) }
        composeRule.onNodeWithTag(ReviewTags.DELETE_PREFIX + "a").performClick()
        assertEquals(null, deleted)
        composeRule.onNodeWithTag(ReviewTags.DELETE_CONFIRM).performClick()
        assertEquals("a", deleted)
    }

    @Test
    fun emptyListSaysSo() {
        composeRule.setContent { ReviewListScreen(emptyList(), {}, { _, _ -> }, {}, {}) }
        composeRule.onNodeWithTag(ReviewTags.LIST_EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(ReviewTags.LIST_SCREEN).captureRoboImage("src/test/screenshots/review_list_empty.png")
    }

    @Test
    fun playerStepsFramesAndJumpsBetweenFinishers() {
        val state = state()
        setPlayer(state)
        composeRule.onNodeWithTag(ReviewTags.FRAME_LABEL).assertTextContains("Frame 31 / 300", substring = true)
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(ReviewTags.FRAME_LABEL).assertTextContains("Frame 32 / 300", substring = true)
        composeRule.onNodeWithTag(ReviewTags.STEP_BACK).performClick()
        composeRule.onNodeWithTag(ReviewTags.NEXT_FINISHER).performClick()
        composeRule.onNodeWithTag(ReviewTags.FRAME_LABEL).assertTextContains("Frame 91 / 300  Mark 2 of 3")
        composeRule.onNodeWithTag(ReviewTags.PREVIOUS_FINISHER).performClick()
        assertEquals(30, state.frameIndex)
        assertEquals(state.clock.positionUs(30), seeks.last())
        composeRule.onNodeWithTag(ReviewTags.PLAYER_SCREEN).captureRoboImage("src/test/screenshots/review_player.png")
    }

    @Test
    fun pinchZoomsAndStaysZoomedWhileStepping() {
        val state = state()
        setPlayer(state)
        composeRule.onNodeWithTag(ReviewTags.ZOOM_LABEL).assertTextContains("Zoom 1.0x")
        composeRule.onNodeWithTag(ReviewTags.ZOOM_AREA).performTouchInput {
            pinch(
                Offset(centerX - 20, centerY),
                Offset(centerX - 60, centerY),
                Offset(centerX + 20, centerY),
                Offset(centerX + 60, centerY),
            )
        }
        val scale = state.zoomPan.scale
        assert(scale > 1.5f) { "pinch out should zoom in, scale=$scale" }
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(ReviewTags.NEXT_FINISHER).performClick()
        assertEquals(scale, state.zoomPan.scale, 0f)
        composeRule.onNodeWithTag(ReviewTags.ZOOM_LABEL).assertTextContains("Zoom %.1fx".format(scale))
        composeRule.onNodeWithTag(
            ReviewTags.PLAYER_SCREEN,
        ).captureRoboImage("src/test/screenshots/review_player_zoomed.png")
    }

    @Test
    fun marksCanBeAddedMovedAndDeleted() {
        val state = state()
        setPlayer(state)
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(ReviewTags.MOVE_MARK).assertIsEnabled().performClick()
        assertEquals(listOf(31, 90, 150), state.sidecar.crossings.map { it.frameIndex })
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(ReviewTags.ADD_MARK).performClick()
        assertEquals(listOf(31, 32, 90, 150), state.sidecar.crossings.map { it.frameIndex })
        composeRule.onNodeWithTag(ReviewTags.DELETE_MARK).performClick()
        assertEquals(listOf(31, 90, 150), state.sidecar.crossings.map { it.frameIndex })
        composeRule.onNodeWithTag(ReviewTags.MOVE_MARK).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReviewTags.DELETE_MARK).assertIsNotEnabled()
        assertEquals(3, saved.size)
        composeRule.onNodeWithTag(
            ReviewTags.PLAYER_SCREEN,
        ).captureRoboImage("src/test/screenshots/review_player_edited.png")
    }

    @Test
    fun standbyOffersReviewInEveryMode() {
        var opened = 0
        var mode by mutableStateOf<State>(State.Armed)
        composeRule.setContent { StandbyScreen(mode, BellStatus(), {}, {}, onReview = { opened++ }) }
        composeRule.onNodeWithTag(TestTags.REVIEW_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithTag(
            TestTags.STANDBY_SCREEN,
        ).captureRoboImage("src/test/screenshots/standby_armed_review_button.png")
        for (state in listOf(State.Standby, State.Arming, State.Acquisition(false))) {
            mode = state
            composeRule.onNodeWithTag(TestTags.REVIEW_BUTTON).assertIsDisplayed()
        }
    }

    @Test
    fun setupOffersReview() {
        var opened = 0
        composeRule.setContent {
            SetupScreen(
                settings = CaptureSettings(),
                capabilities = null,
                permission = PermissionUi.GRANTED,
                settingsOpen = false,
                onSettingsOpenChange = {},
                onSettingsChange = {},
                onFinishLineChange = {},
                onRequestPermissions = {},
                onOpenSystemSettings = {},
                onReview = { opened++ },
                preview = { CameraPreviewPlaceholder() },
            )
        }
        composeRule.onNodeWithTag(TestTags.REVIEW_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithTag(
            TestTags.SETUP_SCREEN,
        ).captureRoboImage("src/test/screenshots/setup_review_button.png")
    }
}
