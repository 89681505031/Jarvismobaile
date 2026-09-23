package com.jarvis.phone

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Called only on the existing background executor; failures keep local memory available. */
class RedisMemoryGateway(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_memory_gateway", Context.MODE_PRIVATE)
    @Volatile private var connectionState = "Подключение ещё не проверено."
    private val session = UUID.randomUUID().toString()

    fun status(): String = if (prefs.getString("url", "").isNullOrBlank()) "Локальная память работает. Облачная память не настроена." else "Облачная память настроена. $connectionState"

    fun endpoint(): String = prefs.getString("url", "").orEmpty()

    fun configure(url: String, token: String): String {
        if (url.isBlank() && token.isBlank()) {
            prefs.edit().remove("url").remove("token").apply()
            return status()
        }
        val parsed = try { URL(url) } catch (_: Exception) { return "Неверный URL шлюза." }
        val selectedToken = if (token.isBlank() && url == endpoint()) prefs.getString("token", "").orEmpty() else token
        if (parsed.protocol != "https" || parsed.host.isBlank() || parsed.userInfo != null || parsed.ref != null || selectedToken.length < 32) return "Нужны HTTPS URL и токен шлюза (от 32 символов)."
        prefs.edit().putString("url", url).putString("token", selectedToken).apply()
        connectionState = "Подключение ещё не проверено."
        return status()
    }

    fun recall(text: String): String {
        return try {
            val result = request(JSONObject().put("action", "recall").put("text", text.take(4000))) ?: return ""
            val items = result.optJSONArray("memories") ?: return ""
            (0 until minOf(items.length(), 5)).mapNotNull { items.optString(it).takeIf(String::isNotBlank) }.joinToString("\n").take(2000)
        } catch (_: Exception) {
            connectionState = "Сервис недоступен; используется локальная память."
            ""
        }
    }

    fun record(text: String, answer: String) {
        if (answer.startsWith("Не удалось получить ответ GigaChat") || answer.startsWith("В настройках J.A.R.V.I.S.") || answer == "GigaChat не вернул текст ответа.") return
        try { request(JSONObject().put("action", "record").put("turnId", UUID.randomUUID().toString()).put("text", text.take(4000)).put("answer", answer.take(8000))) } catch (_: Exception) {
            connectionState = "Последний диалог не удалось сохранить в облаке."
        }
    }

    private fun request(body: JSONObject): JSONObject? {
        val url = prefs.getString("url", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        if (url.isBlank() || token.isBlank()) return null
        val connection = (URL(url).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2500
            connection.readTimeout = if (body.optString("action") == "record") 8000 else 3000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            body.put("sessionId", session)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                connectionState = if (connection.responseCode == 401) "Проверьте токен шлюза." else "Сервис недоступен; используется локальная память."
                return null
            }
            val result = JSONObject(connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(16001)
                var count = 0
                while (count < buffer.size) {
                    val n = reader.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                require(count <= 16000) { "Memory response is too large" }
                String(buffer, 0, count)
            })
            connectionState = "Последнее обращение успешно."
            return result
        } finally { connection.disconnect() }
    }
}
