package com.jarvis.phone

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Manual, opt-in Home Assistant control restricted to one configured light entity. */
class JarvisHomeAssistant(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_home", Context.MODE_PRIVATE)
    fun configure(rawUrl: String, token: String, entityId: String): String {
        val base = rawUrl.trim().trimEnd('/')
        val url = try { URL(base) } catch (_: Exception) { return "Укажите правильный HTTPS-адрес Home Assistant." }
        if (url.protocol != "https" || url.host.isNullOrBlank() || url.userInfo != null ||
            url.query != null || url.ref != null || url.path !in listOf("", "/"))
            return "Поддерживается только HTTPS-адрес сервера без пути и параметров."
        if (!Regex("^light\\.[a-z0-9_]{1,64}$").matches(entityId))
            return "Для начала поддерживается только идентификатор light.имя_светильника."
        if (token.trim().length < 16) return "Укажите долгосрочный токен Home Assistant."
        prefs.edit().putString("base", base).putString("token", token.trim())
            .putString("entity", entityId).apply()
        return "Подключён выбранный светильник $entityId. Другие устройства не контролируются."
    }
    fun configured() = prefs.getString("base", null) != null &&
        prefs.getString("token", null) != null && prefs.getString("entity", null) != null
    fun entity(): String = prefs.getString("entity", "").orEmpty()
    fun clear() { prefs.edit().clear().apply() }
    fun setLight(on: Boolean): String {
        if (!configured()) return "Сначала подключите Home Assistant в настройках JARVIS."
        val base = prefs.getString("base", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        val id = entity()
        if (!Regex("^light\\.[a-z0-9_]{1,64}$").matches(id)) return "Неверный идентификатор света."
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$base/api/services/light/${if (on) "turn_on" else "turn_off"}")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(JSONObject().put("entity_id", id).toString().toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode in 200..299)
                if (on) "Включил выбранный свет." else "Выключил выбранный свет."
            else "Home Assistant вернул HTTP ${connection.responseCode}. Проверьте доступ."
        } catch (_: Exception) {
            "Не удалось связаться с Home Assistant по защищённому соединению."
        } finally { connection?.disconnect() }
    }
}
