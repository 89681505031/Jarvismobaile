package com.jarvis.phone

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Explicit camera/gallery choice only. Input image stays on device: bundled
 * Latin OCR and image labels, not face identification or cloud vision.
 */
class JarvisVision(private val context: Context) {
    fun fromPhoto(uri: Uri, result: (String) -> Unit) {
        try { describe(InputImage.fromFilePath(context, uri), result) }
        catch (_: Exception) { result("Не удалось открыть выбранное изображение.") }
    }
    fun fromCameraThumbnail(bitmap: Bitmap, result: (String) -> Unit) {
        describe(InputImage.fromBitmap(bitmap, 0), result)
    }

    private fun describe(image: InputImage, result: (String) -> Unit) {
        val labels = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var complete = 0
        var text = ""
        var objects = ""
        fun finish() {
            complete++
            if (complete == 2) {
                val output = listOf(objects, text).filter { it.isNotBlank() }
                result(if (output.isEmpty()) "Не удалось распознать объекты или латинский текст на фото."
                else output.joinToString("\n"))
            }
        }
        labels.process(image).addOnSuccessListener { found ->
            objects = found.filter { it.confidence >= 0.55f }.take(5)
                .joinToString(", ") { it.text }
                .let { if (it.isBlank()) "" else "Возможно, на фото: $it" }
        }.addOnFailureListener {
            objects = "Объекты не распознаны."
        }.addOnCompleteListener {
            labels.close()
            finish()
        }
        ocr.process(image).addOnSuccessListener {
            val output = it.text.trim().take(1100)
            if (output.isNotBlank()) text = "Текст (латиница): $output"
        }.addOnCompleteListener {
            ocr.close()
            finish()
        }
    }
}
