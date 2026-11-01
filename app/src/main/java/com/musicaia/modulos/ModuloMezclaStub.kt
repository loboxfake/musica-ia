package com.musicaia.modulos

import android.content.Context
import android.util.Log
import com.musicaia modeled
import java.io.FileOutputStream

class ModuloMezclaStub(private val context: Context) : ModuloIA {
    override val id = "mezcla-stub"
    override val tipo = TipoModulo.MEZCLA
    private var cargado = false

    override fun cargar(): Boolean {
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Salida): Entrada {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        // Generar WAV de mezcla (3 segundos) a 44100 Hz
        val ruta = generarWavSilencio(context)
        return Entrada.Audio(ruta, 44100)
    }

    override fun descargar() {
        cargado = false
    }

    private fun generarWavSilencio(context: Context): String {
        val sampleRate = 44100
        val duracionSeg = 3
        val numMuestras = sampleRate * duracionSeg
        val archivo = java.io.File(context.cacheDir, "mezcla_silencio.wav")
        
        try {
            val fos = FileOutputStream(archivo)
            fos.write("RIFF".toByteArray())
            fos.write(intToBytes(36 + numMuestras * 2))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16))
            fos.write(shortToBytes(1))
            fos.write(shortToBytes(1))
            fos.write(intToBytes(44100))
            fos.write(intToBytes(44100 * 2))
            fos.write(shortToBytes(2))
            fos.write(shortToBytes(16))
            fos.write("data".toByteArray())
            fos.write(intToBytes(numMuestras * 2))
            for (i in 0 until numMuestras) {
                fos.write(shortToBytes(0))
            }
            fos.close()
        } catch (e: Exception) {
            Log.e("ModuloMezclaStub", "Error generando WAV", e)
        }
        return archivo.absolutePath
    }
}