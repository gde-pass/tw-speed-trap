package io.github.gdepass.twspeedtrap

import android.app.Application
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.data.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TwSpeedTrapApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val settings = SettingsRepository(this@TwSpeedTrapApp).settings.first()
            UpdateWorker.schedule(this@TwSpeedTrapApp, settings.autoUpdateEnabled, settings.wifiOnlyUpdates)
        }
    }
}
