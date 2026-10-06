package io.github.gdepass.twspeedtrap.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.SystemClock
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
import io.github.gdepass.twspeedtrap.detection.Fix
import io.github.gdepass.twspeedtrap.detection.StationaryDetector
import io.github.gdepass.twspeedtrap.util.LocaleOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class DetectionService : LifecycleService() {
    private var detectionJob: Job? = null
    private var announcer: Announcer? = null
    private var chimeEnabled = true
    private var allClearChimeEnabled = false
    private lateinit var localized: Context
    private var locationMonitor: BroadcastReceiver? = null
    private var lastNotificationText: String? = null

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
        // Every start request must reach startForeground, including redundant
        // ones while already running (Bluetooth auto-start, notification tap).
        // A redundant one that gets refused is logged and ignored: the ride
        // already running must not be torn down by a reconnecting intercom.
        if (!startForegroundOrDegrade(alreadyRunning = detectionJob != null)) return
        // A start that succeeded supersedes any "tap to start" or failure
        // notification still on the shade.
        getSystemService(NotificationManager::class.java).apply {
            cancel(TapToStart.NOTIFICATION_ID)
            cancel(FAILURE_NOTIFICATION_ID)
        }
        if (detectionJob != null) return

        DetectionStatus.update { it.copy(starting = true) }
        detectionJob =
            lifecycleScope.launch {
                @Suppress("TooGenericExceptionCaught") // whatever breaks, the rider must hear about it
                try {
                    runDetection()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "detection failed", e)
                    reportFailure(e)
                }
            }
    }

    /** Android 14+ rejects a location-type foreground service started while
     * the app is in the background without an "all the time" location grant —
     * a SecurityException here, which must never take the process down (the
     * Bluetooth auto-start receiver reaches this from a cold background
     * start). Degrade to the tap-to-start notification instead of crashing. */
    private fun startForegroundOrDegrade(alreadyRunning: Boolean): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(lastNotificationText ?: getString(R.string.notif_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            true
        } catch (e: SecurityException) {
            refuseStart(e, alreadyRunning)
            false
        } catch (e: IllegalStateException) {
            refuseStart(e, alreadyRunning)
            false
        }

    private fun refuseStart(
        e: Exception,
        alreadyRunning: Boolean,
    ) {
        if (alreadyRunning) {
            Log.w(TAG, "redundant start refused while detection is running — ignoring", e)
        } else {
            degradeToTapToStart(e)
        }
    }

    private fun degradeToTapToStart(e: Exception) {
        Log.e(TAG, "startForeground rejected, degrading to tap-to-start", e)
        val fineGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        // Without location permission a tap would only reproduce the same
        // rejection and post the same notification again: a loop. The rider
        // has to open the app and grant it.
        if (fineGranted) TapToStart.post(this) else Log.w(TAG, "location permission missing — not posting tap-to-start")
        stopSelf()
    }

    private suspend fun runDetection() {
        val settings = SettingsRepository(applicationContext).settings.first()
        chimeEnabled = settings.chimeEnabled
        allClearChimeEnabled = settings.allClearChimeEnabled
        // Spoken alerts and notification text follow the app language.
        localized = LocaleOverride.wrap(this@DetectionService, settings.languageTag)
        announcer =
            Announcer(
                this@DetectionService,
                LocaleOverride.resolve(this@DetectionService, settings.languageTag),
            ) { missing -> DetectionStatus.update { it.copy(voiceMissing = missing) } }
        // Watch the system location toggle from the start: a transition during
        // the database load below must not go unnoticed.
        watchLocationServices()
        val repository = CameraRepository(this@DetectionService)
        val (cameras, sections) = withContext(Dispatchers.IO) { repository.loadAll() }
        val engine = AlertEngine(cameras, settings.toEngineConfig(), sections)
        if (engine.unusableSectionEndpoints > 0) {
            Log.w(TAG, "${engine.unusableSectionEndpoints} section endpoint(s) unusable — check the pipeline output")
        }
        DetectionStatus.update { it.copy(starting = false, running = true, cameraCount = cameras.size) }
        val stationary = StationaryDetector()
        val throttle = NotificationThrottle()
        val staleWatch = launchStaleFixWatch()

        LocationSource(this@DetectionService)
            .fixes(onAvailability = { available -> if (!available) markGpsStale() })
            .collect { fix ->
                if (settings.autoStopEnabled && stationary.onFix(fix)) {
                    Log.i(TAG, "stationary for ${StationaryDetector.HOLD_MS / 60_000} min — stopping detection")
                    announcer?.speak(localized.getString(R.string.alert_auto_stopped), chimeEnabled)
                    announcer?.awaitIdle(FINAL_SPEECH_MAX_MS)
                    staleWatch.cancel()
                    stopDetection()
                    return@collect
                }
                lastFixElapsedMs = SystemClock.elapsedRealtime()
                engine.onFix(fix).forEach(::announce)
                publish(engine, fix, throttle)
            }
    }

    /** Pushes one fix's engine state to the UI flow and the (throttled) notification. */
    private fun publish(
        engine: AlertEngine,
        fix: Fix,
        throttle: NotificationThrottle,
    ) {
        val nearest = engine.nearestCamera
        val alert =
            engine.activeAlert?.let { (camera, distance) ->
                DetectionStatus.ActiveAlert(camera.type, camera.speedLimitKmh, distance.roundToInt())
            }
        val section =
            engine.activeSection?.let { (sec, projected) ->
                DetectionStatus.ActiveSection(sec.speedLimitKmh, projected)
            }
        val speedKmh = (fix.speedMps * 3.6).roundToInt()
        DetectionStatus.update {
            it.copy(
                speedKmh = speedKmh,
                accuracyM = fix.accuracyM.roundToInt(),
                nextCameraDistanceM = nearest?.second?.roundToInt(),
                nextCameraLimitKmh = nearest?.first?.speedLimitKmh,
                activeAlert = alert,
                activeSection = section,
                gpsStale = false,
            )
        }
        val rendered =
            NotificationThrottle.Rendered(
                speedKmh,
                nearest?.second?.roundToInt()?.let(NotificationThrottle::bucket),
            )
        if (throttle.shouldNotify(rendered, SystemClock.elapsedRealtime())) {
            updateNotification(rendered.speedKmh, rendered.distanceBucketM)
        }
    }

    private var lastFixElapsedMs = 0L

    /** Fixes that stop coming (tunnel, car park) must not leave the last speed
     * and a green bubble on screen: after [STALE_FIX_MS] without a fix the
     * status goes stale, which the bubble and the main screen show as no GPS. */
    private fun launchStaleFixWatch(): Job =
        lifecycleScope.launch {
            lastFixElapsedMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(STALE_CHECK_MS)
                if (SystemClock.elapsedRealtime() - lastFixElapsedMs > STALE_FIX_MS) markGpsStale()
            }
        }

    private fun markGpsStale() {
        if (DetectionStatus.state.value.gpsStale) return
        Log.w(TAG, "no GPS fix for a while — showing detection as blind")
        DetectionStatus.update { it.copy(gpsStale = true) }
    }

    /** Detection died (revoked permission, corrupt database, …): the rider
     * must not believe they are still protected. */
    private suspend fun reportFailure(e: Exception) {
        val text = localized.getString(R.string.notif_detection_failed, e.message ?: e.javaClass.simpleName)
        getSystemService(NotificationManager::class.java).notify(
            FAILURE_NOTIFICATION_ID,
            NotificationCompat
                .Builder(this, ATTENTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .build(),
        )
        announcer?.speak(localized.getString(R.string.alert_detection_failed), chimeEnabled)
        announcer?.awaitIdle(FINAL_SPEECH_MAX_MS)
        stopDetection()
    }

    private fun announce(event: AlertEvent) {
        when (event) {
            is AlertEvent.CameraAhead -> announceCameraAhead(event)
            is AlertEvent.AllClear -> {
                Log.i(TAG, "all clear (${event.camera.id})")
                if (allClearChimeEnabled) announcer?.playAllClear()
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
                val resource =
                    if (event.estimated) R.string.alert_section_exited_estimated else R.string.alert_section_exited
                var text = localized.getString(resource, event.averageKmh)
                if (event.overLimit) text = localized.getString(R.string.alert_with_warning, text)
                Log.i(TAG, "alert: $text (${event.section.id}, estimated=${event.estimated})")
                announcer?.speak(text, chime = false)
            }
        }
    }

    private fun announceCameraAhead(event: AlertEvent.CameraAhead) {
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
        announcer?.speak(text, chimeEnabled, urgent = true)
    }

    private fun stopDetection() {
        detectionJob?.cancel()
        detectionJob = null
        lastNotificationText = null
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
            postNotification(localized.getString(R.string.notif_location_off))
        } else if (enabled && wasOff) {
            DetectionStatus.update { it.copy(locationOff = false) }
            announcer?.speak(localized.getString(R.string.alert_location_on), chimeEnabled)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        // The silent status channel is right for the per-second ride
        // notification and wrong for the one message that must interrupt:
        // detection died and the rider believes they are still protected.
        manager.createNotificationChannel(
            NotificationChannel(
                ATTENTION_CHANNEL_ID,
                getString(R.string.notif_attention_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    // The intents are constant, so the PendingIntents can be created once per
    // service instance instead of twice per notification post.
    private val openAppIntent: PendingIntent by lazy {
        // The launcher intent matches the task's root, so tapping brings the
        // existing activity forward instead of stacking a second MainActivity.
        val launch =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
        PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }
    private val stopIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, localized.getString(R.string.notif_stop), stopIntent)
            .build()

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
        postNotification(text)
    }

    private fun postNotification(text: String) {
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun roundForSpeech(distanceM: Double): Int = ((distanceM / 50.0).roundToInt() * 50).coerceAtLeast(50)

    companion object {
        const val ACTION_STOP = "io.github.gdepass.twspeedtrap.STOP"
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "detection"
        private const val ATTENTION_CHANNEL_ID = "detection-attention"
        private const val NOTIFICATION_ID = 1
        private const val FAILURE_NOTIFICATION_ID = 3

        /** Upper bound on waiting for a final announcement to finish before stopping. */
        private const val FINAL_SPEECH_MAX_MS = 8_000L
        private const val STALE_FIX_MS = 8_000L
        private const val STALE_CHECK_MS = 2_000L
    }
}
