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
    fun initialSongThemeImmediatelyAdvancesToTheFirstMissingField() {
        val provided = BardoIntentRouter.extractProvidedFields(
            "Una canción sobre superar los obstáculos y seguir adelante"
        )
        assertEquals("superar los obstáculos y seguir adelante", provided["tema"])
        assertEquals("estilo", BardoIntentRouter.nextRequiredField(provided))

        val withStyle = provided + ("estilo" to "rock")
        assertEquals("ambiente", BardoIntentRouter.nextRequiredField(withStyle))
    }

    @Test
    fun negatedCreativeChangeDoesNotBecomeAFieldUpdate() {
        val rejected = "No quiero cambiar el estilo a rock"
        assertTrue(BardoIntentRouter.isNegatedCreativeChange(rejected))
        assertTrue(BardoIntentRouter.extractProvidedFields(rejected).isEmpty())
    }

    @Test
    fun commaSeparatedNoStillAllowsAnAffirmativeChange() {
        val affirmative = "No, quiero cambiar el estilo a rock"
        assertFalse(BardoIntentRouter.isNegatedCreativeChange(affirmative))
        assertEquals("rock", BardoIntentRouter.extractProvidedFields(affirmative)["estilo"])
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
    fun activeCreationKeepsStandaloneQuestionsInGeneralChatWithoutLosingCreationState() {
        assertEquals(
            BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("¿Qué es un sintetizador?", true)
        )
        assertEquals(
            BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("¿Qué estilo musical me recomiendas?", true)
        )
        assertEquals(
            BardoIntentRouter.Intent.GENERAL_CHAT,
            BardoIntentRouter.classify("Which style would you recommend?", true)
        )
    }

    @Test
    fun activeCreationStillTreatsCreativeAnswersAsFieldValues() {
        assertEquals(
            BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Que sea alegre, ¿te parece?", true)
        )
        assertEquals(
            BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Rock, ¿te parece?", true)
        )
        assertEquals(
            BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Estilo: rock", true)
        )
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
    fun recognizesChangeRequestsThatNeedANewValue() {
        assertEquals("estilo", BardoIntentRouter.requestedFieldChangeWithoutValue("Cambiar el estilo"))
        assertEquals("tema", BardoIntentRouter.requestedFieldChangeWithoutValue("¿Modificar tema?"))
        assertEquals("idioma", BardoIntentRouter.requestedFieldChangeWithoutValue("Change language"))
        assertEquals("tipo", BardoIntentRouter.requestedFieldChangeWithoutValue("Cambiar voz"))
        assertEquals(null, BardoIntentRouter.requestedFieldChangeWithoutValue("Cambiar estilo a rock"))
        assertEquals(null, BardoIntentRouter.requestedFieldChangeWithoutValue("Hola"))
    }

    @Test
    fun recognizesAllConfiguredCreationConfirmationAliases() {
        listOf("adelante", "crear musica", "crear canción", "continuar", "yes").forEach { message ->
            assertEquals(BardoIntentRouter.Intent.CONFIRM_CREATION,
                BardoIntentRouter.classify(message, true))
        }
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
