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

    fun ask(text: String, persona: String = "J.A.R.V.I.S.", allowNeural: Boolean = true): Result {
        val clean = text.trim().take(1_000)
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

        conversationAnswer(normalized)?.let {
            return Result(true, it, Source.CONVERSATION)
        }

        if (allowNeural && languageModel.isReady()) {
            val generation = languageModel.generate(buildNeuralPrompt(clean, persona))
            if (generation.success && generation.text.isNotBlank()) {
                return Result(true, generation.text.trim(), Source.NEURAL_MODEL)
            }
            if (generation.text.isNotBlank()) {
                return Result(false, generation.text.trim(), Source.LOCAL_FALLBACK)
            }
        }

        return Result(
            success = false,
            text = if (allowNeural) {
                "Сэр, этот вопрос требует языковой модели."
            } else {
                "Локальный резерв не может надёжно ответить на этот вопрос без GigaChat."
            },
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

        // Current user intent must always fit into the smallest Instant prompt.
        // Memory is secondary context and must never push the live question out.
        val currentQuestion = query.trim().take(520)
        val facts = memory.approvedBrainFacts(query).take(220)
        val relevantTurn = memory.relevantDialogues(query, 1).firstOrNull()
        val turnText = relevantTurn?.first?.let { user ->
            "Ранее пользователь: ${user.take(130)}"
        }.orEmpty()

        return buildString {
            append("Ты локальный JARVIS BRAIN на телефоне. ")
            append(role).append("\n")
            append("Ответь именно на ТЕКУЩИЙ вопрос. Не повторяй вопрос пользователя и не отвечай на старые реплики. ")
            append("Если память не относится к вопросу — игнорируй её. Отвечай по-русски кратко и по существу.\n\n")
            append("ТЕКУЩИЙ ВОПРОС:\n").append(currentQuestion).append("\n")
            if (facts.isNotBlank()) {
                append("\nДополнительная память, только если полезна:\n").append(facts).append("\n")
            }
            if (turnText.isNotBlank()) {
                append("\nОдин релевантный прошлый эпизод, только как справка:\n").append(turnText).append("\n")
            }
            append("\nОТВЕТ JARVIS:")
        }
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

        topicMemoryQuery(normalized)?.let { topic ->
            val turns = memory.relevantDialogues(topic, 5)
            if (turns.isEmpty()) {
                return "Я не нашёл в долговременной памяти разговоров по теме «$topic»."
            }
            val snippets = turns.mapIndexed { index, turn ->
                val user = turn.first.replace(Regex("\\s+"), " ").take(220)
                val assistant = turn.second.replace(Regex("\\s+"), " ").take(260)
                "${index + 1}. Вы: $user — JARVIS: $assistant"
            }
            return "По теме «$topic» я помню:\n" + snippets.joinToString("\n")
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

    private fun topicMemoryQuery(normalized: String): String? {
        val prefixes = listOf(
            "что мы говорили про ",
            "что мы обсуждали про ",
            "о чем мы говорили про ",
            "о чем мы говорили насчет ",
            "о чём мы говорили про ",
            "о чём мы говорили насчёт ",
            "что я говорил про ",
            "что я рассказывал про ",
            "что ты помнишь про ",
            "вспомни что мы говорили про "
        )
        val prefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return null
        return normalized.removePrefix(prefix)
            .trim()
            .trim('?', '.', '!', ',', ':', ';')
            .takeIf { it.length >= 2 }
            ?.take(180)
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

    private fun conversationAnswer(normalized: String): String? {
        val phrase = normalized.trim().trim('?', '!', '.', ',', ':', ';')
        return when {
            phrase in setOf("привет", "здравствуй", "здравствуйте", "добрый день", "добрый вечер") ->
                "Здравствуйте, сэр. JARVIS BRAIN на связи."

            phrase in setOf(
                "как дела", "как ты", "как твои дела", "как поживаешь", "как поживаете",
                "как дела джарвис", "джарвис как дела"
            ) ->
                "Всё в порядке, сэр. Я на связи и готов к вашим вопросам."

            phrase in setOf("что делаешь", "чем занимаешься", "что сейчас делаешь") ->
                "Жду вашу команду, сэр."

            phrase in setOf("спасибо", "благодарю", "спасибо джарвис") ->
                "Всегда к вашим услугам, сэр."

            phrase.contains("что ты умеешь") ->
                "Я умею выполнять команды телефона, использовать локальную память, вспоминать прошлые диалоги " +
                    "и отвечать через локальную языковую модель без внешнего ИИ."

            else -> null
        }
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
