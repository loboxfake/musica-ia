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
    fun completedFieldsCanBeChangedWithoutRestartingTheFlow() {
        val original = mapOf("tema" to "historia antigua", "estilo" to "pop")
        val updated = original + ("tema" to "superar obstáculos")
        assertEquals("superar obstáculos", updated["tema"])
        assertEquals("pop", updated["estilo"])
        assertEquals("ambiente", BardoIntentRouter.nextRequiredField(updated))
    }
}
