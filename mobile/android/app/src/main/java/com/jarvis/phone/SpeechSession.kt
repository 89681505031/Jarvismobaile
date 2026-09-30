package com.jarvis.phone

/** Tokens prevent callbacks from a cancelled recognizer from affecting a new session. */
class SpeechSession {
    private var generation = 0L
    var active: Boolean = false
        private set
    var foreground: Boolean = false
        private set

    fun resume() { foreground = true }
    fun pause() { foreground = false; cancel() }
    fun begin(): Long? {
        if (!foreground || active) return null
        active = true
        return ++generation
    }
    fun accepts(token: Long): Boolean = foreground && active && generation == token
    fun finish(token: Long): Boolean {
        if (!accepts(token)) return false
        cancel()
        return true
    }
    fun cancel() { active = false; generation++ }
}
