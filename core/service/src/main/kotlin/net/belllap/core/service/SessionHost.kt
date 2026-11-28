package net.belllap.core.service

import kotlinx.coroutines.flow.StateFlow
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State

/** The session the foreground service keeps alive. The app's session controller implements it. */
interface SessionHost {
    val state: StateFlow<State>

    fun dispatch(event: Event, trigger: String)
}

/** Implemented by the `Application` so the service can find the [SessionHost] without depending on the app module. */
interface SessionHostProvider {
    val sessionHost: SessionHost
}

private fun innerOf(state: State): State = if (state is State.Review) state.underlying else state

/** Whether the session needs a partial wake lock. Only ARMED and ACQUISITION do; the review overlay changes nothing. */
fun wakeLockRequired(state: State): Boolean = innerOf(state).let { it == State.Armed || it is State.Acquisition }

/** Whether the UI shows the dimmed standby screen: black, screen kept on, brightness about 0.01. */
fun dimmedStandby(state: State): Boolean = state == State.Standby
