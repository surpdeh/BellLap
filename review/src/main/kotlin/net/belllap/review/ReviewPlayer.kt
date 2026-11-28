package net.belllap.review

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import java.io.File

/**
 * Media3 ExoPlayer for one acquisition MP4 (F19). It seeks exactly ([SeekParameters.EXACT]) and opens paused. It owns
 * no camera, audio capture or service, so it runs beside an ARMED session; call [release] when done.
 */
@OptIn(UnstableApi::class) // androidx.annotation.OptIn: the lint marker for Media3's unstable API
class ReviewPlayer(context: Context, video: File) {
    val exo: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setSeekParameters(SeekParameters.EXACT)
        setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
        playWhenReady = false
        prepare()
    }

    /** Position in us; set at once by [seekToUs], so it never lags a seek that is still in flight. */
    var positionUs by mutableLongStateOf(0L)
        private set

    /** Duration in us, or [C.TIME_UNSET] until the player knows it. */
    var durationUs by mutableLongStateOf(C.TIME_UNSET)
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                durationUs = exo.duration.takeIf {
                    it != C.TIME_UNSET
                }?.let { it * US_PER_MS } ?: C.TIME_UNSET
            }
        }
    }

    init {
        exo.addListener(listener)
    }

    fun seekToUs(us: Long) {
        positionUs = us
        // ExoPlayer seeks in whole ms; the review clock aims at the middle of a frame, so the rounding stays inside it.
        exo.seekTo((us + US_PER_MS / 2) / US_PER_MS)
    }

    fun pause() = exo.pause()

    fun release() {
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val US_PER_MS = 1_000L
    }
}
