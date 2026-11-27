package net.belllap.audio.android

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputTest {
    @Test
    fun prefersUnprocessedOnlyWhenTheDeviceSupportsIt() {
        assertEquals(MediaRecorder.AudioSource.UNPROCESSED, AudioInput.choose(unprocessedSupported = true))
        assertEquals(MediaRecorder.AudioSource.MIC, AudioInput.choose(unprocessedSupported = false))
        assertEquals("UNPROCESSED", AudioInput.name(AudioInput.choose(true)))
        assertEquals("MIC", AudioInput.name(AudioInput.choose(false)))
    }
}
