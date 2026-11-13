package com.musicaia.modulos

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun escribirWavSilencio(ruta: String, segundos: Int, frecuencia: Int = 44100) {
    val numMuestras = frecuencia * segundos
    val archivo = java.io.File(ruta)
    archivo.parentFile?.mkdirs()

    val fos = FileOutputStream(archivo)
    val buffer = ByteBuffer.allocate(44 + numMuestras * 2)
    buffer.order(ByteOrder.LITTLE_ENDIAN)

    // Chunk RIFF
    buffer.put("RIFF".toByteArray())
    buffer.putInt(36 + numMuestras * 2)
    buffer.put("WAVE".toByteArray())
    // fmt subchunk
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16) // Subchunk1Size
    buffer.putShort(1) // AudioFormat PCM
    buffer.putShort(1) // NumChannels mono
    buffer.putInt(frecuencia) // SampleRate
    buffer.putInt(frecuencia * 2) // ByteRate
    buffer.putShort(2) // BlockAlign
    buffer.putShort(16) // BitsPerSample
    // data subchunk
    buffer.put("data".toByteArray())
    buffer.putInt(numMuestras * 2)
    // Silencio
    for (i in 0 until numMuestras) {
        buffer.putShort(0)
    }

    fos.write(buffer.array())
    fos.close()
}