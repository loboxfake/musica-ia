package com.musicaia

import com.musicaia.modelo.BardoGuidedProgressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoGuidedProgressPolicyTest {
    private val fallback = "He anotado el tema. ¿Qué estilo musical prefieres?"

    @Test
    fun prioritizesAnExplicitlyRequestedFieldOverTheNormalQuestionOrder() {
        val values = mapOf(
            "tema" to "seguir adelante",
            "estilo" to "rock"
        )
        assertEquals(
            "idioma",
            BardoGuidedProgressPolicy.nextRequiredField(values, "idioma")
        )
    }

    @Test
    fun fallsBackToNormalOrderWhenTheRequestedFieldIsAlreadyFilled() {
        val values = mapOf(
            "tema" to "seguir adelante",
            "estilo" to "rock",
            "idioma" to "English"
        )
        assertEquals(
            "ambiente",
            BardoGuidedProgressPolicy.nextRequiredField(values, "idioma")
        )
    }

    @Test
    fun keepsNormalOrderWithoutARequestedField() {
        assertEquals(
            "tema",
            BardoGuidedProgressPolicy.nextRequiredField(emptyMap(), null)
        )
    }

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
    fun rejectsAQuestionAboutFavoriteSongsAsAThemePrompt() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué canción te gusta?",
                "tema"
            )
        )
    }

    @Test
    fun acceptsQuestionsThatAskWhatTheNewSongShouldBeAbout() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿De qué quieres que trate la canción?",
                "tema"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "What should the song be about?",
                "tema"
            )
        )
    }

    @Test
    fun rejectsQuestionsThatOnlyMentionMusicWithoutAskingAboutStyle() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué te parece el proyecto musical?",
                "estilo"
            )
        )
    }

    @Test
    fun normalizesSpanishAccentsWhenCheckingTheMissingField() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué género te gustaría?",
                "estilo"
            )
        )
    }

    @Test
    fun rejectsMultipleQuestionsSoTheGuideAsksOnlyOneThingAtATime() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué estilo prefieres? ¿Y qué emoción buscas?",
                "estilo"
            )
        )
    }

    @Test
    fun acceptsSpecificQuestionsAboutRecordingOrImportingTheVoice() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Quieres grabar tu voz o usar un archivo de voz?",
                "vozFuente"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Would you like to upload a voice recording?",
                "vozFuente"
            )
        )
    }

    @Test
    fun doesNotConfuseVoiceTypeWithVoiceSource() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué tipo de voz prefieres?",
                "vozFuente"
            )
        )
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Which voice would you like?",
                "vozFuente"
            )
        )
    }

    @Test
    fun typeQuestionMustAskForVocalOrInstrumentalModeNotVoiceTimbre() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué tipo de voz prefieres?",
                "tipo"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿La prefieres cantada o instrumental?",
                "tipo"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Would you like vocals or an instrumental track?",
                "tipo"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿La quieres con voz?",
                "tipo"
            )
        )
    }

    @Test
    fun rejectsQuestionsWithoutARecognizedFieldLabel() {
        assertTrue(!BardoGuidedProgressPolicy.asksAboutRequiredField("¿Qué prefieres?", "estilo"))
    }

    @Test
    fun rejectsFavoriteEnglishMusicAsSongLanguageQuestion() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Do you like English music?",
                "idioma"
            )
        )
    }

    @Test
    fun acceptsQuestionsThatExplicitlyAskWhichLanguageTheSongShouldUse() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Would you like the song in English?",
                "idioma"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿La quieres en español?",
                "idioma"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Which language would you prefer?",
                "idioma"
            )
        )
    }


    @Test
    fun acceptsSpanishAndEnglishQuestionsAboutTheStoryOrMessageOfTheSong() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué historia quieres contar?",
                "tema"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué mensaje te gustaría transmitir?",
                "tema"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "What story should the song tell?",
                "tema"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "What message would you like the track to convey?",
                "tema"
            )
        )
    }

    @Test
    fun rejectsQuestionsAboutStoriesTheUserLikesRatherThanTheNewSongTheme() {
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué historia te gusta?",
                "tema"
            )
        )
        assertTrue(
            !BardoGuidedProgressPolicy.asksAboutRequiredField(
                "What story do you like?",
                "tema"
            )
        )
    }

}
