package net.belllap.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun lines(f: java.io.File) = f.readLines().filter { it.isNotBlank() }

    @Test
    fun everyEventTypeRoundTrips() {
        val log = EventLog(tmp.newFolder(), nowMs = { 42 })
        val events = listOf(
            StateTransition("ARMED", "ACQUISITION", "flag", 123L),
            DetectorScore("bell", 0.75, 9L),
            DeviceInfo("Google", "Pixel", 34, "14", "0.1.0"),
            BatteryInfo(80, true, 31.5),
            ExitRecord("ANR", 1, 100, 0, 5L, "x"),
        )
        events.forEach(log::log)
        val parsed = lines(log.files.single()).map(EventLog::parse)
        assertEquals(events, parsed.map { it.event })
        assertTrue(parsed.all { it.tMs == 42L })
    }

    @Test
    fun linesAreSingleLineJsonWithTypeDiscriminator() {
        val log = EventLog(tmp.newFolder(), nowMs = { 1 })
        log.transition("SETUP", "STANDBY", "start")
        assertEquals(
            """{"tMs":1,"event":{"type":"transition","from":"SETUP","to":"STANDBY","trigger":"start"}}""",
            lines(log.files.single()).single(),
        )
    }

    @Test
    fun rotatesAndKeepsAtMostMaxFiles() {
        val dir = tmp.newFolder()
        val log = EventLog(dir, nowMs = { 0 }, maxBytes = 200, maxFiles = 3)
        repeat(50) { log.transition("A", "B", "t$it") }
        val files = log.files
        assertEquals(3, files.size)
        assertEquals(listOf("events.jsonl", "events.1.jsonl", "events.2.jsonl"), files.map { it.name })
        assertTrue(files.all { it.length() <= 200 })
        val triggers = files.reversed().flatMap(::lines).map { (EventLog.parse(it).event as StateTransition).trigger }
        assertEquals("t49", triggers.last())
        assertEquals(triggers.sortedBy { it.drop(1).toInt() }, triggers)
        assertFalse("t0" in triggers)
    }

    @Test
    fun resumesSizeFromExistingFileAcrossInstances() {
        val dir = tmp.newFolder()
        EventLog(dir, maxBytes = 300, maxFiles = 2).transition("A", "B", "first")
        val second = EventLog(dir, maxBytes = 300, maxFiles = 2)
        repeat(5) { second.transition("A", "B", "x$it") }
        assertEquals(2, second.files.size)
        assertTrue(second.files.all { it.length() <= 300 })
    }

    @Test
    fun decimatesScoresByIntervalAndDelta() {
        val log = EventLog(tmp.newFolder(), decimator = ScoreDecimator(minIntervalNs = 1000, minDelta = 0.5))
        assertTrue(log.detectorScore("bell", 0.1, 0))
        assertFalse(log.detectorScore("bell", 0.2, 500))
        assertTrue(log.detectorScore("bell", 0.9, 600))
        assertFalse(log.detectorScore("bell", 0.9, 1200))
        assertTrue(log.detectorScore("bell", 0.9, 1700))
        assertTrue(log.detectorScore("flag", 0.1, 10))
        assertEquals(4, lines(log.files.single()).size)
    }
}
