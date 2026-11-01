package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.*

class ModuloInstrumentalStub(private val context: Context) : ModuloIA {
    override val id = "instrumental-stub"
    override val tipo = TipoModulo.INSTRUMENTAL
    private var cargado = false

    override fun cargar(): Boolean {
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Dato): Dato {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        val ruta = java.io.File(context.cacheDir, "instrumental_silencio.wav").absolutePath
        escribirWavSilencio(ruta, 2)
        return Dato.Audio(ruta, 44100)
    }

    override fun descargar() {
        cargado = false
    }
}