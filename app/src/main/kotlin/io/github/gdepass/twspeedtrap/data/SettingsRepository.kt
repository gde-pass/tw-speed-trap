package io.github.gdepass.twspeedtrap.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    /** Alerts fire this many metres before the camera below 100 km/h. */
    val alertDistanceM: Int = 300,
    /** Alerts fire this many metres before the camera at 100 km/h and above. */
    val highSpeedAlertDistanceM: Int = 500,
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
    /** Chime once the alerted camera is behind and nothing else is ahead. */
    val allClearChimeEnabled: Boolean = false,
    /** Floating bubble over other apps: green = clear, red = camera countdown. */
    val overlayBubbleEnabled: Boolean = false,
    /** Last dragged bubble position in screen pixels; -1 = default placement. */
    val overlayX: Int = -1,
    val overlayY: Int = -1,
) {
    fun toEngineConfig(): EngineConfig =
        EngineConfig(
            alertDistanceM = alertDistanceM.toDouble(),
            highSpeedAlertDistanceM = highSpeedAlertDistanceM.toDouble(),
            speedToleranceKmh = speedToleranceKmh.toDouble(),
            enabledTypes = enabledTypes,
        )
}

// One public setter per persisted preference is the point of this class.
@Suppress("TooManyFunctions")
class SettingsRepository(
    private val context: Context,
) {
    val settings: Flow<AppSettings> =
        context.dataStore.data.map { prefs ->
            AppSettings(
                alertDistanceM = prefs[KEY_ALERT_DISTANCE] ?: DEFAULTS.alertDistanceM,
                highSpeedAlertDistanceM = prefs[KEY_ALERT_DISTANCE_HIGH] ?: DEFAULTS.highSpeedAlertDistanceM,
                speedToleranceKmh = prefs[KEY_SPEED_TOLERANCE] ?: DEFAULTS.speedToleranceKmh,
                chimeEnabled = prefs[KEY_CHIME] ?: DEFAULTS.chimeEnabled,
                enabledTypes =
                    prefs[KEY_ENABLED_TYPES]?.let { names ->
                        val parsed = names.mapNotNull { name -> CameraType.entries.find { it.name == name } }.toSet()
                        // A non-empty persisted set that maps to nothing is
                        // corrupt data, not a choice: fall back to defaults
                        // rather than silently disabling every alert.
                        if (parsed.isEmpty() && names.isNotEmpty()) DEFAULTS.enabledTypes else parsed
                    } ?: DEFAULTS.enabledTypes,
                languageTag = prefs[KEY_LANGUAGE] ?: DEFAULTS.languageTag,
                autoUpdateEnabled = prefs[KEY_AUTO_UPDATE] ?: DEFAULTS.autoUpdateEnabled,
                wifiOnlyUpdates = prefs[KEY_WIFI_ONLY] ?: DEFAULTS.wifiOnlyUpdates,
                autoStopEnabled = prefs[KEY_AUTO_STOP] ?: DEFAULTS.autoStopEnabled,
                autoStartBluetoothEnabled = prefs[KEY_AUTO_START_BT] ?: DEFAULTS.autoStartBluetoothEnabled,
                allClearChimeEnabled = prefs[KEY_ALL_CLEAR_CHIME] ?: DEFAULTS.allClearChimeEnabled,
                overlayBubbleEnabled = prefs[KEY_OVERLAY_BUBBLE] ?: DEFAULTS.overlayBubbleEnabled,
                overlayX = prefs[KEY_OVERLAY_X] ?: DEFAULTS.overlayX,
                overlayY = prefs[KEY_OVERLAY_Y] ?: DEFAULTS.overlayY,
            )
        }

    suspend fun setAlertDistance(value: Int) = context.dataStore.edit { it[KEY_ALERT_DISTANCE] = value }

    suspend fun setHighSpeedAlertDistance(value: Int) = context.dataStore.edit { it[KEY_ALERT_DISTANCE_HIGH] = value }

    suspend fun setSpeedTolerance(value: Int) = context.dataStore.edit { it[KEY_SPEED_TOLERANCE] = value }

    suspend fun setChimeEnabled(value: Boolean) = context.dataStore.edit { it[KEY_CHIME] = value }

    suspend fun setEnabledTypes(types: Set<CameraType>) =
        context.dataStore.edit { prefs -> prefs[KEY_ENABLED_TYPES] = types.map { it.name }.toSet() }

    suspend fun setLanguageTag(tag: String) = context.dataStore.edit { it[KEY_LANGUAGE] = tag }

    suspend fun setAutoUpdateEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_UPDATE] = value }

    suspend fun setWifiOnlyUpdates(value: Boolean) = context.dataStore.edit { it[KEY_WIFI_ONLY] = value }

    suspend fun setAutoStopEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_STOP] = value }

    suspend fun setAutoStartBluetoothEnabled(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_START_BT] = value }

    suspend fun setAllClearChimeEnabled(value: Boolean) = context.dataStore.edit { it[KEY_ALL_CLEAR_CHIME] = value }

    suspend fun setOverlayBubbleEnabled(value: Boolean) = context.dataStore.edit { it[KEY_OVERLAY_BUBBLE] = value }

    suspend fun setOverlayPosition(
        x: Int,
        y: Int,
    ) = context.dataStore.edit {
        it[KEY_OVERLAY_X] = x
        it[KEY_OVERLAY_Y] = y
    }

    companion object {
        private val DEFAULTS = AppSettings()
        private val KEY_ALERT_DISTANCE = intPreferencesKey("alert_distance_m")
        private val KEY_ALERT_DISTANCE_HIGH = intPreferencesKey("alert_distance_high_m")
        private val KEY_SPEED_TOLERANCE = intPreferencesKey("speed_tolerance_kmh")
        private val KEY_CHIME = booleanPreferencesKey("chime_enabled")
        private val KEY_ENABLED_TYPES = stringSetPreferencesKey("enabled_types")
        private val KEY_LANGUAGE = stringPreferencesKey("language_tag")
        private val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val KEY_WIFI_ONLY = booleanPreferencesKey("wifi_only_updates")
        private val KEY_AUTO_STOP = booleanPreferencesKey("auto_stop_stationary")
        private val KEY_AUTO_START_BT = booleanPreferencesKey("auto_start_bluetooth")
        private val KEY_ALL_CLEAR_CHIME = booleanPreferencesKey("all_clear_chime")
        private val KEY_OVERLAY_BUBBLE = booleanPreferencesKey("overlay_bubble")
        private val KEY_OVERLAY_X = intPreferencesKey("overlay_x")
        private val KEY_OVERLAY_Y = intPreferencesKey("overlay_y")
    }
}
