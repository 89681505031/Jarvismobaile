package com.jarvis.phone

import android.speech.tts.TextToSpeech

/** Distinct local TTS fallback; cloud Fish voice IDs already exist separately. */
object PersonaSpeech {
    fun apply(tts: TextToSpeech, persona: String) {
        val (rate, pitch) = when (persona.lowercase()) {
            "astra" -> 1.05f to 1.16f
            "luna" -> 0.90f to 1.08f
            "terra" -> 0.96f to 0.91f
            "cyber", "сайбер", "кибер" -> 1.08f to 0.86f
            else -> 0.98f to 1.0f
        }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
    }
}
