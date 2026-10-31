package com.josh.learn

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * v1.4.6: Josh Learn sends no notifications. Clean up what older versions left behind so nothing fires after updating:
 * the daily review alarm (AlarmManager, request code 1), any posted review reminder (id 100), the old download
 * "ready/stopped" notice (id 201) and the reminder channel. Idempotent; runs on every app start.
 * The only channel kept is [ModelDownloadService.CHANNEL]: Android requires a visible notification for the
 * foreground service that downloads the offline AI model, and it is removed as soon as the download ends.
 */
object NoNotifications {
    const val OLD_REMINDER_CHANNEL = "review"
    const val OLD_RECEIVER = "com.josh.learn.ReviewReminderReceiver"
    val OLD_PREFS = listOf("reminder_on", "reminder_hour", "reminder_minute", "notif_asked")

    fun oldAlarmIntent(c: Context) = Intent().setClassName(c.packageName, OLD_RECEIVER)

    fun cleanUp(c: Context) {
        runCatching {
            val am = c.getSystemService(AlarmManager::class.java)
            // Same request code + component as 1.0–1.4.5's PendingIntent, so this matches and cancels the old alarm.
            val pi = PendingIntent.getBroadcast(c, 1, oldAlarmIntent(c), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.cancel(pi); pi.cancel()
        }
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.cancel(100); nm.cancel(ModelDownloadService.NOTIF_ID + 1)
            nm.deleteNotificationChannel(OLD_REMINDER_CHANNEL)
        }
        val p = LearnApp.prefs(c)
        if (OLD_PREFS.any { p.contains(it) }) p.edit().apply { OLD_PREFS.forEach { remove(it) } }.apply()
    }
}
