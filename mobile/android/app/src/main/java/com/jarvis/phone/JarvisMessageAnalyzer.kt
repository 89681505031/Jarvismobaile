package com.jarvis.phone

import java.util.Locale

/**
 * Small privacy-first local message interpreter used until the neural language
 * model is installed. It never performs network requests.
 */
object JarvisMessageAnalyzer {

    fun summarize(senderOrChat: String, message: String, visibleScreenText: String = ""): String {
        val sender = senderOrChat.trim().replace(Regex("\\s+"), " ").take(160)
        val cleanMessage = message.trim().replace(Regex("\\s+"), " ").take(1200)

        if (sender.isNotBlank() || cleanMessage.isNotBlank()) {
            val who = if (sender.isNotBlank()) "от $sender" else "в WhatsApp"
            if (cleanMessage.isBlank()) {
                return "Последнее сообщение $who есть, но текст уведомления не виден."
            }

            val intent = detectIntent(cleanMessage)
            return buildString {
                append("Последнее сообщение ").append(who).append(": ")
                append(cleanMessage)
                if (intent.isNotBlank()) {
                    append(". ").append(intent)
                }
            }
        }

        val screen = visibleScreenText.trim()
            .replace(Regex("\\s+"), " ")
            .takeLast(1400)

        if (screen.isNotBlank()) {
            return "На открытом экране WhatsApp вижу текст: $screen"
        }

        return "Не удалось получить последнее сообщение WhatsApp. Проверьте доступ к уведомлениям или откройте нужный чат."
    }

    private fun detectIntent(message: String): String {
        val n = message.lowercase(Locale("ru", "RU")).replace('ё', 'е')
        val requestWords = listOf(
            "можешь", "можете", "сделай", "сделайте", "пришли", "пришлите",
            "отправь", "отправьте", "позвони", "позвоните", "напиши", "напишите",
            "нужно", "надо", "пожалуйста"
        )
        val questionWords = listOf(
            "когда", "где", "кто", "что", "зачем", "почему", "сколько", "какой", "какая", "какие"
        )

        return when {
            requestWords.any { word -> Regex("(^|\\W)${Regex.escape(word)}(\\W|$)").containsMatchIn(n) } ->
                "Похоже, это просьба или действие, которое от вас ожидают."
            message.contains("?") || questionWords.any { n.startsWith("$it ") } ->
                "Похоже, вам задают вопрос."
            else ->
                "Похоже, это информационное сообщение."
        }
    }
}
