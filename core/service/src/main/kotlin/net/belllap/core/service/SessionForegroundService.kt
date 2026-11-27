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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * The service owns the session state; observe [state].
 */
class SessionForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: SessionController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c = SessionController(nowMs = SystemClock::elapsedRealtime)
        controller = c
        _state.value = c.state.value
        scope.launch {
            c.state.collect { s ->
                _state.value = s
                updateWakeLock(wakeLockRequired(s))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
            }
        }
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                c.tick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller ?: return START_NOT_STICKY
        ensureChannel()
        promoteToForeground(c.state.value)
        when (intent?.action) {
            ACTION_STANDBY -> c.dispatch(Event.StandbyRequested)
            ACTION_BELL_NOW -> c.dispatch(Event.BellNow)
            ACTION_RECORD_NOW -> c.dispatch(Event.RecordNow)
            ACTION_STOP -> c.dispatch(Event.Stop)
            ACTION_EXIT -> {
                c.dispatch(Event.Stop)
                stopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
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

    @SuppressLint("WakelockTimeout")
    private fun updateWakeLock(required: Boolean) {
        val held = wakeLock?.isHeld == true
        if (required && !held) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BellLap:session").apply { acquire() }
        } else if (!required && held) {
            wakeLock?.release()
            wakeLock = null
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
        private const val TICK_MS = 250L

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
