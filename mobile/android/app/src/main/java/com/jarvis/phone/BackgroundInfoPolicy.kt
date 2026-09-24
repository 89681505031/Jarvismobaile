package com.jarvis.phone

import java.util.Locale

/** Small, auditable set of information requests allowed in minimized mode. */
object BackgroundInfoPolicy {
    enum class Kind { TIME, DATE, MAIN_NEWS }

    fun normalize(raw: String): String =
        raw.trim()
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun classify(raw: String): Kind? {
        val text = normalize(raw)

        if (
            text == "время" ||
            text == "который час" ||
            text == "который сейчас час" ||
            (text.contains("котор") && text.contains("час")) ||
            (text.contains("врем") && (
                text.contains("сколько") ||
                text.contains("скажи") ||
                text.contains("сейчас") ||
                text.contains("точн")
            ))
        ) return Kind.TIME

        if (
            text == "дата" ||
            text == "сегодняшняя дата" ||
            (text.contains("дат") && (text.contains("какая") || text.contains("скажи") || text.contains("сегодня"))) ||
            (text.contains("число") && text.contains("сегодня"))
        ) return Kind.DATE

        // Local/place-based news stays foreground-only because location is
        // intentionally not available to the minimized microphone service.
        val localNews = text.contains("местн") ||
            text.contains("рядом") ||
            text.contains("моем городе") ||
            text.contains("моём городе") ||
            text.contains("по месту")

        // Any ordinary request containing "новост..." means the fresh main
        // news feed unless the user clearly asked for location-based news.
        // This intentionally covers natural ASR phrases such as:
        // "какие новости", "что в новостях", "новости на сегодня".
        if (!localNews && text.contains("новост")) return Kind.MAIN_NEWS

        if (!localNews && (
                text == "что нового в мире" ||
                text == "что нового сегодня" ||
                text == "что происходит в мире"
            )
        ) return Kind.MAIN_NEWS

        return null
    }
}
