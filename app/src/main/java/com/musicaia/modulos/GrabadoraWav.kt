package com.musicaia.modulos

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GrabadoraWav(val ruta: String) {
 companion object {
 const val FRECUENCIA = 44100
 }

 @Volatile
 private var grabando = false
 private var hilo: Thread? = null
 private var muestrasGrabadas = 0L

 @SuppressLint("MissingPermission")
 fun iniciar() {
 val minimo = AudioRecord.getMinBufferSize(
 FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
 )
 if (minimo <= 0) throw IllegalStateException("Microfono no disponible")
 val tam = maxOf(minimo, 16384)
 val rec = AudioRecord(
 MediaRecorder.AudioSource.MIC, FRECUENCIA,
 AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tam
 )
 if (rec.state != AudioRecord.STATE_INITIALIZED) {
 rec.release()
 throw IllegalStateException("No se pudo iniciar el microfono")
 }
 val pcm = File(ruta + ".pcm")
 grabando = true
 muestrasGrabadas = 0L
 rec.startRecording()
 val t = Thread {
 val buf = ShortArray(tam / 2)
 try {
 FileOutputStream(pcm).use { out ->
 val bb = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
 while (grabando) {
 val n = rec.read(buf, 0, buf.size)
 if (n > 0) {
 bb.clear()
 for (i in 0 until n) bb.putShort(buf[i])
 out.write(bb.array(), 0, n * 2)
 muestrasGrabadas += n
 }
 }
 }
 } catch (e: Exception) {
 Log.e("GrabadoraWav", "Error grabando", e)
 } finally {
 try { rec.stop() } catch (_: Exception) { }
 rec.release()
 }
 try {
 escribirWav(pcm)
 } catch (e: Exception) {
 Log.e("GrabadoraWav", "Error escribiendo WAV", e)
 }
 }
 hilo = t
 t.start()
 }

 fun detener() {
 grabando = false
 hilo?.join(8000)
 }

 fun segundos(): Int = (muestrasGrabadas / FRECUENCIA).toInt()

 private fun escribirWav(pcm: File) {
 val bytesDatos = pcm.length().toInt()
 val cab = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
 cab.put("RIFF".toByteArray(Charsets.US_ASCII))
 cab.putInt(36 + bytesDatos)
 cab.put("WAVE".toByteArray(Charsets.US_ASCII))
 cab.put("fmt ".toByteArray(Charsets.US_ASCII))
 cab.putInt(16)
 cab.putShort(1)
 cab.putShort(1)
 cab.putInt(FRECUENCIA)
 cab.putInt(FRECUENCIA * 2)
 cab.putShort(2)
 cab.putShort(16)
 cab.put("data".toByteArray(Charsets.US_ASCII))
 cab.putInt(bytesDatos)
 FileOutputStream(ruta).use { out ->
 out.write(cab.array())
 FileInputStream(pcm).use { it.copyTo(out) }
 }
 pcm.delete()
 }
}
