package com.jarvis.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Restores the user-visible JARVIS state after reboot without trying to
 * start microphone capture from the background.
 *
 * Android restricts microphone foreground services from BOOT_COMPLETED,
 * so the receiver posts a notification. Tapping it opens MainActivity,
 * whose normal lifecycle starts the existing voice listener.
 */
class JarvisBootReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "jarvis_boot"
        private const val NOTIFICATION_ID = 702
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "JARVIS",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            702,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.jarvis_icon)
            .setContentTitle("J.A.R.V.I.S. готов")
            .setContentText("Нажмите, чтобы открыть JARVIS и восстановить голосовое управление")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        manager.notify(NOTIFICATION_ID, notification)
    }
}
