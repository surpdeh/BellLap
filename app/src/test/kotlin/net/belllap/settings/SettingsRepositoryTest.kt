package net.belllap.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.belllap.core.model.BellSensitivity
import net.belllap.core.model.CameraFacing
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.FinishLine
import net.belllap.core.model.Fps
import net.belllap.core.model.NormPoint
import net.belllap.core.model.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String = "s.preferences_pb") =
        PreferenceDataStoreFactory.create { File(tmp.root, name) }

    @Test
    fun defaultsWhenNothingStored() = runBlocking {
        assertEquals(CaptureSettings(), SettingsRepository(store()).settings.first())
    }

    @Test
    fun updatesPersistAcrossRepositoryInstances() = runBlocking {
        val changed = CaptureSettings(
            facing = CameraFacing.FRONT,
            resolution = Resolution.HD_720,
            fps = Fps.FPS_60,
            bellAutomation = false,
            bellSensitivity = BellSensitivity.HIGH,
            flagAutomation = false,
            crossingBeeps = false,
            flagTimeoutSeconds = 120,
            alertVolumePercent = 70,
            finishLine = FinishLine(NormPoint(0.4f, 0.1f), NormPoint(0.6f, 0.9f)),
        )
        val first = store("a.preferences_pb")
        SettingsRepository(first).update { changed }
        assertEquals(changed, SettingsRepository(first).settings.first())
        // Clearing the volume override removes the stored value.
        SettingsRepository(first).update { it.copy(alertVolumePercent = null) }
        assertEquals(null, SettingsRepository(first).settings.first().alertVolumePercent)
    }

    @Test
    fun corruptEnumFallsBackToDefault() = runBlocking {
        val s = store()
        s.edit { it[stringPreferencesKey("resolution")] = "BOGUS" }
        assertEquals(Resolution.FHD_1080, SettingsRepository(s).settings.first().resolution)
        s.edit { it[stringPreferencesKey("bell_sensitivity")] = "LOUDEST" }
        assertEquals(BellSensitivity.MEDIUM, SettingsRepository(s).settings.first().bellSensitivity)
    }
}
