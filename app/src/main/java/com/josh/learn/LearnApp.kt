package com.josh.learn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

class LearnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        Repo.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL, "Daily review reminder", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Reminds you when flashcards are due" })
        Reminders.schedule(this)
    }

    companion object {
        fun prefs(c: Context) = c.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        fun applyTheme(c: Context) {
            AppCompatDelegate.setDefaultNightMode(when (prefs(c).getString("theme", "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
        }
    }
}
