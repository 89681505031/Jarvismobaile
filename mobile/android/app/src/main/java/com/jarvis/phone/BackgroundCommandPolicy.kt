package com.jarvis.phone

import java.util.Locale

/**
 * Native actions allowed while the Activity is minimized.
 *
 * These commands do not open a new Activity, place a call, read private data or
 * request a new Android permission. Everything else that changes the visible UI
 * still requires opening JARVIS through the ongoing notification.
 */
object BackgroundCommandPolicy {
    fun normalize(phrase: String): String =
        phrase.trim().lowercase(Locale("ru", "RU")).replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun permitted(phrase: String): Boolean {
        val text = normalize(phrase)

        // Flashlight.
        if (
            (text.contains("фонар") || text == "включи свет" || text == "выключи свет") &&
            (text.contains("включ") || text.contains("выключ") || text.contains("зажг") || text.contains("погас"))
        ) return true

        // Media volume and mute.
        if (
            text.contains("громк") || text.contains("тише") ||
            text == "выключи звук" || text == "включи звук" ||
            text == "убери звук" || text == "верни звук"
        ) return true

        // Generic play/resume only. Named tracks still need a visible music app.
        if (text in setOf(
                "включи музыку", "продолжи музыку", "воспроизведи музыку",
                "играй музыку", "продолжай музыку", "возобнови музыку"
            )
        ) return true

        if (
            text == "пауза" || text == "стоп" || text == "стоп музыка" ||
            text == "останови музыку" || text == "поставь музыку на паузу" ||
            text == "останови воспроизведение"
        ) return true

        if (
            text == "следующая песня" || text == "следующий трек" ||
            text == "следующая" || text == "дальше" ||
            text == "переключи трек" || text == "переключи песню"
        ) return true

        if (
            text == "предыдущая песня" || text == "предыдущий трек" ||
            text == "предыдущая" || text == "назад песню" ||
            text == "верни предыдущий трек"
        ) return true

        // Accessibility global navigation does not launch arbitrary content.
        if (
            text == "назад" || text == "вернись назад" || text == "перейди назад" ||
            text == "домой" || text == "главный экран" || text == "на главный экран" ||
            text == "последние приложения" || text == "покажи последние приложения" ||
            text == "открой последние приложения"
        ) return true

        return false
    }

    fun requiresVisibleUi(phrase: String): Boolean {
        val text = normalize(phrase)
        return text.startsWith("открой ") ||
            text.startsWith("позвони ") ||
            text.startsWith("найди в интернете") ||
            text.startsWith("включи песню ") ||
            (text.startsWith("включи музыку ") && text != "включи музыку") ||
            text.contains("кто звонил") ||
            text.contains("пропущенные вызовы") ||
            text.contains("прочитай сообщение") ||
            text.contains("прочитай последнее сообщение") ||
            text.contains("whatsapp") ||
            text.contains("ватсап")
    }
}
