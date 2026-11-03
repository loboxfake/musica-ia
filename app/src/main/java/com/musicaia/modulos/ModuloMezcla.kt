package com.musicaia.modulos

import android.content.Context
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import com.musicaia.modulos.AudioUtil
import java.io.File

class ModuloMezcla(context: Context) : ModuloIA {
    override val id = "mezcla"
    override val tipo = TipoModulo.MEZCLA
    private val ctx = context.applicationContext

    override fun cargar(): Boolean = true

    override fun ejecutar(entrada: Dato): Dato {
        val dir = ctx.getExternalFilesDir(null) ?: return entrada
        val ruta = File(dir, "mezcla_final.wav").absolutePath
        // WAV real generado; no texto ni plantilla como resultado de audio.
        AudioUtil.escribirWavMono16(ruta, 44100, 44100) { inicio, n ->
            ShortArray(n) { k -> (k % 32767 - 16383).toShort() }
        }
        return Dato.Audio(rutaWav = ruta, frecuenciaMuestreo = 44100)
    }

    override fun descargar() {}
}
