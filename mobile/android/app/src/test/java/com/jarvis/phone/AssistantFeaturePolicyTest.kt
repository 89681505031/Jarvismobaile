package com.jarvis.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantFeaturePolicyTest {
    @Test fun classifiesBriefings() {
        assertEquals(AssistantFeaturePolicy.BriefingKind.MORNING,
            AssistantFeaturePolicy.briefingKind("утренняя сводка"))
        assertEquals(AssistantFeaturePolicy.BriefingKind.EVENING,
            AssistantFeaturePolicy.briefingKind("вечерний брифинг"))
        assertEquals(AssistantFeaturePolicy.BriefingKind.DAILY,
            AssistantFeaturePolicy.briefingKind("что у меня сегодня"))
    }

    @Test fun parsesTasks() {
        val add = AssistantFeaturePolicy.taskAction("добавь задачу купить молоко")
        assertTrue(add is AssistantFeaturePolicy.TaskAction.Add)
        assertEquals("купить молоко", (add as AssistantFeaturePolicy.TaskAction.Add).title)
        assertEquals(AssistantFeaturePolicy.TaskAction.Complete(7),
            AssistantFeaturePolicy.taskAction("выполни задачу 7"))
    }

    @Test fun parsesNotificationFilterAndVision() {
        assertEquals("иван", AssistantFeaturePolicy.notificationPerson("что писал Иван"))
        assertTrue(AssistantFeaturePolicy.asksNotificationBrief("есть что-то важное?"))
        assertTrue(AssistantFeaturePolicy.asksVisionCamera("что передо мной?"))
    }

    @Test fun parsesCalendarDraft() {
        assertNotNull(AssistantFeaturePolicy.calendarDraftTitle("добавь в календарь тренировка"))
        assertEquals("тренировка",
            AssistantFeaturePolicy.calendarDraftTitle("добавь в календарь тренировка"))
    }
}
