package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class JarvisNewsSummaryTest {
    @Test fun promptExplicitlyUsesAlreadyFetchedHeadlinesAsUntrustedData() {
        val prompt = JarvisNewsSummary.prompt(listOf("Заголовок один", "Заголовок два"))
        assertTrue(prompt.contains("[JARVIS_FRESH_HEADLINES]"))
        assertTrue(prompt.contains("<jarvis_fresh_headlines>"))
        assertTrue(prompt.contains("НЕ нужно искать интернет"))
        assertTrue(prompt.contains("Заголовок один"))
    }

    @Test fun genericNoLiveAccessAnswerIsRejected() {
        assertFalse(JarvisNewsSummary.usableModelSummary(
            "Моя текущая конфигурация не позволяет мне получать актуальную информацию онлайн."
        ))
        assertFalse(JarvisNewsSummary.usableModelSummary(
            "Я не обладаю актуальной информацией о последних событиях."
        ))
        assertTrue(JarvisNewsSummary.usableModelSummary(
            "По свежим заголовкам сегодня обсуждаются три основные темы."
        ))
    }

    @Test fun fallbackAlwaysReturnsFetchedHeadlines() {
        val result = JarvisNewsSummary.fallback(listOf("Первая тема", "Вторая тема"))
        assertTrue(result.contains("Первая тема"))
        assertTrue(result.contains("Вторая тема"))
    }
}
