package io.github.gdepass.twspeedtrap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
fun MainScreen() {
    val context = LocalContext.current
    val state by DetectionStatus.state.collectAsStateWithLifecycle()

    // Re-check permission state every time we come back from system dialogs.
    var permissionRefresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permissionRefresh++
        onPauseOrDispose {}
    }
    val fineGranted = remember(permissionRefresh) { context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
    val backgroundGranted =
        remember(permissionRefresh) { context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notificationsGranted =
        remember(permissionRefresh) {
            Build.VERSION.SDK_INT < 33 || context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        }
    val batteryExempt =
        remember(permissionRefresh) {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
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
            StatusRow(state)
            Spacer(Modifier.height(24.dp))
            SpeedDisplay(state)
            Spacer(Modifier.height(24.dp))

            PermissionChecklist(
                fineGranted = fineGranted,
                backgroundGranted = backgroundGranted,
                notificationsGranted = notificationsGranted,
                batteryExempt = batteryExempt,
                onRequestPermission = { permissionLauncher.launch(it) },
                onRequestBatteryExemption = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            Spacer(Modifier.weight(1f))
            StartStopButton(running = state.running, enabled = fineGranted) {
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
    fineGranted: Boolean,
    backgroundGranted: Boolean,
    notificationsGranted: Boolean,
    batteryExempt: Boolean,
    onRequestPermission: (String) -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    if (!fineGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_location_title),
            body = stringResource(R.string.perm_location_body),
            buttonLabel = stringResource(R.string.perm_location_grant),
        ) { onRequestPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
        return
    }
    if (!backgroundGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_background_title),
            body = stringResource(R.string.perm_background_body),
            buttonLabel = stringResource(R.string.perm_background_grant),
        ) { onRequestPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    }
    if (!notificationsGranted) {
        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            body = stringResource(R.string.perm_notifications_body),
            buttonLabel = stringResource(R.string.perm_notifications_grant),
        ) { onRequestPermission(Manifest.permission.POST_NOTIFICATIONS) }
    }
    if (!batteryExempt) {
        PermissionCard(
            title = stringResource(R.string.perm_battery_title),
            body = stringResource(R.string.perm_battery_body),
            buttonLabel = stringResource(R.string.perm_battery_grant),
            onClick = onRequestBatteryExemption,
        )
    }
}

@Composable
private fun StatusRow(state: DetectionStatus.UiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text =
                when {
                    !state.running -> ""
                    state.accuracyM == null -> stringResource(R.string.gps_waiting)
                    else -> stringResource(R.string.gps_accuracy, state.accuracyM)
                },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.running) {
            Text(
                text = stringResource(R.string.cameras_loaded, state.cameraCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
