package com.jarvis.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Opt-in microphone foreground service. It MUST be created by an Activity while
 * that Activity is visible. The service does not start at boot or restart
 * itself after process death. Its permanent notification always has a Stop button.
 *
 * Foreground Activity owns Vosk while visible; this service takes over once
 * Activity's AudioRecord is fully closed. No competing microphone captures.
 */
class WakeForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.jarvis.phone.action.BACKGROUND_WAKE_START"
        const val ACTION_STOP = "com.jarvis.phone.action.BACKGROUND_WAKE_STOP"
        const val ACTION_OPEN_COMMAND = "com.jarvis.phone.action.OPEN_BACKGROUND_COMMAND"
        const val EXTRA_COMMAND = "background_command"
        private const val CHANNEL_ID = "jarvis_bg_voice_v1"
        private const val NOTIFICATION_ID = 719
        private const val FOLLOW_UP_MS = 12_000L
        private const val MAX_MIC_START_ATTEMPTS = 6

        @Volatile var active: WakeForegroundService? = null
            private set

        // MainActivity requests a handoff while visible, then marks the old
        // AudioRecord as released. This removes the race between Activity pause
        // and asynchronous foreground-service creation.
        @Volatile var shouldListenInBackground: Boolean = false
        @Volatile var microphoneHandoffReady: Boolean = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var offline: OfflineWakeEngine
    private lateinit var router: PhoneCommandRouter
    private var tts: TextToSpeech? = null
    private lateinit var fishAudioTts: FishAudioTts
    private lateinit var gigaChat: GigaChatClient
    private lateinit var memory: JarvisMemory
    private lateinit var reminders: JarvisReminders
    private lateinit var home: JarvisHomeAssistant
    private val newsFeed = JarvisNewsFeed()
    private val infoExecutor = Executors.newSingleThreadExecutor()
    private var cloudBusy = false
    private var ttsReady = false
    private var speaking = false
    private var suppressUntil = 0L
    private var armedUntil = 0L
    private var foreground = false
    private var shuttingDown = false
    private var pendingCommand: String? = null
    private var notificationText = "Тихая активация работает в фоне"
    private var startedAtElapsed = 0L
    private var micStartAttempt = 0
    private val micStartRetry = object : Runnable {
        override fun run() {
            if (shuttingDown || !foreground || !shouldListenInBackground || !microphoneHandoffReady ||
                !getSharedPreferences("jarvis_settings", MODE_PRIVATE).getBoolean("background_wake", false)) return
            if (WakeBatteryPolicy.batteryTooLow(this@WakeForegroundService)) {
                notificationText = "Заряд ниже 15%: фоновое ожидание не запускается."
                updateNotification()
                failClosed()
                return
            }
            micStartAttempt++
            notificationText = if (micStartAttempt <= 1)
                "Передаю микрофон в фоновый режим…"
            else "Повторно подключаю фоновый микрофон… попытка $micStartAttempt"
            updateNotification()
            offline.start()
        }
    }
    private val batteryCheck = object : Runnable {
        override fun run() {
            if (shuttingDown || !shouldListenInBackground) return
            val settings = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            val allowed = WakeBatteryPolicy.remainingAllowedMinutes(settings)
            val timedOut = startedAtElapsed > 0L && allowed > 0 &&
                SystemClock.elapsedRealtime() - startedAtElapsed >= allowed * 60_000L
            if (timedOut || WakeBatteryPolicy.batteryTooLow(this@WakeForegroundService)) {
                notificationText = if (timedOut) "Фоновый микрофон автоматически остановлен по таймеру."
                    else "Фоновый микрофон остановлен: заряд батареи ниже 15%."
                updateNotification()
                failClosed()
            } else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        router = PhoneCommandRouter(this)
        fishAudioTts = FishAudioTts(this)
        gigaChat = GigaChatClient(this)
        memory = JarvisMemory(this)
        reminders = JarvisReminders(this)
        home = JarvisHomeAssistant(this)
        offline = OfflineWakeEngine(
            this,
            onStatus = { status, message ->
                when (status) {
                    "error" -> {
                        val canRetry = !shuttingDown && foreground && shouldListenInBackground &&
                            microphoneHandoffReady &&
                            getSharedPreferences("jarvis_settings", MODE_PRIVATE)
                                .getBoolean("background_wake", false) &&
                            micStartAttempt < MAX_MIC_START_ATTEMPTS
                        if (canRetry) {
                            val delay = when (micStartAttempt) {
                                0, 1 -> 350L
                                2 -> 650L
                                3 -> 1_000L
                                4 -> 1_500L
                                else -> 2_000L
                            }
                            notificationText = "Микрофон ещё занят системой. Повторяю подключение…"
                            updateNotification()
                            ui.removeCallbacks(micStartRetry)
                            ui.postDelayed(micStartRetry, delay)
                        } else {
                            notificationText = message
                            updateNotification()
                            // Stop audio safely after several real retries instead of
                            // giving up on the first transient AudioRecord failure.
                            failClosed()
                        }
                    }
                    "model_needed" -> {
                        notificationText = message
                        updateNotification()
                        failClosed()
                    }
                    "listening" -> {
                        micStartAttempt = 0
                        notificationText = "Тихая активация: Джарвис, Астра, Луна, Терра, Кибер"
                        updateNotification()
                    }
                }
            },
            onMatch = { activation -> onWake(activation) },
            stayOpenOnMatch = true,
            onUtterance = { phrase -> onFollowUp(phrase) }
        )
        tts = TextToSpeech(applicationContext) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { ui.post { speaking = true } }
                    override fun onDone(utteranceId: String?) { ui.post { endSpeech() } }
                    override fun onError(utteranceId: String?) { ui.post { endSpeech() } }
                })
            }
        }
        active = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFromNotification()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!getSharedPreferences("jarvis_settings", MODE_PRIVATE)
                        .getBoolean("background_wake", false)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                    !offline.installed()) {
                    failClosed()
                    return START_NOT_STICKY
                }
                if (!foreground) {
                    createChannel()
                    // Android 14+ enforces both the microphone service type and
                    // its matching permission; start only from visible Activity.
                    try {
                        val serviceType = if (android.os.Build.VERSION.SDK_INT >= 30)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                        ServiceCompat.startForeground(
                            this, NOTIFICATION_ID, buildNotification(), serviceType
                        )
                        foreground = true
                    } catch (_: SecurityException) {
                        failClosed()
                        return START_NOT_STICKY
                    } catch (_: RuntimeException) {
                        failClosed()
                        return START_NOT_STICKY
                    }
                }
                if (shouldListenInBackground && microphoneHandoffReady) startBackgroundListening()
            }
            else -> {
                // Never start a microphone service from a null restart intent.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Main-thread only: called once UI's own AudioRecord has stopped. */
    fun startBackgroundListening() {
        if (shuttingDown || !foreground || !shouldListenInBackground || !microphoneHandoffReady ||
            !getSharedPreferences("jarvis_settings", MODE_PRIVATE).getBoolean("background_wake", false)) return
        if (WakeBatteryPolicy.batteryTooLow(this)) {
            notificationText = "Заряд ниже 15%: фоновое ожидание не запускается."
            updateNotification()
            failClosed()
            return
        }
        if (startedAtElapsed == 0L) startedAtElapsed = SystemClock.elapsedRealtime()
        ui.removeCallbacks(batteryCheck)
        ui.postDelayed(batteryCheck, 60_000L)

        // Some OEMs (including MIUI/HyperOS) release the foreground AudioRecord
        // a few hundred milliseconds after its shutdown callback. Delay the first
        // background acquisition slightly and retry transient failures.
        ui.removeCallbacks(micStartRetry)
        micStartAttempt = 0
        notificationText = "Передаю микрофон в фоновый режим…"
        updateNotification()
        ui.postDelayed(micStartRetry, 400L)
    }

    /** Main-thread only: hand the microphone back to the visible Activity. */
    fun pauseForForeground(afterStopped: () -> Unit) {
        shouldListenInBackground = false
        microphoneHandoffReady = false
        armedUntil = 0L
        startedAtElapsed = 0L
        ui.removeCallbacks(batteryCheck)
        ui.removeCallbacks(micStartRetry)
        micStartAttempt = 0
        pendingCommand = null
        notificationText = "JARVIS открыт: микрофон временно передан экрану."
        updateNotification()
        offline.stop { if (!shuttingDown) afterStopped() }
    }

    private fun onWake(match: WakeWordMatcher.Activation) {
        if (shuttingDown || !shouldListenInBackground || !foreground) return
        if (cloudBusy || speaking || SystemClock.elapsedRealtime() < suppressUntil) return
        // A wake name is required before any background action.
        getSharedPreferences("jarvis_settings", MODE_PRIVATE).edit()
            .putString("persona", match.persona).apply()
        if (match.command.isNotBlank()) {
            armedUntil = 0
            executeBackgroundCommand(match.command)
        } else {
            armedUntil = SystemClock.elapsedRealtime() + FOLLOW_UP_MS
            notificationText = "${match.persona}: жду следующую команду (12 секунд)"
            updateNotification()
            speak("Слушаю")
            ui.postDelayed({
                if (!shuttingDown && armedUntil != 0L &&
                    SystemClock.elapsedRealtime() >= armedUntil) {
                    armedUntil = 0L
                    notificationText = "Тихое ожидание имени продолжается"
                    updateNotification()
                }
            }, FOLLOW_UP_MS + 150L)
        }
    }

    private fun onFollowUp(phrase: String) {
        if (shuttingDown || !shouldListenInBackground || !foreground) return
        if (cloudBusy || speaking || SystemClock.elapsedRealtime() < suppressUntil) return
        if (armedUntil == 0L || SystemClock.elapsedRealtime() > armedUntil) {
            armedUntil = 0L
            return
        }
        armedUntil = 0L
        executeBackgroundCommand(phrase)
    }

    private fun executeBackgroundCommand(phrase: String) {
        memory.rememberSelfDisclosure(phrase)
        when (BackgroundInfoPolicy.classify(phrase)) {
            BackgroundInfoPolicy.Kind.TIME,
            BackgroundInfoPolicy.Kind.DATE -> {
                val response = OfflineKnowledge.answer(phrase)
                    ?: "Не удалось определить время или дату."
                notificationText = response
                updateNotification()
                speak(response)
                return
            }
            BackgroundInfoPolicy.Kind.MAIN_NEWS -> {
                fetchBackgroundNews()
                return
            }
            null -> Unit
        }

        val normalized = BackgroundCommandPolicy.normalize(phrase)

        // Local reminders do not need the Activity.
        if (normalized == "мои напоминания" || normalized == "список напоминаний") {
            val response = reminders.list()
            notificationText = response.take(220)
            updateNotification()
            speak(response)
            return
        }
        JarvisReminders.parseRussian(phrase)?.let { parsed ->
            val response = reminders.schedule(parsed.second, parsed.first)
            notificationText = response
            updateNotification()
            speak(response)
            return
        }

        // The single configured Home Assistant light is an explicit, bounded
        // action and can be controlled while minimized.
        if (normalized in setOf(
                "включи умный свет", "выключи умный свет",
                "включи домашний свет", "выключи домашний свет"
            )
        ) {
            val on = normalized.startsWith("включи")
            runBackgroundTask("Управляю выбранным светом…") { home.setLight(on) }
            return
        }

        if (BackgroundCommandPolicy.permitted(phrase)) {
            val response = try { router.execute(phrase) } catch (_: Exception) {
                "Не удалось выполнить команду."
            }
            notificationText = response
            updateNotification()
            speak(response)
            return
        }

        // Exact location is intentionally unavailable to the minimized service.
        // Weather/local-news asks the user to open the app instead of silently
        // turning on background location tracking.
        val needsLocation = normalized.contains("погод") ||
            normalized.contains("местн") && normalized.contains("новост") ||
            normalized.contains("новост") && (
                normalized.contains("рядом") ||
                normalized.contains("моем городе") ||
                normalized.contains("моём городе") ||
                normalized.contains("по месту")
            )
        if (needsLocation) {
            deferToVisibleApp(
                phrase,
                "Для погоды и местных новостей откройте JARVIS: приложению нужно только примерное местоположение."
            )
            return
        }

        // Launching apps, calls, searches or reading private messages changes
        // the visible UI / needs a permission surface, so Android requires user
        // involvement. Keep these behind the ongoing notification.
        if (BackgroundCommandPolicy.requiresVisibleUi(phrase) || router.canHandle(phrase)) {
            deferToVisibleApp(
                phrase,
                "Эта команда меняет экран или требует разрешения Android. Нажмите на уведомление JARVIS."
            )
            return
        }

        // Ordinary questions no longer force the app open: GigaChat can answer
        // directly from the foreground microphone service.
        if (gigaChat.configured()) {
            askGigaChatInBackground(phrase)
        } else {
            deferToVisibleApp(
                phrase,
                "GigaChat ещё не подключён. Откройте JARVIS и настройте мозг GigaChat."
            )
        }
    }

    private fun runBackgroundTask(status: String, work: () -> String) {
        if (cloudBusy || shuttingDown) return
        cloudBusy = true
        notificationText = status
        updateNotification()
        infoExecutor.execute {
            val response = try { work() } catch (_: Exception) { "Не удалось выполнить команду." }
            ui.post {
                if (shuttingDown) return@post
                cloudBusy = false
                notificationText = response.take(220)
                updateNotification()
                speak(response)
            }
        }
    }

    private fun askGigaChatInBackground(phrase: String) {
        if (cloudBusy || shuttingDown) return
        cloudBusy = true
        pendingCommand = null
        notificationText = "GigaChat думает…"
        updateNotification()
        val settings = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
        val persona = settings.getString("persona", "J.A.R.V.I.S.").orEmpty()
        val useMemory = settings.getBoolean("gigachat_memory_enabled", false)

        infoExecutor.execute {
            val context = if (useMemory) memory.approvedBrainFacts(phrase) else ""
            val turns = if (useMemory) memory.recentDialogues().takeLast(6) else emptyList()
            val response = gigaChat.askConversation(phrase.take(4000), persona, context, turns)
            if (response.success) memory.rememberTurn(phrase, response.text)
            ui.post {
                if (shuttingDown) return@post
                cloudBusy = false
                notificationText = response.text.take(220)
                updateNotification()
                speak(response.text)
            }
        }
    }

    private fun deferToVisibleApp(phrase: String, message: String) {
        pendingCommand = phrase.take(240)
        notificationText = message
        updateNotification()
        speak(message)
    }

    private fun fetchBackgroundNews() {
        if (cloudBusy || shuttingDown) return
        cloudBusy = true
        pendingCommand = null
        notificationText = "Получаю главную сводку новостей…"
        updateNotification()
        speak("Получаю главные новости")

        val persona = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
            .getString("persona", "J.A.R.V.I.S.").orEmpty()

        infoExecutor.execute {
            val summary = try {
                val items = newsFeed.fetchMainHeadlines()
                val prompt = JarvisNewsSummary.prompt(items)
                val response = gigaChat.askConversation(prompt, persona)
                if (response.success && JarvisNewsSummary.usableModelSummary(response.text)) response.text
                else JarvisNewsSummary.fallback(items)
            } catch (_: Exception) {
                "Не удалось получить главные новости. Проверьте интернет и повторите."
            }

            ui.post {
                if (shuttingDown) return@post
                cloudBusy = false
                notificationText = summary.take(220)
                updateNotification()
                speak(summary)
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank() || shuttingDown) {
            suppressUntil = SystemClock.elapsedRealtime() + 1_000L
            return
        }
        val settings = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
        val persona = settings.getString("persona", "J.A.R.V.I.S.").orEmpty()
        val fishKey = settings.getString("fish_api_key", "").orEmpty().trim()

        speaking = true
        // Keep the selected premium voice in the minimized foreground-service
        // mode too. If Fish Audio is absent/offline, fall back to Android TTS.
        if (fishKey.isNotBlank() && FishAudioTts.voiceIdFor(persona) != null) {
            fishAudioTts.speak(
                text = text,
                persona = persona,
                onError = {
                    ui.post {
                        if (!shuttingDown) speakWithSystemVoice(text, persona)
                    }
                },
                onComplete = { ui.post { if (!shuttingDown) endSpeech() } },
                onStart = { ui.post { if (!shuttingDown) speaking = true } }
            )
        } else {
            speakWithSystemVoice(text, persona)
        }
    }

    private fun speakWithSystemVoice(text: String, persona: String) {
        if (shuttingDown || text.isBlank()) {
            endSpeech()
            return
        }
        tts?.let { PersonaSpeech.apply(it, persona) }
        if (!ttsReady) {
            endSpeech()
            return
        }
        speaking = true
        val result = tts?.speak(
            text, TextToSpeech.QUEUE_FLUSH, null, "background-${SystemClock.elapsedRealtime()}"
        ) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) endSpeech()
    }

    private fun endSpeech() {
        speaking = false
        // Don't react to our own voice, including residual mic audio.
        suppressUntil = SystemClock.elapsedRealtime() + 1_100L
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "JARVIS • фоновый микрофон", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянный индикатор и выключатель фонового голосового режима"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (pendingCommand != null) {
                action = ACTION_OPEN_COMMAND
                putExtra(EXTRA_COMMAND, pendingCommand)
            }
        }
        val openPending = PendingIntent.getActivity(
            this, 720, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, WakeForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 721, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.jarvis_icon)
            .setContentTitle("J.A.R.V.I.S. • микрофон работает в фоне")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(openPending)
            .addAction(R.drawable.jarvis_icon, "Остановить микрофон", stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        if (foreground && !shuttingDown) notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun failClosed() {
        if (shuttingDown) return
        // Stop audio capture safely, but do NOT erase the user's
        // "listen while minimized" preference. Temporary microphone,
        // battery, model, OEM or service failures must not uncheck it.
        // Only an explicit user action (settings toggle / notification Stop)
        // is allowed to persist background_wake=false.
        shouldListenInBackground = false
        microphoneHandoffReady = false
        ui.removeCallbacks(micStartRetry)
        micStartAttempt = 0
        offline.stop()
        stopSelf()
    }

    private fun stopFromNotification() {
        getSharedPreferences("jarvis_settings", MODE_PRIVATE).edit()
            .putBoolean("background_wake", false)
            .putBoolean("wake_mode", false).apply()
        shouldListenInBackground = false
        microphoneHandoffReady = false
        stopSelf()
    }

    override fun onDestroy() {
        shuttingDown = true
        shouldListenInBackground = false
        microphoneHandoffReady = false
        armedUntil = 0L
        ui.removeCallbacksAndMessages(null)
        try { offline.release() } catch (_: Exception) {}
        try { infoExecutor.shutdownNow() } catch (_: Exception) {}
        try { fishAudioTts.release() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        active = null
        foreground = false
        super.onDestroy()
    }
}
