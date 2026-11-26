package net.belllap.core.statemachine

import net.belllap.core.statemachine.Effect.Log
import net.belllap.core.statemachine.Effect.PlayArmedBeeps
import net.belllap.core.statemachine.Effect.PlayCrossingBeep
import net.belllap.core.statemachine.Effect.PlayStandDownChime
import net.belllap.core.statemachine.Effect.RestoreBrightness
import net.belllap.core.statemachine.Effect.StartRecording
import net.belllap.core.statemachine.Effect.StopRecording

/**
 * Pure transition function. Invalid transitions return the same state plus a [Log] effect.
 *
 * Stop: during ACQUISITION it ends the recording and returns to ARMED; in STANDBY, ARMING or ARMED it
 * ends the session and returns to SETUP.
 * A manual RecordNow starts with the flag absent, so the absence timer runs until a flag is seen.
 */
fun reduce(state: State, event: Event): Transition =
    if (state is State.Review) reduceReview(state, event) else reduceSession(state, event)

private fun reduceReview(state: State.Review, event: Event): Transition =
    when (event) {
        Event.ExitReview -> Transition(state.underlying)
        Event.EnterReview -> ignored(state, event)
        else -> {
            val inner = reduceSession(state.underlying, event)
            Transition(State.Review(inner.state), inner.effects)
        }
    }

private fun reduceSession(state: State, event: Event): Transition =
    when (state) {
        State.Setup -> setup(state, event)
        State.Standby -> standby(state, event)
        State.Arming -> arming(state, event)
        State.Armed -> armed(state, event)
        is State.Acquisition -> acquisition(state, event)
        is State.Review -> ignored(state, event)
    }

private fun setup(s: State, e: Event) =
    when (e) {
        Event.StandbyRequested -> Transition(State.Standby)
        Event.EnterReview -> Transition(State.Review(s))
        else -> ignored(s, e)
    }

private fun standby(s: State, e: Event) =
    when (e) {
        Event.BellDetected, Event.BellNow -> Transition(State.Arming, listOf(RestoreBrightness))
        Event.Stop -> Transition(State.Setup, listOf(RestoreBrightness))
        Event.EnterReview -> Transition(State.Review(s))
        else -> ignored(s, e)
    }

private fun arming(s: State, e: Event) =
    when (e) {
        Event.ArmDelayElapsed -> Transition(State.Armed, listOf(PlayArmedBeeps))
        Event.Stop -> Transition(State.Setup)
        else -> ignored(s, e)
    }

private fun armed(s: State, e: Event) =
    when (e) {
        Event.FlagSeen -> Transition(State.Acquisition(flagVisible = true), listOf(StartRecording))
        Event.RecordNow -> Transition(State.Acquisition(flagVisible = false), listOf(StartRecording))
        Event.Stop -> Transition(State.Setup)
        Event.EnterReview -> Transition(State.Review(s))
        else -> ignored(s, e)
    }

private fun acquisition(s: State.Acquisition, e: Event) =
    when (e) {
        Event.FlagSeen -> Transition(State.Acquisition(flagVisible = true))
        Event.FlagLost -> Transition(State.Acquisition(flagVisible = false))
        Event.CrossingDetected -> Transition(s, listOf(PlayCrossingBeep))
        Event.FlagAbsentTimeout ->
            if (s.flagVisible) {
                ignored(s, e)
            } else {
                stopRecording()
            }
        Event.Stop -> stopRecording()
        else -> ignored(s, e)
    }

private fun stopRecording() = Transition(State.Armed, listOf(StopRecording, PlayStandDownChime))

private fun ignored(s: State, e: Event) = Transition(s, listOf(Log("Ignored $e in $s")))
