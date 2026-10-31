package com.josh.learn

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v1.4.6: no notifications at all; update check on every open with an Update / Later prompt. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi", application = LearnApp::class)
class UpdatesNotificationsTest {
    private val dir = System.getProperty("screens.dir").orEmpty()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val newer = UpdateController.Manifest(99, "9.9.9", "https://example.invalid/a.apk", "Lessons get symbols; no more notifications.")

    @Before fun seed() {
        LearnApp.prefs(ctx).edit().putBoolean("offline_setup_shown", true).putBoolean(UpdateController.PREF_ON_OPEN, false).remove("theme").commit()
        ctx.getSharedPreferences("updater", 0).edit().clear().commit()
        UpdatePrompt.snoozed.clear()
        LocalModels.abiOverride = true
    }
    @org.junit.After fun tidy() = com.josh.oldschool.OsAnimations.reset()

    private fun idle() { repeat(5) { shadowOf(Looper.getMainLooper()).idle() } }
    private fun waitIo() { Thread.sleep(300); idle() }
    private fun all(v: View): List<View> = listOf(v) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
    private fun texts(v: View) = all(v).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
    private fun prompt(a: Activity) = a.findViewById<FrameLayout>(android.R.id.content).findViewWithTag<View>(UpdatePrompt.TAG)
    private fun snap(a: Activity, name: String) {
        idle(); if (dir.isEmpty()) return
        val v = a.window.decorView; val w = v.width.takeIf { it > 0 } ?: 1179; val h = v.height.takeIf { it > 0 } ?: 2556
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(dir).mkdirs(); File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------------- notifications removed

    @Test fun manifestHasNoNotificationPermissionOrReminderReceivers() {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        val perms = pi.requestedPermissions.orEmpty().toList()
        assertFalse(perms.contains("android.permission.POST_NOTIFICATIONS"))
        assertFalse(perms.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        val receivers = pi.receivers.orEmpty().map { it.name }.filter { it.startsWith("com.josh.learn") }
        assertFalse(receivers.any { it.contains("Reminder") })
        assertEquals(listOf("com.josh.learn.TopicsWidget"), receivers)
    }

    @Test fun upgradeCleanupCancelsOldAlarmChannelAndPrefs() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        // State an older version would have left behind
        nm.createNotificationChannel(NotificationChannel("review", "Daily review reminder", NotificationManager.IMPORTANCE_DEFAULT))
        val old = PendingIntent.getBroadcast(ctx, 1, NoNotifications.oldAlarmIntent(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_600_000, old)
        LearnApp.prefs(ctx).edit().putBoolean("reminder_on", true).putInt("reminder_hour", 19).putBoolean("notif_asked", true).commit()
        assertNotNull(shadowOf(am).nextScheduledAlarm)

        NoNotifications.cleanUp(ctx)

        assertNull(shadowOf(am).nextScheduledAlarm)
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
        assertNull(nm.getNotificationChannel("review"))
        assertTrue(nm.notificationChannels.all { it.id == ModelDownloadService.CHANNEL })
        assertFalse(LearnApp.prefs(ctx).contains("reminder_on"))
        assertFalse(LearnApp.prefs(ctx).contains("notif_asked"))
        NoNotifications.cleanUp(ctx) // idempotent
    }

    @Test fun appStartLeavesOnlyTheDownloadChannelAndNoAlarms() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertNull(nm.getNotificationChannel("review"))
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
        Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
        assertTrue(shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }

    @Test fun settingsHaveNoReminderOptionsButHaveUpdateToggle() {
        for (theme in listOf("dark", "osrs")) {
            LearnApp.prefs(ctx).edit().putString("theme", theme).remove(UpdateController.PREF_ON_OPEN).commit() // default
            val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            val t = texts(s.window.decorView)
            assertFalse(t.any { it.contains("remind", ignoreCase = true) || it.contains("notification", ignoreCase = true) })
            val sw = all(s.window.decorView).filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>()
                .single { it.text.toString() == "Check for updates on open" }
            assertTrue(sw.isChecked) // default on
            sw.isChecked = false; assertFalse(UpdateController.checkOnOpen(s)); sw.isChecked = true; assertTrue(UpdateController.checkOnOpen(s))
            assertTrue(t.contains("Check for updates")) // manual button kept
            val sv = all(s.window.decorView).filterIsInstance<android.widget.ScrollView>().first()
            val head = all(sv).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "Updates" }
            var y = 0; var v: View? = head; while (v != null && v !== sv) { y += v.top; v = v.parent as? View }
            sv.scrollTo(0, y - (8 * s.resources.displayMetrics.density).toInt())
            snap(s, "60-settings-updates-no-notifications-$theme")
            s.finish()
        }
    }

    @Test fun defaultIsOnForUpdateCheck() {
        LearnApp.prefs(ctx).edit().remove(UpdateController.PREF_ON_OPEN).commit()
        assertTrue(UpdateController.checkOnOpen(ctx))
    }

    // ---------------------------------------------------------------- update on open

    private fun main(theme: String = "dark"): MainActivity {
        LearnApp.prefs(ctx).edit().putString("theme", theme).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
    }

    @Test fun autoCheckThrottlesSilentWhenOfflineAndUpToDate() {
        LearnApp.prefs(ctx).edit().putBoolean(UpdateController.PREF_ON_OPEN, true).commit()
        val a = main(); val u = a.updater
        var calls = 0; var now = 1_000_000_000L
        u.clock = { now }; u.fetcher = { calls++; newer }
        var offered: UpdateController.Manifest? = null

        u.online = { false }
        assertFalse(u.autoCheck { offered = it }); assertEquals(0, calls)            // offline: silent, no fetch

        u.online = { true }
        assertTrue(u.autoCheck { offered = it }); waitIo(); assertEquals(1, calls); assertEquals(99L, offered?.versionCode)
        offered = null
        now += 5 * 60_000; assertFalse(u.autoCheck { offered = it }); assertEquals(1, calls) // within 10 min: throttled
        now += 6 * 60_000; assertTrue(u.autoCheck { offered = it }); waitIo(); assertEquals(2, calls)

        offered = null; now += 11 * 60_000
        u.fetcher = { calls++; newer.copy(versionCode = BuildConfig.VERSION_CODE.toLong()) }
        assertTrue(u.autoCheck { offered = it }); waitIo(); assertNull(offered)          // up to date: silent
        now += 11 * 60_000; u.fetcher = { throw java.io.IOException("boom") }
        assertTrue(u.autoCheck { offered = it }); waitIo(); assertNull(offered)          // errors: silent

        LearnApp.prefs(ctx).edit().putBoolean(UpdateController.PREF_ON_OPEN, false).commit()
        now += 11 * 60_000; assertFalse(u.autoCheck { offered = it })                    // toggle off
    }

    @Test fun promptUpdateAndLaterSnoozesForSession() {
        val a = main()
        assertNotNull(a.showUpdatePrompt(newer)); idle()
        val p = prompt(a)!!
        assertTrue(texts(p).contains("Update available: v9.9.9"))
        assertTrue(texts(p).containsAll(listOf("Later", "Update")))
        a.showUpdatePrompt(newer); assertEquals(1, all(a.window.decorView).count { it.tag == UpdatePrompt.TAG }) // never stacks
        all(prompt(a)!!).filterIsInstance<android.widget.Button>().first { it.text == "Later" }.performClick(); idle()
        assertNull(prompt(a))
        assertNull(a.showUpdatePrompt(newer)); assertNull(prompt(a))                   // snoozed this session
        assertNotNull(a.showUpdatePrompt(newer.copy(versionCode = 100, versionName = "10.0.0"))) // a newer one still shows
    }

    @Test fun promptScreens() {
        for (theme in listOf("osrs", "dark")) {
            UpdatePrompt.snoozed.clear()
            val a = main(theme)
            a.showUpdatePrompt(newer); idle()
            assertNotNull(prompt(a))
            snap(a, "60-update-prompt-$theme")
            a.finish()
        }
    }
}
