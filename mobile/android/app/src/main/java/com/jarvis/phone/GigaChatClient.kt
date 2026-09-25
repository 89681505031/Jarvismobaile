package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.SSLHandshakeException

/**
 * GigaChat is the default brain for open-ended requests. All HTTP calls must
 * run off Android's main thread. Native phone commands never flow through
 * arbitrary AI-generated tool calls.
 *
 * AndroidManifest networkSecurityConfig includes the Russian CA trust anchors;
 * certificate and hostname verification remain ENABLED.
 */
class GigaChatClient(private val context: Context) {
    companion object {
        private const val TOKEN_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
        private const val CHAT_URL = "https://api.giga.chat/v1/chat/completions"
        private const val SETTINGS = "jarvis_settings"
    }

    data class Reply(val text: String, val success: Boolean)

    private class ApiFailure(val status: Int) : Exception()

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0L
    @Volatile private var tokenFingerprint: String? = null

    fun configured(): Boolean =
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("gigachat_api_key", "").orEmpty().isNotBlank()

    fun modelName(): String = GigaChatBrainPolicy.validModel(
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("gigachat_model", "GigaChat-2").orEmpty()
    )

    fun scopeName(): String = GigaChatBrainPolicy.validScope(
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("gigachat_scope", "GIGACHAT_API_PERS").orEmpty()
    )

    /** Preserves the old call sites for news and explicit message analysis. */
    fun ask(userText: String, persona: String, memoryContext: String = ""): String =
        askConversation(userText, persona, memoryContext).text

    /** Called from MainActivity.backgroundExecutor. */
    fun askConversation(
        userText: String,
        persona: String,
        memoryContext: String = "",
        recentTurns: List<Pair<String, String>> = emptyList()
    ): Reply {
        val key = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("gigachat_api_key", "").orEmpty().trim()
        if (key.isBlank()) return Reply(
            "Подключите GigaChat: откройте настройки JARVIS, введите свой Authorization Key и нажмите «Проверить подключение».",
            false
        )
        if (userText.isBlank()) return Reply("Сначала задайте вопрос.", false)
        return try {
            Reply(askOnce(key, scopeName(), modelName(), userText, persona,
                memoryContext, recentTurns), true)
        } catch (e: Exception) {
            Reply(userReadableError(e), false)
        }
    }

    /** A deliberate one-request probe; user may incur GigaChat API usage. */
    fun testConnection(): Reply = askConversation(
        "Ответь одним коротким предложением, подтверждающим, что связь работает.",
        "J.A.R.V.I.S."
    )

    @Synchronized fun invalidateToken() {
        accessToken = null
        tokenExpiresAt = 0L
        tokenFingerprint = null
    }

    private fun askOnce(
        key: String, scope: String, model: String, userText: String,
        persona: String, memoryContext: String, recentTurns: List<Pair<String, String>>
    ): String {
        val messages = JSONArray()
        val system = GigaChatBrainPolicy.systemPrompt(persona) + if (memoryContext.isBlank()) ""
            else "\n\nКонтекст, который пользователь разрешил использовать:\n" +
                memoryContext.take(12000)
        messages.put(message("system", system))
        // Structured user/assistant turns (not a flattened blob pretending to be
        // a system message). Bounded to keep token use and private context small.
        for ((oldUser, oldAssistant) in recentTurns.takeLast(6)) {
            if (oldUser.isNotBlank() && oldAssistant.isNotBlank()) {
                messages.put(message("user", oldUser.trim().take(700)))
                messages.put(message("assistant", oldAssistant.trim().take(1300)))
            }
        }
        messages.put(message("user", userText.trim().take(4000)))
        val request = JSONObject().put("model", model)
            .put("messages", messages)
            .put("stream", false)
        val body = request.toString()
        fun post(token: String): String = postJson(CHAT_URL, body, mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        ))
        val firstToken = getToken(key, scope)
        val response = try {
            post(firstToken)
        } catch (e: ApiFailure) {
            if (e.status != 401) throw e
            // Retry ONCE after discarding an expired/revoked cached token.
            invalidateToken()
            post(getToken(key, scope))
        }
        return JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("empty_response")
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    @Synchronized private fun getToken(key: String, scope: String): String {
        val now = System.currentTimeMillis()
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$key|$scope".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (fingerprint != tokenFingerprint) {
            accessToken = null
            tokenExpiresAt = 0L
            tokenFingerprint = fingerprint
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
        if (token.isBlank()) throw IllegalStateException("empty_token")
        val rawExpiry = json.optLong("expires_at", now + 1_500_000L)
        accessToken = token
        tokenExpiresAt = if (rawExpiry > 10_000_000_000L) rawExpiry else rawExpiry * 1000L
        return token
    }

    private fun postForm(url: String, body: String, headers: Map<String, String>) =
        request(url, body.toByteArray(Charsets.UTF_8), headers)

    private fun postJson(url: String, body: String, headers: Map<String, String>) =
        request(url, body.toByteArray(Charsets.UTF_8), headers)

    private fun request(endpoint: String, payload: ByteArray, headers: Map<String, String>): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = false
            doInput = true
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiFailure(status)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText().take(512_000)
            }
        } finally { connection.disconnect() }
    }

    private fun userReadableError(error: Exception): String = when (error) {
        is ApiFailure -> GigaChatBrainPolicy.connectionMessage(error.status)
        is SSLHandshakeException ->
            "Не удалось проверить сертификат GigaChat. Проверьте дату Android и настройки доверенных сертификатов."
        is SocketTimeoutException ->
            "GigaChat не ответил вовремя. Проверьте соединение и повторите запрос."
        else -> when (error.message) {
            "empty_response" -> "GigaChat вернул пустой ответ. Попробуйте ещё раз."
            "empty_token" -> "GigaChat не выдал токен доступа. Проверьте ключ и область доступа."
            else -> "Не удалось связаться с GigaChat. Проверьте интернет и настройки подключения."
        }
    }
}
