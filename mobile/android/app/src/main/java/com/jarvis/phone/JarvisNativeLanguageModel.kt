package com.jarvis.phone

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
    @Volatile private var loadedPath: String? = null

    override fun isReady(): Boolean =
        nativeLibraryLoaded &&
            runCatching { nativeRuntimeAvailable() }.getOrDefault(false) &&
            modelStore.modelFile() != null

    override fun modelLabel(): String =
        modelStore.modelFile()?.name ?: "не установлена"

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

        return synchronized(lock) {
            try {
                if (loadedPath != file.absolutePath) {
                    if (!nativeLoadModel(file.absolutePath)) {
                        loadedPath = null
                        return@synchronized JarvisLanguageModel.Generation(
                            success = false,
                            text = "Не удалось загрузить локальную GGUF-модель."
                        )
                    }
                    loadedPath = file.absolutePath
                }

                val output = nativeGenerate(
                    prompt = prompt.take(18_000),
                    maxNewTokens = maxNewTokens.coerceIn(32, 1024)
                ).orEmpty().trim()

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
