package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPcm(val muestras: ShortArray, val frecuencia: Int)

object LectorWav {
 fun leer(ruta: String): AudioPcm {
 val bytes = File(ruta).readBytes()
 if (bytes.size < 44) throw IllegalArgumentException("archivo demasiado pequeño")
 val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
 fun texto(pos: Int) = String(bytes, pos, 4, Charsets.US_ASCII)
 if (texto(0) != "RIFF" || texto(8) != "WAVE") throw IllegalArgumentException("no es un WAV")
 var pos = 12
 var canales = 0
 var frecuencia = 0
 var bits = 0
 var formato = 0
 while (pos + 8 <= bytes.size) {
 val id = texto(pos)
 val tam = bb.getInt(pos + 4)
 val inicio = pos + 8
 if (tam < 0 && id != "data") break
 if (id == "fmt ") {
 formato = bb.getShort(inicio).toInt()
 canales = bb.getShort(inicio + 2).toInt()
 frecuencia = bb.getInt(inicio + 4)
 bits = bb.getShort(inicio + 14).toInt()
 } else if (id == "data") {
 if (formato != 1 || bits != 16 || canales < 1 || frecuencia <= 0) {
 throw IllegalArgumentException("se necesita WAV PCM de 16 bits")
 }
 val declarados = tam.toLong() and 0xffffffffL
 val disponibles = minOf(declarados, (bytes.size - inicio).toLong()).toInt()
 val total = disponibles / 2 / canales
 val muestras = ShortArray(total)
 for (i in 0 until total) {
 var suma = 0
 for (c in 0 until canales) {
 suma += bb.getShort(inicio + (i * canales + c) * 2).toInt()
 }
 muestras[i] = (suma / canales).toShort()
 }
 return AudioPcm(muestras, frecuencia)
 }
 pos = inicio + tam + (tam and 1)
 }
 throw IllegalArgumentException("no se encontro el audio dentro del WAV")
 }
}

object MezclaVoz {
 private const val GANANCIA_VOZ = 1.0f
 private const val GANANCIA_INSTRUMENTAL = 0.5f

 fun mezclar(rutaVoz: String, rutaInstrumental: String, rutaSalida: String) {
 val voz = LectorWav.leer(rutaVoz)
 val ins = LectorWav.leer(rutaInstrumental)
 val frec = ins.frecuencia
 val razon = voz.frecuencia.toDouble() / frec
 val nVoz = (voz.muestras.size / razon).toInt()
 val total = maxOf(ins.muestras.size, nVoz)
 val mezcla = FloatArray(total)
 for (i in 0 until total) {
 var v = 0f
 if (i < nVoz) {
 val p = i * razon
 val i0 = p.toInt()
 val f = (p - i0).toFloat()
 val a = voz.muestras[i0].toFloat()
 val b = if (i0 + 1 < voz.muestras.size) voz.muestras[i0 + 1].toFloat() else a
 v = a + (b - a) * f
 }
 val m = if (i < ins.muestras.size) ins.muestras[i].toFloat() else 0f
 mezcla[i] = v * GANANCIA_VOZ + m * GANANCIA_INSTRUMENTAL
 }
 var pico = 0f
 for (x in mezcla) {
 val ax = kotlin.math.abs(x)
 if (ax > pico) pico = ax
 }
 val escala = if (pico > 29000f) 29000f / pico else 1f
 AudioUtil.escribirWavMono16(rutaSalida, frec, total) { inicio, n ->
 ShortArray(n) { k -> (mezcla[inicio + k] * escala).toInt().toShort() }
 }
 }
}
