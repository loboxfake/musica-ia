package com.musicaia

import com.musicaia.modelo.BardoIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class BardoIntentRouterTest {
    @Test
    fun ordinaryQuestionsStayInGeneralChat() {
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Hola, ¿cómo estás?", false))
        assertEquals(BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Explícame qué es la inteligencia artificial", false))
    }

    @Test
    fun explicitSongRequestsActivateMusicSkill() {
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Una canción sobre superar los obstáculos y seguir adelante", false))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Quiero componer una canción", false))
    }

    @Test
    fun ownLyricsAndChangesAreRecognizedBeforeGeneralChat() {
        assertEquals(BardoIntentRouter.Intent.OWN_LYRICS,
            BardoIntentRouter.classify("Quiero usar mi letra", false))
        assertEquals(BardoIntentRouter.Intent.CHANGE_CREATION,
            BardoIntentRouter.classify("Cambia el estilo a rock", true))
    }

    @Test
    fun activeCreationNeverFallsBackToFreeChatForFieldAnswers() {
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Superar los obstáculos y seguir adelante", true))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Rock alternativo", true))
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Rápido", true))
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
        assertEquals(true, BardoIntentRouter.isInstrumentalOnlyType("instrumental"))
        assertEquals(true, BardoIntentRouter.isInstrumentalOnlyType("solo instrumental"))
        assertEquals(false, BardoIntentRouter.isInstrumentalOnlyType("ambas (voz e instrumental)"))
        assertEquals(true, BardoIntentRouter.requiresUserVoice("voz"))
        assertEquals(true, BardoIntentRouter.requiresUserVoice("ambas"))
        assertEquals(false, BardoIntentRouter.requiresUserVoice("instrumental"))
        assertEquals(false, BardoIntentRouter.requiresUserVoice("instrumental sin voz"))
    }
}
