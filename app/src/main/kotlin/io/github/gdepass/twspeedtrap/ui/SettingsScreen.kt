package io.github.gdepass.twspeedtrap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import io.github.gdepass.twspeedtrap.data.DbUpdater
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.data.UpdateResult
import io.github.gdepass.twspeedtrap.data.UpdateWorker
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.service.DetectionStatus
import io.github.gdepass.twspeedtrap.util.LocaleOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            SwitchRow(stringResource(R.string.settings_chime), settings.chimeEnabled) {
                scope.launch { repository.setChimeEnabled(it) }
            }
            SliderRow(
                label = stringResource(R.string.settings_lead_time, settings.distanceMultiplier.toInt()),
                value = settings.distanceMultiplier.toFloat(),
                range = 6f..20f,
                steps = 13,
            ) { scope.launch { repository.setDistanceMultiplier(it.toDouble()) } }
            SliderRow(
                label = stringResource(R.string.settings_tolerance, settings.speedToleranceKmh),
                value = settings.speedToleranceKmh.toFloat(),
                range = 0f..20f,
                steps = 19,
            ) { scope.launch { repository.setSpeedTolerance(it.toInt()) } }
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
            var checking by remember { mutableStateOf(false) }
            var updateStatus by remember { mutableStateOf<String?>(null) }
            val strings = LocalContext.current.resources
            Button(
                enabled = !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        updateStatus = null
                        val result = DbUpdater(context.applicationContext).checkAndUpdate()
                        updateStatus =
                            when (result) {
                                UpdateResult.UpToDate -> strings.getString(R.string.update_result_uptodate)
                                is UpdateResult.Updated ->
                                    strings.getString(R.string.update_result_updated, result.dataVersion, result.count)
                                is UpdateResult.Failed ->
                                    strings.getString(R.string.update_result_failed, result.reason)
                            }
                        metaRefresh++
                        checking = false
                    }
                },
            ) {
                Text(stringResource(if (checking) R.string.update_checking else R.string.update_check_now))
            }
            updateStatus?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            SectionTitle(stringResource(R.string.settings_voice))
            if (detectionState.voiceMissing) {
                Text(
                    text = stringResource(R.string.voice_missing_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { context.startActivity(installVoicesIntent()) }) {
                    Text(stringResource(R.string.voice_install))
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = { context.startActivity(ttsSettingsIntent()) }) {
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

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun cameraTypeLabel(type: CameraType): String =
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

private fun installVoicesIntent(): Intent =
    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun ttsSettingsIntent(): Intent =
    Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
