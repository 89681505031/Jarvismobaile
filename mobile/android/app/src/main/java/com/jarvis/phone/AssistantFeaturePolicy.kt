package com.jarvis.phone

import java.util.Locale

/** Pure command classification for assistant features that do not need Android APIs. */
object AssistantFeaturePolicy {
    enum class BriefingKind { MORNING, EVENING, DAILY }

    sealed class TaskAction {
        data class Add(val title: String) : TaskAction()
        data object ListTasks : TaskAction()
        data class Complete(val id: Int) : TaskAction()
        data class Delete(val id: Int) : TaskAction()
    }

    private fun normalize(raw: String): String =
        raw.trim()
            .lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun briefingKind(raw: String): BriefingKind? {
        val text = normalize(raw)
        return when {
            text in setOf("утренняя сводка", "утренний брифинг", "доброе утро джарвис", "доброе утро jarvis") ->
                BriefingKind.MORNING
            text in setOf("вечерняя сводка", "вечерний брифинг", "добрый вечер джарвис", "добрый вечер jarvis") ->
                BriefingKind.EVENING
            text in setOf("мой брифинг", "брифинг", "что у меня сегодня", "мой план на сегодня", "сводка дня") ->
                BriefingKind.DAILY
            else -> null
        }
    }

    fun taskAction(raw: String): TaskAction? {
        val text = normalize(raw)
        if (text in setOf("мои задачи", "список задач", "покажи задачи", "что у меня по задачам"))
            return TaskAction.ListTasks

        Regex("^(?:выполни|заверши|закрой) задачу\\s*#?(\\d+)$").find(text)?.let {
            return TaskAction.Complete(it.groupValues[1].toInt())
        }
        Regex("^(?:удали|убери) задачу\\s*#?(\\d+)$").find(text)?.let {
            return TaskAction.Delete(it.groupValues[1].toInt())
        }

        val prefixes = listOf("добавь задачу ", "создай задачу ", "новая задача ", "задача ")
        prefixes.firstOrNull { text.startsWith(it) }?.let { prefix ->
            val title = raw.trim()
                .replaceFirst(Regex("(?i)^" + Regex.escape(prefix).replace("е", "[её]")), "")
                .trim()
                .trimStart(':', '-', '—')
                .trim()
            if (title.isNotBlank()) return TaskAction.Add(title.take(300))
        }
        return null
    }

    fun asksNotificationBrief(raw: String): Boolean {
        val text = normalize(raw)
        return listOf(
            "сводка сообщений", "сводка уведомлений", "что мне писали",
            "кто мне писал", "есть что то важное", "есть что-то важное",
            "что нового в сообщениях", "разбери уведомления", "важные сообщения"
        ).any { text.contains(it) } || notificationPerson(raw) != null
    }

    fun notificationPerson(raw: String): String? {
        val text = normalize(raw)
        val patterns = listOf(
            Regex("^(?:покажи |прочитай )?сообщения от (.+)$"),
            Regex("^что писал (.+)$"),
            Regex("^что писала (.+)$"),
            Regex("^что написал (.+)$"),
            Regex("^что написала (.+)$")
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length >= 2 }?.take(80)
        }
    }

    fun asksVisionCamera(raw: String): Boolean {
        val text = normalize(raw)
        return text in setOf(
            "что передо мной", "что ты видишь", "посмотри что передо мной",
            "посмотри вокруг", "проанализируй камерой", "покажи что видишь"
        )
    }

    fun calendarDraftTitle(raw: String): String? {
        val text = raw.trim()
        val normalized = normalize(raw)
        val prefixes = listOf("добавь в календарь ", "создай событие ", "запланируй в календаре ")
        val prefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return null
        return text.substring(prefix.length).trim().trimStart(':', '-', '—').trim()
            .takeIf { it.isNotBlank() }?.take(200)
    }

    fun asksOpenCalendar(raw: String): Boolean {
        val text = normalize(raw)
        return text in setOf("открой календарь", "покажи календарь", "мой календарь")
    }
}
