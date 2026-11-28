package net.belllap.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State

/**
 * Keeps the session alive with the mic (and camera) in the foreground. Start it from a visible activity only
 * (Android 14+ forbids starting microphone/camera services from the background), and only after the mic and
 * camera permissions are granted.
 *
 * The service owns the session while it runs: it hosts the app session ([SessionHost]), mirrors its state into
 * [state], and drives the notification and the wake lock from it.
 */
class SessionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: SessionHost? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val h = (application as? SessionHostProvider)?.sessionHost ?: return
        host = h
        scope.launch {
            h.state.collect { s ->
                _state.value = s
                updateWakeLock(wakeLockRequired(s))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val h = host
        if (h == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        promoteToForeground(h.state.value)
        when (intent?.action) {
            ACTION_STANDBY -> h.dispatch(Event.StandbyRequested, "user")
            ACTION_BELL_NOW -> h.dispatch(Event.BellNow, "notification")
            ACTION_RECORD_NOW -> h.dispatch(Event.RecordNow, "notification")
            ACTION_STOP -> h.dispatch(Event.Stop, "notification")
            ACTION_EXIT -> {
                h.dispatch(Event.Stop, "notification")
                stopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        updateWakeLock(false)
        _state.value = State.Setup
        super.onDestroy()
    }

    private fun promoteToForeground(s: State) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(s), type)
    }

    // The lock spans state changes, so it is acquired and released in different calls; onDestroy always releases it.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun updateWakeLock(required: Boolean) {
        val lock = wakeLock
        val held = lock?.isHeld == true
        if (required && !held) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BellLap:session").also { it.acquire() }
        } else if (!required && held) {
            try {
                lock.release()
            } finally {
                wakeLock = null
            }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(s: State): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BellLap")
            .setContentText(label(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .addAction(0, "Bell now", actionIntent(ACTION_BELL_NOW))
            .addAction(0, "Record now", actionIntent(ACTION_RECORD_NOW))
            .addAction(0, "Stop", actionIntent(ACTION_STOP))
            .addAction(0, "Exit", actionIntent(ACTION_EXIT))
            .build()

    private fun launchIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, SessionForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_STANDBY = "net.belllap.action.STANDBY"
        const val ACTION_BELL_NOW = "net.belllap.action.BELL_NOW"
        const val ACTION_RECORD_NOW = "net.belllap.action.RECORD_NOW"
        const val ACTION_STOP = "net.belllap.action.STOP"
        const val ACTION_EXIT = "net.belllap.action.EXIT"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1

        private val _state = MutableStateFlow<State>(State.Setup)

        /** The session state, owned by the running service. [State.Setup] when the service is not running. */
        val state: StateFlow<State> = _state.asStateFlow()

        fun label(s: State): String = when (val inner = if (s is State.Review) s.underlying else s) {
            State.Setup -> "Setting up"
            State.Standby -> "Standby: listening for the bell"
            State.Arming -> "Bell heard: arming"
            State.Armed -> "Armed: waiting for the flag"
            is State.Acquisition -> if (inner.flagVisible) "Recording the finish" else "Recording: flag not visible"
            is State.Review -> "Review"
        }

        /** Starts the service and enters STANDBY. Call from a visible activity with mic and camera granted. */
        fun startStandby(context: Context) = send(context, ACTION_STANDBY)

        fun bellNow(context: Context) = send(context, ACTION_BELL_NOW)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionForegroundService::class.java).setAction(action),
            )
        }
    }
}
