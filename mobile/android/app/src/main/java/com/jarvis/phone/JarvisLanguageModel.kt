package com.jarvis.phone

/**
 * Stable boundary between JARVIS BRAIN and the local neural runtime.
 *
 * The rest of the app never needs to know whether generation is powered by
 * llama.cpp, another GGUF engine, or a future first-party runtime.
 */
interface JarvisLanguageModel {
    data class Generation(
        val success: Boolean,
        val text: String,
        val model: String = "",
        val tokensGenerated: Int = 0
    )

    fun isReady(): Boolean
    fun modelLabel(): String
    fun generate(prompt: String, maxNewTokens: Int = 96): Generation
}

/**
 * Used until the native GGUF runtime is linked. This object performs no
 * networking and makes the unavailable state explicit.
 */
object UnavailableJarvisLanguageModel : JarvisLanguageModel {
    override fun isReady(): Boolean = false

    override fun modelLabel(): String = "не установлена"

    override fun generate(prompt: String, maxNewTokens: Int): JarvisLanguageModel.Generation =
        JarvisLanguageModel.Generation(
            success = false,
            text = "Локальная языковая модель пока не подключена."
        )
}
