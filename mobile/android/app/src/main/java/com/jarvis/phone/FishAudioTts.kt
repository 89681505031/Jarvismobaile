package com.jarvis.phone

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class FishAudioTts(private val context: Context) {
    companion object {
        private const val API_URL = "https://api.fish.audio/v1/tts"
        private const val MODEL = "s2.1-pro-free"
        private const val JARVIS_VOICE_ID = "4c3eaacc1a0545cdb0295bfddf3e3785"
        private const val ASTRA_VOICE_ID = "560ef3514c4f44ee9b36b270d718bb39"
        private const val LUNA_VOICE_ID = "d76f881a99464a74962db3c90918aea0"
        private const val CYBER_VOICE_ID = "cc1b79b1108f4ed3b8aac118ba6ebd07"
        private const val TERRA_VOICE_ID = "b347db033a6549378b48d00acb0d06cd"

        fun voiceIdFor(persona: String): String? = when (persona.trim().lowercase()) {
            "j.a.r.v.i.s.", "jarvis", "j.a.r.v.i.s" -> JARVIS_VOICE_ID
            "astra" -> ASTRA_VOICE_ID
            "luna" -> LUNA_VOICE_ID
            "terra" -> TERRA_VOICE_ID
            "cyber", "сайбер", "кибер" -> CYBER_VOICE_ID
            else -> null
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var player: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var amplitudeSink: ((Float) -> Unit)? = null
    private var playingFile: File? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false

    fun speak(
        text: String,
        persona: String,
        onError: ((String) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onStart: (() -> Unit)? = null,
        onAmplitude: ((Float) -> Unit)? = null
    ) {
        if (closed) return
        stop()
        amplitudeSink = onAmplitude
        val token = generation.get()
        val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("fish_api_key", "").orEmpty().trim()
        val voiceId = voiceIdFor(persona)
        if (apiKey.isBlank() || voiceId.isNullOrBlank()) { onError?.invoke("Голос Fish Audio не настроен"); return }
        executor.execute {
            var downloaded: File? = null
            var request: HttpURLConnection? = null
            try {
                if (token != generation.get() || closed) return@execute
                request = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                    setRequestProperty("model", MODEL)
                }
                connection = request
                if (token != generation.get() || closed) return@execute
                val body = JSONObject().put("text", text).put("reference_id", voiceId).put("format", "mp3")
                request.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (request.responseCode !in 200..299) throw IllegalStateException("Fish Audio HTTP ${request.responseCode}")
                val file = File.createTempFile("jarvis_fish_", ".mp3", context.cacheDir)
                downloaded = file
                request.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                mainHandler.post {
                    if (token != generation.get() || closed) { file.delete(); return@post }
                    try {
                        val next = MediaPlayer()
                        player = next
                        playingFile = file
                        next.setDataSource(file.absolutePath)
                        next.setOnCompletionListener {
                            if (token == generation.get()) {
                                releasePlayer()
                                onComplete?.invoke()
                            }
                        }
                        next.setOnErrorListener { _, _, _ ->
                            if (token == generation.get()) { releasePlayer(); onError?.invoke("Ошибка воспроизведения Fish Audio") }
                            true
                        }
                        next.setOnPreparedListener {
                            if (token == generation.get() && !closed) {
                                try {
                                    attachVisualizer(it, token, onAmplitude)
                                    it.start()
                                    onStart?.invoke()
                                } catch (_: Exception) {
                                    releasePlayer()
                                    onError?.invoke("Не удалось начать воспроизведение Fish Audio")
                                }
                            }
                        }
                        next.prepareAsync()
                    } catch (_: Exception) {
                        releasePlayer()
                        onError?.invoke("Ошибка воспроизведения Fish Audio")
                    }
                }
            } catch (_: Exception) {
                downloaded?.delete()
                mainHandler.post { if (token == generation.get() && !closed) onError?.invoke("Fish Audio недоступен") }
            } finally {
                request?.disconnect()
                if (connection === request) connection = null
            }
        }
    }

    private fun attachVisualizer(
        mediaPlayer: MediaPlayer,
        token: Int,
        onAmplitude: ((Float) -> Unit)?
    ) {
        if (onAmplitude == null) return
        releaseVisualizer()
        try {
            val next = Visualizer(mediaPlayer.audioSessionId)
            next.captureSize = Visualizer.getCaptureSizeRange()[1]
            next.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int
                ) {
                    if (waveform.isNullOrEmpty() || token != generation.get() || closed) return
                    var sum = 0.0
                    for (sample in waveform) {
                        val centered = (sample.toInt() and 0xff) - 128
                        sum += centered * centered
                    }
                    val rms = sqrt(sum / waveform.size) / 128.0
                    val level = (rms * 3.1).coerceIn(0.0, 1.0).toFloat()
                    mainHandler.post {
                        if (token == generation.get() && !closed) onAmplitude.invoke(level)
                    }
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int
                ) = Unit
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            next.enabled = true
            visualizer = next
        } catch (_: Exception) {
            // Voice playback must continue even on devices that disable Visualizer.
            releaseVisualizer()
        }
    }

    private fun releaseVisualizer() {
        try { visualizer?.enabled = false } catch (_: Exception) { }
        try { visualizer?.release() } catch (_: Exception) { }
        visualizer = null
        amplitudeSink?.let { sink -> mainHandler.post { sink(0f) } }
    }

    private fun releasePlayer() {
        releaseVisualizer()
        try { player?.release() } catch (_: Exception) { }
        player = null
        playingFile?.delete()
        playingFile = null
        amplitudeSink = null
    }

    fun stop() {
        generation.incrementAndGet()
        connection?.disconnect()
        releasePlayer()
    }

    fun release() {
        closed = true
        stop()
        executor.shutdownNow()
    }
}
