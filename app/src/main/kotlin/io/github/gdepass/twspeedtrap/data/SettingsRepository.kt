package io.github.gdepass.twspeedtrap.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.detection.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val distanceMultiplier: Double = 12.0,
    val speedToleranceKmh: Int = 10,
    val chimeEnabled: Boolean = true,
    val enabledTypes: Set<CameraType> = CameraType.entries.toSet(),
    /** BCP-47 tag ("fr", "en") or [io.github.gdepass.twspeedtrap.util.LocaleOverride.SYSTEM]. */
    val languageTag: String = "system",
    val autoUpdateEnabled: Boolean = true,
    val wifiOnlyUpdates: Boolean = true,
    /** Stop detection after 10 min without movement. Off by default: a long
     * break must not silently disable alerts for the ride home. */
    val autoStopEnabled: Boolean = false,
    /** Start detection when a Bluetooth device connects (helmet intercom). */
    val autoStartBluetoothEnabled: Boolean = false,
) {
    fun toEngineConfig(): EngineConfig =
        EngineConfig(
            distanceMultiplier = distanceMultiplier,
            speedToleranceKmh = speedToleranceKmh.toDouble(),
            enabledTypes = enabledTypes,
        )
}

class SettingsRepository(
    private val context: Context,
) {
    val settings: Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                distanceMultiplier = prefs[KEY_DISTANCE_MULTIPLIER] ?: DEFAULTS.distanceMultiplier,
                speedToleranceKmh = prefs[KEY_SPEED_TOLERANCE] ?: DEFAULTS.speedToleranceKmh,
                chimeEnabled = prefs[KEY_CHIME] ?: DEFAULTS.chimeEnabled,
                enabledTypes =
                    prefs[KEY_ENABLED_TYPES]
                        ?.mapNotNull { name -> CameraType.entries.find { it.name == name } }
                        ?.toSet()
                        ?: DEFAULTS.enabledTypes,
                languageTag = prefs[KEY_LANGUAGE] ?: DEFAULTS.languageTag,
                autoUpdateEnabled = prefs[KEY_AUTO_UPDATE] ?: DEFAULTS.autoUpdateEnabled,
                wifiOnlyUpdates = prefs[KEY_WIFI_ONLY] ?: DEFAULTS.wifiOnlyUpdates,
                autoStopEnabled = prefs[KEY_AUTO_STOP] ?: DEFAULTS.autoStopEnabled,
                autoStartBluetoothEnabled = prefs[KEY_AUTO_START_BT] ?: DEFAULTS.autoStartBluetoothEnabled,
            )
        }

    suspend fun setDistanceMultiplier(value: Double) = context.dataStore.edit { it[KEY_DISTANCE_MULTIPLIER] = value }

    suspend fun setSpeedTolerance(value: Int) = context.dataStore.edit { it[KEY_SPEED_TOLERANCE] = value }

    suspend fun setChimeEnabled(value: Boolean) = context.dataStore.edit { it[KEY_CHIME] = value }

    suspend fun setEnabledTypes(types: Set<CameraType>) =
        context.dataStore.edit { prefs -> prefs[KEY_ENABLED_TYPES] = types.map { it.name }.toSet() }

    suspend fun setLanguageTag(tag: String) = context.dataStore.edit { it[KEY_LANGUAGE] = tag }

    suspend fun setAutoUpdateEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_UPDATE] = value }

    suspend fun setWifiOnlyUpdates(value: Boolean) = context.dataStore.edit { it[KEY_WIFI_ONLY] = value }

    suspend fun setAutoStopEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_STOP] = value }

    suspend fun setAutoStartBluetoothEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_START_BT] = value }

    companion object {
        private val DEFAULTS = AppSettings()
        private val KEY_DISTANCE_MULTIPLIER = doublePreferencesKey("distance_multiplier")
        private val KEY_SPEED_TOLERANCE = intPreferencesKey("speed_tolerance_kmh")
        private val KEY_CHIME = booleanPreferencesKey("chime_enabled")
        private val KEY_ENABLED_TYPES = stringSetPreferencesKey("enabled_types")
        private val KEY_LANGUAGE = stringPreferencesKey("language_tag")
        private val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_updates")
        private val KEY_AUTO_STOP = booleanPreferencesKey("auto_stop_stationary")
        private val KEY_AUTO_START_BT = booleanPreferencesKey("auto_start_bluetooth")
    }
}
