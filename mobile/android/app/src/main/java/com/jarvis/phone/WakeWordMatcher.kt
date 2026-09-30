package com.jarvis.phone

import java.util.Locale

/** Deliberately matches only the start of a recognized utterance (after a short greeting).
 * "Я читал про Луну" must not turn the microphone into command mode.
 */
object WakeWordMatcher {
    data class Activation(val persona: String, val command: String)

    private val names = mapOf(
        "джарвис" to "J.A.R.V.I.S.", "джервис" to "J.A.R.V.I.S.",
        "жарвис" to "J.A.R.V.I.S.", "jarvis" to "J.A.R.V.I.S.",
        "астра" to "Astra", "astra" to "Astra",
        "луна" to "Luna", "luna" to "Luna",
        "терра" to "Terra", "terra" to "Terra",
        "сайбер" to "Кибер", "кибер" to "Кибер", "cyber" to "Кибер"
    )
    private val greetings = Regex("^(эй|привет|слушай|окей|okay|hey|ok)[\\s,!.?]+")
    private val leadingName = Regex("^[\\p{L}]+")
    private val separators = Regex("^[\\s,!.?;:—–-]+")

    fun parse(phrase: String): Activation? {
        var text = phrase.trim().lowercase(Locale.ROOT).replace('ё', 'е')
        repeat(2) { text = greetings.replaceFirst(text, "") }
        val first = leadingName.find(text)?.value ?: return null
        val persona = names[first] ?: return null
        val rest = separators.replaceFirst(text.substring(first.length), "").trim()
        return Activation(persona, rest)
    }
}
