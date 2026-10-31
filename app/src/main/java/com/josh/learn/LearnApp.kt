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
        DynamicColors.applyToActivitiesIfAvailable(this, com.google.android.material.color.DynamicColorsOptions.Builder()
            .setPrecondition { a, _ -> !Display.isCustom(a) }.build())
        Repo.init(this)
        Topics.init(this)
        runCatching { Symbols.migrate(this) } // v1.4.5: replace the default ⭐ on saved lessons and his own topics
        NoNotifications.cleanUp(this) // v1.4.6: no reminders any more; cancel old alarms and delete the old channel
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ModelDownloadService.CHANNEL, "Offline AI download", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown only while the offline AI model downloads (Android requires it)" })
        LocalLlm.install(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_COMPLETE || level == TRIM_MEMORY_RUNNING_CRITICAL) LocalLlm.release()
    }

    companion object {
        fun prefs(c: Context) = c.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        fun applyTheme(c: Context) {
            AppCompatDelegate.setDefaultNightMode(when (prefs(c).getString("theme", "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark", "amoled", "osrs" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            })
        }
    }
}
