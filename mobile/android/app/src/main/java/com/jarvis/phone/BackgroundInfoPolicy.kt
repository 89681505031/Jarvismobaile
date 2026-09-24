package com.jarvis.phone

import java.util.Locale

/** Small, auditable set of information requests allowed in minimized mode. */
object BackgroundInfoPolicy {
    enum class Kind { TIME, DATE, MAIN_NEWS }

    fun classify(raw: String): Kind? {
        val text = raw.trim()
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return when {
            text in setOf(
                "время", "скажи время", "сколько времени", "сколько сейчас времени",
                "который час", "который сейчас час", "скажи который час"
            ) -> Kind.TIME

            text in setOf(
                "дата", "скажи дату", "какая дата", "какая сегодня дата",
                "какое сегодня число", "сегодняшняя дата"
            ) -> Kind.DATE

            text in setOf(
                "новости", "главные новости", "последние новости",
                "сводка новостей", "главная сводка новостей",
                "главные сводки новостей", "расскажи новости",
                "что нового в новостях"
            ) -> Kind.MAIN_NEWS

            else -> null
        }
    }
}
