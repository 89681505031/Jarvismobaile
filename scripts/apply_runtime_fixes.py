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

# MainActivity also had its own automatic wake-listening loop. Disable only the
# automatic restart function; manual microphone startConversationListening stays intact.
m = root / "MainActivity.kt"
s = m.read_text(encoding="utf-8")
start = s.find("    private fun restartWakeListening() {")
if start < 0:
    raise SystemExit("MainActivity restartWakeListening function not found")
next_fun = s.find("\n    private fun ", start + 5)
if next_fun < 0:
    raise SystemExit("Could not find end of restartWakeListening")
replacement = """    private fun restartWakeListening() {
        // Automatic SpeechRecognizer wake loop disabled. SpeechRecognizer is session-based
        // and Android does not support using it as a continuous hotword listener.
        wakeListening = false
    }
"""
s = s[:start] + replacement + s[next_fun:]
m.write_text(s, encoding="utf-8")

print("Applied GigaChat scope fallback and disabled all automatic SpeechRecognizer wake loops")

from pathlib import Path
root = Path("mobile/android")
app = root / "app"
gradle = app / "build.gradle.kts"
if not gradle.exists():
    gradle = app / "build.gradle"
s = gradle.read_text(encoding="utf-8")
dep = 'implementation("ai.picovoice:porcupine-android:4.0.0")'
if "porcupine-android" not in s:
    pos = s.rfind("}")
    # Insert into dependencies block, not the file's final brace.
    d = s.find("dependencies {")
    if d < 0: raise SystemExit("dependencies block not found")
    end = s.find("\n}", d)
    s = s[:end] + "\n    " + dep + s[end:]
    gradle.write_text(s, encoding="utf-8")

src = app / "src/main/java/com/jarvis/phone"
wake = src / "JarvisWakeService.kt"
wake.write_text(r'''package com.jarvis.phone

import android.app.*
import android.content.Intent
import android.os.IBinder
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager

class JarvisWakeService : Service() {
    private var manager: PorcupineManager? = null
    private val channelId = "jarvis_wake_channel"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(701, notification())
        startWakeWord()
    }

    private fun startWakeWord() {
        val accessKey = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            .getString("picovoice_access_key", "").orEmpty().trim()
        if (accessKey.isBlank()) return
        try {
            manager?.delete()
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(this) {
                    try { manager?.stop() } catch (_: Exception) {}
                    sendBroadcast(Intent(ACTION_WAKE).setPackage(packageName).putExtra("text", "jarvis"))
                }
            manager?.start()
        } catch (_: Exception) {
            manager = null
        }
    }

    override fun onDestroy() {
        try { manager?.stop() } catch (_: Exception) {}
        try { manager?.delete() } catch (_: Exception) {}
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "J.A.R.V.I.S. wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification =
        Notification.Builder(this, channelId)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText("Ожидаю слово «Джарвис»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

    companion object { const val ACTION_WAKE = "com.jarvis.phone.ACTION_WAKE" }
}
''', encoding="utf-8")

m = src / "MainActivity.kt"
s = m.read_text(encoding="utf-8")
# Restore automatic transition only while a conversation window is active; never restart wake SpeechRecognizer.
start = s.find("    private fun restartWakeListening() {")
if start >= 0:
    nxt = s.find("\n    private fun ", start + 5)
    if nxt < 0: raise SystemExit("restartWakeListening end not found")
    repl = '''    private fun restartWakeListening() {
        wakeListening = false
        // Wake-word listening is handled by JarvisWakeService/Porcupine.
    }
'''
    s = s[:start] + repl + s[nxt:]

# Make the normal conversation window 30 seconds wherever the old default 10 seconds is used.
s = s.replace("startConversationListening(10_000)", "startConversationListening(30_000)")
# Preserve the 30-second window when a result is delivered; do not zero it immediately.
s = s.replace("        conversationUntil = 0L\n        val clean = text.trim()", "        if (wasConversation) conversationUntil = System.currentTimeMillis() + 30_000L\n        val clean = text.trim()")
m.write_text(s, encoding="utf-8")

# Wire the wake-word broadcast into MainActivity. This is applied by text patterns so
# the source archive can remain unchanged in Git while we iterate.
s = m.read_text(encoding="utf-8")
if "jarvisWakeReceiver" not in s:
    # Add imports only when the source uses explicit imports.
    if "import android.content.BroadcastReceiver" not in s:
        s = s.replace("import android.content.", "import android.content.BroadcastReceiver\nimport android.content.IntentFilter\nimport android.content.", 1)

    class_pos = s.find("class MainActivity")
    brace = s.find("{", class_pos)
    receiver = r'''
    private val jarvisWakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != JarvisWakeService.ACTION_WAKE) return
            // Porcupine stops itself before broadcasting. Give AudioRecord a moment to
            // release the microphone, then start normal command recognition.
            conversationUntil = System.currentTimeMillis() + 30_000L
            mainHandler.postDelayed({
                if (!isSpeaking) startConversationListening(30_000)
            }, 250L)
        }
    }

'''
    s = s[:brace+1] + receiver + s[brace+1:]

    # Register/unregister receiver using lifecycle hooks. Prefer existing onResume/onPause.
    resume = s.find("override fun onResume()")
    if resume >= 0:
        body = s.find("{", resume)
        s = s[:body+1] + r'''
        val wakeFilter = IntentFilter(JarvisWakeService.ACTION_WAKE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(jarvisWakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(jarvisWakeReceiver, wakeFilter)
        }
''' + s[body+1:]
    else:
        # Insert before onDestroy if no onResume exists.
        od = s.find("    override fun onDestroy()")
        if od < 0: raise SystemExit("MainActivity lifecycle insertion point not found")
        lifecycle = r'''    override fun onResume() {
        super.onResume()
        val wakeFilter = IntentFilter(JarvisWakeService.ACTION_WAKE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(jarvisWakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(jarvisWakeReceiver, wakeFilter)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(jarvisWakeReceiver) } catch (_: Exception) {}
        super.onPause()
    }

'''
        s = s[:od] + lifecycle + s[od:]

    # If onPause already existed before insertion, ensure receiver is unregistered.
    pause = s.find("override fun onPause()")
    if pause >= 0:
        body = s.find("{", pause)
        segment = s[body:body+500]
        if "unregisterReceiver(jarvisWakeReceiver)" not in segment:
            s = s[:body+1] + '\n        try { unregisterReceiver(jarvisWakeReceiver) } catch (_: Exception) {}' + s[body+1:]

m.write_text(s, encoding="utf-8")
print("Installed Porcupine JARVIS wake-word service, ACTION_WAKE receiver, and 30-second conversation window")

# Final runtime wiring: preserve the 30-second deadline across TTS and declare the
# microphone foreground service on modern Android.
m = src / "MainActivity.kt"
s = m.read_text(encoding="utf-8")
s = s.replace("private val conversationResumeDurationMs = 12_000L", "private val conversationResumeDurationMs = 30_000L")
s = s.replace(
    """        manualListening = false
        conversationUntil = 0L
        speechRecognizer?.cancel()
""",
    """        manualListening = false
        if (resumeAfter) {
            conversationUntil = maxOf(conversationUntil, System.currentTimeMillis() + 30_000L)
        } else {
            conversationUntil = 0L
        }
        speechRecognizer?.cancel()
""",
    1
)
s = s.replace(
    "                startConversationListening(conversationResumeDurationMs)",
    """                val remaining = (conversationUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remaining > 0L) startConversationListening(remaining)
                else restartWakeListening()""",
    1
)
m.write_text(s, encoding="utf-8")

manifest = app / "src/main/AndroidManifest.xml"
s = manifest.read_text(encoding="utf-8")
if "android.permission.FOREGROUND_SERVICE_MICROPHONE" not in s:
    s = s.replace(
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
        '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />'
    )
if 'android:name=".JarvisWakeService"' not in s:
    service = '        <service android:name=".JarvisWakeService" android:exported="false" android:foregroundServiceType="microphone" />\\n'
    s = s.replace("    </application>", service + "    </application>")
manifest.write_text(s, encoding="utf-8")
print("Preserved conversation deadline across TTS and declared wake microphone service")

