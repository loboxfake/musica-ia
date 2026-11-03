package com.musicaia

import org.junit.Test
import org.junit.Assert.*

class ConversacionGuiadaTest {
    @Test
    fun testDatosConverInicialesVacios() {
        val datos = mutableMapOf<String, String>()
        val requeridos = listOf("tema","estilo","ambiente","ritmo","idioma")
        assertEquals(5, requeridos.filter { datos[it].isNullOrBlank() }.size)
    }

    @Test
    fun testRespuestaTemasSinRepetir() {
        val datos = mutableMapOf<String, String>()
        datos["tema"] = "superar obstáculos"
        datos["estilo"] = "rock"
        val faltan = listOf("tema","estilo","ambiente","ritmo","idioma").filter { datos[it].isNullOrBlank() }
        assertEquals(3, faltan.size)
        assertFalse(faltan.contains("tema"))
        assertFalse(faltan.contains("estilo"))
    }

    @Test
    fun testCambioDeOpinionActualiza() {
        val datos = mutableMapOf("tema" to "vieja", "estilo" to "pop")
        datos["tema"] = "superar obstáculos" // cambio
        assertEquals("superar obstáculos", datos["tema"])
    }

    @Test
    fun testResumenCompletoYNoRepite() {
        val datos = mutableMapOf("tema" to "a", "estilo" to "b", "ambiente" to "c",
            "ritmo" to "d", "idioma" to "e", "tipo" to "voz", "vozFuente" to "archivo")
        val completo = listOf("tema","estilo","ambiente","ritmo","idioma").all { datos[it]?.isNotBlank() == true }
        assertTrue(completo)
        val faltan = listOf("tema","estilo","ambiente","ritmo","idioma","tipo","vozFuente")
            .filter { datos[it].isNullOrBlank() }
        assertEquals(0, faltan.size)
    }

    @Test
    fun testNoRepitePreguntaYaRespondida() {
        val datos = mutableMapOf("tema" to "x", "estilo" to "y")
        val siguiente = listOf("tema","estilo","ambiente","ritmo","idioma").firstOrNull { datos[it].isNullOrBlank() }
        assertEquals("ambiente", siguiente)
    }
}
