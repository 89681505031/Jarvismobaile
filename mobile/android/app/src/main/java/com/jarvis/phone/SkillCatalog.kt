package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Typed built-in skill switches, never downloads executable plugins. */
class SkillCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_skills", Context.MODE_PRIVATE)
    private val definitions = listOf(
        "offline" to "Офлайн: время и дата",
        "reminders" to "Локальные напоминания",
        "vision" to "Офлайн-зрение (снимки пользователя)",
        "home" to "Home Assistant: только выбранный свет",
        "overlay" to "Плавающая кнопка"
    )
    fun enabled(id: String) = definitions.any { it.first == id } && prefs.getBoolean(id, true)
    fun set(id: String, enabled: Boolean): Boolean {
        if (definitions.none { it.first == id }) return false
        prefs.edit().putBoolean(id, enabled).apply()
        return true
    }
    fun json(): String {
        val output = JSONArray()
        definitions.forEach { (id, title) ->
            output.put(JSONObject().put("id", id).put("title", title).put("enabled", enabled(id)))
        }
        return output.toString()
    }
}
