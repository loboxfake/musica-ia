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
        val entrada = Entrada.Texto("prueba")
        val salida = modulo.ejecutar(Entrada.Texto("prueba"))
        assertTrue(salida is Entrada.Texto || salida is Entrada.Audio || salida is Entrada.Imagen)
        modulo.descargar()
    }

    @Test
    fun testTiposModuloEnum() {
        assertEquals(5, TipoModulo.values().size)
        assertTrue(TipoModulo.values().contains(TipoModulo.MEZCLA))
    }
}