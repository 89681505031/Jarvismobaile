package com.jarvis.phone

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Owns the local GGUF model file used by JARVIS BRAIN.
 *
 * Inference is always local. A model may be selected from Android storage or
 * downloaded once from a pinned HTTPS source, verified by SHA-256 and then kept
 * in app-private storage.
 */
class JarvisModelStore(private val context: Context) {
    data class Profile(
        val id: String,
        val label: String,
        val approximateBytes: Long,
        val url: String,
        val sha256: String
    )

    data class Info(
        val present: Boolean,
        val validGguf: Boolean,
        val bytes: Long,
        val profileId: String = "",
        val label: String = ""
    )

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) {
        val percent: Int
            get() = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
            } else {
                -1
            }
    }

    private val directory = File(context.filesDir, "jarvis-brain")
    private val model = File(directory, "model.gguf")
    private val prefs = context.getSharedPreferences("jarvis_brain_model", Context.MODE_PRIVATE)

    fun profiles(): List<Profile> = PROFILES

    fun profile(profileId: String): Profile? =
        PROFILES.firstOrNull { it.id == profileId }

    fun info(): Info {
        val present = model.isFile
        val profileId = prefs.getString("profile_id", "").orEmpty()
        val profile = profile(profileId)
        return Info(
            present = present,
            validGguf = present && hasGgufMagic(model),
            bytes = if (present) model.length() else 0L,
            profileId = profileId,
            label = profile?.label ?: if (present) "Пользовательская GGUF" else ""
        )
    }

    fun modelFile(): File? =
        model.takeIf { it.isFile && hasGgufMagic(it) }

    fun installFrom(uri: Uri): Result<Info> = runCatching {
        directory.mkdirs()
        val temp = tempFile()
        temp.delete()

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть выбранный файл." }
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }

        validateBasic(temp)
        replaceModel(temp)
        prefs.edit().remove("profile_id").apply()
        info()
    }

    fun downloadProfile(
        profileId: String,
        onProgress: (Progress) -> Unit = {}
    ): Result<Info> = runCatching {
        val profile = requireNotNull(profile(profileId)) {
            "Неизвестный профиль локальной модели."
        }
        require(profile.url.startsWith("https://huggingface.co/")) {
            "Источник модели не разрешён."
        }

        directory.mkdirs()
        val requiredFree = profile.approximateBytes + FREE_SPACE_MARGIN_BYTES
        require(directory.usableSpace >= requiredFree) {
            "Недостаточно свободного места. Нужно примерно ${humanSize(requiredFree)}."
        }

        val temp = tempFile()
        temp.delete()
        val digest = MessageDigest.getInstance("SHA-256")

        val connection = (URL(profile.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "JARVIS-Mobile/0.1")
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            connection.connect()
            require(connection.responseCode in 200..299) {
                "Сервер модели ответил кодом ${connection.responseCode}."
            }
            val reported = connection.contentLengthLong
            val total = if (reported > 0L) reported else profile.approximateBytes

            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    var lastProgressBytes = -PROGRESS_STEP_BYTES
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read

                        if (downloaded - lastProgressBytes >= PROGRESS_STEP_BYTES ||
                            downloaded == total
                        ) {
                            lastProgressBytes = downloaded
                            onProgress(Progress(downloaded, total))
                        }
                    }
                    output.fd.sync()
                    onProgress(Progress(downloaded, total))
                }
            }
        } finally {
            connection.disconnect()
        }

        validateBasic(temp)
        val actualSha = digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
        require(actualSha.equals(profile.sha256, ignoreCase = true)) {
            temp.delete()
            "Проверка SHA-256 не пройдена. Файл модели удалён."
        }

        val minExpected = (profile.approximateBytes * 85L) / 100L
        val maxExpected = (profile.approximateBytes * 115L) / 100L
        require(temp.length() in minExpected..maxExpected) {
            temp.delete()
            "Размер загруженной модели не совпадает с ожидаемым."
        }

        replaceModel(temp)
        prefs.edit().putString("profile_id", profile.id).apply()
        info()
    }

    fun remove(): Boolean {
        tempFile().delete()
        prefs.edit().remove("profile_id").apply()
        return !model.exists() || model.delete()
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0 МБ"
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) {
            String.format(Locale.US, "%.2f ГБ", mib / 1024.0)
        } else {
            String.format(Locale.US, "%.0f МБ", mib)
        }
    }

    private fun tempFile(): File = File(directory, "model.gguf.part")

    private fun validateBasic(file: File) {
        require(file.length() >= 1024L * 1024L) {
            "Файл слишком маленький для GGUF-модели."
        }
        require(hasGgufMagic(file)) {
            "Файл не похож на GGUF-модель."
        }
    }

    private fun replaceModel(temp: File) {
        if (model.exists() && !model.delete()) {
            error("Не удалось заменить старую модель.")
        }
        if (!temp.renameTo(model)) {
            FileInputStream(temp).use { input ->
                FileOutputStream(model).use { output -> input.copyTo(output) }
            }
            temp.delete()
        }
    }

    private fun hasGgufMagic(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 &&
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val BUFFER_BYTES = 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 4L * 1024L * 1024L
        private const val FREE_SPACE_MARGIN_BYTES = 384L * 1024L * 1024L

        private val PROFILES = listOf(
            Profile(
                id = "qwen25-0.5b-q2k",
                label = "JARVIS Instant · Qwen2.5 0.5B Q2_K",
                approximateBytes = 415_000_000L,
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/6dd44a1fb35d11b5d1b28902876ce3cc9e882d0e/qwen2.5-0.5b-instruct-q2_k.gguf?download=true",
                sha256 = "9ee36184e616dfc76df4f5dd66f908dbde6979524ae36e6cefb67f532f798cb8"
            ),
            Profile(
                id = "qwen3-0.6b-q4km",
                label = "JARVIS Lite · Qwen3 0.6B Q4_K_M",
                approximateBytes = 397_000_000L,
                url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/1208e45d782fe18602c5eaf10e5758d5b0f24c03/Qwen3-0.6B-Q4_K_M.gguf?download=true",
                sha256 = "b0638f08417a2d3c8652760462eb5407c6e30173cf9608ad0820757a281eea0e"
            ),
            Profile(
                id = "qwen3-1.7b-q4km",
                label = "JARVIS Standard · Qwen3 1.7B Q4_K_M",
                approximateBytes = 1_280_000_000L,
                url = "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/daeb8e2d528a760970442092f6bf1e55c3b659eb/Qwen3-1.7B-Q4_K_M.gguf?download=true",
                sha256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
            )
        )
    }
}
