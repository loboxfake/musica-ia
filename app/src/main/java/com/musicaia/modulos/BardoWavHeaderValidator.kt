package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile

data class BardoWavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataBytes: Long
)

/** Validación de cabecera WAV PCM16 sin cargar el archivo completo en RAM. */
object BardoWavHeaderValidator {
    fun inspect(file: File): BardoWavHeader {
        require(file.isFile && file.length() >= 44L) { "El archivo WAV es demasiado pequeño." }
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "La cabecera no contiene RIFF." }
            val riffSize = readUInt32LE(input)
            require(readAscii(input, 4) == "WAVE") { "La cabecera no contiene WAVE." }
            require(riffSize >= 36L && riffSize + 8L <= file.length()) {
                "El tamaño RIFF no coincide con el archivo."
            }

            var format: Int? = null
            var channels: Int? = null
            var sampleRate: Int? = null
            var bits: Int? = null
            while (input.filePointer + 8L <= file.length()) {
                val chunkId = readAscii(input, 4)
                val chunkSize = readUInt32LE(input)
                val chunkStart = input.filePointer
                require(chunkSize <= file.length() - chunkStart) {
                    "Un bloque WAV declara datos fuera del archivo."
                }
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "El bloque fmt está incompleto." }
                        format = readUInt16LE(input)
                        channels = readUInt16LE(input)
                        sampleRate = readUInt32LE(input).toInt()
                        readUInt32LE(input) // byte rate
                        readUInt16LE(input) // block align
                        bits = readUInt16LE(input)
                    }
                    "data" -> {
                        val fmt = format ?: throw IllegalArgumentException("El bloque fmt debe preceder al audio.")
                        val channelCount = channels ?: 0
                        val rate = sampleRate ?: 0
                        val bitDepth = bits ?: 0
                        require(fmt == 1 && bitDepth == 16) {
                            "Se necesita WAV PCM lineal de 16 bits."
                        }
                        require(channelCount in 1..8 && rate in 8000..192000) {
                            "Número de canales o frecuencia WAV no compatible."
                        }
                        require(chunkSize > 0L && chunkSize % (channelCount * 2L) == 0L) {
                            "El bloque de audio WAV no contiene muestras completas."
                        }
                        return BardoWavHeader(rate, channelCount, bitDepth, chunkSize)
                    }
                }
                input.seek(chunkStart + chunkSize + (chunkSize and 1L))
            }
            throw IllegalArgumentException("No se encontró el bloque de audio WAV.")
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt16LE(input: RandomAccessFile): Int =
        java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(input.readShort()))

    private fun readUInt32LE(input: RandomAccessFile): Long =
        java.lang.Integer.toUnsignedLong(java.lang.Integer.reverseBytes(input.readInt()))
}
