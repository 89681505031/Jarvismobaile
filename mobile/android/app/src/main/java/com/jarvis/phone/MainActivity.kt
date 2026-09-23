package com.jarvis.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var router: PhoneCommandRouter
    private lateinit var gigaChat: GigaChatClient
    private lateinit var speechInput: SpeechInputController
    private var pendingMicStart = false
    private var pendingMicDiagnostics = false
    private var microphonePermissionPending = false
    @Volatile private var diagnosticGeneration = 0
    private var diagnosticInProgress = false
    private var diagnosticInterrupted = false
    private var speechGeneration = 0L
    private var speechTimeout: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var selectedPersona = "J.A.R.V.I.S."
    private lateinit var fishAudioTts: FishAudioTts
    private lateinit var memory: JarvisMemory
    private lateinit var cloudMemory: RedisMemoryGateway
    private var isSpeaking = false
    private var resumeListeningAfterSpeech = false
    @Volatile private var activityResumed = false
    private val prefs by lazy { getSharedPreferences("jarvis_settings", MODE_PRIVATE) }
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = PhoneCommandRouter(this)
        gigaChat = GigaChatClient(this)
        selectedPersona = prefs.getString("persona", "J.A.R.V.I.S.") ?: "J.A.R.V.I.S."
        fishAudioTts = FishAudioTts(this)
        memory = JarvisMemory(this)
        cloudMemory = RedisMemoryGateway(this)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { utteranceId?.substringAfterLast("-")?.toLongOrNull()?.let { finishSpeech(it) } }
                    override fun onError(utteranceId: String?) { utteranceId?.substringAfterLast("-")?.toLongOrNull()?.let { finishSpeech(it) } }
                })
            }
        }
        setupSpeechRecognizer()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
            }
            addJavascriptInterface(AndroidBridge(), "AndroidJarvis")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

    }

    private fun voiceEvent(name: String, vararg values: Any) {
        if (isFinishing || isDestroyed || !::webView.isInitialized) return
        val args = values.joinToString(",") { if (it is Number) it.toString() else JSONObject.quote(it.toString()) }
        webView.evaluateJavascript("window.$name && window.$name($args)", null)
    }

    private fun setupSpeechRecognizer() {
        speechInput = SpeechInputController(this,
            onState = { state, message -> voiceEvent("onJarvisSpeechState", state, message) },
            onText = { text -> voiceEvent("onJarvisSpeechResult", text) },
            onError = { error -> voiceEvent("onJarvisSpeechError", error) },
            onLevel = { level -> voiceEvent("onJarvisSpeechLevel", level) },
            onUnavailable = { startSystemSpeechInput() })
    }

    private fun startListening() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (speechInput.isListening) return
        if (diagnosticInProgress) {
            voiceEvent("onJarvisSpeechError", "Завершите проверку микрофона, затем нажмите на круг.")
            return
        }
        stopSpeech()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicStart = true
            if (!microphonePermissionPending) {
                microphonePermissionPending = true
                voiceEvent("onJarvisSpeechState", "permission", "Разрешите доступ к микрофону в окне Android.")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7011)
            }
            return
        }
        pendingMicStart = false
        startConversationListening(20_000)
    }

    private fun startConversationListening(durationMs: Long) {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (isSpeaking) { resumeListeningAfterSpeech = true; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        speechInput.start(durationMs)
    }

    private fun requestMicrophoneDiagnostic() {
        if (!activityResumed || isFinishing || isDestroyed) return
        // A diagnostic is user-triggered, never a hidden background recording.
        pendingMicStart = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicDiagnostics = true
            if (!microphonePermissionPending) {
                microphonePermissionPending = true
                voiceEvent("onJarvisMicDiagnostic", "checking", "Разрешите микрофон для проверки.")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7011)
            }
            return
        }
        runMicrophoneDiagnostic()
    }

    private fun runMicrophoneDiagnostic() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (diagnosticInProgress) return
        pendingMicDiagnostics = false
        diagnosticInProgress = true
        speechInput.cancel()
        stopSpeech()
        val serviceAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        val offlineAvailable = android.os.Build.VERSION.SDK_INT >= 31 &&
            android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val serviceStatus = when {
            serviceAvailable -> "Системное распознавание: доступно."
            offlineAvailable -> "Системное распознавание: только офлайн."
            else -> "Сервис распознавания не найден: включите голосовой ввод Android."
        }
        val generation = ++diagnosticGeneration
        voiceEvent("onJarvisMicDiagnostic", "checking", "Проверяю аудиосигнал ~2 секунды. Произнесите несколько слов.")
        backgroundExecutor.execute {
            val result = MicrophoneProbe.run(applicationContext) {
                activityResumed && generation == diagnosticGeneration
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && activityResumed && generation == diagnosticGeneration) {
                    diagnosticInProgress = false
                    voiceEvent("onJarvisMicDiagnostic", result.status, result.message + " " + serviceStatus)
                }
            }
        }
    }

    private fun startSystemSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите команду J.A.R.V.I.S.")
        }
        try { startActivityForResult(intent, 7012) }
        catch (_: Exception) {
            voiceEvent("onJarvisSpeechError", "В Android нет доступного голосового ввода. Включите или установите сервис распознавания речи, затем повторите.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7012) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (resultCode == RESULT_OK && text.isNotBlank()) {
            voiceEvent("onJarvisSpeechState", "listening", "Речь распознана.")
            voiceEvent("onJarvisSpeechResult", text)
        } else voiceEvent("onJarvisSpeechState", "idle", "Голосовой ввод завершён. Нажмите на круг, чтобы повторить.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7011) return
        microphonePermissionPending = false
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (pendingMicDiagnostics) {
                if (activityResumed) runMicrophoneDiagnostic()
            } else if (pendingMicStart && activityResumed) {
                startListening()
            }
        } else {
            val diagnosticWasRequested = pendingMicDiagnostics
            pendingMicDiagnostics = false
            pendingMicStart = false
            val message = "Доступ к микрофону не разрешён. Откройте настройки приложения → Разрешения → Микрофон."
            if (diagnosticWasRequested) voiceEvent("onJarvisMicDiagnostic", "error", message)
            else voiceEvent("onJarvisSpeechError", message)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        speechInput.resume()
        if (diagnosticInterrupted) {
            diagnosticInterrupted = false
            voiceEvent("onJarvisMicDiagnostic", "cancelled", "Проверка прервана при сворачивании приложения.")
        }
        if (pendingMicDiagnostics && !microphonePermissionPending) requestMicrophoneDiagnostic()
        else if (pendingMicStart && !microphonePermissionPending) startListening()
    }

    override fun onPause() {
        activityResumed = false
        diagnosticGeneration++
        diagnosticInterrupted = diagnosticInProgress
        diagnosticInProgress = false
        speechInput.pause()
        stopSpeech()
        if (!microphonePermissionPending) pendingMicStart = false
        super.onPause()
    }

    private fun stopSpeech() {
        speechGeneration++
        isSpeaking = false
        resumeListeningAfterSpeech = false
        tts?.stop()
        fishAudioTts.stop()
        speechTimeout?.let { mainHandler.removeCallbacks(it) }
        speechTimeout = null
    }

    private fun finishSpeech(generation: Long) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (generation != speechGeneration || isFinishing || isDestroyed) return@runOnUiThread
            speechTimeout?.let { mainHandler.removeCallbacks(it) }
            speechTimeout = null
            isSpeaking = false
            val resume = resumeListeningAfterSpeech
            resumeListeningAfterSpeech = false
            if (resume && activityResumed) startConversationListening(12_000)
        }
    }

    private fun speak(text: String, resumeAfterSpeech: Boolean = false) {
        if (text.isBlank() || !activityResumed || isFinishing || isDestroyed) return
        stopSpeech()
        speechInput.cancel()
        val generation = speechGeneration
        isSpeaking = true
        resumeListeningAfterSpeech = resumeAfterSpeech
        voiceEvent("onJarvisSpeechState", "speaking", "J.A.R.V.I.S. отвечает. Нажмите на круг, чтобы прервать.")
        speechTimeout = Runnable {
            if (generation == speechGeneration) {
                tts?.stop()
                fishAudioTts.stop()
                finishSpeech(generation)
            }
        }.also { mainHandler.postDelayed(it, 120_000) }
        val apiKey = prefs.getString("fish_api_key", "").orEmpty()
        if (apiKey.isNotBlank() && FishAudioTts.voiceIdFor(selectedPersona) != null) {
            fishAudioTts.speak(text, selectedPersona,
                onError = { runOnUiThread { speakWithSystemVoice(text, generation) } },
                onComplete = { finishSpeech(generation) })
        } else speakWithSystemVoice(text, generation)
    }

    private fun speakWithSystemVoice(text: String, generation: Long, mayWait: Boolean = true) {
        if (generation != speechGeneration || !activityResumed) return
        if (!ttsReady) {
            if (mayWait) mainHandler.postDelayed({ speakWithSystemVoice(text, generation, false) }, 700)
            else finishSpeech(generation)
            return
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-$generation") ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) finishSpeech(generation)
    }

    fun openAccessibilitySettings() { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun openNotificationSettings() { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private fun notificationReply(): String {
        val messages = JarvisNotificationService.latest(10)
        if (messages.isEmpty()) return "Пока нет новых сообщений в уведомлениях WhatsApp или Telegram. Проверьте, что доступ к уведомлениям J.A.R.V.I.S. включён."
        return buildString {
            append("Последние сообщения:\n")
            messages.forEach { message ->
                val appName = when (message.packageName) {
                    JarvisNotificationService.WHATSAPP -> "WhatsApp"
                    JarvisNotificationService.TELEGRAM -> "Telegram"
                    else -> message.packageName
                }
                append("• ").append(appName)
                if (message.title.isNotBlank()) append(" — ").append(message.title)
                if (message.text.isNotBlank()) append(": ").append(message.text)
                append('\n')
            }
        }.trim()
    }


    private fun fetchNewsAndSpeak() {
        showVoiceStatus("Получаю свежие новости…")
        backgroundExecutor.execute {
            try {
                val feeds = listOf(
                    "https://news.google.com/rss?hl=ru&gl=RU&ceid=RU:ru",
                    "https://news.google.com/rss?hl=ru&gl=US&ceid=US:ru"
                )
                var items = emptyList<String>()
                var lastError: Exception? = null

                for (feed in feeds) {
                    try {
                        val connection = (URL(feed).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 10_000
                            readTimeout = 20_000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", "Mozilla/5.0 JARVIS-Android")
                            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
                        }
                        val code = connection.responseCode
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        val xml = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        connection.disconnect()
                        if (code !in 200..299) throw IllegalStateException("HTTP $code")

                        items = Regex("<item>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
                            .findAll(xml)
                            .mapNotNull { match ->
                                val block = match.groupValues[1]
                                val title = Regex("<title>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                                    .find(block)?.groupValues?.get(1)
                                    ?.replace("<![CDATA[", "")?.replace("]]>", "")
                                    ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                                val source = Regex("<source[^>]*>([\\s\\S]*?)</source>", RegexOption.IGNORE_CASE)
                                    .find(block)?.groupValues?.get(1)
                                    ?.replace("<![CDATA[", "")?.replace("]]>", "")
                                    ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                                title?.takeIf { it.isNotBlank() }?.let {
                                    if (source.isNullOrBlank()) it else "$it — $source"
                                }
                            }
                            .distinct()
                            .take(6)
                            .toList()
                        if (items.isNotEmpty()) break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }

                if (items.isEmpty()) throw lastError ?: IllegalStateException("В новостной ленте нет материалов.")
                val prompt = "Сделай краткую нейтральную голосовую сводку свежих новостей на русском языке. Назови 5-6 главных тем по заголовкам ниже, по 1-2 предложения на тему. Не придумывай факты и явно отделяй заголовок от неподтвержденных деталей. Заголовки: " + items.joinToString(" | ")
                val answer = gigaChat.ask(prompt, selectedPersona, "")
                val finalText = if (answer.startsWith("В настройках J.A.R.V.I.S.")) {
                    "Свежие новости: " + items.take(5).joinToString(". ")
                } else answer
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("window.onGigaChatResult && window.onGigaChatResult(${JSONObject.quote(finalText)})", null)
                    }
                    speak(finalText, resumeAfterSpeech = true)
                }
            } catch (e: Exception) {
                val message = "Не удалось получить свежие новости: ${e.message ?: "ошибка соединения"}"
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("window.onGigaChatResult && window.onGigaChatResult(${JSONObject.quote(message)})", null)
                    }
                    speak(message, resumeAfterSpeech = true)
                }
            }
        }
    }

    private fun analyzeLatestWhatsApp() {
        mainHandler.postDelayed({
            backgroundExecutor.execute {
                val notification = JarvisNotificationService.latest(30)
                    .firstOrNull { JarvisNotificationService.isWhatsApp(it.packageName) }
                val screen = if (notification == null) {
                    JarvisAccessibilityService.instance?.visibleText(setOf("com.whatsapp", "com.whatsapp.w4b")).orEmpty()
                } else {
                    ""
                }
                val source = buildString {
                    if (notification != null) {
                        append("Последнее уведомление WhatsApp. Отправитель/чат: ")
                        append(notification.title)
                        append(". Текст: ")
                        append(notification.text)
                    }
                    if (screen.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Текст, видимый на открытом экране WhatsApp:\n")
                        append(screen.take(8000))
                    }
                }.trim()

                val answer = if (source.isBlank()) {
                    "Я открыл WhatsApp, но не смог получить текст последнего сообщения. Проверьте доступ J.A.R.V.I.S. к уведомлениям и специальным возможностям."
                } else {
                    val prompt = "Проанализируй последнее сообщение WhatsApp по данным ниже. Ответь по-русски коротко и естественно для голосового ассистента. Обязательно назови имя отправителя или название группы, если оно видно. Затем объясни простыми словами, о чём сообщение, что человек или группа сообщает, просит или хочет. Не выдумывай отсутствующие сведения и скажи, если данных недостаточно. Данные WhatsApp:\n" + source
                    val result = gigaChat.ask(prompt, selectedPersona, "")
                    if (result.startsWith("В настройках J.A.R.V.I.S.")) {
                        "Последнее сообщение: ${notification?.title.orEmpty()}. ${notification?.text.orEmpty()}".trim()
                    } else result
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("window.onGigaChatResult && window.onGigaChatResult(${JSONObject.quote(answer)})", null)
                    }
                    speak(answer, resumeAfterSpeech = true)
                }
            }
        }, 1500)
    }

    private fun showVoiceStatus(text: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.onGigaChatResult && window.onGigaChatResult(${JSONObject.quote(text)})", null)
            }
        }
    }

    private fun sendToGigaChat(text: String, memoryText: String = text) {
        backgroundExecutor.execute {
            val remote = cloudMemory.recall(memoryText)
            val context = memory.memoryContext() + if (remote.isNotBlank()) "\n\nРелевантные воспоминания:\n" + remote else ""
            val answer = gigaChat.ask(text, selectedPersona, context)
            memory.rememberTurn(memoryText, answer)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (::webView.isInitialized) {
                    val escaped = JSONObject.quote(answer)
                    webView.evaluateJavascript("window.onGigaChatResult && window.onGigaChatResult($escaped)", null)
                }
                speak(answer, resumeAfterSpeech = true)
            }
            cloudMemory.record(memoryText, answer)
        }
    }

    private fun rememberUserName(text: String) {
        val s = text.trim()
        val lower = s.lowercase(Locale("ru", "RU"))
        val marker = when { lower.startsWith("меня зовут ") -> "меня зовут "; lower.startsWith("моё имя ") -> "моё имя "; lower.startsWith("мое имя ") -> "мое имя "; else -> "" }
        if (marker.isNotEmpty()) memory.setUserName(s.substring(marker.length).trim().split(" ").firstOrNull().orEmpty())
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            Toast.makeText(this, "Версия ${BuildConfig.VERSION_NAME}. Обновления устанавливаются из проверенной сборки GitHub Actions.", Toast.LENGTH_LONG).show()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface fun command(text: String): String {
            if (isFinishing || isDestroyed) return "Приложение закрывается."
            return try { executeCommand(text.take(4000)) }
            catch (_: Exception) { "Не удалось выполнить команду. Проверьте разрешения Android." }
        }

        private fun executeCommand(text: String): String {
            val memoryText = text.substringAfter("Запрос пользователя: ", text).trim()
            rememberUserName(memoryText)
            memory.recordHabit(memoryText)
            val normalized = memoryText.lowercase()
            if (
                normalized.contains("кто мне написал") ||
                normalized.contains("прочитай сообщения") ||
                normalized.contains("прочитай сообщение") ||
                normalized.contains("новые сообщения")
            ) return notificationReply()

            if (
                normalized.contains("кто тебя создал") ||
                normalized.contains("кто тебя разработал") ||
                normalized.contains("кто тебя придумал") ||
                normalized.contains("кто твой создатель") ||
                normalized.contains("кто создал тебя")
            ) {
                val answer = if (selectedPersona == "J.A.R.V.I.S.") {
                    "Я J.A.R.V.I.S. — искусственный интеллект и голосовой помощник Тони Старка из фильма «Железный человек»."
                } else {
                    "Я $selectedPersona — персонаж J.A.R.V.I.S. и мой создатель — сам J.A.R.V.I.S. из фильма «Железный человек»."
                }
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (
                normalized.contains("последние чаты") ||
                normalized.contains("покажи последние чаты") ||
                normalized.contains("последние разговоры") ||
                normalized.contains("последний чат") ||
                normalized.contains("что мы обсуждали") ||
                normalized.contains("что я спрашивал") ||
                normalized.contains("что я спрашивала")
            ) {
                val dialogues = memory.recentDialogues().takeLast(8)
                val answer = if (dialogues.isEmpty()) {
                    "Я пока не сохранил прошлые диалоги."
                } else {
                    buildString {
                        append("Вот последние сохранённые диалоги:\n")
                        dialogues.forEachIndexed { index, pair ->
                            append(index + 1).append(". Вы: ").append(pair.first)
                                .append(" | Я: ").append(pair.second).append("\n")
                        }
                    }.trim()
                }
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (
                normalized == "что ты помнишь обо мне" ||
                normalized == "что ты обо мне помнишь" ||
                normalized == "что ты знаешь обо мне" ||
                normalized == "какую информацию ты обо мне помнишь"
            ) {
                val answer = memory.factsSummary()
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (
                normalized.startsWith("запомни что ") ||
                normalized.startsWith("запомни, что ") ||
                normalized.startsWith("запомни: ")
            ) {
                val factText = memoryText
                    .replaceFirst(Regex("(?i)^запомни\\s*,?\\s*"), "")
                    .trim()
                val answer = if (memory.rememberFact(factText)) {
                    "Запомнил: $factText"
                } else {
                    "Не получилось сохранить информацию. Скажите, что именно нужно запомнить."
                }
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (
                normalized == "забудь это" ||
                normalized == "забудь последнее" ||
                normalized == "забудь последнюю информацию"
            ) {
                val answer = if (memory.forgetLastFact()) {
                    "Хорошо, последнюю сохранённую информацию забыл."
                } else {
                    "У меня нет сохранённого факта, который можно забыть."
                }
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (normalized.startsWith("забудь что ") || normalized.startsWith("забудь, что ")) {
                val query = memoryText.replaceFirst(Regex("(?i)^забудь\\s*,?\\s*что\\s*"), "").trim()
                val answer = if (memory.forgetFact(query)) {
                    "Хорошо, эту информацию забыл."
                } else {
                    "Я не нашёл такую информацию в памяти."
                }
                memory.rememberTurn(memoryText, answer)
                return answer
            }

            if (
                normalized == "новости" ||
                normalized.contains("сводка новостей") ||
                normalized.contains("последние новости") ||
                normalized.contains("главные новости")
            ) {
                fetchNewsAndSpeak()
                memory.rememberTurn(memoryText, "Получаю свежую сводку новостей.")
                return "Получаю свежую сводку новостей."
            }

            if (
                normalized.contains("прочитай последнее сообщение в ватсап") ||
                normalized.contains("прочитай последнее сообщение whatsapp")
            ) {
                val hasStoredMessage = JarvisNotificationService.latest(30)
                    .any { JarvisNotificationService.isWhatsApp(it.packageName) }
                val result = if (hasStoredMessage) {
                    "Читаю последнее сообщение WhatsApp."
                } else {
                    router.execute(memoryText)
                }
                analyzeLatestWhatsApp()
                memory.rememberTurn(memoryText, result)
                return result
            }

            if (router.canHandle(memoryText)) {
                val result = router.execute(memoryText)
                memory.rememberTurn(memoryText, result)
                return result
            }
            sendToGigaChat(text, memoryText)
            return ""
        }

        @JavascriptInterface fun startListening() { runOnUiThread { this@MainActivity.startListening() } }
        @JavascriptInterface fun diagnoseMicrophone() { runOnUiThread { this@MainActivity.requestMicrophoneDiagnostic() } }
        @JavascriptInterface fun startConversationWindow(seconds: Int) {
            runOnUiThread { this@MainActivity.startConversationListening(seconds.coerceIn(1, 30) * 1000L) }
        }
        @JavascriptInterface fun speak(text: String) { runOnUiThread { this@MainActivity.speak(text.take(8000), resumeAfterSpeech = true) } }
        @JavascriptInterface fun stopListening() { runOnUiThread { speechInput.cancel() } }
        @JavascriptInterface fun openMicrophoneSettings() { runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } }
        @JavascriptInterface fun setPersona(name: String) { selectedPersona = name; prefs.edit().putString("persona", name).apply() }

        @JavascriptInterface fun setUserProfile(name: String, day: Int, month: Int, year: Int) {
            if (name.isNotBlank()) memory.setUserName(name)
            memory.setBirthDate(day, month, year)
        }

        @JavascriptInterface fun getUserName(): String = memory.getUserName()
        @JavascriptInterface fun getMemorySummary(): String = memory.memoryContext()
        @JavascriptInterface fun setMemoryGateway(url: String, token: String): String {
            return cloudMemory.configure(url, token)
        }
        @JavascriptInterface fun getMemoryGatewayStatus(): String = cloudMemory.status()
        @JavascriptInterface fun getMemoryGatewayUrl(): String = cloudMemory.endpoint()

        // Store only the gateway origin. Instagram OAuth and admin secrets stay out of the APK.
        @JavascriptInterface fun setInstagramGatewayUrl(value: String): String {
            if (value.isBlank()) {
                prefs.edit().remove("instagram_gateway_url").apply()
                return "Адрес шлюза Instagram очищен."
            }
            val origin = try {
                val uri = java.net.URI(value.trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank() ||
                    uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                    (uri.rawPath != null && uri.rawPath != "" && uri.rawPath != "/") ||
                    uri.port > 65535) null
                else "https://" + uri.rawAuthority
            } catch (_: Exception) { null }
            if (origin == null) return "Укажите только HTTPS-адрес сервера, например https://example.com."
            prefs.edit().putString("instagram_gateway_url", origin).apply()
            return "Сервер Instagram сохранён. Для работы Direct настройте API на сервере."
        }

        @JavascriptInterface fun getInstagramGatewayUrl(): String =
            prefs.getString("instagram_gateway_url", "").orEmpty()

        @JavascriptInterface fun openInstagramDashboard(): String {
            val origin = getInstagramGatewayUrl()
            if (origin.isBlank()) return "Сначала сохраните HTTPS-адрес своего шлюза Instagram."
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse(origin + "/admin")))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось открыть шлюз Instagram.", Toast.LENGTH_LONG).show()
                }
            }
            return "Открываю панель Instagram в браузере."
        }



        @JavascriptInterface
        fun setApiKeys(fish: String, giga: String): String {
            val editor = prefs.edit()
            if (fish.isNotBlank()) editor.putString("fish_api_key", fish)
            if (giga.isNotBlank()) editor.putString("gigachat_api_key", giga)
            editor.apply()
            return "Fish Audio: ${if (fish.isNotBlank() || prefs.getString("fish_api_key", "").orEmpty().isNotBlank()) "✓ настроен" else "не настроен"} · GigaChat: ${if (giga.isNotBlank() || prefs.getString("gigachat_api_key", "").orEmpty().isNotBlank()) "✓ настроен" else "не настроен"}"
        }

        @JavascriptInterface
        fun getApiKeyStatus(): String = JSONObject().apply {
            put("fish", prefs.getString("fish_api_key", "").orEmpty().isNotBlank())
            put("giga", prefs.getString("gigachat_api_key", "").orEmpty().isNotBlank())
        }.toString()

        @JavascriptInterface
        fun listApps(): String {
            val array = JSONArray()
            router.launcherApps().forEach {
                array.put(JSONObject().apply {
                    put("label", it.label)
                    put("packageName", it.packageName)
                    put("allowed", router.isAppAllowed(it.packageName))
                })
            }
            return array.toString()
        }

        @JavascriptInterface fun setAppAllowed(packageName: String, allowed: Boolean): String {
            router.setAppAllowed(packageName, allowed)
            return if (allowed) "Приложение добавлено в JARVIS." else "Приложение удалено из JARVIS."
        }

        @JavascriptInterface fun enableAccessibility(): String {
            openAccessibilitySettings()
            return "Откройте J.A.R.V.I.S. в специальных возможностях Android и включите доступ."
        }

        @JavascriptInterface fun enableNotifications(): String {
            openNotificationSettings()
            return "Откройте доступ к уведомлениям для J.A.R.V.I.S. и вернитесь в приложение."
        }

        @JavascriptInterface fun clearMessages(): String {
            JarvisNotificationService.clear(this@MainActivity)
            return "История уведомлений J.A.R.V.I.S. очищена."
        }

        @JavascriptInterface fun checkUpdates() { checkForUpdates(true) }
        @JavascriptInterface fun toast(text: String) { runOnUiThread { Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show() } }
    }

    override fun onDestroy() {
        activityResumed = false
        diagnosticGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        speechInput.destroy()
        tts?.stop()
        tts?.shutdown()
        fishAudioTts.release()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidJarvis")
            webView.destroy()
        }
        super.onDestroy()
    }
}
