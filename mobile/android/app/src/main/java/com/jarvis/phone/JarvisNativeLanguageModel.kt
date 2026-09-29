package com.jarvis.phone

import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI adapter for the future native GGUF inference backend.
 *
 * Important: merely selecting a GGUF file does not make inference available.
 * The adapter becomes ready only when the APK contains libjarvisbrain.so and a
 * valid private model file is present. Until then JARVIS BRAIN safely falls
 * back to deterministic local reasoning and memory.
 */
class JarvisNativeLanguageModel(
    private val modelStore: JarvisModelStore
) : JarvisLanguageModel {

    private val lock = Any()
    private val preparing = AtomicBoolean(false)
    @Volatile private var loadedPath: String? = null

    override fun isReady(): Boolean =
        nativeLibraryLoaded &&
            runCatching { nativeRuntimeAvailable() }.getOrDefault(false) &&
            modelStore.modelFile() != null

    override fun modelLabel(): String =
        modelStore.modelFile()?.name ?: "не установлена"

    fun prepare(): Boolean {
        if (!nativeLibraryLoaded) return false
        val file = modelStore.modelFile() ?: return false
        if (loadedPath == file.absolutePath) return true
        if (!preparing.compareAndSet(false, true)) return false
        return try {
            synchronized(lock) {
                if (loadedPath == file.absolutePath) {
                    true
                } else if (nativeLoadModel(file.absolutePath)) {
                    loadedPath = file.absolutePath
                    true
                } else {
                    loadedPath = null
                    false
                }
            }
        } catch (_: Throwable) {
            loadedPath = null
            false
        } finally {
            preparing.set(false)
        }
    }

    override fun generate(
        prompt: String,
        maxNewTokens: Int
    ): JarvisLanguageModel.Generation {
        if (!nativeLibraryLoaded) {
            return JarvisLanguageModel.Generation(
                success = false,
                text = "Нативный модуль JARVIS BRAIN ещё не встроен в APK."
            )
        }

        val file = modelStore.modelFile()
            ?: return JarvisLanguageModel.Generation(
                success = false,
                text = "Локальная GGUF-модель не выбрана."
            )

        if (preparing.get()) {
            return JarvisLanguageModel.Generation(
                success = false,
                text = "Локальная модель ещё загружается в память. Повторите запрос через несколько секунд."
            )
        }
        if (loadedPath != file.absolutePath) {
            return JarvisLanguageModel.Generation(
                success = false,
                text = "Локальная модель ещё не готова в RAM. Откройте настройки и нажмите «Проверить мозг»."
            )
        }

        return synchronized(lock) {
            try {
                val profileId = modelStore.info().profileId
                val preparedPrompt = if (profileId.startsWith("qwen3-")) {
                    prompt.trimEnd() + "\n/no_think"
                } else {
                    prompt
                }
                val promptLimit = if (profileId == "qwen25-0.5b-q4_0") 1_200 else 2_200
                val tokenLimit = if (profileId == "qwen25-0.5b-q4_0") 48 else 96
                val rawOutput = nativeGenerate(
                    prompt = preparedPrompt.take(promptLimit),
                    maxNewTokens = maxNewTokens.coerceIn(8, tokenLimit)
                ).orEmpty()
                if (rawOutput == "__JARVIS_TIMEOUT__") {
                    return@synchronized JarvisLanguageModel.Generation(
                        success = false,
                        text = "Локальная модель не успела ответить за 5 секунд. Для быстрого режима используйте JARVIS Lite."
                    )
                }
                val output = cleanOutput(rawOutput)

                if (output.isBlank()) {
                    JarvisLanguageModel.Generation(
                        success = false,
                        text = "Локальная модель не вернула ответ."
                    )
                } else {
                    JarvisLanguageModel.Generation(
                        success = true,
                        text = output,
                        model = modelLabel()
                    )
                }
            } catch (_: Throwable) {
                loadedPath = null
                JarvisLanguageModel.Generation(
                    success = false,
                    text = "Ошибка локального нейросетевого движка."
                )
            }
        }
    }

    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        // Qwen3 may still emit an empty/short thinking wrapper when the
        // soft /no_think switch is used through a generic GGUF template.
        text = text.replace(
            Regex("^\\s*<think>[\\s\\S]*?</think>\\s*", RegexOption.IGNORE_CASE),
            ""
        ).trim()
        return text
    }

    fun unload() {
        if (!nativeLibraryLoaded) return
        synchronized(lock) {
            try {
                nativeUnloadModel()
            } catch (_: Throwable) {
                // Keep shutdown best-effort.
            }
            loadedPath = null
        }
    }

    private external fun nativeRuntimeAvailable(): Boolean
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxNewTokens: Int): String?
    private external fun nativeUnloadModel()

    companion object {
        private val nativeLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary("jarvisbrain")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
