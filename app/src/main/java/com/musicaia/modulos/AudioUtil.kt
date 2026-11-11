package com.musicaia.modulos

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioUtil {
    fun escribirWavMono16(
        ruta: String,
        frecuencia: Int,
        totalMuestras: Int,
        generador: (inicio: Int, cantidad: Int) -> ShortArray
    ) {
        require(frecuencia in 8_000..384_000) { "Frecuencia de muestreo no válida." }
        require(totalMuestras > 0) { "El WAV debe contener al menos una muestra." }
        val bytesDatosLong = totalMuestras.toLong() * 2L
        require(bytesDatosLong <= Int.MAX_VALUE.toLong() - 36L) {
            "El WAV supera el tamaño PCM16 admitido."
        }
        val bytesDatos = bytesDatosLong.toInt()

        val cab = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        cab.put("RIFF".toByteArray(Charsets.US_ASCII))
        cab.putInt(36 + bytesDatos)
        cab.put("WAVE".toByteArray(Charsets.US_ASCII))
        cab.put("fmt ".toByteArray(Charsets.US_ASCII))
        cab.putInt(16)
        cab.putShort(1)
        cab.putShort(1)
        cab.putInt(frecuencia)
        cab.putInt(frecuencia * 2)
        cab.putShort(2)
        cab.putShort(16)
        cab.put("data".toByteArray(Charsets.US_ASCII))
        cab.putInt(bytesDatos)

        val destino = File(ruta)
        destino.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "No se pudo crear el directorio de salida WAV." }
        }
        BufferedOutputStream(FileOutputStream(destino)).use { salida ->
            salida.write(cab.array())
            var escritas = 0
            while (escritas < totalMuestras) {
                val n = minOf(16_384, totalMuestras - escritas)
                val datos = generador(escritas, n)
                require(datos.size == n) {
                    "El generador devolvió " + datos.size + " muestras; se esperaban " + n + "."
                }
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(datos[i])
                salida.write(bb.array())
                escritas += n
            }
        }
    }
}
