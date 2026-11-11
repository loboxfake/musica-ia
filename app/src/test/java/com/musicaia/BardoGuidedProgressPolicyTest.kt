package com.musicaia

import com.musicaia.modelo.BardoGuidedProgressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGuidedProgressPolicyTest {
    private val fallback = "He anotado el tema. ¿Qué estilo musical prefieres?"

    @Test
    fun keepsGeneratedQuestionWhenItAsksForTheNextMissingField() {
        val reply = "Entendido. ¿Qué género musical te gustaría?"
        assertEquals(
            reply,
            BardoGuidedProgressPolicy.selectReply(reply, fallback, requiredNextField = "estilo")
        )
    }

    @Test
    fun replacesGenericAcknowledgementThatDoesNotAdvanceTheGuidedFlow() {
        assertEquals(
            fallback,
            BardoGuidedProgressPolicy.selectReply(
                "Entendido. Hablemos de una canción sobre seguir adelante.",
                fallback,
                requiredNextField = "estilo"
            )
        )
    }

    @Test
    fun replacesQuestionAboutTheWrongField() {
        assertEquals(
            fallback,
            BardoGuidedProgressPolicy.selectReply(
                "¿Qué emoción quieres transmitir?",
                fallback,
                requiredNextField = "estilo"
            )
        )
    }

    @Test
    fun acceptsEnglishQuestionForTheMissingField() {
        val reply = "Which musical genre would you prefer?"
        assertEquals(
            reply,
            BardoGuidedProgressPolicy.selectReply(
                reply,
                "Which style would you like?",
                requiredNextField = "estilo"
            )
        )
    }

    @Test
    fun alwaysShowsTheDeterministicSummaryBeforeCreation() {
        val summary = "Resumen de la canción:\n- Tema: seguir adelante\nEscribe 'crear' o cambia un campo."
        assertEquals(
            summary,
            BardoGuidedProgressPolicy.selectReply(
                "Perfecto, ya tengo toda la información. ¡Voy a crearla!",
                summary,
                requiredNextField = null,
                requireSummary = true
            )
        )
    }

    @Test
    fun doesNotReplaceNormalConversationWhenNoGuidedFieldIsPending() {
        val natural = "Puedo explicarte cómo se organiza una mezcla estéreo."
        assertEquals(
            natural,
            BardoGuidedProgressPolicy.selectReply(natural, fallback, requiredNextField = null)
        )
    }

    @Test
    fun rejectsQuestionsWithoutARecognizedFieldLabel() {
        assertTrue(!BardoGuidedProgressPolicy.asksAboutRequiredField("¿Qué prefieres?", "estilo"))
    }
}
