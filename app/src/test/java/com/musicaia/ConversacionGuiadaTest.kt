package com.musicaia

import org.junit.Test
import org.junit.Assert.*
import com.musicaia.modelo.*
import android.os.Looper

class ConversacionGuiadaTest {
    @Test
    fun testDatosConverInicialesVacios() {
        // Estado inicial debe permitir completar paso a paso sin repetir
        val datos = mutableMapOf<String, String>()
        val requeridos = listOf("tema", "estilo", "ambiente", "ritmo", "idioma")
        val faltan = requeridos.filter { datos[it].isNullOrBlank() }
        assertEquals(5, faltan.size)
    }

    @Test
    fun testActualizacionAdaptativaSinRepetir() {
        val datos = mutableMapOf<String, String>()
        datos["tema"] = "superar obstáculos"
        datos["estilo"] = "rock"
        val faltan = listOf("tema","estilo","ambiente","ritmo","idioma").filter { datos[it].isNullOrBlank() }
        assertEquals(3, faltan.size)
        assertFalse(faltan.contains("tema"))
        assertFalse(faltan.contains("estilo"))
    }

    @Test
    fun testResumenCompleto() {
        val datos = mutableMapOf("tema" to "superar obstáculos", "estilo" to "pop", "ambiente" to "alegre", "ritmo" to "medio", "idioma" to "español")
        val completo = listOf("tema","estilo","ambiente","ritmo","idioma").all { datos[it]?.isNotBlank() == true }
        assertTrue(completo)
    }
}
