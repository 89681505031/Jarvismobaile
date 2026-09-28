package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small on-device task list. It does not require calendar permissions or cloud sync. */
class JarvisTasks(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE)
    private val lock = Any()

    fun add(title: String): String = synchronized(lock) {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(300)
        if (clean.isBlank()) return "Укажите название задачи."
        val items = read()
        val id = prefs.getInt("next_id", 1).coerceAtLeast(1)
        items.put(JSONObject().apply {
            put("id", id)
            put("title", clean)
            put("createdAt", System.currentTimeMillis())
            put("done", false)
        })
        save(items)
        prefs.edit().putInt("next_id", id + 1).apply()
        "Задача #$id добавлена: $clean"
    }

    fun list(): String = synchronized(lock) {
        val items = read()
        val active = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { !it.optBoolean("done") }
        if (active.isEmpty()) return "Активных задач нет."
        buildString {
            append("Активные задачи:\n")
            active.take(30).forEach {
                append("#").append(it.optInt("id")).append(" — ")
                    .append(it.optString("title")).append("\n")
            }
        }.trim()
    }

    fun complete(id: Int): String = synchronized(lock) {
        val items = read()
        var found = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("id") == id) {
                found = true
                item.put("done", true)
                item.put("completedAt", System.currentTimeMillis())
                break
            }
        }
        if (!found) return "Задача #$id не найдена."
        save(items)
        "Задача #$id отмечена выполненной."
    }

    fun delete(id: Int): String = synchronized(lock) {
        val items = read()
        val next = JSONArray()
        var found = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optInt("id") == id) found = true else next.put(item)
        }
        if (!found) return "Задача #$id не найдена."
        save(next)
        "Задача #$id удалена."
    }

    fun summaryForBriefing(limit: Int = 5): String = synchronized(lock) {
        val items = read()
        val active = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { !it.optBoolean("done") }
            .take(limit.coerceIn(1, 10))
        if (active.isEmpty()) "Задач на сегодня не записано."
        else "Задачи: " + active.joinToString("; ") { "#${it.optInt("id")} ${it.optString("title")}" }
    }

    private fun read(): JSONArray = try {
        JSONArray(prefs.getString("items", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun save(items: JSONArray) {
        prefs.edit().putString("items", items.toString()).apply()
    }
}
