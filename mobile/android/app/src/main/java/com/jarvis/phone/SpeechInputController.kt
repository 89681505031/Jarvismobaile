package com.jarvis.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One foreground recognition session at a time. All methods run on the main thread. */
class SpeechInputController(
    private val context: Context,
    private val onState: (String, String) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onUnavailable: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = SpeechSession()
    private var recognizer: SpeechRecognizer? = null
    val isListening: Boolean get() = session.active

    fun resume() { session.resume() }
    fun pause() { session.pause(); dispose(); onState("idle", "Нажмите на круг, чтобы говорить.") }
    fun destroy() { pause() }
    fun cancel() { session.cancel(); dispose(); onState("idle", "Нажмите на круг, чтобы говорить.") }

    fun start(durationMs: Long = 20_000L, onDevice: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session.active || !session.foreground) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("Нет доступа к микрофону. Разрешите его в настройках приложения.")
            return
        }
        val deviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val useDevice = onDevice || !SpeechRecognizer.isRecognitionAvailable(context)
        if (useDevice && !deviceAvailable) { onUnavailable(); return }
        val token = session.begin() ?: return
        var partial = ""
        onState("starting", "Подключаю микрофон…")
        fun finish(text: String?, error: String?) {
            if (!session.finish(token)) return
            dispose()
            if (!text.isNullOrBlank()) {
                onState("processing", "Речь распознана.")
                onText(text.trim())
            } else {
                onState("idle", "Нажмите на круг, чтобы повторить.")
                onError(error ?: "Речь не распознана. Повторите, пожалуйста.")
            }
        }
        fun deadline(delay: Long) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ finish(partial.takeIf { it.isNotBlank() }, "Сервис распознавания не ответил. Повторите попытку.") }, delay)
        }
        try {
            recognizer = if (useDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (session.accepts(token)) onState("listening", "Слушаю. Говорите обычным голосом.")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) { if (session.accepts(token)) onLevel(if (rmsdB.isFinite()) rmsdB else 0f) }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!session.accepts(token)) return
                    onState("processing", "Распознаю речь…")
                    deadline(8_000)
                }
                override fun onPartialResults(results: Bundle?) {
                    if (session.accepts(token)) best(results).takeIf { it.isNotBlank() }?.let { partial = it }
                }
                override fun onResults(results: Bundle?) { finish(best(results).ifBlank { partial }, null) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!session.accepts(token)) return
                    if (partial.isNotBlank() && error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        finish(partial, null)
                    } else if (!useDevice && deviceAvailable && error in listOf(SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT)) {
                        session.cancel()
                        dispose()
                        start(durationMs, onDevice = true)
                    } else finish(null, errorMessage(error))
                }
            })
            deadline(durationMs.coerceIn(5_000, 30_000) + 5_000)
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            })
        } catch (_: SecurityException) {
            finish(null, "Android запретил доступ к микрофону. Проверьте разрешения и переключатель микрофона в шторке.")
        } catch (_: Exception) {
            finish(null, "Не удалось запустить сервис распознавания речи. Проверьте голосовой ввод Android.")
        }
    }

    private fun best(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun dispose() {
        handler.removeCallbacksAndMessages(null)
        val old = recognizer
        recognizer = null
        try { old?.cancel() } catch (_: Exception) { }
        try { old?.destroy() } catch (_: Exception) { }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Android не смог открыть микрофон. Закройте другие приложения записи и проверьте переключатель микрофона в шторке."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Разрешите доступ к микрофону в настройках J.A.R.V.I.S."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Сервис распознавания занят. Подождите и нажмите на круг ещё раз."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет связи с сервисом распознавания. Проверьте интернет и голосовой ввод Android."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не удалось расслышать речь. Нажмите на круг и повторите."
        12, 13 -> "Русский язык недоступен в сервисе распознавания. Установите русский голосовой пакет в настройках Android."
        10 -> "Слишком много запросов к распознавателю. Подождите несколько секунд."
        else -> "Ошибка распознавания речи ($error). Проверьте голосовой ввод Android."
    }
}
