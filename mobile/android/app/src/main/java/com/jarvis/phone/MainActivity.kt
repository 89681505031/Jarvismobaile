package com.jarvis.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioFormat
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var router: PhoneCommandRouter
    private lateinit var brain: JarvisBrainEngine
    private lateinit var modelStore: JarvisModelStore
    private lateinit var localLanguageModel: JarvisNativeLanguageModel
    private val brainGeneration = AtomicInteger(0)
    private val modelDownloadRunning = AtomicBoolean(false)
    private val brainSelfTestRunning = AtomicBoolean(false)
    private lateinit var speechInput: SpeechInputController
    private lateinit var offlineWake: OfflineWakeEngine
    private var pendingMicStart = false
    private var pendingMicDiagnostics = false
    private var pendingWakePermission = false
    private var microphonePermissionPending = false
    private var wakeModeEnabled = false
    private var backgroundWakeEnabled = false
    private var pendingBackgroundMic = false
    private var pendingBackgroundNotification = false
    private var pageReady = false
    private var pendingBackgroundCommand: String? = null
    private var systemSpeechOpen = false
    private var interruptByVoice = false
    private var pendingCameraPermission = false
    private var pendingVisionSpeak = false
    private var pendingVisionCapture: File? = null
    private lateinit var skills: SkillCatalog
    private lateinit var reminders: JarvisReminders
    private lateinit var tasks: JarvisTasks
    private lateinit var home: JarvisHomeAssistant
    private lateinit var vision: JarvisVision
    private lateinit var updates: JarvisSignedUpdates
    private lateinit var localInfo: JarvisLocalInfo
    private val newsFeed = JarvisNewsFeed()
    private var pendingWeatherLocation = false
    private var pendingLocalNewsLocation = false
    private var pendingLocationOnly = false
    private var startupPermissionsRequested = false
    private var wakeSessionActive = false
    private var wakeFailures = 0
    private var wakeRestart: Runnable? = null

    @Volatile private var diagnosticGeneration = 0
    private var diagnosticInProgress = false
    private var diagnosticInterrupted = false
    private var speechGeneration = 0L
    private var speechTimeout: Runnable? = null
    private var conversationDeadline = 0L
    private var conversationRestart: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var selectedPersona = "J.A.R.V.I.S."
    private lateinit var fishAudioTts: FishAudioTts
    private lateinit var memory: JarvisMemory
    private var isSpeaking = false
    private var speechPlaybackStarted = false
    private var speechTextLength = 0
    private var resumeListeningAfterSpeech = false
    @Volatile private var ttsAudioEncoding = AudioFormat.ENCODING_PCM_16BIT
    @Volatile private var lastVoiceAmplitudeAt = 0L
    @Volatile private var activityResumed = false
    private val prefs by lazy { getSharedPreferences("jarvis_settings", MODE_PRIVATE) }
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = PhoneCommandRouter(this)
        skills = SkillCatalog(this)
        reminders = JarvisReminders(this)
        tasks = JarvisTasks(this)
        home = JarvisHomeAssistant(this)
        vision = JarvisVision(this)
        localInfo = JarvisLocalInfo(this)
        updates = JarvisSignedUpdates(this) { status ->
            runOnUiThread {
                voiceEvent("onJarvisFeatureStatus", "updates", status)
                showVoiceStatus(status)
            }
        }
        interruptByVoice = prefs.getBoolean("interrupt_voice", false)
        selectedPersona = canonicalPersona(prefs.getString("persona", "J.A.R.V.I.S.") ?: "J.A.R.V.I.S.")
        prefs.edit().putString("persona", selectedPersona).apply()
        wakeModeEnabled = prefs.getBoolean("wake_mode", false)
        backgroundWakeEnabled = prefs.getBoolean("background_wake", false)
        pendingBackgroundCommand = intent?.takeIf { it.action == WakeForegroundService.ACTION_OPEN_COMMAND }
            ?.getStringExtra(WakeForegroundService.EXTRA_COMMAND)?.take(240)
        fishAudioTts = FishAudioTts(this)
        memory = JarvisMemory(this)
        modelStore = JarvisModelStore(this)
        localLanguageModel = JarvisNativeLanguageModel(modelStore)
        brain = JarvisBrainEngine(memory, localLanguageModel)
        if (modelStore.modelFile() != null) {
            modelExecutor.execute {
                localLanguageModel.prepare()
            }
        }
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("ru", "RU")
                tts?.let { PersonaSpeech.apply(it, selectedPersona) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val id = utteranceId?.substringAfterLast("-")?.toLongOrNull()
                        if (id != null) runOnUiThread { speechPlaybackBegan(id) }
                    }
                    override fun onBeginSynthesis(
                        utteranceId: String?,
                        sampleRateInHz: Int,
                        audioFormat: Int,
                        channelCount: Int
                    ) {
                        ttsAudioEncoding = audioFormat
                    }
                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        val id = utteranceId?.substringAfterLast("-")?.toLongOrNull() ?: return
                        if (id != speechGeneration || audio == null || audio.isEmpty()) return
                        emitVoiceAmplitude(pcmAmplitude(audio, ttsAudioEncoding))
                    }
                    override fun onDone(utteranceId: String?) { utteranceId?.substringAfterLast("-")?.toLongOrNull()?.let { finishSpeech(it) } }
                    override fun onError(utteranceId: String?) { utteranceId?.substringAfterLast("-")?.toLongOrNull()?.let { finishSpeech(it) } }
                })
            }
        }
        setupSpeechRecognizer()
        offlineWake = OfflineWakeEngine(
            this,
            onStatus = { state, message ->
                voiceEvent("onJarvisWakeModel", state, message)
                if (state == "model_needed" || state == "error")
                    voiceEvent("onJarvisWakeStatus", state, message)
                if (state == "listening")
                    voiceEvent("onJarvisWakeStatus", "listening", message)
            },
            onMatch = { activation ->
                if (isSpeaking) {
                    val stop = activation.command.trim().lowercase(Locale("ru", "RU"))
                    if (interruptByVoice && stop in setOf("стоп", "замолчи", "прекрати", "хватит")) {
                        offlineWake.stop()
                        wakeSessionActive = false
                        stopSpeech()
                        voiceEvent("onJarvisInterruptStatus", "Ответ прерван голосом.")
                        if (activityResumed && wakeModeEnabled) scheduleWakeRestart(550L)
                    }
                    // Ignore other words while speaking; don't execute commands
                    // accidentally from the phone's own speaker.
                } else {
                    offlineWake.stop()
                    wakeSessionActive = false
                    selectedPersona = canonicalPersona(activation.persona)
                    prefs.edit().putString("persona", selectedPersona).apply()
                    voiceEvent("onJarvisWakeDetected", selectedPersona, activation.command)
                    if (activation.command.isBlank()) speak("Слушаю", resumeAfterSpeech = true)
                }
            },
            stayOpenOnMatch = true,
            onVoiceActivity = { strength ->
                if (activityResumed && wakeModeEnabled && !isSpeaking)
                    voiceEvent("onJarvisWakeActivity", strength)
            }
        )
        // Old builds used repeating SpeechRecognizer sessions, causing audible
        // system chimes. Never resume that legacy mode without the offline model.
        if (wakeModeEnabled && !offlineWake.installed()) {
            wakeModeEnabled = false
            prefs.edit().putBoolean("wake_mode", false).apply()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean = true
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    voiceEvent("onJarvisPersonaChanged", selectedPersona)
                    deliverBackgroundCommand()
                    // At most one metadata request a day. Installation always
                    // requires a separate user tap and signature verification.
                    updates.checkAutomaticallyOnLaunch()
                }
            }
            addJavascriptInterface(AndroidBridge(), "AndroidJarvis")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)

    }

    private fun deliverBackgroundCommand() {
        if (!pageReady || !activityResumed || pendingBackgroundCommand.isNullOrBlank()) return
        val text = pendingBackgroundCommand!!
        pendingBackgroundCommand = null
        voiceEvent("onJarvisPendingBackgroundCommand", text)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == WakeForegroundService.ACTION_OPEN_COMMAND) {
            pendingBackgroundCommand = intent.getStringExtra(WakeForegroundService.EXTRA_COMMAND)?.take(240)
            deliverBackgroundCommand()
        }
    }

    private fun voiceEvent(name: String, vararg values: Any) {
        if (isFinishing || isDestroyed || !::webView.isInitialized) return
        val args = values.joinToString(",") { if (it is Number) it.toString() else JSONObject.quote(it.toString()) }
        webView.evaluateJavascript("window.$name && window.$name($args)", null)
    }

    private fun setupSpeechRecognizer() {
        // Android's recognizer is only used for an actual command, never
        // restarted indefinitely while waiting for a name.
        speechInput = SpeechInputController(this,
            onState = { state, message -> voiceEvent("onJarvisSpeechState", state, message) },
            onText = { text ->
                conversationDeadline = 0L
                conversationRestart?.let { mainHandler.removeCallbacks(it) }
                conversationRestart = null
                voiceEvent("onJarvisSpeechResult", text)
            },
            onError = { error ->
                voiceEvent("onJarvisSpeechError", error)
                val remaining = conversationDeadline - SystemClock.elapsedRealtime()
                if (remaining > 1_000L && activityResumed && !diagnosticInProgress) {
                    conversationRestart?.let { mainHandler.removeCallbacks(it) }
                    conversationRestart = Runnable {
                        conversationRestart = null
                        startConversationListeningUntilDeadline()
                    }.also { mainHandler.postDelayed(it, 450L) }
                } else {
                    conversationDeadline = 0L
                    if (wakeModeEnabled && activityResumed && !diagnosticInProgress)
                        scheduleWakeRestart(1700L)
                }
            },
            onLevel = { level -> voiceEvent("onJarvisSpeechLevel", level) },
            onUnavailable = { startSystemSpeechInput() })
    }

    private fun cancelWakeRestart() {
        wakeRestart?.let { mainHandler.removeCallbacks(it) }
        wakeRestart = null
    }

    private fun scheduleWakeRestart(delay: Long) {
        cancelWakeRestart()
        if (!wakeModeEnabled || !activityResumed || diagnosticInProgress) return
        wakeRestart = Runnable {
            wakeRestart = null
            startWakeSession()
        }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun startWakeSession() {
        if (!wakeModeEnabled || !activityResumed || isSpeaking || diagnosticInProgress ||
            wakeSessionActive || speechInput.isListening || microphonePermissionPending ||
            isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceEvent("onJarvisWakeStatus", "error", "Разрешите доступ к микрофону.")
            return
        }
        if (!offlineWake.installed()) {
            voiceEvent("onJarvisWakeStatus", "model_needed",
                "Тихий режим требует офлайн-модель. Загрузите её один раз в настройках.")
            return
        }
        wakeSessionActive = true
        offlineWake.start()
    }

    private fun setWakeModeEnabled(enabled: Boolean) {
        cancelWakeRestart()
        if (!enabled) {
            if (backgroundWakeEnabled) setBackgroundWakeEnabled(false)
            pendingWakePermission = false
            wakeModeEnabled = false
            prefs.edit().putBoolean("wake_mode", false).apply()
            wakeSessionActive = false
            offlineWake.stop()
            voiceEvent("onJarvisWakeModeChanged", false)
            voiceEvent("onJarvisWakeStatus", "off", "Ожидание имени отключено.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakePermission = true
            voiceEvent("onJarvisWakeStatus", "permission", "Разрешите микрофон для ожидания имени.")
            if (!microphonePermissionPending) {
                microphonePermissionPending = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7011)
            }
            return
        }
        pendingWakePermission = false
        if (!offlineWake.installed()) {
            wakeModeEnabled = false
            prefs.edit().putBoolean("wake_mode", false).apply()
            voiceEvent("onJarvisWakeModeChanged", false)
            voiceEvent("onJarvisWakeStatus", "model_needed",
                "Загрузите офлайн-модель в настройках (около 46 МБ), затем включите ожидание имени.")
            return
        }
        wakeModeEnabled = true
        prefs.edit().putBoolean("wake_mode", true).apply()
        voiceEvent("onJarvisWakeModeChanged", true)
        voiceEvent("onJarvisWakeStatus", "starting", "Тихое ожидание включено, только пока приложение открыто.")
        scheduleWakeRestart(500L)
    }

    private fun startBackgroundServiceIfEligible() {
        if (!backgroundWakeEnabled || !wakeModeEnabled || !activityResumed ||
            isFinishing || isDestroyed || !offlineWake.installed()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Keep the user's saved preference. Permission may be temporarily
            // revoked; showing an error must not silently turn the setting off.
            voiceEvent("onJarvisBackgroundWakeChanged", backgroundWakeEnabled)
            voiceEvent("onJarvisBackgroundWakeStatus", "error",
                "Для фонового микрофона разрешите уведомления JARVIS.")
            return
        }
        if (WakeForegroundService.active != null) return
        // Must happen while this Activity is visible on Android 12–15:
        // calling startForegroundService from onPause is too late.
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, WakeForegroundService::class.java)
                    .setAction(WakeForegroundService.ACTION_START)
            )
            voiceEvent("onJarvisBackgroundWakeStatus", "ready",
                "Фоновый режим готов. При сворачивании микрофон перейдёт в службу с постоянным уведомлением.")
        } catch (_: Exception) {
            // A transient OEM/Android service-start failure is operational state,
            // not a user request to disable background listening.
            voiceEvent("onJarvisBackgroundWakeChanged", backgroundWakeEnabled)
            voiceEvent("onJarvisBackgroundWakeStatus", "error",
                "Android временно не запустил фоновый режим. Настройка сохранена; откройте JARVIS и попробуйте снова.")
        }
    }

    private fun setBackgroundWakeEnabled(enabled: Boolean) {
        if (!enabled) {
            backgroundWakeEnabled = false
            pendingBackgroundMic = false
            pendingBackgroundNotification = false
            prefs.edit().putBoolean("background_wake", false).apply()
            WakeForegroundService.shouldListenInBackground = false
            WakeForegroundService.microphoneHandoffReady = false
            stopService(Intent(this, WakeForegroundService::class.java))
            voiceEvent("onJarvisBackgroundWakeChanged", false)
            voiceEvent("onJarvisBackgroundWakeStatus", "off", "Работа в фоне отключена.")
            return
        }
        if (!wakeModeEnabled || !offlineWake.installed()) {
            voiceEvent("onJarvisBackgroundWakeChanged", false)
            voiceEvent("onJarvisBackgroundWakeStatus", "error",
                "Сначала установите офлайн-модель и включите активацию по имени.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundMic = true
            voiceEvent("onJarvisBackgroundWakeStatus", "permission", "Разрешите микрофон.")
            if (!microphonePermissionPending) {
                microphonePermissionPending = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7011)
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingBackgroundNotification = true
            voiceEvent("onJarvisBackgroundWakeStatus", "permission",
                "Разрешите уведомления: в них находится видимая кнопка выключения фонового микрофона.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7013)
            return
        }
        pendingBackgroundMic = false
        pendingBackgroundNotification = false
        backgroundWakeEnabled = true
        prefs.edit().putBoolean("background_wake", true).apply()
        voiceEvent("onJarvisBackgroundWakeChanged", true)
        startBackgroundServiceIfEligible()
    }

    private fun minimizeToBackground(): String {
        if (!wakeModeEnabled || !offlineWake.installed()) {
            return "Сначала установите офлайн-модель и включите активацию по имени."
        }
        if (!backgroundWakeEnabled) {
            return "Сначала включите «Продолжать слушать в свёрнутом виде» в настройках."
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Разрешите микрофон, затем повторите."
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "Разрешите уведомления JARVIS, затем повторите."
        }

        startBackgroundServiceIfEligible()
        voiceEvent("onJarvisBackgroundWakeStatus", "handoff",
            "Перехожу в фон. После сворачивания микрофон перейдёт в фоновую службу.")
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed && activityResumed) moveTaskToBack(true)
        }, 350L)
        return "Перехожу в фоновый режим."
    }

    private fun startListening() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (wakeSessionActive) {
            wakeSessionActive = false
            cancelWakeRestart()
            offlineWake.stop { if (activityResumed) startListening() }
            return
        }
        cancelWakeRestart()
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
        conversationDeadline = SystemClock.elapsedRealtime() + durationMs.coerceIn(1_000L, 30_000L)
        startConversationListeningUntilDeadline()
    }

    private fun startConversationListeningUntilDeadline() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (isSpeaking) { resumeListeningAfterSpeech = true; return }
        if (diagnosticInProgress) return
        val remaining = conversationDeadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            conversationDeadline = 0L
            if (wakeModeEnabled) scheduleWakeRestart(650L)
            return
        }
        cancelWakeRestart()
        if (wakeSessionActive) {
            wakeSessionActive = false
            offlineWake.stop { if (activityResumed) startConversationListeningUntilDeadline() }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            conversationDeadline = 0L
            return
        }
        speechInput.start(remaining.coerceIn(5_000L, 30_000L))
    }

    private fun closeConversationWindow() {
        conversationDeadline = 0L
        conversationRestart?.let { mainHandler.removeCallbacks(it) }
        conversationRestart = null
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
        cancelWakeRestart()
        if (wakeSessionActive) {
            wakeSessionActive = false
            offlineWake.stop { if (activityResumed) runMicrophoneDiagnostic() }
            return
        }
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
                    if (wakeModeEnabled) scheduleWakeRestart(950L)
                }
            }
        }
    }

    private fun startSystemSpeechInput() {
        systemSpeechOpen = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите команду J.A.R.V.I.S.")
        }
        try { startActivityForResult(intent, 7012) }
        catch (_: Exception) {
            systemSpeechOpen = false
            voiceEvent("onJarvisSpeechError", "В Android нет доступного голосового ввода. Включите или установите сервис распознавания речи, затем повторите.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7440) {
            if (resultCode != RESULT_OK) {
                voiceEvent("onJarvisBrainModelStatus", "cancelled", "Выбор модели отменён.")
                return
            }
            val uri = data?.data
            if (uri == null) {
                voiceEvent("onJarvisBrainModelStatus", "error", "Android не вернул файл модели.")
                return
            }
            showVoiceStatus("Копирую GGUF-модель в локальную память JARVIS…")
            backgroundExecutor.execute {
                val installed = modelStore.installFrom(uri)
                runOnUiThread {
                    installed.fold(
                        onSuccess = { info ->
                            localLanguageModel.unload()
                            val preloaded = localLanguageModel.prepare()
                            brainGeneration.incrementAndGet()
                            val text = "GGUF-модель сохранена локально: " +
                                modelStore.humanSize(info.bytes) +
                                if (preloaded) {
                                    ". Модель загружена в RAM и готова к быстрому ответу."
                                } else {
                                    ". JARVIS BRAIN загрузит её при первом вопросе."
                                }
                            voiceEvent("onJarvisBrainModelStatus", "stored", text)
                            showVoiceStatus(text)
                        },
                        onFailure = { error ->
                            val text = error.message ?: "Не удалось сохранить GGUF-модель."
                            voiceEvent("onJarvisBrainModelStatus", "error", text)
                            showVoiceStatus(text)
                        }
                    )
                }
            }
            return
        }
        if (requestCode == 7411 || requestCode == 7412) {
            if (resultCode == RESULT_OK) {
                if (requestCode == 7411) {
                    val file = pendingVisionCapture
                    pendingVisionCapture = null
                    val speakResult = pendingVisionSpeak
                    pendingVisionSpeak = false
                    if (file == null || !file.isFile) showVoiceStatus("Камера не вернула полный снимок.")
                    else vision.fromPhoto(Uri.fromFile(file)) { result ->
                        file.delete()
                        runOnUiThread {
                            voiceEvent("onJarvisFeatureStatus", "vision", result)
                            showVoiceStatus(result)
                            if (speakResult && activityResumed) speak(result, resumeAfterSpeech = true)
                        }
                    }
                } else {
                    val uri = data?.data
                    if (uri == null) showVoiceStatus("Изображение не выбрано.")
                    else vision.fromPhoto(uri) { result ->
                        runOnUiThread {
                            voiceEvent("onJarvisFeatureStatus", "vision", result)
                            showVoiceStatus(result)
                        }
                    }
                }
            } else if (requestCode == 7411) {
                pendingVisionCapture?.delete()
                pendingVisionCapture = null
                pendingVisionSpeak = false
            }
            return
        }
        if (requestCode != 7012) return
        systemSpeechOpen = false
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (resultCode == RESULT_OK && text.isNotBlank()) {
            voiceEvent("onJarvisSpeechState", "listening", "Речь распознана.")
            voiceEvent("onJarvisSpeechResult", text)
        } else voiceEvent("onJarvisSpeechState", "idle", "Голосовой ввод завершён. Нажмите на круг, чтобы повторить.")
    }

    private fun requestStartupRuntimePermissions() {
        if (!activityResumed || isFinishing || isDestroyed) return
        val missing = mutableListOf<String>()
        fun addIfMissing(permission: String) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                missing += permission
        }
        addIfMissing(Manifest.permission.RECORD_AUDIO)
        addIfMissing(Manifest.permission.CAMERA)
        addIfMissing(Manifest.permission.ACCESS_COARSE_LOCATION)
        addIfMissing(Manifest.permission.READ_CONTACTS)
        addIfMissing(Manifest.permission.READ_CALL_LOG)
        addIfMissing(Manifest.permission.CALL_PHONE)
        if (android.os.Build.VERSION.SDK_INT >= 33) addIfMissing(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) {
            voiceEvent("onJarvisPermissionsStatus", "granted", "Основные разрешения JARVIS уже выданы.")
            return
        }
        voiceEvent("onJarvisPermissionsStatus", "request",
            "Android запросит основные разрешения JARVIS. Специальные доступы Android выдаются отдельно в системных настройках.")
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7430)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7430) {
            val denied = permissions.filterIndexed { index, _ ->
                grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
            }
            val state = if (denied.isEmpty()) "granted" else "partial"
            val message = if (denied.isEmpty())
                "Основные разрешения JARVIS выданы."
            else
                "Часть разрешений не выдана. Их можно разрешить позже в настройках Android."
            voiceEvent("onJarvisPermissionsStatus", state, message)
            voiceEvent("onJarvisWakeModeChanged", prefs.getBoolean("wake_mode", false))
            voiceEvent("onJarvisBackgroundWakeChanged", prefs.getBoolean("background_wake", false))
            return
        }
        if (requestCode == 7421) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val weather = pendingWeatherLocation
            val localNews = pendingLocalNewsLocation
            val justPermission = pendingLocationOnly
            pendingWeatherLocation = false
            pendingLocalNewsLocation = false
            pendingLocationOnly = false
            if (granted) {
                voiceEvent("onJarvisLocationStatus", "granted",
                    "Примерное местоположение разрешено только для погоды и местных новостей.")
                when {
                    weather -> fetchWeatherAndSpeak()
                    localNews -> fetchLocalNewsAndSpeak()
                    justPermission -> showVoiceStatus("Примерное местоположение разрешено.")
                }
            } else {
                val message = "Местоположение не разрешено. Погода и местные новости по району останутся выключены."
                voiceEvent("onJarvisLocationStatus", "denied", message)
                if (weather || localNews) speak(message, resumeAfterSpeech = true)
            }
            return
        }
        if (requestCode == 7413) {
            val wanted = pendingCameraPermission
            val speakResult = pendingVisionSpeak
            pendingCameraPermission = false
            pendingVisionSpeak = false
            if (wanted && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                openVisionCamera(speakResult)
            else showVoiceStatus("Разрешите камеру для снимка или выберите готовую фотографию.")
            return
        }
        if (requestCode == 7013) {
            val requested = pendingBackgroundNotification
            pendingBackgroundNotification = false
            if (requested && activityResumed) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) setBackgroundWakeEnabled(true)
                else {
                    voiceEvent("onJarvisBackgroundWakeChanged", false)
                    voiceEvent("onJarvisBackgroundWakeStatus", "error",
                        "Уведомления не разрешены. Фоновый микрофон оставлен выключенным.")
                }
            }
            return
        }
        if (requestCode != 7011) return
        microphonePermissionPending = false
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (pendingMicDiagnostics) {
                if (activityResumed) runMicrophoneDiagnostic()
            } else if (pendingMicStart && activityResumed) {
                startListening()
            } else if (pendingWakePermission && activityResumed) {
                setWakeModeEnabled(true)
            } else if (pendingBackgroundMic && activityResumed) {
                pendingBackgroundMic = false
                setBackgroundWakeEnabled(true)
            }
        } else {
            val diagnosticWasRequested = pendingMicDiagnostics
            val wakeWasRequested = pendingWakePermission
            val backgroundWasRequested = pendingBackgroundMic
            pendingBackgroundMic = false
            pendingWakePermission = false
            pendingMicDiagnostics = false
            pendingMicStart = false
            val message = "Доступ к микрофону не разрешён. Откройте настройки приложения → Разрешения → Микрофон."
            if (diagnosticWasRequested) voiceEvent("onJarvisMicDiagnostic", "error", message)
            else if (wakeWasRequested) {
                voiceEvent("onJarvisWakeModeChanged", false)
                voiceEvent("onJarvisWakeStatus", "error", message)
            } else if (backgroundWasRequested) {
                voiceEvent("onJarvisBackgroundWakeChanged", false)
                voiceEvent("onJarvisBackgroundWakeStatus", "error", message)
            } else voiceEvent("onJarvisSpeechError", message)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (!startupPermissionsRequested) {
            startupPermissionsRequested = true
            mainHandler.postDelayed({ requestStartupRuntimePermissions() }, 500L)
        }
        wakeModeEnabled = prefs.getBoolean("wake_mode", false)
        backgroundWakeEnabled = prefs.getBoolean("background_wake", false)
        speechInput.resume()
        // Stop the service's recorder before reacquiring audio for the visible
        // screen; this prevents two Vosk sessions competing for the microphone.
        WakeForegroundService.shouldListenInBackground = false
        WakeForegroundService.microphoneHandoffReady = false
        if (diagnosticInterrupted) {
            diagnosticInterrupted = false
            voiceEvent("onJarvisMicDiagnostic", "cancelled",
                "Проверка прервана при сворачивании приложения.")
        }
        voiceEvent("onJarvisWakeModeChanged", wakeModeEnabled)
        voiceEvent("onJarvisBackgroundWakeChanged", backgroundWakeEnabled)
        val service = if (backgroundWakeEnabled) WakeForegroundService.active else null
        if (service != null) service.pauseForForeground { resumeForegroundMicrophone() }
        else resumeForegroundMicrophone()
        if (backgroundWakeEnabled && wakeModeEnabled) startBackgroundServiceIfEligible()
        deliverBackgroundCommand()
        updates.resumePendingInstallIfAllowed()
    }

    private fun resumeForegroundMicrophone() {
        if (!activityResumed || isFinishing || isDestroyed) return
        if (pendingMicDiagnostics && !microphonePermissionPending) requestMicrophoneDiagnostic()
        else if (pendingMicStart && !microphonePermissionPending) startListening()
        else if (pendingWakePermission && !microphonePermissionPending) setWakeModeEnabled(true)
        else if (pendingBackgroundMic && !microphonePermissionPending) setBackgroundWakeEnabled(true)
        else if (wakeModeEnabled && !pendingBackgroundNotification) scheduleWakeRestart(500L)
    }

    override fun onPause() {
        activityResumed = false
        cancelWakeRestart()
        closeConversationWindow()
        diagnosticGeneration++
        val diagnosticRunning = diagnosticInProgress
        diagnosticInterrupted = diagnosticRunning
        diagnosticInProgress = false
        speechInput.pause()
        stopSpeech()
        val handoff = backgroundWakeEnabled && wakeModeEnabled && !systemSpeechOpen &&
            !diagnosticRunning && !isFinishing && !isDestroyed
        wakeSessionActive = false
        WakeForegroundService.shouldListenInBackground = handoff
        WakeForegroundService.microphoneHandoffReady = false
        offlineWake.stop {
            // This callback fires AFTER the visible Activity has fully released
            // its AudioRecord. If the Service already exists it starts now; if
            // Android is still creating it, onStartCommand sees these flags later.
            if (handoff && !activityResumed &&
                prefs.getBoolean("background_wake", false) &&
                prefs.getBoolean("wake_mode", false)) {
                WakeForegroundService.microphoneHandoffReady = true
                WakeForegroundService.active?.startBackgroundListening()
            } else {
                WakeForegroundService.shouldListenInBackground = false
                WakeForegroundService.microphoneHandoffReady = false
            }
        }
        // Safety net for OEMs that delay the Vosk shutdown callback while the
        // Activity is moving to background. The service itself now retries if
        // Android still reports the microphone as busy.
        if (handoff) {
            mainHandler.postDelayed({
                if (!activityResumed &&
                    prefs.getBoolean("background_wake", false) &&
                    prefs.getBoolean("wake_mode", false)) {
                    WakeForegroundService.shouldListenInBackground = true
                    WakeForegroundService.microphoneHandoffReady = true
                    WakeForegroundService.active?.startBackgroundListening()
                }
            }, 1_000L)
        }
        if (!handoff) {
            WakeForegroundService.shouldListenInBackground = false
            WakeForegroundService.microphoneHandoffReady = false
        }
        voiceEvent(
            "onJarvisWakeStatus", "paused",
            if (handoff) "Переключаю тихое ожидание в фоновый режим…"
            else "Ожидание приостановлено."
        )
        if (!microphonePermissionPending) pendingMicStart = false
        super.onPause()
    }

    private fun canonicalPersona(name: String): String = when (name.trim().lowercase(Locale("ru", "RU"))) {
        "cyber", "сайбер", "кибер" -> "Кибер"
        "astra", "астра" -> "Astra"
        "luna", "луна" -> "Luna"
        "terra", "терра" -> "Terra"
        "jarvis", "j.a.r.v.i.s", "j.a.r.v.i.s.", "джарвис" -> "J.A.R.V.I.S."
        else -> name.trim().ifBlank { "J.A.R.V.I.S." }
    }

    private fun emitVoiceAmplitude(level: Float) {
        if (!activityResumed || (!isSpeaking && level > 0f)) return
        val now = SystemClock.elapsedRealtime()
        if (level > 0f && now - lastVoiceAmplitudeAt < 32L) return
        lastVoiceAmplitudeAt = now
        val safe = level.coerceIn(0f, 1f)
        mainHandler.post {
            if (!isFinishing && !isDestroyed && activityResumed &&
                (isSpeaking || safe == 0f)) {
                voiceEvent("onJarvisVoiceAmplitude", safe)
            }
        }
    }

    private fun pcmAmplitude(audio: ByteArray, encoding: Int): Float {
        if (audio.isEmpty()) return 0f
        var sum = 0.0
        var count = 0
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            for (b in audio) {
                val sample = (b.toInt() and 0xff) - 128
                sum += sample * sample
                count++
            }
            return (sqrt(sum / count.coerceAtLeast(1)) / 128.0 * 2.8)
                .coerceIn(0.0, 1.0).toFloat()
        }
        var i = 0
        while (i + 1 < audio.size) {
            val sample = (((audio[i + 1].toInt() shl 8) or
                (audio[i].toInt() and 0xff))).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32768.0 * 3.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun speechPlaybackBegan(generation: Long) {
        if (generation != speechGeneration || !isSpeaking ||
            speechPlaybackStarted || !activityResumed || isFinishing || isDestroyed) return
        speechPlaybackStarted = true
        // Starts Variant A exactly when the engine begins playback, not while
        // a cloud audio file is still downloading / being prepared.
        voiceEvent("onJarvisSpeechState", "speaking",
            "$selectedPersona отвечает. Нажмите на голограмму, чтобы прервать.")
        voiceEvent("onJarvisPersonaChanged", selectedPersona)
        voiceEvent("onJarvisSpeakState", "start", speechTextLength)
    }

    private fun stopSpeech() {
        val wasSpeaking = speechPlaybackStarted
        speechGeneration++
        isSpeaking = false
        speechPlaybackStarted = false
        speechTextLength = 0
        resumeListeningAfterSpeech = false
        tts?.stop()
        fishAudioTts.stop()
        speechTimeout?.let { mainHandler.removeCallbacks(it) }
        speechTimeout = null
        emitVoiceAmplitude(0f)
        if (wasSpeaking) voiceEvent("onJarvisSpeakState", "stop")
    }

    private fun finishSpeech(generation: Long) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (generation != speechGeneration || isFinishing || isDestroyed) return@runOnUiThread
            speechTimeout?.let { mainHandler.removeCallbacks(it) }
            speechTimeout = null
            isSpeaking = false
            val hadPlayback = speechPlaybackStarted
            speechPlaybackStarted = false
            speechTextLength = 0
            emitVoiceAmplitude(0f)
            if (hadPlayback) voiceEvent("onJarvisSpeakState", "stop")
            else voiceEvent("onJarvisSpeechState", "idle", "Голосовой ответ завершён.")
            val resume = resumeListeningAfterSpeech
            resumeListeningAfterSpeech = false
            if (resume && activityResumed && prefs.getBoolean("continuous_dialogue", true)) {
                voiceEvent("onJarvisConversationWindow", true, 30)
                startConversationListening(30_000)
            } else if (wakeModeEnabled && activityResumed) {
                if (wakeSessionActive) {
                    wakeSessionActive = false
                    offlineWake.stop { scheduleWakeRestart(850L) }
                } else scheduleWakeRestart(850L)
            }
        }
    }

    private fun speak(text: String, resumeAfterSpeech: Boolean = false) {
        if (text.isBlank() || !activityResumed || isFinishing || isDestroyed) return
        stopSpeech()
        cancelWakeRestart()
        wakeSessionActive = false
        offlineWake.stop()
        speechInput.cancel()
        val generation = speechGeneration
        isSpeaking = true
        speechPlaybackStarted = false
        speechTextLength = text.length
        resumeListeningAfterSpeech = resumeAfterSpeech
        voiceEvent("onJarvisSpeechState", "processing", "Готовлю голосовой ответ…")
        speechTimeout = Runnable {
            if (generation == speechGeneration) {
                tts?.stop()
                fishAudioTts.stop()
                finishSpeech(generation)
            }
        }.also { mainHandler.postDelayed(it, 120_000) }
        if (interruptByVoice && wakeModeEnabled && offlineWake.installed() && activityResumed) {
            mainHandler.postDelayed({
                if (isSpeaking && interruptByVoice && activityResumed && !wakeSessionActive) {
                    wakeSessionActive = true
                    offlineWake.start()
                }
            }, 450L)
        }
        val apiKey = prefs.getString("fish_api_key", "").orEmpty()
        if (apiKey.isNotBlank() && FishAudioTts.voiceIdFor(selectedPersona) != null) {
            fishAudioTts.speak(text, selectedPersona,
                onError = { runOnUiThread { speakWithSystemVoice(text, generation) } },
                onComplete = { finishSpeech(generation) },
                onStart = { runOnUiThread { speechPlaybackBegan(generation) } },
                onAmplitude = { level -> emitVoiceAmplitude(level) })
        } else speakWithSystemVoice(text, generation)
    }

    private fun speakWithSystemVoice(text: String, generation: Long, mayWait: Boolean = true) {
        if (generation != speechGeneration || !activityResumed) return
        if (!ttsReady) {
            if (mayWait) mainHandler.postDelayed({ speakWithSystemVoice(text, generation, false) }, 700)
            else finishSpeech(generation)
            return
        }
        tts?.let { PersonaSpeech.apply(it, selectedPersona) }
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
            val finalText = try {
                val items = newsFeed.fetchMainHeadlines()
                JarvisNewsSummary.fallback(items)
            } catch (_: Exception) {
                "Не удалось получить свежие новости. Проверьте интернет и повторите запрос."
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent("onJarvisBrainResult", finalText)
                speak(finalText, resumeAfterSpeech = true)
            }
        }
    }

    private fun hasApproximateLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestApproximateLocation(forWeather: Boolean = false, forLocalNews: Boolean = false) {
        if (hasApproximateLocation()) {
            when {
                forWeather -> fetchWeatherAndSpeak()
                forLocalNews -> fetchLocalNewsAndSpeak()
                else -> voiceEvent("onJarvisLocationStatus", "granted",
                    "Примерное местоположение уже разрешено.")
            }
            return
        }
        pendingWeatherLocation = forWeather
        pendingLocalNewsLocation = forLocalNews
        pendingLocationOnly = !forWeather && !forLocalNews
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            voiceEvent("onJarvisLocationStatus", "request",
                "Android попросит примерное местоположение. Точная геопозиция не нужна.")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                7421
            )
        }
    }

    private fun fetchWeatherAndSpeak() {
        if (!hasApproximateLocation()) {
            requestApproximateLocation(forWeather = true)
            return
        }
        showVoiceStatus("Определяю примерный район и получаю погоду…")
        localInfo.requestWeather { weather ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent("onJarvisLocalInfo", "weather", weather)
                voiceEvent("onJarvisBrainResult", weather)
                speak(weather, resumeAfterSpeech = true)
            }
        }
    }

    private fun fetchLocalNewsAndSpeak() {
        if (!hasApproximateLocation()) {
            requestApproximateLocation(forLocalNews = true)
            return
        }
        showVoiceStatus("Ищу свежие новости рядом с текущим районом…")
        localInfo.requestLocalNewsHeadlines { result ->
            val value = result.fold(
                onSuccess = { (place, headlines) ->
                    if (headlines.isEmpty()) {
                        "Не нашёл свежих местных заголовков для ${place.label}."
                    } else {
                        JarvisNewsSummary.fallback(headlines, place.label)
                    }
                },
                onFailure = {
                    "Не удалось получить местные новости. Проверьте интернет, геолокацию и повторите."
                }
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent("onJarvisLocalInfo", "local_news", value)
                voiceEvent("onJarvisBrainResult", value)
                speak(value, resumeAfterSpeech = true)
            }
        }
    }
    private fun analyzeLatestWhatsApp() {
        mainHandler.postDelayed({
            backgroundExecutor.execute {
                val notification = JarvisNotificationService.latest(30)
                    .firstOrNull { JarvisNotificationService.isWhatsApp(it.packageName) }
                val screen = if (notification == null) {
                    JarvisAccessibilityService.instance
                        ?.visibleText(setOf("com.whatsapp", "com.whatsapp.w4b"))
                        .orEmpty()
                } else {
                    ""
                }

                val answer = JarvisMessageAnalyzer.summarize(
                    senderOrChat = notification?.title.orEmpty(),
                    message = notification?.text.orEmpty(),
                    visibleScreenText = screen
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.onJarvisBrainResult && window.onJarvisBrainResult(${JSONObject.quote(answer)})",
                            null
                        )
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
                webView.evaluateJavascript("window.onJarvisBrainResult && window.onJarvisBrainResult(${JSONObject.quote(text)})", null)
            }
        }
    }

    /**
     * Free-form questions are answered by first-party JARVIS BRAIN on-device.
     */
    private fun sendToJarvisBrain(text: String, memoryText: String = text) {
        val ticket = brainGeneration.incrementAndGet()
        val persona = selectedPersona
        runOnUiThread {
            voiceEvent("onJarvisBrainState", "thinking", "JARVIS BRAIN думает локально…")
        }
        backgroundExecutor.execute {
            val response = brain.ask(memoryText, persona)
            if (ticket != brainGeneration.get()) return@execute

            if (response.success) {
                memory.rememberTurn(memoryText, response.text)
            }

            runOnUiThread {
                if (ticket != brainGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent(
                    "onJarvisBrainState",
                    if (response.success) "ready" else "local_model_required",
                    if (response.success) "Локальный ответ JARVIS BRAIN готов" else response.text
                )
                voiceEvent("onJarvisBrainResult", response.text)
                speak(response.text, resumeAfterSpeech = true)
            }
        }
    }

    private fun rememberUserName(text: String) {
        val s = text.trim()
        val lower = s.lowercase(Locale("ru", "RU"))
        val marker = when { lower.startsWith("меня зовут ") -> "меня зовут "; lower.startsWith("моё имя ") -> "моё имя "; lower.startsWith("мое имя ") -> "мое имя "; else -> "" }
        if (marker.isNotEmpty()) memory.setUserName(s.substring(marker.length).trim().take(120))
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) updates.check()
    }

    private fun openVisionCamera(speakResult: Boolean = false) {
        if (!activityResumed || !skills.enabled("vision")) return
        pendingVisionSpeak = speakResult
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7413)
            return
        }
        try {
            val dir = File(cacheDir, "vision").apply { mkdirs() }
            val file = File.createTempFile("jarvis-camera-", ".jpg", dir)
            pendingVisionCapture?.delete()
            pendingVisionCapture = file
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("JARVIS camera", uri)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(camera, 7411)
        } catch (_: Exception) {
            pendingVisionCapture?.delete()
            pendingVisionCapture = null
            pendingVisionSpeak = false
            showVoiceStatus("Системная камера недоступна.")
        }
    }

    private fun selectJarvisBrainModelPicker() {
        if (!activityResumed || isFinishing || isDestroyed) return
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, 7440)
        } catch (_: Exception) {
            showVoiceStatus("Выбор GGUF-модели недоступен на этом устройстве.")
        }
    }

    private fun selectVisionImage() {
        if (!activityResumed || !skills.enabled("vision")) return
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, 7412)
        } catch (_: Exception) { showVoiceStatus("Выбор фото недоступен.") }
    }

    private fun setFloatingOrb(enabled: Boolean) {
        val shortcutPrefs = getSharedPreferences("jarvis_features", MODE_PRIVATE)
        if (!enabled) {
            shortcutPrefs.edit().putBoolean("floating_orb", false).apply()
            stopService(Intent(this, JarvisFloatingOrb::class.java))
            voiceEvent("onJarvisOverlayStatus", false, "Плавающая кнопка отключена.")
            return
        }
        if (!skills.enabled("overlay")) {
            voiceEvent("onJarvisOverlayStatus", false, "Сначала включите навык плавающей кнопки.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            voiceEvent("onJarvisOverlayStatus", false,
                "Android откроет экран разрешения. После разрешения вернитесь и включите переключатель ещё раз.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        shortcutPrefs.edit().putBoolean("floating_orb", true).apply()
        startService(Intent(this, JarvisFloatingOrb::class.java))
        voiceEvent("onJarvisOverlayStatus", true, "Плавающая кнопка включена. × убирает её с экрана.")
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
            memory.rememberSelfDisclosure(memoryText)
            memory.recordHabit(memoryText)
            val normalized = memoryText.lowercase()
            if (skills.enabled("reminders")) {
                if (normalized == "мои напоминания" || normalized == "список напоминаний")
                    return reminders.list()
                val parsed = JarvisReminders.parseRussian(memoryText)
                if (parsed != null) return reminders.schedule(parsed.second, parsed.first)
            }
            // Time/date are core local commands and must work regardless of
            // optional skill switches left over from an earlier build.
            OfflineKnowledge.answer(memoryText)?.let { return it }
            if (skills.enabled("home") && normalized in setOf(
                    "включи умный свет", "выключи умный свет", "включи домашний свет", "выключи домашний свет")) {
                val on = normalized.startsWith("включи")
                backgroundExecutor.execute {
                    val response = home.setLight(on)
                    runOnUiThread {
                        voiceEvent("onJarvisFeatureStatus", "home", response)
                        if (activityResumed) speak(response, resumeAfterSpeech = true)
                    }
                }
                return "Отправляю команду выбранному светильнику…"
            }
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
                val answer = "Я J.A.R.V.I.S. — реальная программная версия голосового помощника, " +
                    "вдохновлённая J.A.R.V.I.S. из фильма «Железный человек». " +
                    "Эту версию создал Пименов Алекс Романович."
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
                normalized == "уйди в фон" ||
                normalized == "уйди в трей" ||
                normalized == "свернись" ||
                normalized == "сверни приложение" ||
                normalized == "работай в фоне"
            ) {
                return minimizeToBackground()
            }

            if (
                normalized == "погода" ||
                normalized == "погода сейчас" ||
                normalized == "погода сегодня" ||
                normalized.contains("какая погода") ||
                normalized.contains("что с погодой")
            ) {
                if (hasApproximateLocation()) fetchWeatherAndSpeak()
                else requestApproximateLocation(forWeather = true)
                return "Получаю погоду по примерному местоположению…"
            }

            if (
                normalized == "местные новости" ||
                normalized == "новости рядом" ||
                normalized.contains("новости по месту") ||
                normalized.contains("новости в моем городе") ||
                normalized.contains("новости в моём городе")
            ) {
                if (hasApproximateLocation()) fetchLocalNewsAndSpeak()
                else requestApproximateLocation(forLocalNews = true)
                return "Получаю местную новостную сводку…"
            }

            if (BackgroundInfoPolicy.classify(memoryText) == BackgroundInfoPolicy.Kind.MAIN_NEWS) {
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
            sendToJarvisBrain(text, memoryText)
            return ""
        }

        @JavascriptInterface fun startListening() { runOnUiThread { this@MainActivity.startListening() } }
        @JavascriptInterface fun hasApproximateLocation(): Boolean = this@MainActivity.hasApproximateLocation()
        @JavascriptInterface fun requestApproximateLocation() {
            this@MainActivity.requestApproximateLocation()
        }
        @JavascriptInterface fun requestWeather() {
            if (this@MainActivity.hasApproximateLocation()) this@MainActivity.fetchWeatherAndSpeak()
            else this@MainActivity.requestApproximateLocation(forWeather = true)
        }
        @JavascriptInterface fun requestLocalNews() {
            if (this@MainActivity.hasApproximateLocation()) this@MainActivity.fetchLocalNewsAndSpeak()
            else this@MainActivity.requestApproximateLocation(forLocalNews = true)
        }
        @JavascriptInterface fun requestNewsSummary() { this@MainActivity.fetchNewsAndSpeak() }
        @JavascriptInterface fun getInterruptByVoice(): Boolean = prefs.getBoolean("interrupt_voice", false)
        @JavascriptInterface fun setInterruptByVoice(enabled: Boolean) {
            prefs.edit().putBoolean("interrupt_voice", enabled).apply()
            runOnUiThread {
                interruptByVoice = enabled
                if (!enabled && isSpeaking && wakeSessionActive) {
                    wakeSessionActive = false
                    offlineWake.stop()
                }
            }
        }
        @JavascriptInterface fun skillsJson(): String = skills.json()
        @JavascriptInterface fun enableSkill(id: String, enabled: Boolean): Boolean = skills.set(id, enabled)
        @JavascriptInterface fun scheduleReminder(text: String, minutes: Int): String =
            if (skills.enabled("reminders")) reminders.schedule(text, minutes)
            else "Включите навык напоминаний."
        @JavascriptInterface fun listReminders(): String = reminders.list()
        @JavascriptInterface fun cancelReminder(id: Int): String = reminders.cancel(id)
        @JavascriptInterface fun startVisionCamera() { runOnUiThread { openVisionCamera(false) } }
        @JavascriptInterface fun selectVisionPhoto() { runOnUiThread { selectVisionImage() } }
        @JavascriptInterface fun configureHome(url: String, token: String, entity: String): String =
            home.configure(url, token, entity)
        @JavascriptInterface fun homeStatus(): String =
            if (home.configured()) "Подключён выбранный свет: ${home.entity()}"
            else "Home Assistant ещё не подключён."
        @JavascriptInterface fun clearHome() { home.clear() }
        @JavascriptInterface fun controlSmartLight(on: Boolean): String {
            if (!skills.enabled("home")) return "Включите навык умного дома."
            backgroundExecutor.execute {
                val response = home.setLight(on)
                runOnUiThread { voiceEvent("onJarvisFeatureStatus", "home", response) }
            }
            return "Отправляю команду выбранному светильнику…"
        }
        @JavascriptInterface fun overlayEnabled(): Boolean =
            getSharedPreferences("jarvis_features", MODE_PRIVATE).getBoolean("floating_orb", false)
        @JavascriptInterface fun setOverlayEnabled(enabled: Boolean) {
            runOnUiThread { setFloatingOrb(enabled) }
        }
        @JavascriptInterface fun batteryMinutes(): Int = WakeBatteryPolicy.remainingAllowedMinutes(prefs)
        @JavascriptInterface fun setBatteryMinutes(minutes: Int): Boolean {
            if (minutes !in setOf(0, 15, 30, 60, 120)) return false
            prefs.edit().putInt("background_minutes", minutes).apply()
            return true
        }
        @JavascriptInterface fun getWakeModeEnabled(): Boolean = prefs.getBoolean("wake_mode", false)
        @JavascriptInterface fun getBackgroundWakeEnabled(): Boolean = prefs.getBoolean("background_wake", false)
        @JavascriptInterface fun minimizeToBackground(): String = this@MainActivity.minimizeToBackground()
        @JavascriptInterface fun setBackgroundWakeEnabled(enabled: Boolean) {
            runOnUiThread { this@MainActivity.setBackgroundWakeEnabled(enabled) }
        }
        @JavascriptInterface fun wakeModelInstalled(): Boolean = offlineWake.installed()
        @JavascriptInterface fun installWakeModel() {
            runOnUiThread { offlineWake.install() }
        }
        @JavascriptInterface fun setWakeModeEnabled(enabled: Boolean) {
            runOnUiThread { this@MainActivity.setWakeModeEnabled(enabled) }
        }
        @JavascriptInterface fun diagnoseMicrophone() { runOnUiThread { this@MainActivity.requestMicrophoneDiagnostic() } }
        @JavascriptInterface fun startConversationWindow(seconds: Int) {
            runOnUiThread { this@MainActivity.startConversationListening(seconds.coerceIn(1, 30) * 1000L) }
        }
        @JavascriptInterface fun continuousDialogueEnabled(): Boolean =
            prefs.getBoolean("continuous_dialogue", true)
        @JavascriptInterface fun setContinuousDialogueEnabled(enabled: Boolean) {
            prefs.edit().putBoolean("continuous_dialogue", enabled).apply()
            if (!enabled) runOnUiThread { closeConversationWindow() }
        }
        @JavascriptInterface fun speak(text: String) { runOnUiThread { this@MainActivity.speak(text.take(8000), resumeAfterSpeech = true) } }
        @JavascriptInterface fun stopListening() { runOnUiThread {
            // Text commands must stop ambient listening as well, not just the recognizer.
            closeConversationWindow()
            wakeSessionActive = false
            cancelWakeRestart()
            offlineWake.stop()
            speechInput.cancel()
        } }
        @JavascriptInterface fun openMicrophoneSettings() { runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        } }
        @JavascriptInterface fun setPersona(name: String) {
            selectedPersona = canonicalPersona(name)
            prefs.edit().putString("persona", selectedPersona).apply()
        }

        @JavascriptInterface fun setUserProfile(name: String, day: Int, month: Int, year: Int) {
            if (name.isNotBlank()) memory.setUserName(name)
            memory.setBirthDate(day, month, year)
        }

        @JavascriptInterface fun getUserName(): String = memory.getUserName()
        @JavascriptInterface fun getMemorySummary(): String = memory.memoryContext()

        @JavascriptInterface
        fun setFishAudioKey(fish: String): String {
            if (fish.isNotBlank()) prefs.edit().putString("fish_api_key", fish).apply()
            return "Fish Audio: " +
                if (fish.isNotBlank() || prefs.getString("fish_api_key", "").orEmpty().isNotBlank())
                    "✓ настроен"
                else
                    "не настроен"
        }

        @JavascriptInterface
        fun getJarvisBrainStatus(): String {
            val info = modelStore.info()
            return JSONObject().apply {
                put("engine", "JARVIS BRAIN")
                put("version", "0.1")
                put("mode", "local")
                put("networkRequired", false)
                put("memoryLocal", true)
                put("memoryEpisodes", memory.episodeCount())
                put("modelFilePresent", info.present)
                put("modelValid", info.validGguf)
                put("modelBytes", info.bytes)
                put("modelSize", modelStore.humanSize(info.bytes))
                put("modelProfileId", info.profileId)
                put("modelLabel", info.label)
                put("modelDownloadRunning", modelDownloadRunning.get())
                put("brainSelfTestRunning", brainSelfTestRunning.get())
                put("neuralModelInstalled", localLanguageModel.isReady())
            }.toString()
        }

        @JavascriptInterface
        fun chooseJarvisBrainModel() {
            runOnUiThread { this@MainActivity.selectJarvisBrainModelPicker() }
        }

        @JavascriptInterface
        fun downloadJarvisBrainModel(profileId: String): String {
            val profile = modelStore.profile(profileId)
                ?: return "Неизвестный профиль локальной модели."
            if (!modelDownloadRunning.compareAndSet(false, true)) {
                return "Загрузка модели уже выполняется."
            }

            localLanguageModel.unload()
            brainGeneration.incrementAndGet()
            runOnUiThread {
                voiceEvent(
                    "onJarvisBrainModelDownload",
                    "starting",
                    "Начинаю загрузку: ${profile.label}"
                )
            }

            modelExecutor.execute {
                var lastUiAt = 0L
                val result = modelStore.downloadProfile(profile.id) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiAt >= 500L || progress.percent >= 100) {
                        lastUiAt = now
                        val text = if (progress.percent >= 0) {
                            "Загрузка ${profile.label}: ${progress.percent}% · " +
                                modelStore.humanSize(progress.downloadedBytes) + " / " +
                                modelStore.humanSize(progress.totalBytes)
                        } else {
                            "Загрузка ${profile.label}: " +
                                modelStore.humanSize(progress.downloadedBytes)
                        }
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                voiceEvent("onJarvisBrainModelDownload", "progress", text)
                            }
                        }
                    }
                }

                modelDownloadRunning.set(false)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.fold(
                        onSuccess = { info ->
                            val preloaded = localLanguageModel.prepare()
                            brainGeneration.incrementAndGet()
                            val text = (info.label.ifBlank { "GGUF-модель" }) +
                                " установлена локально · " + modelStore.humanSize(info.bytes) +
                                if (preloaded) {
                                    ". Модель уже загружена в RAM — JARVIS BRAIN готов к быстрому ответу."
                                } else {
                                    ". JARVIS BRAIN загрузит её при первом вопросе."
                                }
                            voiceEvent("onJarvisBrainModelDownload", "stored", text)
                            voiceEvent("onJarvisBrainModelStatus", "stored", text)
                            showVoiceStatus(text)
                        },
                        onFailure = { error ->
                            val text = error.message ?: "Не удалось загрузить локальную модель."
                            voiceEvent("onJarvisBrainModelDownload", "error", text)
                            voiceEvent("onJarvisBrainModelStatus", "error", text)
                            showVoiceStatus(text)
                        }
                    )
                }
            }

            return "Загрузка ${profile.label} запущена."
        }

        @JavascriptInterface
        fun testJarvisBrainModel(): String {
            if (modelDownloadRunning.get()) return "Дождитесь завершения загрузки модели."
            if (modelStore.modelFile() == null) return "Сначала установите GGUF-модель."
            if (!brainSelfTestRunning.compareAndSet(false, true)) {
                return "Самопроверка JARVIS BRAIN уже выполняется."
            }

            brainGeneration.incrementAndGet()
            runOnUiThread {
                voiceEvent(
                    "onJarvisBrainSelfTest",
                    "running",
                    "Загружаю локальную модель в RAM и проверяю генерацию…"
                )
            }

            modelExecutor.execute {
                val started = SystemClock.elapsedRealtime()
                val result = try {
                    localLanguageModel.generate(
                        prompt = "Ты JARVIS BRAIN. Ответь одной короткой фразой по-русски: локальный мозг работает. /no_think",
                        maxNewTokens = 64
                    )
                } catch (_: Throwable) {
                    JarvisLanguageModel.Generation(
                        success = false,
                        text = "Ошибка локального нейросетевого самотеста."
                    )
                }
                val elapsedMs = SystemClock.elapsedRealtime() - started
                brainSelfTestRunning.set(false)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val seconds = String.format(Locale.US, "%.1f", elapsedMs / 1000.0)
                    val text = if (result.success) {
                        "Самотест пройден за $seconds с. Ответ модели: ${result.text.take(240)}"
                    } else {
                        "Самотест не пройден за $seconds с. ${result.text}"
                    }
                    voiceEvent(
                        "onJarvisBrainSelfTest",
                        if (result.success) "success" else "error",
                        text
                    )
                    showVoiceStatus(text)
                }
            }

            return "Самопроверка JARVIS BRAIN запущена."
        }

        @JavascriptInterface
        fun removeJarvisBrainModel(): String {
            if (modelDownloadRunning.get()) return "Сначала дождитесь завершения загрузки модели."
            localLanguageModel.unload()
            val removed = modelStore.remove()
            brainGeneration.incrementAndGet()
            return if (removed) {
                "Локальный файл модели удалён."
            } else {
                "Не удалось удалить локальный файл модели."
            }
        }

        @JavascriptInterface
        fun clearJarvisBrainHistory(): String {
            val count = memory.clearChatHistory()
            brainGeneration.incrementAndGet()
            return "Удалено локальных диалогов: $count."
        }

        @JavascriptInterface
        fun getApiKeyStatus(): String = JSONObject().apply {
            put("fish", prefs.getString("fish_api_key", "").orEmpty().isNotBlank())
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
        wakeSessionActive = false
        cancelWakeRestart()
        offlineWake.release()
        diagnosticGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        modelExecutor.shutdownNow()
        if (::localLanguageModel.isInitialized) localLanguageModel.unload()
        speechInput.destroy()
        tts?.stop()
        tts?.shutdown()
        fishAudioTts.release()
        localInfo.release()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidJarvis")
            webView.destroy()
        }
        super.onDestroy()
    }
}
