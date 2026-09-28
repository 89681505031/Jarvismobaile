package com.jarvis.phone

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.cyrillic.CyrillicTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit camera/gallery choice only. Images stay on device.
 * Local ML Kit extracts scene labels, Latin/Cyrillic text and QR/barcodes.
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
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val cyrillic = TextRecognition.getClient(CyrillicTextRecognizerOptions.Builder().build())
        val barcodes = BarcodeScanning.getClient()

        val complete = AtomicInteger(0)
        var objects = ""
        val texts = linkedSetOf<String>()
        val codes = linkedSetOf<String>()

        fun finish() {
            if (complete.incrementAndGet() != 4) return
            val output = mutableListOf<String>()
            if (objects.isNotBlank()) output += objects
            if (texts.isNotEmpty()) output += "Распознанный текст: " + texts.joinToString(" · ").take(1800)
            if (codes.isNotEmpty()) output += "QR/штрихкод: " + codes.joinToString(" · ").take(1000)
            result(
                if (output.isEmpty()) "Не удалось уверенно распознать объекты, текст или код на изображении."
                else output.joinToString("\n")
            )
        }

        labels.process(image).addOnSuccessListener { found ->
            objects = found.filter { it.confidence >= 0.50f }.take(7)
                .joinToString(", ") { it.text }
                .let { if (it.isBlank()) "" else "На изображении, вероятно: $it." }
        }.addOnCompleteListener {
            labels.close()
            finish()
        }

        latin.process(image).addOnSuccessListener {
            it.text.trim().takeIf(String::isNotBlank)?.let { value -> texts += value.take(1200) }
        }.addOnCompleteListener {
            latin.close()
            finish()
        }

        cyrillic.process(image).addOnSuccessListener {
            it.text.trim().takeIf(String::isNotBlank)?.let { value -> texts += value.take(1200) }
        }.addOnCompleteListener {
            cyrillic.close()
            finish()
        }

        barcodes.process(image).addOnSuccessListener { found ->
            found.take(8).mapNotNullTo(codes) { it.rawValue?.trim()?.takeIf(String::isNotBlank)?.take(300) }
        }.addOnCompleteListener {
            barcodes.close()
            finish()
        }
    }
}
