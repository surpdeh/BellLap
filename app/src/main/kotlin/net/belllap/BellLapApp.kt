package net.belllap

import android.app.Application
import androidx.core.content.edit
import net.belllap.core.log.AndroidInfo
import net.belllap.core.log.EventLog
import net.belllap.core.log.RollingFileTree
import timber.log.Timber
import java.io.File

class BellLapApp : Application() {
    lateinit var eventLog: EventLog
        private set

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
