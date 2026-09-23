package com.jarvis.phone

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OfflineKnowledge {
    fun answer(text: String, now: Long = System.currentTimeMillis()): String? {
        return when (text.trim().lowercase(Locale("ru", "RU")).trimEnd('?', '.', '!')) {
            "сколько времени", "который час", "скажи время" ->
                "Сейчас " + SimpleDateFormat("HH:mm", Locale("ru", "RU")).format(Date(now))
            "какое сегодня число", "какая сегодня дата", "скажи дату" ->
                "Сегодня " + SimpleDateFormat("d MMMM yyyy", Locale("ru", "RU")).format(Date(now))
            "что умеешь без интернета" ->
                "Без интернета доступны время, дата, фонарик, громкость, напоминания и управление музыкой."
            else -> null
        }
    }
}
