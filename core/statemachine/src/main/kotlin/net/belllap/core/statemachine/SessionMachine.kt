package net.belllap.core.statemachine

/**
 * Stateful wrapper around [reduce] that owns the two timeouts. Time is supplied by the caller
 * (frame PTS or a virtual clock in tests), never read from the wall clock.
 */
class SessionMachine(
    private val config: SessionConfig = SessionConfig(),
    initial: State = State.Setup,
    nowMs: Long = 0,
) {
    var state: State = initial
        private set

    private var timerKey: Any? = null
    private var deadlineMs: Long? = null

    init {
        retime(nowMs)
    }

    /** Next time at which [advanceTo] would fire a timeout, or null. */
    val nextDeadlineMs: Long? get() = deadlineMs

    /** Applies [event] at [nowMs], first firing any timeout due by then. Returns all effects in order. */
    fun dispatch(event: Event, nowMs: Long): List<Effect> = advanceTo(nowMs) + apply(event, nowMs)

    /** Fires any timeout whose deadline is <= [nowMs]. */
    fun advanceTo(nowMs: Long): List<Effect> {
        val effects = mutableListOf<Effect>()
        while (true) {
            val due = deadlineMs?.takeIf { it <= nowMs } ?: break
            val event = if (innerOf(state) == State.Arming) Event.ArmDelayElapsed else Event.FlagAbsentTimeout
            effects += apply(event, due)
        }
        return effects
    }

    private fun apply(event: Event, at: Long): List<Effect> {
        val t = reduce(state, event)
        state = t.state
        retime(at)
        return t.effects
    }

    private fun retime(at: Long) {
        val inner = innerOf(state)
        val key: Any? =
            when {
                inner == State.Arming -> inner
                inner is State.Acquisition && !inner.flagVisible -> inner
                else -> null
            }
        if (key == timerKey) return
        timerKey = key
        deadlineMs =
            when (key) {
                State.Arming -> at + config.armDelayMs
                null -> null
                else -> at + config.flagAbsentTimeoutMs
            }
    }

    private fun innerOf(s: State): State = if (s is State.Review) s.underlying else s
}
