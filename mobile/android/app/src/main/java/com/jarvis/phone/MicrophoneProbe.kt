package com.jarvis.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.abs

/**
 * Short, on-device hardware test. It never stores, transcribes, uploads, or
 * returns recorded samples. Only aggregate sample count and peak are used.
 * Call on a worker thread, and never at the same time as SpeechRecognizer.
 */
object MicrophoneProbe {
    data class Result(val status: String, val message: String)

    fun run(context: Context, shouldContinue: () -> Boolean): Result {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Result("error", "Нет разрешения RECORD_AUDIO. Разрешите микрофон в настройках Android.")
        }
        val rate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(rate, channel, encoding)
        if (minimum <= 0) return Result("error", "Android не поддерживает проверку аудиозахвата на 16 кГц.")
        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, channel, encoding, maxOf(minimum, 4096)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return Result("error", "Не удалось открыть микрофон. Проверьте доступ и закройте другие приложения записи.")
            }
            if (!shouldContinue()) return Result("cancelled", "Проверка отменена: приложение свёрнуто.")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return Result("error", "Android не начал запись. Проверьте системный переключатель микрофона.")
            }
            val buffer = ShortArray(1024)
            var peak = 0
            var countTotal = 0
            var failures = 0
            val started = SystemClock.elapsedRealtime()
            while (shouldContinue() && SystemClock.elapsedRealtime() - started < 1_600) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (++failures >= 3) break
                    continue
                }
                for (i in 0 until count) peak = maxOf(peak, abs(buffer[i].toInt()))
                countTotal += count
            }
            if (!shouldContinue()) return Result("cancelled", "Проверка отменена: приложение свёрнуто.")
            if (countTotal == 0) {
                return Result("error", "Микрофон открылся, но Android не передал аудиоданные.")
            }
            return if (peak < 150) {
                Result("quiet", "Аудиопоток работает, но сигнал очень тихий (пик $peak/32768). Повторите тест, говоря в микрофон.")
            } else {
                Result("ok", "Микрофон захватывает звук (пик $peak/32768). Если команды не распознаются, проверьте сервис голосового ввода Android.")
            }
        } catch (_: SecurityException) {
            return Result("error", "Android запретил запись. Проверьте разрешение и системный переключатель микрофона.")
        } catch (e: Exception) {
            return Result("error", "Ошибка аудиозахвата: ${e.javaClass.simpleName}.")
        } finally {
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) { }
            try { recorder?.release() } catch (_: Exception) { }
        }
    }
}
