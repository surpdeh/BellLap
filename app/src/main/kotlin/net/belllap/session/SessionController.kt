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
import net.belllap.core.model.AcquisitionNaming
import net.belllap.core.model.AcquisitionSink
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.CrossingSource
import net.belllap.core.model.FinishLine
import net.belllap.core.model.FlagIntervalTracker
import net.belllap.core.model.RecordingUnavailableException
import net.belllap.core.model.SelfTestVerdict
import net.belllap.core.model.Sidecar
import net.belllap.core.model.SidecarCrossing
import net.belllap.core.model.SidecarDevice
import net.belllap.core.model.StorageGuard
import net.belllap.core.service.SessionHost
import net.belllap.core.statemachine.BeepSink
import net.belllap.core.statemachine.Cue
import net.belllap.core.statemachine.Effect
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.SessionMachine
import net.belllap.core.statemachine.State
import net.belllap.core.statemachine.cueOrNull
import net.belllap.vision.core.Frame
import net.belllap.vision.finishline.SlitScanCrossingDetector
import net.belllap.vision.finishline.toSidecar
import java.util.UUID
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
 * The foreground service (F13) hosts it through [SessionHost], so it survives the activity.
 *
 * [nowMs] is a monotonic clock for the machine's timers (ARMING's 10 s). Bell detections are dispatched as they
 * are decided; the detector itself only sees audio PTS. [openAudio] opens a fresh source each time STANDBY starts
 * listening, and [classifier] is the stage-1 model, loaded once and reused. Audible effects go to [beeps] when the
 * current settings allow that cue (F15).
 *
 * Recording (F17): the machine's StartRecording and StopRecording effects are routed to [sink], and flag visibility
 * changes are tracked by the sink's frame PTS for the sidecar. Crossings (F18): [onFrame] feeds camera frames to the
 * slit-scan detector while the camera is needed, so its background is warm when ACQUISITION starts; only in
 * ACQUISITION does a crossing beep and go to the sidecar. [eventLabel] names the day's acquisitions ("<label>
 * Finish N"); it is a label, not a time base.
 */
@Suppress("TooManyFunctions")
class SessionController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val openAudio: () -> AudioSource,
    private val classifier: () -> BellClassifier,
    private val log: SessionLog,
    private val beeps: BeepSink,
    private val sink: AcquisitionSink,
    private val device: SidecarDevice,
    private val appVersion: String,
    private val eventLabel: () -> String,
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Whether the screen is on and interactive; the F33 self-test needs it off when the simulated bell fires. */
    private val screenOn: () -> Boolean = { true },
    /** Called with each finished self-test run, so the app can persist the pass for this device. */
    private val onSelfTestVerdict: (SelfTestVerdict) -> Unit = {},
) : SessionHost {
    private val machine = SessionMachine(nowMs = nowMs())
    private val mutableState = MutableStateFlow<State>(machine.state)
    private val mutableBell = MutableStateFlow(BellStatus())

    /** The settings last passed to [updateSettings]. */
    var settings = CaptureSettings()
        private set
    private var timer: Job? = null
    private var listener: Job? = null
    private var active: ActiveRecording? = null
    private var selfTestJob: Job? = null
    private val mutableSelfTest = MutableStateFlow<SelfTestStatus>(SelfTestStatus.Idle)
    private var crossings: SlitScanCrossingDetector? = null
    private var crossingLine: FinishLine? = null
    private val mutableNotice = MutableStateFlow<String?>(null)

    /** The alert-stream level for cues: the custom percent, or 100 (maximum) by default. */
    val alertVolumePercent: Int @Synchronized get() = settings.alertVolumePercent ?: MAX_VOLUME_PERCENT

    override val state: StateFlow<State> = mutableState.asStateFlow()

    /** The F33 locked-screen self-test, for the settings screen. */
    val selfTest: StateFlow<SelfTestStatus> = mutableSelfTest.asStateFlow()

    /** True while a self-test runs, which forces the headless camera path (the phone is meant to be locked). */
    val selfTestActive: Boolean
        get() = mutableSelfTest.value.let { it is SelfTestStatus.Countdown || it is SelfTestStatus.Running }
    val bell: StateFlow<BellStatus> = mutableBell.asStateFlow()

    /** The latest recording problem for the user (storage guard, camera), or null. Cleared when a recording starts. */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    @Synchronized
    fun updateSettings(new: CaptureSettings) {
        val restart = new.bellAutomation != settings.bellAutomation || new.bellSensitivity != settings.bellSensitivity
        settings = new
        if (restart) {
            stopListening()
            syncListener()
        }
    }

    /** Starts the self-test: lock the phone during the countdown. Ignored while one is running or a session is. */
    @Synchronized
    fun runSelfTest() {
        if (selfTestActive || machine.state != State.Setup) return
        mutableSelfTest.value = SelfTestStatus.Countdown(ScreenLockSelfTestRunner.LOCK_COUNTDOWN_S)
        val host = object : SelfTestHost {
            override val state: StateFlow<State> = this@SessionController.state
            override val notice: StateFlow<String?> = this@SessionController.notice

            override fun dispatch(event: Event, trigger: String) {
                this@SessionController.dispatch(event, trigger)
            }

            override fun framePtsUs(): Long? = sink.latestFramePtsUs()

            override fun screenOn(): Boolean = this@SessionController.screenOn()
        }
        selfTestJob = scope.launch {
            try {
                val verdict = ScreenLockSelfTestRunner(host, { mutableSelfTest.value = it }).run()
                onSelfTestVerdict(verdict)
            } catch (e: CancellationException) {
                mutableSelfTest.value = SelfTestStatus.Idle
                throw e
            }
        }
    }

    fun standby() = dispatch(Event.StandbyRequested, "user")

    fun bellNow() = dispatch(Event.BellNow, "user")

    fun recordNow() = dispatch(Event.RecordNow, "user")

    fun stop() = dispatch(Event.Stop, "user")

    @Synchronized
    override fun dispatch(event: Event, trigger: String) {
        val before = machine.state
        val effects = machine.dispatch(event, nowMs())
        afterChange(before, effects, "$trigger:${event::class.simpleName}")
    }

    @Synchronized
    private fun tick() {
        val before = machine.state
        val effects = machine.advanceTo(nowMs())
        afterChange(before, effects, "timer")
    }

    /** Beeps (F15) and recording (F17) are played here; brightness is F13. */
    private fun afterChange(before: State, effects: List<Effect>, trigger: String) {
        effects.mapNotNull { it.cueOrNull() }.filter(settings::allows).forEach(beeps::play)
        val after = machine.state
        if (after != before) log.transition(before, after, trigger)
        var refused = false
        effects.forEach { effect ->
            when (effect) {
                Effect.StartRecording -> refused = !startRecording() || refused
                Effect.StopRecording -> stopRecording()
                else -> Unit
            }
        }
        trackFlag()
        // Sync the listener first, so anyone who sees the new state also sees the listener status that goes with it.
        syncListener()
        mutableState.value = machine.state
        timer?.cancel()
        timer = machine.nextDeadlineMs?.let { deadline ->
            scope.launch {
                delay(maxOf(0L, deadline - nowMs()))
                tick()
            }
        }
        // A refused start leaves ACQUISITION through the normal Stop path, so state and recorder never disagree.
        if (refused) dispatch(Event.Stop, "recorder")
    }

    private class ActiveRecording(
        val id: String,
        val name: String,
        val settings: CaptureSettings,
        val firstFramePtsUs: Long,
        val flags: FlagIntervalTracker,
        val crossings: MutableList<SidecarCrossing> = ArrayList(),
    )

    /**
     * One camera frame (unrotated sensor luma, PTS = the camera timestamp) from the analysis stream. Runs the
     * crossing detector while the camera is needed; in ACQUISITION each crossing beeps and is kept for the sidecar.
     */
    @Synchronized
    fun onFrame(frame: Frame) {
        val line = settings.finishLine
        if (!cameraNeeded(machine.state) || line == null) {
            crossings = null
            return
        }
        val detector = crossings?.takeIf { crossingLine == line }
            ?: SlitScanCrossingDetector(line).also {
                crossings = it
                crossingLine = line
            }
        val found = detector.accept(frame)
        if (innerState() !is State.Acquisition) return
        found.forEach { c ->
            active?.let { it.crossings += c.toSidecar(it.firstFramePtsUs, it.settings.fps.value, CrossingSource.LIVE) }
            dispatch(Event.CrossingDetected, "crossing")
        }
    }

    private fun flagVisible(): Boolean? =
        (innerState() as? State.Acquisition)?.flagVisible

    private fun innerState() = machine.state.let { if (it is State.Review) it.underlying else it }

    /** Returns false when the recording was refused; the reason is in [notice]. */
    private fun startRecording(): Boolean {
        mutableNotice.value = null
        val check = StorageGuard.check(sink.freeBytes(), settings.resolution, settings.fps)
        if (!check.ok) {
            mutableNotice.value = check.message
            return false
        }
        val id = UUID.randomUUID().toString()
        return try {
            sink.start(id)
            val firstPts = sink.latestFramePtsUs() ?: 0L
            val name = AcquisitionNaming.nextName(eventLabel(), sink.existingNames())
            active = ActiveRecording(
                id, name, settings, firstPts, FlagIntervalTracker(firstPts, flagVisible() ?: false),
            )
            true
        } catch (e: RecordingUnavailableException) {
            mutableNotice.value = e.message ?: "Recording could not start."
            false
        }
    }

    private fun stopRecording() {
        val rec = active ?: return
        active = null
        val endPts = sink.latestFramePtsUs() ?: rec.firstFramePtsUs
        val sidecar = Sidecar(
            id = rec.id,
            name = rec.name,
            videoFile = "acq_${rec.id}.mp4",
            device = device,
            fps = rec.settings.fps.value,
            width = rec.settings.resolution.width,
            height = rec.settings.resolution.height,
            line = rec.settings.finishLine,
            firstFramePtsUs = rec.firstFramePtsUs,
            crossings = rec.crossings.toList(),
            flagIntervals = rec.flags.finish(endPts),
            appVersion = appVersion,
        )
        try {
            sink.stop(rec.id, sidecar.encode())
        } catch (e: RecordingUnavailableException) {
            mutableNotice.value = e.message ?: "Recording could not be saved."
        }
    }

    private fun trackFlag() {
        val rec = active ?: return
        flagVisible()?.let { rec.flags.setVisible(it, sink.latestFramePtsUs() ?: rec.firstFramePtsUs) }
    }

    private fun inStandby() = innerState() == State.Standby

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

/** Whether the user's settings switch this cue on. */
fun CaptureSettings.allows(cue: Cue): Boolean = when (cue) {
    Cue.ARMED_BEEPS -> armedBeeps
    Cue.CROSSING_BEEP -> crossingBeeps
    Cue.STAND_DOWN_CHIME -> standDownChime
}

private const val MAX_VOLUME_PERCENT = 100

/** The camera is bound from ARMING on, so it is ready when the flag is seen or "Record now" is pressed (F17). */
fun cameraNeeded(state: State): Boolean =
    (if (state is State.Review) state.underlying else state).let {
        it == State.Arming || it == State.Armed || it is State.Acquisition
    }
