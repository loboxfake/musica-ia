package com.musicaia.modulos

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.musicaia.modelo.Dato

class AudioPcm(val muestras: ShortArray, val frecuencia: Int)

/**
 * Lector RIFF/WAVE estricto para PCM16. Valida límites de RIFF y de cada chunk
 * antes de acceder a sus bytes; no acepta tamaños truncados ni marcos PCM parciales.
 */
object LectorWav {
    private const val MAX_CHANNELS = 8
    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 384_000

    fun leer(ruta: String): AudioPcm {
        val file = File(ruta)
        require(file.isFile) { "no existe el archivo WAV" }
        require(file.length() >= 44L) { "archivo demasiado pequeño" }

        val bytes = file.readBytes()
        require(bytes.size >= 44) { "archivo demasiado pequeño" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun texto(pos: Int): String = String(bytes, pos, 4, Charsets.US_ASCII)
        fun u16(pos: Int): Int = bb.getShort(pos).toInt() and 0xffff
        fun u32(pos: Int): Long = bb.getInt(pos).toLong() and 0xffffffffL

        require(texto(0) == "RIFF" && texto(8) == "WAVE") {
            "cabecera RIFF/WAVE no válida"
        }

        val riffSize = u32(4)
        require(riffSize >= 4L) { "tamaño RIFF no válido" }
        val riffEndLong = 8L + riffSize
        require(riffEndLong <= bytes.size.toLong() && riffEndLong <= Int.MAX_VALUE.toLong()) {
            "el bloque RIFF está truncado"
        }
        val riffEnd = riffEndLong.toInt()

        var formato = -1
        var canales = 0
        var frecuencia = 0
        var bits = 0
        var blockAlign = 0
        var byteRate = 0L
        var inicioDatos = -1
        var tamDatos = -1

        var pos = 12
        while (pos < riffEnd) {
            require(riffEnd - pos >= 8) { "cabecera de chunk WAV incompleta" }
            val id = texto(pos)
            val tamLong = u32(pos + 4)
            val inicioLong = pos.toLong() + 8L
            val finLong = inicioLong + tamLong
            require(finLong <= riffEndLong && finLong <= Int.MAX_VALUE.toLong()) {
                "chunk WAV truncado o fuera de los límites RIFF: " + id
            }
            val inicio = inicioLong.toInt()

            when (id) {
                "fmt " -> {
                    require(tamLong >= 16L) { "chunk fmt incompleto" }
                    formato = u16(inicio)
                    canales = u16(inicio + 2)
                    frecuencia = u32(inicio + 4).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0
                    byteRate = u32(inicio + 8)
                    blockAlign = u16(inicio + 12)
                    bits = u16(inicio + 14)
                }
                "data" -> if (inicioDatos < 0) {
                    inicioDatos = inicio
                    tamDatos = tamLong.toInt()
                }
            }

            val siguiente = finLong + (tamLong and 1L)
            require(siguiente <= riffEndLong) { "falta el byte de relleno de un chunk WAV" }
            pos = siguiente.toInt()
        }

        require(formato == 1 && bits == 16) {
            "se necesita WAV PCM de 16 bits"
        }
        require(canales in 1..MAX_CHANNELS) { "número de canales no válido" }
        require(frecuencia in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "frecuencia de muestreo no válida" }
        val expectedBlockAlign = canales * 2
        require(blockAlign == expectedBlockAlign) { "alineación PCM no válida" }
        require(byteRate == frecuencia.toLong() * expectedBlockAlign.toLong()) {
            "velocidad de bytes PCM no válida"
        }
        require(inicioDatos >= 0 && tamDatos > 0) { "no se encontró audio PCM dentro del WAV" }
        require(tamDatos % expectedBlockAlign == 0) { "el bloque de audio termina en un marco PCM incompleto" }

        val totalFrames = tamDatos / expectedBlockAlign
        val muestras = ShortArray(totalFrames)
        for (frame in 0 until totalFrames) {
            var suma = 0L
            for (canal in 0 until canales) {
                val sampleOffset = inicioDatos + (frame * canales + canal) * 2
                suma += bb.getShort(sampleOffset).toLong()
            }
            muestras[frame] = (suma / canales).toShort()
        }
        return AudioPcm(muestras, frecuencia)
    }
}

object MezclaVoz {
    private const val GANANCIA_VOZ = 1.0f
    private const val GANANCIA_INSTRUMENTAL = 0.5f

    fun mezclar(rutaVoz: String, rutaInstrumental: String, rutaSalida: String): Dato.Audio {
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
        return Dato.Audio(rutaSalida, frec)
    }
}
