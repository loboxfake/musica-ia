package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile

data class BardoWavHeader(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataBytes: Long,
    val dataOffset: Long = 0L
)

/**
 * Validación estricta de RIFF/WAVE PCM16 sin cargar muestras a memoria.
 * Cada chunk queda limitado al tamaño declarado por RIFF y se verifican
 * blockAlign/byteRate para impedir cabeceras internamente contradictorias.
 */
object BardoWavHeaderValidator {
    fun inspect(file: File): BardoWavHeader {
        require(file.isFile && file.length() >= 44L) {
            "El archivo WAV es demasiado pequeño."
        }

        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "La cabecera no contiene RIFF." }
            val riffSize = readUInt32LE(input)
            require(riffSize >= 4L) { "El tamaño RIFF no es válido." }
            val riffEnd = 8L + riffSize
            require(riffEnd <= file.length()) {
                "El tamaño RIFF declara datos fuera del archivo."
            }
            require(readAscii(input, 4) == "WAVE") { "La cabecera no contiene WAVE." }

            var format = -1
            var channels = 0
            var sampleRate = 0L
            var byteRate = 0L
            var blockAlign = 0
            var bitsPerSample = 0
            var dataBytes = -1L
            var dataOffset = -1L
            var offset = 12L

            while (offset < riffEnd) {
                require(riffEnd - offset >= 8L) {
                    "La cabecera de un chunk WAV queda fuera del límite RIFF."
                }
                input.seek(offset)
                val chunkId = readAscii(input, 4)
                val chunkSize = readUInt32LE(input)
                val chunkStart = input.filePointer
                require(chunkSize <= riffEnd - chunkStart) {
                    "El chunk $chunkId excede el tamaño declarado por RIFF."
                }
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd <= file.length()) {
                    "El chunk $chunkId declara datos fuera del archivo."
                }

                when (chunkId) {
                    "fmt " -> {
                        require(format == -1) { "El WAV contiene más de un chunk fmt." }
                        require(chunkSize >= 16L) { "El bloque fmt está incompleto." }
                        format = readUInt16LE(input)
                        channels = readUInt16LE(input)
                        sampleRate = readUInt32LE(input)
                        byteRate = readUInt32LE(input)
                        blockAlign = readUInt16LE(input)
                        bitsPerSample = readUInt16LE(input)
                    }
                    "data" -> {
                        // RIFF permite que data aparezca antes de fmt. Guardamos su
                        // ubicación y validamos la alineación cuando ya conocemos el formato.
                        require(dataBytes < 0L) { "El WAV contiene más de un chunk data." }
                        dataBytes = chunkSize
                        dataOffset = chunkStart
                    }
                }

                val nextOffset = chunkEnd + (chunkSize and 1L)
                require(nextOffset <= riffEnd) {
                    "Falta el byte de relleno de un chunk WAV dentro de RIFF."
                }
                offset = nextOffset
            }

            require(offset == riffEnd) { "El bloque RIFF no termina en un límite de chunk." }
            require(format == 1 && bitsPerSample == 16) {
                "Se necesita WAV PCM lineal de 16 bits."
            }
            require(channels in 1..8 && sampleRate in 8_000L..192_000L) {
                "Número de canales o frecuencia WAV no compatible."
            }
            val expectedBlockAlign = channels * (bitsPerSample / 8)
            require(blockAlign == expectedBlockAlign) {
                "El campo blockAlign no coincide con canales y profundidad."
            }
            require(byteRate == sampleRate * expectedBlockAlign.toLong()) {
                "El campo byteRate no coincide con frecuencia y blockAlign."
            }
            require(dataBytes > 0L && dataBytes % expectedBlockAlign == 0L) {
                "El bloque de audio WAV no contiene muestras PCM completas."
            }

            return BardoWavHeader(
                sampleRate = sampleRate.toInt(),
                channels = channels,
                bitsPerSample = bitsPerSample,
                dataBytes = dataBytes,
                dataOffset = dataOffset
            )
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
