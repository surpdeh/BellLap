package net.belllap.core.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.belllap.core.statemachine.Effect
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.SessionConfig
import net.belllap.core.statemachine.SessionMachine
import net.belllap.core.statemachine.State

/** Whether the session needs a partial wake lock. Only ARMED and ACQUISITION do (the review overlay does not change that). */
fun wakeLockRequired(state: State): Boolean {
    val inner = if (state is State.Review) state.underlying else state
    return inner == State.Armed || inner is State.Acquisition
}

/** Whether the UI shows the dimmed standby screen: black, screen kept on, brightness about 0.01. */
fun dimmedStandby(state: State): Boolean = state == State.Standby

/**
 * Pure holder of the session state. The service owns one and exposes [state] as the single source of truth for the UI.
 * Time is supplied by the caller (a monotonic clock until frame PTS is wired in), never the wall clock.
 */
class SessionController(config: SessionConfig = SessionConfig(), private val nowMs: () -> Long) {
    private val machine = SessionMachine(config, nowMs = nowMs())
    private val _state = MutableStateFlow(machine.state)
    private val _effects = MutableSharedFlow<Effect>(extraBufferCapacity = EFFECT_BUFFER)

    val state: StateFlow<State> = _state.asStateFlow()

    /** Effects (beeps, recording, brightness restore) for whoever owns the hardware. */
    val effects: SharedFlow<Effect> = _effects.asSharedFlow()

    fun dispatch(event: Event) {
        emit(machine.dispatch(event, nowMs()))
    }

    /** Fires due timeouts. Call periodically while the session runs. */
    fun tick() {
        emit(machine.advanceTo(nowMs()))
    }

    private fun emit(list: List<Effect>) {
        _state.value = machine.state
        list.forEach { _effects.tryEmit(it) }
    }

    private companion object {
        const val EFFECT_BUFFER = 64
    }
}
