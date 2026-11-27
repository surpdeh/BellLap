package net.belllap.session

import net.belllap.audio.bell.BellTrace
import net.belllap.core.log.DetectorScore
import net.belllap.core.log.EventLog
import net.belllap.core.statemachine.State
import net.belllap.core.statemachine.TransitionTable
import timber.log.Timber

/** Writes session transitions and bell-cascade stages to the JSONL event log (F05). */
class EventLogSessionLog(private val log: EventLog) : SessionLog {
    override fun transition(from: State, to: State, trigger: String) {
        log.transition(TransitionTable.name(from), TransitionTable.name(to), trigger)
        Timber.i("Session %s -> %s (%s)", TransitionTable.name(from), TransitionTable.name(to), trigger)
    }

    override fun bell(trace: BellTrace) {
        when (trace) {
            is BellTrace.Candidate -> log.log(DetectorScore("bell.stage0", trace.candidate.prominenceDb, trace.ptsNs))
            is BellTrace.Scored -> log.detectorScore("bell.stage1", trace.score.toDouble(), trace.ptsNs)
            is BellTrace.Detected -> {
                val name = "bell.detected.${trace.reason.name.lowercase()}"
                log.log(DetectorScore(name, trace.hits.toDouble(), trace.ptsNs))
                Timber.i("Bell detected at %d ms (%s, %d hits)", trace.ptsNs / NS_PER_MS, trace.reason, trace.hits)
            }
        }
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
