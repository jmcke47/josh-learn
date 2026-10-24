package com.josh.learn

/** Very small HTML -> readable text extractor for shared web pages (no dependencies). */
object HtmlText {
    fun title(html: String): String? =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { decode(it).trim() }?.ifEmpty { null }

    fun extract(html: String, maxChars: Int = 9000): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg|head|nav|footer|header|form|iframe)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?s)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)[^>]*>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), " ")
        s = decode(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r\u00A0]+"), " ")
        s = s.lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.take(maxChars)
    }

    fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&rsquo;", "'").replace("&lsquo;", "'")
        .replace("&ldquo;", "\"").replace("&rdquo;", "\"").replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: "" }

    private val urlRe = Regex("(?i)\\bhttps?://[^\\s<>\"]+")
    fun findUrl(text: String): String? = urlRe.find(text)?.value?.trimEnd('.', ',', ')', ';', '!', '?')
    /** True when the shared text is essentially just a link (maybe with a page title). */
    fun isMostlyUrl(text: String): Boolean {
        val u = findUrl(text) ?: return false
        return text.replace(u, "").trim().length < 120
    }
}
