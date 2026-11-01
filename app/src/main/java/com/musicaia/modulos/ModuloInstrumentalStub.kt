package com.musicaia.modulos

import android.content.Context
import android.util.Log
import com.musicaia.modelo.*
import java.io.FileOutputStream

class ModuloInstrumentalStub(private val context: Context) : ModuloIA {
    override val id = "instrumental-stub"
    override val tipo = TipoModulo.INSTRUMENTAL
    private var cargado = false

    override fun cargar(): Boolean {
        Thread.sleep(500)
        cargado = true
        return true
    }

    override fun ejecutar(entrada: Salida): Entrada {
        if (!cargado) throw IllegalStateException("Modelo no cargado")
        // Generar WAV de silencio 2 segundos a 44100 Hz
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
        val archivo = java.io.File(context.cacheDir, "instrumental_silencio.wav")
        
        try {
            val fos = FileOutputStream(archivo)
            // Escribir cabecera WAV simple (44 bytes) + datos (muestras de 16-bit = 2 bytes)
            // Cabecera RIFF
            fos.write("RIFF".toByteArray())
            // Tamaño total - 8
            val tamanoTotal = 36 + numMuestras * 2
            fos.write(intToBytes(tamanoTotal))
            fos.write("WAVE".toByteArray())
            // Chunk fmt
            fos.write("fmt ".toByteArray())
            fos.write(intToBytes(16)) // tamaño chunk fmt
            fos.write(shortToBytes(1)) // formato PCM
            fos.write(shortToBytes(1)) // canales (mono)
            fos.write(intToBytes(sampleRate)) // sample rate
            fos.write(intToBytes(sampleRate * 2)) // byte rate (sampleRate * canales * bits/8)
            fos.write(shortToBytes(2)) // block align (canales * bits/8)
            fos.write(shortToBytes(16)) // bits por muestra
            // Chunk data
            fos.write("data".toByteArray())
            fos.write(intToBytes(numMuestras * 2)) // tamaño data
            // Datos de silencio (ceros)
            for (i in 0 until numMuestras) {
                fos.write(shortToBytes(0))
            }
            fos.close()
        } catch (e: Exception) {
            Log.e("ModuloInstrumentalStub", "Error generando WAV", e)
        }
        return archivo.absolutePath
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v & 0xFF).toByte(),
        ((v >> 8) & 0xFF).toByte(),
        ((v >> 16) & 0xFF).toByte(),
        ((v >> 24) & 0xFF).toByte()
    )
    private fun shortToBytes(v: Int) = byteArrayOf(
        (v & 0xFF).toByte(),
        ((v >> 8) & 0xFF).toByte()
    )
}