package com.josh.learn

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Observable download state (same process), updated from the service thread, delivered on the main thread. */
object ModelDownloader {
    enum class Status { IDLE, RUNNING, VERIFYING, DONE, PAUSED, FAILED }
    data class State(val model: LocalModel? = null, val status: Status = Status.IDLE, val done: Long = 0, val total: Long = 0,
                     val bytesPerSec: Long = 0, val message: String = "") {
        val percent get() = if (total > 0) (done * 100 / total).toInt() else 0
        val active get() = status == Status.RUNNING || status == Status.VERIFYING
        fun progressText(): String {
            val mb = "%.0f / %.0f MB".format(done / 1e6, total / 1e6)
            val eta = if (bytesPerSec > 0 && total > done) {
                val s = (total - done) / bytesPerSec
                if (s > 3600) " · ~${s / 3600} h ${(s % 3600) / 60} min left" else if (s > 60) " · ~${s / 60} min left" else " · <1 min left"
            } else ""
            val speed = if (bytesPerSec > 0) " · %.1f MB/s".format(bytesPerSec / 1e6) else ""
            return "$percent% · $mb$speed$eta"
        }
    }

    @Volatile var state = State(); private set
    private val listeners = mutableSetOf<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var cancelRequested = false

    fun listen(l: (State) -> Unit) { listeners += l; l(state) }
    fun unlisten(l: (State) -> Unit) { listeners -= l }
    internal fun update(s: State) { state = s; main.post { listeners.toList().forEach { it(s) } } }

    /** Starts (or resumes) downloading [m]. Returns an error message instead if it can't start. */
    fun start(c: Context, m: LocalModel): String? {
        if (state.active) return if (state.model == m) null else "Another model is downloading. Cancel it first."
        if (LocalModels.isInstalled(c, m)) return null
        LocalModels.unsupportedReason(c)?.let { return it }
        if (!LocalModels.hasSpace(c, m)) return "Not enough free storage: need %.1f GB, have %.1f GB.".format(LocalModels.needBytes(c, m) / 1e9, LocalModels.freeBytes(c) / 1e9)
        if (LocalModels.wifiOnly(c) && isMetered(c)) return "You're not on Wi-Fi. Connect to Wi-Fi, or turn off \"Wi-Fi only\" to use mobile data (${m.sizeText})."
        cancelRequested = false
        update(State(m, Status.RUNNING, LocalModels.partialBytes(c, m), m.sizeBytes, message = "Starting…"))
        ContextCompat.startForegroundService(c, Intent(c, ModelDownloadService::class.java).putExtra("model", m.id))
        return null
    }

    /** Stops the download, keeping the partial file so it can resume later. */
    fun pause() { cancelRequested = true }

    fun isMetered(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}

class ModelDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val m = LocalModel.byId(intent?.getStringExtra("model")) ?: run { stopSelf(); return START_NOT_STICKY }
        runCatching {
            val n = notification(m, ModelDownloader.state)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
        } // if the system refuses foreground, keep downloading while the app is open
        if (worker?.isAlive == true) return START_NOT_STICKY
        worker = Thread({ run(m) }, "model-download").apply { start() }
        return START_NOT_STICKY
    }

    private fun notification(m: LocalModel, s: ModelDownloader.State) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading offline AI (${m.short})")
        .setContentText(if (s.status == ModelDownloader.Status.VERIFYING) "Checking the file…" else s.progressText())
        .setProgress(100, s.percent, s.status == ModelDownloader.Status.VERIFYING || s.total == 0L)
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun run(m: LocalModel) {
        val nm = getSystemService(NotificationManager::class.java)
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JoshLearn:model").apply { acquire(4 * 3600_000L) }
        var lastNotif = 0L
        val publish = { s: ModelDownloader.State ->
            ModelDownloader.update(s)
            val now = System.currentTimeMillis()
            if (now - lastNotif > 1000) { lastNotif = now; runCatching { nm.notify(NOTIF_ID, notification(m, s)) } }
        }
        val final = try {
            download(m, publish)
            publish(ModelDownloader.State(m, ModelDownloader.Status.VERIFYING, m.sizeBytes, m.sizeBytes, message = "Checking the file…"))
            verifyAndInstall(m)
            ModelDownloader.State(m, ModelDownloader.Status.DONE, m.sizeBytes, m.sizeBytes, message = "Ready")
        } catch (e: PausedException) {
            ModelDownloader.State(m, ModelDownloader.Status.PAUSED, LocalModels.partialBytes(this, m), m.sizeBytes, message = e.message ?: "Paused")
        } catch (e: Exception) {
            ModelDownloader.State(m, ModelDownloader.Status.FAILED, LocalModels.partialBytes(this, m), m.sizeBytes,
                message = (e.message ?: e.javaClass.simpleName) + if (LocalModels.partialBytes(this, m) > 0) " Tap Resume to continue where it stopped." else "")
        } finally { runCatching { wl.release() } }
        ModelDownloader.update(final)
        if (final.status == ModelDownloader.Status.DONE) LocalModels.select(this, m)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (final.status != ModelDownloader.Status.PAUSED || final.message.contains("Wi-Fi")) runCatching {
            nm.notify(NOTIF_ID + 1, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_school)
                .setContentTitle(if (final.status == ModelDownloader.Status.DONE) "Offline AI is ready" else "Offline AI download stopped")
                .setContentText(if (final.status == ModelDownloader.Status.DONE) "${m.short} downloaded. Josh Learn now works without internet." else final.message)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        stopSelf()
    }

    class PausedException(msg: String) : IOException(msg)

    private fun download(m: LocalModel, publish: (ModelDownloader.State) -> Unit) {
        val part = LocalModels.partFile(this, m)
        var attempt = 0
        while (true) {
            try { downloadOnce(m, part, publish); return }
            catch (e: PausedException) { throw e }
            catch (e: IOException) {
                if (++attempt > 5) throw IOException("Download failed: ${e.message}.")
                publish(ModelDownloader.state.copy(message = "Connection dropped, retrying ($attempt/5)…", bytesPerSec = 0))
                Thread.sleep(3000L * attempt)
            }
        }
    }

    private fun checkPause(m: LocalModel) {
        if (ModelDownloader.cancelRequested) throw PausedException("Paused")
        if (LocalModels.wifiOnly(this) && ModelDownloader.isMetered(this)) throw PausedException("Paused: waiting for Wi-Fi (Wi-Fi only is on). Tap Resume when connected.")
    }

    private fun downloadOnce(m: LocalModel, part: File, publish: (ModelDownloader.State) -> Unit) {
        checkPause(m)
        var have = if (part.exists()) part.length() else 0L
        if (have > m.sizeBytes) { part.delete(); have = 0 }
        if (have == m.sizeBytes) return
        var url = URL(m.url)
        var conn: HttpURLConnection? = null
        for (hop in 0..8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "JoshLearn/${BuildConfig.VERSION_NAME}")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                c.disconnect()
                val next = URL(url, loc)
                if (next.protocol != "https") throw IOException("Refusing insecure redirect")
                url = next; continue
            }
            if (code == 416) { c.disconnect(); if (part.length() == m.sizeBytes) return; part.delete(); throw IOException("Server rejected resume; restarting") }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code from ${url.host}") }
            if (code == 200 && have > 0) { have = 0 } // server ignored Range: start over
            conn = c; break
        }
        val c = conn ?: throw IOException("Too many redirects")
        try {
            FileOutputStream(part, have > 0).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    var winStart = System.currentTimeMillis(); var winBytes = 0L; var speed = 0L; var lastCheck = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n; winBytes += n
                        val now = System.currentTimeMillis()
                        if (now - winStart >= 1000) { speed = winBytes * 1000 / (now - winStart); winStart = now; winBytes = 0 }
                        if (now - lastCheck > 500) {
                            lastCheck = now
                            checkPause(m)
                            publish(ModelDownloader.State(m, ModelDownloader.Status.RUNNING, done, m.sizeBytes, speed, "Downloading…"))
                        }
                    }
                }
            }
        } finally { c.disconnect() }
        if (part.length() < m.sizeBytes) throw IOException("Connection closed early")
    }

    private fun verifyAndInstall(m: LocalModel) {
        val part = LocalModels.partFile(this, m)
        if (part.length() != m.sizeBytes) { part.delete(); throw IOException("Downloaded file has the wrong size.") }
        val md = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(m.sha256, ignoreCase = true)) { part.delete(); throw IOException("Downloaded file is corrupted (checksum mismatch). Please download again.") }
        val dest = LocalModels.file(this, m)
        if (!part.renameTo(dest)) throw IOException("Couldn't save the model file.")
    }

    companion object { const val CHANNEL = "model_download"; const val NOTIF_ID = 200 }
}
