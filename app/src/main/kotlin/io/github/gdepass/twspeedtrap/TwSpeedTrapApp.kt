package io.github.gdepass.twspeedtrap

import android.app.Application
import android.util.Log
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.data.UpdateWorker
import io.github.gdepass.twspeedtrap.service.BluetoothAutoStartReceiver
import io.github.gdepass.twspeedtrap.service.BubbleOverlayController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TwSpeedTrapApp : Application() {
    private val appScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e("TwSpeedTrapApp", "startup task failed", e) },
        )

    override fun onCreate() {
        super.onCreate()
        BubbleOverlayController.init(this)
        appScope.launch {
            val settings = SettingsRepository(this@TwSpeedTrapApp).settings.first()
            UpdateWorker.schedule(this@TwSpeedTrapApp, settings.autoUpdateEnabled, settings.wifiOnlyUpdates)
            BluetoothAutoStartReceiver.setComponentEnabled(this@TwSpeedTrapApp, settings.autoStartBluetoothEnabled)
        }
    }
}
