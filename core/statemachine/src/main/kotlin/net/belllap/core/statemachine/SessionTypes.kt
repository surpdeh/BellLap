package net.belllap.core.statemachine

/** Session modes from docs/PLAN.md §1.2. Pure data: no Android types. */
sealed interface State {
    data object Setup : State

    data object Standby : State

    data object Arming : State

    data object Armed : State

    /** [flagVisible] is false after FlagLost (or a manual start); the absence timer runs while false. */
    data class Acquisition(val flagVisible: Boolean) : State

    /**
     * Review overlay. The session underneath ([underlying]) keeps running, so a bell, flag or timer
     * event is still applied to it while the user reviews.
     */
    data class Review(val underlying: State) : State {
        init {
            require(underlying !is Review) { "Review cannot be nested" }
        }
    }
}

sealed interface Event {
    data object StandbyRequested : Event

    data object BellDetected : Event

    data object BellNow : Event

    data object ArmDelayElapsed : Event

    data object FlagSeen : Event

    data object FlagLost : Event

    data object RecordNow : Event

    data object Stop : Event

    data object FlagAbsentTimeout : Event

    data object CrossingDetected : Event

    data object EnterReview : Event

    data object ExitReview : Event
}

sealed interface Effect {
    data object PlayArmedBeeps : Effect

    data object PlayCrossingBeep : Effect

    data object PlayStandDownChime : Effect

    data object StartRecording : Effect

    data object StopRecording : Effect

    data object RestoreBrightness : Effect

    data class Log(val message: String) : Effect
}

data class Transition(val state: State, val effects: List<Effect> = emptyList())

data class SessionConfig(
    val armDelayMs: Long = 10_000,
    val flagAbsentTimeoutMs: Long = 15_000,
)
