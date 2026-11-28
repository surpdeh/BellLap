package net.belllap.core.service

import net.belllap.core.statemachine.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    @Test
    fun wakeLockOnlyInArmedAndAcquisition() {
        assertFalse(wakeLockRequired(State.Setup))
        assertFalse(wakeLockRequired(State.Standby))
        assertFalse(wakeLockRequired(State.Arming))
        assertTrue(wakeLockRequired(State.Armed))
        assertTrue(wakeLockRequired(State.Acquisition(flagVisible = true)))
        assertTrue(wakeLockRequired(State.Acquisition(flagVisible = false)))
        assertTrue(wakeLockRequired(State.Review(State.Armed)))
        assertFalse(wakeLockRequired(State.Review(State.Standby)))
    }

    @Test
    fun dimmedOnlyInStandby() {
        assertTrue(dimmedStandby(State.Standby))
        assertFalse(dimmedStandby(State.Arming))
        assertFalse(dimmedStandby(State.Armed))
        assertFalse(dimmedStandby(State.Setup))
    }
}
