package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class WakeWordMatcherTest {
    @Test fun allPersonasAndAliases() {
        for ((phrase, name) in listOf(
            "Джарвис" to "J.A.R.V.I.S.", "джервис" to "J.A.R.V.I.S.",
            "жарвис" to "J.A.R.V.I.S.", "jarvis" to "J.A.R.V.I.S.",
            "Астра" to "Astra", "Astra" to "Astra",
            "Луна" to "Luna", "Luna" to "Luna",
            "Терра" to "Terra", "Terra" to "Terra",
            "Сайбер" to "Cyber", "кибер" to "Cyber", "Cyber" to "Cyber"
        )) assertEquals(name, WakeWordMatcher.parse(phrase)?.persona)
    }

    @Test fun inlineVoiceCommandKeepsItsText() {
        assertEquals(
            WakeWordMatcher.Activation("J.A.R.V.I.S.", "открой камеру"),
            WakeWordMatcher.parse("Эй, Джарвис, открой камеру")
        )
        assertEquals(
            WakeWordMatcher.Activation("Terra", "открой браузер"),
            WakeWordMatcher.parse("Терра! Открой браузер")
        )
    }

    @Test fun noAccidentalActivationInAnOrdinarySentence() {
        for (phrase in listOf("Я смотрел на Луну", "лунаход", "астральный мир",
                "где Джарвис", "сосед позвал астру", "слушай музыку")) {
            assertNull(phrase, WakeWordMatcher.parse(phrase))
        }
    }

    @Test fun wakeOnlyHasAnEmptyCommand() {
        assertEquals("", WakeWordMatcher.parse("Привет, Луна!")?.command)
    }
}
