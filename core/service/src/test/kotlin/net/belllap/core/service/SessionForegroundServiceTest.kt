package net.belllap.core.service

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.SessionMachine
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
import org.robolectric.shadows.ShadowPowerManager

/** A session host over the real state machine, with a clock the test advances by hand. */
class FakeHost : SessionHost {
    var nowMs = 0L
    private val machine = SessionMachine(nowMs = 0)
    private val mutable = MutableStateFlow<State>(machine.state)
    override val state: StateFlow<State> = mutable

    override fun dispatch(event: Event, trigger: String) {
        machine.dispatch(event, nowMs)
        mutable.value = machine.state
    }

    fun advance(ms: Long) {
        nowMs += ms
        machine.advanceTo(nowMs)
        mutable.value = machine.state
    }
}

class TestApp : Application(), SessionHostProvider {
    override val sessionHost = FakeHost()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class SessionForegroundServiceTest {
    private val app: TestApp = ApplicationProvider.getApplicationContext()
    private val host get() = app.sessionHost

    private fun send(
        c: ServiceController<SessionForegroundService>?,
        action: String,
    ): ServiceController<SessionForegroundService> {
        val intent = Intent(app, SessionForegroundService::class.java).setAction(action)
        val controller = c ?: Robolectric.buildService(SessionForegroundService::class.java, intent).create()
        controller.get().onStartCommand(intent, 0, 1)
        return controller
    }

    private fun wakeLockHeld(): Boolean = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    @Test
    fun startsForegroundWithMicrophoneAndCameraTypes() {
        val c = send(null, SessionForegroundService.ACTION_STANDBY)

        assertNotNull(shadowOf(c.get()).lastForegroundNotification)
        val declared = app.packageManager
            .getServiceInfo(ComponentName(app, SessionForegroundService::class.java), 0)
            .foregroundServiceType
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            declared,
        )
        assertEquals(State.Standby, host.state.value)
        assertEquals(State.Standby, SessionForegroundService.state.value)
    }

    @Test
    fun notificationCarriesTheFourActions() {
        val c = send(null, SessionForegroundService.ACTION_STANDBY)

        val titles = shadowOf(c.get()).lastForegroundNotification.actions.map { it.title.toString() }
        assertEquals(listOf("Bell now", "Record now", "Stop", "Exit"), titles)
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
    }

    @Test
    fun wakeLockHeldOnlyWhenArmed() {
        val c = send(null, SessionForegroundService.ACTION_STANDBY)
        assertFalse(wakeLockHeld())

        send(c, SessionForegroundService.ACTION_BELL_NOW)
        assertEquals(State.Arming, host.state.value)
        assertFalse(wakeLockHeld())

        host.advance(10_000)
        assertEquals(State.Armed, host.state.value)
        assertTrue(wakeLockHeld())

        send(c, SessionForegroundService.ACTION_STOP)
        assertEquals(State.Setup, host.state.value)
        assertFalse(wakeLockHeld())
    }

    @Test
    fun recordNowFromTheNotificationStartsAcquisition() {
        val c = send(null, SessionForegroundService.ACTION_STANDBY)
        send(c, SessionForegroundService.ACTION_BELL_NOW)
        host.advance(10_000)

        send(c, SessionForegroundService.ACTION_RECORD_NOW)
        assertEquals(State.Acquisition(flagVisible = false), host.state.value)
        assertTrue(wakeLockHeld())
    }

    @Test
    fun exitStopsTheService() {
        val c = send(null, SessionForegroundService.ACTION_STANDBY)
        send(c, SessionForegroundService.ACTION_EXIT)

        assertTrue(shadowOf(c.get()).isStoppedBySelf)
        assertEquals(State.Setup, host.state.value)
        c.destroy()
        assertEquals(State.Setup, SessionForegroundService.state.value)
    }
}
