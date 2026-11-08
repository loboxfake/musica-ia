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
        assertEquals("español", parsed["idioma"])
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
        assertEquals("español", parsed["idioma"])
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
        assertEquals("español", parsed["idioma"])
        assertEquals("solo instrumental", parsed["tipo"])
        assertEquals("ninguna", parsed["vozFuente"])
    }

    @Test
    fun doesNotInferCreativeFieldsFromUnlabelledAmbiguousWords() {
        assertEquals(
            emptyMap<String, String>(),
            BardoIntentRouter.extractProvidedFields("He escuchado rock y me siento optimista.")
        )
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
