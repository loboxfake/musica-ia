package com.musicaia.modulos

import org.junit.Assert.assertEquals
import org.junit.Test

class ModuloLetraTest {
    @Test
    fun extractsManualLyricsBeforeSpanishCreationContext() {
        val input = "[LETRA_USUARIO]\nMi letra en español\n\n[CONTEXTO_CREACION]\nTema: esperanza. Estilo: pop."
        assertEquals("Mi letra en español", ModuloLetra.extractManualLyrics(input))
    }

    @Test
    fun extractsManualLyricsBeforeEnglishCreationContext() {
        val input = "[LETRA_USUARIO]\nMy own lyrics\n\n[CONTEXTO_CREACION]\nTheme: resilience. Style: rock."
        assertEquals("My own lyrics", ModuloLetra.extractManualLyrics(input))
    }

    @Test
    fun preservesMultipleVerseLinesAndBlankLinesInsideManualLyrics() {
        val input = "[LETRA_USUARIO]\nVerso uno\n\nVerso dos\n\n[CONTEXTO_CREACION]\nTema: avanzar."
        assertEquals("Verso uno\n\nVerso dos", ModuloLetra.extractManualLyrics(input))
    }

    @Test
    fun acceptsPreviouslyStoredSpanishAndEnglishContextDelimiters() {
        assertEquals(
            "Letra guardada",
            ModuloLetra.extractManualLyrics("[LETRA_USUARIO]\nLetra guardada\n\nTema: esperanza.")
        )
        assertEquals(
            "Stored lyrics",
            ModuloLetra.extractManualLyrics("[LETRA_USUARIO]\nStored lyrics\n\nTheme: hope.")
        )
    }
}
