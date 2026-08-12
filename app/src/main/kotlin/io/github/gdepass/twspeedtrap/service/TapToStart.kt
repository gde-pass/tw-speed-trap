package io.github.gdepass.twspeedtrap.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.gdepass.twspeedtrap.R

/**
 * High-priority "tap to start detection" notification. Tapping a
 * notification is user interaction, which makes a foreground-service start
 * (and its while-in-use location access) eligible from any app state — the
 * degrade path whenever a direct background start is rejected or would be.
 */
internal object TapToStart {
    fun post(context: Context) {
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
                .setContentTitle(context.getString(R.string.notif_autostart_title))
                .setContentText(context.getString(R.string.notif_autostart_text))
                .setContentIntent(start)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "autostart"
    private const val NOTIFICATION_ID = 2
    private const val REQUEST_CODE = 2
}
