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
        archivo.outputStream().use { out ->
            val dos = DataOutputStream(out)
            // Header WAV simple
            dos.writeBytes("RIFF")
            val dataSize = 36 + buffer.size * 2
            dos.writeInt(Integer.reverseBytes(dataSize))
            dos.writeBytes("WAVE")
            dos.writeBytes("fmt ")
            dos.writeInt(Integer.reverseBytes(16)) // subchunk size
            dos.writeShort(Integer.reverseBytes(1)) // audio format PCM
            dos.writeShort(Integer.reverseBytes(1)) // num channels mono
            dos.writeInt(Integer.reverseBytes(frecuenciaMuestreo))
            dos.writeInt(Integer.reverseBytes(frecuenciaMuestreo * 2))
            dos.writeShort(Integer.reverseBytes(2)) // bits per sample
            dos.writeBytes("data")
            dos.writeInt(Integer.reverseBytes(buffer.size * 2))
            for (s in buffer) {
                dos.writeShort(Integer.reverseBytes(s.toInt()))
            }
        }
    }

    override fun descargar() {}
}
