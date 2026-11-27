package net.belllap

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
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
    @get:Rule
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

        val crossings = Fixtures.race().labels.crossings.size
        val expected = listOf(
            "SETUP>STANDBY:StandbyRequested",
            "STANDBY>ARMING:BellDetected",
            "ARMING>ARMED:ArmDelayElapsed",
            "ARMED>ACQUISITION:FlagSeen",
        ) + List(crossings) { "ACQUISITION>ACQUISITION:CrossingDetected" } + listOf(
            "ACQUISITION>ACQUISITION:FlagLost",
            "ACQUISITION>ARMED:Stop",
            "ARMED>SETUP:Stop",
        )
        assertEquals("replay events in events.jsonl", expected, replayedTransitions())
    }

    private fun statusText(): String =
        composeRule.onNodeWithTag(ReplayTags.STATUS).fetchSemanticsNode().config
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
        const val HOLD_MS = 2_000L
    }
}
