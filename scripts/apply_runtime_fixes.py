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

# Replace the legacy SpeechRecognizer wake entry point too. onResume() calls
# startWakeListening() directly, so leaving its old body would still contend with Porcupine.
s = m.read_text(encoding="utf-8")
start = s.find("    private fun startWakeListening() {")
if start < 0:
    raise SystemExit("MainActivity startWakeListening function not found")
next_fun = s.find("\n    private fun ", start + 5)
if next_fun < 0:
    raise SystemExit("Could not find end of startWakeListening")
replacement = """    private fun startWakeListening() {
        restartWakeListening()
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
    }

    private fun startWakeWord() {
        val accessKey = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            .getString("picovoice_access_key", "").orEmpty().trim()
        if (accessKey.isBlank()) return
        try {
            try { manager?.stop() } catch (_: Exception) {}
            try { manager?.delete() } catch (_: Exception) {}
            manager = null
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .build(this) {
                    try { manager?.stop() } catch (_: Exception) {}
                    try { manager?.delete() } catch (_: Exception) {}
                    manager = null
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate must not start Porcupine too: a newly started service receives
        // onCreate followed by onStartCommand, which otherwise opens the mic twice.
        startWakeWord()
        return START_STICKY
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

    companion object {
        const val ACTION_WAKE = "com.jarvis.phone.ACTION_WAKE"
        const val ACTION_RESUME_WAKE = "com.jarvis.phone.ACTION_RESUME_WAKE"
    }
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
        if (isSpeaking) return
        if (conversationUntil > System.currentTimeMillis()) {
            restartConversationListening()
            return
        }
        conversationUntil = 0L
        wakeListening = false
        manualListening = false
        try {
            startService(
                Intent(this, JarvisWakeService::class.java)
                    .setAction(JarvisWakeService.ACTION_RESUME_WAKE)
            )
        } catch (_: Exception) {}
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
            registerReceiver(jarvisWakeReceiver, wakeFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
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
        conversationUntil = 0L
        speechRecognizer?.cancel()
""",
    1
)
s = s.replace(
    "                startConversationListening(conversationResumeDurationMs)",
    """                conversationUntil = System.currentTimeMillis() + conversationResumeDurationMs
                startConversationListening(conversationResumeDurationMs)""",
    1
)
m.write_text(s, encoding="utf-8")

manifest = app / "src/main/AndroidManifest.xml"
s = manifest.read_text(encoding="utf-8")
if "android.permission.FOREGROUND_SERVICE_MICROPHONE" not in s:
    permission = '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n'
    app_pos = s.find("<application")
    if app_pos < 0: raise SystemExit("AndroidManifest application tag not found")
    s = s[:app_pos] + permission + s[app_pos:]
if 'android:name=".JarvisWakeService"' in s:
    import re
    def ensure_mic_type(match):
        tag = match.group(0)
        if "foregroundServiceType" in tag:
            return re.sub(r'android:foregroundServiceType="[^"]*"', 'android:foregroundServiceType="microphone"', tag)
        return tag[:-1] + ' android:foregroundServiceType="microphone">'
    s = re.sub(r'<service\b[^>]*android:name="\.JarvisWakeService"[^>]*>', ensure_mic_type, s, count=1)
else:
    service = '        <service android:name=".JarvisWakeService" android:exported="false" android:foregroundServiceType="microphone" />\n'
    s = s.replace("    </application>", service + "    </application>")
manifest.write_text(s, encoding="utf-8")
print("Preserved conversation deadline across TTS and declared wake microphone service")

# Picovoice AccessKey stays on the device. Expose a small JS bridge API so the
# existing WebView settings page can save/check it without embedding secrets in Git.
m = src / "MainActivity.kt"
s = m.read_text(encoding="utf-8")
if "fun setPicovoiceAccessKey(" not in s:
    anchor = "    @JavascriptInterface\n    fun getApiKeyStatus"
    pos = s.find(anchor)
    if pos < 0:
        anchor = "    @JavascriptInterface\n    fun setApiKeys"
        pos = s.find(anchor)
    if pos < 0:
        raise SystemExit("Could not locate MainActivity JavascriptInterface settings bridge")
    bridge = r'''    @JavascriptInterface
    fun setPicovoiceAccessKey(key: String): String {
        val clean = key.trim()
        val editor = prefs.edit()
        if (clean.isBlank()) editor.remove("picovoice_access_key")
        else editor.putString("picovoice_access_key", clean)
        editor.apply()
        // Restart only our wake service so it reloads the new key.
        try { stopService(Intent(this@MainActivity, JarvisWakeService::class.java)) } catch (_: Exception) {}
        if (clean.isNotBlank()) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, JarvisWakeService::class.java)
                )
            } catch (_: Exception) {}
        }
        return if (clean.isBlank()) "Picovoice: ключ удалён" else "Picovoice: ключ сохранён"
    }

    @JavascriptInterface
    fun getPicovoiceKeyStatus(): String =
        if (prefs.getString("picovoice_access_key", "").orEmpty().isBlank()) "не задан" else "сохранён"

'''
    s = s[:pos] + bridge + s[pos:]

# Start the wake service once audio permission is already available. This does not
# expose the AccessKey and the service itself remains idle when no key is stored.
needle = "setupSpeechRecognizer()"
idx = s.find(needle)
if idx >= 0 and "JarvisWakeService::class.java" not in s[max(0, idx-300):idx+800]:
    endline = s.find("\n", idx)
    startup = r'''
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, JarvisWakeService::class.java)
                )
            } catch (_: Exception) {}
        }
'''
    s = s[:endline+1] + startup + s[endline+1:]

m.write_text(s, encoding="utf-8")
print("Added on-device Picovoice key bridge and wake-service startup")

# Inject a Picovoice AccessKey field into the existing WebView settings page.
# Locate the HTML dynamically because the archived project may use index.html or another asset name.
assets = app / "src/main/assets"
html_files = list(assets.rglob("*.html"))
target = None
for candidate in html_files:
    txt = candidate.read_text(encoding="utf-8", errors="ignore")
    if "setApiKeys" in txt or "gigachat" in txt.lower() or "fish" in txt.lower():
        target = candidate
        break
if target is None:
    raise SystemExit("Settings HTML not found in Android assets")

h = target.read_text(encoding="utf-8")
if "picovoiceAccessKey" not in h:
    panel = r'''
<div id="picovoiceAccessKeyBlock" style="margin-top:14px">
  <label for="picovoiceAccessKey">Picovoice AccessKey — слово JARVIS</label>
  <input id="picovoiceAccessKey" type="password" autocomplete="off"
         placeholder="Вставьте AccessKey Picovoice" style="width:100%;box-sizing:border-box;margin-top:6px" />
  <button type="button" onclick="savePicovoiceAccessKey()" style="margin-top:8px">Сохранить Picovoice AccessKey</button>
  <div id="picovoiceAccessKeyStatus" style="margin-top:6px;font-size:12px;opacity:.75"></div>
</div>
<script>
function savePicovoiceAccessKey() {
  const el = document.getElementById('picovoiceAccessKey');
  const status = document.getElementById('picovoiceAccessKeyStatus');
  try {
    const msg = window.AndroidJarvis && AndroidJarvis.setPicovoiceAccessKey
      ? AndroidJarvis.setPicovoiceAccessKey((el && el.value) || '')
      : 'Android bridge недоступен';
    if (status) status.textContent = msg;
    if (el) el.value = '';
  } catch (e) {
    if (status) status.textContent = 'Не удалось сохранить ключ';
  }
}
function refreshPicovoiceAccessKeyStatus() {
  const status = document.getElementById('picovoiceAccessKeyStatus');
  try {
    if (status && window.AndroidJarvis && AndroidJarvis.getPicovoiceKeyStatus)
      status.textContent = 'Picovoice: ' + AndroidJarvis.getPicovoiceKeyStatus();
  } catch (_) {}
}
document.addEventListener('DOMContentLoaded', refreshPicovoiceAccessKeyStatus);
</script>
'''
    body = h.lower().rfind("</body>")
    if body < 0:
        raise SystemExit("Settings HTML has no </body>")
    h = h[:body] + panel + h[body:]
    target.write_text(h, encoding="utf-8")

print("Added Picovoice AccessKey field to WebView settings")



# Fail fast if any critical runtime edit did not actually land.
main_text = m.read_text(encoding="utf-8")
manifest_text = manifest.read_text(encoding="utf-8")
gradle_text = gradle.read_text(encoding="utf-8")
wake_text = wake.read_text(encoding="utf-8")
html_text = target.read_text(encoding="utf-8")
checks = {
    "Porcupine dependency": "porcupine-android" in gradle_text,
    "wake service": "PorcupineManager" in wake_text and "ACTION_RESUME_WAKE" in wake_text and "override fun onStartCommand" in wake_text,
    "single Porcupine start": "startForeground(701, notification())\n    }" in wake_text and wake_text.count("startWakeWord()") == 1,
    "legacy wake entry disabled": "private fun startWakeListening() {\n        restartWakeListening()\n    }" in main_text,
    "wake resumes Porcupine": "setAction(JarvisWakeService.ACTION_RESUME_WAKE)" in main_text,
    "wake releases microphone": "manager?.stop()" in wake_text and "manager?.delete()" in wake_text and "manager = null" in wake_text,
    "wake receiver": "jarvisWakeReceiver" in main_text and "startConversationListening(30_000)" in main_text,
    "30s after TTS": "conversationUntil = System.currentTimeMillis() + conversationResumeDurationMs" in main_text and "startConversationListening(conversationResumeDurationMs)" in main_text,
    "Picovoice bridge": "setPicovoiceAccessKey" in main_text and "AndroidJarvis.setPicovoiceAccessKey" in html_text,
    "microphone FGS permission": "android.permission.FOREGROUND_SERVICE_MICROPHONE" in manifest_text,
    "microphone FGS type": 'android:foregroundServiceType="microphone"' in manifest_text,
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit("Runtime fix verification failed: " + ", ".join(failed))
print("Runtime fix verification passed")
