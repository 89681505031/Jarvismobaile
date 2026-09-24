package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class BackgroundCommandPolicyTest {
    @Test fun safeCommandsAcceptNaturalSpeechInMinimizedMode() {
        for (phrase in listOf(
            "Сделай громче!", "Громче", "тише", "Включи фонарь",
            "Погаси фонарик", "Следующий трек", "переключи песню",
            "поставь музыку на паузу", "Включи музыку!", "возобнови музыку",
            "домой", "покажи последние приложения"
        )) {
            assertTrue(phrase, BackgroundCommandPolicy.permitted(phrase))
        }
    }

    @Test fun actionsRequiringScreenOrPermissionStayBehindNotification() {
        for (phrase in listOf(
            "позвони папе", "открой браузер", "открой WhatsApp",
            "прочитай последнее сообщение", "найди в интернете погоду",
            "включи песню Metallica", "включи музыку Metallica"
        )) {
            assertFalse(phrase, BackgroundCommandPolicy.permitted(phrase))
            assertTrue(phrase, BackgroundCommandPolicy.requiresVisibleUi(phrase))
        }
    }

    @Test fun combinedOrAmbiguousCommandsAreNotPromotedToSafeActions() {
        assertFalse(BackgroundCommandPolicy.permitted("пауза и позвони"))
        assertFalse(BackgroundCommandPolicy.permitted("включи фонарик и открой браузер"))
        assertFalse(BackgroundCommandPolicy.requiresVisibleUi("который час"))
        assertFalse(BackgroundCommandPolicy.requiresVisibleUi("расскажи про космос"))
    }

    @Test fun normalizationHandlesPunctuationSpacesAndYo() {
        assertEquals("включи музыку", BackgroundCommandPolicy.normalize("  Включи   музыку!!!  "))
        assertEquals("все еще тише", BackgroundCommandPolicy.normalize("Всё ещё тише?"))
    }
}
