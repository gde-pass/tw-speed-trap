package io.github.gdepass.twspeedtrap.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import io.github.gdepass.twspeedtrap.data.AppSettings
import io.github.gdepass.twspeedtrap.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the floating bubble for the whole process, not just while detection
 * runs: with the setting on and the overlay permission granted, the bubble is
 * up for as long as the app is open — including while it sits behind Google
 * Maps — and for as long as detection runs, grey (tap to start) while
 * detection is off, live states from [DetectionStatus] while it runs.
 * Background process starts (update worker, Bluetooth receiver) alone never
 * summon it, and closing the app puts it away again. Tapping toggles the
 * [DetectionService]; dragging persists the position.
 */
object BubbleOverlayController {
    private var bubble: OverlayBubble? = null
    private val permissionRecheck = MutableStateFlow(0)

    /** True while the app has a live activity: open, or backgrounded with its
     * task intact. Two things hang on it — a background-only process
     * resurrection never summons the bubble, and closing the app takes an idle
     * bubble down. The second has no other rescue: an overlay window holds the
     * process at perceptible priority, so it is never reclaimed, and a bubble
     * that outlives the app that owns it outlives it for good. */
    private val userPresent = MutableStateFlow(false)
    private val presence = ActivityPresence()
    private var started = false

    /** Idempotent; call once from [android.app.Application.onCreate]. */
    fun init(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(UserPresence)
        // Main.immediate: every bubble mutation is a WindowManager call.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = SettingsRepository(app)
        scope.launch {
            combine(
                repository.settings,
                DetectionStatus.state,
                permissionRecheck,
                userPresent,
            ) { settings, status, _, present ->
                Triple(settings, status, present)
            }.collect { (settings, status, present) -> apply(app, scope, repository, settings, status, present) }
        }
    }

    /** Re-evaluate visibility, e.g. after returning from the permission screen. */
    fun refresh() {
        permissionRecheck.value++
    }

    /** The bubble floats over every other app, so it must never be a window
     * the user cannot put away: with detection off it lives exactly as long as
     * the app does, and while detection runs the bubble itself is the off
     * switch. */
    internal fun shouldShow(
        enabled: Boolean,
        present: Boolean,
        running: Boolean,
        canDrawOverlays: Boolean,
    ): Boolean = enabled && (present || running) && canDrawOverlays

    private fun apply(
        app: Application,
        scope: CoroutineScope,
        repository: SettingsRepository,
        settings: AppSettings,
        status: DetectionStatus.UiState,
        present: Boolean,
    ) {
        val show =
            shouldShow(
                settings.overlayBubbleEnabled,
                present,
                status.running,
                Settings.canDrawOverlays(app),
            )
        if (!show) {
            bubble?.detach()
            bubble = null
            return
        }
        val view = bubble ?: createBubble(app, scope, repository, settings) ?: return
        view.render(stateFor(status))
    }

    private fun createBubble(
        app: Application,
        scope: CoroutineScope,
        repository: SettingsRepository,
        settings: AppSettings,
    ): OverlayBubble? {
        val view =
            OverlayBubble(
                app,
                settings.overlayX,
                settings.overlayY,
                onPositionChanged = { x, y -> scope.launch { repository.setOverlayPosition(x, y) } },
                onTap = { toggleDetection(app) },
            )
        return try {
            view.attach()
            bubble = view
            view
        } catch (e: WindowManager.BadTokenException) {
            // Permission revoked between the check and the addView: skip this
            // round instead of taking the process down.
            Log.e(TAG, "overlay attach refused", e)
            null
        } catch (e: IllegalStateException) {
            Log.e(TAG, "overlay attach refused", e)
            null
        }
    }

    internal fun stateFor(status: DetectionStatus.UiState): BubbleState =
        when {
            !status.running -> BubbleState.Idle
            // Blind detection must never look protected: location services
            // off, or not a single GPS fix yet (including a background start
            // on Android 11–13 where location updates are silently withheld).
            status.locationOff || status.accuracyM == null -> BubbleState.NoGps
            status.activeAlert != null ->
                BubbleState.Alert(
                    status.activeAlert.type,
                    status.activeAlert.speedLimitKmh,
                    status.activeAlert.distanceM,
                )
            status.activeSection != null ->
                BubbleState.Section(
                    status.activeSection.speedLimitKmh,
                    status.activeSection.projectedAverageKmh,
                )
            else -> BubbleState.Clear
        }

    private fun toggleDetection(context: Context) {
        val intent = Intent(context, DetectionService::class.java)
        if (DetectionStatus.state.value.running) {
            intent.action = DetectionService.ACTION_STOP
            context.startService(intent)
            return
        }
        // Startable from the background thanks to the display-over-other-apps
        // permission. Android 14+ refuses a background location-FGS without
        // the "all the time" grant (the service degrades to a tap-to-start
        // notification); on 11–13 the start can succeed with location updates
        // withheld — the bubble then shows the amber no-GPS state, never a
        // false green.
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "bubble start refused", e)
            TapToStart.post(context)
        } catch (e: SecurityException) {
            Log.e(TAG, "bubble start refused", e)
            TapToStart.post(context)
        }
    }

    private object UserPresence : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) {
            userPresent.value = presence.onCreated()
        }

        override fun onActivityDestroyed(activity: Activity) {
            userPresent.value = presence.onDestroyed(activity.isChangingConfigurations)
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit
    }

    private const val TAG = "BubbleOverlay"
}
