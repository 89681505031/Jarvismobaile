package com.jarvis.phone

import org.junit.Assert.*
import org.junit.Test

class GigaChatBrainPolicyTest {
    @Test fun supportedModelsAndAccountScopesAreExplicit() {
        assertEquals("GigaChat-2", GigaChatBrainPolicy.validModel(""))
        assertEquals("GigaChat-2", GigaChatBrainPolicy.validModel("unverified-model"))
        assertEquals("GigaChat-2-Pro", GigaChatBrainPolicy.validModel("GigaChat-2-Pro"))
        assertEquals("GigaChat-2-Max", GigaChatBrainPolicy.validModel("GigaChat-2-Max"))
        assertEquals("GIGACHAT_API_PERS", GigaChatBrainPolicy.validScope("unknown"))
        assertEquals("GIGACHAT_API_B2B", GigaChatBrainPolicy.validScope("GIGACHAT_API_B2B"))
        assertEquals("GIGACHAT_API_CORP", GigaChatBrainPolicy.validScope("GIGACHAT_API_CORP"))
    }

    @Test fun charactersUseGigaChatWithoutPretendingToControlAndroid() {
        val jarvis = GigaChatBrainPolicy.systemPrompt("J.A.R.V.I.S.")
        assertTrue(jarvis.contains("GigaChat"))
        assertTrue(jarvis.contains("отдельный модуль Android"))
        assertTrue(jarvis.contains("Пименовым Алексом Романовичем"))
        assertTrue(jarvis.contains("не утверждай, что это буквально"))
        assertTrue(jarvis.contains("[JARVIS_FRESH_HEADLINES]"))
        assertTrue(jarvis.lowercase().contains("не говори, что у тебя нет доступа к актуальной информации"))
        assertTrue(GigaChatBrainPolicy.systemPrompt("Cyber").contains("безопасность"))
        assertTrue(GigaChatBrainPolicy.systemPrompt("Кибер").contains("Кибер"))
        assertTrue(GigaChatBrainPolicy.systemPrompt("Luna").contains("аналитична"))
    }

    @Test fun connectionErrorsDoNotExposeKeysOrRawServerMessages() {
        assertTrue(GigaChatBrainPolicy.connectionMessage(401).contains("ключ"))
        assertTrue(GigaChatBrainPolicy.connectionMessage(403).contains("scope"))
        assertTrue(GigaChatBrainPolicy.connectionMessage(429).contains("лимит"))
        assertFalse(GigaChatBrainPolicy.connectionMessage(500).contains("Bearer"))
        assertFalse(GigaChatBrainPolicy.connectionMessage(403).contains("Authorization: Basic"))
    }
}
