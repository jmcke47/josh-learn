package com.josh.learn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic fixtures only: never put real export data in the repo. */
class GrokImportTest {
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L
    private val zone = ZoneId.of("Australia/Brisbane")

    private fun conv(id: String, title: String, human: List<String>, daysAgo: Int, assistant: String = "Sure! Here's a long answer about many things: bitcoin, politics, cars.") =
        JSONObject().put("conversation", JSONObject().put("id", id).put("title", title).put("create_time", "2026-09-01T00:00:00Z"))
            .put("responses", JSONArray().apply {
                human.forEachIndexed { i, h ->
                    put(JSONObject().put("response", JSONObject().put("message", h).put("sender", "human")
                        .put("create_time", JSONObject().put("\$date", JSONObject().put("\$numberLong", (now - (daysAgo + i) * day).toString())))))
                    put(JSONObject().put("response", JSONObject().put("message", assistant).put("sender", "assistant")))
                }
            })

    private fun export(): ByteArray {
        val convs = JSONArray()
        repeat(6) { i -> convs.put(conv("c$i", "Land Cruiser 1FZ vacuum leak", listOf("my 1fz idle is rough, vacuum leak?", "checked the coolant and the diff oil too"), i * 3)) }
        repeat(3) { i -> convs.put(conv("g$i", "OSRS early game", listOf("best osrs quests for skilling early?", "runescape grand exchange flipping"), 40 + i)) }
        repeat(2) { i -> convs.put(conv("p$i", "Proxmox and docker", listOf("proxmox vm with docker and jellyfin", "cloudflare tunnel dns"), 300 + i)) }
        convs.put(conv("s1", "Talking to my girlfriend", listOf("how do I fix things with my girlfriend about the car"), 1))
        convs.put(conv("s2", "Router login", listOf("I forgot the router password for my proxmox network"), 1))
        convs.put(conv("u1", "Random", listOf("hello there"), 1))
        convs.put(conv("c0", "duplicate id", listOf("land cruiser coolant 1fz"), 1)) // duplicate id: ignored
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("ttl/30d/export_data/uid/prod-mc-auth-mgmt-api.json")); z.write("""{"user":{"email":"x@example.com"}}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("ttl/30d/export_data/uid/prod-mc-asset-server//abc/content")); z.write(ByteArray(1000)); z.closeEntry()
            z.putNextEntry(ZipEntry("ttl/30d/export_data/uid/prod-grok-backend.json"))
            z.write(JSONObject().put("conversations", convs).put("projects", JSONArray()).toString().toByteArray()); z.closeEntry()
        }
        return bo.toByteArray()
    }

    @Test fun ranksTopicsFromZipAndSkipsSensitive() {
        val progress = mutableListOf<Int>()
        val r = GrokImport.analyseZip(ByteArrayInputStream(export()), now, zone, { progress += it })
        assertEquals(14, r.chats) // 15 with a duplicate id
        assertEquals(2, r.excludedSensitive)
        assertEquals(1, r.unmatched)
        val titles = r.topics.map { it.title }
        assertEquals("4WD & Car Mechanics", titles.first())
        assertTrue(titles.indexOf("Gaming & Handhelds") in 1..2)
        assertTrue("Home Lab & Networking" in titles)
        val car = r.topics.first()
        assertEquals(6, car.chats)
        assertEquals(5, car.subtopics.size)
        assertEquals("Land Cruiser 1FZ vacuum leak", car.subtopics.first()) // title-derived
        assertTrue(car.subtopics.contains("Vacuum leaks and rough idle"))
        assertTrue(progress.last() == 14)
        // nothing from excluded chats leaks into topics
        assertFalse(r.topics.flatMap { it.subtopics }.any { it.contains("girlfriend", true) || it.contains("password", true) })
    }

    @Test fun outputIsImportableTopicsJson() {
        val r = GrokImport.analyseZip(ByteArrayInputStream(export()), now, zone)
        val parsed = Topics.parseSuggested(GrokImport.toJson(r.topics))
        assertEquals(r.topics.map { it.title }, parsed.map { it.title })
        assertEquals(100, parsed.first().priority)
    }

    @Test fun tolerantOfVariations() {
        // flat messages with role/content, top-level array, ISO + epoch-seconds times, no zip
        val json = JSONArray().apply {
            repeat(3) { i -> put(JSONObject().put("id", "x$i").put("title", "Bitcoin fees").put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", "why are bitcoin fees and btc blocks like that").put("timestamp", ((now - i * day) / 1000).toString()))
                .put(JSONObject().put("role", "assistant").put("content", JSONArray().put("rich")))
                .put(JSONObject().put("role", "USER").put("text", "and stablecoins like tether?").put("created_at", "2026-09-0${i + 1}T10:00:00+10:00")))) }
        }.toString()
        val r = GrokImport.analyseJson(ByteArrayInputStream(json.toByteArray()), now, zone)
        assertEquals(3, r.chats)
        assertEquals("Cryptocurrency & Blockchain", r.topics.first().title)
        assertEquals(6, r.topics.first().messages)
    }

    @Test fun garbageZipGivesEmptyResult() {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("a.json")); z.write("{not json".toByteArray()); z.closeEntry() }
        val r = GrokImport.analyseZip(ByteArrayInputStream(bo.toByteArray()), now, zone)
        assertTrue(r.topics.isEmpty()); assertEquals(0, r.chats)
        assertTrue(GrokImport.isZip(bo.toByteArray().copyOf(4)))
        assertFalse(GrokImport.isZip("{\"topics\":[]}".toByteArray()))
    }

    @Test fun sensitiveFilter() {
        listOf("my wife said", "what dosage of ibuprofen", "my password is", "seed phrase backup", "talk to a lawyer", "my doctor said").forEach { assertTrue(it, GrokImport.isSensitive(it)) }
        listOf("how a haldex awd works", "osrs quest guide", "solar battery payback").forEach { assertFalse(it, GrokImport.isSensitive(it)) }
    }

    /** Validation against a real export on the dev box only: ./gradlew testDebugUnitTest -PgrokZip=/path/export.zip (writes to build/, never committed). */
    @Test fun realExportValidation() {
        val path = System.getProperty("grok.zip").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).exists())
        val t0 = System.currentTimeMillis()
        val r = File(path).inputStream().use { GrokImport.analyseZip(it, zone = zone) }
        val out = File(System.getProperty("project.root") ?: ".", "app/build/grok-validation.txt")
        out.writeText(buildString {
            appendLine("chats=${r.chats} msgs=${r.messages} excluded=${r.excludedSensitive} unmatched=${r.unmatched} ms=${System.currentTimeMillis() - t0}")
            r.topics.forEachIndexed { i, t -> appendLine("${i + 1}. ${t.emoji} ${t.title} score=${t.score} chats=${t.chats} msgs=${t.messages} days=${t.days} | ${t.subtopics}") }
        })
        assertTrue(r.topics.size >= 10)
    }
}
