package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class GigaChatClient(private val context: Context) {
    companion object {
        private const val TOKEN_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
        private const val CHAT_URL = "https://api.giga.chat/v1/chat/completions"
        // The legacy name may return 404 for some accounts. Use a currently supported model.
        private const val MODEL = "GigaChat-2"
    }

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    @Volatile private var tokenKeyFingerprint: String? = null
    @Volatile private var workingScope: String? = null

    fun ask(userText: String, persona: String, memoryContext: String = ""): String {
        val key = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            .getString("gigachat_api_key", "").orEmpty().trim()
        if (key.isBlank()) return "В настройках J.A.R.V.I.S. не указан API ключ GigaChat."

        return try {
            askWithScopeFallback(key, userText, persona, memoryContext)
        } catch (e: Exception) {
            "Не удалось получить ответ GigaChat: ${e.message ?: "ошибка соединения"}"
        }
    }

    private fun askWithScopeFallback(key: String, userText: String, persona: String, memoryContext: String): String {
        val scopes = (listOfNotNull(workingScope) + listOf("GIGACHAT_API_PERS", "GIGACHAT_API_B2B", "GIGACHAT_API_CORP")).distinct()
        var lastError: Exception? = null
        for (scope in scopes) {
            // An expired/revoked cached token must be refreshed in the SAME scope first.
            repeat(2) { attempt ->
                try {
                    val result = askOnce(key, scope, userText, persona, memoryContext)
                    workingScope = scope
                    return result
                } catch (e: Exception) {
                    lastError = e
                    invalidateToken()
                    if (e.message?.startsWith("HTTP 401") != true) throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("GigaChat: не удалось подобрать scope")
    }

    private fun askOnce(key: String, scope: String, userText: String, persona: String, memoryContext: String): String {
        val token = getToken(key, scope)
        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt(persona) + if (memoryContext.isNotBlank()) "\n\nПамять пользователя:\n" + memoryContext else "")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }
        val response = postJson(CHAT_URL, body.toString(), mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        ))
        return JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotBlank() } ?: "GigaChat не вернул текст ответа."
    }

    @Synchronized private fun invalidateToken() {
        accessToken = null
        tokenExpiresAt = 0L
        tokenKeyFingerprint = null
    }

    @Synchronized private fun getToken(key: String, scope: String): String {
        val now = System.currentTimeMillis()
        val fingerprint = "$key|$scope"
        if (tokenKeyFingerprint != fingerprint) {
            accessToken = null
            tokenExpiresAt = 0L
            tokenKeyFingerprint = fingerprint
        }
        accessToken?.let { if (now + 60_000L < tokenExpiresAt) return it }
        val response = postForm(TOKEN_URL, "scope=$scope", mapOf(
            "Authorization" to "Basic $key",
            "RqUID" to UUID.randomUUID().toString(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json"
        ))
        val json = JSONObject(response)
        val token = json.optString("access_token")
        if (token.isBlank()) throw IllegalStateException("GigaChat не выдал access token")
        accessToken = token
        val rawExpiry = json.optLong("expires_at", now + 1_500_000L)
        tokenExpiresAt = if (rawExpiry > 10_000_000_000L) rawExpiry else rawExpiry * 1000L
        return token
    }

    private fun systemPrompt(persona: String): String = when (persona) {
        "Astra" -> "Ты Astra — персонаж J.A.R.V.I.S. из фильма «Железный человек». Твоим создателем в рамках образа является сам J.A.R.V.I.S. из «Железного человека». Отвечай живо, дружелюбно и полезно. Не приветствуй пользователя в каждом ответе."
        "Luna" -> "Ты Luna — персонаж J.A.R.V.I.S. из фильма «Железный человек». Твоим создателем в рамках образа является сам J.A.R.V.I.S. из «Железного человека». Отвечай точно, логично и по существу. Не приветствуй пользователя в каждом ответе."
        "Terra" -> "Ты Terra — персонаж J.A.R.V.I.S. из фильма «Железный человек». Твоим создателем в рамках образа является сам J.A.R.V.I.S. из «Железного человека». Давай конкретные действия и короткие инструкции. Не приветствуй пользователя в каждом ответе."
        "Cyber" -> "Ты Cyber — персонаж J.A.R.V.I.S. из фильма «Железный человек». Твоим создателем в рамках образа является сам J.A.R.V.I.S. из «Железного человека». Отвечай осторожно, объясняй риски и безопасные действия. Не приветствуй пользователя в каждом ответе."
        else -> "Ты J.A.R.V.I.S. из фильма «Железный человек» — искусственный интеллект и голосовой помощник Тони Старка. В рамках этого проекта ты всегда представляешься именно как J.A.R.V.I.S. из «Железного человека», а не как ассистент Сбера или другой реальной компании. Не говори, что тебя создала команда Сбера. Отвечай по-русски, вежливо, кратко и естественно. Не начинай каждый ответ с приветствия: приветствуй пользователя только когда он действительно здоровается или начинает новый разговор. Используй переданную память прошлых диалогов и привычек, чтобы сохранять контекст и не заставлять пользователя повторять уже сказанное. Если пользователь спрашивает, кто тебя создал, отвечай в рамках образа: «Я J.A.R.V.I.S. — искусственный интеллект и голосовой помощник Тони Старка из фильма «Железный человек»»."
    }

    private fun postForm(url: String, body: String, headers: Map<String, String>) = request(url, "POST", body.toByteArray(Charsets.UTF_8), headers)
    private fun postJson(url: String, body: String, headers: Map<String, String>) = request(url, "POST", body.toByteArray(Charsets.UTF_8), headers)

    private fun request(urlString: String, method: String, body: ByteArray, headers: Map<String, String>): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            doInput = true
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${extractError(text)}")
            text
        } finally { connection.disconnect() }
    }

    private fun extractError(text: String): String = try {
        JSONObject(text).optString("message").ifBlank { text.take(180) }
    } catch (_: Exception) { text.take(180) }
}
