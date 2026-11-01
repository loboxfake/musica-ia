package com.musicaia.modulos

import android.content.Context
import android.util.Log
import com.musicaia.modelo.*
import java.io.FileOutputStream

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
        // Generar WAV de voz sintética (2 segundos) a 44100 Hz
        val ruta = generarWavSilencio(context)
        return Entrada.Audio(ruta, 44100)
    }

    override fun descargar() {
        cargado = false
    }

    private fun generarWavSilencio(context: Context): String {
        val sampleRate = 44100
        val duracionSeg = 2
        val numMuestras = sampleRate * duracionSeg
        val archivo = java.io.File(context.cacheDir, "voz_silencio.wav")
        
        try {
            val fos = FileOutputStream(archivo)
            // Cabecera WAV
            fos.write("RIFF".toByteArray())
            fos.write(intToBytes(36 + numMuestras * 2))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16))
            fos.write("PCM".toByteArray())
            fos.write(intToBytes(1)) // canales (mono)
            fos.write(intToBytes(44100)) // sample rate
            fos.write(intToBytes(44100 * 2)) // byte rate
            fos.write(shortToBytes(2)) // block align
            fos.write(shortToBytes(1)) // bits per sample
            fos.write("data".toByteArray())
            fos.write(intToBytes(numMuestras * 2))
            for (i in 0 until numMuestras) {
                fos.write(shortToBytes(0))
            }
            fos.close()
        } catch (e: Exception) {
            Log.e("ModuloVozStub", "Error generando WAV", e)
        }
        return archivo.absolutePath
    }
}
