package com.josh.learn

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class Msg(val role: String, val content: String)

/** Typed failure so the UI can show a clear message and offer "Retry with <other provider>". */
class AiException(val kind: Kind, message: String, val httpCode: Int = 0, val retryAfterSec: Int = 0, cause: Throwable? = null) :
    IOException(message, cause) {
    enum class Kind { NO_KEY, AUTH, RATE_LIMIT, QUOTA, BAD_MODEL, BAD_REQUEST, SERVER, NETWORK, PARSE, CANCELLED, LOCAL, OTHER }
    /** Worth trying another provider for. */
    val providerProblem get() = kind in setOf(Kind.AUTH, Kind.RATE_LIMIT, Kind.QUOTA, Kind.BAD_MODEL, Kind.SERVER, Kind.NO_KEY, Kind.LOCAL)
    /** Temporary server-side trouble (429/5xx/timeout): worth retrying, a lighter model, or the offline model. Never key/auth problems. */
    val transient get() = kind != Kind.AUTH && kind != Kind.NO_KEY && kind != Kind.CANCELLED &&
        (httpCode in AiClient.RETRY_CODES || kind == Kind.SERVER || kind == Kind.QUOTA || kind == Kind.RATE_LIMIT || cause is java.net.SocketTimeoutException)
}

/**
 * Chat client for OpenAI-compatible Chat Completions (Gemini /v1beta/openai, xAI, OpenAI, custom) and the
 * Anthropic Messages API (Claude). LOCAL is delegated to [localBackend] (set by the app; on-device model).
 * Pure java.net + org.json so it's unit-testable. Never logs or echoes the API key. Call off the main thread.
 * Deltas starting with [STATUS] are progress messages, not model text.
 */
object AiClient {
    var userAgent = "JoshLearn"
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val STATUS = "\u0001"

    /** On-device generation hook (LocalLlm), installed by LearnApp. */
    @Volatile var localBackend: ((ProviderConfig, List<Msg>, ((String) -> Unit)?, () -> Boolean) -> String)? = null

    val RETRY_CODES = setOf(429, 500, 502, 503, 504, 529)
    /** Backoff between retries of the same model (ms). */
    var retryDelaysMs = listOf(1_000L, 3_000L, 7_000L)
    /** Model that actually answered the last cloud call on this thread (may be a lighter fallback). */
    val lastModel = ThreadLocal<String?>()
    /** Test hook. */
    var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    /** Lighter Gemini models to try when the chosen one keeps failing with 429/5xx (current stable ids, Oct 2026). */
    val GEMINI_FALLBACKS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    fun fallbackModels(cfg: ProviderConfig): List<String> =
        if (cfg.provider == Provider.GEMINI) GEMINI_FALLBACKS.filter { !it.equals(cfg.model, ignoreCase = true) } else emptyList()

    private fun pause(ms: Long, isCancelled: () -> Boolean) {
        var left = ms
        while (left > 0) { if (isCancelled()) throw AiException(AiException.Kind.CANCELLED, "Cancelled"); val s = minOf(left, 100L); sleeper(s); left -= s }
    }

    /**
     * Cloud: retries transient errors (429/5xx/timeouts) with backoff, then (Gemini) falls back to lighter models.
     * Throws the last error if everything fails. Key/auth/bad-request errors are never retried.
     */
    fun complete(cfg: ProviderConfig, messages: List<Msg>, onDelta: ((String) -> Unit)? = null,
                 isCancelled: () -> Boolean = { false }): String {
        if (cfg.provider == Provider.LOCAL) {
            val b = localBackend ?: throw AiException(AiException.Kind.LOCAL, "Offline AI isn't available on this device.")
            return b(cfg, messages, onDelta, isCancelled)
        }
        if (cfg.apiKey.isBlank()) throw AiException(AiException.Kind.NO_KEY, "Add your ${cfg.provider.short} key in Settings first.") // never call without a key
        var last: AiException? = null
        for ((i, model) in (listOf(cfg.model) + fallbackModels(cfg)).withIndex()) {
            val c = cfg.copy(model = model)
            if (i > 0) onDelta?.invoke(STATUS + "${cfg.provider.short} is busy, trying the lighter $model…")
            val delays = if (i == 0) retryDelaysMs else retryDelaysMs.take(1)
            for (attempt in 0..delays.size) {
                try { return completeOnce(c, messages, onDelta, isCancelled).also { lastModel.set(model) } }
                catch (e: AiException) {
                    last = e
                    if (e.kind == AiException.Kind.BAD_MODEL && i > 0) break          // fallback id not available: next one
                    if (!e.transient) throw e
                    if (e.kind == AiException.Kind.QUOTA) break                       // daily quota: retrying won't help, try another model
                    if (attempt == delays.size) break
                    val wait = maxOf(delays[attempt], if (e.retryAfterSec in 1..10) e.retryAfterSec * 1000L else 0L)
                    onDelta?.invoke(STATUS + "${cfg.provider.short} is busy, retrying…")
                    pause(wait, isCancelled)
                }
            }
        }
        throw last ?: AiException(AiException.Kind.OTHER, "Something went wrong.")
    }

    private fun completeOnce(cfg: ProviderConfig, messages: List<Msg>, onDelta: ((String) -> Unit)?, isCancelled: () -> Boolean): String {
        val claude = cfg.provider == Provider.CLAUDE
        val stream = onDelta != null
        val body = if (claude) JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", 4096)
            val sys = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
            if (sys.isNotEmpty()) put("system", sys)
            put("messages", JSONArray().apply {
                // Claude needs alternating user/assistant turns starting with user: merge consecutive same-role turns.
                val merged = mutableListOf<Msg>()
                messages.filter { it.role != "system" }.forEach { m ->
                    val role = if (m.role == "assistant") "assistant" else "user"
                    if (merged.isNotEmpty() && merged.last().role == role) merged[merged.size - 1] = Msg(role, merged.last().content + "\n\n" + m.content)
                    else merged += Msg(role, m.content)
                }
                if (merged.firstOrNull()?.role == "assistant") merged.add(0, Msg("user", "(continue)"))
                merged.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            if (stream) put("stream", true)
        } else JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
            if (stream) put("stream", true)
        }
        val endpoint = cfg.baseUrl.trimEnd('/') + if (claude) "/messages" else "/chat/completions"
        val conn = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
                if (claude) {
                    setRequestProperty("x-api-key", cfg.apiKey)
                    setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                } else setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                setRequestProperty("User-Agent", userAgent)
            }
        } catch (e: Exception) { throw AiException(AiException.Kind.BAD_REQUEST, "Invalid server address", cause = e) }
        try {
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                    throw httpError(cfg.provider, code, err, conn.getHeaderField("Retry-After"))
                }
                val type = conn.contentType ?: ""
                val sb = StringBuilder()
                conn.inputStream.bufferedReader().use { r ->
                    if (stream && type.contains("event-stream")) {
                        while (true) {
                            if (isCancelled()) throw AiException(AiException.Kind.CANCELLED, "Cancelled")
                            val line = r.readLine() ?: break
                            val ev = (if (claude) parseAnthropicSseLine(line) else parseSseLine(line)) ?: continue
                            if (ev == DONE) break
                            if (ev.isNotEmpty()) { sb.append(ev); onDelta!!(ev) }
                        }
                    } else {
                        val raw = r.readText()
                        val text = if (claude) parseAnthropic(raw) else parseCompletion(raw)
                        sb.append(text); onDelta?.invoke(text)
                    }
                }
                if (sb.isBlank()) throw AiException(AiException.Kind.PARSE, "The AI returned an empty response. Try again.")
                return sb.toString()
            } finally { conn.disconnect() }
        } catch (e: AiException) { throw e
        } catch (e: UnknownHostException) { throw AiException(AiException.Kind.NETWORK, "Couldn't reach ${cfg.provider.short} (no connection or DNS failed). The Offline AI works without internet.", cause = e)
        } catch (e: SocketTimeoutException) { throw AiException(AiException.Kind.NETWORK, "${cfg.provider.short} took too long to respond.", cause = e)
        } catch (e: javax.net.ssl.SSLException) { throw AiException(AiException.Kind.NETWORK, "Secure connection failed. Check the phone's date & time.", cause = e)
        } catch (e: IOException) { throw AiException(AiException.Kind.NETWORK, "Network error talking to ${cfg.provider.short}: ${e.message}", cause = e)
        } catch (e: org.json.JSONException) { throw AiException(AiException.Kind.PARSE, "Unexpected response from ${cfg.provider.short}.", cause = e) }
    }

    const val DONE = "\u0000DONE"

    /** OpenAI-style SSE: delta text, "" for non-content events, [DONE] at the end, or null to skip. */
    fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        if (data == "[DONE]") return DONE
        return try {
            val o = JSONObject(data)
            o.optJSONObject("error")?.let { throw AiException(AiException.Kind.SERVER, it.optString("message", "Stream error")) }
            val ch = o.optJSONArray("choices")?.optJSONObject(0) ?: return ""
            val d = ch.optJSONObject("delta") ?: ch.optJSONObject("message") ?: return ""
            if (d.isNull("content")) "" else d.optString("content", "")
        } catch (_: org.json.JSONException) { null }
    }

    /** Anthropic SSE: text_delta text; thinking/other events ""; message_stop -> DONE; error event -> exception. */
    fun parseAnthropicSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty()) return null
        return try {
            val o = JSONObject(data)
            when (o.optString("type")) {
                "content_block_delta" -> o.optJSONObject("delta")?.let { d -> if (d.optString("type") == "text_delta") d.optString("text") else "" } ?: ""
                "message_stop" -> DONE
                "error" -> {
                    val e = o.optJSONObject("error")
                    val t = e?.optString("type") ?: ""
                    throw AiException(if (t == "overloaded_error" || t == "rate_limit_error") AiException.Kind.RATE_LIMIT else AiException.Kind.SERVER,
                        "Claude: " + (e?.optString("message") ?: "stream error"))
                }
                else -> ""
            }
        } catch (_: org.json.JSONException) { null }
    }

    fun parseCompletion(body: String): String {
        val o = JSONObject(body)
        val m = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (m.isNull("content")) "" else m.optString("content", "")
    }

    fun parseAnthropic(body: String): String =
        JSONObject(body).optJSONArray("content").objs().filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }

    /** Pulls a readable message out of OpenAI-, Gemini- or Anthropic-style error bodies. */
    fun errorMessage(body: String): String {
        val t = body.trim()
        return runCatching {
            val o = if (t.startsWith("[")) JSONArray(t).getJSONObject(0) else JSONObject(t)
            val e = o.opt("error")
            when (e) { is JSONObject -> e.optString("message"); is String -> e; else -> o.optString("message") }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: t.take(200)
    }

    fun retryDelaySeconds(body: String, header: String?): Int {
        header?.trim()?.toIntOrNull()?.let { return it }
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(body)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)retry in (\\d+)(?:\\.\\d+)?\\s*s").find(body)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    fun httpError(p: Provider, code: Int, body: String, retryAfter: String?): AiException {
        val msg = errorMessage(body)
        val low = (msg + " " + body).lowercase()
        val wait = retryDelaySeconds(body, retryAfter)
        return when {
            code == 401 || code == 403 || (code == 400 && (low.contains("api key") || low.contains("api_key") || low.contains("api_key_invalid"))) ->
                AiException(AiException.Kind.AUTH, "Your ${p.short} key isn't working. Check it in Settings, or create a new one" +
                    (if (p == Provider.GEMINI) " at aistudio.google.com/apikey" else "") + ". ($msg)", code)
            (code == 429 || code == 400 || code == 402) && (low.contains("insufficient_quota") || low.contains("billing") || low.contains("per day") ||
                low.contains("perday") || low.contains("credits") || low.contains("credit balance")) ->
                AiException(AiException.Kind.QUOTA, "${p.short} quota used up" +
                    (if (p == Provider.GEMINI) " (free-tier daily limit). It resets daily, or switch to another model/provider." else ". Check your plan or billing."), code, wait)
            code == 429 || code == 529 -> AiException(AiException.Kind.RATE_LIMIT,
                "${p.short} " + (if (code == 529) "is overloaded" else "rate limit hit") + (if (p == Provider.GEMINI) " (free tier allows only a few requests per minute)" else "") +
                    ". " + (if (wait > 0) "Wait about $wait s and retry." else "Wait a minute and retry."), code, wait)
            code == 404 || (code == 400 && low.contains("model")) ->
                AiException(AiException.Kind.BAD_MODEL, "${p.short} doesn't recognise that model. Pick another in Settings. ($msg)", code)
            code >= 500 -> AiException(AiException.Kind.SERVER, "${p.short} is having trouble (HTTP $code). Try again shortly.", code, wait)
            else -> AiException(AiException.Kind.BAD_REQUEST, "${p.short} error (HTTP $code): $msg", code)
        }
    }
}
