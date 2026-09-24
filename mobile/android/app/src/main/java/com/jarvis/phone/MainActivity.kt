package com.jarvis.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var router: PhoneCommandRouter
    private lateinit var gigaChat: GigaChatClient
    private val brainGeneration = AtomicInteger(0)
    private val brainTestRunning = AtomicBoolean(false)
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
    private lateinit var skills: SkillCatalog
    private lateinit var reminders: JarvisReminders
    private lateinit var home: JarvisHomeAssistant
    private lateinit var vision: JarvisVision
    private lateinit var updates: JarvisSignedUpdates
    private lateinit var localInfo: JarvisLocalInfo
    private val newsFeed = JarvisNewsFeed()
    private var pendingWeatherLocation = false
    private var pendingLocalNewsLocation = false
    private var pendingLocationOnly = false
    private var wakeSessionActive = false
    private var wakeFailures = 0
    private var wakeRestart: Runnable? = null

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
    private var speechPlaybackStarted = false
    private var speechTextLength = 0
    private var resumeListeningAfterSpeech = false
    @Volatile private var activityResumed = false
    private val prefs by lazy { getSharedPreferences("jarvis_settings", MODE_PRIVATE) }
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = PhoneCommandRouter(this)
        gigaChat = GigaChatClient(this)
        skills = SkillCatalog(this)
        reminders = JarvisReminders(this)
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
        selectedPersona = prefs.getString("persona", "J.A.R.V.I.S.") ?: "J.A.R.V.I.S."
        wakeModeEnabled = prefs.getBoolean("wake_mode", false)
        backgroundWakeEnabled = prefs.getBoolean("background_wake", false)
        pendingBackgroundCommand = intent?.takeIf { it.action == WakeForegroundService.ACTION_OPEN_COMMAND }
            ?.getStringExtra(WakeForegroundService.EXTRA_COMMAND)?.take(240)
        fishAudioTts = FishAudioTts(this)
        memory = JarvisMemory(this)
        cloudMemory = RedisMemoryGateway(this)
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
                    selectedPersona = activation.persona
                    prefs.edit().putString("persona", activation.persona).apply()
                    voiceEvent("onJarvisWakeDetected", activation.persona, activation.command)
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
            onText = { text -> voiceEvent("onJarvisSpeechResult", text) },
            onError = { error ->
                voiceEvent("onJarvisSpeechError", error)
                if (wakeModeEnabled && activityResumed && !diagnosticInProgress)
                    scheduleWakeRestart(1700L)
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
            setBackgroundWakeEnabled(false)
            voiceEvent("onJarvisBackgroundWakeStatus", "error",
                "Для видимого выключателя фонового микрофона разрешите уведомления JARVIS.")
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
            setBackgroundWakeEnabled(false)
            voiceEvent("onJarvisBackgroundWakeStatus", "error",
                "Android запретил запуск фонового режима. Откройте JARVIS и попробуйте снова.")
        }
    }

    private fun setBackgroundWakeEnabled(enabled: Boolean) {
        if (!enabled) {
            backgroundWakeEnabled = false
            pendingBackgroundMic = false
            pendingBackgroundNotification = false
            prefs.edit().putBoolean("background_wake", false).apply()
            WakeForegroundService.shouldListenInBackground = false
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
        if (!activityResumed || isFinishing || isDestroyed) return
        if (isSpeaking) { resumeListeningAfterSpeech = true; return }
        if (diagnosticInProgress) return
        cancelWakeRestart()
        if (wakeSessionActive) {
            wakeSessionActive = false
            offlineWake.stop { if (activityResumed) startConversationListening(durationMs) }
            return
        }
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
        if (requestCode == 7411 || requestCode == 7412) {
            if (resultCode == RESULT_OK) {
                if (requestCode == 7411) {
                    @Suppress("DEPRECATION")
                    val bitmap = data?.extras?.get("data") as? Bitmap
                    if (bitmap == null) showVoiceStatus("Камера не вернула фото.")
                    else vision.fromCameraThumbnail(bitmap) { showVoiceStatus(it) }
                } else {
                    val uri = data?.data
                    if (uri == null) showVoiceStatus("Изображение не выбрано.")
                    else vision.fromPhoto(uri) { showVoiceStatus(it) }
                }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            pendingCameraPermission = false
            if (wanted && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                openVisionCamera()
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
        wakeModeEnabled = prefs.getBoolean("wake_mode", false)
        backgroundWakeEnabled = prefs.getBoolean("background_wake", false)
        speechInput.resume()
        // Stop the service's recorder before reacquiring audio for the visible
        // screen; this prevents two Vosk sessions competing for the microphone.
        WakeForegroundService.shouldListenInBackground = false
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
        diagnosticGeneration++
        val diagnosticRunning = diagnosticInProgress
        diagnosticInterrupted = diagnosticRunning
        diagnosticInProgress = false
        speechInput.pause()
        stopSpeech()
        val handoff = backgroundWakeEnabled && wakeModeEnabled && !systemSpeechOpen &&
            !diagnosticRunning && !isFinishing && !isDestroyed
        wakeSessionActive = false
        offlineWake.stop {
            // This callback fires AFTER the old AudioRecord was released.
            // startForegroundService was already called while Activity was visible.
            if (handoff && !activityResumed &&
                prefs.getBoolean("background_wake", false) &&
                prefs.getBoolean("wake_mode", false)) {
                WakeForegroundService.shouldListenInBackground = true
                WakeForegroundService.active?.startBackgroundListening()
            }
        }
        if (!handoff) WakeForegroundService.shouldListenInBackground = false
        voiceEvent(
            "onJarvisWakeStatus", "paused",
            if (handoff) "Переключаю тихое ожидание в фоновый режим…"
            else "Ожидание приостановлено."
        )
        if (!microphonePermissionPending) pendingMicStart = false
        super.onPause()
    }

    private fun speechPlaybackBegan(generation: Long) {
        if (generation != speechGeneration || !isSpeaking ||
            speechPlaybackStarted || !activityResumed || isFinishing || isDestroyed) return
        speechPlaybackStarted = true
        // Starts Variant A exactly when the engine begins playback, not while
        // a cloud audio file is still downloading / being prepared.
        voiceEvent("onJarvisSpeechState", "speaking",
            "J.A.R.V.I.S. отвечает. Нажмите на круг, чтобы прервать.")
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
            if (hadPlayback) voiceEvent("onJarvisSpeakState", "stop")
            else voiceEvent("onJarvisSpeechState", "idle", "Голосовой ответ завершён.")
            val resume = resumeListeningAfterSpeech
            resumeListeningAfterSpeech = false
            if (resume && activityResumed) startConversationListening(12_000)
            else if (wakeModeEnabled && activityResumed) {
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
                onStart = { runOnUiThread { speechPlaybackBegan(generation) } })
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
                val prompt = "Сделай краткую нейтральную голосовую сводку свежих новостей на русском языке. " +
                    "Назови 5-6 главных тем по заголовкам ниже, по 1-2 предложения на тему. " +
                    "Не придумывай факты и явно отделяй заголовок от неподтвержденных деталей. Заголовки: " +
                    items.joinToString(" | ")
                val response = gigaChat.askConversation(prompt, selectedPersona)
                if (response.success) response.text
                else "Свежие новости по заголовкам: " + items.take(5).joinToString(". ")
            } catch (_: Exception) {
                "Не удалось получить свежие новости. Проверьте интернет и повторите запрос."
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent("onGigaChatResult", finalText)
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
                voiceEvent("onGigaChatResult", weather)
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
                        val prompt = "Сделай короткую нейтральную голосовую сводку местных новостей для " +
                            place.label + ". Используй только заголовки ниже, не придумывай детали. " +
                            "Назови 4–6 важных тем. Заголовки: " + headlines.joinToString(" | ")
                        val response = gigaChat.askConversation(prompt, selectedPersona)
                        if (response.success) response.text
                        else "Свежие местные заголовки для ${place.label}: " +
                            headlines.take(5).joinToString(". ")
                    }
                },
                onFailure = {
                    "Не удалось получить местные новости. Проверьте интернет, геолокацию и повторите."
                }
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent("onJarvisLocalInfo", "local_news", value)
                voiceEvent("onGigaChatResult", value)
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
                    if (!prefs.getBoolean("gigachat_share_messages", false)) {
                        "Последнее сообщение без облачного анализа:\n" +
                            source.take(800) +
                            "\nЧтобы отправить текст для анализа GigaChat, включите отдельное разрешение в настройках."
                    } else gigaChat.askConversation(prompt, selectedPersona).text
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

    /**
     * All non-device questions are answered by GigaChat. Android commands still
     * use the native permission-aware router; the model cannot silently call APIs.
     */
    private fun sendToGigaChat(text: String, memoryText: String = text) {
        val ticket = brainGeneration.incrementAndGet()
        val persona = selectedPersona
        runOnUiThread {
            voiceEvent("onJarvisBrainState", "thinking", "GigaChat обрабатывает вопрос…")
        }
        backgroundExecutor.execute {
            val useHistory = prefs.getBoolean("gigachat_memory_enabled", false)
            val turns = if (useHistory) memory.recentDialogues().takeLast(6) else emptyList()
            val remote = if (useHistory) cloudMemory.recall(memoryText).take(2500) else ""
            val context = if (!useHistory) "" else buildString {
                append(memory.approvedBrainFacts())
                if (remote.isNotBlank()) append("\nРелевантные заметки:\n").append(remote)
            }
            val response = gigaChat.askConversation(memoryText, persona, context, turns)
            // If a second question was asked during this one, only deliver the
            // newest result. In particular, don't speak stale voice replies.
            if (ticket != brainGeneration.get()) return@execute
            if (response.success) {
                memory.rememberTurn(memoryText, response.text)
                if (useHistory) cloudMemory.record(memoryText, response.text)
            }
            runOnUiThread {
                if (ticket != brainGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                voiceEvent(
                    "onJarvisBrainState",
                    if (response.success) "ready" else "error",
                    if (response.success) "Ответ GigaChat получен" else response.text
                )
                voiceEvent("onGigaChatResult", response.text)
                if (response.success) speak(response.text, resumeAfterSpeech = true)
            }
        }
    }

    private fun rememberUserName(text: String) {
        val s = text.trim()
        val lower = s.lowercase(Locale("ru", "RU"))
        val marker = when { lower.startsWith("меня зовут ") -> "меня зовут "; lower.startsWith("моё имя ") -> "моё имя "; lower.startsWith("мое имя ") -> "мое имя "; else -> "" }
        if (marker.isNotEmpty()) memory.setUserName(s.substring(marker.length).trim().split(" ").firstOrNull().orEmpty())
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) updates.check()
    }

    private fun openVisionCamera() {
        if (!activityResumed || !skills.enabled("vision")) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7413)
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 7411)
        } catch (_: Exception) { showVoiceStatus("Системная камера недоступна.") }
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

            if (
                normalized == "новости" ||
                normalized.contains("сводка новостей") ||
                normalized.contains("сводки новостей") ||
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
        @JavascriptInterface fun startVisionCamera() { runOnUiThread { openVisionCamera() } }
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
        @JavascriptInterface fun speak(text: String) { runOnUiThread { this@MainActivity.speak(text.take(8000), resumeAfterSpeech = true) } }
        @JavascriptInterface fun stopListening() { runOnUiThread {
            // Text commands must stop ambient listening as well, not just the recognizer.
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


        @JavascriptInterface
        fun setApiKeys(fish: String, giga: String): String {
            val editor = prefs.edit()
            if (fish.isNotBlank()) editor.putString("fish_api_key", fish)
            if (giga.isNotBlank()) {
                editor.putString("gigachat_api_key", giga)
                editor.remove("gigachat_verified_at")
                gigaChat.invalidateToken()
            }
            editor.apply()
            return "Fish Audio: ${if (fish.isNotBlank() || prefs.getString("fish_api_key", "").orEmpty().isNotBlank()) "✓ настроен" else "не настроен"} · GigaChat: ${if (giga.isNotBlank() || prefs.getString("gigachat_api_key", "").orEmpty().isNotBlank()) "✓ настроен" else "не настроен"}"
        }

        @JavascriptInterface
        fun getGigaBrainStatus(): String = JSONObject().apply {
            put("configured", gigaChat.configured())
            put("model", gigaChat.modelName())
            put("scope", gigaChat.scopeName())
            put("memory", prefs.getBoolean("gigachat_memory_enabled", false))
            put("shareMessages", prefs.getBoolean("gigachat_share_messages", false))
            put("verifiedAt", prefs.getLong("gigachat_verified_at", 0L))
        }.toString()

        @JavascriptInterface
        fun configureGigaChatBrain(key: String, scope: String, model: String): String {
            // Key is never returned to JS or inserted in a status message.
            val k = key.trim()
            if (k.length > 8192) return "Слишком длинный ключ авторизации."
            if (scope != GigaChatBrainPolicy.validScope(scope) ||
                model != GigaChatBrainPolicy.validModel(model))
                return "Выберите доступную модель и правильный тип API."
            if (k.isBlank() && !gigaChat.configured()) return "Сначала введите ключ авторизации GigaChat."
            val edit = prefs.edit()
                .putString("gigachat_scope", scope)
                .putString("gigachat_model", model)
                .remove("gigachat_verified_at")
            if (k.isNotBlank()) edit.putString("gigachat_api_key", k)
            edit.apply()
            gigaChat.invalidateToken()
            runOnUiThread {
                voiceEvent("onJarvisBrainState", "saved",
                    "Настройки GigaChat сохранены. Проверка связи ещё не проводилась.")
            }
            return "GigaChat настроен. Нажмите «Проверить подключение»."
        }

        @JavascriptInterface
        fun testGigaChatBrain() {
            if (!brainTestRunning.compareAndSet(false, true)) return
            runOnUiThread { voiceEvent("onJarvisBrainState", "testing", "Проверяю GigaChat…") }
            val originalKey = prefs.getString("gigachat_api_key", "").orEmpty()
            val originalScope = gigaChat.scopeName()
            val originalModel = gigaChat.modelName()
            backgroundExecutor.execute {
                try {
                    val result = gigaChat.testConnection()
                    val unchanged = originalKey.isNotBlank() &&
                        prefs.getString("gigachat_api_key", "").orEmpty() == originalKey &&
                        gigaChat.scopeName() == originalScope &&
                        gigaChat.modelName() == originalModel
                    if (result.success && unchanged) {
                        prefs.edit().putLong("gigachat_verified_at", System.currentTimeMillis()).apply()
                    } else prefs.edit().remove("gigachat_verified_at").apply()
                    runOnUiThread {
                        voiceEvent("onJarvisBrainState",
                            if (result.success && unchanged) "connected" else "error",
                            if (result.success && unchanged)
                                "GigaChat подключён. Теперь он отвечает на вопросы JARVIS."
                            else if (!unchanged) "Настройки GigaChat изменились во время проверки. Проверьте ещё раз."
                            else result.text)
                    }
                } finally {
                    brainTestRunning.set(false)
                }
            }
        }

        @JavascriptInterface
        fun setGigaBrainMemory(enabled: Boolean) {
            prefs.edit().putBoolean("gigachat_memory_enabled", enabled).apply()
            runOnUiThread {
                voiceEvent("onJarvisBrainState", "memory",
                    if (enabled) "Контекст памяти будет передаваться GigaChat при вопросах."
                    else "Локальная история и заметки больше не передаются GigaChat.")
            }
        }

        @JavascriptInterface
        fun setGigaShareMessages(enabled: Boolean) {
            prefs.edit().putBoolean("gigachat_share_messages", enabled).apply()
            runOnUiThread {
                voiceEvent("onJarvisBrainState", "privacy",
                    if (enabled) "Анализ выбранных сообщений через GigaChat разрешён."
                    else "Передача сообщений GigaChat отключена.")
            }
        }

        @JavascriptInterface
        fun clearGigaBrainHistory(): String {
            val count = memory.clearChatHistory()
            brainGeneration.incrementAndGet()
            return "Удалено локальных диалогов: $count. Данные внешнего сервиса не затронуты."
        }

        @JavascriptInterface
        fun disconnectGigaChatBrain(): String {
            prefs.edit()
                .remove("gigachat_api_key")
                .remove("gigachat_verified_at")
                .putBoolean("gigachat_memory_enabled", false)
                .putBoolean("gigachat_share_messages", false)
                .apply()
            gigaChat.invalidateToken()
            brainGeneration.incrementAndGet()
            runOnUiThread {
                voiceEvent("onJarvisBrainState", "disconnected",
                    "Ключ GigaChat удалён из приложения. Голосовые команды телефона доступны.")
            }
            return "GigaChat отключён. Локальная история осталась на устройстве."
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
        wakeSessionActive = false
        cancelWakeRestart()
        offlineWake.release()
        diagnosticGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
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
