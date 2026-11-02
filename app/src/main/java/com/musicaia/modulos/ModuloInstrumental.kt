package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo

class ModuloInstrumental(context: Context) : ModuloIA {
    override val id = "instrumental"
    override val tipo = TipoModulo.INSTRUMENTAL
    private val ctx = context.applicationContext

    override fun cargar(): Boolean {
        return true
    }

    override fun ejecutar(entrada: Dato): Dato {
        val rutaWav = ctx.getExternalFilesDir(null)?.path + "/instrumental.wav"
        return Dato.Audio(
            rutaWav = rutaWav,
            frecuenciaMuestreo = 44100
        )
    }

    override fun descargar() {}
}
