package io.github.gdepass.twspeedtrap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.service.DetectionService
import io.github.gdepass.twspeedtrap.service.DetectionStatus

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit = {},
    onOpenMap: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by DetectionStatus.state.collectAsStateWithLifecycle()

    // Re-check permission state every time we come back from system dialogs.
    var permissionRefresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permissionRefresh++
        onPauseOrDispose {}
    }
    val checks = remember(permissionRefresh) { SystemChecks.read(context) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            StatusRow(state, onOpenSettings, onOpenMap)
            Spacer(Modifier.height(24.dp))
            SpeedDisplay(state)
            Spacer(Modifier.height(24.dp))

            PermissionChecklist(
                checks = checks,
                onRequestPermission = { permissionLauncher.launch(it) },
                onRequestBatteryExemption = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                onOpenLocationSettings = {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                },
            )

            Spacer(Modifier.weight(1f))
            StartStopButton(running = state.running, enabled = checks.fineGranted) {
                val intent = Intent(context, DetectionService::class.java)
                if (state.running) {
                    intent.action = DetectionService.ACTION_STOP
                    context.startService(intent)
                } else {
                    context.startForegroundService(intent)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionChecklist(
    checks: SystemChecks,
    onRequestPermission: (String) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    if (!checks.fineGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_location_title),
            body = stringResource(R.string.perm_location_body),
            buttonLabel = stringResource(R.string.perm_location_grant),
        ) { onRequestPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }
    if (!checks.locationEnabled) {
        PermissionCard(
            title = stringResource(R.string.perm_gps_off_title),
            body = stringResource(R.string.perm_gps_off_body),
            buttonLabel = stringResource(R.string.perm_gps_off_enable),
            onClick = onOpenLocationSettings,
        )
    }
    if (!checks.backgroundGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_background_title),
            body = stringResource(R.string.perm_background_body),
            buttonLabel = stringResource(R.string.perm_background_grant),
        ) { onRequestPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    }
    if (!checks.notificationsGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            body = stringResource(R.string.perm_notifications_body),
            buttonLabel = stringResource(R.string.perm_notifications_grant),
        ) { onRequestPermission(Manifest.permission.POST_NOTIFICATIONS) }
    }
    if (!checks.batteryExempt) {
        PermissionCard(
            title = stringResource(R.string.perm_battery_title),
            body = stringResource(R.string.perm_battery_body),
            buttonLabel = stringResource(R.string.perm_battery_grant),
            onClick = onRequestBatteryExemption,
        )
    }
}

/** Everything that must be true before alerts can actually fire. */
private data class SystemChecks(
    val fineGranted: Boolean,
    val backgroundGranted: Boolean,
    val notificationsGranted: Boolean,
    val batteryExempt: Boolean,
    val locationEnabled: Boolean,
) {
    companion object {
        fun read(context: Context): SystemChecks =
            SystemChecks(
                fineGranted = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                backgroundGranted = context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                notificationsGranted =
                    Build.VERSION.SDK_INT < 33 || context.hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                batteryExempt =
                    context
                        .getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName),
                locationEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled,
            )
    }
}

@Composable
private fun StatusRow(
    state: DetectionStatus.UiState,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                when {
                    !state.running -> ""
                    state.locationOff -> stringResource(R.string.gps_disabled_warning)
                    state.accuracyM == null -> stringResource(R.string.gps_waiting)
                    else -> stringResource(R.string.gps_accuracy, state.accuracyM)
                },
            style = MaterialTheme.typography.labelLarge,
            color =
                if (state.locationOff) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.running) {
                Text(
                    text = stringResource(R.string.cameras_loaded, state.cameraCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenMap) {
                Icon(
                    imageVector = Icons.Filled.Place,
                    contentDescription = stringResource(R.string.map_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpeedDisplay(state: DetectionStatus.UiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.speedKmh?.toString() ?: "—",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.speed_unit),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val distance = state.nextCameraDistanceM
        val limit = state.nextCameraLimitKmh
        Text(
            text =
                when {
                    !state.running -> ""
                    distance == null -> stringResource(R.string.no_camera_nearby)
                    limit != null -> stringResource(R.string.next_camera_limit, distance, limit)
                    else -> stringResource(R.string.next_camera, distance)
                },
            style = MaterialTheme.typography.titleLarge,
            color =
                if (distance != null) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

@Composable
private fun StartStopButton(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(72.dp),
    ) {
        Text(
            text = stringResource(if (running) R.string.btn_stop else R.string.btn_start),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
