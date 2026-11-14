package com.musicaia.modulos

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Mezclador WAV PCM básico, no destructivo y sin funciones DAW profesionales. */
object BardoWavMultitrackMixer {
    const val MAX_TRACK_BYTES = 25L * 1024L * 1024L
    const val MAX_TOTAL_INPUT_BYTES = 100L * 1024L * 1024L
    const val TARGET_PEAK_DBFS = -1.0
    private const val BLOCK_FRAMES = 8192
    data class Track(val file: File, val gainDb: Double = 0.0, val pan: Double = 0.0)
    data class Result(val output: File, val sampleRate: Int, val frames: Long, val trackCount: Int,
        val peakBeforeDbfs: Double, val peakAfterDbfs: Double, val normalizationGainDb: Double)
    private data class Pcm(val source: Track, val bytes: ByteArray, val offset: Int,
        val channels: Int, val rate: Int, val frames: Long)

    fun mix(tracks: List<Track>, output: File): Result {
        require(tracks.size in 2..8) { "La mezcla necesita entre 2 y 8 pistas." }
        require(tracks.map { it.file.canonicalFile }.distinct().size == tracks.size) { "Pista duplicada." }
        require(tracks.all { it.gainDb.isFinite() && it.gainDb in -24.0..6.0 }) { "Ganancia fuera de -24 a +6 dB." }
        require(tracks.all { it.pan.isFinite() && it.pan in -1.0..1.0 }) { "Panorama fuera de -1 a +1." }
        require(tracks.none { it.file.canonicalFile == output.canonicalFile }) { "La salida no puede sobrescribir una pista." }
        var total = 0L
        val pcm = tracks.map { track ->
            require(track.file.isFile && track.file.length() in 44..MAX_TRACK_BYTES) { "WAV ausente o superior a 25 MB: " + track.file.name }
            total += track.file.length()
            require(total <= MAX_TOTAL_INPUT_BYTES) { "Las pistas superan 100 MB." }
            parse(track)
        }
        val rate = pcm.first().rate
        require(pcm.all { it.rate == rate }) { "Todas las pistas deben compartir frecuencia de muestreo; no se remuestrea." }
        val frames = pcm.maxOf { it.frames }
        require(frames in 1..(rate.toLong() * 300L)) { "La sesión supera 5 minutos." }
        val dataSize = frames * 4L
        require(dataSize <= Int.MAX_VALUE - 44L) { "El WAV de salida supera el límite RIFF." }
        var peak = 0.0
        var cursor = 0L
        while (cursor < frames) {
            val count = min(BLOCK_FRAMES.toLong(), frames - cursor).toInt()
            render(pcm, cursor, count).forEach { peak = maxOf(peak, abs(it)) }
            cursor += count
        }
        require(peak > 1e-8) { "La mezcla está en silencio; no se genera un archivo vacío." }
        val target = 10.0.pow(TARGET_PEAK_DBFS / 20.0)
        val normalization = min(target / peak, 10.0.pow(12.0 / 20.0))
        val outputDirectory = output.absoluteFile.parentFile ?: File(".")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "No se pudo crear la salida." }
        val temp = File.createTempFile("bardo_multitrack_", ".wav.tmp", outputDirectory)
        var peakAfterLinear = 0.0
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(header(rate, 2, dataSize.toInt()))
                cursor = 0L
                while (cursor < frames) {
                    val count = min(BLOCK_FRAMES.toLong(), frames - cursor).toInt()
                    val samples = render(pcm, cursor, count)
                    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    samples.forEach { sample ->
                        val limited = limitPeak(sample * normalization, target)
                        val pcm16 = (limited * 32768.0).toInt().coerceIn(-32768, 32767)
                        peakAfterLinear = maxOf(peakAfterLinear, abs(pcm16 / 32768.0))
                        bytes.putShort(pcm16.toShort())
                    }
                    stream.write(bytes.array(), 0, bytes.position())
                    cursor += count
                }
                stream.fd.sync()
            }
            require(temp.isFile && temp.length() == 44L + dataSize) { "El WAV generado está truncado." }
            val verified = parse(Track(temp))
            require(verified.channels == 2 && verified.rate == rate && verified.frames == frames) {
                "La cabecera/canales/frecuencia del WAV no superan la verificación."
            }
            try {
                Files.move(
                    temp.toPath(), output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            require(output.isFile && output.length() == 44L + dataSize) {
                "La salida publicada no coincide con el WAV temporal verificado."
            }
            return Result(output, rate, frames, pcm.size, db(peak), db(peakAfterLinear), db(normalization))
        } finally { temp.delete() }
    }

    private fun render(tracks: List<Pcm>, start: Long, count: Int): DoubleArray {
        val out = DoubleArray(count * 2)
        tracks.forEach { t ->
            val gain = 10.0.pow(t.source.gainDb / 20.0)
            val pan = t.source.pan
            val lpan = if (t.channels == 1) cos((pan + 1.0) * Math.PI / 4.0) else if (pan > 0) cos(pan * Math.PI / 2) else 1.0
            val rpan = if (t.channels == 1) sin((pan + 1.0) * Math.PI / 4.0) else if (pan < 0) cos(-pan * Math.PI / 2) else 1.0
            val countAvailable = (t.frames - start).coerceIn(0L, count.toLong()).toInt()
            val frameBytes = t.channels * 2
            repeat(countAvailable) { i ->
                val p = t.offset + ((start + i) * frameBytes).toInt()
                if (t.channels == 1) {
                    val value = sample(t.bytes, p) / 32768.0 * gain
                    out[i * 2] += value * lpan
                    out[i * 2 + 1] += value * rpan
                } else {
                    out[i * 2] += sample(t.bytes, p) / 32768.0 * gain * lpan
                    out[i * 2 + 1] += sample(t.bytes, p + 2) / 32768.0 * gain * rpan
                }
            }
        }
        return out
    }

    private fun parse(track: Track): Pcm {
        // Validación RIFF compartida: tamaño declarado, orden, duplicados,
        // alineación PCM, relleno y límites de cada chunk.
        val header = BardoWavHeaderValidator.inspect(track.file)
        require(header.channels in 1..2 && header.sampleRate in 8_000..96_000) {
            "Canales/frecuencia no compatibles: ${track.file.name}"
        }
        require(header.bitsPerSample == 16) { "Solo se admite WAV PCM16." }
        val align = header.channels * 2
        require(header.dataBytes > 0L && header.dataBytes % align == 0L) {
            "Datos PCM incompletos: ${track.file.name}"
        }
        val frames = header.dataBytes / align
        require(frames in 1..(header.sampleRate.toLong() * 300L)) {
            "La pista supera 5 minutos: ${track.file.name}"
        }
        require(header.dataOffset in 0L..Int.MAX_VALUE.toLong()) {
            "La posición del audio supera los límites admitidos: ${track.file.name}"
        }
        return Pcm(
            source = track,
            bytes = track.file.readBytes(),
            offset = header.dataOffset.toInt(),
            channels = header.channels,
            rate = header.sampleRate,
            frames = frames
        )
    }

    private fun sample(b:ByteArray,p:Int):Int=
        ((b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)).toShort().toInt()

    private fun header(rate:Int,channels:Int,size:Int):ByteArray {
        val align=channels*2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36+size)
            put("WAVE".toByteArray(Charsets.US_ASCII)); put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1.toShort()); putShort(channels.toShort()); putInt(rate); putInt(rate*align)
            putShort(align.toShort()); putShort(16.toShort()); put("data".toByteArray(Charsets.US_ASCII)); putInt(size)
        }.array()
    }
    /**
     * Limitador suave con techo real en TARGET_PEAK_DBFS.
     * Mantiene linealidad por debajo del inicio de rodilla y comprime
     * progresivamente los picos altos sin el recorte duro de PCM16.
     */
    private fun limitPeak(sample: Double, ceiling: Double): Double {
        val magnitude = abs(sample)
        val kneeStart = ceiling * 0.8
        val limited = if (magnitude <= kneeStart) magnitude else {
            ceiling - (ceiling - kneeStart) *
                kotlin.math.exp(-(magnitude - kneeStart) / (ceiling - kneeStart))
        }
        return kotlin.math.sign(sample) * limited
    }

    private fun db(v:Double)=if(v<=1e-12) -120.0 else 20.0*ln(v)/ln(10.0)
}