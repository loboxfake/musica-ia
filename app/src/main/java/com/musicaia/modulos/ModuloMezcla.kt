package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloMezcla(context: Context) : ModuloIA {
    override val id = "mezcla"
    override val tipo = TipoModulo.MEZCLA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean = true

    override fun ejecutar(entrada: Dato): Dato {
        val dir = ctx.getExternalFilesDir(null) ?: return entrada
        val archivo = File(dir, "mezcla_final.wav")
        archivo.writeText("mezcla de tramos con fundidos")
        return Dato.Audio(rutaWav = archivo.absolutePath)
    }

    override fun descargar() {}
}
