package com.musicaia

import com.musicaia.modelo.BardoIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoIntentRouterTest {
    @Test
    fun ordinaryQuestionsStayInGeneralChatEvenWhenOldPreferencesExist() {
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Hola, ¿cómo estás?", false))
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Explícame qué es la inteligencia artificial", false))
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Cambia el estilo a rock", false))
    }

    @Test
    fun explicitSongRequestsActivateMusicSkill() {
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Una canción sobre superar los obstáculos y seguir adelante", false))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Quiero componer una canción", false))
    }

    @Test
    fun ownLyricsAndChangesAreRecognizedInCorrectContext() {
        assertEquals(BardoIntentRouter.Intent.OWN_LYRICS,
            BardoIntentRouter.classify("Quiero usar mi letra", false))
        assertEquals(BardoIntentRouter.Intent.CHANGE_CREATION,
            BardoIntentRouter.classify("Cambia el estilo a rock", true))
    }

    @Test
    fun activeCreationRoutesAnswersToTheNextField() {
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Superar los obstáculos y seguir adelante", true))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Rock alternativo", true))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Rápido", true))
    }

    @Test
    fun userCanExplicitlyLeaveCreationMode() {
        assertEquals(BardoIntentRouter.Intent.END_CREATION,
            BardoIntentRouter.classify("Volver al chat general", true))
        assertEquals(BardoIntentRouter.Intent.END_CREATION,
            BardoIntentRouter.classify("Cancelar creación", true))
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Hola", false))
    }

    @Test
    fun confirmationOnlyConfirmsAnExistingCreation() {
        assertEquals(BardoIntentRouter.Intent.CONFIRM_CREATION,
            BardoIntentRouter.classify("Crear", true))
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Crear", false))
    }

    @Test
    fun instrumentalAndVoiceChoicesMapToHonestExecutionModes() {
        assertTrue(BardoIntentRouter.isInstrumentalOnlyType("instrumental"))
        assertTrue(BardoIntentRouter.isInstrumentalOnlyType("solo instrumental"))
        assertFalse(BardoIntentRouter.isInstrumentalOnlyType("ambas (voz e instrumental)"))
        assertTrue(BardoIntentRouter.requiresUserVoice("voz"))
        assertTrue(BardoIntentRouter.requiresUserVoice("ambas"))
        assertFalse(BardoIntentRouter.requiresUserVoice("instrumental"))
        assertFalse(BardoIntentRouter.requiresUserVoice("instrumental sin voz"))
    }
}
