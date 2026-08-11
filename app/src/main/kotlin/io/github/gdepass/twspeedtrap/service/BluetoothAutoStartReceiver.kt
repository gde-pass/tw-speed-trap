package io.github.gdepass.twspeedtrap.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts detection when a Bluetooth device connects (opt-in setting).
 *
 * Android 12+ forbids foreground-service starts from a background receiver,
 * so where the direct start is rejected we degrade to a high-priority
 * "tap to start" notification — tapping a notification is always a valid
 * foreground-service trigger.
 */
class BluetoothAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        // Without location permission the service would only start and die.
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "location permission missing — ignoring Bluetooth connect")
            return
        }
        val pending = goAsync()
        // The timeout keeps the coroutine bounded well inside the ~10 s
        // broadcast window, so nothing outlives the receiver.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings =
                    withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                        SettingsRepository(context.applicationContext).settings.first()
                    }
                if (settings?.autoStartBluetoothEnabled == true) startOrPrompt(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun startOrPrompt(context: Context) {
        val service = Intent(context, DetectionService::class.java)
        try {
            context.startForegroundService(service)
            Log.i(TAG, "detection auto-started on Bluetooth connect")
        } catch (e: IllegalStateException) {
            Log.i(TAG, "background start rejected ($e), posting tap-to-start notification")
            postTapToStart(context, service)
        } catch (e: SecurityException) {
            Log.i(TAG, "background start rejected ($e), posting tap-to-start notification")
            postTapToStart(context, service)
        }
    }

    private fun postTapToStart(
        context: Context,
        service: Intent,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_autostart_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val start =
            PendingIntent.getForegroundService(
                context,
                REQUEST_CODE,
                service,
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_autostart_title))
                .setContentText(context.getString(R.string.notif_autostart_text))
                .setContentIntent(start)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "BluetoothAutoStart"
        private const val CHANNEL_ID = "autostart"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_CODE = 2
        private const val SETTINGS_TIMEOUT_MS = 8_000L
    }
}
