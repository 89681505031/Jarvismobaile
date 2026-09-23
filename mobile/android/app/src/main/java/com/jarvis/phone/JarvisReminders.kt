package com.jarvis.phone

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** On-device, inexact reminders. Never promise second-accurate Android alarms. */
class JarvisReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(text: String, minutes: Int): String {
        val label = text.trim().take(180)
        if (minutes !in 1..10080 || label.isBlank()) return "Укажите текст и время от 1 минуты до 7 дней."
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return "Разрешите уведомления JARVIS в настройках Android, чтобы получать напоминания."
        val id = prefs.getInt("next_id", 1000).coerceAtLeast(1000) + 1
        val at = System.currentTimeMillis() + minutes * 60_000L
        val event = JSONObject().put("id", id).put("text", label).put("at", at)
        val list = JSONArray(prefs.getString("items", "[]"))
        list.put(event)
        prefs.edit().putInt("next_id", id).putString("items", list.toString()).apply()
        arm(id, label, at)
        return "Напомню через $minutes мин.: $label. Android может немного задержать уведомление."
    }

    fun list(): String {
        val now = System.currentTimeMillis()
        val list = JSONArray(prefs.getString("items", "[]"))
        val records = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optLong("at") > now }.sortedBy { it.optLong("at") }
        return if (records.isEmpty()) "Напоминаний нет."
            else records.take(20).joinToString("\n") {
                val date = java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU"))
                    .format(java.util.Date(it.optLong("at")))
                "#${it.optInt("id")} — $date: ${it.optString("text")}"
            }
    }

    fun cancel(id: Int): String {
        val list = JSONArray(prefs.getString("items", "[]"))
        val kept = JSONArray()
        var found = false
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optInt("id") == id) found = true else kept.put(item)
        }
        if (!found) return "Напоминание с таким номером не найдено."
        prefs.edit().putString("items", kept.toString()).apply()
        alarms.cancel(pending(id, "", PendingIntent.FLAG_NO_CREATE))
        return "Напоминание #$id отменено."
    }

    fun completed(id: Int) {
        val list = JSONArray(prefs.getString("items", "[]"))
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (item.optInt("id") != id) kept.put(item)
        }
        prefs.edit().putString("items", kept.toString()).apply()
    }

    fun restoreAfterBoot() {
        val list = JSONArray(prefs.getString("items", "[]"))
        val now = System.currentTimeMillis()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val at = item.optLong("at")
            if (at > now) arm(item.optInt("id"), item.optString("text"), at)
        }
    }

    private fun pending(id: Int, label: String, flag: Int): PendingIntent? {
        val intent = Intent(context, JarvisReminderReceiver::class.java)
            .putExtra("id", id).putExtra("text", label)
        return PendingIntent.getBroadcast(
            context, id, intent, flag or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun arm(id: Int, label: String, at: Long) {
        val p = pending(id, label, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }

    companion object {
        private val regex = Regex(
            "^напомни(?: мне)? через (\\d{1,5}) (минуту|минуты|минут|мин|час|часа|часов|день|дня|дней)\\s*(.*)$",
            RegexOption.IGNORE_CASE
        )

        fun parseRussian(raw: String): Pair<Int, String>? {
            val m = regex.matchEntire(raw.trim()) ?: return null
            val n = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val minutes = when {
                unit.startsWith("час") -> n * 60L
                unit.startsWith("д") -> n * 1440L
                else -> n.toLong()
            }
            if (minutes !in 1..10080) return null
            val note = m.groupValues[3].trim().trimStart(',', '.', ':', ' ')
            return minutes.toInt() to note.ifBlank { "Напоминание" }
        }
    }
}
