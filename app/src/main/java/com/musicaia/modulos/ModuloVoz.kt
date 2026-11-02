package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Catalogo
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloVoz(context: Context) : ModuloIA {
    override val id = "voz"
    override val tipo = TipoModulo.VOZ
    private val ctx = context.applicationContext
    private val catalogo = Catalogo()
    private val modeloId = "musica-voz-v1"

    override fun cargar(): Boolean {
        val modelo = catalogo.obtenerModelo(modeloId)
        return modelo != null && modelo.tamanoMb < 2048
    }

    override fun ejecutar(entrada: Dato): Dato {
        val ruta = File(ctx.getExternalFilesDir(null), "voz_cantada.wav").absolutePath
        return Dato.Audio(rutaWav = ruta, frecuenciaMuestreo = 44100)
    }

    override fun descargar() {}
}
