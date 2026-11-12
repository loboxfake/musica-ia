package com.musicaia

import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.Orquestador
import com.musicaia.modelo.TipoModulo
import com.musicaia.modulos.ModuloLetraStub
import org.junit.Assert.*
import org.junit.Test

class OrquestadorTest {
    @Test
    fun testCicloBasicoConStubs() {
        val modulo = ModuloLetraStub()
        assertTrue("El stub debe cargar", modulo.cargar())
        assertNotNull(modulo.id)
        assertEquals(TipoModulo.LETRA, modulo.tipo)
        val salida = modulo.ejecutar(Dato.Texto("prueba"))
        assertTrue(salida is Dato.Texto || salida is Dato.Audio || salida is Dato.Imagen)
        modulo.descargar()
    }

    @Test
    fun testModuloAnteriorSeDescargaAntesDeCargarElSiguiente() {
        val primero = TrackingModule("primero")
        val segundo = TrackingModule("segundo", beforeLoad = { assertTrue(primero.descargado) })
        val resultados = Orquestador().ejecutarModulos(
            listOf(primero, segundo),
            Dato.Texto("entrada")
        )
        assertTrue(primero.descargado)
        assertTrue(segundo.descargado)
        assertEquals(2, resultados.size)
    }

    @Test
    fun testDescargaSeEjecutaAunqueFalleLaEjecucion() {
        val modulo = TrackingModule("fallo", failOnExecute = true)
        try {
            Orquestador().ejecutarModulos(listOf(modulo), Dato.Texto("entrada"))
            fail("Debía propagarse el error del módulo.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("fallo"))
        }
        assertTrue("El módulo debe liberar recursos tras un error", modulo.descargado)
    }

    @Test
    fun testDescargaSeEjecutaCuandoCargaDevuelveFalse() {
        val modulo = TrackingModule("no-carga", loadResult = false)
        try {
            Orquestador().ejecutarModulos(listOf(modulo), Dato.Texto("entrada"))
            fail("Una carga fallida debe detener el plan.")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("no pudo cargarse"))
        }
        assertTrue(modulo.descargado)
    }

    @Test
    fun queuedCallbacksFromPreviousExecutionAreDiscarded() {
        val pendingCallbacks = mutableListOf<() -> Unit>()
        val orchestrator = Orquestador { callback -> pendingCallbacks += callback }
        val previousListener = CountingListener()
        val currentListener = CountingListener()

        orchestrator.setListener(previousListener)
        orchestrator.ejecutarModulos(
            listOf(TrackingModule("anterior")),
            Dato.Texto("entrada")
        )

        orchestrator.setListener(currentListener)
        orchestrator.ejecutarModulos(
            listOf(TrackingModule("actual")),
            Dato.Texto("entrada")
        )

        pendingCallbacks.forEach { it() }

        assertEquals("Los callbacks antiguos no deben alcanzar al listener anterior.", 0, previousListener.callbackCount)
        assertTrue("La ejecución actual debe notificar a su listener.", currentListener.callbackCount > 0)
    }

    private class CountingListener : Orquestador.ProgresoListener {
        var callbackCount = 0

        override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
            callbackCount++
        }

        override fun onCompletado(resultados: List<Pair<String, Dato>>) {
            callbackCount++
        }

        override fun onError(mensaje: String) {
            callbackCount++
        }
    }

    @Test
    fun testTiposModuloEnum() {
        assertEquals(5, TipoModulo.values().size)
        assertTrue(TipoModulo.values().contains(TipoModulo.MEZCLA))
    }

    private class TrackingModule(
        override val id: String,
        private val loadResult: Boolean = true,
        private val failOnExecute: Boolean = false,
        private val beforeLoad: () -> Unit = {}
    ) : ModuloIA {
        override val tipo: TipoModulo = TipoModulo.LETRA
        var descargado = false

        override fun cargar(): Boolean {
            beforeLoad()
            return loadResult
        }

        override fun ejecutar(entrada: Dato): Dato {
            if (failOnExecute) throw IllegalStateException("Error de prueba en $id")
            val value = (entrada as Dato.Texto).texto
            return Dato.Texto("$value-$id")
        }

        override fun descargar() {
            descargado = true
        }
    }
}
