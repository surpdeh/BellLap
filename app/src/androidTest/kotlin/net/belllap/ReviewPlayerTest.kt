package net.belllap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import net.belllap.core.model.AcquisitionStore
import net.belllap.core.model.CrossingSource
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.review.ReviewTags
import net.belllap.testing.fixtures.Fixtures
import net.belllap.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * F19 on a real OS: opens the host-encoded race_synth MP4 (the replay fixture, `:app:raceSynthMedia`) from the review
 * list, nudges one frame, jumps between finishers, pinch-zooms and checks the zoom survives a step, adds a mark and
 * renames the recording. Needs the Media3 decoder, so it runs on the CI emulator, not in the cloud container.
 */
class ReviewPlayerTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val dir get() = File(composeRule.activity.filesDir, "acquisitions")
    private val labels = Fixtures.race(withFrames = false).labels
    private val firstMarkFrame get() = labels.crossings.first().frame

    /** Puts the fixture MP4 and a sidecar for it where F17 writes acquisitions. */
    @Before
    fun installFixtureAcquisition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(
            "no host-encoded race_synth.mp4 (build without ffmpeg)",
            "race_synth.mp4" in assets.list("replay").orEmpty(),
        )
        dir.deleteRecursively()
        dir.mkdirs()
        assets.open("replay/race_synth.mp4").use {
                input ->
            File(dir, "acq_$ID.mp4").outputStream().use { input.copyTo(it) }
        }
        val sidecar = Sidecar(
            id = ID,
            name = "Fixture Finish 1",
            videoFile = "acq_$ID.mp4",
            device = SidecarDevice("Test", "Emulator", 34),
            fps = checkNotNull(labels.fps).toInt(),
            width = 320,
            height = 240,
            firstFramePtsUs = 0,
            crossings = labels.crossings.map {
                SidecarCrossing(
                    it.ptsNs / NS_PER_US,
                    it.frame,
                    0.9f,
                    CrossingSource.REFINED,
                )
            },
            appVersion = "test",
        )
        AcquisitionStore(dir).save(sidecar)
    }

    private fun waitForTag(tag: String) = composeRule.waitUntil(TIMEOUT_MS) {
        composeRule.onAllNodesWithTagCount(tag) > 0
    }

    private fun ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun opensTheFixtureNudgesZoomsAndEditsMarks() {
        waitForTag(TestTags.REVIEW_BUTTON)
        composeRule.onNodeWithTag(TestTags.REVIEW_BUTTON).performClick()
        waitForTag(ReviewTags.ITEM_PREFIX + ID)
        TestEvidence.screenshot("30-review-list")
        composeRule.onNodeWithTag(ReviewTags.ITEM_PREFIX + ID).performClick()

        waitForTag(ReviewTags.FRAME_LABEL)
        composeRule.onNodeWithTag(
            ReviewTags.FRAME_LABEL,
        ).assertTextContains("Frame ${firstMarkFrame + 1} /", substring = true)
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(
            ReviewTags.FRAME_LABEL,
        ).assertTextContains("Frame ${firstMarkFrame + 2} /", substring = true)
        composeRule.onNodeWithTag(ReviewTags.STEP_BACK).performClick()
        composeRule.onNodeWithTag(
            ReviewTags.FRAME_LABEL,
        ).assertTextContains("Frame ${firstMarkFrame + 1} /", substring = true)
        Thread.sleep(SETTLE_MS) // let the decoder show the sought frame for the screenshot
        TestEvidence.screenshot("31-review-player-first-finisher")

        composeRule.onNodeWithTag(ReviewTags.ZOOM_AREA).performTouchInput {
            pinch(
                Offset(centerX - 20, centerY),
                Offset(centerX - 80, centerY),
                Offset(centerX + 20, centerY),
                Offset(centerX + 80, centerY),
            )
        }
        composeRule.waitForIdle()
        val zoomed = zoomLabel()
        assertTrue("pinch should zoom in, label=$zoomed", zoomed != "Zoom 1.0x")
        composeRule.onNodeWithTag(ReviewTags.STEP_FORWARD).performClick()
        composeRule.onNodeWithTag(ReviewTags.ZOOM_LABEL).assertTextContains(zoomed)
        Thread.sleep(SETTLE_MS)
        TestEvidence.screenshot("32-review-player-zoomed")

        composeRule.onNodeWithTag(ReviewTags.ADD_MARK).performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            AcquisitionStore(dir).load(ID)?.sidecar?.crossings.orEmpty().any { it.source == CrossingSource.MANUAL }
        }
        val saved = checkNotNull(AcquisitionStore(dir).load(ID)).sidecar
        assertEquals(labels.crossings.size + 1, saved.crossings.size)
        composeRule.onNodeWithTag(ReviewTags.DELETE_MARK).performClick()
        composeRule.waitUntil(
            TIMEOUT_MS,
        ) { AcquisitionStore(dir).load(ID)?.sidecar?.crossings?.size == labels.crossings.size }

        composeRule.onNodeWithTag(ReviewTags.CLOSE).performClick()
        waitForTag(ReviewTags.RENAME_PREFIX + ID)
        composeRule.onNodeWithTag(ReviewTags.RENAME_PREFIX + ID).performClick()
        composeRule.onNodeWithTag(ReviewTags.NAME_FIELD).performTextReplacement("A grade")
        composeRule.onNodeWithTag(ReviewTags.NAME_CONFIRM).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { AcquisitionStore(dir).load(ID)?.sidecar?.name == "A grade" }
    }

    private fun zoomLabel(): String = composeRule.onNodeWithTag(ReviewTags.ZOOM_LABEL).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private companion object {
        const val ID = "review_fixture"
        const val NS_PER_US = 1_000L
        const val TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 1_000L
    }
}
