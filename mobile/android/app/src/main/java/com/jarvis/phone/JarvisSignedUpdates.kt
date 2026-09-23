package com.jarvis.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Explicit HTTPS GitHub Release update check, fail-closed if no signed APK
 * matches the currently installed package, signing certificate, and version.
 * A permanent CI signing key + a published signed Release are prerequisites.
 */
class JarvisSignedUpdates(private val context: Context, private val report: (String) -> Unit) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var busy = false

    fun check() {
        if (busy) { report("Проверка обновлений уже выполняется."); return }
        busy = true
        report("Проверяю подписанные GitHub Releases…")
        io.execute {
            try {
                val url = URL("https://api.github.com/repos/89681505031/Jarvismobaile/releases/latest")
                val response = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 9000; readTimeout = 9000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Jarvis-Mobile-UpdateChecker")
                }
                val data = try {
                    val code = response.responseCode
                    if (code == 404) {
                        announce("Подписанный GitHub Release ещё не опубликован. Доступна отдельная тестовая сборка.")
                        return@execute
                    }
                    if (code != 200) error("GitHub HTTP $code")
                    response.inputStream.bufferedReader().use { it.readText().take(512_000) }
                } finally { response.disconnect() }
                val release = JSONObject(data)
                val assets = release.optJSONArray("assets") ?: error("В релизе отсутствуют APK.")
                var assetUrl: String? = null
                val expectedName = if (context.packageName.endsWith(".plus"))
                    "Jarvis-Mobile-PLUS-signed.apk" else "Jarvis-Mobile-signed.apk"
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") == expectedName) {
                        assetUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (assetUrl.isNullOrBlank()) {
                    announce("Для этой версии пока нет подписанного APK в GitHub Release.")
                    return@execute
                }
                val parsed = URL(assetUrl)
                if (parsed.protocol != "https" || parsed.host != "github.com")
                    error("Неподходящий источник обновления")
                announce("Скачиваю подписанный APK для проверки…")
                val file = File(context.cacheDir, "jarvis-verified-update.apk")
                try {
                    val connection = parsed.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = true
                    try {
                        if (connection.responseCode != 200 || connection.url.protocol != "https") error("HTTPS-загрузка не удалась")
                        val size = connection.contentLengthLong
                        if (size > 100L * 1024 * 1024) error("APK слишком большой")
                        connection.inputStream.use { input ->
                            file.outputStream().use { output ->
                                val buf = ByteArray(32 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > 100L * 1024 * 1024) error("APK слишком большой")
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                    } finally { connection.disconnect() }
                    val pm = context.packageManager
                    @Suppress("DEPRECATION")
                    val signingFlags = if (android.os.Build.VERSION.SDK_INT >= 28)
                        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                    @Suppress("DEPRECATION")
                    val installed = pm.getPackageInfo(context.packageName, signingFlags)
                    @Suppress("DEPRECATION")
                    val candidate = pm.getPackageArchiveInfo(file.path, signingFlags)
                        ?: error("APK повреждён")
                    if (candidate.packageName != context.packageName) error("Другая версия приложения")
                    @Suppress("DEPRECATION")
                    val oldSigners = if (android.os.Build.VERSION.SDK_INT >= 28)
                        installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                    else installed.signatures?.map { it.toCharsString() }?.toSet()
                    @Suppress("DEPRECATION")
                    val newSigners = if (android.os.Build.VERSION.SDK_INT >= 28)
                        candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                    else candidate.signatures?.map { it.toCharsString() }?.toSet()
                    if (oldSigners.isNullOrEmpty() || oldSigners != newSigners)
                        error("Подпись APK отличается от установленной. Обновление заблокировано.")
                    @Suppress("DEPRECATION")
                    val current = installed.longVersionCode
                    @Suppress("DEPRECATION")
                    if (candidate.longVersionCode <= current) {
                        announce("Уже установлена актуальная версия.")
                        return@execute
                    }
                    val content = FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file
                    )
                    main.post {
                        report("APK проверен. Android попросит подтвердить установку.")
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(content, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        try { context.startActivity(intent) }
                        catch (_: Exception) { report("Разрешите установку обновлений для JARVIS в Android.") }
                    }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            } catch (e: Exception) {
                announce("Обновление не установлено: ${e.message ?: "ошибка проверки"}.")
            } finally {
                busy = false
            }
        }
    }

    private fun announce(value: String) = main.post { report(value) }
}
