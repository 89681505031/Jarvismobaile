package com.jarvis.phone

import java.util.Locale

/**
 * JARVIS BRAIN 0.1
 *
 * First-party on-device reasoning layer. It deliberately has no network client
 * and no API keys. The engine combines deterministic knowledge with relevant
 * long-term memories. A local neural generator can be plugged in later without
 * changing the rest of the Android app.
 */
class JarvisBrainEngine(
    private val memory: JarvisMemory,
    private val languageModel: JarvisLanguageModel = UnavailableJarvisLanguageModel
) {
    data class Result(
        val success: Boolean,
        val text: String,
        val source: Source
    )

    enum class Source {
        OFFLINE_KNOWLEDGE,
        MEMORY,
        IDENTITY,
        CONVERSATION,
        NEURAL_MODEL,
        LOCAL_FALLBACK
    }

    fun ask(text: String, persona: String = "J.A.R.V.I.S."): Result {
        val clean = text.trim().take(12_000)
        if (clean.isBlank()) {
            return Result(false, "Я не расслышал вопрос.", Source.LOCAL_FALLBACK)
        }

        OfflineKnowledge.answer(clean)?.let {
            return Result(true, it, Source.OFFLINE_KNOWLEDGE)
        }

        val normalized = normalize(clean)

        identityAnswer(normalized, persona)?.let {
            return Result(true, it, Source.IDENTITY)
        }

        memoryIntentAnswer(normalized)?.let {
            return Result(true, it, Source.MEMORY)
        }

        relevantMemoryAnswer(clean)?.let {
            return Result(true, it, Source.MEMORY)
        }

        conversationAnswer(normalized)?.let {
            return Result(true, it, Source.CONVERSATION)
        }

        if (languageModel.isReady()) {
            val generation = languageModel.generate(buildNeuralPrompt(clean, persona))
            if (generation.success && generation.text.isNotBlank()) {
                return Result(true, generation.text.trim(), Source.NEURAL_MODEL)
            }
        }

        return Result(
            success = false,
            text = "Сэр, этот вопрос уже требует локальной языковой модели. " +
                "JARVIS BRAIN работает без GigaChat и без внешнего ИИ, но нейросетевой генератор ещё не установлен.",
            source = Source.LOCAL_FALLBACK
        )
    }

    fun neuralModelReady(): Boolean = languageModel.isReady()

    fun neuralModelLabel(): String = languageModel.modelLabel()

    private fun buildNeuralPrompt(query: String, persona: String): String {
        val role = when (persona) {
            "Astra" -> "Астра: творческий, находчивый помощник."
            "Luna" -> "Луна: аналитичный и аккуратный помощник."
            "Terra" -> "Терра: практичный повседневный помощник."
            "Кибер", "Cyber" -> "Кибер: технический помощник."
            else -> "J.A.R.V.I.S.: универсальный персональный помощник."
        }
        val facts = memory.approvedBrainFacts(query).take(8000)
        val selectedTurns = LinkedHashMap<String, Pair<String, String>>()
        memory.relevantDialogues(query, 6).forEach { turn ->
            selectedTurns[turn.first + "\u0000" + turn.second] = turn
        }
        memory.recentDialogues().takeLast(4).forEach { turn ->
            selectedTurns[turn.first + "\u0000" + turn.second] = turn
        }
        val turns = selectedTurns.values.takeLast(8)
            .joinToString("\n") { (user, assistant) ->
                "Пользователь: ${user.take(900)}\nJARVIS: ${assistant.take(1200)}"
            }

        return buildString {
            append("Ты работаешь внутри JARVIS BRAIN полностью локально на телефоне.\n")
            append(role).append("\n")
            append("Отвечай по-русски естественно и по существу. Не выдумывай действия телефона, ")
            append("если их не выполнил отдельный модуль команд.\n")
            if (facts.isNotBlank()) {
                append("\nЛокальная долговременная память:\n").append(facts).append("\n")
            }
            if (turns.isNotBlank()) {
                append("\nРелевантные и недавние эпизоды памяти:\n").append(turns).append("\n")
            }
            append("\nТекущий вопрос пользователя:\n").append(query)
            append("\n\nОтвет JARVIS:")
        }.take(18_000)
    }

    private fun identityAnswer(normalized: String, persona: String): String? {
        if (
            normalized == "кто ты" ||
            normalized.contains("как тебя зовут") ||
            normalized.contains("ты кто")
        ) {
            return when (persona) {
                "Astra" -> "Я Астра, локальный помощник системы JARVIS."
                "Luna" -> "Я Луна, локальный аналитический помощник системы JARVIS."
                "Terra" -> "Я Терра, локальный практический помощник системы JARVIS."
                "Кибер", "Cyber" -> "Я Кибер, локальный технический помощник системы JARVIS."
                else -> "Я J.A.R.V.I.S. Мой мозг JARVIS BRAIN работает локально на этом устройстве."
            }
        }

        if (
            normalized.contains("кто тебя создал") ||
            normalized.contains("кто твой создатель")
        ) {
            return "Этот JARVIS развивается как собственный проект пользователя устройства."
        }

        return null
    }

    private fun memoryIntentAnswer(normalized: String): String? {
        if (
            normalized.contains("как меня зовут") ||
            normalized == "мое имя" ||
            normalized == "моё имя"
        ) {
            val name = memory.getUserName().trim()
            return if (name.isNotBlank()) "Вас зовут $name." else "Я пока не сохранил ваше имя."
        }

        if (
            normalized.contains("что ты помнишь обо мне") ||
            normalized.contains("что ты знаешь обо мне") ||
            normalized.contains("покажи мою память")
        ) {
            val facts = memory.factsSummary().trim()
            val habits = memory.habitsSummary().trim()
            val body = listOf(facts, habits).filter { it.isNotBlank() }.joinToString("\n")
            return if (body.isBlank()) {
                "В долговременной памяти пока нет сохранённых сведений о вас."
            } else {
                "Вот что хранится в моей локальной памяти:\n$body"
            }
        }

        if (
            normalized.contains("что мы обсуждали") ||
            normalized.contains("о чем мы говорили") ||
            normalized.contains("о чём мы говорили") ||
            normalized.contains("последний разговор")
        ) {
            val turns = memory.recentDialogues().takeLast(4)
            if (turns.isEmpty()) return "Локальная история разговоров пока пуста."
            val topics = turns.mapIndexed { index, turn ->
                "${index + 1}. ${turn.first.replace(Regex("\\s+"), " ").take(180)}"
            }
            return "Последние темы, которые я помню:\n" + topics.joinToString("\n")
        }

        return null
    }

    private fun relevantMemoryAnswer(query: String): String? {
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return null

        val facts = memory.approvedBrainFacts(query)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("Сохранённый факт:") }
            .map { it.substringAfter(':').trim() }
            .filter { it.isNotBlank() }
            .toList()

        val best = facts
            .map { fact -> fact to overlapScore(queryTokens, tokens(fact)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?: return null

        // One accidental common word should not turn every question into a
        // memory lookup. For very short questions one overlap is sufficient;
        // longer questions require at least two matching terms.
        val minimum = if (queryTokens.size <= 2) 1 else 2
        if (best.second < minimum) return null

        return "Я помню: ${best.first}"
    }

    private fun conversationAnswer(normalized: String): String? = when {
        normalized in setOf("привет", "здравствуй", "здравствуйте", "добрый день", "добрый вечер") ->
            "Здравствуйте, сэр. JARVIS BRAIN на связи."

        normalized in setOf("спасибо", "благодарю", "спасибо джарвис") ->
            "Всегда к вашим услугам, сэр."

        normalized.contains("что ты умеешь") ->
            "Сейчас я умею выполнять команды телефона, использовать локальную память, " +
                "вспоминать прошлые диалоги и отвечать на часть вопросов без интернета. " +
                "Следующий этап — локальная языковая модель для свободного диалога."

        else -> null
    }

    private fun overlapScore(a: Set<String>, b: Set<String>): Int =
        a.count { it in b }

    private fun tokens(text: String): Set<String> =
        normalize(text)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .asSequence()
            .filter { it.length >= 3 }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun normalize(text: String): String =
        text.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private val STOP_WORDS = setOf(
            "что", "как", "это", "этот", "эта", "эти", "мне", "меня", "про", "для",
            "или", "так", "там", "тут", "где", "когда", "какой", "какая", "какие",
            "сейчас", "тебя", "твой", "твоя", "твои", "наш", "наша", "наши"
        )
    }
}
