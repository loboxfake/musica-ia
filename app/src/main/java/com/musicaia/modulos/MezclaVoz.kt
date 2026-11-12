package com.musicaia.modulos

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.musicaia.modelo.Dato

class AudioPcm(val muestras: ShortArray, val frecuencia: Int)

/**
 * Lector RIFF/WAVE PCM16 que analiza chunks con acceso aleatorio.
 * No copia el WAV completo a memoria antes de decodificar sus muestras.
 */
object LectorWav {
    private const val MAX_CHANNELS = 8
    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 384_000
    const val MAX_FILE_BYTES = 120L * 1024L * 1024L
    private const val MAX_FRAMES_PER_FILE = 32_000_000L

    fun leer(ruta: String): AudioPcm {
        val file = File(ruta)
        require(file.isFile) { "no existe el archivo WAV" }
        val fileLength = file.length()
        require(fileLength >= 44L) { "archivo demasiado pequeño" }
        require(fileLength <= MAX_FILE_BYTES) {
            "el WAV supera el límite de seguridad de 120 MiB"
        }

        RandomAccessFile(file, "r").use { input ->
            require(readAscii(input, 4) == "RIFF") { "cabecera RIFF no válida" }
            val riffSize = readUnsignedIntLe(input)
            require(riffSize >= 4L) { "tamaño RIFF no válido" }
            val riffEnd = 8L + riffSize
            require(riffEnd <= fileLength) { "el bloque RIFF está truncado" }

            input.seek(8L)
            require(readAscii(input, 4) == "WAVE") { "cabecera WAVE no válida" }

            var formato = -1
            var canales = 0
            var frecuencia = 0L
            var bits = 0
            var blockAlign = 0
            var byteRate = 0L
            var inicioDatos = -1L
            var tamDatos = -1L

            var pos = 12L
            while (pos < riffEnd) {
                require(riffEnd - pos >= 8L) { "cabecera de chunk WAV incompleta" }
                input.seek(pos)
                val id = readAscii(input, 4)
                val tamLong = readUnsignedIntLe(input)
                val inicioLong = pos + 8L
                val finLong = inicioLong + tamLong
                require(finLong <= riffEnd && finLong <= fileLength) {
                    "chunk WAV truncado o fuera de los límites RIFF: $id"
                }

                when (id) {
                    "fmt " -> {
                        require(formato == -1) { "el WAV contiene más de un chunk fmt" }
                        require(tamLong >= 16L) { "chunk fmt incompleto" }
                        input.seek(inicioLong)
                        formato = readUnsignedShortLe(input)
                        canales = readUnsignedShortLe(input)
                        frecuencia = readUnsignedIntLe(input)
                        byteRate = readUnsignedIntLe(input)
                        blockAlign = readUnsignedShortLe(input)
                        bits = readUnsignedShortLe(input)
                    }
                    "data" -> {
                        require(inicioDatos < 0L) { "el WAV contiene más de un chunk data" }
                        inicioDatos = inicioLong
                        tamDatos = tamLong
                    }
                }

                val siguiente = finLong + (tamLong and 1L)
                require(siguiente <= riffEnd) { "falta el byte de relleno de un chunk WAV" }
                pos = siguiente
            }
            require(pos == riffEnd) { "el bloque RIFF no termina en un límite de chunk" }

            require(formato == 1 && bits == 16) {
                "se necesita WAV PCM de 16 bits"
            }
            require(canales in 1..MAX_CHANNELS) { "número de canales no válido" }
            require(frecuencia in MIN_SAMPLE_RATE.toLong()..MAX_SAMPLE_RATE.toLong()) {
                "frecuencia de muestreo no válida"
            }
            val expectedBlockAlign = canales * 2
            require(blockAlign == expectedBlockAlign) { "alineación PCM no válida" }
            require(byteRate == frecuencia * expectedBlockAlign.toLong()) {
                "velocidad de bytes PCM no válida"
            }
            require(inicioDatos >= 0L && tamDatos > 0L) {
                "no se encontró audio PCM dentro del WAV"
            }
            require(tamDatos % expectedBlockAlign == 0L) {
                "el bloque de audio termina en un marco PCM incompleto"
            }

            val totalFrames = tamDatos / expectedBlockAlign
            require(totalFrames in 1L..MAX_FRAMES_PER_FILE) {
                "el WAV supera el máximo de muestras PCM admitido"
            }
            val muestras = ShortArray(totalFrames.toInt())
            input.seek(inicioDatos)
            val framesPorBloque = 8_192
            val bloqueBytes = ByteArray(framesPorBloque * expectedBlockAlign)
            var frameOffset = 0
            while (frameOffset < muestras.size) {
                val cantidadFrames = minOf(framesPorBloque, muestras.size - frameOffset)
                val cantidadBytes = cantidadFrames * expectedBlockAlign
                input.readFully(bloqueBytes, 0, cantidadBytes)
                var byteOffset = 0
                for (frame in 0 until cantidadFrames) {
                    var suma = 0L
                    for (canal in 0 until canales) {
                        val bajo = bloqueBytes[byteOffset++].toInt() and 0xff
                        val alto = bloqueBytes[byteOffset++].toInt()
                        suma += ((bajo or (alto shl 8)).toShort()).toLong()
                    }
                    muestras[frameOffset + frame] = (suma / canales).toShort()
                }
                frameOffset += cantidadFrames
            }
            return AudioPcm(muestras, frecuencia.toInt())
        }
    }

    private fun readAscii(input: RandomAccessFile, count: Int): String {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUnsignedShortLe(input: RandomAccessFile): Int {
        val a = input.read()
        val b = input.read()
        require(a >= 0 && b >= 0) { "cabecera WAV truncada" }
        return (a and 0xff) or ((b and 0xff) shl 8)
    }

    private fun readUnsignedIntLe(input: RandomAccessFile): Long {
        val a = input.read()
        val b = input.read()
        val c = input.read()
        val d = input.read()
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "cabecera WAV truncada" }
        return (a.toLong() and 0xffL) or
            ((b.toLong() and 0xffL) shl 8) or
            ((c.toLong() and 0xffL) shl 16) or
            ((d.toLong() and 0xffL) shl 24)
    }
}

object MezclaVoz {
    private const val GANANCIA_VOZ = 1.0f
    private const val GANANCIA_INSTRUMENTAL = 0.5f

    fun mezclar(
        rutaVoz: String,
        rutaInstrumental: String,
        rutaSalida: String,
        isCancellationRequested: () -> Boolean = { false }
    ): Dato.Audio {
        fun comprobarCancelacion() {
            if (isCancellationRequested()) {
                throw java.util.concurrent.CancellationException("La mezcla de voz fue cancelada.")
            }
        }
        comprobarCancelacion()
        val archivoVoz = File(rutaVoz)
        val archivoInstrumental = File(rutaInstrumental)
        val archivoSalida = File(rutaSalida)
        val salidaCanonica = archivoSalida.canonicalFile
        require(salidaCanonica != archivoVoz.canonicalFile &&
            salidaCanonica != archivoInstrumental.canonicalFile
        ) { "El archivo de salida no puede sobrescribir la voz ni el instrumental originales." }

        val voz = LectorWav.leer(rutaVoz)
        comprobarCancelacion()
        val ins = LectorWav.leer(rutaInstrumental)
        comprobarCancelacion()
        val frec = ins.frecuencia
        val razon = voz.frecuencia.toDouble() / frec
        val nVozLong = (voz.muestras.size.toDouble() / razon).toLong()
        require(nVozLong in 1L..Int.MAX_VALUE.toLong()) {
            "La duración de la voz supera el tamaño PCM admitido."
        }
        val nVoz = nVozLong.toInt()
        val total = maxOf(ins.muestras.size, nVoz)
        require(total > 0) { "No hay muestras PCM para mezclar." }

        // Dos pasadas sobre las muestras, evitando un FloatArray del tamaño
        // completo de la canción: la mezcla se calcula por bloques al escribir.
        fun muestraMezclada(indice: Int): Float {
            var vozActual = 0f
            if (indice < nVoz) {
                val posicion = indice * razon
                val indiceBase = posicion.toInt()
                val fraccion = (posicion - indiceBase).toFloat()
                val a = voz.muestras[indiceBase].toFloat()
                val b = if (indiceBase + 1 < voz.muestras.size) {
                    voz.muestras[indiceBase + 1].toFloat()
                } else {
                    a
                }
                vozActual = a + (b - a) * fraccion
            }
            val instrumentalActual = if (indice < ins.muestras.size) {
                ins.muestras[indice].toFloat()
            } else {
                0f
            }
            return vozActual * GANANCIA_VOZ + instrumentalActual * GANANCIA_INSTRUMENTAL
        }

        var pico = 0f
        for (i in 0 until total) {
            if ((i and 0x3fff) == 0) comprobarCancelacion()
            val absoluto = kotlin.math.abs(muestraMezclada(i))
            if (absoluto > pico) pico = absoluto
        }
        val escala = if (pico > 29000f) 29000f / pico else 1f

        val directorio = archivoSalida.absoluteFile.parentFile ?: File(".")
        check(directorio.isDirectory || directorio.mkdirs()) {
            "No se pudo crear el directorio de salida WAV."
        }
        val temporal = File.createTempFile("bardo_voice_mix_", ".wav.tmp", directorio)
        try {
            AudioUtil.escribirWavMono16(temporal.absolutePath, frec, total) { inicio, cantidad ->
                comprobarCancelacion()
                ShortArray(cantidad) { k ->
                    (muestraMezclada(inicio + k) * escala).toInt().toShort()
                }
            }

            comprobarCancelacion()
            val infoTemporal = WavSegmentDiagnostics.inspect(temporal)
            require(
                infoTemporal.sampleRate == frec &&
                    infoTemporal.channels == 1 &&
                    infoTemporal.bitsPerSample == 16 &&
                    infoTemporal.frames == total.toLong() &&
                    temporal.length() == 44L + total.toLong() * 2L
            ) { "El WAV mezclado no superó la validación previa a su publicación." }

            try {
                Files.move(
                    temporal.toPath(),
                    archivoSalida.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporal.toPath(),
                    archivoSalida.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            val infoFinal = WavSegmentDiagnostics.inspect(archivoSalida)
            require(
                infoFinal.sampleRate == frec &&
                    infoFinal.channels == 1 &&
                    infoFinal.bitsPerSample == 16 &&
                    infoFinal.frames == total.toLong() &&
                    archivoSalida.length() == 44L + total.toLong() * 2L
            ) { "El WAV mezclado publicado no superó la validación final." }

            return Dato.Audio(archivoSalida.absolutePath, frec)
        } finally {
            if (temporal.exists()) temporal.delete()
        }
    }
}
