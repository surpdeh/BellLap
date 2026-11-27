package net.belllap.core.service

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import net.belllap.core.statemachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionForegroundServiceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun start(action: String): ServiceController<SessionForegroundService> =
        Robolectric.buildService(
            SessionForegroundService::class.java,
            Intent(context, SessionForegroundService::class.java).setAction(action),
        ).create().startCommand(0, 1)

    private fun send(c: ServiceController<SessionForegroundService>, action: String) {
        c.get().onStartCommand(Intent(context, SessionForegroundService::class.java).setAction(action), 0, 2)
    }

    private fun wakeLockHeld(): Boolean =
        org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    @Test
    fun startsForegroundWithMicrophoneAndCameraTypes() {
        val c = start(SessionForegroundService.ACTION_STANDBY)

        val shadow = shadowOf(c.get())
        assertNotNull(shadow.lastForegroundNotification)
        val declared = context.packageManager
            .getServiceInfo(ComponentName(context, SessionForegroundService::class.java), 0)
            .foregroundServiceType
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            declared,
        )
        assertEquals(State.Standby, SessionForegroundService.state.value)
    }

    @Test
    fun notificationCarriesTheFourActions() {
        val c = start(SessionForegroundService.ACTION_STANDBY)

        val titles = shadowOf(c.get()).lastForegroundNotification.actions.map { it.title.toString() }
        assertEquals(listOf("Bell now", "Record now", "Stop", "Exit"), titles)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
    }

    @Test
    fun wakeLockHeldOnlyWhenArmed() {
        val c = start(SessionForegroundService.ACTION_STANDBY)
        assertFalse(wakeLockHeld())

        send(c, SessionForegroundService.ACTION_BELL_NOW)
        assertEquals(State.Arming, SessionForegroundService.state.value)
        assertFalse(wakeLockHeld())

        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(State.Armed, SessionForegroundService.state.value)
        assertTrue(wakeLockHeld())

        send(c, SessionForegroundService.ACTION_STOP)
        assertEquals(State.Setup, SessionForegroundService.state.value)
        assertFalse(wakeLockHeld())
    }

    @Test
    fun exitStopsTheService() {
        val c = start(SessionForegroundService.ACTION_STANDBY)
        send(c, SessionForegroundService.ACTION_EXIT)

        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        c.destroy()
        assertEquals(State.Setup, SessionForegroundService.state.value)
    }
}
