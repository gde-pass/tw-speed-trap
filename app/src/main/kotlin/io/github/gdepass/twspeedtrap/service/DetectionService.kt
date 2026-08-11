package io.github.gdepass.twspeedtrap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.gdepass.twspeedtrap.MainActivity
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.CameraRepository
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import io.github.gdepass.twspeedtrap.detection.AlertEngine
import io.github.gdepass.twspeedtrap.detection.AlertEvent
import io.github.gdepass.twspeedtrap.detection.CameraType
import io.github.gdepass.twspeedtrap.util.LocaleOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class DetectionService : LifecycleService() {
    private var detectionJob: Job? = null
    private var announcer: Announcer? = null
    private var chimeEnabled = true
    private lateinit var localized: Context
    private var stationarySinceMs: Long? = null
    private var locationMonitor: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        localized = this
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        return when (intent?.action) {
            ACTION_STOP -> {
                stopDetection()
                START_NOT_STICKY
            }
            else -> {
                startDetection()
                START_STICKY
            }
        }
    }

    private fun startDetection() {
        if (detectionJob != null) return
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        detectionJob =
            lifecycleScope.launch {
                val settings = SettingsRepository(applicationContext).settings.first()
                chimeEnabled = settings.chimeEnabled
                // Spoken alerts and notification text follow the app language.
                localized = LocaleOverride.wrap(this@DetectionService, settings.languageTag)
                announcer =
                    Announcer(
                        this@DetectionService,
                        LocaleOverride.resolve(this@DetectionService, settings.languageTag),
                    ) { missing -> DetectionStatus.update { it.copy(voiceMissing = missing) } }
                val repository = CameraRepository(this@DetectionService)
                val (cameras, sections) =
                    withContext(Dispatchers.IO) { repository.loadCameras() to repository.loadSections() }
                val engine = AlertEngine(cameras, settings.toEngineConfig(), sections)
                DetectionStatus.update { it.copy(running = true, cameraCount = cameras.size) }
                watchLocationServices()

                LocationSource(this@DetectionService).fixes().collect { fix ->
                    if (settings.autoStopEnabled && stationaryTooLong(fix.speedMps, fix.timestampMs)) {
                        Log.i(TAG, "stationary for ${AUTO_STOP_AFTER_MS / 60_000} min — stopping detection")
                        stopDetection()
                        return@collect
                    }
                    engine.onFix(fix).forEach(::announce)
                    val nearest = engine.nearestCamera
                    val speedKmh = (fix.speedMps * 3.6).roundToInt()
                    DetectionStatus.update {
                        it.copy(
                            speedKmh = speedKmh,
                            accuracyM = fix.accuracyM.roundToInt(),
                            nextCameraDistanceM = nearest?.second?.roundToInt(),
                            nextCameraLimitKmh = nearest?.first?.speedLimitKmh,
                        )
                    }
                    updateNotification(speedKmh, nearest?.second?.roundToInt())
                }
            }
    }

    private fun announce(event: AlertEvent) {
        when (event) {
            is AlertEvent.CameraAhead -> {
                val distance = roundForSpeech(event.distanceM)
                val limit = event.camera.speedLimitKmh
                var text =
                    when (event.camera.type) {
                        CameraType.RED_LIGHT -> localized.getString(R.string.alert_red_light_camera, distance)
                        CameraType.TECH -> localized.getString(R.string.alert_tech_enforcement, distance)
                        CameraType.MOBILE -> localized.getString(R.string.alert_mobile_camera, distance)
                        else ->
                            if (limit != null) {
                                localized.getString(R.string.alert_fixed_camera_limit, distance, limit)
                            } else {
                                localized.getString(R.string.alert_fixed_camera, distance)
                            }
                    }
                if (event.overLimit) {
                    text = localized.getString(R.string.alert_with_warning, text)
                }
                Log.i(TAG, "alert: $text (${event.camera.id} at ${event.distanceM.roundToInt()} m)")
                announcer?.speak(text, chimeEnabled)
            }
            is AlertEvent.SectionEntered -> {
                val text = localized.getString(R.string.alert_section_entered, event.section.speedLimitKmh)
                Log.i(TAG, "alert: $text (${event.section.id})")
                announcer?.speak(text, chimeEnabled)
            }
            is AlertEvent.SectionOverPace -> {
                val text = localized.getString(R.string.alert_section_over)
                Log.i(TAG, "alert: $text (projected ${event.projectedAvgKmh} km/h)")
                announcer?.speak(text, chime = false)
            }
            is AlertEvent.SectionExited -> {
                var text = localized.getString(R.string.alert_section_exited, event.averageKmh)
                if (event.overLimit) text = localized.getString(R.string.alert_with_warning, text)
                Log.i(TAG, "alert: $text (${event.section.id})")
                announcer?.speak(text, chime = false)
            }
        }
    }

    private fun stopDetection() {
        detectionJob?.cancel()
        detectionJob = null
        stationarySinceMs = null
        locationMonitor?.let(::unregisterReceiver)
        locationMonitor = null
        announcer?.release()
        announcer = null
        DetectionStatus.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detectionJob?.cancel()
        locationMonitor?.let(::unregisterReceiver)
        locationMonitor = null
        announcer?.release()
        DetectionStatus.reset()
        super.onDestroy()
    }

    /** GPS silently off = the most dangerous state: the app looks alive but
     * can never alert. Watch the system toggle, tell the rider both ways. */
    private fun watchLocationServices() {
        if (locationMonitor != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) = onLocationServicesChanged()
            }
        locationMonitor = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onLocationServicesChanged()
    }

    private fun onLocationServicesChanged() {
        val enabled = getSystemService(LocationManager::class.java).isLocationEnabled
        val wasOff = DetectionStatus.state.value.locationOff
        if (!enabled && !wasOff) {
            Log.w(TAG, "location services turned off while detecting")
            DetectionStatus.update { it.copy(locationOff = true) }
            announcer?.speak(localized.getString(R.string.alert_location_off), chimeEnabled)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(localized.getString(R.string.notif_location_off)))
        } else if (enabled && wasOff) {
            DetectionStatus.update { it.copy(locationOff = false) }
            announcer?.speak(localized.getString(R.string.alert_location_on), chimeEnabled)
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun updateNotification(
        speedKmh: Int,
        cameraDistanceM: Int?,
    ) {
        val text =
            if (cameraDistanceM != null) {
                localized.getString(R.string.notif_speed_camera, speedKmh, cameraDistanceM)
            } else {
                localized.getString(R.string.notif_speed, speedKmh)
            }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun roundForSpeech(distanceM: Double): Int = ((distanceM / 50.0).roundToInt() * 50).coerceAtLeast(50)

    /** True once every fix for AUTO_STOP_AFTER_MS stayed below walking pace.
     * Fix-driven, so a phone parked where GPS starves simply keeps running —
     * the conservative failure mode for a safety feature. */
    private fun stationaryTooLong(
        speedMps: Double,
        timestampMs: Long,
    ): Boolean {
        if (speedMps >= STATIONARY_SPEED_MPS) {
            stationarySinceMs = null
            return false
        }
        val since = stationarySinceMs ?: timestampMs.also { stationarySinceMs = it }
        return timestampMs - since >= AUTO_STOP_AFTER_MS
    }

    companion object {
        const val ACTION_STOP = "io.github.gdepass.twspeedtrap.STOP"
        private const val TAG = "DetectionService"
        private const val STATIONARY_SPEED_MPS = 1.0
        private const val AUTO_STOP_AFTER_MS = 10 * 60_000L
        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 1
    }
}
