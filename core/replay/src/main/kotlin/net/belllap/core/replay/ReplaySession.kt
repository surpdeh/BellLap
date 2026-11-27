package net.belllap.core.replay

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import net.belllap.audio.source.AudioSource
import net.belllap.audio.source.PcmChunk
import net.belllap.core.statemachine.Effect
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.SessionConfig
import net.belllap.core.statemachine.SessionMachine
import net.belllap.core.statemachine.State
import net.belllap.vision.core.Frame
import net.belllap.vision.core.FrameSource

enum class ReplaySpeed { REAL_TIME, FAST }

/** Observer of a replayed session. Everything is stamped with the media PTS, never the wall clock. */
interface ReplayListener {
    fun onTransition(from: State, to: State, trigger: String, ptsNs: Long)

    fun onEffect(effect: Effect, ptsNs: Long) = Unit
}

/** Short, stable name of a state for logs and the UI overlay. */
fun State.label(): String =
    when (this) {
        State.Setup -> "SETUP"
        State.Standby -> "STANDBY"
        State.Arming -> "ARMING"
        State.Armed -> "ARMED"
        is State.Acquisition -> "ACQUISITION"
        is State.Review -> "REVIEW"
    }

/**
 * Runs a whole session (state machine plus detectors) from a file-backed [AudioSource] and [FrameSource],
 * merging both by PTS. [ReplaySpeed.REAL_TIME] only paces delivery; the time base is still the PTS.
 */
class ReplaySession(
    private val audio: AudioSource,
    private val video: FrameSource,
    private val detectors: Detectors,
    private val listener: ReplayListener,
    private val config: SessionConfig = SessionConfig(),
) {
    private val machine = SessionMachine(config)
    private var flagVisible = false

    suspend fun run(speed: ReplaySpeed = ReplaySpeed.FAST) {
        var lastPts = 0L
        step(Event.StandbyRequested, 0)
        coroutineScope {
            val chunks = channelOf(audio.chunks())
            val frames = channelOf(video.frames())
            var chunk = chunks.next()
            var frame = frames.next()
            while (chunk != null || frame != null) {
                val c = chunk
                val f = frame
                val takeChunk = f == null || (c != null && c.ptsNs <= f.ptsNs)
                val pts = if (c != null && takeChunk) c.ptsNs else checkNotNull(f).ptsNs
                if (speed == ReplaySpeed.REAL_TIME && pts > lastPts) delay((pts - lastPts) / NS_PER_MS)
                lastPts = maxOf(lastPts, pts)
                fireTimeouts(pts)
                if (c != null && takeChunk) {
                    if (detectors.bell.accept(c)) step(Event.BellDetected, pts)
                    chunk = chunks.next()
                } else {
                    onFrame(checkNotNull(f))
                    frame = frames.next()
                }
            }
        }
        fireTimeouts(lastPts)
        repeat(MAX_STOPS) { if (machine.state != State.Setup) step(Event.Stop, lastPts) }
    }

    private fun onFrame(frame: Frame) {
        val pts = frame.ptsNs
        val visible = detectors.flag.accept(frame)
        if (visible != flagVisible) {
            flagVisible = visible
            step(if (visible) Event.FlagSeen else Event.FlagLost, pts)
        }
        if (detectors.crossing.accept(frame)) step(Event.CrossingDetected, pts)
    }

    /** Fires due timeouts one at a time so each state change is reported with its own trigger. */
    private fun fireTimeouts(ptsNs: Long) {
        val ms = ptsNs / NS_PER_MS
        while (machine.nextDeadlineMs?.let { it <= ms } == true) {
            val before = machine.state
            val due = machine.nextDeadlineMs!!
            val trigger = if (before == State.Arming) Event.ArmDelayElapsed else Event.FlagAbsentTimeout
            report(before, machine.advanceTo(due), trigger.toString(), due * NS_PER_MS)
        }
    }

    private fun step(event: Event, ptsNs: Long) {
        val before = machine.state
        report(before, machine.dispatch(event, ptsNs / NS_PER_MS), event.toString(), ptsNs)
    }

    private fun report(before: State, effects: List<Effect>, trigger: String, ptsNs: Long) {
        if (machine.state != before) listener.onTransition(before, machine.state, trigger, ptsNs)
        effects.forEach { listener.onEffect(it, ptsNs) }
    }

    private fun <T : Any> CoroutineScope.channelOf(flow: Flow<T>): ReceiveChannel<T> {
        val channel = Channel<T>(BUFFER)
        launch {
            try {
                flow.collect { channel.send(it) }
            } finally {
                channel.close()
            }
        }
        return channel
    }

    private suspend fun <T : Any> ReceiveChannel<T>.next(): T? = receiveCatching().getOrNull()

    private companion object {
        const val NS_PER_MS = 1_000_000L
        const val BUFFER = 16
        const val MAX_STOPS = 2
    }
}
