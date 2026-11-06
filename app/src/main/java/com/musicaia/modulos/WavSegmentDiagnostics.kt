package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile

/** Inspección ligera de la cabecera RIFF sin cargar el PCM completo en memoria. */
object WavSegmentDiagnostics {
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val frames: Long,
        val dataBytes: Long,
        val durationSeconds: Double
    )

    fun inspect(file: File): Info {
        require(file.isFile && file.length() >= 44L) { "WAV inexistente o demasiado pequeño: ${file.name}" }
        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "Cabecera RIFF no válida: ${file.name}" }
            input.seek(8L)
            require(readAscii(input, 4) == "WAVE") { "Cabecera WAVE no válida: ${file.name}" }

            var format = -1
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataBytes = -1L
            var offset = 12L
            val fileLength = input.length()

            while (offset + 8L <= fileLength) {
                input.seek(offset)
                val chunk = readAscii(input, 4)
                val chunkSize = Integer.toUnsignedLong(readIntLe(input))
                val dataStart = offset + 8L
                val dataEnd = dataStart + chunkSize
                require(dataEnd <= fileLength) { "Chunk $chunk truncado en ${file.name}" }

                when (chunk) {
                    "fmt " -> {
                        require(chunkSize >= 16L) { "Chunk fmt incompleto en ${file.name}" }
                        format = readUnsignedShortLe(input)
                        channels = readUnsignedShortLe(input)
                        sampleRate = readIntLe(input)
                        readIntLe(input) // byte rate
                        readUnsignedShortLe(input) // block align
                        bitsPerSample = readUnsignedShortLe(input)
                    }
                    "data" -> if (dataBytes < 0L) dataBytes = chunkSize
                }

                offset = dataEnd + (chunkSize and 1L)
            }

            require(format == 1) { "El segmento no es PCM sin comprimir (format=$format)." }
            require(channels in 1..2) { "Número de canales no admitido: $channels." }
            require(sampleRate in 8_000..192_000) { "Frecuencia de muestreo no válida: $sampleRate Hz." }
            require(bitsPerSample == 16) { "Se esperaba PCM16; recibido $bitsPerSample bits." }
            require(dataBytes > 0L && dataBytes % (channels * 2L) == 0L) {
                "Bloque PCM vacío o desalineado en ${file.name}."
            }
            val frames = dataBytes / (channels * 2L)
            return Info(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                frames = frames,
                dataBytes = dataBytes,
                durationSeconds = frames.toDouble() / sampleRate.toDouble()
            )
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readIntLe(input: RandomAccessFile): Int {
        val a = input.read()
        val b = input.read()
        val c = input.read()
        val d = input.read()
        require(d >= 0) { "Cabecera WAV truncada." }
        return (a and 0xff) or ((b and 0xff) shl 8) or
            ((c and 0xff) shl 16) or ((d and 0xff) shl 24)
    }

    private fun readUnsignedShortLe(input: RandomAccessFile): Int {
        val a = input.read()
        val b = input.read()
        require(b >= 0) { "Cabecera WAV truncada." }
        return (a and 0xff) or ((b and 0xff) shl 8)
    }
}
