package net.belllap.session

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.belllap.capture.CameraUnavailableException
import net.belllap.capture.PreviewSession
import net.belllap.core.model.CaptureSettings
import timber.log.Timber

/**
 * The camera for a locked phone (F33). The activity binds the camera while it is visible; when it is not (screen
 * off) and the session needs the camera, this binds the same use cases to a lifecycle the app owns, on a discarded
 * surface. The foreground service keeps the process alive, and its camera type lets the camera open from the
 * background. It stands down as soon as the activity is visible again, and the activity rebinds.
 *
 * Runs only while the screen-lock option is active on this device or a self-test is running, so a phone that never
 * passed the test keeps the dimmed-standby behaviour.
 */
class HeadlessCamera(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: SessionController,
    private val activityVisible: StateFlow<Boolean>,
    private val deviceId: String,
) {
    private var owner: HeadlessOwner? = null
    private var binding: Job? = null

    fun start() {
        scope.launch {
            combine(session.state, activityVisible, session.selfTest) { state, visible, _ ->
                !visible && cameraNeeded(state) && (session.selfTestActive || session.settings.lockedOk())
            }.collect { wanted -> if (wanted) bind() else release() }
        }
    }

    private fun CaptureSettings.lockedOk() = screenLockActive(deviceId)

    private fun bind() {
        if (owner != null) return
        val o = HeadlessOwner().also { owner = it }
        binding = scope.launch {
            repeat(BIND_ATTEMPTS) { attempt ->
                try {
                    PreviewSession.bind(context, o, session.settings) { PreviewSession.discardSurface(context, it) }
                    Timber.i("Headless camera bound")
                    return@launch
                } catch (e: CameraUnavailableException) {
                    Timber.w(e, "Headless camera bind failed (attempt %d)", attempt + 1)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun release() {
        binding?.cancel()
        binding = null
        owner?.destroy()
        owner = null
    }

    private class HeadlessOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val BIND_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
    }
}
