package com.musicaia

import org.junit.Test
import org.junit.Assert.*
import com.musicaia.modelo.*

class OrquestadorTest {
    @Test
    fun testCicloBasicoConStubs() {
        val orquestador = Orquestador()
        val modulo = com.musicaia.modulos.ModuloLetraStub()
        // Prueba: el módulo debe cargar correctamente
        assertTrue("El stub debe cargar", modulo.cargar())
        assertNotNull(modulo.id)
        assertEquals(TipoModulo.LETRA, modulo.tipo)
        // Ejecutar con entrada de texto
        val entrada = Dato.Texto("prueba")
        val salida = modulo.ejecutar(Dato.Texto("prueba"))
        assertTrue(salida is Dato.Texto || salida is Dato.Audio || salida is Dato.Imagen)
        modulo.descargar()
    }

    @Test
    fun testTiposModuloEnum() {
        assertEquals(5, TipoModulo.values().size)
        assertTrue(TipoModulo.values().contains(TipoModulo.MEZCLA))
    }
}