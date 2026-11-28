package net.belllap.session

import net.belllap.core.model.VolumeKey
import net.belllap.core.model.VolumeKeyAction
import net.belllap.core.statemachine.Event
import net.belllap.core.statemachine.State

/**
 * A hardware volume key event (F38). Returns true when the key is mapped and the session is past SETUP, so the
 * activity should swallow it and the system volume stays put; otherwise the key keeps its system behaviour.
 * Only a fresh key-down ([down] and not [repeat]) acts: ARM works as "bell now" from STANDBY, and RECORD starts
 * recording from ARMED. The state machine ignores (and logs) the event in any other mode.
 */
fun SessionController.volumeKey(key: VolumeKey, down: Boolean = true, repeat: Boolean = false): Boolean {
    val action = settings.actionFor(key)
    if (action == VolumeKeyAction.OFF || state.value == State.Setup) return false
    if (down && !repeat) {
        val event = if (action == VolumeKeyAction.ARM) Event.BellNow else Event.RecordNow
        dispatch(event, "volume_${key.name.lowercase()}")
    }
    return true
}
