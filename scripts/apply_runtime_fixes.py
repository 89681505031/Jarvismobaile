from pathlib import Path

root = Path("mobile/android/app/src/main/java/com/jarvis/phone")
g = root / "GigaChatClient.kt"
s = g.read_text(encoding="utf-8")
start = s.index('    fun ask(userText: String, persona: String, memoryContext: String = ""): String {')
end = s.index('    private fun systemPrompt', start)
prefix = s[:start]
if "workingScope" not in prefix:
    prefix = prefix.replace(
        "    @Volatile private var tokenKeyFingerprint: Int? = null\n",
        "    @Volatile private var tokenKeyFingerprint: Int? = null\n    @Volatile private var workingScope: String? = null\n",
    )
block = r'''    fun ask(userText: String, persona: String, memoryContext: String = ""): String {
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
            try {
                val result = askOnce(key, scope, userText, persona, memoryContext)
                workingScope = scope
                return result
            } catch (e: Exception) {
                lastError = e
                invalidateToken()
                if (e.message?.contains("HTTP 401") != true) throw e
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
        val fingerprint = "$key|$scope".hashCode()
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

'''
g.write_text(prefix + block + s[end:], encoding="utf-8")

w = root / "JarvisWakeService.kt"
s = w.read_text(encoding="utf-8")
needle = '        startForeground(701, notification())\n        startRecognition()\n'
replacement = '        startForeground(701, notification())\n        // Background SpeechRecognizer loop disabled: it repeatedly reopens the microphone.\n'
if needle not in s:
    raise SystemExit("JarvisWakeService onCreate pattern not found")
w.write_text(s.replace(needle, replacement, 1), encoding="utf-8")

print("Applied GigaChat scope fallback and disabled background recognizer startup")
