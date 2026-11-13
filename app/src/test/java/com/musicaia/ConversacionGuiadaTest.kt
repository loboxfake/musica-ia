package com.musicaia

import com.musicaia.modelo.BardoIntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversacionGuiadaTest {
    @Test
    fun asksOnlyForTheFirstMissingRequiredField() {
        assertEquals("tema", BardoIntentRouter.nextRequiredField(emptyMap()))
        assertEquals("ambiente", BardoIntentRouter.nextRequiredField(
            mapOf("tema" to "superar obstáculos", "estilo" to "rock")
        ))
        assertEquals("idioma", BardoIntentRouter.nextRequiredField(
            mapOf("tema" to "x", "estilo" to "y", "ambiente" to "z", "ritmo" to "medio")
        ))
    }

    @Test
    fun requestsVoiceSourceOnlyWhenVoiceWasSelected() {
        val required = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "español",
            "tipo" to "voz"
        )
        assertEquals("vozFuente", BardoIntentRouter.nextRequiredField(required))
        assertNull(BardoIntentRouter.nextRequiredField(required + ("vozFuente" to "grabada")))
    }

    @Test
    fun instrumentalDoesNotAskForVoiceSource() {
        val values = mapOf(
            "tema" to "superar obstáculos",
            "estilo" to "rock",
            "ambiente" to "esperanzador",
            "ritmo" to "medio",
            "idioma" to "español",
            "tipo" to "solo instrumental"
        )
        assertNull(BardoIntentRouter.nextRequiredField(values))
    }

    @Test
    fun extractsSeveralExplicitCreativeFieldsFromOneMessage() {
        val parsed = BardoIntentRouter.extractProvidedFields(
            "Una canción sobre superar obstáculos, estilo: rock alternativo, ambiente: esperanzador, " +
                "ritmo: rápido, idioma: español, tipo: solo instrumental"
        )
        assertEquals("superar obstáculos", parsed["tema"])
        assertEquals("rock alternativo", parsed["estilo"])
        assertEquals("esperanzador", parsed["ambiente"])
        assertEquals("rápido", parsed["ritmo"])
        assertEquals("Español", parsed["idioma"])
        assertEquals("solo instrumental", parsed["tipo"])
        assertEquals("ninguna", parsed["vozFuente"])
    }

    @Test
    fun extractsVoiceSourceOnlyWhenTheMessageClearlyProvidesIt() {
        val parsed = BardoIntentRouter.extractProvidedFields(
            "Tema: valentía; género: pop; emoción: optimista; tempo: medio; lengua: español; " +
                "tipo: ambas; fuente de voz: grabada"
        )
        assertEquals("valentía", parsed["tema"])
        assertEquals("pop", parsed["estilo"])
        assertEquals("optimista", parsed["ambiente"])
        assertEquals("medio", parsed["ritmo"])
        assertEquals("Español", parsed["idioma"])
        assertEquals("ambas (voz e instrumental)", parsed["tipo"])
        assertEquals("grabada", parsed["vozFuente"])
    }

    @Test
    fun recognizesNaturalGenreMoodTempoThemeLanguageAndInstrumentalChoice() {
        val parsed = BardoIntentRouter.extractProvidedFields(
            "Quiero una canción de rock alternativo sobre superar obstáculos, que sea esperanzadora, " +
                "con un ritmo rápido, en español y sin voz"
        )
        assertEquals("superar obstáculos", parsed["tema"])
        assertEquals("rock alternativo", parsed["estilo"])
        assertEquals("esperanzadora", parsed["ambiente"])
        assertEquals("rápido", parsed["ritmo"])
        assertEquals("Español", parsed["idioma"])
        assertEquals("solo instrumental", parsed["tipo"])
        assertEquals("ninguna", parsed["vozFuente"])
    }

    @Test
    fun recognizesExplicitBpmNotation() {
        val parsed = BardoIntentRouter.extractProvidedFields(
            "Una canción para correr a 120 BPM, estilo synthwave"
        )
        assertEquals("120 BPM", parsed["ritmo"])
        assertEquals("synthwave", parsed["estilo"])
    }

    @Test
    fun doesNotInferCreativeFieldsFromUnlabelledAmbiguousWords() {
        assertEquals(
            emptyMap<String, String>(),
            BardoIntentRouter.extractProvidedFields("He escuchado rock y me siento optimista.")
        )
    }

    @Test
    fun extractsVoiceSourceFromAnEnglishLabelledBrief() {
        val parsed = BardoIntentRouter.extractProvidedFields(
            "Theme: resilience; style: rock; mood: hopeful; rhythm: medium; " +
                "language: English; type: with vocals; voice source: recorded"
        )
        assertEquals("English", parsed["idioma"])
        assertEquals("voz", parsed["tipo"])
        assertEquals("grabada", parsed["vozFuente"])
    }

    @Test
    fun detectsSpanishAndEnglishConversationLanguageButLeavesAmbiguousMessagesAlone() {
        assertEquals("en", BardoIntentRouter.detectConversationLanguage(
            "Write a song about hope with a fast tempo"
        ))
        assertEquals("es", BardoIntentRouter.detectConversationLanguage(
            "Quiero una canción sobre esperanza con ritmo rápido"
        ))
        assertNull(BardoIntentRouter.detectConversationLanguage("rock"))
        assertEquals("en", BardoIntentRouter.detectConversationLanguage("English"))
    }

    @Test
    fun songsAreLimitedToSpanishAndEnglish() {
        assertEquals("Español", BardoIntentRouter.canonicalSongLanguage("castellano"))
        assertEquals("English", BardoIntentRouter.canonicalSongLanguage("English"))
        assertEquals("English", BardoIntentRouter.canonicalSongLanguage("en-US"))
        assertNull(BardoIntentRouter.canonicalSongLanguage("français"))
        assertEquals("idioma", BardoIntentRouter.nextRequiredField(mapOf(
            "tema" to "resilience", "estilo" to "rock", "ambiente" to "hopeful",
            "ritmo" to "fast", "idioma" to "français", "tipo" to "solo instrumental"
        )))
    }

    @Test
    fun extractsEnglishSongBrief() {
        val fields = BardoIntentRouter.extractProvidedFields(
            "Write a song about overcoming obstacles, style: alternative rock, mood: hopeful, " +
                "rhythm: fast tempo, language: English, type: instrumental only"
        )
        assertEquals("overcoming obstacles", fields["tema"])
        assertEquals("alternative rock", fields["estilo"])
        assertEquals("hopeful", fields["ambiente"])
        assertEquals("fast tempo", fields["ritmo"])
        assertEquals("English", fields["idioma"])
        assertEquals("solo instrumental", fields["tipo"])
    }

    @Test
    fun identifiesEnglishCreationIntent() {
        assertEquals(BardoIntentRouter.Intent.MUSIC_CREATION,
            BardoIntentRouter.classify("Write a song about hope", false))
    }

    @Test
    fun classifiesEnglishFieldChangesWithoutRestartingCreation() {
        assertEquals(
            BardoIntentRouter.Intent.CHANGE_CREATION,
            BardoIntentRouter.classify("change theme to resilience", true)
        )
        assertEquals(
            BardoIntentRouter.Intent.CHANGE_CREATION,
            BardoIntentRouter.classify("switch language to English", true)
        )
    }

    @Test
    fun detectsEnglishAndSpanishGuidedDialogueWithoutGuessingFromOneAmbiguousWord() {
        assertEquals("en", BardoIntentRouter.detectGuidedLanguage("Write a song about hope"))
        assertEquals("es", BardoIntentRouter.detectGuidedLanguage("Quiero una canción sobre esperanza"))
        assertEquals("en", BardoIntentRouter.detectGuidedLanguage("English"))
        assertEquals("es", BardoIntentRouter.detectGuidedLanguage("Español"))
        assertNull(BardoIntentRouter.detectGuidedLanguage("I heard music yesterday"))
    }

    @Test
    fun completedFieldsCanBeChangedWithoutRestartingTheFlow() {
        val original = mapOf("tema" to "historia antigua", "estilo" to "pop")
        val updated = original + ("tema" to "superar obstáculos")
        assertEquals("superar obstáculos", updated["tema"])
        assertEquals("pop", updated["estilo"])
        assertEquals("ambiente", BardoIntentRouter.nextRequiredField(updated))
    }
}
