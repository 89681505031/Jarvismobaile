package com.jarvis.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Explicit SAF imports only; capped reads and image decoding. */
class JarvisAttachments(private val context: Context) {
    @Volatile var text: String = ""
        private set
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break; out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
    fun clear() { text = ""; dir.listFiles()?.forEach { it.delete() } }
    fun importFile(uri: Uri, result: (JSONObject) -> Unit) {
        try {
            clear()
            val resolver = context.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            var name = "Файл"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0).orEmpty().take(200)
            }
            val file = File.createTempFile("import-", ".bin", dir)
            try {
                resolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(8192); var size = 0
                        while (true) { val n = input.read(buf); if (n < 0) break
                            size += n; require(size <= 10 * 1024 * 1024) { "Файл больше 10 МБ" }; output.write(buf, 0, n) }
                    }
                } ?: error("Не удалось открыть файл")
                when {
                    mime.startsWith("image/") -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Формат фото не поддерживается" }
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Не удалось прочитать фото")
                        File(dir, "source.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        JarvisVision(context).fromPhoto(Uri.fromFile(File(dir, "source.png"))) { scan ->
                            text = scan.take(12000)
                            result(JSONObject().put("name", name).put("text", text).put("image", true))
                        }
                    }
                    mime == "application/pdf" || name.endsWith(".pdf", true) -> scanPdf(file, name, result)
                    name.endsWith(".docx", true) -> {
                        val xml = ZipInputStream(file.inputStream()).use { zip ->
                            var found = ""
                            while (true) { val entry = zip.nextEntry ?: break
                                if (entry.name == "word/document.xml") {
                                    val bytes = readLimited(zip, 1024 * 1024 + 1)
                                    require(bytes.size <= 1024 * 1024) { "Документ слишком большой" }
                                    found = String(bytes, Charsets.UTF_8); break
                                }
                            }; found
                        }
                        require(xml.isNotEmpty()) { "В DOCX нет текста" }
                        text = xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "")
                            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").take(12000)
                        result(JSONObject().put("name", name).put("text", text))
                    }
                    mime.startsWith("text/") || name.substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "json", "js", "kt", "java", "html", "xml", "log") -> {
                        text = file.inputStream().use { String(readLimited(it, 48000), Charsets.UTF_8) }.take(12000)
                        result(JSONObject().put("name", name).put("text", text))
                    }
                    else -> result(JSONObject().put("name", name).put("text", "Этот формат пока не читается. Выберите фото, PDF, DOCX или текстовый файл."))
                }
            } finally { if (mime != "application/pdf" && !name.endsWith(".pdf", true)) file.delete() }
        } catch (error: Exception) { result(JSONObject().put("error", error.message ?: "Не удалось прочитать файл")) }
    }
    private fun scanPdf(file: File, name: String, result: (JSONObject) -> Unit) {
        val renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        val pages = minOf(renderer.pageCount, 10)
        require(pages > 0) { "PDF пуст" }
        val collected = StringBuilder()
        fun next(index: Int) {
            if (index >= pages) {
                val total = renderer.pageCount
                renderer.close(); file.delete()
                text = collected.toString().take(12000)
                result(JSONObject().put("name", name).put("text", text).put("pagesScanned", pages).put("totalPages", total)); return
            }
            val page = renderer.openPage(index)
            val scale = 1600.0 / maxOf(page.width, page.height)
            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close()
            JarvisVision(context).fromCameraThumbnail(bitmap) { scan ->
                bitmap.recycle(); collected.append("Страница ${index + 1}:\n$scan\n"); next(index + 1)
            }
        }
        next(0)
    }
}
