package net.belllap

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import net.belllap.core.model.Sidecar
import net.belllap.core.statemachine.State
import net.belllap.ui.TestTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * F17 on a real OS: Standby, Bell now, wait for ARMED, Record now, then Stop. Asserts the acquisition MP4 and its
 * `acq_<id>.json` are in app storage and the sidecar validates. Needs a camera, so it runs on the CI emulator.
 */
class AcquisitionRecordingTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BellLapApp

    @After
    fun backToSetup() {
        if (app.session.state.value != State.Setup) app.session.stop()
        if (app.session.state.value != State.Setup) app.session.stop()
    }

    @Test
    fun recordNowWritesAnMp4AndAValidSidecar() {
        val dir = File(app.filesDir, "acquisitions").also { it.deleteRecursively() }
        composeRule.onNodeWithTag(TestTags.STANDBY_BUTTON).performClick()
        composeRule.onNodeWithTag(TestTags.BELL_NOW_BUTTON).performClick()
        composeRule.waitUntil(ARM_TIMEOUT_MS) { app.session.state.value == State.Armed }
        composeRule.waitForIdle()
        Thread.sleep(CAMERA_WARMUP_MS)
        composeRule.onNodeWithTag(TestTags.RECORD_NOW_BUTTON).performClick()
        composeRule.waitUntil(ARM_TIMEOUT_MS) { app.session.state.value is State.Acquisition }
        Thread.sleep(RECORD_MS)
        composeRule.onNodeWithTag(TestTags.STOP_BUTTON).performClick()
        composeRule.waitUntil(ARM_TIMEOUT_MS) { app.session.state.value == State.Armed }

        val sidecarFile = dir.listFiles { f -> f.name.startsWith("acq_") && f.extension == "json" }.orEmpty().single()
        val sidecar = Sidecar.decode(sidecarFile.readText())
        assertEquals("acq_${sidecar.id}.json", sidecarFile.name)
        assertTrue(sidecar.name, sidecar.name.endsWith(" Finish 1"))
        val mp4 = File(dir, sidecar.videoFile)
        composeRule.waitUntil(ARM_TIMEOUT_MS) { mp4.length() > 0 } // the recorder finalizes asynchronously
        assertTrue("${mp4.name} is empty", mp4.length() > 0)
    }

    private companion object {
        const val ARM_TIMEOUT_MS = 20_000L
        const val CAMERA_WARMUP_MS = 1_500L
        const val RECORD_MS = 2_000L
    }
}
