package com.musicaia.modulos

import com.musicaia.modelo.*

class ModuloLetraStub : ModuloIA {
    override val id = "letra-stub"
    override val tipo = TipoModulo.LETRA
    private var cargado = false

    override fun cargar(): Boolean {
        // Simular carga de modelo
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Salida): Entrada {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        // Devuelve texto fijo
        val texto = "Verso 1: La noche brilla con luz de luna\n" +
                    "Coro: Bailamos hasta el amanecer\n" +
                    "Verso 2: El ritmo late en el corazón\n" +
                    "Coro: La música nos hace volar"
        return Entrada.Texto(texto)
    }

    override fun descargar() {
        cargado = false
    }
}