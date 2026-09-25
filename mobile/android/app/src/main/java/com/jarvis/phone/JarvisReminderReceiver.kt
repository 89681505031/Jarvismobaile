package com.jarvis.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat

class JarvisReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra("id", -1) ?: return
        if (id < 0) return
        val text = intent.getStringExtra("text").orEmpty().take(180)
        JarvisReminders(context).completed(id)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(NotificationManager::class.java) ?: return
        notifications.createNotificationChannel(NotificationChannel(
            "jarvis_reminders", "Напоминания JARVIS", NotificationManager.IMPORTANCE_HIGH
        ))
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notifications.notify(id, NotificationCompat.Builder(context, "jarvis_reminders")
            .setSmallIcon(R.drawable.jarvis_icon).setContentTitle("J.A.R.V.I.S. • напоминание")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
