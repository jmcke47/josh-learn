package com.josh.learn

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Daily "cards are due" reminder at a user-chosen time (inexact alarm, no special permission needed). */
object Reminders {
    const val CHANNEL = "review"

    fun enabled(c: Context) = LearnApp.prefs(c).getBoolean("reminder_on", true)
    fun hour(c: Context) = LearnApp.prefs(c).getInt("reminder_hour", 19)
    fun minute(c: Context) = LearnApp.prefs(c).getInt("reminder_minute", 0)

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 1, Intent(c, ReviewReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun nextTrigger(now: Long, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c))
        if (!enabled(c)) return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTrigger(System.currentTimeMillis(), hour(c), minute(c)), pi(c))
    }

    fun notifyIfDue(c: Context) {
        Repo.init(c)
        val due = Repo.due().size
        if (due == 0) return
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return
        val open = PendingIntent.getActivity(c, 2, Intent(c, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_school)
            .setContentTitle("Time to review")
            .setContentText("$due flashcard${if (due == 1) "" else "s"} due. A few minutes keeps them fresh.")
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(c).notify(100, n) }
    }
}

class ReviewReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Reminders.notifyIfDue(context)
        Reminders.schedule(context)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Reminders.schedule(context)
        }
    }
}
