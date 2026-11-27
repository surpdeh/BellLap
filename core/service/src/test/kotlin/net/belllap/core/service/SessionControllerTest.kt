package net.belllap.core.service

import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    private var now = 0L
    private val controller = SessionController(nowMs = { now })

    @Test
    fun wakeLockOnlyInArmedAndAcquisition() {
        assertFalse(wakeLockRequired(State.Setup))
        assertFalse(wakeLockRequired(State.Standby))
        assertFalse(wakeLockRequired(State.Arming))
        assertTrue(wakeLockRequired(State.Armed))
        assertTrue(wakeLockRequired(State.Acquisition(flagVisible = true)))
        assertTrue(wakeLockRequired(State.Review(State.Armed)))
        assertFalse(wakeLockRequired(State.Review(State.Standby)))
    }

    @Test
    fun dimmedOnlyInStandby() {
        assertTrue(dimmedStandby(State.Standby))
        assertFalse(dimmedStandby(State.Arming))
        assertFalse(dimmedStandby(State.Setup))
    }

    @Test
    fun standbyThenBellNowUndimsAndArms() {
        controller.dispatch(Event.StandbyRequested)
        assertTrue(dimmedStandby(controller.state.value))
        controller.dispatch(Event.BellNow)
        assertEquals(State.Arming, controller.state.value)
        assertFalse(dimmedStandby(controller.state.value))
    }

    @Test
    fun armDelayElapsesOnTick() {
        controller.dispatch(Event.StandbyRequested)
        controller.dispatch(Event.BellDetected)
        now = 10_000
        controller.tick()
        assertEquals(State.Armed, controller.state.value)
    }

    @Test
    fun stopReturnsToSetup() {
        controller.dispatch(Event.StandbyRequested)
        controller.dispatch(Event.Stop)
        assertEquals(State.Setup, controller.state.value)
    }
}
