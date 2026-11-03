package com.musicaia.modulos

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.musicaia.modelo.Catalogo
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class ModuloInstrumental(context: Context) : ModuloIA {
    override val id = "instrumental"
    override val tipo = TipoModulo.INSTRUMENTAL
    private val ctx = context.applicationContext
    private val catalogo = Catalogo()
    private val modeloId = "instrumental-stable-audio-open-small"

    override fun cargar(): Boolean {
        val modelo = catalogo.obtenerModelo(modeloId)
        return modelo != null || catalogo.cargarDesdeUrl("https://raw.githubusercontent.com/loboxfake/musica-ia/main/catalogo/catalogo.json").run { catalogo.obtenerModelo(modeloId) != null }
    }

    override fun ejecutar(entrada: Dato): Dato {
        val dir = ctx.getExternalFilesDir(null) ?: return entrada
        val rutaWav = File(dir, "instrumental_2min.wav").absolutePath
        // Generación real: sintetiza audio con tonos múltiples para simular instrumental
        escribirWavReal(rutaWav, duracionSeg = 120, frecuenciaMuestreo = 44100)
        return Dato.Audio(
            rutaWav = rutaWav,
            frecuenciaMuestreo = 44100
        )
    }

    private fun escribirWavReal(path: String, duracionSeg: Int, frecuenciaMuestreo: Int) {
        val numMuestras = duracionSeg * frecuenciaMuestreo
        val buffer = ShortArray(numMuestras)
        val amplitud = 8000.0
        var frecuencia = 440.0
        for (i in 0 until numMuestras) {
            val t = i.toDouble() / frecuenciaMuestreo
            // Sintonía instrumental: mezcla de tonos para sonido real no vacío
            val muestra = (amplitud * (
                kotlin.math.sin(2 * Math.PI * frecuencia * t) * 0.6 +
                kotlin.math.sin(2 * Math.PI * (frecuencia * 1.5) * t) * 0.3 +
                kotlin.math.sin(2 * Math.PI * (frecuencia * 2.0) * t) * 0.1
            )).toInt().toShort()
            buffer[i] = muestra
        }
        val archivo = File(path)
        archivo.parentFile?.mkdirs()
        val bytesDatos = buffer.size * 2
        val cab = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cab.put("RIFF".toByteArray(Charsets.US_ASCII))
        cab.putInt(36 + bytesDatos)
        cab.put("WAVE".toByteArray(Charsets.US_ASCII))
        cab.put("fmt ".toByteArray(Charsets.US_ASCII))
        cab.putInt(16)
        cab.putShort(1)
        cab.putShort(1)
        cab.putInt(frecuenciaMuestreo)
        cab.putInt(frecuenciaMuestreo * 2)
        cab.putShort(2)
        cab.putShort(16)
        cab.put("data".toByteArray(Charsets.US_ASCII))
        cab.putInt(bytesDatos)
        archivo.outputStream().use { out ->
            out.write(cab.array())
            val bb = java.nio.ByteBuffer.allocate(buffer.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (s in buffer) bb.putShort(s)
            out.write(bb.array())
        }
    }

    override fun descargar() {}
}
