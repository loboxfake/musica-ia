package com.musicaia

import com.musicaia.modelo.BardoGuidedProgressPolicy
import com.musicaia.modelo.BardoCreationReadinessPolicy
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
    fun acceptsNaturalSpanishSynonymsForStyleMoodAndTempoQuestions() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué tipo de música prefieres?",
                "estilo"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué sentimiento quieres transmitir?",
                "ambiente"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué velocidad te gustaría para la canción?",
                "ritmo"
            )
        )
    }

    @Test
    fun acceptsNaturalQuestionsAboutSongEnergyAndAtmosphere() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Qué atmósfera debería tener la canción?",
                "ambiente"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿Cómo quieres que se sienta el tema?",
                "ambiente"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "What energy should the track have?",
                "ambiente"
            )
        )
    }

    @Test
    fun acceptsNaturalSlowOrFastQuestionsAsRhythmPrompts() {
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "¿La imaginas lenta o rápida?",
                "ritmo"
            )
        )
        assertTrue(
            BardoGuidedProgressPolicy.asksAboutRequiredField(
                "Would you prefer a slow or fast track?",
                "ritmo"
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


    @Test
    fun creationReadinessRequiresEveryGuidedFieldAndSupportedLanguage() {
        val complete = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "Español",
            "tipo" to "solo instrumental"
        )
        assertEquals(null, BardoCreationReadinessPolicy.blockReason(complete, hasVoiceWav = false))
        assertTrue(
            BardoCreationReadinessPolicy.blockReason(
                complete - "ritmo",
                hasVoiceWav = false
            )!!.contains("ritmo")
        )
        assertTrue(
            BardoCreationReadinessPolicy.blockReason(
                complete + ("idioma" to "Français"),
                hasVoiceWav = false
            )!!.contains("español o inglés")
        )
    }

    @Test
    fun vocalCreationRequiresAnExplicitVoiceSourceAndAnAvailableWav() {
        val complete = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "Español",
            "tipo" to "voz",
            "vozFuente" to "grabada"
        )
        assertTrue(
            BardoCreationReadinessPolicy.blockReason(
                complete - "vozFuente",
                hasVoiceWav = false
            )!!.contains("fuente de voz")
        )
        assertTrue(
            BardoCreationReadinessPolicy.blockReason(
                complete,
                hasVoiceWav = false
            )!!.contains("archivo WAV")
        )
        assertEquals(
            null,
            BardoCreationReadinessPolicy.blockReason(complete, hasVoiceWav = true)
        )
    }

    @Test
    fun instrumentalCreationDoesNotRequireAUserVoiceFile() {
        val complete = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "English",
            "tipo" to "solo instrumental",
            "vozFuente" to "ninguna"
        )
        assertEquals(
            null,
            BardoCreationReadinessPolicy.blockReason(complete, hasVoiceWav = false)
        )
    }


    @Test
    fun vocalCreationRejectsUnknownOrContradictoryVoiceSourcesEvenWhenAWavExists() {
        val base = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "Español",
            "tipo" to "voz"
        )
        listOf("ninguna", "sin voz", "voice synthesis", "otra fuente").forEach { source ->
            assertTrue(
                "La fuente '$source' no debe aceptarse",
                BardoCreationReadinessPolicy.blockReason(
                    base + ("vozFuente" to source),
                    hasVoiceWav = true
                )!!.contains("fuente de voz válida")
            )
        }
        assertEquals(
            null,
            BardoCreationReadinessPolicy.blockReason(
                base + ("vozFuente" to "grabada"),
                hasVoiceWav = true
            )
        )
        assertEquals(
            null,
            BardoCreationReadinessPolicy.blockReason(
                base + ("vozFuente" to "archivo"),
                hasVoiceWav = true
            )
        )
    }

}
