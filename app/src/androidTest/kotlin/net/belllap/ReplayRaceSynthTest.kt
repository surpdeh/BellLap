package net.belllap

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import net.belllap.core.log.EventLog
import net.belllap.core.log.StateTransition
import net.belllap.debug.ReplayTags
import net.belllap.testing.fixtures.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** F24: replays race_synth (real MP4 decode, WAV source, state machine) from the debug menu and checks the log. */
class ReplayRaceSynthTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun replaysRaceSynthAndLogsTheEventSequence() {
        composeRule.onNodeWithTag(ReplayTags.OPEN).performClick()
        composeRule.onNodeWithTag(ReplayTags.RUN_RACE_SYNTH).performClick()

        composeRule.waitUntil(TIMEOUT_MS) { statusText().let { it.startsWith("Done") || it.startsWith("Failed") } }
        val status = statusText()
        assertTrue("Replay did not complete: $status", status.startsWith("Done"))
        composeRule.onNodeWithTag(ReplayTags.STATE).assertTextEquals("SETUP")
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MS)
        TestEvidence.screenshot("02-replay-race-synth-done")

        assertEquals("replay events in events.jsonl", expectedSequence(), replayedTransitions())
    }

    /**
     * The user journey at real time, so the recording and screenshots show the debug menu and each session
     * mode as it happens: menu, ARMING after the bell, ARMED, ACQUISITION on the flag, crossings, done.
     */
    @Test
    fun journeyAtRealTimeShowsEveryMode() {
        TestEvidence.awaitPreviewContent { timeoutMs, condition -> composeRule.waitUntil(timeoutMs, condition) }
        TestEvidence.screenshot("10-journey-setup-with-debug-entry")
        composeRule.onNodeWithTag(ReplayTags.OPEN).performClick()
        composeRule.waitForIdle()
        TestEvidence.screenshot("11-journey-replay-menu")

        composeRule.onNodeWithTag(ReplayTags.SPEED).performClick() // fast -> real time
        composeRule.waitForIdle()
        TestEvidence.screenshot("12-journey-real-time-selected")

        composeRule.onNodeWithTag(ReplayTags.RUN_RACE_SYNTH).performClick()
        for ((mode, shot) in listOf(
            "STANDBY" to "13-journey-standby",
            "ARMING" to "14-journey-arming-after-bell",
            "ARMED" to "15-journey-armed",
            "ACQUISITION" to "16-journey-acquisition-flag",
        )) {
            composeRule.waitUntil(MODE_TIMEOUT_MS) { textOf(ReplayTags.STATE) == mode }
            composeRule.waitForIdle()
            TestEvidence.screenshot(shot)
        }
        composeRule.waitUntil(TIMEOUT_MS) { statusText().let { it.startsWith("Done") || it.startsWith("Failed") } }
        assertTrue("Replay did not complete: ${statusText()}", statusText().startsWith("Done"))
        composeRule.onNodeWithTag(ReplayTags.STATE).assertTextEquals("SETUP")
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MS)
        TestEvidence.screenshot("17-journey-done-event-list")
        assertEquals("replay events in events.jsonl", expectedSequence(), replayedTransitions())
    }

    private fun expectedSequence(): List<String> {
        val crossings = Fixtures.race().labels.crossings.size
        return listOf(
            "SETUP>STANDBY:StandbyRequested",
            "STANDBY>ARMING:BellDetected",
            "ARMING>ARMED:ArmDelayElapsed",
            "ARMED>ACQUISITION:FlagSeen",
        ) + List(crossings) { "ACQUISITION>ACQUISITION:CrossingDetected" } + listOf(
            "ACQUISITION>ACQUISITION:FlagLost",
            "ACQUISITION>ARMED:Stop",
            "ARMED>SETUP:Stop",
        )
    }

    private fun statusText(): String = textOf(ReplayTags.STATUS)

    private fun textOf(tag: String): String =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    /** The newest run of replay transitions (those carrying a PTS) in the app's event log. */
    private fun replayedTransitions(): List<String> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BellLapApp
        val all = app.eventLog.files.sortedBy { it.name }.flatMap { it.readLines() }.filter { it.isNotBlank() }
            .map { EventLog.parse(it).event }
            .filterIsInstance<StateTransition>()
            .filter { it.ptsNs != null }
            .map { "${it.from}>${it.to}:${it.trigger}" }
        val start = all.lastIndexOf("SETUP>STANDBY:StandbyRequested")
        assertTrue("no replay found in event log: $all", start >= 0)
        return all.drop(start)
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val MODE_TIMEOUT_MS = 90_000L
        const val HOLD_MS = 2_000L
    }
}
