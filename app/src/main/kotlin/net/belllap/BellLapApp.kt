package net.belllap

import android.app.Application
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import net.belllap.audio.android.AudioInput
import net.belllap.audio.android.AudioRecordPcmRecorder
import net.belllap.audio.android.YamnetBellClassifier
import net.belllap.audio.bell.BellClassifier
import net.belllap.audio.source.MicAudioSource
import net.belllap.core.log.AndroidInfo
import net.belllap.core.log.EventLog
import net.belllap.core.log.RollingFileTree
import net.belllap.session.EventLogSessionLog
import net.belllap.session.SessionController
import timber.log.Timber
import java.io.File

class BellLapApp : Application() {
    lateinit var eventLog: EventLog
        private set

    private val classifier: BellClassifier by lazy { YamnetBellClassifier.loadOrFallback(this) }

    /** The session lives as long as the process for now; F13 moves it into the foreground service. */
    val session: SessionController by lazy {
        SessionController(
            scope = MainScope(),
            nowMs = SystemClock::elapsedRealtime,
            openAudio = {
                val source = AudioInput.choose(AudioInput.unprocessedSupported(this))
                MicAudioSource(AudioRecordPcmRecorder(source))
            },
            classifier = { classifier },
            log = EventLogSessionLog(eventLog),
            audioDispatcher = Dispatchers.IO,
        )
    }

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
    }

    private companion object {
        const val KEY_LAST_EXIT = "lastExitMs"
    }
}
