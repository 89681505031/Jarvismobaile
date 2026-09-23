package com.jarvis.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * One sustained, keyless on-device microphone session. A user-initiated
 * microphone foreground service can also own it when the app is minimized.
 * No SpeechRecognizer restarts, tones, network requests or saved audio while listening.
 * The separate optional Vosk Russian model is downloaded only at an explicit tap.
 */
class OfflineWakeEngine(
    private val context: Context,
    private val onStatus: (String, String) -> Unit,
    private val onMatch: (WakeWordMatcher.Activation) -> Unit,
    private val stayOpenOnMatch: Boolean = false,
    private val onUtterance: ((String) -> Unit)? = null,
    // Indicates recognizer partial-speech activity, NOT measured PCM volume.
    // Foreground HUD may use it for an approximate listening animation.
    private val onVoiceActivity: ((Float) -> Unit)? = null
) {
    companion object {
        private const val URL_MODEL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        private const val SHA256_MODEL =
            "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"
        private const val MAX_COMPRESSED = 65L * 1024 * 1024
        private const val MAX_EXTRACTED = 220L * 1024 * 1024
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val location = File(context.filesDir, "quiet-wake-ru-0.22")
    private val zipFile = File(context.cacheDir, "quiet-wake-model.part")
    private val unpacking = File(context.filesDir, "quiet-wake-installing")
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var loading = false
    private var downloading = false
    private var disposed = false
    private var requested = false
    private var generation = 0
    private var lastPartialAt = 0L

    fun installed(): Boolean = modelLooksValid(location)
    fun downloading(): Boolean = downloading

    fun install() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (disposed || downloading) return
        if (installed()) {
            onStatus("ready", "Офлайн-модель уже установлена. Включите ожидание имени.")
            return
        }
        downloading = true
        onStatus("download", "Загружаю русскую офлайн-модель (~46 МБ).")
        io.execute {
            try {
                if (zipFile.exists()) zipFile.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = URL(URL_MODEL).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    conn.instanceFollowRedirects = false
                    conn.connect()
                    if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                    val length = conn.contentLengthLong
                    if (length > MAX_COMPRESSED) error("Слишком большой архив модели")
                    var downloaded = 0L
                    var progress = -1
                    conn.inputStream.use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val chunk = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(chunk)
                                if (n < 0) break
                                downloaded += n
                                if (downloaded > MAX_COMPRESSED) error("Слишком большой архив модели")
                                digest.update(chunk, 0, n)
                                output.write(chunk, 0, n)
                                if (length > 0) {
                                    val percent = (downloaded * 100 / length).toInt()
                                    if (percent >= progress + 10) {
                                        progress = percent
                                        update("download", "Загрузка модели: $percent%")
                                    }
                                }
                            }
                        }
                    }
                } finally { conn.disconnect() }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != SHA256_MODEL) error("Контрольная сумма модели не совпадает")
                update("download", "Распаковываю офлайн-модель…")
                if (unpacking.exists()) unpacking.deleteRecursively()
                unpacking.mkdirs()
                var total = 0L
                var entries = 0
                val root = unpacking.canonicalPath + File.separator
                ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > 300) error("В архиве слишком много файлов")
                        val name = entry.name.replace('\\', '/')
                        // The official archive has one outer directory; remove it.
                        val inner = name.substringAfter('/', "")
                        if (inner.isBlank()) { zip.closeEntry(); continue }
                        val output = File(unpacking, inner)
                        val canonical = output.canonicalPath
                        if (!canonical.startsWith(root)) error("Небезопасный путь архива")
                        if (entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            FileOutputStream(output).use { stream ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_EXTRACTED) error("Превышен размер модели")
                                    stream.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
                if (!modelLooksValid(unpacking)) error("В архиве отсутствуют файлы модели")
                if (location.exists()) location.deleteRecursively()
                if (!unpacking.renameTo(location)) error("Не удалось установить модель")
                update("ready", "Тихая офлайн-модель установлена. Можно включить ожидание имени.")
            } catch (e: Exception) {
                unpacking.deleteRecursively()
                update("error", "Не удалось установить модель: ${e.message ?: "ошибка соединения"}")
            } finally {
                zipFile.delete()
                ui.post { downloading = false }
            }
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (disposed || requested || loading || service != null) return
        if (!installed()) {
            onStatus("model_needed", "Для тихого ожидания один раз загрузите офлайн-модель в настройках.")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("error", "Разрешите доступ к микрофону в Android.")
            return
        }
        requested = true
        loading = true
        val ticket = ++generation
        onStatus("starting", "Запускаю тихое ожидание имени…")
        io.execute {
            var recognizerCandidate: Recognizer? = null
            var serviceCandidate: SpeechService? = null
            try {
                val localModel = model ?: Model(location.absolutePath).also { model = it }
                recognizerCandidate = Recognizer(localModel, 16_000f)
                serviceCandidate = SpeechService(recognizerCandidate, 16_000f)
                val rec = recognizerCandidate
                val svc = serviceCandidate
                ui.post {
                    loading = false
                    if (disposed || !requested || generation != ticket) {
                        io.execute { svc.shutdown(); rec.close() }
                        return@post
                    }
                    recognizer = rec
                    service = svc
                    val listener = object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String) {
                            if (onVoiceActivity == null || generation != ticket || !requested) return
                            val partial = try {
                                JSONObject(hypothesis).optString("partial").trim()
                            } catch (_: Exception) { "" }
                            if (partial.isBlank()) return
                            val now = SystemClock.elapsedRealtime()
                            // Bound WebView messages, no microphone buffer storage.
                            if (now - lastPartialAt < 120L) return
                            lastPartialAt = now
                            val strength = (0.24f + partial.length * 0.024f)
                                .coerceIn(0.3f, 0.82f)
                            ui.post {
                                if (!disposed && requested && generation == ticket)
                                    onVoiceActivity?.invoke(strength)
                            }
                        }
                        override fun onResult(hypothesis: String) { accept(hypothesis, "text", ticket) }
                        override fun onFinalResult(hypothesis: String) { accept(hypothesis, "text", ticket) }
                        override fun onError(exception: Exception) {
                            if (generation == ticket) {
                                stop()
                                onStatus("error", "Офлайн-микрофон недоступен: ${exception.message ?: "ошибка записи"}")
                            }
                        }
                        override fun onTimeout() = Unit // No time limit is supplied.
                    }
                    if (!svc.startListening(listener)) {
                        stop()
                        onStatus("error", "Не удалось запустить офлайн-распознавание.")
                    } else onStatus("listening", "Тихое ожидание: Джарвис, Астра, Луна, Терра, Сайбер")
                }
            } catch (e: Exception) {
                try { serviceCandidate?.shutdown() } catch (_: Exception) {}
                try { recognizerCandidate?.close() } catch (_: Exception) {}
                ui.post {
                    if (generation == ticket) {
                        loading = false
                        requested = false
                        onStatus("error", "Не удалось запустить тихое ожидание: ${e.message ?: "ошибка"}")
                    }
                }
            }
        }
    }

    private fun accept(json: String, field: String, ticket: Int) {
        if (disposed || !requested || ticket != generation) return
        val text = try { JSONObject(json).optString(field).trim() } catch (_: Exception) { "" }
        if (text.isBlank()) return
        val match = WakeWordMatcher.parse(text)
        if (match != null) {
            if (!stayOpenOnMatch) stop()
            onMatch(match)
        } else onUtterance?.invoke(text)
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        check(Looper.myLooper() == Looper.getMainLooper())
        requested = false
        loading = false
        generation++
        val oldService = service
        val oldRecognizer = recognizer
        service = null
        recognizer = null
        if (oldService != null || oldRecognizer != null) {
            io.execute {
                try { oldService?.cancel() } catch (_: Exception) {}
                try { oldService?.shutdown() } catch (_: Exception) {}
                try { oldRecognizer?.close() } catch (_: Exception) {}
                if (onStopped != null) ui.post { if (!disposed) onStopped() }
            }
        } else onStopped?.invoke()
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        disposed = true
        io.execute {
            try { model?.close() } catch (_: Exception) {}
            model = null
        }
        io.shutdown()
    }

    private fun update(state: String, message: String) {
        ui.post { if (!disposed) onStatus(state, message) }
    }

    private fun modelLooksValid(folder: File): Boolean =
        File(folder, "am/final.mdl").isFile &&
        File(folder, "conf/model.conf").isFile &&
        File(folder, "graph/HCLr.fst").isFile
}
