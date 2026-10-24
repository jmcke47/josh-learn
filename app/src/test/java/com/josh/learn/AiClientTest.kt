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

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            lastAuth = ex.requestHeaders.getFirst("Authorization")
            lastRequest = ex.requestBody.bufferedReader().readText()
            ex.responseHeaders.add("Content-Type", contentType)
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val b = body.toByteArray()
            ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
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
            assertEquals(AiException.Kind.AUTH, e.kind); assertTrue(e.message!!.startsWith("Grok"))
        }
    }

    @Test fun retryAfterHeader() {
        status = 429; contentType = "application/json"; headers = mapOf("Retry-After" to "12")
        body = """{"error":{"message":"Rate limit reached"}}"""
        try { AiClient.complete(cfg(Provider.OPENAI), listOf(Msg("user", "hi"))); fail() } catch (e: AiException) {
            assertEquals(AiException.Kind.RATE_LIMIT, e.kind); assertEquals(12, e.retryAfterSec)
        }
    }

    @Test fun sseLineParsing() {
        assertEquals(AiClient.DONE, AiClient.parseSseLine("data: [DONE]"))
        assertEquals(null, AiClient.parseSseLine(": keep-alive"))
        assertEquals("", AiClient.parseSseLine("data: {\"choices\":[{\"delta\":{\"content\":null}}]}"))
        assertEquals("x", AiClient.parseSseLine("data:{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
    }
}
