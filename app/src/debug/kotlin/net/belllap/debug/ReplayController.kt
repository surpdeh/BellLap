package net.belllap.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.belllap.audio.source.WavFileAudioSource
import net.belllap.core.log.EventLog
import net.belllap.core.replay.ReplayListener
import net.belllap.core.replay.ReplaySession
import net.belllap.core.replay.ReplaySpeed
import net.belllap.core.replay.label
import net.belllap.core.statemachine.Effect
import net.belllap.core.statemachine.State
import net.belllap.vision.core.VideoFileFrameSource

/** Runs a [ReplayInput] through [ReplaySession], mirroring every state change to the UI and the event log. */
class ReplayController(private val eventLog: EventLog) {
    var stateLabel by mutableStateOf("SETUP")
        private set
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Idle")
        private set
    val lines = mutableStateListOf<String>()

    suspend fun replay(prepare: () -> ReplayInput, speed: ReplaySpeed) {
        if (running) return
        running = true
        lines.clear()
        try {
            status = "Preparing media"
            val input = withContext(Dispatchers.IO) { prepare() }
            status = "Replaying ${input.name} (${speed.name.lowercase()})"
            val audio = withContext(Dispatchers.IO) { WavFileAudioSource(input.audio) }
            val video = VideoFileFrameSource(input.video, MediaCodecVideoDecoder())
            withContext(Dispatchers.Default) {
                ReplaySession(audio, video, input.detectors, listener()).run(speed)
            }
            status = "Done: ${input.name}"
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            status = "Failed: ${e.message}"
        } finally {
            running = false
        }
    }

    private fun listener() = object : ReplayListener {
        var current: State = State.Setup

        override fun onTransition(from: State, to: State, trigger: String, ptsNs: Long) {
            current = to
            eventLog.transition(from.label(), to.label(), trigger, ptsNs)
            post(
                "${"%.1f".format(ptsNs / NS_PER_S)}s  ${from.label()} > ${to.label()}  $trigger",
            ) { stateLabel = to.label() }
        }

        override fun onEffect(effect: Effect, ptsNs: Long) {
            if (effect == Effect.PlayCrossingBeep) {
                eventLog.transition(current.label(), current.label(), "CrossingDetected", ptsNs)
                post("${"%.1f".format(ptsNs / NS_PER_S)}s  CROSSING") {}
            }
        }

        private fun post(line: String, update: () -> Unit) {
            update()
            lines += line
        }
    }

    private companion object {
        const val NS_PER_S = 1e9
    }
}
