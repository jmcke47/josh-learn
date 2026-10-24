package com.josh.learn

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import androidx.core.content.edit
import java.io.File

/**
 * On-device models (LiteRT-LM .litertlm, public & ungated on Hugging Face, Apache-2.0).
 * URLs are pinned to a repo revision; sha256 is verified after download.
 * Models live in filesDir/models (app-private, NOT cache) so app updates never remove them.
 * The model catalogue is versioned separately from the app: an app update only needs a new download
 * if it ships a new catalogue entry, and then the user is asked first.
 */
enum class LocalModel(
    val id: String, val label: String, val short: String, val fileName: String, val url: String,
    val sizeBytes: Long, val sha256: String, val license: String, val minRamGb: Int, val blurb: String,
) {
    FAST("qwen3-0.6b", "Fast · Qwen3 0.6B", "Qwen3 0.6B", "Qwen3-0.6B.litertlm",
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm",
        613_722_264L, "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", "Apache-2.0", 3,
        "Small and quick. Works on most phones. Simpler, more error-prone answers."),
    BETTER("gemma-4-e2b", "Better · Gemma 4 E2B", "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1caa527c1e88b55f046fcd8d2b4265606680663d/gemma-4-E2B-it.litertlm",
        2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", "Apache-2.0", 6,
        "Much better lessons and quizzes. Needs a recent phone (6 GB+ RAM) and is slower.");

    val sizeText: String get() = if (sizeBytes >= 1_000_000_000L) "%.1f GB".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} MB"

    companion object {
        /** Bump only when the catalogue changes in a way that needs a new download. */
        const val CATALOG_VERSION = 1
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

object LocalModels {
    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context, m: LocalModel) = File(dir(c), m.fileName)
    fun partFile(c: Context, m: LocalModel) = File(dir(c), m.fileName + ".part")

    fun isInstalled(c: Context, m: LocalModel) = file(c, m).let { it.exists() && it.length() == m.sizeBytes }
    fun installed(c: Context) = LocalModel.entries.filter { isInstalled(c, it) }

    private fun prefs(c: Context) = LearnApp.prefs(c)

    /** Selected model (default: Fast). */
    fun selected(c: Context): LocalModel = LocalModel.byId(prefs(c).getString("local_model", null)) ?: LocalModel.FAST
    fun select(c: Context, m: LocalModel) = prefs(c).edit { putString("local_model", m.id) }

    /** The model actually usable now: the selected one if installed, else any installed one. */
    fun usable(c: Context): LocalModel? = selected(c).takeIf { isInstalled(c, it) } ?: installed(c).firstOrNull()

    fun wifiOnly(c: Context) = prefs(c).getBoolean("model_wifi_only", true)
    fun setWifiOnly(c: Context, v: Boolean) = prefs(c).edit { putBoolean("model_wifi_only", v) }
    fun useGpu(c: Context) = prefs(c).getBoolean("model_gpu", false)
    fun setUseGpu(c: Context, v: Boolean) = prefs(c).edit { putBoolean("model_gpu", v) }

    fun freeBytes(c: Context): Long = runCatching { StatFs(c.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    fun partialBytes(c: Context, m: LocalModel) = partFile(c, m).let { if (it.exists()) it.length() else 0L }
    /** Bytes still needed on disk (+300 MB margin). */
    fun needBytes(c: Context, m: LocalModel) = m.sizeBytes - partialBytes(c, m) + 300_000_000L
    fun hasSpace(c: Context, m: LocalModel) = freeBytes(c) >= needBytes(c, m)

    fun totalRamGb(c: Context): Double = runCatching {
        val mi = ActivityManager.MemoryInfo(); c.getSystemService(ActivityManager::class.java).getMemoryInfo(mi); mi.totalMem / 1e9
    }.getOrNull()?.takeIf { it > 0.1 } ?: 8.0 // unknown -> don't block

    // ---- device support / graceful degradation
    /** Test hook; null = detect. */
    @Volatile var abiOverride: Boolean? = null
    fun abiSupported(): Boolean = abiOverride ?: (android.os.Build.SUPPORTED_ABIS?.contains("arm64-v8a") == true)
    const val MIN_RAM_GB = 2.5
    fun loadCrashed(c: Context) = prefs(c).getBoolean("llm_load_crashed", false)
    fun clearLoadCrashed(c: Context) = prefs(c).edit { putBoolean("llm_load_crashed", false) }

    /** Why Offline AI can't be used on this phone, or null if it can. */
    fun unsupportedReason(c: Context): String? = when {
        !Features.OFFLINE_AI -> "Offline AI isn't available in this version."
        !abiSupported() -> "Offline AI needs a 64-bit ARM phone, and this one isn't. Use Google Gemini (free key) or another online provider instead."
        totalRamGb(c) < MIN_RAM_GB -> "This phone has %.1f GB RAM; Offline AI needs about 3 GB or more. Use Google Gemini (free key) or another online provider instead.".format(totalRamGb(c))
        loadCrashed(c) -> "Offline AI closed unexpectedly while loading last time, so it's switched off to keep the app stable. You can try again, or use an online provider."
        else -> null
    }
    fun supported(c: Context) = unsupportedReason(c) == null

    /** User-initiated only. Also removes any partial download. */
    fun delete(c: Context, m: LocalModel) {
        LocalLlm.release()
        file(c, m).delete(); partFile(c, m).delete()
    }

    /** Default is the small (~0.6 GB) model for every phone; Better (2.6 GB) is an optional upgrade in Settings. */
    @Suppress("UNUSED_PARAMETER") fun recommended(c: Context): LocalModel = LocalModel.FAST
}
