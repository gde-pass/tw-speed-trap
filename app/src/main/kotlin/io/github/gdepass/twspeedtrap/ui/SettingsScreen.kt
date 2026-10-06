package io.github.gdepass.twspeedtrap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.AppSettings
import io.github.gdepass.twspeedtrap.data.CameraRepository
import io.github.gdepass.twspeedtrap.data.ManualUpdateCheck
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.data.UpdateResult
import io.github.gdepass.twspeedtrap.data.UpdateWorker
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.service.DetectionStatus
import io.github.gdepass.twspeedtrap.util.LocaleOverride
import io.github.gdepass.twspeedtrap.util.startActivitySafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val detectionState by DetectionStatus.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            }

            SectionTitle(stringResource(R.string.settings_language))
            LanguageOption(stringResource(R.string.language_system), LocaleOverride.SYSTEM, settings.languageTag) {
                scope.launch {
                    repository.setLanguageTag(it)
                    activity?.recreate()
                }
            }
            LanguageOption(stringResource(R.string.language_french), "fr", settings.languageTag) {
                scope.launch {
                    repository.setLanguageTag(it)
                    activity?.recreate()
                }
            }
            LanguageOption(stringResource(R.string.language_english), "en", settings.languageTag) {
                scope.launch {
                    repository.setLanguageTag(it)
                    activity?.recreate()
                }
            }

            SectionTitle(stringResource(R.string.settings_alerts))
            if (detectionState.running) {
                // The service reads settings once at start: say so instead of
                // letting mid-ride changes look like they applied.
                Text(
                    stringResource(R.string.settings_applies_next_start),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            SwitchRow(stringResource(R.string.settings_chime), settings.chimeEnabled) {
                scope.launch { repository.setChimeEnabled(it) }
            }
            SwitchRow(stringResource(R.string.settings_all_clear_chime), settings.allClearChimeEnabled) {
                scope.launch { repository.setAllClearChimeEnabled(it) }
            }
            val res = LocalContext.current.resources
            SliderRow(
                label = { res.getString(R.string.settings_alert_distance, it) },
                value = settings.alertDistanceM.toFloat(),
                range = 100f..600f,
                steps = 9,
            ) { scope.launch { repository.setAlertDistance(it.roundToInt()) } }
            SliderRow(
                label = { res.getString(R.string.settings_alert_distance_high, it) },
                value = settings.highSpeedAlertDistanceM.toFloat(),
                range = 200f..600f,
                steps = 7,
            ) { scope.launch { repository.setHighSpeedAlertDistance(it.roundToInt()) } }
            SliderRow(
                label = { res.getString(R.string.settings_tolerance, it) },
                value = settings.speedToleranceKmh.toFloat(),
                range = 0f..20f,
                steps = 19,
            ) { scope.launch { repository.setSpeedTolerance(it.roundToInt()) } }
            OverlayBubbleSetting(settings.overlayBubbleEnabled) { enabled ->
                scope.launch { repository.setOverlayBubbleEnabled(enabled) }
            }
            SwitchRow(stringResource(R.string.settings_auto_stop), settings.autoStopEnabled) {
                scope.launch { repository.setAutoStopEnabled(it) }
            }
            BluetoothAutoStartSetting(settings.autoStartBluetoothEnabled) { enabled ->
                scope.launch { repository.setAutoStartBluetoothEnabled(enabled) }
            }

            SectionTitle(stringResource(R.string.settings_camera_types))
            CameraType.entries.forEach { type ->
                SwitchRow(cameraTypeLabel(type), type in settings.enabledTypes) { enabled ->
                    val updated = if (enabled) settings.enabledTypes + type else settings.enabledTypes - type
                    scope.launch { repository.setEnabledTypes(updated) }
                }
            }

            SectionTitle(stringResource(R.string.settings_data))
            var metaRefresh by remember { mutableIntStateOf(0) }
            val metadata by produceState(initialValue = emptyMap(), metaRefresh) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching { CameraRepository(context.applicationContext).metadata() }
                            .getOrDefault(emptyMap())
                    }
            }
            Text(
                text =
                    stringResource(
                        R.string.data_version_info,
                        metadata["data_version"] ?: "—",
                        metadata["count"] ?: "—",
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            SwitchRow(stringResource(R.string.settings_auto_update), settings.autoUpdateEnabled) {
                scope.launch {
                    repository.setAutoUpdateEnabled(it)
                    UpdateWorker.schedule(context.applicationContext, it, settings.wifiOnlyUpdates)
                }
            }
            SwitchRow(stringResource(R.string.settings_wifi_only), settings.wifiOnlyUpdates) {
                scope.launch {
                    repository.setWifiOnlyUpdates(it)
                    UpdateWorker.schedule(context.applicationContext, settings.autoUpdateEnabled, it)
                }
            }
            // Process-scoped: rotating or leaving the screen must not discard
            // a check that is still downloading, nor its result.
            val check by ManualUpdateCheck.state.collectAsStateWithLifecycle()
            LaunchedEffect(check.completedCount) { if (check.completedCount > 0) metaRefresh++ }
            val strings = LocalContext.current.resources
            Button(
                enabled = !check.running,
                onClick = { ManualUpdateCheck.start(context.applicationContext) },
            ) {
                Text(stringResource(if (check.running) R.string.update_checking else R.string.update_check_now))
            }
            check.result?.let { result ->
                Spacer(Modifier.height(6.dp))
                Text(
                    when (result) {
                        UpdateResult.UpToDate -> strings.getString(R.string.update_result_uptodate)
                        is UpdateResult.Updated ->
                            strings.getString(R.string.update_result_updated, result.dataVersion, result.count)
                        is UpdateResult.Failed -> strings.getString(R.string.update_result_failed, result.reason)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionTitle(stringResource(R.string.settings_voice))
            if (detectionState.voiceMissing) {
                Text(
                    text = stringResource(R.string.voice_missing_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { context.startActivitySafely(installVoicesIntent()) }) {
                    Text(stringResource(R.string.voice_install))
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = { context.startActivitySafely(ttsSettingsIntent()) }) {
                Text(stringResource(R.string.voice_open_tts_settings))
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenAbout) {
                Text(stringResource(R.string.about_title))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LanguageOption(
    label: String,
    tag: String,
    currentTag: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = currentTag == tag, onClick = { onSelect(tag) })
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = currentTag == tag, onClick = { onSelect(tag) })
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Toggle plus its BLUETOOTH_CONNECT permission handling: requested when
 * enabling on API 31+, and a visible warning while the toggle is on but the
 * permission is missing — without it the connect broadcast never arrives. */
@Composable
private fun BluetoothAutoStartSetting(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    fun permissionMissing() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    SwitchRow(stringResource(R.string.settings_auto_start_bt), enabled) { on ->
        if (on && permissionMissing()) launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        onSetEnabled(on)
    }
    val warn = remember(refresh, enabled) { enabled && permissionMissing() }
    if (warn) {
        Text(
            stringResource(R.string.settings_bt_permission_missing),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
            Text(stringResource(R.string.settings_bt_permission_grant))
        }
    }
}

/** Toggle plus its display-over-other-apps permission handling: the special
 * access screen opens when enabling without it, and a visible warning shows
 * while the toggle is on but the permission is missing — without it the
 * service cannot attach the bubble. */
@Composable
private fun OverlayBubbleSetting(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }

    fun permissionMissing() = !Settings.canDrawOverlays(context)

    SwitchRow(stringResource(R.string.settings_overlay_bubble), enabled) { on ->
        if (on && permissionMissing()) context.startActivitySafely(overlayPermissionIntent(context))
        onSetEnabled(on)
    }
    val warn = remember(refresh, enabled) { enabled && permissionMissing() }
    if (warn) {
        Text(
            stringResource(R.string.settings_overlay_permission_missing),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { context.startActivitySafely(overlayPermissionIntent(context)) }) {
            Text(stringResource(R.string.settings_overlay_permission_grant))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The thumb follows the finger from local state; the value is persisted
 * once the drag ends. Persisting on every frame rewrote the settings file
 * dozens of times a second and made the thumb lag behind the touch. */
@Composable
private fun SliderRow(
    label: (Int) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label(local.roundToInt()), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

/** Label plus the emoji the floating bubble uses for the type — a legend. */
@Composable
private fun cameraTypeLabel(type: CameraType): String {
    val label =
        stringResource(
            when (type) {
                CameraType.FIXED -> R.string.type_fixed
                CameraType.MOBILE -> R.string.type_mobile
                CameraType.RED_LIGHT -> R.string.type_red_light
                CameraType.SECTION -> R.string.type_section
                CameraType.TECH -> R.string.type_tech
                CameraType.OTHER -> R.string.type_other
            },
        )
    val emoji =
        when (type) {
            CameraType.MOBILE -> "🚓"
            CameraType.RED_LIGHT -> "🚦"
            CameraType.SECTION -> "⏱️"
            CameraType.TECH -> "👀"
            else -> "📸"
        }
    return "$label $emoji"
}

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun installVoicesIntent(): Intent =
    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun ttsSettingsIntent(): Intent =
    Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
