package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.*

class ModuloVozStub(private val context: Context) : ModuloIA {
    override val id = "voz-stub"
    override val tipo = TipoModulo.VOZ
    private var cargado = false

    override fun cargar(): Boolean {
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Salida): Entrada {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        val ruta = java.io.File(context.cacheDir, "voz_silencio.wav").absolutePath
        escribirWavSilencio(ruta, 2)
        return Entrada.Audio(ruta, 44100)
    }

    override fun descargar() {
        cargado = false
    }
}