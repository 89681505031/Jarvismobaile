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

        @Volatile var active: WakeForegroundService? = null
            private set

        // Only MainActivity sets this after its microphone has actually stopped.
        @Volatile var shouldListenInBackground: Boolean = false
    }

    private val ui = Handler(Looper.getMainLooper())
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var offline: OfflineWakeEngine
    private lateinit var router: PhoneCommandRouter
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var suppressUntil = 0L
    private var armedUntil = 0L
    private var foreground = false
    private var shuttingDown = false
    private var pendingCommand: String? = null
    private var notificationText = "Тихая активация работает в фоне"
    private var startedAtElapsed = 0L
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
        offline = OfflineWakeEngine(
            this,
            onStatus = { status, message ->
                when (status) {
                    "error", "model_needed" -> {
                        notificationText = message
                        updateNotification()
                        // Stop rather than run an apparent 'always listening' service
                        // that is not capturing any audio.
                        failClosed()
                    }
                    "listening" -> {
                        notificationText = "Тихая активация: Джарвис, Астра, Луна, Терра, Сайбер"
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
                if (shouldListenInBackground) startBackgroundListening()
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
        if (shuttingDown || !foreground || !shouldListenInBackground ||
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
        if (offline.installed()) offline.start()
    }

    /** Main-thread only: hand the microphone back to the visible Activity. */
    fun pauseForForeground(afterStopped: () -> Unit) {
        shouldListenInBackground = false
        armedUntil = 0L
        startedAtElapsed = 0L
        ui.removeCallbacks(batteryCheck)
        pendingCommand = null
        notificationText = "Работа в фоне включена. Откройте JARVIS для обычных команд."
        updateNotification()
        offline.stop { if (!shuttingDown) afterStopped() }
    }

    private fun onWake(match: WakeWordMatcher.Activation) {
        if (shuttingDown || !shouldListenInBackground || !foreground) return
        if (speaking || SystemClock.elapsedRealtime() < suppressUntil) return
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
        if (speaking || SystemClock.elapsedRealtime() < suppressUntil) return
        if (armedUntil == 0L || SystemClock.elapsedRealtime() > armedUntil) {
            armedUntil = 0L
            return
        }
        armedUntil = 0L
        executeBackgroundCommand(phrase)
    }

    private fun executeBackgroundCommand(phrase: String) {
        if (BackgroundCommandPolicy.permitted(phrase)) {
            val response = try { router.execute(phrase) } catch (_: Exception) {
                "Не удалось выполнить команду. Откройте JARVIS."
            }
            notificationText = response
            updateNotification()
            speak(response)
        } else {
            // Background activity launches, calls, message access and network AI
            // are NOT silently attempted from a service. User must tap first.
            pendingCommand = phrase.take(240)
            notificationText = "Нажмите на уведомление, чтобы продолжить команду в JARVIS"
            updateNotification()
            speak("Для этой команды откройте Джарвис через уведомление.")
        }
    }

    private fun speak(text: String) {
        tts?.let {
            val persona = getSharedPreferences("jarvis_settings", MODE_PRIVATE)
                .getString("persona", "J.A.R.V.I.S.").orEmpty()
            PersonaSpeech.apply(it, persona)
        }
        if (!ttsReady || text.isBlank()) {
            suppressUntil = SystemClock.elapsedRealtime() + 1_000L
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
        getSharedPreferences("jarvis_settings", MODE_PRIVATE).edit()
            .putBoolean("background_wake", false).apply()
        shouldListenInBackground = false
        offline.stop()
        stopSelf()
    }

    private fun stopFromNotification() {
        getSharedPreferences("jarvis_settings", MODE_PRIVATE).edit()
            .putBoolean("background_wake", false)
            .putBoolean("wake_mode", false).apply()
        shouldListenInBackground = false
        stopSelf()
    }

    override fun onDestroy() {
        shuttingDown = true
        shouldListenInBackground = false
        armedUntil = 0L
        ui.removeCallbacksAndMessages(null)
        try { offline.release() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        active = null
        foreground = false
        super.onDestroy()
    }
}
