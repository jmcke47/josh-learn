package com.josh.learn

import android.content.Context
import androidx.core.content.edit

/** Cloud providers use OpenAI-compatible Chat Completions, except Claude (Anthropic Messages API). LOCAL runs on-device. */
enum class Provider(val label: String, val short: String, val defaultBaseUrl: String, val defaultModel: String,
                    val keyHint: String, val keyUrl: String) {
    /** On-device model via LiteRT-LM: no key, works offline. */
    LOCAL("Offline (on this phone)", "Offline", "local", "", "No key needed: downloads a model once, then works offline", ""),
    GEMINI("Google Gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash",
        "Free key: aistudio.google.com/app/apikey (starts with AIza)", "https://aistudio.google.com/app/apikey"),
    XAI("xAI Grok", "Grok", "https://api.x.ai/v1", "grok-4.7",
        "Key from console.x.ai (starts with xai-)", "https://console.x.ai"),
    CLAUDE("Anthropic Claude", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-5-5",
        "Key from console.anthropic.com (starts with sk-ant-)", "https://console.anthropic.com/settings/keys"),
    OPENAI("OpenAI ChatGPT", "ChatGPT", "https://api.openai.com/v1", "gpt-5-mini",
        "Key from platform.openai.com (starts with sk-)", "https://platform.openai.com/api-keys"),
    CUSTOM("Custom (OpenAI-compatible)", "Custom", "", "",
        "Any server with an OpenAI-compatible /chat/completions endpoint", "");

    /** Suggested models shown in Settings (user can type anything). */
    val suggestions: List<String> get() = when (this) {
        GEMINI -> listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-2.5-flash")
        XAI -> listOf("grok-4.7", "latest")
        OPENAI -> listOf("gpt-5-mini", "gpt-5", "gpt-5-nano")
        CLAUDE -> listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")
        CUSTOM, LOCAL -> emptyList()
    }
}

data class ProviderConfig(val provider: Provider, val baseUrl: String, val model: String, val apiKey: String)

/** Per-provider settings (each with its own encrypted key + model) and the active provider. */
class ProviderStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("providers", Context.MODE_PRIVATE)
    private val secrets = SecretBox(context)

    var active: Provider
        get() = runCatching { Provider.valueOf(prefs.getString("active", Provider.LOCAL.name)!!) }.getOrDefault(Provider.LOCAL)
        set(v) = prefs.edit { putString("active", v.name) }

    fun model(p: Provider): String = if (p == Provider.LOCAL) (LocalModels.usable(ctx) ?: LocalModels.selected(ctx)).short else prefs.getString("model_${p.name}", null)?.trim()?.ifEmpty { null } ?: p.defaultModel
    fun setModel(p: Provider, m: String) = prefs.edit { putString("model_${p.name}", m.trim()) }

    fun baseUrl(p: Provider): String =
        (if (p == Provider.CUSTOM) prefs.getString("base_CUSTOM", "") ?: "" else p.defaultBaseUrl).trim().trimEnd('/')
    fun setBaseUrl(p: Provider, url: String) { if (p == Provider.CUSTOM) prefs.edit { putString("base_CUSTOM", url.trim()) } }

    fun key(p: Provider): String? = secrets.get("key_${p.name}")
    fun setKey(p: Provider, key: String?) = secrets.put("key_${p.name}", key?.trim()?.replace(Regex("\\s"), ""))
    fun hasKey(p: Provider) = !key(p).isNullOrEmpty()

    fun isReady(p: Provider) = if (p == Provider.LOCAL) LocalModels.usable(ctx) != null else hasKey(p) && model(p).isNotEmpty() && baseUrl(p).startsWith("http")

    /** Providers that are fully set up, active one first. */
    fun ready(): List<Provider> = Provider.entries.filter { isReady(it) }.sortedBy { if (it == active) 0 else 1 }

    fun config(p: Provider = active): ProviderConfig? {
        if (!isReady(p)) return null
        if (p == Provider.LOCAL) return ProviderConfig(p, "local", LocalModels.usable(ctx)!!.id, "")
        return ProviderConfig(p, baseUrl(p), model(p), key(p)!!)
    }

    /** Last 4 chars only, for display. */
    fun maskedKey(p: Provider): String? = key(p)?.let { if (it.length > 8) "••••" + it.takeLast(4) else "••••" }
}
