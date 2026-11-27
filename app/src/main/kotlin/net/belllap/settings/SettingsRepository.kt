package net.belllap.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.belllap.core.model.CaptureSettings
import net.belllap.core.model.FinishLine

/** Persists [CaptureSettings] in DataStore. Unknown or invalid stored values fall back to defaults. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<CaptureSettings> = store.data.map(::decode)

    suspend fun update(transform: (CaptureSettings) -> CaptureSettings) {
        store.edit { prefs -> encode(transform(decode(prefs)), prefs) }
    }

    private fun decode(p: Preferences): CaptureSettings {
        val d = CaptureSettings()
        val timeout = p[FLAG_TIMEOUT]?.takeIf { it in CaptureSettings.FLAG_TIMEOUT_RANGE_S }
        val volume = p[ALERT_VOLUME]?.takeIf { it in 0..100 }
        return CaptureSettings(
            facing = enumOrDefault(p[FACING], d.facing),
            resolution = enumOrDefault(p[RESOLUTION], d.resolution),
            fps = enumOrDefault(p[FPS], d.fps),
            bellAutomation = p[BELL] ?: d.bellAutomation,
            bellSensitivity = enumOrDefault(p[BELL_SENSITIVITY], d.bellSensitivity),
            flagAutomation = p[FLAG] ?: d.flagAutomation,
            crossingBeeps = p[BEEPS] ?: d.crossingBeeps,
            flagTimeoutSeconds = timeout ?: d.flagTimeoutSeconds,
            alertVolumePercent = volume,
            finishLine = p[FINISH_LINE]?.let(FinishLine::fromSidecarJson),
        )
    }

    private fun encode(s: CaptureSettings, p: androidx.datastore.preferences.core.MutablePreferences) {
        p[FACING] = s.facing.name
        p[RESOLUTION] = s.resolution.name
        p[FPS] = s.fps.name
        p[BELL] = s.bellAutomation
        p[BELL_SENSITIVITY] = s.bellSensitivity.name
        p[FLAG] = s.flagAutomation
        p[BEEPS] = s.crossingBeeps
        p[FLAG_TIMEOUT] = s.flagTimeoutSeconds
        val v = s.alertVolumePercent
        if (v == null) p.remove(ALERT_VOLUME) else p[ALERT_VOLUME] = v
        val line = s.finishLine
        if (line == null) p.remove(FINISH_LINE) else p[FINISH_LINE] = line.toSidecarJson()
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val FACING = stringPreferencesKey("facing")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FPS = stringPreferencesKey("fps")
        val BELL = booleanPreferencesKey("bell_automation")
        val BELL_SENSITIVITY = stringPreferencesKey("bell_sensitivity")
        val FLAG = booleanPreferencesKey("flag_automation")
        val BEEPS = booleanPreferencesKey("crossing_beeps")
        val FLAG_TIMEOUT = intPreferencesKey("flag_timeout_s")
        val ALERT_VOLUME = intPreferencesKey("alert_volume_pct")
        val FINISH_LINE = stringPreferencesKey("finish_line")
    }
}
