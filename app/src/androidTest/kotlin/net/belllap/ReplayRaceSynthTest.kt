package net.belllap

import android.os.SystemClock
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
import net.belllap.debug.ReplayController
import net.belllap.debug.ReplayTags
import net.belllap.testing.fixtures.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** F24: replays race_synth (real MP4 decode, WAV source, state machine) from the debug menu and checks the log. */
class ReplayRaceSynthTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Puts the host-encoded race_synth (`:app:raceSynthMedia`, androidTest assets) where the app caches it, so the
     * replay skips encoding on the emulator. Without those assets (no ffmpeg at build time) the app encodes it.
     */
    @Before
    fun placeHostEncodedMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val names = assets.list(ASSET_DIR).orEmpty()
        val dir = File(instrumentation.targetContext.cacheDir, ASSET_DIR).apply { mkdirs() }
        for (name in names) {
            assets.open("$ASSET_DIR/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        android.util.Log.i("BellLapReplay", "host-encoded media placed: ${names.toList()}")
    }

    @Test
    fun replaysRaceSynthAndLogsTheEventSequence() {
        composeRule.onNodeWithTag(ReplayTags.OPEN).performClick()
        composeRule.onNodeWithTag(ReplayTags.RUN_RACE_SYNTH).performClick()

        awaitReplayFinished("replaysRaceSynth")
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
        Thread.sleep(HOLD_MS) // let the preview settle, as LaunchTest does
        TestEvidence.awaitPreviewContent { timeoutMs, condition -> composeRule.waitUntil(timeoutMs, condition) }
        TestEvidence.screenshot("10-journey-setup-with-debug-entry")
        composeRule.onNodeWithTag(ReplayTags.OPEN).performClick()
        composeRule.waitForIdle()
        TestEvidence.screenshot("11-journey-replay-menu")

        composeRule.onNodeWithTag(ReplayTags.SPEED).performClick() // off (fast) -> on (real time)
        composeRule.waitForIdle()
        TestEvidence.screenshot("12-journey-real-time-selected")

        composeRule.onNodeWithTag(ReplayTags.RUN_RACE_SYNTH).performClick()
        val start = SystemClock.elapsedRealtime()
        composeRule.waitUntil(PREPARE_TIMEOUT_MS) { statusText() != ReplayController.PREPARING }
        logTiming("journey", "prepare", start)
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
        awaitReplayFinished("journey")
        logTiming("journey", "total", start)
        composeRule.onNodeWithTag(ReplayTags.STATE).assertTextEquals("SETUP")
        composeRule.waitForIdle()
        Thread.sleep(HOLD_MS)
        TestEvidence.screenshot("17-journey-done-event-list")
        assertEquals("replay events in events.jsonl", expectedSequence(), replayedTransitions())
    }

    private fun expectedSequence(): List<String> {
        val crossings = Fixtures.race(withFrames = false).labels.crossings.size
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

    /**
     * Waits for Done or Failed. Preparing the media (generating and encoding race_synth on a cold cache) gets its
     * own budget, because on the CI emulator it alone has taken over two minutes. On a timeout, keeps a screenshot
     * and reports what the screen said. Logs how long each wait took under [name] (see [logTiming]).
     */
    private fun awaitReplayFinished(name: String) {
        try {
            val start = SystemClock.elapsedRealtime()
            composeRule.waitUntil(PREPARE_TIMEOUT_MS) { statusText() != ReplayController.PREPARING }
            val prepared = SystemClock.elapsedRealtime()
            logTiming(name, "prepare_wait", start)
            composeRule.waitUntil(TIMEOUT_MS) { statusText().let { it.startsWith("Done") || it.startsWith("Failed") } }
            logTiming(name, "replay", prepared)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching { TestEvidence.screenshot("replay-timeout") }
            throw AssertionError("Replay timed out. Status='${statusText()}' state='${textOf(ReplayTags.STATE)}'", e)
        }
        assertTrue("Replay did not complete: ${statusText()}", statusText().startsWith("Done"))
    }

    /**
     * Logs `BellLapTiming: <test> <phase> <ms>ms` for scripts/ci/test-timings.py, which puts it in
     * evidence/test-timings.json. Wall-clock test timing only; the app's own time base stays the frame PTS.
     */
    private fun logTiming(test: String, phase: String, sinceElapsedMs: Long) {
        android.util.Log.i(TIMING_TAG, "$test $phase ${SystemClock.elapsedRealtime() - sinceElapsedMs}ms")
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
        const val ASSET_DIR = "replay"
        const val TIMING_TAG = "BellLapTiming"
        const val PREPARE_TIMEOUT_MS = 300_000L
        const val TIMEOUT_MS = 180_000L
        const val MODE_TIMEOUT_MS = 90_000L
        const val HOLD_MS = 2_000L
    }
}
