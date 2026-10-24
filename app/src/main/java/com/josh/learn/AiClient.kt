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
    enum class Kind { NO_KEY, AUTH, RATE_LIMIT, QUOTA, BAD_MODEL, BAD_REQUEST, SERVER, NETWORK, PARSE, CANCELLED, OTHER }
    /** Worth trying another provider for. */
    val providerProblem get() = kind in setOf(Kind.AUTH, Kind.RATE_LIMIT, Kind.QUOTA, Kind.BAD_MODEL, Kind.SERVER, Kind.NO_KEY)
}

/**
 * Minimal OpenAI-compatible Chat Completions client (works for Gemini's /v1beta/openai, xAI, OpenAI, custom).
 * Pure java.net + org.json so it's unit-testable. Never logs or echoes the API key. Call off the main thread.
 */
object AiClient {
    var userAgent = "JoshLearn"

    fun complete(cfg: ProviderConfig, messages: List<Msg>, onDelta: ((String) -> Unit)? = null,
                 isCancelled: () -> Boolean = { false }): String {
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
            if (onDelta != null) put("stream", true)
        }
        val conn = try {
            (URL(cfg.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", if (onDelta != null) "text/event-stream" else "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
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
                    if (onDelta != null && type.contains("event-stream")) {
                        while (true) {
                            if (isCancelled()) throw AiException(AiException.Kind.CANCELLED, "Cancelled")
                            val line = r.readLine() ?: break
                            val ev = parseSseLine(line) ?: continue
                            if (ev == DONE) break
                            if (ev.isNotEmpty()) { sb.append(ev); onDelta(ev) }
                        }
                    } else {
                        val text = parseCompletion(r.readText())
                        sb.append(text); onDelta?.invoke(text)
                    }
                }
                if (sb.isBlank()) throw AiException(AiException.Kind.PARSE, "The AI returned an empty response. Try again.")
                return sb.toString()
            } finally { conn.disconnect() }
        } catch (e: AiException) { throw e
        } catch (e: UnknownHostException) { throw AiException(AiException.Kind.NETWORK, "Couldn't reach ${cfg.provider.short} (no connection or DNS failed).", cause = e)
        } catch (e: SocketTimeoutException) { throw AiException(AiException.Kind.NETWORK, "${cfg.provider.short} took too long to respond.", cause = e)
        } catch (e: javax.net.ssl.SSLException) { throw AiException(AiException.Kind.NETWORK, "Secure connection failed. Check the phone's date & time.", cause = e)
        } catch (e: IOException) { throw AiException(AiException.Kind.NETWORK, "Network error talking to ${cfg.provider.short}: ${e.message}", cause = e)
        } catch (e: org.json.JSONException) { throw AiException(AiException.Kind.PARSE, "Unexpected response from ${cfg.provider.short}.", cause = e) }
    }

    const val DONE = "\u0000DONE"

    /** Returns delta text for an SSE line, "" for non-content events, [DONE] at the end, or null to skip. */
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

    fun parseCompletion(body: String): String {
        val o = JSONObject(body)
        val m = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return if (m.isNull("content")) "" else m.optString("content", "")
    }

    /** Pulls a readable message out of OpenAI-style or Gemini-style error bodies. */
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
            code == 401 || code == 403 || (code == 400 && (low.contains("api key") || low.contains("api_key"))) ->
                AiException(AiException.Kind.AUTH, "${p.short} rejected the API key. Check it in Settings. ($msg)", code)
            code == 429 && (low.contains("insufficient_quota") || low.contains("billing") || low.contains("per day") || low.contains("perday") || low.contains("credits")) ->
                AiException(AiException.Kind.QUOTA, "${p.short} quota used up" +
                    (if (p == Provider.GEMINI) " (free-tier daily limit). It resets daily, or switch to another model/provider." else ". Check your plan or billing.") , code, wait)
            code == 429 -> AiException(AiException.Kind.RATE_LIMIT,
                "${p.short} rate limit hit" + (if (p == Provider.GEMINI) " (free tier allows only a few requests per minute)" else "") +
                    ". " + (if (wait > 0) "Wait about $wait s and retry." else "Wait a minute and retry."), code, wait)
            code == 404 || (code == 400 && low.contains("model")) ->
                AiException(AiException.Kind.BAD_MODEL, "${p.short} doesn't recognise that model. Pick another in Settings. ($msg)", code)
            code >= 500 -> AiException(AiException.Kind.SERVER, "${p.short} is having trouble (HTTP $code). Try again shortly.", code, wait)
            else -> AiException(AiException.Kind.BAD_REQUEST, "${p.short} error (HTTP $code): $msg", code)
        }
    }
}
