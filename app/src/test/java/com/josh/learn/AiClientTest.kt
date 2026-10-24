package com.josh.learn

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class AiClientTest {
    private lateinit var server: HttpServer
    private var status = 200
    private var contentType = "text/event-stream"
    private var body = ""
    private var headers = mapOf<String, String>()
    private var lastAuth: String? = null
    private var lastRequest = ""
    private var lastPath = ""
    private var lastHeaders = mapOf<String, String>()
    private val statusQueue = ArrayDeque<Int>()
    private val models = mutableListOf<String>()
    private var calls = 0
    private val slept = mutableListOf<Long>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { ex ->
            lastPath = ex.requestURI.path
            lastHeaders = ex.requestHeaders.mapValues { it.value.firstOrNull() ?: "" }.mapKeys { it.key.lowercase() }
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastRequest = ex.requestBody.bufferedReader().readText()
            calls++
            Regex("\"model\":\"([^\"]+)\"").find(lastRequest)?.let { models += it.groupValues[1] }
            val st = statusQueue.removeFirstOrNull() ?: status
            if (st != 200) {
                val eb = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                ex.sendResponseHeaders(st, eb.size.toLong()); ex.responseBody.use { it.write(eb) }; return@createContext
            }
            ex.responseHeaders.add("Content-Type", contentType)
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val b = body.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        AiClient.sleeper = { slept += it }
    }
    @After fun stop() = server.stop(0)

    private fun cfg(p: Provider = Provider.GEMINI) = ProviderConfig(p, "http://127.0.0.1:${server.address.port}/v1", "gemini-3.8-flash", "AIzaTESTKEY123")

    @Test fun streamsDeltas() {
        body = listOf("""{"choices":[{"delta":{"role":"assistant"}}]}""", """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""", "[DONE]").joinToString("\n\n") { "data: $it" } + "\n\n"
        val parts = mutableListOf<String>()
        val out = AiClient.complete(cfg(), listOf(Msg("user", "hi")), { parts += it })
        assertEquals("Hello", out); assertEquals(listOf("Hel", "lo"), parts)
        assertEquals("Bearer AIzaTESTKEY123", lastAuth)
        assertTrue(lastRequest.contains("\"stream\":true")); assertTrue(lastRequest.contains("gemini-3.8-flash"))
    }

    @Test fun nonStreamingFallback() {
        contentType = "application/json"
        body = """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""
        assertEquals("OK", AiClient.complete(cfg(), listOf(Msg("user", "hi")), { }))
        assertEquals("OK", AiClient.complete(cfg(), listOf(Msg("user", "hi"))))
        assertFalse(lastRequest.contains("stream"))
    }

    @Test fun geminiRateLimitWithRetryDelay() {
        status = 429; contentType = "application/json"
        body = """[{"error":{"code":429,"message":"You exceeded your current quota. Please retry in 37.2s.","status":"RESOURCE_EXHAUSTED",
            "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}]"""
        try { AiClient.complete(cfg(), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMIT, e.kind); assertEquals(37, e.retryAfterSec)
            assertTrue(e.message!!.contains("free tier")); assertFalse(e.message!!.contains("AIzaTESTKEY123"))
        }
    }

    @Test fun geminiDailyQuota() {
        status = 429; contentType = "application/json"
        body = """{"error":{"code":429,"message":"Quota exceeded for metric: generate_content_free_tier_requests, quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier"}}"""
        try { AiClient.complete(cfg(), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) {
            assertEquals(AiException.Kind.QUOTA, e.kind); assertTrue(e.providerProblem)
        }
    }

    @Test fun badKeyAndModel() {
        status = 400; contentType = "application/json"
        body = """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]"""
        try { AiClient.complete(cfg(), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.AUTH, e.kind) }
        status = 404; body = """{"error":{"message":"models/foo is not found"}}"""
        try { AiClient.complete(cfg(), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.BAD_MODEL, e.kind) }
        status = 401; body = """{"error":{"message":"Incorrect API key"}}"""
        try { AiClient.complete(cfg(Provider.XAI), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind); assertTrue(e.message!!.startsWith("Your Grok key isn't working"))
        }
    }

    @Test fun retryAfterHeader() {
        status = 429; contentType = "application/json"; headers = mapOf("Retry-After" to "12")
        body = """{"error":{"message":"Rate limit reached"}}"""
        try { AiClient.complete(cfg(Provider.OPENAI), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMIT, e.kind); assertEquals(12, e.retryAfterSec)
        }
    }

    @Test fun claudeStreamingAndHeaders() {
        body = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi \"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Josh\"}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}").joinToString("\n\n") + "\n\n"
        val cfg = ProviderConfig(Provider.CLAUDE, "http://127.0.0.1:${server.address.port}/v1", "claude-sonnet-5-5", "sk-ant-TEST")
        val out = AiClient.complete(cfg, listOf(Msg("system", "be nice"), Msg("assistant", "earlier"), Msg("user", "a"), Msg("user", "b")), { })
        assertEquals("Hi Josh", out)
        assertEquals("/v1/messages", lastPath)
        assertEquals("sk-ant-TEST", lastHeaders["x-api-key"]); assertEquals(AiClient.ANTHROPIC_VERSION, lastHeaders["anthropic-version"])
        assertEquals(null, lastHeaders["authorization"])
        val req = org.json.JSONObject(lastRequest)
        assertEquals("be nice", req.getString("system")); assertTrue(req.getInt("max_tokens") > 0)
        val msgs = req.getJSONArray("messages")
        assertEquals("user", msgs.getJSONObject(0).getString("role")) // leading assistant turn gets a user turn before it
        assertEquals("a\n\nb", msgs.getJSONObject(msgs.length() - 1).getString("content")) // consecutive user turns merged
    }

    @Test fun claudeNonStreamingAndErrors() {
        contentType = "application/json"
        body = """{"content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"OK"}]}"""
        val cfg = ProviderConfig(Provider.CLAUDE, "http://127.0.0.1:${server.address.port}/v1", "claude-sonnet-5-5", "sk-ant-TEST")
        assertEquals("OK", AiClient.complete(cfg, listOf(Msg("user", "hi"))))
        status = 529; body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        try { AiClient.complete(cfg, listOf(Msg("user", "hi"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.RATE_LIMIT, e.kind) }
        status = 400; body = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""
        try { AiClient.complete(cfg, listOf(Msg("user", "hi"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.QUOTA, e.kind) }
    }

    @Test fun localWithoutBackendIsClearError() {
        AiClient.localBackend = null
        try { AiClient.complete(ProviderConfig(Provider.LOCAL, "local", "qwen3-0.6b", ""), listOf(Msg("user", "hi"))); fail() }
        catch (e: AiException) { assertEquals(AiException.Kind.LOCAL, e.kind) }
        AiClient.localBackend = { _, m, d, _ -> d?.invoke("x"); "echo " + m.last().content }
        assertEquals("echo hi", AiClient.complete(ProviderConfig(Provider.LOCAL, "local", "qwen3-0.6b", ""), listOf(Msg("user", "hi"))))
        AiClient.localBackend = null
    }

    @Test fun sseLineParsing() {
        assertEquals(AiClient.DONE, AiClient.parseSseLine("data: [DONE]"))
        assertEquals(null, AiClient.parseSseLine(": keep-alive"))
        assertEquals("", AiClient.parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":null}}]}"))
        assertEquals("x", AiClient.parseSseLine("data:{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
    }

    private val okStream = listOf("""{"choices":[{"delta":{"content":"OK"}}]}""", "[DONE]").joinToString("\n\n") { "data: $it" } + "\n\n"

    @Test fun neverCallsWithoutKey() {
        val r = runCatching { AiClient.complete(cfg().copy(apiKey = "  "), listOf(Msg("user", "hi"))) }
        assertEquals(AiException.Kind.NO_KEY, (r.exceptionOrNull() as AiException).kind)
        assertEquals(0, calls)
    }

    @Test fun retries503WithBackoffThenSucceeds() {
        statusQueue.addAll(listOf(503, 503)); body = okStream
        val status = mutableListOf<String>()
        val out = AiClient.complete(cfg(), listOf(Msg("user", "hi")), { if (it.startsWith(AiClient.STATUS)) status += it.substring(1) })
        assertEquals("OK", out); assertEquals(3, calls)
        assertEquals(4_000L, slept.sum()) // 1 s + 3 s
        assertTrue(status.all { it == "Gemini is busy, retrying…" })
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.8-flash"), models)
    }

    @Test fun fallsBackToLighterGeminiModel() {
        statusQueue.addAll(listOf(503, 503, 503, 503)); body = okStream
        val out = AiClient.complete(cfg(), listOf(Msg("user", "hi")), {})
        assertEquals("OK", out)
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"), models)
        assertEquals(11_000L, slept.sum()) // 1 + 3 + 7 s
    }

    @Test fun throwsTransientAfterEverythingFails() {
        status = 503; body = """{"error":{"message":"overloaded"}}"""
        val e = runCatching { AiClient.complete(cfg(), listOf(Msg("user", "hi"))) }.exceptionOrNull() as AiException
        assertTrue(e.transient); assertEquals(503, e.httpCode)
        assertEquals(4 + 2 + 2, calls) // main model x4, each lighter model x2
    }

    @Test fun badKeyIsNotRetried() {
        for ((code, err) in listOf(400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""",
                                   401 to """{"error":{"message":"unauthorized"}}""", 403 to """{"error":{"message":"forbidden"}}""")) {
            calls = 0; status = code; body = err
            val e = runCatching { AiClient.complete(cfg(), listOf(Msg("user", "hi"))) }.exceptionOrNull() as AiException
            assertEquals(AiException.Kind.AUTH, e.kind); assertFalse(e.transient)
            assertTrue(e.message!!.startsWith("Your Gemini key isn't working"))
            assertEquals(1, calls)
        }
    }

    @Test fun nonGeminiProvidersRetryButDontSwapModels() {
        statusQueue.addAll(listOf(502)); body = okStream
        assertEquals("OK", AiClient.complete(cfg(Provider.XAI).copy(model = "grok-4.7"), listOf(Msg("user", "hi")), {}))
        assertEquals(listOf("grok-4.7", "grok-4.7"), models)
        assertTrue(AiClient.fallbackModels(cfg(Provider.XAI)).isEmpty())
    }
}
