package net.belllap

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.belllap.audio.android.AudioInput
import net.belllap.audio.android.AudioRecordPcmRecorder
import net.belllap.audio.android.YamnetBellClassifier
import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.source.MicAudioSource
import net.belllap.capture.CameraXAcquisitionSink
import net.belllap.capture.DeviceProbe
import net.belllap.core.log.AndroidInfo
import net.belllap.core.log.EventLog
import net.belllap.core.log.RollingFileTree
import net.belllap.core.model.DeviceCapabilities
import net.belllap.core.service.AlarmBeepPlayer
import net.belllap.core.model.SidecarDevice
import net.belllap.core.service.SessionHost
import net.belllap.core.service.SessionHostProvider
import net.belllap.session.EventLogSessionLog
import net.belllap.session.SessionController
import timber.log.Timber
import java.io.File
import java.time.LocalDate

class BellLapApp : Application(), SessionHostProvider {
    lateinit var eventLog: EventLog
        private set

    private val capabilities = MutableStateFlow<DeviceCapabilities?>(null)

    /** What this device can do (F25), or null until the first probe finishes. Settings gate their options on it. */
    val deviceCapabilities: StateFlow<DeviceCapabilities?> = capabilities

    /** Stage 1 of the bell cascade, loaded once (it maps a 4 MB model). Shared by the session and Replay. */
    val bellClassifier: BellClassifier by lazy { YamnetBellClassifier.loadOrFallback(this) }

    /** The session lives as long as the process; the foreground service keeps the process alive. */
    val session: SessionController by lazy {
        SessionController(
            scope = MainScope(),
            nowMs = SystemClock::elapsedRealtime,
            openAudio = {
                val source = AudioInput.choose(AudioInput.unprocessedSupported(this))
                MicAudioSource(AudioRecordPcmRecorder(source))
            },
            classifier = { bellClassifier },
            log = EventLogSessionLog(eventLog),
            beeps = AlarmBeepPlayer(this) { session.alertVolumePercent },
            sink = CameraXAcquisitionSink(this, File(filesDir, "acquisitions")),
            device = SidecarDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT),
            appVersion = BuildConfig.VERSION_NAME,
            eventLabel = { LocalDate.now().toString() },
            audioDispatcher = Dispatchers.IO,
        )
    }

    override val sessionHost: SessionHost get() = session

    override fun onCreate() {
        super.onCreate()
        val logsDir = File(filesDir, "logs")
        Timber.plant(RollingFileTree(logsDir))
        eventLog = EventLog(logsDir)
        eventLog.log(AndroidInfo.device(BuildConfig.VERSION_NAME))
        AndroidInfo.battery(this)?.let(eventLog::log)
        val prefs = getSharedPreferences("eventlog", MODE_PRIVATE)
        val newest = AndroidInfo.recordExitInfo(this, eventLog, prefs.getLong(KEY_LAST_EXIT, 0L))
        prefs.edit { putLong(KEY_LAST_EXIT, newest) }
        // The JSON sits beside the event logs, so a bug report bundling the logs directory includes it.
        MainScope().launch(Dispatchers.IO) {
            runCatching { DeviceProbe.writeJson(this@BellLapApp, logsDir) }
                .onSuccess { capabilities.value = it }
                .onFailure { Timber.w(it, "Device capability probe failed") }
        }
    }

    private companion object {
        const val KEY_LAST_EXIT = "lastExitMs"
    }
}
