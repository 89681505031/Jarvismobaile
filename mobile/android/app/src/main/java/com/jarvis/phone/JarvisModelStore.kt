package com.jarvis.phone

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Owns the local GGUF model file used by JARVIS BRAIN.
 *
 * The model is copied into app-private storage. No network access, API key or
 * cloud account is involved.
 */
class JarvisModelStore(private val context: Context) {
    private val directory = File(context.filesDir, "jarvis-brain")
    private val model = File(directory, "model.gguf")

    data class Info(
        val present: Boolean,
        val validGguf: Boolean,
        val bytes: Long
    )

    fun info(): Info {
        val present = model.isFile
        return Info(
            present = present,
            validGguf = present && hasGgufMagic(model),
            bytes = if (present) model.length() else 0L
        )
    }

    fun modelFile(): File? =
        model.takeIf { it.isFile && hasGgufMagic(it) }

    fun installFrom(uri: Uri): Result<Info> = runCatching {
        directory.mkdirs()
        val temp = File(directory, "model.gguf.part")
        temp.delete()

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось открыть выбранный файл." }
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }

        require(temp.length() >= 1024L * 1024L) {
            "Файл слишком маленький для GGUF-модели."
        }
        require(hasGgufMagic(temp)) {
            "Выбранный файл не похож на GGUF-модель."
        }

        if (model.exists() && !model.delete()) {
            error("Не удалось заменить старую модель.")
        }
        if (!temp.renameTo(model)) {
            FileInputStream(temp).use { input ->
                FileOutputStream(model).use { output -> input.copyTo(output) }
            }
            temp.delete()
        }

        info()
    }

    fun remove(): Boolean {
        File(directory, "model.gguf.part").delete()
        return !model.exists() || model.delete()
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return "0 МБ"
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) {
            String.format(java.util.Locale.US, "%.2f ГБ", mib / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.0f МБ", mib)
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
}
