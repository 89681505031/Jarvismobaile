package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class BackgroundInfoPolicyTest {
    @Test fun timeAndDatePhrasesWorkWithNaturalRussianSpeech() {
        assertEquals(BackgroundInfoPolicy.Kind.TIME, BackgroundInfoPolicy.classify("Время"))
        assertEquals(BackgroundInfoPolicy.Kind.TIME, BackgroundInfoPolicy.classify("Сколько сейчас времени?"))
        assertEquals(BackgroundInfoPolicy.Kind.TIME, BackgroundInfoPolicy.classify("Скажи который час!"))
        assertEquals(BackgroundInfoPolicy.Kind.DATE, BackgroundInfoPolicy.classify("Какая сегодня дата?"))
    }

    @Test fun mainNewsWorksInBackgroundButLocationNewsStaysForegroundOnly() {
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Новости"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Главные сводки новостей"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Последние новости!"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Какие новости?"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Какие сегодня новости"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Что в новостях"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Новости на сегодня"))
        assertEquals(BackgroundInfoPolicy.Kind.MAIN_NEWS, BackgroundInfoPolicy.classify("Что нового в мире"))
        assertNull(BackgroundInfoPolicy.classify("местные новости"))
        assertNull(BackgroundInfoPolicy.classify("погода"))
    }

    @Test fun timeAnswerSupportsShortNaturalCommands() {
        val fixed = 0L
        assertNotNull(OfflineKnowledge.answer("время", fixed))
        assertNotNull(OfflineKnowledge.answer("сколько сейчас времени?", fixed))
        assertNotNull(OfflineKnowledge.answer("который сейчас час", fixed))
    }
}
