package com.jarvis.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class JarvisMemory(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)
    private val lock = Any()
    private fun readArray(key: String): JSONArray = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun readObject(key: String): JSONObject = try { JSONObject(prefs.getString(key, "{}")) } catch (_: Exception) { JSONObject() }

    fun setUserName(name: String) {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isNotBlank()) prefs.edit().putString("user_name", clean).apply()
    }

    fun getUserName(): String = prefs.getString("user_name", "").orEmpty()

    fun setBirthDate(day: Int, month: Int, year: Int) {
        if (year !in 1900..2100) return
        try { java.time.LocalDate.of(year, month, day) } catch (_: Exception) { return }
        prefs.edit()
            .putInt("birth_day", day)
            .putInt("birth_month", month)
            .putInt("birth_year", year)
            .apply()
    }

    fun birthDateSummary(): String {
        val day = prefs.getInt("birth_day", 0)
        val month = prefs.getInt("birth_month", 0)
        val year = prefs.getInt("birth_year", 0)
        if (day == 0 || month == 0 || year == 0) return ""
        val months = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
        )
        val monthName = months.getOrNull(month - 1) ?: return ""
        return "$day $monthName $year года"
    }

    fun rememberTurn(userText: String, assistantText: String) {
        if (userText.isBlank() || assistantText.isBlank()) return
        synchronized(lock) {
            val old = readArray("dialogues")
            val next = JSONArray()
            val start = maxOf(0, old.length() - 19)
            for (i in start until old.length()) next.put(old.opt(i))
            next.put(JSONObject().apply {
                put("user", userText.trim().take(4000))
                put("assistant", assistantText.trim().take(8000))
                put("time", System.currentTimeMillis())
            })
            prefs.edit().putString("dialogues", next.toString()).apply()
        }
    }

    fun recentDialogues(): List<Pair<String, String>> = synchronized(lock) {
        val array = readArray("dialogues")
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val user = item.optString("user").trim()
            val assistant = item.optString("assistant").trim()
            if (user.isBlank() || assistant.isBlank()) null else user to assistant
        }
    }

    /**
     * Optional context sent to GigaChat ONLY when the user enables the memory
     * sharing switch. Excludes birthday, habits, contact data and raw OS events.
     * Chat turns are provided separately as bounded role-labelled messages.
     */
    fun approvedBrainFacts(query: String = ""): String = synchronized(lock) {
        val facts = readArray("facts")
        val name = getUserName().take(120)
        val all = (0 until facts.length())
            .map { facts.optString(it).trim() }
            .filter { it.isNotBlank() }

        // Keep every fact on-device. For each GigaChat request select the most
        // relevant older facts plus recent ones instead of silently forgetting
        // everything beyond an arbitrary 10/30 item window.
        val tokens = normalize(query)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .toSet()
        val relevant = if (tokens.isEmpty()) emptyList() else all.withIndex()
            .map { indexed ->
                val normalizedFact = normalize(indexed.value)
                val score = tokens.count { normalizedFact.contains(it) }
                Triple(indexed.value, score, indexed.index)
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }
                .thenByDescending { it.third })
            .take(40)
            .map { it.first }

        val selected = LinkedHashSet<String>()
        relevant.forEach { selected.add(it) }
        all.takeLast(40).forEach { selected.add(it) }

        buildString {
            if (name.isNotBlank()) append("Имя пользователя: ").append(name).append("\n")
            val birthDate = birthDateSummary()
            if (birthDate.isNotBlank()) append("Дата рождения пользователя: ").append(birthDate).append("\n")
            selected.forEach { fact ->
                append("Сохранённый факт: ").append(fact.take(700)).append("\n")
            }
        }.trim().take(12000)
    }

    fun clearChatHistory(): Int = synchronized(lock) {
        val count = readArray("dialogues").length()
        prefs.edit().remove("dialogues").apply()
        count
    }


    fun recordHabit(text: String) {
        val category = habitCategory(text)
        synchronized(lock) {
            val habits = readObject("habits")
            habits.put(category, habits.optInt(category, 0) + 1)
            val commands = readObject("habit_commands")
            val key = normalize(text).take(120)
            if (key.isNotBlank()) commands.put(key, commands.optInt(key, 0) + 1)

            val ranked = commands.keys().asSequence().toList().sortedByDescending { commands.optInt(it) }.take(100)
            val boundedCommands = JSONObject()
            ranked.forEach { boundedCommands.put(it, commands.optInt(it)) }
            val facts = readArray("facts")
            var limitedFacts = facts
            extractFacts(text).forEach { fact ->
                val next = JSONArray()
                for (i in 0 until limitedFacts.length()) {
                    val existing = limitedFacts.optString(i).trim()
                    if (existing.isNotBlank() && !existing.equals(fact, ignoreCase = true)) next.put(existing)
                }
                next.put(fact)
                limitedFacts = next
            }
            prefs.edit()
                .putString("habits", habits.toString())
                .putString("habit_commands", boundedCommands.toString())
                .putString("facts", limitedFacts.toString())
                .apply()
        }
    }

    fun rememberFact(text: String): Boolean = synchronized(lock) {
        val clean = text.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
        if (clean.length < 2 || containsSecret(clean)) return false
        val fact = if (clean.startsWith("Пользователь ", ignoreCase = true)) clean else "Пользователь: $clean"
        appendUniqueFact(fact)
        true
    }

    /**
     * Automatically remembers natural first-person self-disclosures such as
     * family, study, work, preferences, goals and biographical details.
     * Credentials, payment data and one-time security codes are deliberately
     * excluded even when they are phrased in the first person.
     */
    fun rememberSelfDisclosure(text: String): Boolean = synchronized(lock) {
        val clean = text.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?').take(700)
        if (clean.length < 3 || containsSecret(clean)) return false
        val lower = normalize(clean)
        val starters = listOf(
            "я ", "мне ", "меня ", "мой ", "моя ", "моё ", "мое ", "мои ",
            "у меня ", "мы ", "нам ", "наш ", "наша ", "наше ", "наши "
        )
        if (starters.none { lower.startsWith(it) }) return false
        if (lower.startsWith("меня зовут ") || lower.startsWith("моё имя ") || lower.startsWith("мое имя ")) {
            // Name has its own dedicated field; keeping the full statement too
            // makes it searchable together with all other personal facts.
        }
        appendUniqueFact("Пользователь рассказал: $clean")
        true
    }

    private fun appendUniqueFact(fact: String) {
        val facts = readArray("facts")
        val next = JSONArray()
        for (i in 0 until facts.length()) {
            val existing = facts.optString(i).trim()
            if (existing.isNotBlank() && !existing.equals(fact, ignoreCase = true)) next.put(existing)
        }
        next.put(fact)
        prefs.edit().putString("facts", next.toString()).apply()
    }

    private fun containsSecret(text: String): Boolean {
        val value = normalize(text)
        val secretMarkers = listOf(
            "парол", "пин-код", "pin-код", " cvv", " cvc", "api key",
            "authorization key", "токен", "одноразовый код", "код из смс",
            "номер карты", "секретный ключ"
        )
        return secretMarkers.any { value.contains(it) }
    }

    fun forgetFact(query: String): Boolean = synchronized(lock) {
        val needle = normalize(query).trim()
        if (needle.isBlank()) return false
        val facts = readArray("facts")
        val next = JSONArray()
        var removed = false
        for (i in 0 until facts.length()) {
            val existing = facts.optString(i).trim()
            if (!removed && normalize(existing).contains(needle)) {
                removed = true
            } else if (existing.isNotBlank()) {
                next.put(existing)
            }
        }
        if (removed) prefs.edit().putString("facts", next.toString()).apply()
        removed
    }

    fun forgetLastFact(): Boolean = synchronized(lock) {
        val facts = readArray("facts")
        if (facts.length() == 0) return false
        val next = JSONArray()
        for (i in 0 until facts.length() - 1) next.put(facts.optString(i))
        prefs.edit().putString("facts", next.toString()).apply()
        true
    }

    fun factsSummary(): String = synchronized(lock) {
        val facts = readArray("facts")
        val name = getUserName()
        val birthDate = birthDateSummary()
        if (facts.length() == 0 && name.isBlank() && birthDate.isBlank())
            return "Пока важных фактов обо мне не сохранено."
        buildString {
            append("Что JARVIS запомнил о пользователе:\n")
            if (name.isNotBlank()) append("• Имя: ").append(name).append("\n")
            if (birthDate.isNotBlank()) append("• Дата рождения: ").append(birthDate).append("\n")
            for (i in 0 until facts.length()) {
                val fact = facts.optString(i).trim()
                if (fact.isNotBlank()) append("• ").append(fact).append("\n")
            }
        }.trim()
    }

    fun habitsSummary(): String {
        val habits = readObject("habits")
        if (habits.length() == 0) return "Пока привычки использования не накоплены."
        val names = mapOf(
            "apps" to "открытие приложений",
            "web" to "поиск в интернете",
            "calls" to "звонки и вызовы",
            "settings" to "настройки телефона",
            "camera" to "камера",
            "messages" to "чтение сообщений",
            "ai" to "вопросы ИИ",
            "other" to "другие команды"
        )
        val parts = mutableListOf<String>()
        val keys = habits.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            parts += (names[key] ?: key) + ": " + habits.optInt(key, 0)
        }
        return parts.sortedByDescending { it.substringAfterLast(": ").toIntOrNull() ?: 0 }.joinToString(", ")
    }

    fun memoryContext(): String {
        val name = getUserName()
        val dialogues = recentDialogues().takeLast(8)
        val habits = habitsSummary()
        val commands = readObject("habit_commands")
        val commandKeys = commands.keys()
        val frequent = mutableListOf<Pair<String, Int>>()
        while (commandKeys.hasNext()) { val key = commandKeys.next(); frequent += Pair(key, commands.optInt(key, 0)) }
        val frequentText = frequent.sortedByDescending { pair -> pair.second }.take(5).joinToString(", ") { pair -> "«" + pair.first + "» (" + pair.second + " раз)" }
        val birthDate = birthDateSummary()
        return buildString {
            if (name.isNotBlank()) append("Имя пользователя: ").append(name).append("\n")
            if (birthDate.isNotBlank()) append("Дата рождения пользователя: ").append(birthDate).append("\n")
            append("Наблюдаемые привычки использования телефона: ").append(habits).append("\n")
            val facts = factsSummary()
            if (facts != "Пока важных фактов обо мне не сохранено.") append(facts).append("\n")
            if (frequentText.isNotBlank()) append("Частые команды пользователя: ").append(frequentText).append("\n")
            if (dialogues.isNotEmpty()) {
                append("Последние ").append(dialogues.size).append(" диалогов:\n")
                dialogues.forEachIndexed { index, pair ->
                    append(index + 1).append(". Пользователь: ").append(pair.first.take(1000))
                        .append(" | Ассистент: ").append(pair.second.take(2000)).append("\n")
                }
            }
        }.trim().take(16000)
    }


    private fun normalize(text: String): String =
        text.trim().lowercase(Locale("ru", "RU")).replace(Regex("\\s+"), " ")

    private fun extractFacts(text: String): List<String> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        val lower = clean.lowercase(Locale("ru", "RU"))
        val prefixes = listOf(
            "я люблю " to "Пользователь любит ",
            "мне нравится " to "Пользователю нравится ",
            "я предпочитаю " to "Пользователь предпочитает ",
            "я хочу " to "Пользователь хочет ",
            "я не люблю " to "Пользователь не любит ",
            "я работаю " to "Пользователь работает ",
            "я живу " to "Пользователь живёт "
        )
        for ((prefix, label) in prefixes) {
            if (lower.startsWith(prefix) && clean.length > prefix.length) {
                val value = clean.substring(prefix.length).trim().trimEnd('.', '!', '?')
                if (value.length >= 2) return listOf(label + value)
            }
        }
        return emptyList()
    }

    private fun habitCategory(text: String): String {
        val s = text.lowercase(Locale("ru", "RU"))
        return when {
            s.contains("открой ") -> "apps"
            s.contains("найди в интернете") || s.contains("поищи") || s.contains("гугл") -> "web"
            s.startsWith("позвони") || s.contains("кто звонил") || s.contains("пропущенн") -> "calls"
            s.contains("настройк") -> "settings"
            s.contains("камер") -> "camera"
            s.contains("сообщен") || s.contains("telegram") || s.contains("whatsapp") -> "messages"
            else -> "ai"
        }
    }
}
