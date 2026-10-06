package io.github.gdepass.twspeedtrap.service

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
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
        val device = connectedDevice(intent)
        val pending = goAsync()
        // The timeout keeps the coroutine bounded well inside the ~10 s
        // broadcast window, so nothing outlives the receiver.
        // A settings read failing here must not crash the process on a
        // background broadcast; the connect is simply ignored.
        val crashGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "auto-start failed", e) }
        CoroutineScope(Dispatchers.IO + crashGuard).launch {
            try {
                val settings =
                    withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                        SettingsRepository(context.applicationContext).settings.first()
                    }
                when {
                    settings?.autoStartBluetoothEnabled != true -> Unit
                    !deviceAllowed(device, settings.autoStartBluetoothDevices) ->
                        Log.i(TAG, "device $device is not in the auto-start list — ignoring")
                    else -> startOrPrompt(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** The connected device's address, or null when the broadcast carries none. */
    private fun connectedDevice(intent: Intent): String? {
        val device =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        return device?.address
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

        /** Well inside the ~10 s temporary allowlist the broadcast grants for the service start. */
        private const val SETTINGS_TIMEOUT_MS = 5_000L

        /** Empty list = every device (the original behaviour); otherwise only the chosen ones. */
        fun deviceAllowed(
            address: String?,
            allowed: Set<String>,
        ): Boolean = allowed.isEmpty() || (address != null && address.uppercase() in allowed.map { it.uppercase() })

        /**
         * The manifest receiver is otherwise always live: every watch or
         * earbud reconnect cold-started the process (Application.onCreate,
         * DataStore, WorkManager) only to read a disabled setting.
         */
        fun setComponentEnabled(
            context: Context,
            enabled: Boolean,
        ) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BluetoothAutoStartReceiver::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
