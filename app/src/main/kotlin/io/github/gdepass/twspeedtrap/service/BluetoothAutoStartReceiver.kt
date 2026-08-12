package io.github.gdepass.twspeedtrap.service

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts detection when a Bluetooth device connects (opt-in setting).
 *
 * Two Android restrictions shape the flow. Android 12+ forbids
 * foreground-service starts from a background receiver — except for
 * BLUETOOTH_CONNECT-gated broadcasts like this one, so the start call itself
 * goes through. But Android 14+ then rejects a location-type service inside
 * startForeground (SecurityException, crashing the process if unhandled)
 * unless location is granted "all the time": a while-in-use grant gives a
 * background-started service no location eligibility. So the direct start is
 * only attempted with a background-location grant; otherwise we degrade to a
 * high-priority "tap to start" notification — tapping a notification is
 * always a valid foreground-service trigger.
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
        val backgroundLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!backgroundLocation) {
            // The direct start would pass (Bluetooth broadcast exemption) and
            // then crash in the service at startForeground — see class KDoc.
            Log.i(TAG, "no background-location grant, posting tap-to-start notification")
            TapToStart.post(context)
            return
        }
        try {
            context.startForegroundService(Intent(context, DetectionService::class.java))
            Log.i(TAG, "detection auto-started on Bluetooth connect")
        } catch (e: IllegalStateException) {
            Log.i(TAG, "background start rejected ($e), posting tap-to-start notification")
            TapToStart.post(context)
        } catch (e: SecurityException) {
            Log.i(TAG, "background start rejected ($e), posting tap-to-start notification")
            TapToStart.post(context)
        }
    }

    companion object {
        private const val TAG = "BluetoothAutoStart"
        private const val SETTINGS_TIMEOUT_MS = 8_000L
    }
}
