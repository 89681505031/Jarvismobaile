package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class BackgroundCommandPolicyTest {
    @Test fun smallSafeCommandSetIsUsable() {
        assertTrue(BackgroundCommandPolicy.permitted("Сделай громче!"))
        assertTrue(BackgroundCommandPolicy.permitted("Включи фонарик"))
        assertTrue(BackgroundCommandPolicy.permitted("Следующий трек"))
        assertTrue(BackgroundCommandPolicy.permitted("пауза"))
    }
    @Test fun actionsRequiringScreenOrConfirmationStayBehindNotification() {
        for (phrase in listOf("позвони папе", "открой браузер", "открой WhatsApp",
                "прочитай мои сообщения", "найди в интернете погоду", "включи музыку")) {
            assertFalse(phrase, BackgroundCommandPolicy.permitted(phrase))
        }
        assertFalse(BackgroundCommandPolicy.permitted("пауза и позвони"))
        assertFalse(BackgroundCommandPolicy.permitted("включи фонарик и открой браузер"))
    }
}
