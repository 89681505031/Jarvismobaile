package com.jarvis.phone

import java.util.Locale

/** Voice actions safe to execute while the user is not looking at the screen.
 * Never dial, open applications, read private notifications or start activities
 * without the user opening JARVIS through its ongoing notification.
 */
object BackgroundCommandPolicy {
    private val allowed = setOf(
        "включи фонарик", "выключи фонарик", "включи свет", "выключи свет",
        "увеличь громкость", "сделай громче", "уменьши громкость", "сделай тише",
        "выключи звук", "включи звук",
        "стоп музыка", "останови музыку", "пауза",
        "следующая песня", "следующий трек", "предыдущая песня",
        "предыдущий трек", "назад песню"
    )
    fun permitted(phrase: String): Boolean {
        val normalized = phrase.trim().lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[.!?,;:]+$"), "").trim()
        return normalized in allowed
    }
}
