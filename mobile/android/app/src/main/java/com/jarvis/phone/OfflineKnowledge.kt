package com.jarvis.phone

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OfflineKnowledge {
    fun answer(text: String, now: Long = System.currentTimeMillis()): String? {
        val normalized = text.trim()
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .trimEnd('?', '.', '!', ',', ';', ':')
            .replace(Regex("\\s+"), " ")
            .trim()

        val asksTime =
            normalized == "время" ||
            normalized == "который час" ||
            normalized == "который сейчас час" ||
            (normalized.contains("врем") && (
                normalized.contains("сколько") ||
                normalized.contains("скажи") ||
                normalized.contains("сейчас") ||
                normalized.contains("точн")
            ))

        if (asksTime) {
            return "Сейчас " + SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date(now))
        }

        val asksDate =
            normalized == "дата" ||
            normalized == "сегодняшняя дата" ||
            (normalized.contains("дат") && (
                normalized.contains("какая") ||
                normalized.contains("скажи") ||
                normalized.contains("сегодня")
            )) ||
            (normalized.contains("число") && normalized.contains("сегодня"))

        if (asksDate) {
            return "Сегодня " + SimpleDateFormat("d MMMM yyyy", Locale("ru", "RU")).format(Date(now))
        }

        return when (normalized) {
            "что умеешь без интернета" ->
                "Без интернета доступны время, дата, фонарик, громкость, напоминания и управление музыкой."
            else -> null
        }
    }
}
