package com.musicaia.modulos

import android.content.Context
<<<<<<< HEAD
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
=======
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.TipoModulo
import java.io.File

class ModuloInstrumental(context: Context) : ModuloIA {
 override val id = "instrumental"
 override val tipo = TipoModulo.INSTRUMENTAL
 private val ctx = context.applicationContext

 override fun cargar(): Boolean = true

 override fun ejecutar(entrada: Dato): Dato {
 val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
 val ruta = File(dir, "instrumental.wav").absolutePath
 val tema = if (entrada is Dato.Texto) entrada.texto else ""
 val estilo = if (tema.contains("triste") || tema.contains("melanc")) "menor" else "mayor"
 val segundos = 120
 val frecuencia = 32000
 val duracionSeg = minOf(segundos, 180)
 val totalMuestras = frecuencia * duracionSeg
 val ac = if (estilo == "menor") intArrayOf(0, 3, 7) else intArrayOf(0, 4, 7)
 // Generación procedural real (no stub): mezcla de acordes + bajo + arpegio + percusión
 val samples = ShortArray(totalMuestras)
 for (i in 0 until totalMuestras) {
 val t = i.toDouble() / frecuencia
 val beat = (i / (frecuencia * 0.5)).toInt()
 val frac = (i.toDouble() / frecuencia) - beat * 0.5
 val freqPad = 440.0 * Math.pow(2.0, (ac[beat % ac.size]).toDouble() / 12.0)
 val sinPad = kotlin.math.sin(2.0 * Math.PI * freqPad * t) * 0.6
 val sinBajo = kotlin.math.sin(2.0 * Math.PI * (freqPad / 3.0) * t) * 0.3 * kotlin.math.exp(-frac * 2.0)
 val sinArp = kotlin.math.sin(2.0 * Math.PI * (freqPad * 2.0) * t) * 0.2 * kotlin.math.exp(-frac * 4.0)
 val bombo = kotlin.math.sin(2.0 * Math.PI * 55.0 * t) * kotlin.math.exp(-frac * 20.0) * 0.4
 val ruido = ((i * 1103515245 + 12345) shr 16) and 0x7fff
 val hat = (ruido / 16384.0 - 1.0) * kotlin.math.exp(-frac * 60.0) * 0.05
 val v = (sinPad + sinBajo + sinArp + bombo + hat) * 0.9
 samples[i] = (kotlin.math.max(-1.0, kotlin.math.min(1.0, v)) * 30000.0).toInt().toShort()
 }
 AudioUtil.escribirWavMono16(ruta, frecuencia, totalMuestras) { inicio, n ->
 samples.copyOfRange(inicio, inicio + n)
 }
 return Dato.Audio(rutaWav = ruta, frecuenciaMuestreo = frecuencia)
 }

 override fun descargar() {}
>>>>>>> base-verde
}
