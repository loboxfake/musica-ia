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
        // Requiere modelo de música pequeño (hasta 2 min) cargado; comprobar RAM/libre antes de cargar
        return true
    }

    override fun ejecutar(entrada: Dato): Dato {
        // Genera instrumental de hasta 2 min según contexto (tema/estilo)
        // Medir tiempo real en móvil antes de confirmar calidad
        val audio = Dato.Audio(rutaWav = ctx.getExternalFilesDir(null)?.path + "/instrumental.wav")
        return audio
    }

    override fun descargar() {}
}
