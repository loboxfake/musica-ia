package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloPortada(context: Context) : ModuloIA {
    override val id = "portada"
    override val tipo = TipoModulo.PORTADA

    private val ctx = context.applicationContext

    override fun cargar(): Boolean {
        // Modelo pequeño de imagen en CPU; comprobar RAM y tiempo antes de cargar
        return true
    }

    override fun ejecutar(entrada: Dato): Dato {
        val ruta = ctx.getExternalFilesDir(null)?.path + "/portada.png"
        return Dato.Imagen(rutaPng = ruta)
    }

    override fun descargar() {}
}
