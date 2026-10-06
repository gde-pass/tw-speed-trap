package io.github.gdepass.twspeedtrap.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.util.LocaleOverride

/**
 * High-priority "tap to start detection" notification. Tapping a
 * notification is user interaction, which makes a foreground-service start
 * (and its while-in-use location access) eligible from any app state — the
 * degrade path whenever a direct background start is rejected or would be.
 */
internal object TapToStart {
    const val NOTIFICATION_ID = 2

    /** Posts the notification; false when notifications are blocked for the
     * app, in which case the degrade path has silently degraded to nothing
     * and the caller should log it. */
    fun post(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "notifications are blocked — tap-to-start cannot be shown")
            return false
        }
        val localized = LocaleOverride.wrapCached(context)
        val service = Intent(context, DetectionService::class.java)
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
                .setContentTitle(localized.getString(R.string.notif_autostart_title))
                .setContentText(localized.getString(R.string.notif_autostart_text))
                .setContentIntent(start)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private const val TAG = "TapToStart"
    private const val CHANNEL_ID = "autostart"
    private const val REQUEST_CODE = 2
}
