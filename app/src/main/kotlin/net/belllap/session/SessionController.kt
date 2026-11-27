package net.belllap.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.bell.BellDetector
import net.belllap.audio.bell.BellDetectorConfig
import net.belllap.audio.bell.BellTrace
import net.belllap.audio.source.AudioSource
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CaptureSettings
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.SessionMachine
import net.belllap.core.statemachine.State
import kotlin.coroutines.cancellation.CancellationException

/** What the bell listener is doing, for the STANDBY screen. */
data class BellStatus(
    val listening: Boolean = false,
    val classifier: String? = null,
    val sensitivity: BellSensitivity = BellSensitivity.MEDIUM,
    val lastScore: Float? = null,
    val error: String? = null,
)

/** Where the session reports what happened; the app writes these to the JSONL event log. */
interface SessionLog {
    fun transition(from: State, to: State, trigger: String)

    fun bell(trace: BellTrace)
}

/**
 * Owns the [SessionMachine] while the activity is on screen and runs the bell cascade in STANDBY (F14).
 * F13 moves this into the foreground service; the API stays the same.
 *
 * [nowMs] is a monotonic clock for the machine's timers (ARMING's 10 s). Bell detections are dispatched as they
 * are decided; the detector itself only sees audio PTS. [openAudio] opens a fresh source each time STANDBY starts
 * listening, and [classifier] is the stage-1 model, loaded once and reused.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val openAudio: () -> AudioSource,
    private val classifier: () -> BellClassifier,
    private val log: SessionLog,
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val machine = SessionMachine(nowMs = nowMs())
    private val mutableState = MutableStateFlow<State>(machine.state)
    private val mutableBell = MutableStateFlow(BellStatus())
    private var settings = CaptureSettings()
    private var timer: Job? = null
    private var listener: Job? = null

    val state: StateFlow<State> = mutableState.asStateFlow()
    val bell: StateFlow<BellStatus> = mutableBell.asStateFlow()

    @Synchronized
    fun updateSettings(new: CaptureSettings) {
        val restart = new.bellAutomation != settings.bellAutomation || new.bellSensitivity != settings.bellSensitivity
        settings = new
        if (restart) {
            stopListening()
            syncListener()
        }
    }

    fun standby() = dispatch(Event.StandbyRequested, "user")

    fun bellNow() = dispatch(Event.BellNow, "user")

    fun stop() = dispatch(Event.Stop, "user")

    @Synchronized
    fun dispatch(event: Event, trigger: String) {
        val before = machine.state
        machine.dispatch(event, nowMs())
        afterChange(before, "$trigger:${event::class.simpleName}")
    }

    @Synchronized
    private fun tick() {
        val before = machine.state
        machine.advanceTo(nowMs())
        afterChange(before, "timer")
    }

    /** Effects (brightness, beeps, recording) are F13, F15 and F17; this feature only drives the modes. */
    private fun afterChange(before: State, trigger: String) {
        val after = machine.state
        if (after != before) log.transition(before, after, trigger)
        // Sync the listener first, so anyone who sees the new state also sees the listener status that goes with it.
        syncListener()
        mutableState.value = after
        timer?.cancel()
        timer = machine.nextDeadlineMs?.let { deadline ->
            scope.launch {
                delay(maxOf(0L, deadline - nowMs()))
                tick()
            }
        }
    }

    private fun inStandby() = machine.state.let { if (it is State.Review) it.underlying else it } == State.Standby

    private fun syncListener() {
        val want = inStandby() && settings.bellAutomation
        if (want && listener == null) {
            startListening()
        } else if (!want && listener != null) {
            stopListening()
        }
    }

    private fun startListening() {
        val sensitivity = settings.bellSensitivity
        mutableBell.value = BellStatus(listening = true, sensitivity = sensitivity)
        listener = scope.launch(audioDispatcher) {
            try {
                val stage1 = classifier()
                mutableBell.value = mutableBell.value.copy(classifier = stage1.name)
                val audio = openAudio()
                val detector = BellDetector(audio.sampleRate, stage1, BellDetectorConfig.forSensitivity(sensitivity)) {
                    log.bell(it)
                    if (it is BellTrace.Scored) mutableBell.value = mutableBell.value.copy(lastScore = it.score)
                }
                audio.chunks().collect { chunk ->
                    if (detector.accept(chunk).isNotEmpty()) dispatch(Event.BellDetected, "bell")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                mutableBell.value = mutableBell.value.copy(listening = false, error = e.message ?: e.toString())
            }
        }
    }

    private fun stopListening() {
        listener?.cancel()
        listener = null
        mutableBell.value = mutableBell.value.copy(listening = false)
    }
}
