package com.musicaia.modulos

import android.content.Context
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
}
