package com.jarvis.phone

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/** Fetches headline text only. Summarisation is a separate GigaChat step. */
class JarvisNewsFeed {
    fun fetchMainHeadlines(): List<String> {
        val feeds = listOf(
            "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru",
            "https://news.google.com/rss?hl=ru&gl=PL&ceid=PL:ru",
            "https://news.google.com/rss?hl=ru&gl=US&ceid=US:ru"
        )
        var lastError: Exception? = null
        for (feed in feeds) {
            try {
                val items = parse(get(feed))
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("В новостной ленте нет материалов.")
    }

    private fun get(endpoint: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "JARVIS-Android")
            connection.setRequestProperty(
                "Accept",
                "application/rss+xml, application/xml, text/xml"
            )
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText().take(1_000_000)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun parse(xml: String): List<String> =
            Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .mapNotNull { match ->
                    val block = match.groupValues[1]
                    val title = Regex(
                        "<title>([\\s\\S]*?)</title>",
                        RegexOption.IGNORE_CASE
                    ).find(block)?.groupValues?.get(1)
                        ?.replace("<![CDATA[", "")?.replace("]]>", "")
                        ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }

                    val source = Regex(
                        "<source[^>]*>([\\s\\S]*?)</source>",
                        RegexOption.IGNORE_CASE
                    ).find(block)?.groupValues?.get(1)
                        ?.replace("<![CDATA[", "")?.replace("]]>", "")
                        ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }

                    title?.takeIf { it.isNotBlank() }?.let {
                        if (source.isNullOrBlank()) it else "$it — $source"
                    }
                }
                .distinct()
                .take(7)
                .toList()
    }
}
