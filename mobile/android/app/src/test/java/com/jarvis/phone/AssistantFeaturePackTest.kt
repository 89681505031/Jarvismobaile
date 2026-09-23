package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class AssistantFeaturePackTest {
    @Test fun reminderParserSupportsMinutesHoursAndDays() {
        assertEquals(10 to "проверить уроки",
            JarvisReminders.parseRussian("Напомни через 10 минут проверить уроки"))
        assertEquals(120 to "зарядить телефон",
            JarvisReminders.parseRussian("напомни мне через 2 часа зарядить телефон"))
        assertEquals(1440 to "Напоминание",
            JarvisReminders.parseRussian("Напомни через 1 день"))
    }
    @Test fun reminderParserRejectsUnboundedAndUnrequestedSpeech() {
        assertNull(JarvisReminders.parseRussian("напомни через 0 минут звонок"))
        assertNull(JarvisReminders.parseRussian("напомни через 10 дней звонок"))
        assertNull(JarvisReminders.parseRussian("позвони через 5 минут"))
    }
    @Test fun offlineKnowledgeNeverPretendsToBeLiveWebOrAI() {
        assertNotNull(OfflineKnowledge.answer("сколько времени"))
        assertNotNull(OfflineKnowledge.answer("какая сегодня дата"))
        assertNull(OfflineKnowledge.answer("какая погода в Москве"))
        assertNull(OfflineKnowledge.answer("кто мне написал"))
    }
}
